package nl.jan.airco.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Poortwachter: afkoelen na 429, opbouw, en het verzoekbudget. */
class RateGuardTest {
    private var now = 1_000_000L
    private val noJitter = object : Random() { override fun nextBits(bitCount: Int) = 0; override fun nextDouble() = 0.5 }
    private fun guard(budget: Int = 30, extras: Int = 22) =
        RateGuard(clock = { now }, random = noJitter, budget = budget, extrasBudget = extras)

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try { block(); fail("verwacht ${T::class.simpleName}") } catch (e: Throwable) { if (e !is T) throw e }
    }

    @Test
    fun retryAfterInSecondenWordtGevolgd() {
        val g = guard()
        val until = g.onRateLimited("120")
        assertEquals(now + 120_000, until)
        now += 119_000
        assertThrows<RateLimitedException> { g.acquire(essential = true) } // ook /context wacht
        now += 1_000
        g.acquire(essential = true)
    }

    @Test
    fun zonderRetryAfterOpbouw2_4_8_15Minuten() {
        val g = guard()
        val waits = (1..5).map { val start = now; val u = g.onRateLimited(null); now = u; u - start }
        assertEquals(listOf(2L, 4L, 8L, 15L, 15L).map { it * 60_000 }, waits)
    }

    @Test
    fun succesZetOpbouwTerug() {
        val g = guard()
        now = g.onRateLimited(null); now = g.onRateLimited(null) // 2, 4 min
        g.acquire(true); g.onSuccess()
        val start = now
        assertEquals(2 * 60_000L, g.onRateLimited(null) - start)
    }

    @Test
    fun bijzakenStoppenEerderDanDeLijst() {
        val g = guard(budget = 5, extras = 3)
        repeat(3) { g.acquire(essential = false) }
        assertThrows<BudgetExceededException> { g.acquire(essential = false) }
        g.acquire(essential = true) // de lijst en jouw bediening mogen nog
        g.acquire(essential = true)
        assertThrows<BudgetExceededException> { g.acquire(essential = true) }
        now += 5 * 60_000 // venster voorbij
        g.acquire(essential = false)
    }

    @Test
    fun retryAfterAlsDatum() {
        now = java.time.Instant.parse("2026-10-17T08:00:00Z").toEpochMilli()
        val until = guard().onRateLimited("Sat, 17 Oct 2026 08:05:00 GMT")
        assertEquals(5 * 60_000L, until - now)
        assertTrue(true)
    }
}
