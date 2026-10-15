package nl.jan.airco.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.jan.airco.R
import nl.jan.airco.data.Mode

/** Ontwerp-tokens uit het "Airco — bediening"-canvas. */
object Ink {
    val Ground = Color(0xFF0B0D12)
    val GroundTop = Color(0xFF171B26)
    val Text = Color(0xFFEEF0F5)
    val TextStrong = Color(0xFFF2F4F8)
    val Muted = Color(0xFF8B93A7)
    val Muted2 = Color(0xFF9AA2B4)
    val Faint = Color(0xFF6E7689)
    val Dim = Color(0xFF7E869A)
    val IconSoft = Color(0xFFC9CFDB)
    val Accent = Color(0xFF9DB7FF)
    val Error = Color(0xFFFF8A80)
    val OffC1 = Color(0xFF454B59)
    val OffC2 = Color(0xFF5A6170)
    val Track = Color(0xFF1A1E27)
    val BarOff = Color(0xFF262B36)

    fun white(alpha: Float) = Color.White.copy(alpha = alpha)
}

val Sora = FontFamily(
    Font(R.font.sora_200, FontWeight.ExtraLight),
    Font(R.font.sora_300, FontWeight.Light),
    Font(R.font.sora_400, FontWeight.Normal),
    Font(R.font.sora_600, FontWeight.SemiBold),
)

val Manrope = FontFamily(
    Font(R.font.manrope_400, FontWeight.Normal),
    Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold),
    Font(R.font.manrope_700, FontWeight.Bold),
)

/** Kleurpaar per modus: c1 = licht/warm uiteinde, c2 = verzadigd uiteinde. */
data class ModeStyle(val c1: Color, val c2: Color, val verb: String, val icon: ImageVector)

fun Mode?.style(): ModeStyle = when (this) {
    Mode.Heat -> ModeStyle(Color(0xFFFFC15A), Color(0xFFFF5A36), "Verwarmen", Icons.Warm)
    Mode.Cool -> ModeStyle(Color(0xFF62E6FF), Color(0xFF3F6BFF), "Koelen", Icons.Cool)
    Mode.Dry -> ModeStyle(Color(0xFF8AF5D0), Color(0xFF16B59E), "Ontvochtigen", Icons.Dry)
    Mode.Fan -> ModeStyle(Color(0xFFD7DEEC), Color(0xFF8391AD), "Ventileren", Icons.Fan)
    Mode.Automatic -> ModeStyle(Color(0xFF4DA8FF), Color(0xFFFF2E7E), "Automatisch", Icons.Auto)
    null -> ModeStyle(Color(0xFFD7DEEC), Color(0xFF8391AD), "—", Icons.Fan)
}

/** Iconen: exact de SVG-paden uit het ontwerp (24×24, lijnen). */
object Icons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun stroked(width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = addPathNodes(d),
                        stroke = SolidColor(Color.White),
                        strokeLineWidth = width,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()

    private fun filled(vararg paths: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply { paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.White)) } }
            .build()

    val Refresh = stroked(1.8f, "M20 11a8 8 0 1 0-2.34 5.66", "M20 4v7h-7")
    val More = filled(circle(12f, 5f, 1.7f), circle(12f, 12f, 1.7f), circle(12f, 19f, 1.7f))
    val Power = stroked(2.4f, "M12 3v8", "M6.3 6.8a8 8 0 1 0 11.4 0")
    val Minus = stroked(2f, "M6 12h12")
    val Plus = stroked(2f, "M6 12h12", "M12 6v12")
    val Chevron = stroked(2f, "M9 6l6 6-6 6")
    val Warning = stroked(1.8f, "M12 4l9 16H3z", "M12 10v4", "M12 17h.01")

    val Warm = stroked(
        1.8f,
        circle(12f, 12f, 4f),
        "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
    )
    val Cool = stroked(
        1.8f,
        "M12 2.5v19M3.8 7.25l16.4 9.5M3.8 16.75l16.4-9.5",
        "M9.5 4.5L12 6.5l2.5-2M9.5 19.5L12 17.5l2.5 2",
    )
    val Dry = stroked(1.8f, "M12 3c-3 4.4-6 7.5-6 11a6 6 0 0 0 12 0c0-3.5-3-6.6-6-11z")
    val Fan = stroked(
        1.8f,
        circle(12f, 12f, 1.6f),
        "M12 10.4c-.6-3.6.6-6.9 3.4-6.9 2.6 0 2.9 4.2-1.9 6.3",
        "M13.6 12c3.6-.6 6.9.6 6.9 3.4 0 2.6-4.2 2.9-6.3-1.9",
        "M12 13.6c.6 3.6-.6 6.9-3.4 6.9-2.6 0-2.9-4.2 1.9-6.3",
        "M10.4 12c-3.6.6-6.9-.6-6.9-3.4 0-2.6 4.2-2.9 6.3 1.9",
    )
    val Auto = stroked(
        1.8f,
        "M20 12a8 8 0 1 1-2.34-5.66",
        "M20 4v4h-4",
        "M9.5 15.5l2.5-7 2.5 7M10.3 13.3h3.4",
    )
}

/** 21.5 → "21,5"; 22.0 → "22". */
fun fmtTemp(t: Double): String =
    if (t % 1.0 == 0.0) t.toInt().toString() else t.toString().replace('.', ',')
