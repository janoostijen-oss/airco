package nl.jan.airco.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stevige klik als het doel de kamertemperatuur (rode streepje) bereikt of passeert. */
class PassesRoomTest {
    @Test fun bereikenTelt() = assertTrue(passesRoom(21.0, 21.5, room = 21.5))
    @Test fun overspringenTelt() = assertTrue(passesRoom(21.0, 21.5, room = 21.3))
    @Test fun terugTeltOok() = assertTrue(passesRoom(22.0, 21.5, room = 21.5))
    @Test fun wegVanStreepjeTeltNiet() = assertFalse(passesRoom(21.5, 22.0, room = 21.5))
    @Test fun aanDezelfdeKantNiet() = assertFalse(passesRoom(19.0, 19.5, room = 21.5))
    @Test fun zonderKamertemperatuurNiet() = assertFalse(passesRoom(21.0, 22.0, room = null))
}
