package nl.jan.airco.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Eén HTTP-client voor de hele app (één connectiepool, één set threads).
 * Lui aangemaakt: het opzetten van TLS kost tijd en hoort niet in de opstart
 * op de UI-thread, maar bij het eerste verzoek.
 */
internal object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** Statuscode + body van een afgerond verzoek. */
internal data class HttpResult(val code: Int, val body: String, val retryAfter: String? = null)

/**
 * Voert het verzoek uit zonder een thread te blokkeren. Wordt de coroutine
 * geannuleerd (bijv. een nieuwere refresh), dan wordt het verzoek zelf ook
 * afgebroken in plaats van op de achtergrond af te lopen.
 */
internal suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { HttpResult(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
            } catch (e: IOException) {
                cont.resumeWithException(e)
                return
            }
            cont.resume(result)
        }
    })
}
