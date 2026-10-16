package nl.jan.airco.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class NotLoggedInException(
    message: String = "Log opnieuw in",
    /** True als de automatische login technisch faalde (loginpagina veranderd). */
    val loginFlowBroken: Boolean = false,
) : Exception(message)
class HttpStatusException(val code: Int) : IOException("MELCloud gaf foutcode $code")
class RateLimitedException : IOException("MELCloud vraagt even rustig aan te doen (te veel verzoeken). Probeer het later opnieuw.")

/**
 * Minimale MELCloud Home client voor airco's (ATA), naar het voorbeeld van
 * OlivierZal/melcloud-api `src/api/home.ts`:
 *  - GET  /context                  → gebouwen + units + actuele settings
 *  - PUT  /monitor/ataunit/{id}     → partiële update (power, setTemperature, …)
 *
 * Sessiebeheer, in volgorde: access token (vooraf ververst) → refresh token →
 * stil opnieuw inloggen met de opgeslagen e-mail/wachtwoord. Pas als ook dat
 * geweigerd wordt, krijg je het loginscherm.
 */
class MelcloudApi(private val tokens: TokenStore, private val credentials: CredentialStore) {
    private val authLock = Mutex()
    private val json = "application/json; charset=utf-8".toMediaType()

    val isLoggedIn: Boolean get() = tokens.load() != null || credentials.exists()

    /** Inloggen vanuit het formulier; bij succes worden tokens én gegevens bewaard. */
    suspend fun login(username: String, password: String) {
        val fresh = Auth.loginWithPassword(username, password)
        // Versleutelen (en de eerste keer de sleutel aanmaken) via de Keystore kan
        // tientallen ms duren: niet op de UI-thread.
        withContext(Dispatchers.IO) {
            tokens.save(fresh)
            credentials.save(CredentialStore.Credentials(username, password))
        }
    }

    /** Inloggen via de reserve-WebView: alleen tokens, geen wachtwoord bekend. */
    fun saveTokens(fresh: Tokens) = tokens.save(fresh)

    fun logout() {
        tokens.clear()
        credentials.clear()
    }

    suspend fun units(): List<AircoUnit> {
        val body = try {
            send { Request.Builder().url("$BASE_URL/context").get() }
        } catch (e: HttpStatusException) {
            // 404 op /context = token is geldig, maar dit account heeft geen MELCloud Home-woning
            // (zie melcloud-api `#requestContext`). Vaak: ingelogd met een ander e-mailadres.
            if (e.code == 404) {
                throw IOException(
                    "Dit MELCloud Home-account heeft geen woning of apparaten (404). " +
                        "Ben je met hetzelfde e-mailadres ingelogd als in de officiële app?",
                )
            }
            throw e
        }
        // Parsen buiten de UI-thread, zodat een refresh tijdens scrollen niet hapert.
        return withContext(Dispatchers.Default) { ContextParser.parse(JSONObject(body)) }
    }

    /**
     * Meest recente buitentemperatuur die deze unit meldt (van zijn buitenunit),
     * uit het trendrapport van de laatste 24 uur. `null` als er niets is.
     */
    suspend fun latestOutdoorReading(unitId: String): Reading? {
        val fmt = REPORT_DATE
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val url = "$BASE_URL/report/v1/trendsummary".toHttpUrl().newBuilder()
            .addQueryParameter("unitId", unitId)
            .addQueryParameter("from", fmt.format(now.minus(24, ChronoUnit.HOURS)))
            .addQueryParameter("to", fmt.format(now))
            .addQueryParameter("period", "Hourly")
            .build()
        val body = send { Request.Builder().url(url).get() }
        return withContext(Dispatchers.Default) { ReportParser.latestOutdoor(body) }
    }

    suspend fun update(unitId: String, values: Map<String, Any>) {
        val payload = JSONObject(values).toString().toRequestBody(json)
        send { Request.Builder().url("$BASE_URL/monitor/ataunit/$unitId").put(payload) }
    }

    /**
     * Verstuurt met Bearer-token; bij een 401 eenmaal sessie herstellen en opnieuw proberen.
     * Alles op IO: ook het token-beheer, want stil opnieuw inloggen ontsleutelt het
     * wachtwoord via de Keystore. Annuleren breekt het lopende verzoek echt af.
     */
    private suspend fun send(build: () -> Request.Builder): String = withContext(Dispatchers.IO) {
        var token = validAccessToken(forceRefresh = false)
        repeat(2) { attempt ->
            val (code, text) = Http.client.newCall(build().header("Authorization", "Bearer $token").build()).await()
            when {
                code in 200..299 -> return@withContext text
                code == 401 && attempt == 0 -> token = validAccessToken(forceRefresh = true)
                code == 401 -> throw NotLoggedInException()
                code == 429 -> throw RateLimitedException()
                else -> throw HttpStatusException(code)
            }
        }
        throw NotLoggedInException()
    }

    private suspend fun validAccessToken(forceRefresh: Boolean): String = authLock.withLock {
        val current = tokens.load() ?: return@withLock reLogin()
        val almostExpired = current.expiresAtMillis - System.currentTimeMillis() < REFRESH_AHEAD_MS
        if (!forceRefresh && !almostExpired && current.accessToken.isNotEmpty()) {
            return@withLock current.accessToken
        }
        val fresh = Auth.refresh(current.refreshToken) ?: return@withLock reLogin()
        tokens.save(fresh)
        fresh.accessToken
    }

    /**
     * Sessie verlopen: stil opnieuw inloggen met de bewaarde gegevens.
     * Netwerkfouten worden doorgegeven (je blijft "ingelogd" en kunt het
     * straks opnieuw proberen); alleen een echte weigering stuurt je naar
     * het loginscherm.
     */
    private suspend fun reLogin(): String {
        tokens.clear()
        val saved = credentials.load() ?: throw NotLoggedInException()
        return try {
            val fresh = Auth.loginWithPassword(saved.username, saved.password)
            tokens.save(fresh)
            fresh.accessToken
        } catch (e: BadCredentialsException) {
            // Wachtwoord gewijzigd? Dan niet blijven proberen met het oude.
            credentials.clear()
            throw NotLoggedInException("Je wachtwoord werd geweigerd. Log opnieuw in.")
        } catch (e: LoginFlowException) {
            throw NotLoggedInException("Automatisch opnieuw inloggen lukte niet: ${e.message}", loginFlowBroken = true)
        }
    }

    private companion object {
        const val BASE_URL = "https://mobile.bff.melcloudhome.com"
        const val REFRESH_AHEAD_MS = 5 * 60 * 1000L

        /**
         * Het rapport wil .NET-stijl UTC-tijden met 7 nullen achter de seconde
         * (anders geeft het een leeg venster; zie melcloud-api `toReportDate`).
         * Thread-safe, dus één keer aanmaken.
         */
        val REPORT_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS").withZone(ZoneOffset.UTC)
    }
}
