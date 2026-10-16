package nl.jan.airco

import nl.jan.airco.data.AircoUnit
import nl.jan.airco.data.Capabilities
import nl.jan.airco.data.FanSpeed
import nl.jan.airco.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Buitentemperatuur: één rapport per buitenunit, niet per binnenunit. */
class OutdoorGroupsTest {
    private val caps = Capabilities(
        modes = listOf(Mode.Heat), numberOfFanSpeeds = 5, hasAutoFan = true, halfDegrees = false,
        heatRange = 16.0..31.0, coolDryRange = 16.0..31.0, autoRange = 16.0..31.0,
    )

    private fun unit(id: String, systemId: String?) = AircoUnit(
        id = id, name = id, building = "Thuis", isConnected = true, isInError = false, power = false,
        roomTemperature = 20.0, setTemperature = 21.0, mode = Mode.Heat, fanSpeed = FanSpeed.Auto,
        capabilities = caps, systemId = systemId,
    )

    @Test
    fun multiSplitDeeltEenBuitenunit() {
        val groups = outdoorGroups(
            listOf(unit("woon", "sysA"), unit("slaap", "sysA"), unit("kantoor", "sysB"), unit("zolder", "sysA")),
        )
        assertEquals(listOf(listOf("woon", "slaap", "zolder"), listOf("kantoor")), groups)
    }

    @Test
    fun zonderSystemIdEigenBuitenunit() {
        val groups = outdoorGroups(listOf(unit("a", null), unit("b", null)))
        assertEquals(listOf(listOf("a"), listOf("b")), groups)
    }

    @Test
    fun leegBlijftLeeg() {
        assertEquals(emptyList<List<String>>(), outdoorGroups(emptyList()))
    }
}
