package nl.jan.airco.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotLoggedInException : Exception("Niet (meer) ingelogd")
class RateLimitedException : IOException("MELCloud vraagt even rustig aan te doen (te veel verzoeken). Probeer het later opnieuw.")

/**
 * Minimale MELCloud Home client voor airco's (ATA), naar het voorbeeld van
 * OlivierZal/melcloud-api `src/api/home.ts`:
 *  - GET  /context                  → gebouwen + units + actuele settings
 *  - PUT  /monitor/ataunit/{id}     → partiële update (power, setTemperature, …)
 */
class MelcloudApi(private val store: TokenStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val refreshLock = Mutex()
    private val json = "application/json; charset=utf-8".toMediaType()

    val isLoggedIn: Boolean get() = store.load() != null

    fun saveTokens(tokens: Tokens) = store.save(tokens)
    fun logout() = store.clear()

    suspend fun units(): List<AircoUnit> {
        val body = send { Request.Builder().url("$BASE_URL/context").get() }
        // Parsen buiten de UI-thread, zodat een refresh tijdens scrollen niet hapert.
        return withContext(Dispatchers.Default) { ContextParser.parse(JSONObject(body)) }
    }

    suspend fun update(unitId: String, values: Map<String, Any>) {
        val payload = JSONObject(values).toString().toRequestBody(json)
        send { Request.Builder().url("$BASE_URL/monitor/ataunit/$unitId").put(payload) }
    }

    /** Verstuurt met Bearer-token; ververst vooraf als hij bijna verloopt en één keer bij een 401. */
    private suspend fun send(build: () -> Request.Builder): String {
        var token = validAccessToken(forceRefresh = false)
        repeat(2) { attempt ->
            val result = withContext(Dispatchers.IO) {
                http.newCall(build().header("Authorization", "Bearer $token").build()).execute().use { r ->
                    r.code to r.body?.string().orEmpty()
                }
            }
            val (code, text) = result
            when {
                code in 200..299 -> return text
                code == 401 && attempt == 0 -> token = validAccessToken(forceRefresh = true)
                code == 401 -> throw NotLoggedInException()
                code == 429 -> throw RateLimitedException()
                else -> throw IOException("MELCloud gaf foutcode $code")
            }
        }
        throw NotLoggedInException()
    }

    private suspend fun validAccessToken(forceRefresh: Boolean): String = refreshLock.withLock {
        val current = store.load() ?: throw NotLoggedInException()
        val almostExpired = current.expiresAtMillis - System.currentTimeMillis() < REFRESH_AHEAD_MS
        if (!forceRefresh && !almostExpired && current.accessToken.isNotEmpty()) {
            return current.accessToken
        }
        val fresh = Auth.refresh(current.refreshToken)
        if (fresh == null) {
            store.clear()
            throw NotLoggedInException()
        }
        store.save(fresh)
        fresh.accessToken
    }

    private companion object {
        const val BASE_URL = "https://mobile.bff.melcloudhome.com"
        const val REFRESH_AHEAD_MS = 5 * 60 * 1000L
    }
}
