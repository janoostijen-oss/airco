package nl.jan.airco

import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.jan.airco.data.Reading
import nl.jan.airco.data.ReportParser
import nl.jan.airco.data.outdoorTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Trendpijltjes: buiten nu tegenover een half uur geleden, gemiddeld over de buitenunits. */
@RunWith(AndroidJUnit4::class) // org.json in de parser-test
class OutdoorTrendTest {
    private val now = Instant.parse("2026-10-16T18:00:00Z")
    private fun r(t: String, v: Double) = Reading(Instant.parse(t), v)

    @Test
    fun warmerGeeftOmhoog() {
        val s = listOf(r("2026-10-16T17:10:00Z", 8.0), r("2026-10-16T17:50:00Z", 9.0))
        assertEquals(1, outdoorTrend(listOf(s), now))
    }

    @Test
    fun kouderGeeftOmlaag() {
        val s = listOf(r("2026-10-16T17:20:00Z", 9.0), r("2026-10-16T17:45:00Z", 8.5))
        assertEquals(-1, outdoorTrend(listOf(s), now))
    }

    @Test
    fun gelijkOfKleinVerschilGeeftNul() {
        val gelijk = listOf(r("2026-10-16T17:00:00Z", 8.5), r("2026-10-16T17:55:00Z", 8.5))
        assertEquals(0, outdoorTrend(listOf(gelijk), now))
        val klein = listOf(r("2026-10-16T17:00:00Z", 8.50), r("2026-10-16T17:55:00Z", 8.55))
        assertEquals(0, outdoorTrend(listOf(klein), now))
    }

    @Test
    fun geenMetingSindsHalfUurIsGelijk() {
        // Laatste meting van vóór het half uur: nu en toen dezelfde waarde.
        val s = listOf(r("2026-10-16T16:00:00Z", 7.0), r("2026-10-16T17:00:00Z", 8.0))
        assertEquals(0, outdoorTrend(listOf(s), now))
    }

    @Test
    fun gemiddeldOverBuitenunits() {
        val a = listOf(r("2026-10-16T17:00:00Z", 8.0), r("2026-10-16T17:50:00Z", 9.0)) // +1
        val b = listOf(r("2026-10-16T17:00:00Z", 10.0), r("2026-10-16T17:50:00Z", 9.5)) // -0,5
        assertEquals(1, outdoorTrend(listOf(a, b), now)) // gemiddeld +0,25
    }

    @Test
    fun teWeinigDataIsOnbekend() {
        // Alleen metingen van de laatste 10 minuten: geen waarde van een half uur geleden.
        assertNull(outdoorTrend(listOf(listOf(r("2026-10-16T17:55:00Z", 9.0))), now))
        assertNull(outdoorTrend(emptyList(), now))
    }

    @Test
    fun parserLeestReeksEnVorigeMeting() {
        val body = """{"datasets":[{"id":"outside_temperature","data":[
            {"x":"2026-10-16 17:40:00.0000000","y":9.0},{"x":"2026-10-16T17:05:00","y":8.0},{"x":"2026-10-16 17:50:00","y":null}]}],
            "previousTriggers":[{"measure":"outside_temperature","trigger":"2026-10-15 23:00:00","value":7.5},
                                {"measure":"room_temperature","trigger":"2026-10-15 23:00:00","value":20.0}]}"""
        val s = ReportParser.outdoorSeries(body)
        assertEquals(listOf(7.5, 8.0, 9.0), s.map { it.value })
        assertEquals(Instant.parse("2026-10-16T17:40:00Z"), s.last().time)
        assertEquals(1, outdoorTrend(listOf(s), now))
    }

    @Test
    fun geenMetingSentinelWordtGenegeerd() {
        val body = """{"datasets":[],"previousTriggers":[{"measure":"outside_temperature","trigger":"9999-12-31T23:59:59","value":5.0}]}"""
        assertEquals(emptyList<Reading>(), ReportParser.outdoorSeries(body))
    }
}
