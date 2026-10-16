package nl.jan.airco.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Breakpoints: welke indeling hoort bij welke vensterbreedte. */
class LayoutModeTest {

    @Test
    fun telefoonsZijnCompact() {
        assertEquals(LayoutMode.Compact, layoutModeFor(360f))
        assertEquals(LayoutMode.Compact, layoutModeFor(411f))
        assertEquals(LayoutMode.Compact, layoutModeFor(599f))
    }

    @Test
    fun opengevouwenFoldableEnStaandeTabletZijnMedium() {
        assertEquals(LayoutMode.Medium, layoutModeFor(600f))
        assertEquals(LayoutMode.Medium, layoutModeFor(673f)) // Pixel Fold binnenscherm
        assertEquals(LayoutMode.Medium, layoutModeFor(800f)) // tablet staand
        assertEquals(LayoutMode.Medium, layoutModeFor(839f))
    }

    @Test
    fun liggendeTabletIsExpanded() {
        assertEquals(LayoutMode.Expanded, layoutModeFor(840f))
        assertEquals(LayoutMode.Expanded, layoutModeFor(1280f))
    }

    @Test
    fun lijstkolomOpFoldableSchaaltBinnenGrenzen() {
        assertEquals(248f, mediumListWidthDp(600f), 0.01f) // ondergrens: bediening houdt ruimte
        assertEquals(287f, mediumListWidthDp(700f), 0.01f)
        assertEquals(300f, mediumListWidthDp(839f), 0.01f) // bovengrens
    }
}
