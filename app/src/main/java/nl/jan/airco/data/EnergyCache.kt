package nl.jan.airco.data

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Bewaart het verbruik van "gisteren" op het toestel. Een afgesloten dag verandert
 * niet meer, dus één keer ophalen per dag is genoeg — ook na een herstart van de app.
 * Sleutel = de datum van die dag, zodat een oude waarde nooit voor een andere dag geldt.
 */
class EnergyCache(context: Context) {
    private val prefs = context.getSharedPreferences("energy_cache", Context.MODE_PRIVATE)

    fun load(day: LocalDate): Double? {
        if (prefs.getString(KEY_DAY, null) != day.toString()) return null
        return prefs.getString(KEY_KWH, null)?.toDoubleOrNull()
    }

    fun save(day: LocalDate, kwh: Double) {
        prefs.edit().putString(KEY_DAY, day.toString()).putString(KEY_KWH, kwh.toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_KWH = "kwh"

        /** Tot dit tijdstip na middernacht kan MELCloud nog verbruik van gisteren nasturen. */
        val SETTLED_AFTER: LocalTime = LocalTime.of(1, 0)

        /** Opslaan alleen als de waarde geldig en groter dan nul is, én opgehaald na 01:00. */
        fun shouldPersist(kwh: Double?, fetchedAt: ZonedDateTime): Boolean =
            kwh != null && kwh > 0.0 && !kwh.isNaN() && !fetchedAt.toLocalTime().isBefore(SETTLED_AFTER)

        /**
         * Tot wanneer een opgehaalde "gisteren" in het geheugen geldt zonder opnieuw te vragen:
         * vóór 01:00 opgehaald → tot 01:00 (dan nog één keer definitief), anders tot middernacht.
         */
        fun validUntil(fetchedAt: ZonedDateTime, zone: ZoneId): ZonedDateTime {
            val day = fetchedAt.toLocalDate()
            return if (fetchedAt.toLocalTime().isBefore(SETTLED_AFTER)) day.atTime(SETTLED_AFTER).atZone(zone)
            else day.plusDays(1).atStartOfDay(zone)
        }
    }
}
