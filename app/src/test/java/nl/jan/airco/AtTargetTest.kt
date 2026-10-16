package nl.jan.airco

import nl.jan.airco.data.AircoUnit
import nl.jan.airco.data.AtTargetTracker
import nl.jan.airco.data.Capabilities
import nl.jan.airco.data.FanSpeed
import nl.jan.airco.data.Mode
import nl.jan.airco.data.rawAtTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Op temperatuur": huidig = doel (op 0,5°), zonder knipperen. */
class AtTargetTest {
    private val caps = Capabilities(
        modes = Mode.entries, numberOfFanSpeeds = 5, hasAutoFan = true, halfDegrees = true,
        heatRange = 16.0..31.0, coolDryRange = 16.0..31.0, autoRange = 16.0..31.0,
    )

    private fun unit(room: Double?, target: Double = 21.0, mode: Mode = Mode.Heat, on: Boolean = true, id: String = "a") =
        AircoUnit(
            id = id, name = id, building = "Thuis", isConnected = true, isInError = false, power = on,
            roomTemperature = room, setTemperature = target, mode = mode, fanSpeed = FanSpeed.Auto, capabilities = caps,
        )

    @Test fun gelijkIsBereikt() = assertTrue(rawAtTarget(unit(21.0)))
    @Test fun afgerondOpHalveGraad() {
        assertTrue(rawAtTarget(unit(21.2)))   // → 21,0
        assertFalse(rawAtTarget(unit(21.3)))  // → 21,5
        assertTrue(rawAtTarget(unit(21.4, target = 21.5)))
    }
    @Test fun doorschietenTeltNiet() = assertFalse(rawAtTarget(unit(22.0)))
    @Test fun koelEnAutoTellen() {
        assertTrue(rawAtTarget(unit(21.0, mode = Mode.Cool)))
        assertTrue(rawAtTarget(unit(21.0, mode = Mode.Automatic)))
    }
    @Test fun nietBijUitDroogOfVentilator() {
        assertFalse(rawAtTarget(unit(21.0, on = false)))
        assertFalse(rawAtTarget(unit(21.0, mode = Mode.Dry)))
        assertFalse(rawAtTarget(unit(21.0, mode = Mode.Fan)))
        assertFalse(rawAtTarget(unit(null)))
    }

    @Test
    fun blijftStaanBijEenAfwijkendeMeting() {
        val t = AtTargetTracker()
        assertEquals(setOf("a"), t.update(listOf(unit(21.0))))
        assertEquals("één meting ernaast: blijft", setOf("a"), t.update(listOf(unit(21.5))))
        assertEquals("terug op doel", setOf("a"), t.update(listOf(unit(21.0))))
        assertEquals(setOf("a"), t.update(listOf(unit(20.5))))
        assertEquals("twee op rij ernaast: weg", emptySet<String>(), t.update(listOf(unit(20.5))))
    }

    @Test
    fun anderDoelOfUitzettenIsMeteenWeg() {
        val t = AtTargetTracker()
        t.update(listOf(unit(21.0)))
        assertEquals(emptySet<String>(), t.update(listOf(unit(21.0, target = 22.0))))
        t.update(listOf(unit(22.0, target = 22.0)))
        assertEquals(emptySet<String>(), t.update(listOf(unit(22.0, target = 22.0, on = false))))
    }

    @Test
    fun onderwegNaEigenWijzigingNiet() {
        val t = AtTargetTracker()
        assertEquals(emptySet<String>(), t.update(listOf(unit(21.0)), suppressed = setOf("a")))
        assertEquals(setOf("a"), t.update(listOf(unit(21.0))))
    }

    @Test
    fun perUnit() {
        val t = AtTargetTracker()
        val r = t.update(listOf(unit(21.0, id = "a"), unit(23.0, id = "b")))
        assertEquals(setOf("a"), r)
    }
}
