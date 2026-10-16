package nl.jan.airco

import nl.jan.airco.data.EnergyParser
import nl.jan.airco.ui.energyLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Verbruik vandaag: parsen, optellen over units, begin van de dag, weergave. */
class EnergyTest {
    @Test
    fun telWattUrenOpAlsKwh() {
        // Vorm zoals MELCloud Home het levert: Wh per uur, als tekst.
        val body = """{"deviceId":"x","measureData":[{"type":"cumulativeEnergyConsumedSinceLastUpload",
            "values":[{"time":"2026-10-16 06:00:00.000000000","value":"1200.0"},
                      {"time":"2026-10-16 07:00:00.000000000","value":"400"},
                      {"time":"2026-10-16 08:00:00.000000000","value":""}]}]}"""
        assertEquals(1.6, EnergyParser.totalKwh(body)!!, 1e-9)
    }

    @Test fun geenMeetpuntenIsOnbekend() = assertNull(EnergyParser.totalKwh("""{"measureData":[]}"""))
    @Test fun leegAntwoordIsOnbekend() = assertNull(EnergyParser.totalKwh(""))

    @Test
    fun tellerstandIsGeenDagverbruik() {
        // Oude data kan de tellerstand (~10^5 Wh) bevatten i.p.v. verbruik.
        assertNull(EnergyParser.totalKwh("""{"measureData":[{"values":[{"time":"t","value":"250000"}]}]}"""))
    }

    @Test
    fun optellenOverUnits() {
        assertEquals(5.6, combineEnergy(listOf(true to 3.5, true to 2.1))!!, 1e-9)
        // Unit met meter maar zonder meetpunten stond uit: telt als 0.
        assertEquals(3.5, combineEnergy(listOf(true to 3.5, true to null))!!, 1e-9)
        // Onbekend of hij meet en geen data: niet meetellen.
        assertEquals(3.5, combineEnergy(listOf(true to 3.5, null to null))!!, 1e-9)
        assertEquals(0.0, combineEnergy(listOf(true to null))!!, 1e-9)
    }

    @Test fun nietsBekendGeeftStreepje() = assertNull(combineEnergy(listOf(null to null)))

    @Test
    fun vandaagBegintOmMiddernachtLokaal() {
        val zone = ZoneId.of("Europe/Amsterdam")
        // 16 okt 17:50 zomertijd (UTC+2) → middernacht = 15 okt 22:00 UTC.
        assertEquals(
            Instant.parse("2026-10-15T22:00:00Z"),
            startOfToday(Instant.parse("2026-10-16T15:50:00Z"), zone),
        )
    }

    @Test
    fun weergave() {
        assertEquals("5,6", energyLabel(5.63))
        assertEquals("0,0", energyLabel(0.0))
        assertEquals("12,4", energyLabel(12.38))
        assertEquals("–", energyLabel(null))
    }
}
