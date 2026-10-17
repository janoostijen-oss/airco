package nl.jan.airco.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Verbruik gisteren lokaal bewaren: wanneer wel, wanneer niet, en per dag. */
@RunWith(AndroidJUnit4::class)
class EnergyCacheTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 17, h, m, 0, 0, zone)

    @Test fun opslaanNa0100MetGeldigeWaarde() = assertTrue(EnergyCache.shouldPersist(11.0, at(16, 53)))
    @Test fun nietVoor0100() = assertFalse(EnergyCache.shouldPersist(11.0, at(0, 30)))
    @Test fun nietBijNulOfOnbekend() {
        assertFalse(EnergyCache.shouldPersist(0.0, at(16, 53)))
        assertFalse(EnergyCache.shouldPersist(null, at(16, 53)))
    }

    @Test
    fun geldigheidInHetGeheugen() {
        // Vóór 01:00 opgehaald: tot 01:00, dan nog één keer definitief.
        assertEquals(at(1, 0), EnergyCache.validUntil(at(0, 20), zone))
        // Daarna: tot middernacht.
        assertEquals(ZonedDateTime.of(2026, 10, 18, 0, 0, 0, 0, zone), EnergyCache.validUntil(at(16, 53), zone))
    }

    @Test
    fun bewaardPerDag() {
        val cache = EnergyCache(ApplicationProvider.getApplicationContext())
        val day = LocalDate.of(2026, 10, 16)
        cache.save(day, 11.04)
        assertEquals(11.04, cache.load(day)!!, 1e-9)
        assertNull("andere dag: niet gebruiken", cache.load(day.plusDays(1)))
        cache.clear()
        assertNull("na uitloggen weg", cache.load(day))
    }
}
