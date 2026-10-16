package nl.jan.airco.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** MELCloud weigert e-mail/wachtwoord. */
class BadCredentialsException(message: String) : Exception(message)

/** De loginpagina ziet er anders uit dan verwacht (Mitsubishi heeft iets veranderd). */
class LoginFlowException(message: String) : IOException(message)

class AuthHttpException(val code: Int, path: String) : IOException("Login-server gaf $code op $path")

/**
 * MELCloud Home login: OIDC authorization-code flow met PKCE, zoals de
 * officiële app (client "homemobile"). Geport uit
 * OlivierZal/melcloud-api `src/api/token-auth.ts`.
 *
 * Twee manieren om aan een autorisatiecode te komen:
 *  - [loginWithPassword]: headless, vult het Cognito-formulier zelf in
 *    (zoals de library). Hiermee kan de app automatisch opnieuw inloggen.
 *  - [startLogin] + WebView: de echte loginpagina tonen. Reserve-route.
 */
object Auth {
    const val CLIENT_ID = "homemobile"
    const val REDIRECT_URI = "melcloudhome://"
    private const val SCOPES = "openid profile email offline_access IdentityServerApi"
    private const val AUTH_BASIC = "Basic aG9tZW1vYmlsZTo=" // "homemobile:"
    private const val AUTH_BASE_URL = "https://auth.melcloudhome.com"
    private const val COGNITO_AUTHORITY = "https://live-melcloudhome.auth.eu-west-1.amazoncognito.com"
    private const val MAX_REDIRECTS = 20

    // Dezelfde client als de API (gedeelde pool en threads), pas bij gebruik aangemaakt.
    private val http: OkHttpClient get() = Http.client
    private val random = SecureRandom()

    /** Alles wat de WebView nodig heeft, plus de verifier voor de token-exchange. */
    data class LoginRequest(val authorizeUrl: String, val verifier: String)

    // ---------------------------------------------------------------- PKCE / PAR

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes).also(random::nextBytes)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun postForm(path: String, fields: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(AUTH_BASE_URL + path)
            .header("Authorization", AUTH_BASIC)
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw AuthHttpException(response.code, path)
            return JSONObject(text)
        }
    }

    /** Pushed Authorization Request → authorize-URL + bijbehorende verifier (blocking). */
    private fun pushAuthorizationRequest(): LoginRequest {
        val verifier = randomUrlSafe(32)
        val json = postForm(
            "/connect/par",
            mapOf(
                "client_id" to CLIENT_ID,
                "code_challenge" to challengeFor(verifier),
                "code_challenge_method" to "S256",
                "redirect_uri" to REDIRECT_URI,
                "response_type" to "code",
                "scope" to SCOPES,
                "state" to randomUrlSafe(16),
            ),
        )
        val requestUri = json.getString("request_uri")
        val url = "$AUTH_BASE_URL/connect/authorize?client_id=$CLIENT_ID" +
            "&request_uri=" + URLEncoder.encode(requestUri, "UTF-8")
        return LoginRequest(url, verifier)
    }

    private fun exchangeCodeBlocking(code: String, verifier: String): Tokens = Tokens.fromJson(
        postForm(
            "/connect/token",
            mapOf(
                "client_id" to CLIENT_ID,
                "code" to code,
                "code_verifier" to verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT_URI,
            ),
        ),
        previousRefreshToken = null,
    )

    // ---------------------------------------------------------------- WebView-route

    suspend fun startLogin(): LoginRequest = withContext(Dispatchers.IO) { pushAuthorizationRequest() }

    suspend fun exchangeCode(code: String, verifier: String): Tokens =
        withContext(Dispatchers.IO) { exchangeCodeBlocking(code, verifier) }

    // ---------------------------------------------------------------- Headless route

    /**
     * Volledige headless login met e-mail en wachtwoord:
     * PAR → authorize (redirects naar Cognito) → formulier posten →
     * callback-redirects volgen tot `melcloudhome://?code=…` → tokens.
     */
    suspend fun loginWithPassword(username: String, password: String): Tokens = withContext(Dispatchers.IO) {
        val request = pushAuthorizationRequest()
        // Eigen client per login: verse cookies, en redirects zelf afhandelen
        // (de keten springt tussen auth.melcloudhome.com en Cognito heen en weer).
        val client = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(MemoryCookieJar())
            .build()

        val (_, loginHtml) = followRedirects(client, request.authorizeUrl)
        val action = extractFormAction(loginHtml)
            ?: throw LoginFlowException("Loginformulier niet gevonden op de MELCloud-pagina")

        val form = FormBody.Builder().apply {
            extractHiddenFields(loginHtml).forEach { (k, v) -> add(k, v) }
            add("cognitoAsfData", "")
            add("username", username)
            add("password", password)
        }.build()
        val (submitCode, submitLocation, submitBody) = client.newCall(
            Request.Builder().url(action).post(form).build(),
        ).execute().use { r -> Triple(r.code, r.header("Location"), r.body?.string().orEmpty()) }

        if (submitCode !in 300..399 || submitLocation.isNullOrEmpty()) {
            // Cognito toont de loginpagina opnieuw met een foutmelding = gegevens geweigerd.
            val reason = Regex("""(?:id|class)="errorMessage[^"]*"[^>]*>([^<]*)<""")
                .find(submitBody)?.groupValues?.get(1)?.trim().orEmpty()
            throw BadCredentialsException(reason.ifEmpty { "E-mail of wachtwoord onjuist" })
        }

        val (finalUrl, _) = followRedirects(client, resolve(action, submitLocation))
        val params = queryParams(finalUrl)
        val code = params["code"]
            ?: throw LoginFlowException(
                "Geen autorisatiecode ontvangen" + (params["error_description"] ?: params["error"])
                    ?.let { " ($it)" }.orEmpty(),
            )
        exchangeCodeBlocking(code, request.verifier)
    }

    /** Volgt HTTP- én JavaScript/meta-redirects tot een gewone pagina of `melcloudhome://`. */
    private fun followRedirects(client: OkHttpClient, start: String): Pair<String, String> {
        var url = start
        repeat(MAX_REDIRECTS) {
            if (url.startsWith(REDIRECT_URI)) return url to ""
            val (code, location, body) = client.newCall(Request.Builder().url(url).get().build())
                .execute().use { r -> Triple(r.code, r.header("Location"), r.body?.string().orEmpty()) }
            val next = when {
                code in 300..399 && !location.isNullOrEmpty() -> location
                else -> extractPageRedirect(body)
            } ?: return url to body
            url = resolve(url, next)
        }
        throw LoginFlowException("Te veel doorverwijzingen tijdens het inloggen")
    }

    private fun resolve(base: String, location: String): String = when {
        location.startsWith(REDIRECT_URI) || location.startsWith("http") -> location
        else -> base.toHttpUrl().resolve(location)?.toString()
            ?: throw LoginFlowException("Ongeldige doorverwijzing: $location")
    }

    private fun extractFormAction(html: String): String? {
        val raw = Regex("""<form[^>]+action="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: return null
        val action = raw.replace("&amp;", "&")
        return if (action.startsWith("/")) COGNITO_AUTHORITY + action else action
    }

    private fun extractHiddenFields(html: String): Map<String, String> =
        Regex("""<input[^>]+type="hidden"[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { m ->
            val tag = m.value
            val name = Regex("""name="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
            val value = Regex("""value="([^"]*)"""").find(tag)?.groupValues?.get(1).orEmpty()
            name to value
        }.toMap()

    private fun extractPageRedirect(html: String): String? {
        val js = Regex("""window\.location\s*=\s*['"]([^'"]+)""").find(html)?.groupValues?.get(1)
        val meta = Regex("""<meta[^>]+http-equiv="refresh"[^>]+content="[^"]*url=([^"]+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        return (js ?: meta)?.replace("&amp;", "&")
    }

    private fun queryParams(url: String): Map<String, String> =
        url.substringAfter('?', "").substringBefore('#').split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    // ---------------------------------------------------------------- Refresh

    /**
     * Nieuwe access token via de refresh token. `null` = de server weigert
     * hem; netwerkfouten worden gewoon gegooid, zodat je bij slechte wifi
     * niet uitgelogd raakt.
     */
    suspend fun refresh(refreshToken: String): Tokens? = withContext(Dispatchers.IO) {
        try {
            Tokens.fromJson(
                postForm(
                    "/connect/token",
                    mapOf(
                        "client_id" to CLIENT_ID,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken,
                    ),
                ),
                previousRefreshToken = refreshToken,
            )
        } catch (e: AuthHttpException) {
            if (e.code in 400..499 && e.code != 429) null else throw e
        }
    }
}

/** Simpele cookie-opslag voor één loginpoging (meerdere domeinen). */
private class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            this.cookies.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }
}

data class Tokens(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long) {
    companion object {
        fun fromJson(json: JSONObject, previousRefreshToken: String?): Tokens {
            val expiresIn = json.optLong("expires_in", 3600L)
            // Server stuurt soms geen nieuwe refresh token mee; dan de oude houden.
            val refresh = json.optString("refresh_token").ifEmpty { previousRefreshToken.orEmpty() }
            return Tokens(
                accessToken = json.getString("access_token"),
                refreshToken = refresh,
                expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000,
            )
        }
    }
}
