package nl.jan.airco

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wanneer de bijzaken (buiten, verbruik) opnieuw opgehaald worden. */
class FreshnessTest {
    private val min = 60_000L

    @Test fun nooitOpgehaaldDusNu() = assertTrue(Freshness.due(10 * min, 0, 0, Freshness.AUTO_MS))
    @Test fun vanzelfPasNa10Minuten() {
        assertFalse(Freshness.due(now = 9 * min, fetchedAt = 0, attemptAt = 0, maxAgeMs = Freshness.AUTO_MS))
        assertTrue(Freshness.due(now = 10 * min, fetchedAt = 0, attemptAt = 0, maxAgeMs = Freshness.AUTO_MS))
    }
    @Test fun tikOfTerugkomenNa3Minuten() {
        assertFalse(Freshness.due(now = 2 * min, fetchedAt = 0, attemptAt = 0, maxAgeMs = Freshness.RESUME_OR_TAP_MS))
        assertTrue(Freshness.due(now = 3 * min, fetchedAt = 0, attemptAt = 0, maxAgeMs = Freshness.RESUME_OR_TAP_MS))
    }
    @Test fun nooitVakerDanEensPerMinuutOokBijZelfVernieuwen() {
        // Zelf vernieuwen = maxAge 0, maar 30 s na de vorige poging: overslaan.
        assertFalse(Freshness.due(now = 100 * min + 30_000, fetchedAt = 0, attemptAt = 100 * min, maxAgeMs = 0))
        assertTrue(Freshness.due(now = 101 * min, fetchedAt = 0, attemptAt = 100 * min, maxAgeMs = 0))
    }

    @Test fun terugkomenAltijdBehalveVlakNaVorigeKeer() {
        val r = Freshness.RESUME_MIN_INTERVAL_MS
        // Data 40 s oud: bij terugkomen tóch vernieuwen (maxAge 0, minimaal 30 s).
        assertTrue(Freshness.due(now = 40_000, fetchedAt = 0, attemptAt = 0, maxAgeMs = 0, minIntervalMs = r))
        // Net (10 s geleden) al gedaan, bv. snel heen en weer wisselen: overslaan.
        assertFalse(Freshness.due(now = 10_000, fetchedAt = 0, attemptAt = 0, maxAgeMs = 0, minIntervalMs = r))
    }
}
