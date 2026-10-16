package nl.jan.airco.data

import kotlin.math.round

/**
 * "Op temperatuur": de unit staat aan in een modus met een doel (Warm, Koel,
 * Auto) en de huidige temperatuur is gelijk aan het doel, op 0,5° nauwkeurig
 * (de resolutie waarin MELCloud meet en waarin je het doel instelt).
 */
fun rawAtTarget(unit: AircoUnit): Boolean {
    if (!unit.power || !unit.isConnected || unit.isInError) return false
    if (unit.mode != Mode.Heat && unit.mode != Mode.Cool && unit.mode != Mode.Automatic) return false
    val room = unit.roomTemperature ?: return false
    return round(room * 2) / 2 == unit.setTemperature
}

/**
 * Houdt per unit bij of hij "op temperatuur" is, zonder te knipperen:
 *  - Aan: zodra één meting gelijk is.
 *  - Uit: pas na [missesToLeave] metingen op rij die niet gelijk zijn
 *    (units schommelen rond het doel).
 *  - Meteen uit bij een ander doel, andere modus of aan/uit, en zolang de
 *    unit "onderweg" is na een eigen wijziging ([suppressed]).
 */
class AtTargetTracker(private val missesToLeave: Int = 2) {
    private data class Key(val power: Boolean, val mode: Mode?, val target: Double)

    private val reached = mutableSetOf<String>()
    private val misses = mutableMapOf<String, Int>()
    private val keys = mutableMapOf<String, Key>()

    fun update(units: List<AircoUnit>, suppressed: Set<String> = emptySet()): Set<String> {
        val ids = units.mapTo(mutableSetOf()) { it.id }
        reached.retainAll(ids); misses.keys.retainAll(ids); keys.keys.retainAll(ids)
        for (u in units) {
            val key = Key(u.power, u.mode, u.setTemperature)
            if (keys[u.id] != key) {
                keys[u.id] = key
                reached.remove(u.id)
                misses.remove(u.id)
            }
            if (u.id in suppressed) {
                reached.remove(u.id)
                misses.remove(u.id)
                continue
            }
            when {
                rawAtTarget(u) -> { reached += u.id; misses.remove(u.id) }
                u.id in reached -> {
                    val m = (misses[u.id] ?: 0) + 1
                    if (m >= missesToLeave || !canBeAtTarget(u)) { reached.remove(u.id); misses.remove(u.id) } else misses[u.id] = m
                }
            }
        }
        return reached.toSet()
    }

    /** Uit, offline, storing of een modus zonder doel: dan nooit "op temperatuur". */
    private fun canBeAtTarget(u: AircoUnit) =
        u.power && u.isConnected && !u.isInError && (u.mode == Mode.Heat || u.mode == Mode.Cool || u.mode == Mode.Automatic)
}
