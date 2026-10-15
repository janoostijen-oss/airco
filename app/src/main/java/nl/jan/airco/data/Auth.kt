package nl.jan.airco.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MELCloud Home login: OIDC authorization-code flow met PKCE, zoals de
 * officiële app (client "homemobile"). Overgenomen uit
 * OlivierZal/melcloud-api `src/api/token-auth.ts`.
 *
 * Verschil met de Node-library: die vult het Cognito-loginformulier
 * headless in. Wij tonen de echte loginpagina in een WebView en vangen
 * de `melcloudhome://?code=…` redirect af. Je wachtwoord komt zo nooit
 * in deze app terecht; alleen de tokens worden bewaard.
 */
object Auth {
    const val CLIENT_ID = "homemobile"
    const val REDIRECT_URI = "melcloudhome://"
    private const val SCOPES = "openid profile email offline_access IdentityServerApi"
    private const val AUTH_BASIC = "Basic aG9tZW1vYmlsZTo=" // "homemobile:"
    private const val AUTH_BASE_URL = "https://auth.melcloudhome.com"

    private val http = OkHttpClient()
    private val random = SecureRandom()

    /** Alles wat de WebView nodig heeft, plus de verifier voor de token-exchange. */
    data class LoginRequest(val authorizeUrl: String, val verifier: String)

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
            if (!response.isSuccessful) {
                throw AuthHttpException(response.code, path)
            }
            return JSONObject(text)
        }
    }

    /** Stap 1: Pushed Authorization Request → URL om in de WebView te openen. */
    suspend fun startLogin(): LoginRequest = withContext(Dispatchers.IO) {
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
        LoginRequest(url, verifier)
    }

    /** Stap 2: de code uit de redirect inwisselen voor tokens. */
    suspend fun exchangeCode(code: String, verifier: String): Tokens = withContext(Dispatchers.IO) {
        Tokens.fromJson(
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
    }

    /**
     * Nieuwe access token via de refresh token. `null` = de server weigert
     * hem (opnieuw inloggen nodig); netwerkfouten worden gewoon gegooid,
     * zodat je bij slechte wifi niet uitgelogd raakt.
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

class AuthHttpException(val code: Int, path: String) : IOException("Login-server gaf $code op $path")

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
