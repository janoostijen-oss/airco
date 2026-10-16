package nl.jan.airco.data

import org.json.JSONArray
import org.json.JSONObject

/** Werkingsmodi zoals de MELCloud Home API ze noemt. */
enum class Mode(val wire: String, val label: String) {
    Heat("Heat", "Warm"),
    Cool("Cool", "Koel"),
    Dry("Dry", "Droog"),
    Fan("Fan", "Fan"),
    Automatic("Automatic", "Auto");

    companion object {
        fun fromWire(value: String): Mode? = entries.firstOrNull { it.wire == value }
    }
}

/** Ventilatorstanden. De API levert ze soms als "0".."5", soms als naam. */
enum class FanSpeed(val wire: String, val label: String) {
    Auto("Auto", "Auto"),
    One("One", "1"),
    Two("Two", "2"),
    Three("Three", "3"),
    Four("Four", "4"),
    Five("Five", "5");

    companion object {
        fun fromWire(value: String): FanSpeed {
            value.toIntOrNull()?.let { n -> return entries.getOrNull(n) ?: Auto }
            return entries.firstOrNull { it.wire == value } ?: Auto
        }
    }
}

data class Capabilities(
    val modes: List<Mode>,
    val numberOfFanSpeeds: Int,
    val hasAutoFan: Boolean,
    val halfDegrees: Boolean,
    val heatRange: ClosedFloatingPointRange<Double>,
    val coolDryRange: ClosedFloatingPointRange<Double>,
    val autoRange: ClosedFloatingPointRange<Double>,
    /** Meet de unit zijn eigen stroomverbruik? `null` = MELCloud zegt het niet. */
    val hasEnergyMeter: Boolean? = null,
) {
    /** Toegestaan temperatuurbereik voor een modus; `null` = geen setpoint (ventilator). */
    fun rangeFor(mode: Mode?): ClosedFloatingPointRange<Double>? = when (mode) {
        Mode.Heat -> heatRange
        Mode.Cool, Mode.Dry -> coolDryRange
        Mode.Automatic -> autoRange
        Mode.Fan -> null
        null -> heatRange
    }

    val step: Double get() = if (halfDegrees) 0.5 else 1.0

    val fanSpeeds: List<FanSpeed>
        get() = buildList {
            if (hasAutoFan) add(FanSpeed.Auto)
            addAll(FanSpeed.entries.drop(1).take(numberOfFanSpeeds.coerceIn(0, 5)))
        }
}

data class AircoUnit(
    val id: String,
    val name: String,
    val building: String,
    val isConnected: Boolean,
    val isInError: Boolean,
    val power: Boolean,
    val roomTemperature: Double?,
    val setTemperature: Double,
    val mode: Mode?,
    val fanSpeed: FanSpeed,
    val capabilities: Capabilities,
    /** Gedeelde buitenunit (multi-split); `null` = eigen buitenunit. */
    val systemId: String? = null,
)

/** Parser voor de `/context` response (eigen + gedeelde gebouwen, alleen airco's). */
object ContextParser {
    fun parse(json: JSONObject): List<AircoUnit> {
        val units = mutableListOf<AircoUnit>()
        val failures = mutableListOf<Throwable>()
        var buildingCount = 0
        for (key in listOf("buildings", "guestBuildings")) {
            val buildings = json.optJSONArray(key) ?: continue
            for (b in buildings.objects()) {
                buildingCount++
                val buildingName = b.optString("name")
                val ataUnits = b.optJSONArray("airToAirUnits") ?: continue
                for (u in ataUnits.objects()) {
                    // Eén afwijkende unit mag niet de hele lijst laten mislukken.
                    runCatching { parseUnit(u, buildingName) }
                        .onSuccess { units += it }
                        .onFailure { failures += it }
                }
            }
        }
        if (units.isEmpty()) {
            // Geen stille lege lijst: zeg wát er leeg was, zodat we weten waar het misgaat.
            val detail = when {
                failures.isNotEmpty() ->
                    "${failures.size} airco('s) konden niet gelezen worden: ${failures.first().message}"
                buildingCount == 0 -> "MELCloud gaf geen woningen terug voor dit account."
                else -> "Wel $buildingCount woning(en) gevonden, maar zonder airco's."
            }
            throw java.io.IOException(detail)
        }
        return units.sortedWith(compareBy({ it.building }, { it.name }))
    }

    private fun parseUnit(u: JSONObject, building: String): AircoUnit {
        val settings = buildMap {
            u.optJSONArray("settings")?.objects()?.forEach { put(it.optString("name"), it.optString("value")) }
        }
        val c = u.optJSONObject("capabilities") ?: JSONObject()
        val modes = buildList {
            if (c.optBoolean("hasHeatOperationMode", true)) add(Mode.Heat)
            if (c.optBoolean("hasCoolOperationMode", true)) add(Mode.Cool)
            if (c.optBoolean("hasDryOperationMode", true)) add(Mode.Dry)
            add(Mode.Fan)
            if (c.optBoolean("hasAutoOperationMode", true)) add(Mode.Automatic)
        }
        val caps = Capabilities(
            modes = modes,
            numberOfFanSpeeds = c.optInt("numberOfFanSpeeds", 5),
            hasAutoFan = c.optBoolean("hasAutomaticFanSpeed", true),
            halfDegrees = c.optBoolean("hasHalfDegreeIncrements", false),
            heatRange = range(c, "minTempHeat", "maxTempHeat", 10.0, 31.0),
            coolDryRange = range(c, "minTempCoolDry", "maxTempCoolDry", 16.0, 31.0),
            autoRange = range(c, "minTempAutomatic", "maxTempAutomatic", 16.0, 31.0),
            hasEnergyMeter = if (c.has("hasEnergyConsumedMeter")) c.optBoolean("hasEnergyConsumedMeter") else null,
        )
        return AircoUnit(
            id = u.getString("id"),
            name = u.optString("givenDisplayName").ifBlank { "Airco" },
            building = building,
            isConnected = u.optBoolean("isConnected", true),
            isInError = u.optBoolean("isInError", false),
            systemId = if (u.isNull("systemId")) null else u.optString("systemId").ifBlank { null },
            power = settings["Power"] == "True",
            roomTemperature = settings["RoomTemperature"]?.toDoubleOrNull(),
            setTemperature = settings["SetTemperature"]?.toDoubleOrNull() ?: 21.0,
            mode = settings["OperationMode"]?.let(Mode::fromWire),
            fanSpeed = FanSpeed.fromWire(settings["SetFanSpeed"].orEmpty()),
            capabilities = caps,
        )
    }

    private fun range(c: JSONObject, min: String, max: String, dMin: Double, dMax: Double): ClosedFloatingPointRange<Double> {
        val lo = c.optDouble(min, dMin).takeUnless { it.isNaN() } ?: dMin
        val hi = c.optDouble(max, dMax).takeUnless { it.isNaN() } ?: dMax
        return if (lo <= hi) lo..hi else dMin..dMax
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}

/**
 * Parser voor `/telemetry/telemetry/energy/{id}` (airco: de meting
 * `cumulative_energy_consumed_since_last_upload`). De waarden zijn
 * watt-uren per tijdvak; opgeteld en omgerekend naar kWh.
 * `null` = geen enkel meetpunt (geen data, of de unit meet niet).
 */
object EnergyParser {
    /** Meer dan dit op één dag voor één airco is geen verbruik maar een tellerstand (oude data). */
    private const val MAX_PLAUSIBLE_KWH_PER_DAY = 150.0

    fun totalKwh(body: String): Double? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        val measures = JSONObject(trimmed).optJSONArray("measureData") ?: return null
        var wattHours = 0.0
        var points = 0
        for (i in 0 until measures.length()) {
            val values = measures.optJSONObject(i)?.optJSONArray("values") ?: continue
            for (j in 0 until values.length()) {
                val v = values.optJSONObject(j)?.optString("value")?.trim()?.toDoubleOrNull() ?: continue
                if (v.isNaN() || v < 0) continue
                wattHours += v
                points++
            }
        }
        if (points == 0) return null
        val kwh = wattHours / 1000.0
        return kwh.takeIf { it <= MAX_PLAUSIBLE_KWH_PER_DAY }
    }
}

/** Eén meting: tijdstip (UTC, zoals de API het geeft) + waarde. */
data class Reading(val time: String, val value: Double)

/**
 * Parser voor `/report/v1/trendsummary`: haalt de meest recente
 * buitentemperatuur uit de dataset `outside_temperature`. Valt terug op
 * `previousTriggers` (laatste meting vóór het venster) als er in het
 * venster zelf niets gemeten is.
 */
object ReportParser {
    private const val OUTSIDE = "outside_temperature"

    fun latestOutdoor(body: String): Reading? {
        val trimmed = body.trim()
        val reports: List<JSONObject> = when {
            trimmed.startsWith("[") -> JSONArray(trimmed).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            trimmed.startsWith("{") -> listOf(JSONObject(trimmed))
            else -> emptyList()
        }
        var best: Reading? = null
        fun consider(time: String, value: Double) {
            if (value.isNaN()) return
            val t = time.replace(' ', 'T')
            if (best == null || t > best!!.time) best = Reading(t, value)
        }
        for (report in reports) {
            val datasets = report.optJSONArray("datasets") ?: continue
            for (i in 0 until datasets.length()) {
                val ds = datasets.optJSONObject(i) ?: continue
                val isOutside = ds.optString("id") == OUTSIDE ||
                    ds.optString("label").contains("outside", ignoreCase = true)
                if (!isOutside) continue
                val data = ds.optJSONArray("data") ?: continue
                for (j in 0 until data.length()) {
                    val p = data.optJSONObject(j) ?: continue
                    if (p.isNull("y")) continue
                    consider(p.optString("x"), p.optDouble("y"))
                }
            }
        }
        if (best == null) {
            for (report in reports) {
                val triggers = report.optJSONArray("previousTriggers") ?: continue
                for (i in 0 until triggers.length()) {
                    val t = triggers.optJSONObject(i) ?: continue
                    if (t.optString("measure") != OUTSIDE || t.isNull("value")) continue
                    consider(t.optString("trigger"), t.optDouble("value"))
                }
            }
        }
        return best
    }
}
