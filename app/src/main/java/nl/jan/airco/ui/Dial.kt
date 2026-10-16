package nl.jan.airco.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.graphicsLayer
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
import android.os.SystemClock
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
    /** Huidige kamertemperatuur; stuurt het streepje op de knop. `null` = verbergen. */
    current: Double?,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    on: Boolean,
    style: ModeStyle,
    adjustable: Boolean,
    glow: Float = 0.55f,
    onChange: (Double) -> Unit,
    /** Huidig = doel: het streepje "klikt vast" in de greep (ring + status "Op temperatuur"). */
    atTarget: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c1 by animateColorAsState(if (on) style.c1 else Ink.OffC1, tween(350), label = "c1")
    val c2 by animateColorAsState(if (on) style.c2 else Ink.OffC2, tween(350), label = "c2")
    val glowAlpha by animateFloatAsState(if (on) glow else 0f, tween(400), label = "glow")
    val numColor = if (on) Color(0xFFF4F6FA) else Ink.Dim

    // Tijdens slepen toont de knop zijn eigen waarde; pas bij loslaten gaat die naar MELCloud.
    var dragValue by remember { mutableStateOf<Double?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val shown = dragValue ?: target
    val dragging = dragValue != null

    val span = (range.endInclusive - range.start).takeIf { it > 0 } ?: 1.0
    val fracTarget = ((shown - range.start) / span).toFloat().coerceIn(0f, 1f)
    // Volgt de vinger direct tijdens slepen; animeert bij wijzigingen van buitenaf.
    // Geanimeerde waarden worden alleen in de teken-/layerfase gelezen: een animatie
    // tekent dan opnieuw zonder de hele knop (teksten, vlakken) te hercomponeren.
    val frac by animateFloatAsState(fracTarget, if (dragging) snap() else tween(220), label = "frac")
    // Het streepje op de knop toont de kamertemperatuur, op dezelfde schaal als de boog
    // (buiten het bereik: tegen de rand). Zo zie je in één oogopslag hoe ver doel en kamer uiteen liggen.
    val currentFrac by animateFloatAsState(
        current?.let { ((it - range.start) / span).toFloat().coerceIn(0f, 1f) } ?: 0f,
        tween(400),
        label = "current",
    )
    val grab by animateFloatAsState(if (dragging || pressed) 1f else 0f, tween(120), label = "grab")
    // Niet tijdens slepen: dan verschuift het doel en klopt "bereikt" (nog) niet.
    val reachedNow = atTarget && on && !dragging
    // Eén keer ingroeien (600 ms) bij het bereiken; bij openen van het scherm staat hij er meteen.
    val reach by animateFloatAsState(if (reachedNow) 1f else 0f, tween(600, easing = FastOutSlowInEasing), label = "reach")

    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val latestTarget by rememberUpdatedState(target)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestRange by rememberUpdatedState(range)
    val latestStep by rememberUpdatedState(step)
    val latestFrac by rememberUpdatedState(fracTarget)
    val latestCurrent by rememberUpdatedState(current)

    Box(
        modifier
            .size(320.dp)
            .semantics {
                contentDescription = "Doeltemperatuur ${fmtTemp(shown)} graden" +
                    if (reachedNow && current != null) ", huidig ${fmtTemp(current)} graden, doel bereikt" else ""
            }
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
                        // requireUnconsumed = false: een net uitlopende scroll mag de eerste
                        // aanraking "opeten" om de fling te stoppen; de greep moet dan toch reageren.
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val unit = size.width / 320f
                        val handleDeg = -135.0 + latestFrac * 270.0
                        val a = Math.toRadians(handleDeg)
                        val handle = Offset(
                            c.x + (128 * unit * sin(a)).toFloat(),
                            c.y - (128 * unit * cos(a)).toFloat(),
                        )
                        // Grijpzone: ruime cirkel om de greep, plus een stuk ring
                        // (±22° langs de boog) zodat je hem niet exact hoeft te raken.
                        val r0 = (down.position - c).getDistance()
                        var dAng = angleOf(down.position, c) - handleDeg
                        if (dAng > 180) dAng -= 360
                        if (dAng < -180) dAng += 360
                        val distToHandle = (down.position - handle).getDistance()
                        // Grijpzone: cirkel van 40dp om de greep, plus een smalle strook ring
                        // (±12° langs de boog). Smaller dan v2.5, dat te veel scrolls ving.
                        val onHandle = distToHandle <= 40.dp.toPx() ||
                            (r0 in (100 * unit)..(165 * unit) && kotlin.math.abs(dAng) <= 12)
                        if (!onHandle) return@awaitEachGesture
                        val precise = distToHandle <= 28.dp.toPx()

                        // Direct zichtbaar "aangeraakt": de greep licht op.
                        pressed = true
                        try {
                        /*
                         * Beslissen: draaien of scrollen. Tot dat moment consumeren we NIETS,
                         * zodat de pagina-scroll kan winnen. We beslissen altijd vóór de
                         * scroll zijn eigen drempel (touch slop) haalt.
                         *  - Beweging langs de boog (horizontaal/diagonaal): pakken na ±5dp.
                         *  - Steil verticale beweging = scrollgebaar: alleen pakken als je
                         *    precies op de greep begon, langzaam beweegt én echt langs de boog
                         *    gaat (op 3/9 uur loopt de boog verticaal). Anders: scrollen.
                         *  - Even stil vasthouden (200 ms, < halve slop) pakt altijd: de
                         *    bewuste route, ook op 3/9 uur.
                         */
                        val slop = viewConfiguration.touchSlop
                        val commitDist = 0.6f * slop
                        val holdMs = 200L
                        var holdEligible = true
                        var pos = down.position
                        var decided = false
                        while (!decided) {
                            val waited = SystemClock.uptimeMillis() - down.uptimeMillis
                            val event = if (holdEligible) {
                                withTimeoutOrNull((holdMs - waited).coerceAtLeast(1L)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                decided = true // stil vastgehouden: bewust pakken
                                break
                            }
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (!ch.pressed || ch.isConsumed) return@awaitEachGesture // tik, of iemand anders nam het
                            pos = ch.position
                            val d = pos - down.position
                            val dist = d.getDistance()
                            if (dist > 0.5f * slop) holdEligible = false
                            if (dist < commitDist) continue

                            val fromC = pos - c
                            val len = fromC.getDistance().coerceAtLeast(1f)
                            val radial = Offset(fromC.x / len, fromC.y / len)
                            val tangent = Offset(-radial.y, radial.x)
                            val t = kotlin.math.abs(d.x * tangent.x + d.y * tangent.y)
                            val rad = kotlin.math.abs(d.x * radial.x + d.y * radial.y)
                            val steepVertical = kotlin.math.abs(d.y) > 2f * kotlin.math.abs(d.x)
                            val elapsed = (ch.uptimeMillis - down.uptimeMillis).coerceAtLeast(1L)
                            val slow = dist / elapsed < 0.5f * density // < 0,5 dp/ms

                            decided = if (!steepVertical) {
                                t >= rad
                            } else {
                                precise && slow && t >= 2.5f * rad
                            }
                            // Drempel gehaald zonder besluit: dit is een scroll. Loslaten.
                            if (!decided && dist >= slop) return@awaitEachGesture
                        }
                        // Vanaf hier is het een draai: de pagina mag niet meer meebewegen.
                        currentEvent.changes.firstOrNull { it.id == down.id }?.consume()

                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val r = latestRange
                        val stepSize = latestStep
                        var value = latestTarget
                        // Rekenen vanaf het aanraakpunt: de beweging vóór het besluit telt mee.
                        var prev = angleOf(down.position, c)
                        dragValue = value

                        fun follow(p: Offset) {
                            val ang = angleOf(p, c)
                            var dd = ang - prev
                            if (dd > 180) dd -= 360
                            if (dd < -180) dd += 360
                            prev = ang
                            value = (value + dd / 270.0 * (r.endInclusive - r.start)).coerceIn(r)
                            val snapped = (round(value / stepSize) * stepSize).coerceIn(r)
                            val before = dragValue
                            if (snapped != before) {
                                if (before != null && passesRoom(before, snapped, latestCurrent)) {
                                    // Doel raakt of passeert de kamertemperatuur (het rode streepje):
                                    // één stevige klik in plaats van het gewone fijne tikje.
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                } else {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                dragValue = snapped
                            }
                        }
                        follow(pos)

                        val completed = drag(down.id) { change ->
                            follow(change.position)
                            change.consume()
                        }

                        val final = dragValue
                        // Eerst doorgeven, dan pas de eigen waarde loslaten: geen terugflits.
                        if (completed && final != null && final != latestTarget) latestOnChange(final)
                        } finally {
                            // Ook bij een onderbroken gebaar: greep nooit laten "hangen".
                            dragValue = null
                            pressed = false
                        }
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
                .background(KnobOuter, CircleShape)
                .border(1.dp, Ink.white(0.06f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(KnobRibs)
                    .border(1.dp, Ink.white(0.05f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (current != null) Box(
                    Modifier
                        .size(184.dp)
                        .graphicsLayer {
                            rotationZ = -135f + currentFrac * 270f
                            alpha = 1f - reach // valt samen met de greep: opgaan in de ring
                        }
                        .semantics { contentDescription = "Kamertemperatuur ${fmtTemp(current)} graden" },
                ) {
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
                        .background(KnobCenter, CircleShape)
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
                        when {
                            !on -> "Uit"
                            reachedNow -> "Op temperatuur"
                            else -> style.verb
                        },
                        fontFamily = Manrope,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = if (reachedNow) c1 else c2,
                    )
                }
            }
        }

        // Greep op de boog.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val unit = size.width / 320f
            rotate(-135f + frac * 270f, center) {
                val p = Offset(center.x, center.y - 128 * unit)
                val glowColor = lerp(c2, c1, reach)
                if (reach > 0f) {
                    // "Vastgeklikt": dunne ring om de greep.
                    drawCircle(
                        color = c1.copy(alpha = 0.6f * reach),
                        radius = 20 * unit * (0.7f + 0.3f * reach) + 4 * grab * unit,
                        center = p,
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(glowColor.copy(alpha = 0.55f + 0.3f * grab), Color.Transparent),
                        center = p,
                        radius = (30 + 10 * grab) * unit,
                    ),
                    radius = (30 + 10 * grab) * unit,
                    center = p,
                )
                drawCircle(glowColor.copy(alpha = 0.25f + 0.15f * grab), radius = (19 + 4 * grab) * unit, center = p)
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

/**
 * True als de doeltemperatuur van [from] naar [to] de kamertemperatuur bereikt of
 * passeert. Eén keer per passage: weer weggaan van precies de kamertemperatuur telt niet.
 */
internal fun passesRoom(from: Double, to: Double, room: Double?): Boolean {
    if (room == null) return false
    val before = from.compareTo(room).coerceIn(-1, 1)
    val after = to.compareTo(room).coerceIn(-1, 1)
    return before != 0 && before != after
}

// Vaste verlopen van de knop: één keer aanmaken in plaats van bij elke recompositie.
private val KnobOuter = Brush.linearGradient(listOf(Color(0xFF2C313C), Color(0xFF11141A)))
private val KnobRibs = Brush.sweepGradient(
    listOf(
        Color(0xFF252A34), Color(0xFF3B414D), Color(0xFF1F232B), Color(0xFF363C48),
        Color(0xFF22262F), Color(0xFF3B414D), Color(0xFF252A34),
    ),
)
private val KnobCenter = Brush.radialGradient(listOf(Color(0xFF1D212A), Color(0xFF0E1116)))
private val MiniCenter = Brush.radialGradient(listOf(Color(0xFF222631), Color(0xFF0F1217)))

/** Kleine, niet-interactieve versie voor de kaarten in het overzicht (104dp). */
@Composable
fun MiniDial(
    target: Double,
    range: ClosedFloatingPointRange<Double>,
    on: Boolean,
    style: ModeStyle,
    showTarget: Boolean,
    atTarget: Boolean = false,
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
    // Kaarten verversen vaak: geen ingroei-animatie, alleen een zachte overgang.
    val reach by animateFloatAsState(if (atTarget && on) 1f else 0f, tween(300), label = "mreach")

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
                .background(MiniCenter, CircleShape)
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
                if (reach > 0f) {
                    drawCircle(c1.copy(alpha = 0.6f * reach), radius = 11 * unit, center = p, style = Stroke(1.dp.toPx()))
                }
                drawCircle(
                    brush = Brush.radialGradient(listOf(lerp(c2, c1, reach).copy(alpha = 0.5f), Color.Transparent), center = p, radius = 14 * unit),
                    radius = 14 * unit,
                    center = p,
                )
                drawCircle(c2.copy(alpha = 0.25f), radius = 9 * unit, center = p)
                drawCircle(Color.White, radius = 6 * unit, center = p)
            }
        }
    }
}
