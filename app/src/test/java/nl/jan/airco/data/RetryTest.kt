package nl.jan.airco.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Opnieuw proberen: alleen wat mislukte, alleen bij zinvolle fouten. */
class RetryTest {
    private val fast = listOf(1L, 1L, 1L)

    @Test
    fun mislukteUnitWordtOpnieuwGeprobeerdGeslaagdeNiet() = runBlocking {
        val calls = IntArray(3)
        var retries = 0
        val r = fetchAllWithRetry(3, delaysMs = fast, onRetry = { retries++ }) { i ->
            calls[i]++
            if (i == 1 && calls[i] < 3) throw SocketTimeoutException("time-out")
            "unit$i"
        }
        assertEquals(listOf("unit0", "unit1", "unit2"), r)
        assertEquals(listOf(1, 3, 1), calls.toList()) // alleen unit 1 opnieuw
        assertEquals(2, retries)
    }

    @Test
    fun naAllePogingenNull() = runBlocking {
        var calls = 0
        val r = fetchAllWithRetry(1, delaysMs = fast) { calls++; throw IOException("weg") }
        assertNull(r)
        assertEquals(4, calls) // 1 + 3 herhalingen
    }

    @Test
    fun geenHerhalingBijRustVerzoekOfAfwijzing() = runBlocking {
        var calls = 0
        assertNull(fetchAllWithRetry(1, delaysMs = fast) { calls++; throw RateLimitedException() })
        assertEquals(1, calls)
        calls = 0
        assertNull(fetchAllWithRetry(1, delaysMs = fast) { calls++; throw HttpStatusException(404) })
        assertEquals(1, calls)
    }

    @Test
    fun serverfoutWelOpnieuw() {
        assertTrue(isRetryable(HttpStatusException(503)))
        assertTrue(isRetryable(SocketTimeoutException()))
        assertTrue(isRetryable(BudgetExceededException()))
        assertFalse(isRetryable(HttpStatusException(400)))
        assertFalse(isRetryable(NotLoggedInException()))
    }

    @Test
    fun perOnderdeelHoudtGeslaagdeOok() = runBlocking {
        val r = fetchEachWithRetry(3, delaysMs = fast) { i -> if (i == 2) throw IOException() else i * 10 }
        assertEquals(listOf(0, 10, null), r)
    }
}
