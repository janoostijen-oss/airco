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
)

/** Parser voor de `/context` response (eigen + gedeelde gebouwen, alleen airco's). */
object ContextParser {
    fun parse(json: JSONObject): List<AircoUnit> {
        val units = mutableListOf<AircoUnit>()
        for (key in listOf("buildings", "guestBuildings")) {
            val buildings = json.optJSONArray(key) ?: continue
            for (b in buildings.objects()) {
                val buildingName = b.optString("name")
                val ataUnits = b.optJSONArray("airToAirUnits") ?: continue
                for (u in ataUnits.objects()) units += parseUnit(u, buildingName)
            }
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
        )
        return AircoUnit(
            id = u.getString("id"),
            name = u.optString("givenDisplayName").ifBlank { "Airco" },
            building = building,
            isConnected = u.optBoolean("isConnected", true),
            isInError = u.optBoolean("isInError", false),
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
