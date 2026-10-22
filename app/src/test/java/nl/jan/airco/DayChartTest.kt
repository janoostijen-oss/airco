package nl.jan.airco

import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.jan.airco.data.AircoUnit
import nl.jan.airco.data.Capabilities
import nl.jan.airco.data.FanSpeed
import nl.jan.airco.data.Mode
import nl.jan.airco.data.Reading
import nl.jan.airco.data.ReportParser
import nl.jan.airco.data.clipTo
import nl.jan.airco.ui.DayChartModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Grafiek "vandaag": rapport lezen, bijknippen, live bijhouden en wat er getekend wordt. */
@RunWith(AndroidJUnit4::class) // org.json in de parser
class DayChartTest {
    private val from = Instant.parse("2026-10-21T22:00:00Z") // middernacht in NL (zomertijd)
    private fun at(h: Double) = from.plusMillis((h * 3_600_000).toLong())
    private fun r(h: Double, v: Double) = Reading(at(h), v)

    private fun unit(power: Boolean = true, room: Double? = 21.0, target: Double = 22.0) = AircoUnit(
        id = "a", name = "A", building = "", isConnected = true, isInError = false, power = power,
        roomTemperature = room, setTemperature = target, mode = Mode.Heat, fanSpeed = FanSpeed.Auto,
        capabilities = Capabilities(
            modes = Mode.entries, numberOfFanSpeeds = 5, hasAutoFan = true, halfDegrees = true,
            heatRange = 10.0..31.0, coolDryRange = 16.0..31.0, autoRange = 16.0..31.0,
        ),
    )

    @Test
    fun parserLeestHuidigEnDoelMetUitAlsGat() {
        val body = """[{"datasets":[
            {"id":"room_temperature","label":"x","data":[{"x":"2026-10-22 06:00:00.0000000","y":19.5},{"x":"2026-10-22T08:00:00","y":21.0}]},
            {"id":"set_temperature","label":"y","data":[{"x":"2026-10-22 05:00:00","y":21.0},{"x":"2026-10-22 09:00:00","y":null}]},
            {"id":"outside_temperature","label":"z","data":[{"x":"2026-10-22 06:00:00","y":7.0}]}],
            "previousTriggers":[{"measure":"room_temperature","trigger":"2026-10-21 20:00:00","value":18.5},
                                {"measure":"set_temperature","trigger":"9999-12-31 23:59:59","value":20.0}]}]"""
        val s = ReportParser.daySeries(body)
        assertEquals(listOf(18.5, 19.5, 21.0), s.room.map { it.value })
        assertEquals(2, s.target.size)
        assertEquals(21.0, s.target[0].value, 0.0)
        assertTrue(s.target[1].value.isNaN())
    }

    @Test
    fun doelHeetSomsAnders() {
        assertTrue(ReportParser.isTargetId("set_temperature"))
        assertTrue(ReportParser.isTargetId("set_temperature_zone1"))
        assertTrue(ReportParser.isTargetId("target_temperature"))
        assertTrue(!ReportParser.isTargetId("set_tank_water_temperature"))
        assertTrue(!ReportParser.isTargetId("room_temperature"))
    }

    @Test
    fun knippenBegintOpMiddernachtMetDeStandVanToen() {
        val s = listOf(r(-5.0, 17.0), r(-1.0, 18.0), r(3.0, 19.0), r(30.0, 25.0))
        val c = s.clipTo(from, at(12.0))
        assertEquals(listOf(from, at(3.0)), c.map { it.time })
        assertEquals(listOf(18.0, 19.0), c.map { it.value })
    }

    @Test
    fun liveAlleenBijVerandering() {
        val c = DayChart(from, room = listOf(r(1.0, 21.0)), target = listOf(r(1.0, 22.0)))
        assertSame(c, c.withLive(unit(), at(2.0)))
        val warmer = c.withLive(unit(room = 21.5), at(2.0))
        assertEquals(listOf(21.0, 21.5), warmer.room.map { it.value })
        assertEquals(1, warmer.target.size)
        val uit = c.withLive(unit(power = false), at(3.0))
        assertTrue(uit.target.last().value.isNaN())
        assertSame(uit, uit.withLive(unit(power = false), at(4.0)))
    }

    @Test
    fun modelLoopttotNuEnRekentTussenwaarden() {
        val chart = DayChart(from, room = listOf(r(0.0, 18.0), r(4.0, 20.0)), target = listOf(r(0.0, Double.NaN), r(2.0, 21.0)))
        val m = DayChartModel.build(chart, unit(room = 22.0, target = 21.0), at(6.0))
        assertEquals(6f, m.nowH, 0.001f)
        assertEquals(19.0, m.roomAt(2f)!!, 0.001)       // halverwege 18 → 20
        assertEquals(21.0, m.roomAt(5f)!!, 0.001)       // naar de stand van nu
        assertEquals(22.0, m.roomNow!!, 0.0)
        assertTrue(m.targetAt(1f)!!.isNaN())            // uit
        assertEquals(21.0, m.targetAt(3f)!!, 0.0)
        assertNull(DayChartModel.build(DayChart(from), unit(), at(6.0)).targetAt(-1f))
        assertEquals(18.0, m.roomMin!!, 0.0)
        assertEquals(22.0, m.roomMax!!, 0.0)
        // As: ruim om de waarden, rasterlijnen binnen het bereik.
        assertTrue(m.tMin < 18.0 && m.tMax > 22.0)
        assertTrue(m.grid.isNotEmpty() && m.grid.all { it > m.tMin && it < m.tMax })
    }
}
