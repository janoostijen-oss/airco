package nl.jan.airco.ui

/**
 * Indeling op basis van de vensterbreedte (Material-breakpoints):
 *  - Compact  (< 600dp): telefoon, en een dichtgeklapte foldable — overzicht ⇄ detail.
 *  - Medium   (600–839dp): opengevouwen foldable, tablet staand — smalle lijst + bediening.
 *  - Expanded (≥ 840dp): tablet liggend — ruime lijst + gecentreerde bediening.
 * Op de vensterbreedte, niet het apparaat: ook split-screen en vrije vensters kloppen zo.
 */
enum class LayoutMode { Compact, Medium, Expanded }

fun layoutModeFor(widthDp: Float): LayoutMode = when {
    widthDp < 600f -> LayoutMode.Compact
    widthDp < 840f -> LayoutMode.Medium
    else -> LayoutMode.Expanded
}

/** Breedte van de lijstkolom bij Medium: meeschalen, maar de bediening houdt genoeg ruimte. */
fun mediumListWidthDp(widthDp: Float): Float = (widthDp * 0.41f).coerceIn(248f, 300f)

/** Vaste namen voor de UI-tests. */
object TestTags {
    const val OVERVIEW = "overview"
    const val LIST_PANE = "list-pane"
    const val DETAIL_PANE = "detail-pane"
    const val DETAIL_NAME = "detail-name"
    const val DIAL = "dial"
    const val CURRENT_TEMP = "current-temp"
    const val ENERGY_TODAY = "energy-today"
    const val OUTDOOR_TREND = "outdoor-trend"
    const val OUTDOOR = "outdoor"
    const val LOADING = "loading"
    const val HOT_CONFIRM = "hot-confirm"
    fun roomRow(id: String) = "room-row-$id"
}
