package nl.jan.airco.data

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Poortwachter vóór elk MELCloud-verzoek, zodat de app de API nooit overbelast:
 *  - **Afkoelen na 429:** alle verzoeken (ook /context) wachten tot de server het
 *    weer goed vindt: `Retry-After` als die er is, anders 2 → 4 → 8 → 15 min (±20%).
 *    Eén geslaagd verzoek zet de opbouw terug.
 *  - **Budget:** wat de app zelf doet, hooguit [budget] verzoeken per [windowMs].
 *    Bijzaken (rapporten, verbruik) stoppen al eerder, zodat er ruimte blijft voor
 *    de lijst zelf.
 *  - **Bediening** (aan/uit, modus, ventilator, temperatuur) valt niet onder het
 *    budget: die komt altijd van jou en de temperatuur is al gebundeld. Alleen
 *    tijdens afkoelen na een 429 wacht ook bediening (MELCloud weigert dan toch).
 */
enum class RequestKind { Command, List, Extra }

class RateGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val budget: Int = 30,
    private val extrasBudget: Int = 22,
    private val windowMs: Long = 5 * 60_000L,
) {
    private var cooldownUntil = 0L
    private var backoffStep = 0
    private val recent = ArrayDeque<Long>()

    /** Tot wanneer (ms) alle verzoeken wachten; 0 = niet. */
    @get:Synchronized val blockedUntil: Long get() = if (clock() < cooldownUntil) cooldownUntil else 0L

    /** Gooit als dit verzoek nu niet mag; registreert het anders als verstuurd. */
    @Synchronized
    fun acquire(kind: RequestKind) {
        val now = clock()
        if (now < cooldownUntil) throw RateLimitedException(cooldownUntil)
        while (recent.isNotEmpty() && now - recent.first() >= windowMs) recent.removeFirst()
        val limit = when (kind) {
            RequestKind.Command -> Int.MAX_VALUE // nooit afgeremd door het budget
            RequestKind.List -> budget
            RequestKind.Extra -> extrasBudget
        }
        if (recent.size >= limit) throw BudgetExceededException()
        // Ook bediening telt mee, zodat bijzaken rustiger aan doen als jij druk bezig bent.
        recent.addLast(now)
    }

    @Synchronized
    fun onSuccess() {
        backoffStep = 0
    }

    /** Server gaf 429. [retryAfter] = de header, als die er was. Geeft het eindtijdstip terug. */
    @Synchronized
    fun onRateLimited(retryAfter: String?): Long {
        val now = clock()
        val serverWait = parseRetryAfter(retryAfter, now)
        val wait = serverWait ?: run {
            val base = BACKOFF_MS[backoffStep.coerceAtMost(BACKOFF_MS.lastIndex)]
            (base * (0.8 + 0.4 * random.nextDouble())).toLong()
        }
        backoffStep++
        cooldownUntil = maxOf(cooldownUntil, now + wait)
        return cooldownUntil
    }

    private fun parseRetryAfter(value: String?, now: Long): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { return (it * 1000).coerceIn(1_000L, MAX_WAIT_MS) }
        return try {
            val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - now).coerceIn(1_000L, MAX_WAIT_MS)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(2 * 60_000L, 4 * 60_000L, 8 * 60_000L, 15 * 60_000L)
        const val MAX_WAIT_MS = 60 * 60_000L
    }
}

/** Bijzaak overgeslagen: het verzoekbudget is even op. Stil afhandelen. */
class BudgetExceededException : java.io.IOException("Even rustig aan met verzoeken")
