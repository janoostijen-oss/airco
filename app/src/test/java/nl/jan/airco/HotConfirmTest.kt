package nl.jan.airco

import nl.jan.airco.data.Mode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wanneer vraagt de app "hoger dan 25°?" — alleen bij verwarmen, alleen bij passeren. */
class HotConfirmTest {
    @Test fun warmOver25Vragen() = assertTrue(needsHotConfirm(Mode.Heat, from = 25.0, to = 25.5))
    @Test fun autoOver25Vragen() = assertTrue(needsHotConfirm(Mode.Automatic, from = 22.0, to = 26.0))
    @Test fun koelenNooit() = assertFalse(needsHotConfirm(Mode.Cool, from = 24.0, to = 27.0))
    @Test fun drogenEnVentilerenNooit() {
        assertFalse(needsHotConfirm(Mode.Dry, from = 24.0, to = 27.0))
        assertFalse(needsHotConfirm(Mode.Fan, from = 24.0, to = 27.0))
    }
    @Test fun tot25NietVragen() = assertFalse(needsHotConfirm(Mode.Heat, from = 24.5, to = 25.0))
    @Test fun alBoven25VerderOmhoogNietOpnieuw() = assertFalse(needsHotConfirm(Mode.Heat, from = 25.5, to = 26.0))
    @Test fun omlaagNooit() = assertFalse(needsHotConfirm(Mode.Heat, from = 27.0, to = 26.5))
    @Test fun vanKoelenNaarWarmBoven25Vragen() =
        assertTrue(needsHotConfirm(Mode.Heat, from = 27.0, to = 27.0, fromMode = Mode.Cool))
    @Test fun vanWarmNaarAutoBoven25NietOpnieuw() =
        assertFalse(needsHotConfirm(Mode.Automatic, from = 26.0, to = 26.0, fromMode = Mode.Heat))
}
