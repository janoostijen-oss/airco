package nl.jan.airco.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/** Pauzes tussen herhaalpogingen: snel eerst (vaak een hapering), daarna rustiger. */
val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 12_000L)

/**
 * Is deze fout de moeite van opnieuw proberen waard?
 *  - Ja: netwerkfouten, time-outs, serverfouten (5xx), 408, en "budget even op".
 *  - Nee: MELCloud vraagt om rust (429: de poortwachter wacht al), niet ingelogd,
 *    of een vraag die de server afwijst (4xx) — opnieuw vragen helpt dan niet.
 */
fun isRetryable(e: Throwable): Boolean = when (e) {
    is RateLimitedException -> false
    is NotLoggedInException -> false
    is HttpStatusException -> e.code >= 500 || e.code == 408
    is BudgetExceededException -> true
    is IOException -> true
    else -> false
}

/**
 * Haalt [count] onderdelen op (per unit); mislukte onderdelen worden met pauzes
 * opnieuw geprobeerd, gelukte niet opnieuw gevraagd. Geeft per onderdeel het
 * resultaat, of `null` als dat onderdeel na alle pogingen (of bij een fout die
 * geen herhaling verdient) niet lukte. [onRetry] meldt een nieuwe poging.
 */
suspend fun <T> fetchEachWithRetry(
    count: Int,
    delaysMs: List<Long> = RETRY_DELAYS_MS,
    gapMs: Long = 0L,
    onRetry: (attempt: Int) -> Unit = {},
    fetch: suspend (index: Int) -> T,
): List<T?> {
    val results = arrayOfNulls<Any?>(count)
    val done = BooleanArray(count)
    val hopeless = BooleanArray(count)
    for (attempt in 0..delaysMs.size) {
        if (done.indices.all { done[it] || hopeless[it] }) break
        if (attempt > 0) {
            onRetry(attempt)
            delay(delaysMs[attempt - 1])
        }
        var first = true
        for (i in 0 until count) {
            if (done[i] || hopeless[i]) continue
            if (!first && gapMs > 0) delay(gapMs)
            first = false
            try {
                results[i] = fetch(i)
                done[i] = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isRetryable(e)) hopeless[i] = true
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    return results.toList() as List<T?>
}

/** Zoals [fetchEachWithRetry], maar alles-of-niets: `null` als één onderdeel niet lukte. */
suspend fun <T : Any> fetchAllWithRetry(
    count: Int,
    delaysMs: List<Long> = RETRY_DELAYS_MS,
    gapMs: Long = 0L,
    onRetry: (attempt: Int) -> Unit = {},
    fetch: suspend (index: Int) -> T,
): List<T>? {
    val r = fetchEachWithRetry(count, delaysMs, gapMs, onRetry, fetch)
    return if (r.all { it != null }) r.filterNotNull() else null
}
