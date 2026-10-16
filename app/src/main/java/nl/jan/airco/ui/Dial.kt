package nl.jan.airco.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.round

/**
 * Draaiknop zoals in het ontwerp: 270°-boog met kleurverloop, maatstreepjes,
 * metalen knop met indicator en een gloeiende greep. Slepen over de knop
 * zet de doeltemperatuur.
 */
@Composable
fun TemperatureDial(
    target: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    on: Boolean,
    style: ModeStyle,
    adjustable: Boolean,
    glow: Float = 0.55f,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c1 by animateColorAsState(if (on) style.c1 else Ink.OffC1, tween(350), label = "c1")
    val c2 by animateColorAsState(if (on) style.c2 else Ink.OffC2, tween(350), label = "c2")
    val glowAlpha by animateFloatAsState(if (on) glow else 0f, tween(400), label = "glow")
    val numColor = if (on) Color(0xFFF4F6FA) else Ink.Dim

    // Tijdens slepen toont de knop zijn eigen waarde; pas bij loslaten gaat die naar MELCloud.
    var dragValue by remember { mutableStateOf<Double?>(null) }
    val shown = dragValue ?: target
    val dragging = dragValue != null

    val span = (range.endInclusive - range.start).takeIf { it > 0 } ?: 1.0
    val fracTarget = ((shown - range.start) / span).toFloat().coerceIn(0f, 1f)
    // Volgt de vinger direct tijdens slepen; animeert bij wijzigingen van buitenaf.
    val frac by animateFloatAsState(fracTarget, if (dragging) snap() else tween(220), label = "frac")
    val deg = -135f + frac * 270f
    val grab by animateFloatAsState(if (dragging) 1f else 0f, tween(150), label = "grab")

    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val latestTarget by rememberUpdatedState(target)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestRange by rememberUpdatedState(range)
    val latestStep by rememberUpdatedState(step)
    val latestFrac by rememberUpdatedState(fracTarget)

    Box(
        modifier
            .size(320.dp)
            .semantics { contentDescription = "Doeltemperatuur ${fmtTemp(shown)} graden" }
            .then(
                if (!adjustable) Modifier else Modifier.pointerInput(Unit) {
                    /*
                     * Alleen de greep is te pakken, en pas na een echte beweging:
                     *  - Bij aanraken gebeurt niets en wordt niets geconsumeerd, zodat
                     *    scrollen (en trekken-om-te-vernieuwen) gewoon werkt.
                     *  - Begint de aanraking niet op de greep (±36dp), dan doet de knop niets.
                     *  - Op de greep: na de touch slop is hij "gepakt" (tikje), daarna
                     *    draait hij relatief mee met de hoekverandering — geen sprong
                     *    naar de vinger.
                     *  - Loslaten verstuurt de nieuwe waarde; een afgebroken gebaar niet.
                     */
                    fun angleOf(p: Offset, c: Offset): Double =
                        Math.toDegrees(atan2((p.x - c.x).toDouble(), (c.y - p.y).toDouble()))

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val unit = size.width / 320f
                        val a = Math.toRadians(-135.0 + latestFrac * 270.0)
                        val handle = Offset(
                            c.x + (128 * unit * sin(a)).toFloat(),
                            c.y - (128 * unit * cos(a)).toFloat(),
                        )
                        if ((down.position - handle).getDistance() > 36.dp.toPx()) return@awaitEachGesture

                        val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture

                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val r = latestRange
                        val stepSize = latestStep
                        var value = latestTarget
                        var prev = angleOf(start.position, c)
                        dragValue = value

                        val completed = drag(start.id) { change ->
                            val ang = angleOf(change.position, c)
                            var d = ang - prev
                            if (d > 180) d -= 360
                            if (d < -180) d += 360
                            prev = ang
                            value = (value + d / 270.0 * (r.endInclusive - r.start)).coerceIn(r)
                            val snapped = (round(value / stepSize) * stepSize).coerceIn(r)
                            if (snapped != dragValue) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                dragValue = snapped
                            }
                            change.consume()
                        }

                        val final = dragValue
                        // Eerst doorgeven, dan pas de eigen waarde loslaten: geen terugflits.
                        if (completed && final != null && final != latestTarget) latestOnChange(final)
                        dragValue = null
                    }
                },
            ),
    ) {
        // Gloed, maatstreepjes en de boog.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val unit = size.width / 320f // 1 ontwerp-px

            if (glowAlpha > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to c2.copy(alpha = 0.53f * glowAlpha),
                        0.68f to Color.Transparent,
                        center = center,
                        radius = 150 * unit,
                    ),
                    radius = 150 * unit,
                    center = center,
                )
            }

            for (i in 0..30) {
                val major = i % 2 == 0
                val lit = i / 30f <= frac + 0.0001f
                val color = when {
                    !lit -> Ink.white(0.12f)
                    on -> Ink.white(0.78f)
                    else -> Ink.white(0.30f)
                }
                val h = (if (major) 10 else 5) * unit
                rotate(-135f + i * 9f, center) {
                    drawLine(
                        color = color,
                        start = Offset(center.x, 0f + unit),
                        end = Offset(center.x, h),
                        strokeWidth = 2 * unit,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }

            val ringRadius = 128 * unit
            val ringWidth = 11 * unit
            val arcTopLeft = Offset(center.x - ringRadius, center.y - ringRadius)
            val arcSize = Size(ringRadius * 2, ringRadius * 2)
            // Canvas-hoek 0° = 3 uur; het ontwerp start op 225° vanaf 12 uur = 135° hier.
            rotate(135f, center) {
                drawArc(
                    brush = Brush.sweepGradient(0f to c1, 0.75f to c2, 1f to c2, center = center),
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(ringWidth),
                )
                drawArc(
                    color = Ink.Track,
                    startAngle = frac * 270f,
                    sweepAngle = 270f - frac * 270f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(ringWidth),
                )
            }
        }

        // Knop: buitenring, geribbelde ring met indicator, ingezonken midden.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(204.dp)
                .shadow(24.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .background(Brush.linearGradient(listOf(Color(0xFF2C313C), Color(0xFF11141A))), CircleShape)
                .border(1.dp, Ink.white(0.06f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFF252A34), Color(0xFF3B414D), Color(0xFF1F232B), Color(0xFF363C48),
                                Color(0xFF22262F), Color(0xFF3B414D), Color(0xFF252A34),
                            ),
                        ),
                    )
                    .border(1.dp, Ink.white(0.05f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(184.dp).rotate(deg)) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(width = 4.dp, height = 14.dp)
                            .shadow(6.dp, RoundedCornerShape(2.dp), ambientColor = c2, spotColor = c2)
                            .background(c2, RoundedCornerShape(2.dp)),
                    )
                }
                Column(
                    Modifier
                        .size(128.dp)
                        .background(
                            Brush.radialGradient(listOf(Color(0xFF1D212A), Color(0xFF0E1116))),
                            CircleShape,
                        )
                        .border(1.dp, Ink.white(0.05f), CircleShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "DOEL",
                        fontFamily = Manrope,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        color = Ink.Dim,
                    )
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (adjustable) floor(shown).toInt().toString() else "—",
                            fontFamily = Sora,
                            fontWeight = FontWeight.ExtraLight,
                            fontSize = 50.sp,
                            letterSpacing = (-2).sp,
                            lineHeight = 52.sp,
                            color = numColor,
                        )
                        if (adjustable) {
                            Text(
                                (if (shown % 1.0 != 0.0) ",5" else "") + "°",
                                fontFamily = Sora,
                                fontWeight = FontWeight.Light,
                                fontSize = 20.sp,
                                color = numColor,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    Text(
                        if (on) style.verb else "Uit",
                        fontFamily = Manrope,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = c2,
                    )
                }
            }
        }

        // Greep op de boog.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val unit = size.width / 320f
            rotate(deg, center) {
                val p = Offset(center.x, center.y - 128 * unit)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(c2.copy(alpha = 0.55f + 0.3f * grab), Color.Transparent),
                        center = p,
                        radius = (30 + 10 * grab) * unit,
                    ),
                    radius = (30 + 10 * grab) * unit,
                    center = p,
                )
                drawCircle(c2.copy(alpha = 0.25f + 0.15f * grab), radius = (19 + 4 * grab) * unit, center = p)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White, Color(0xFFDCE1EA)),
                        center = Offset(p.x - 3 * unit, p.y - 4 * unit),
                        radius = (16 + 4 * grab) * unit,
                    ),
                    radius = (14 + 4 * grab) * unit,
                    center = p,
                )
            }
        }
    }
}

/** Kleine, niet-interactieve versie voor de kaarten in het overzicht (104dp). */
@Composable
fun MiniDial(
    target: Double,
    range: ClosedFloatingPointRange<Double>,
    on: Boolean,
    style: ModeStyle,
    showTarget: Boolean,
    modifier: Modifier = Modifier,
) {
    val c1 by animateColorAsState(if (on) style.c1 else Ink.OffC1, tween(350), label = "mc1")
    val c2 by animateColorAsState(if (on) style.c2 else Ink.OffC2, tween(350), label = "mc2")
    val span = (range.endInclusive - range.start).takeIf { it > 0 } ?: 1.0
    val frac by animateFloatAsState(
        ((target - range.start) / span).toFloat().coerceIn(0f, 1f),
        tween(220),
        label = "mfrac",
    )
    val numColor = if (on) Color(0xFFF4F6FA) else Ink.Dim

    Box(modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val unit = size.width / 104f
            val r = 47 * unit
            val topLeft = Offset(center.x - r, center.y - r)
            val arc = Size(r * 2, r * 2)
            rotate(135f, center) {
                drawArc(
                    brush = Brush.sweepGradient(0f to c1, 0.75f to c2, 1f to c2, center = center),
                    startAngle = 0f, sweepAngle = 270f, useCenter = false,
                    topLeft = topLeft, size = arc, style = Stroke(7 * unit),
                )
                drawArc(
                    color = Color(0xFF1C2029),
                    startAngle = frac * 270f, sweepAngle = 270f - frac * 270f, useCenter = false,
                    topLeft = topLeft, size = arc, style = Stroke(7 * unit),
                )
            }
        }
        Column(
            Modifier
                .size(72.dp)
                .shadow(10.dp, CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF222631), Color(0xFF0F1217))), CircleShape)
                .border(1.dp, Ink.white(0.05f), CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (showTarget) floor(target).toInt().toString() else "—",
                    fontFamily = Sora,
                    fontWeight = FontWeight.ExtraLight,
                    fontSize = 28.sp,
                    letterSpacing = (-1).sp,
                    lineHeight = 30.sp,
                    color = numColor,
                )
                if (showTarget) {
                    Text(
                        (if (target % 1.0 != 0.0) ",5" else "") + "°",
                        fontFamily = Sora,
                        fontWeight = FontWeight.Light,
                        fontSize = 12.sp,
                        color = numColor,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Text(
                "DOEL",
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.6.sp,
                color = Ink.Dim,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val unit = size.width / 104f
            rotate(-135f + frac * 270f, center) {
                val p = Offset(center.x, center.y - 47 * unit)
                drawCircle(
                    brush = Brush.radialGradient(listOf(c2.copy(alpha = 0.5f), Color.Transparent), center = p, radius = 14 * unit),
                    radius = 14 * unit,
                    center = p,
                )
                drawCircle(c2.copy(alpha = 0.25f), radius = 9 * unit, center = p)
                drawCircle(Color.White, radius = 6 * unit, center = p)
            }
        }
    }
}
