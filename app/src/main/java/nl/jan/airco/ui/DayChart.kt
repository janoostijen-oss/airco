package nl.jan.airco.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import nl.jan.airco.AircoActions
import nl.jan.airco.DayChart
import nl.jan.airco.data.AircoUnit
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Grafieken per unit-id, net als [LocalAtTarget] als CompositionLocal. */
internal val LocalDayCharts = compositionLocalOf { emptyMap<String, DayChart>() }

private val RoomColor = Color(0xFFE0703A)
private val TargetColor = Color(0xBFE6EAF2) // rgba(230,234,242,0.75)
private val CardGround = Color(0xFF151922)

// Maten uit het ontwerp (178 hoog): plot van y 22..154, labels eronder.
private val ChartHeight = 178.dp
private val PlotLeft = 30.dp
private val PlotRightPad = 8.dp
private val PlotTop = 22.dp
private val PlotBottom = 154.dp
private val ShadeTop = 16.dp

/**
 * Wat de grafiek tekent, los van Compose (zo te testen): uren sinds middernacht
 * met waarden. Doel: [Double.NaN] = uit (stippellijn onderbroken).
 */
internal class DayChartModel(
    val nowH: Float,
    val room: List<Pair<Float, Double>>,
    val target: List<Pair<Float, Double>>,
    val tMin: Double,
    val tMax: Double,
    val grid: List<Int>,
) {
    val roomMin: Double? = room.minOfOrNull { it.second }
    val roomMax: Double? = room.maxOfOrNull { it.second }
    val roomNow: Double? = room.lastOrNull()?.second

    /** Huidig op uur [h]: zoals de lijn getekend is (rechte stukken tussen metingen). */
    fun roomAt(h: Float): Double? {
        if (room.isEmpty() || h < room.first().first) return null
        val i = room.indexOfLast { it.first <= h }
        val a = room[i]
        val b = room.getOrNull(i + 1) ?: return a.second
        if (b.first <= a.first) return b.second
        return a.second + (b.second - a.second) * (h - a.first) / (b.first - a.first)
    }

    /** Doel op uur [h] (trap): `null` = onbekend, [Double.NaN] = uit. */
    fun targetAt(h: Float): Double? = target.lastOrNull { it.first <= h }?.second

    companion object {
        fun build(chart: DayChart, unit: AircoUnit, now: Instant): DayChartModel {
            fun hours(t: Instant) = (Duration.between(chart.from, t).toMillis() / 3_600_000.0).toFloat().coerceIn(0f, 24f)
            val nowH = hours(now)
            val room = chart.room.filter { !it.time.isAfter(now) && !it.value.isNaN() }
                .map { hours(it.time) to it.value }.toMutableList()
            unit.roomTemperature?.let { if ((room.lastOrNull()?.first ?: -1f) < nowH) room += nowH to it }
            val target = chart.target.filter { !it.time.isAfter(now) }.map { hours(it.time) to it.value }.toMutableList()
            val tNow = if (unit.power) unit.setTemperature else Double.NaN
            if (target.isNotEmpty() || !tNow.isNaN()) target += nowH to tNow

            val values = room.map { it.second } + target.map { it.second }.filter { !it.isNaN() }
            var lo = (values.minOrNull() ?: 18.0) - 1.5
            var hi = (values.maxOrNull() ?: 22.0) + 1.5
            if (hi - lo < 6) {
                val mid = (hi + lo) / 2
                lo = mid - 3; hi = mid + 3
            }
            val step = if (hi - lo <= 10) 2 else 5
            val grid = (ceil(lo / step).toInt()..floor(hi / step).toInt()).map { it * step }
                .filter { it > lo + 0.4 && it < hi - 0.4 }
            return DayChartModel(nowH, room, target, lo, hi, grid)
        }
    }
}

/**
 * "Vandaag": huidige temperatuur (lijn + zachte vlakvulling) en doel (stippel,
 * trap) sinds middernacht, met een "NU"-streep en schuiven voor de waarden op
 * een tijdstip. Haalt de gegevens op zodra hij zichtbaar is ([active]).
 */
@Composable
internal fun DayChartSection(unit: AircoUnit, vm: AircoActions, active: Boolean, scrolling: () -> Boolean) {
    val chart = LocalDayCharts.current[unit.id]
    LifecycleResumeEffect(unit.id, active) {
        if (active) vm.loadDayChart(unit.id)
        onPauseOrDispose { }
    }
    // "Nu" schuift elke minuut op, ook zonder nieuwe gegevens.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000)
            value = Instant.now()
        }
    }
    val model = remember(chart, unit.roomTemperature, unit.setTemperature, unit.power, now) {
        chart?.let { DayChartModel.build(it, unit, now) }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            "VANDAAG",
            fontFamily = Manrope,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = Ink.Muted,
            // Voor TalkBack (en om niet te verwarren met "Vandaag" van het verbruik).
            modifier = Modifier.weight(1f).clearAndSetSemantics {
                heading()
                contentDescription = "Temperatuur vandaag"
            },
        )
        val lo = model?.roomMin
        val hi = model?.roomMax
        if (lo != null && hi != null) {
            Text(
                "Huidig ${fmtTemp(round1(lo))}° – ${fmtTemp(round1(hi))}°",
                fontFamily = Manrope,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = Ink.Muted2,
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink.white(0.035f), shape)
            .border(1.dp, Ink.white(0.07f), shape)
            .padding(start = 6.dp, top = 14.dp, end = 12.dp, bottom = 10.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem("Huidig", dashed = false)
            LegendItem("Doel", dashed = true)
        }
        val hasData = model != null && (model.room.size > 1 || model.target.size > 1)
        when {
            hasData -> ChartPlot(model!!, chart!!.from, scrolling)
            chart == null || chart.loading -> ChartLoading()
            else -> ChartEmpty()
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "Sleep over de grafiek voor de waarden op een tijdstip.",
        fontFamily = Manrope,
        fontSize = 11.sp,
        color = Ink.Faint,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

@Composable
private fun LegendItem(label: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 2.dp)) {
            if (dashed) {
                drawLine(
                    TargetColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                    strokeWidth = size.height,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                )
            } else {
                drawRect(RoomColor)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Ink.Muted2)
    }
}

@Composable
private fun ChartPlot(model: DayChartModel, from: Instant, scrolling: () -> Boolean) {
    val measurer = rememberTextMeasurer()
    val view = LocalView.current
    val latest by rememberUpdatedState(model)
    // Uur onder de vinger tijdens schuiven; null = niet aan het schuiven.
    var hover by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(0) }
    var tipW by remember { mutableIntStateOf(0) }
    val exclusion = rememberQuietGestureExclusion(scrolling)
    val zone = ZoneId.systemDefault()
    val roomNow = model.roomNow

    Box(
        Modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .testTag(TestTags.DAY_CHART)
            .semantics {
                contentDescription = if (model.roomMin != null && roomNow != null) {
                    "Temperatuur vandaag: huidig van ${fmtTemp(round1(model.roomMin))} tot " +
                        "${fmtTemp(round1(model.roomMax!!))} graden, nu ${fmtTemp(round1(roomNow))} graden"
                } else "Temperatuur vandaag"
            }
            .onSizeChanged { widthPx = it.width }
            .then(exclusion)
            .pointerInput(Unit) {
                fun hourAt(x: Float): Float {
                    val x0 = PlotLeft.toPx()
                    val x1 = size.width - PlotRightPad.toPx()
                    return ((x - x0) / (x1 - x0) * 24f).coerceIn(0f, latest.nowH)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture // tik of verticaal scrollen: de pagina doet het
                    try {
                        hover = hourAt(start.position.x)
                        var lastHour = hover!!.toInt()
                        horizontalDrag(start.id) { change ->
                            val h = hourAt(change.position.x)
                            hover = h
                            // Tikje bij elk heel uur: voel waar je bent zonder te kijken.
                            if (h.toInt() != lastHour) {
                                lastHour = h.toInt()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            change.consume()
                        }
                    } finally {
                        hover = null
                    }
                }
            },
    ) {
        // Basis: alleen opnieuw getekend als de gegevens veranderen, niet tijdens schuiven.
        Canvas(Modifier.fillMaxSize()) { drawChart(model, measurer) }
        // Bovenlaag: kruisdraad en stip, leest [hover] alleen in de tekenfase.
        Canvas(Modifier.fillMaxSize()) {
            val h = hover ?: return@Canvas
            val x = xOf(h)
            drawLine(Ink.white(0.35f), Offset(x, ShadeTop.toPx()), Offset(x, PlotBottom.toPx()), strokeWidth = 1.dp.toPx())
            model.roomAt(h)?.let { v ->
                val c = Offset(x, yOf(v, model))
                drawCircle(CardGround, radius = 6.5.dp.toPx(), center = c)
                drawCircle(RoomColor, radius = 4.5.dp.toPx(), center = c)
            }
        }
        val h = hover
        if (h != null) {
            val time = from.plusMillis((h * 3_600_000).toLong()).atZone(zone).toLocalTime()
            val minutes = ((time.hour * 60 + time.minute) / 5.0).roundToInt() * 5
            val label = String.format(java.util.Locale.ROOT, "%02d:%02d", (minutes / 60) % 24, minutes % 60)
            val room = model.roomAt(h)
            val tgt = model.targetAt(h)
            val tipShape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .offset {
                        val x0 = PlotLeft.toPx()
                        val x1 = widthPx - PlotRightPad.toPx()
                        val x = x0 + (x1 - x0) * h / 24f
                        val gap = 14.dp.toPx()
                        // Aan de andere kant van je vinger, zodat je de lijn blijft zien.
                        val left = if (x > widthPx / 2f) x - gap - tipW else x + gap
                        IntOffset(left.roundToInt(), 24.dp.roundToPx())
                    }
                    .onSizeChanged { tipW = it.width }
                    .testTag(TestTags.DAY_CHART_TIP)
                    .shadow(14.dp, tipShape)
                    .background(Color(0xFF1F2430), tipShape)
                    .border(1.dp, Ink.white(0.1f), tipShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(label, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Ink.TextStrong)
                TipRow("Huidig", room?.let { "${fmtTemp(round1(it))}°" } ?: "–", dashed = false)
                TipRow("Doel", when {
                    tgt == null -> "–"
                    tgt.isNaN() -> "uit"
                    else -> "${fmtTemp(tgt)}°"
                }, dashed = true)
            }
        }
    }
}

@Composable
private fun TipRow(label: String, value: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 8.dp, height = 2.dp)) {
            if (dashed) {
                drawLine(
                    TargetColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                    strokeWidth = size.height,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                )
            } else drawRect(RoomColor)
        }
        Spacer(Modifier.width(6.dp))
        Text("$label ", fontFamily = Manrope, fontSize = 11.sp, color = Ink.IconSoft)
        Text(value, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Ink.TextStrong)
    }
}

private fun DrawScope.xOf(h: Float): Float {
    val x0 = PlotLeft.toPx()
    val x1 = size.width - PlotRightPad.toPx()
    return x0 + (x1 - x0) * h / 24f
}

private fun DrawScope.yOf(t: Double, m: DayChartModel): Float {
    val y0 = PlotBottom.toPx()
    val y1 = PlotTop.toPx()
    return (y0 - (y0 - y1) * (t - m.tMin) / (m.tMax - m.tMin)).toFloat()
}

private fun DrawScope.drawChart(m: DayChartModel, measurer: androidx.compose.ui.text.TextMeasurer) {
    val label = TextStyle(fontFamily = Manrope, fontSize = 10.sp, color = Ink.Faint)
    val x0 = PlotLeft.toPx()
    val x1 = size.width - PlotRightPad.toPx()
    val yBottom = PlotBottom.toPx()

    // Raster + temperatuurlabels links.
    for (t in m.grid) {
        val y = yOf(t.toDouble(), m)
        drawLine(Ink.white(0.06f), Offset(x0, y), Offset(x1, y), strokeWidth = 1.dp.toPx())
        val txt = measurer.measure("$t°", label)
        drawText(txt, topLeft = Offset(x0 - 8.dp.toPx() - txt.size.width, y - txt.size.height / 2f))
    }
    // Uren onder de as.
    for (h in listOf(0, 6, 12, 18, 24)) {
        val txt = measurer.measure(String.format(java.util.Locale.ROOT, "%02d", h), label)
        drawText(txt, topLeft = Offset(xOf(h.toFloat()) - txt.size.width / 2f, 174.dp.toPx() - txt.size.height * 0.8f))
    }

    // Rest van de dag licht gearceerd, met een stippelstreep "NU".
    val nx = xOf(m.nowH)
    val top = ShadeTop.toPx()
    drawRect(Ink.white(0.018f), topLeft = Offset(nx, top), size = androidx.compose.ui.geometry.Size(x1 - nx, yBottom - top))
    drawLine(
        Ink.white(0.18f), Offset(nx, top), Offset(nx, yBottom), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
    )
    val nu = measurer.measure(
        "NU",
        TextStyle(fontFamily = Manrope, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Ink.Muted),
    )
    drawText(nu, topLeft = Offset((nx - nu.size.width / 2f).coerceIn(x0, x1 - nu.size.width), 0f))

    // Huidig: vlak + lijn.
    if (m.room.size > 1) {
        val line = Path()
        m.room.forEachIndexed { i, (h, v) ->
            val x = xOf(h)
            val y = yOf(v, m)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(xOf(m.room.last().first), yBottom)
            lineTo(xOf(m.room.first().first), yBottom)
            close()
        }
        drawPath(area, Brush.verticalGradient(0f to RoomColor.copy(alpha = 0.16f), 1f to RoomColor.copy(alpha = 0f), startY = PlotTop.toPx(), endY = yBottom))
        drawPath(line, RoomColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Doel: stippel-trap, onderbroken waar de unit uit stond.
    val tgt = Path()
    var prev: Double? = null
    for ((h, v) in m.target) {
        val x = xOf(h)
        when {
            v.isNaN() -> {
                prev?.let { tgt.lineTo(x, yOf(it, m)) }
                prev = null
            }
            prev == null -> {
                tgt.moveTo(x, yOf(v, m))
                prev = v
            }
            else -> {
                tgt.lineTo(x, yOf(prev, m))
                tgt.lineTo(x, yOf(v, m))
                prev = v
            }
        }
    }
    drawPath(
        tgt, TargetColor,
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
    )

    // Eindpunt met de waarde van nu ernaast.
    m.room.lastOrNull()?.let { (h, v) ->
        val c = Offset(xOf(h), yOf(v, m))
        drawCircle(RoomColor.copy(alpha = 0.25f), radius = 6.5.dp.toPx(), center = c)
        drawCircle(RoomColor, radius = 4.dp.toPx(), center = c)
        drawCircle(CardGround, radius = 4.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
        val txt = measurer.measure(
            "${fmtTemp(round1(v))}°",
            TextStyle(fontFamily = Manrope, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.Text),
        )
        val right = c.x + 8.dp.toPx()
        val lx = if (right + txt.size.width > size.width) c.x - 8.dp.toPx() - txt.size.width else right
        drawText(txt, topLeft = Offset(lx, (c.y - 8.dp.toPx() - txt.size.height).coerceAtLeast(0f)))
    }
}

/** Nog niets binnen: het raster met een zachte glans die eroverheen schuift. */
@Composable
private fun ChartLoading() {
    val sweep = rememberInfiniteTransition(label = "chartLoading")
    val t by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1_300, easing = LinearEasing)), label = "sheen")
    val pulse by sweep.animateFloat(0.03f, 0.07f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .testTag(TestTags.DAY_CHART_LOADING)
            .semantics { contentDescription = "Grafiek wordt opgehaald" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x0 = PlotLeft.toPx()
            val x1 = size.width - PlotRightPad.toPx()
            val top = PlotTop.toPx()
            val bottom = PlotBottom.toPx()
            for (i in 0..3) {
                val y = top + (bottom - top) * i / 3f
                drawLine(Ink.white(0.06f), Offset(x0, y), Offset(x1, y), strokeWidth = 1.dp.toPx())
            }
            val r = 10.dp.toPx()
            drawRoundRect(
                Ink.white(pulse),
                topLeft = Offset(x0, top),
                size = androidx.compose.ui.geometry.Size(x1 - x0, bottom - top),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
            val band = (x1 - x0) * 0.4f
            val x = x0 - band + (x1 - x0 + 2 * band) * t
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent, 0.5f to Ink.white(0.08f), 1f to Color.Transparent,
                    startX = x - band / 2, endX = x + band / 2,
                ),
                topLeft = Offset(x0, top),
                size = androidx.compose.ui.geometry.Size(x1 - x0, bottom - top),
            )
        }
    }
}

@Composable
private fun ChartEmpty() {
    Box(Modifier.fillMaxWidth().height(ChartHeight), contentAlignment = Alignment.Center) {
        Text("Nog geen metingen van vandaag", fontFamily = Manrope, fontSize = 12.sp, color = Ink.Faint)
    }
}
