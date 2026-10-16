@file:OptIn(ExperimentalMaterial3Api::class)

package nl.jan.airco.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import kotlin.math.roundToInt
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import nl.jan.airco.AircoActions
import nl.jan.airco.UiState
import nl.jan.airco.data.AircoUnit
import nl.jan.airco.data.FanSpeed
import nl.jan.airco.data.Mode


@Composable
fun UnitsScreen(state: UiState, vm: AircoActions) {
    // Op de voorgrond: automatisch verversen (ook IR-wijzigingen); weg: stoppen.
    LifecycleResumeEffect(Unit) {
        vm.onForeground()
        onPauseOrDispose { vm.onBackground() }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    // Indeling op vensterbreedte: telefoon (overzicht ⇄ detail) of lijst + bediening naast elkaar.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val layoutMode = layoutModeFor(screenWidthDp)
    val twoPane = layoutMode != LayoutMode.Compact

    // null = overzicht "Alle ruimtes"; anders de bedieningspagina van die unit.
    // In twee-panelen: de gekozen ruimte (null = de eerste).
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    // De laatst geopende unit blijft op de detaillaag staan, ook tijdens het teruggaan.
    var shownId by rememberSaveable { mutableStateOf<String?>(null) }
    if (detailId != null && detailId != shownId) shownId = detailId
    val detailUnit = state.units.firstOrNull { it.id == shownId } ?: state.units.firstOrNull()
    val detailScroll = rememberScrollState()
    val overviewScroll = rememberScrollState()

    /*
     * Overgang overzicht ⇄ detail, stottervrij:
     *  - Beide schermen blijven altijd gecomponeerd (detail vooraf "opgewarmd");
     *    er wordt bij het wisselen niets opgebouwd, alleen verschoven en vervaagd.
     *  - progress (0 = overzicht, 1 = detail) wordt alleen in graphicsLayer gelezen:
     *    geen recompositie of layout per frame, alles op de GPU.
     *  - Material "shared axis X": 30dp schuiven, eerst uitvagen, dan invagen,
     *    dus nooit twee half-doorzichtige schermen over elkaar.
     *  - Terug-veeggebaar (Android 14+) scrubt de overgang met je vinger mee.
     */
    val progress = remember { Animatable(if (detailId != null) 1f else 0f) }
    val emphasized = remember { CubicBezierEasing(0.2f, 0f, 0f, 1f) }
    // Afgebroken terugveeg: deze teller herstart de effect hieronder, die terug naar 1 animeert.
    var backCancelled by remember { mutableIntStateOf(0) }
    LaunchedEffect(detailId != null, backCancelled) {
        if (detailId != null) detailScroll.scrollTo(0)
        progress.animateTo(if (detailId != null) 1f else 0f, tween(320, easing = emphasized))
    }
    PredictiveBackHandler(enabled = detailId != null && !twoPane) { events ->
        try {
            events.collect { e -> progress.snapTo(1f - e.progress) }
            detailId = null // voltooid: de LaunchedEffect animeert vanaf hier naar het overzicht
        } catch (e: kotlinx.coroutines.CancellationException) {
            backCancelled++ // terugveeg afgebroken: terugveren naar detail
            throw e
        }
    }
    val overviewVisible by remember { derivedStateOf { progress.value < 1f } }
    val detailVisible by remember { derivedStateOf { progress.value > 0f } }
    val shift = with(LocalDensity.current) { 30.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .drawBehind {
                // Zachte lichtval van boven, zoals in het ontwerp.
                drawRect(
                    Brush.radialGradient(
                        0f to Ink.GroundTop,
                        1f to Ink.Ground,
                        center = Offset(size.width / 2, 0f),
                        radius = size.height * 0.55f,
                    ),
                )
            },
    ) {
        PullToRefreshBox(
            isRefreshing = state.pullRefreshing,
            onRefresh = { vm.refresh(userInitiated = true) },
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        ) {
            if (twoPane) {
                TwoPane(
                    state = state,
                    vm = vm,
                    wide = layoutMode == LayoutMode.Expanded,
                    listWidth = if (layoutMode == LayoutMode.Expanded) 380.dp else mediumListWidthDp(screenWidthDp).dp,
                    selected = state.units.firstOrNull { it.id == detailId } ?: state.units.firstOrNull(),
                    onSelect = { detailId = it },
                )
            } else Box(Modifier.fillMaxSize()) {
                // --- Overzicht
                Column(
                    Modifier
                        .fillMaxSize()
                        .testTag(TestTags.OVERVIEW)
                        .placedIf(overviewVisible)
                        .graphicsLayer {
                            val p = progress.value
                            translationX = -shift * p
                            alpha = 1f - (p / 0.35f).coerceIn(0f, 1f)
                        }
                        .verticalScroll(overviewScroll)
                        .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 24.dp),
                ) {
                    Header(vm, title = "Alle ruimtes", onBack = null)
                    if (state.units.isEmpty()) {
                        EmptyState(state, vm)
                    } else {
                        Overview(state, vm, onOpen = { detailId = it })
                    }
                }

                // --- Detail (bovenop)
                if (detailUnit != null) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .testTag(TestTags.DETAIL_PANE)
                            .placedIf(detailVisible)
                            .graphicsLayer {
                                val p = progress.value
                                translationX = shift * (1f - p)
                                alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                            }
                            .verticalScroll(detailScroll)
                            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 24.dp),
                    ) {
                        Header(vm, title = "Airco", onBack = { detailId = null })
                        if (state.units.size > 1) {
                            Spacer(Modifier.height(18.dp))
                            RoomSwitcher(state.units, detailUnit.id) { detailId = it }
                        }
                        Spacer(Modifier.height(22.dp))
                        UnitControls(detailUnit, vm)
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp)) {
            Snackbar(it, containerColor = Color(0xFF1C2029), contentColor = Ink.Text)
        }
    }
}

/**
 * Wel meten en gecomponeerd houden, maar alleen plaatsen (tekenen, aanraakbaar)
 * als [visible]. Zo is een verborgen scherm direct klaar zonder iets te kosten.
 */
private fun Modifier.placedIf(visible: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (visible) placeable.place(0, 0)
    }
}

// ----------------------------------------------------------------------------- header

@Composable
private fun Header(
    vm: AircoActions,
    title: String,
    onBack: (() -> Unit)?,
    titleSize: TextUnit = 24.sp,
    showMore: Boolean = true,
) {
    var spin by remember { mutableFloatStateOf(0f) }
    val spinAnim by animateFloatAsState(spin, tween(600), label = "spin")

    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        // Bediening: glazen terugknop vóór de titel (zoals in het ontwerp).
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                GlassIconButton(Icons.Back, "Terug naar overzicht", tint = Color(0xFFE4E8EF), onClick = onBack)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                fontFamily = Sora,
                fontWeight = FontWeight.SemiBold,
                fontSize = titleSize,
                letterSpacing = (-0.5).sp,
                color = Ink.Text,
            )
        }
        GlassIconButton(Icons.Refresh, "Vernieuwen", iconRotation = { spinAnim }) {
            spin += 360f
            vm.refresh(userInitiated = true)
        }
        if (showMore) {
            Spacer(Modifier.width(8.dp))
            MoreMenuButton(vm)
        }
    }
}

@Composable
private fun MoreMenuButton(vm: AircoActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        GlassIconButton(Icons.More, "Meer opties") { menuOpen = true }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Color(0xFF1C2029),
        ) {
            DropdownMenuItem(
                text = { Text("Uitloggen", fontFamily = Manrope, color = Ink.Text) },
                onClick = { menuOpen = false; vm.logout() },
            )
        }
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    label: String,
    /** Gelezen in de layerfase, zodat het draaien geen recompositie per frame kost. */
    iconRotation: (() -> Float)? = null,
    tint: Color = Ink.IconSoft,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(Ink.white(0.04f))
            .border(1.dp, Ink.white(0.08f), shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .then(if (iconRotation != null) Modifier.graphicsLayer { rotationZ = iconRotation() } else Modifier),
        )
    }
}

// ----------------------------------------------------------------------------- kamerkiezer

@Composable
private fun RoomSwitcher(units: List<AircoUnit>, selectedId: String, onPick: (String) -> Unit) {
    val scrollable = units.size > 3
    Row(
        Modifier.fillMaxWidth().then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        units.forEach { u ->
            RoomCard(
                unit = u,
                selected = u.id == selectedId,
                modifier = if (scrollable) Modifier.width(110.dp) else Modifier.weight(1f),
                onClick = { onPick(u.id) },
            )
        }
    }
}

@Composable
private fun RoomCard(unit: AircoUnit, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val bg by animateColorAsState(Ink.white(if (selected) 0.09f else 0.03f), tween(250), label = "bg")
    val border by animateColorAsState(Ink.white(if (selected) 0.18f else 0.06f), tween(250), label = "bd")
    val style = unit.mode.style()
    val dot = when {
        unit.isInError -> Ink.Error
        unit.power && unit.isConnected -> style.c2
        else -> Color(0xFF4A5160)
    }
    Column(
        modifier
            .height(76.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                unit.name,
                fontFamily = Manrope,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (selected) Ink.TextStrong else Color(0xFFA3AABB),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(7.dp)
                    .then(
                        if (dot != Color(0xFF4A5160)) {
                            Modifier.shadow(6.dp, CircleShape, ambientColor = dot, spotColor = dot)
                        } else Modifier,
                    )
                    .background(dot, CircleShape),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                unit.roomTemperature?.let { "${fmtTemp(it)}°" } ?: "–",
                fontFamily = Sora,
                fontWeight = FontWeight.Light,
                fontSize = 22.sp,
                letterSpacing = (-0.4).sp,
                color = Ink.TextStrong,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    !unit.isConnected -> "Offline"
                    unit.power -> "${fmtTemp(unit.setTemperature)}°"
                    else -> "Uit"
                },
                fontFamily = Manrope,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                color = Ink.Muted,
            )
        }
    }
}

// ----------------------------------------------------------------------------- bediening

/** Telefoon: kop, draaiknop, modus en ventilator onder elkaar (ongewijzigd ontwerp). */
@Composable
private fun UnitControls(unit: AircoUnit, vm: AircoActions) {
    DetailHeader(unit, vm)
    Spacer(Modifier.height(8.dp))
    DialBlock(unit, vm)
    Spacer(Modifier.height(24.dp))
    ModeSection(unit, unit.isConnected, vm)
    Spacer(Modifier.height(20.dp))
    FanSection(unit, unit.mode.style(), unit.isConnected, vm)
}

/** Naam + status + aan/uit; maten per schermformaat. */
@Composable
private fun DetailHeader(
    unit: AircoUnit,
    vm: AircoActions,
    nameSize: TextUnit = 28.sp,
    statusSize: TextUnit = 13.sp,
    toggleWidth: Dp = 66.dp,
    toggleHeight: Dp = 38.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    val on = unit.power
    val style = unit.mode.style()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                unit.name,
                fontFamily = Sora,
                fontWeight = FontWeight.SemiBold,
                fontSize = nameSize,
                letterSpacing = (-0.8).sp,
                color = Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(TestTags.DETAIL_NAME),
            )
            Row {
                Text(
                    "Kamer ${fmtRoom(unit.roomTemperature)} · ",
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Medium,
                    fontSize = statusSize,
                    color = Ink.Muted2,
                )
                Text(
                    statusText(unit),
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Medium,
                    fontSize = statusSize,
                    color = when {
                        unit.isInError || !unit.isConnected -> Ink.Error
                        on -> style.c1
                        else -> Ink.Dim
                    },
                )
            }
        }
        PowerToggle(
            on = on,
            style = style,
            enabled = unit.isConnected,
            width = toggleWidth,
            height = toggleHeight,
        ) { vm.setPower(unit, !on) }
        trailing?.invoke()
    }
}

/**
 * Draaiknop met −/+ in de opening onderaan, als één blok van 320×314dp.
 * Is er minder breedte (smalle foldable), dan schaalt het hele blok mee;
 * aanraken blijft kloppen omdat Compose de schaal meeneemt in de coördinaten.
 */
@Composable
private fun DialBlock(unit: AircoUnit, vm: AircoActions) {
    val on = unit.power
    val style = unit.mode.style()
    val range = unit.capabilities.rangeFor(unit.mode)
    val enabled = unit.isConnected
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val scale = (maxWidth / 320.dp).coerceIn(0.6f, 1f)
        Box(Modifier.scaledBy(scale).size(width = 320.dp, height = 314.dp).testTag(TestTags.DIAL)) {
            TemperatureDial(
                target = unit.setTemperature,
                current = unit.roomTemperature,
                range = range ?: unit.capabilities.heatRange,
                step = unit.capabilities.step,
                on = on,
                style = style,
                adjustable = enabled && range != null,
                onChange = { vm.setTemperature(unit, it) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            // −/+ in de opening onderaan de knop (bovenop, dus eerst aanraakbaar).
            Row(
                Modifier.width(268.dp).align(Alignment.BottomCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundStepButton(Icons.Minus, "Temperatuur omlaag", enabled && range != null && unit.setTemperature > range.start) {
                    vm.changeTemperature(unit, -1)
                }
                Text(
                    range?.let { "${fmtTemp(it.start)}° — ${fmtTemp(it.endInclusive)}°" } ?: "Geen setpoint",
                    fontFamily = Manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.9.sp,
                    color = Ink.Faint,
                    // Het label ligt over de onderkant van de draaiknop: zelf aanraakdoel zijn
                    // (zonder te consumeren) zodat een tik hier niet doorvalt naar de knop.
                    // Alleen het label, niet de hele rij, zodat de greep bij min/max pakbaar blijft.
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                RoundStepButton(Icons.Plus, "Temperatuur omhoog", enabled && range != null && unit.setTemperature < range.endInclusive) {
                    vm.changeTemperature(unit, +1)
                }
            }
        }
    }
}

/** Meet de inhoud op ware grootte en tekent hem geschaald; neemt ook alleen de geschaalde ruimte in. */
private fun Modifier.scaledBy(scale: Float): Modifier = if (scale >= 0.999f) this else layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    val w = (placeable.width * scale).roundToInt()
    val h = (placeable.height * scale).roundToInt()
    layout(w, h) {
        placeable.placeWithLayer((w - placeable.width) / 2, (h - placeable.height) / 2) {
            scaleX = scale
            scaleY = scale
        }
    }
}

private fun statusText(unit: AircoUnit): String {
    if (!unit.isConnected) return "Offline"
    if (unit.isInError) return "Storing"
    if (!unit.power) return "Standby"
    val t = fmtTemp(unit.setTemperature)
    return when (unit.mode) {
        Mode.Fan -> "Ventileren"
        Mode.Dry -> "Ontvochtigen"
        Mode.Automatic -> "Houdt $t° vast"
        Mode.Heat -> "Verwarmt naar $t°"
        Mode.Cool -> "Koelt naar $t°"
        null -> "Aan"
    }
}

@Composable
private fun PowerToggle(
    on: Boolean,
    style: ModeStyle,
    enabled: Boolean,
    width: Dp = 66.dp,
    height: Dp = 38.dp,
    label: String = "",
    onToggle: () -> Unit,
) {
    val knob = height - 8.dp
    val view = LocalView.current
    val knobX by animateDpAsState(
        if (on) width - knob - 5.dp else 3.dp,
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "knob",
    )
    val iconColor by animateColorAsState(if (on) style.c2 else Color(0xFF6B7385), tween(300), label = "pi")
    val shape = RoundedCornerShape(height / 2)
    Box(
        Modifier
            .size(width = width, height = height)
            .then(if (on) Modifier.shadow(14.dp, shape, ambientColor = style.c2, spotColor = style.c2) else Modifier)
            .clip(shape)
            .background(
                if (on) Brush.linearGradient(listOf(style.c1, style.c2)) else Brush.linearGradient(
                    listOf(Color(0xFF1C2029), Color(0xFF1C2029)),
                ),
            )
            .border(1.dp, Ink.white(0.1f), shape)
            .clickable(enabled = enabled, role = Role.Switch) {
                view.powerHaptic(turningOn = !on)
                onToggle()
            }
            .semantics {
                contentDescription = "$label ${if (on) "aan, tik om uit te zetten" else "uit, tik om aan te zetten"}".trim()
            },
    ) {
        Box(
            Modifier
                // Lambda-offset: de veer-animatie verschuift alleen, zonder recompositie per frame.
                .offset { IntOffset(knobX.roundToPx(), 3.dp.roundToPx()) }
                .size(knob)
                .shadow(5.dp, CircleShape)
                .background(Brush.linearGradient(listOf(Color.White, Color(0xFFD9DEE8))), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Power, contentDescription = null, tint = iconColor, modifier = Modifier.size(knob / 2))
        }
    }
}

@Composable
private fun RoundStepButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    size: Dp = 52.dp,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        Modifier
            .size(size)
            .shadow(if (size > 48.dp) 10.dp else 6.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF222731), Color(0xFF14171D))))
            .border(1.dp, Ink.white(0.09f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button) {
                // Voelbaar klikje bij elke tik. Bij de tik zelf, niet bij het indrukken:
                // zo voel je niets als je over de knop heen scrolt.
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) Color(0xFFE4E8EF) else Color(0xFF4A5160),
            modifier = Modifier.size(size * 0.38f),
        )
    }
}

// ----------------------------------------------------------------------------- modus

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        color = Ink.Muted,
        modifier = modifier,
    )
}

@Composable
private fun ModeSection(unit: AircoUnit, enabled: Boolean, vm: AircoActions) {
    SectionTitle("Modus")
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        unit.capabilities.modes.forEach { mode ->
            ModeButton(mode, active = unit.mode == mode, enabled = enabled) { vm.setMode(unit, mode) }
        }
    }
}

@Composable
private fun RowScope.ModeButton(mode: Mode, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val s = mode.style()
    val shape = RoundedCornerShape(18.dp)
    val border by animateColorAsState(if (active) s.c2.copy(alpha = 0.6f) else Ink.white(0.07f), tween(250), label = "mb")
    val icon by animateColorAsState(if (active) s.c1 else Ink.Muted2, tween(250), label = "mi")
    val label by animateColorAsState(if (active) Ink.TextStrong else Ink.Muted2, tween(250), label = "ml")
    Column(
        Modifier
            .weight(1f)
            .height(68.dp)
            .then(if (active) Modifier.shadow(12.dp, shape, ambientColor = s.c2, spotColor = s.c2) else Modifier)
            .clip(shape)
            .background(
                if (active) {
                    Brush.linearGradient(listOf(s.c2.copy(alpha = 0.20f), s.c2.copy(alpha = 0.07f)))
                } else {
                    Brush.linearGradient(listOf(Ink.white(0.035f), Ink.white(0.035f)))
                },
            )
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = active },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(s.icon, contentDescription = null, tint = icon, modifier = Modifier.size(22.dp))
        Text(mode.label, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = label)
    }
}

// ----------------------------------------------------------------------------- ventilator

@Composable
private fun FanSection(unit: AircoUnit, style: ModeStyle, enabled: Boolean, vm: AircoActions) {
    val on = unit.power
    val c1 = if (on) style.c1 else Ink.OffC1
    val c2 = if (on) style.c2 else Ink.OffC2
    val speeds = unit.capabilities.fanSpeeds
    val bars = speeds.filter { it != FanSpeed.Auto }
    val level = bars.indexOf(unit.fanSpeed) + 1 // 0 = auto
    val isAuto = unit.fanSpeed == FanSpeed.Auto
    // Tijdens schuiven: lokale voorvertoning; pas bij loslaten één update naar MELCloud.
    var preview by remember(unit.id) { mutableStateOf<Int?>(null) }
    val shownLevel = preview ?: level
    val shownAuto = preview == null && isAuto
    val view = LocalView.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestUnit by rememberUpdatedState(unit)
    val latestBars by rememberUpdatedState(bars)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        SectionTitle("Ventilator", Modifier.weight(1f))
        Text(
            if (shownAuto) "Automatisch" else "Stand $shownLevel van ${bars.size}",
            fontFamily = Manrope,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = Ink.IconSoft,
        )
    }
    Spacer(Modifier.height(10.dp))

    val outer = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(outer)
            .background(Ink.white(0.035f))
            .border(1.dp, Ink.white(0.07f), outer)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (FanSpeed.Auto in speeds) {
            val shape = RoundedCornerShape(15.dp)
            val autoBorder by animateColorAsState(if (isAuto) c2.copy(alpha = 0.6f) else Color.Transparent, label = "ab")
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(shape)
                    .background(
                        if (isAuto) Brush.linearGradient(listOf(c2.copy(alpha = 0.25f), c2.copy(alpha = 0.09f)))
                        else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                    )
                    .border(1.dp, autoBorder, shape)
                    .clickable(enabled = enabled, role = Role.RadioButton) { vm.setFanSpeed(unit, FanSpeed.Auto) }
                    .semantics { selected = isAuto }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Auto",
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isAuto) Ink.TextStrong else Ink.Muted2,
                )
            }
        }
        /*
         * Balkjes: tikken (per balkje, zoals voorheen) én schuiven over de hele strook.
         *  - Schuiven start pas na horizontale touch slop; verticaal blijft de pagina
         *    scrollen (elke kant meet zijn eigen as, wie eerst de drempel haalt wint).
         *  - Auto doet niet mee: links voorbij stand 1 blijft het stand 1.
         *  - 4dp hysterese op de grenzen tegen flikkeren; tikje per standwissel.
         *  - Eén update bij loslaten, alleen als de stand echt veranderde.
         *  - Uitgezonderd van het terug-veeggebaar (de strook loopt tot ±28dp van de rand).
         */
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .systemGestureExclusion()
                .pointerInput(enabled, bars.size, rtl) {
                    if (!enabled || bars.size < 2) return@pointerInput
                    val count = bars.size
                    val hyst = 4.dp.toPx()
                    fun levelAt(x: Float, cur: Int?): Int {
                        val w = size.width.toFloat()
                        val cell = w / count
                        val xx = if (rtl) w - x else x
                        val raw = (xx / cell).toInt().coerceIn(0, count - 1) + 1
                        if (cur == null || raw == cur) return raw
                        val edge = if (raw > cur) cur * cell else (cur - 1) * cell
                        return if (kotlin.math.abs(xx - edge) > hyst) raw else cur
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture // tik (afgehandeld door het balkje) of scroll
                        try {
                            preview = levelAt(start.position.x, null)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val completed = horizontalDrag(start.id) { change ->
                                val l = levelAt(change.position.x, preview)
                                if (l != preview) {
                                    preview = l
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                change.consume()
                            }
                            val chosen = preview
                            val u = latestUnit
                            val bs = latestBars
                            if (completed && chosen != null && chosen in 1..bs.size && u.fanSpeed != bs[chosen - 1]) {
                                vm.setFanSpeed(u, bs[chosen - 1])
                            }
                        } finally {
                            preview = null
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            bars.forEachIndexed { i, speed ->
                val n = i + 1
                val lit = !shownAuto && n <= shownLevel
                val active = preview == n
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 24.dp),
                            enabled = enabled,
                            role = Role.RadioButton,
                        ) { vm.setFanSpeed(unit, speed) }
                        .semantics {
                            contentDescription = "Ventilatorstand $n"
                            selected = unit.fanSpeed == speed
                        }
                        .padding(bottom = 4.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // Het balkje onder je vinger licht iets op tijdens schuiven.
                    val scale by animateFloatAsState(if (active) 1.12f else 1f, tween(120), label = "fanbar")
                    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, 1f) }) {
                        FanBar(height = (10 + n * 6).dp, lit = lit, on = on, c1 = c1, c2 = c2)
                    }
                }
            }
        }
    }
}

@Composable
private fun FanBar(height: Dp, lit: Boolean, on: Boolean, c1: Color, c2: Color) {
    val shape: Shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(width = 18.dp, height = height)
            .then(if (lit && on) Modifier.shadow(8.dp, shape, ambientColor = c2, spotColor = c2) else Modifier)
            .background(
                if (lit) Brush.verticalGradient(listOf(c2, c1)) else Brush.verticalGradient(listOf(Ink.BarOff, Ink.BarOff)),
                shape,
            ),
    )
}

// ----------------------------------------------------------------------------- lege toestand

@Composable
private fun EmptyState(state: UiState, vm: AircoActions) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp)) {
        when {
            state.loading -> {
                Text("Laden…", fontFamily = Manrope, color = Ink.Muted2)
            }
            state.loadError != null -> {
                Text(
                    "Kon je airco's niet ophalen",
                    fontFamily = Sora,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    color = Ink.Text,
                )
                Spacer(Modifier.height(8.dp))
                Text(state.loadError, fontFamily = Manrope, fontSize = 14.sp, color = Ink.Error)
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.white(0.06f))
                        .border(1.dp, Ink.white(0.1f), RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { vm.refresh(userInitiated = true) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text("Opnieuw proberen", fontFamily = Manrope, fontWeight = FontWeight.SemiBold, color = Ink.Text)
                }
            }
            else -> Text(
                "Geen airco's gevonden. Trek omlaag om te vernieuwen.",
                fontFamily = Manrope,
                color = Ink.Muted2,
            )
        }
    }
}

// ----------------------------------------------------------------------------- overzicht "Alle ruimtes"

@Composable
private fun Overview(state: UiState, vm: AircoActions, onOpen: (String) -> Unit) {
    val units = state.units
    Spacer(Modifier.height(18.dp))
    SummaryCard(state, vm, SummarySize.Phone)
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        units.forEach { u -> key(u.id) { RoomOverviewCard(u, vm, onOpen = { onOpen(u.id) }) } }
    }
}

/** Maten van de samenvatting per schermformaat (ontwerp: telefoon, foldable, tablet). */
internal enum class SummarySize(
    val label: Int, val value: Int, val unit: Int, val kwhUnit: Int, val divider: Int, val gap: Int,
    val padStart: Int, val corner: Int, val button: Int, val buttonCorner: Int, val icon: Int,
    /** Trendpijltjes achter de buitentemperatuur: breedte, hoogte, afstand tot het getal. */
    val arrowW: Int, val arrowH: Int, val arrowGap: Int,
) {
    Phone(11, 26, 15, 15, 40, 14, 18, 22, 46, 15, 15, 8, 5, 6),
    Foldable(10, 20, 12, 11, 34, 8, 16, 20, 44, 14, 14, 7, 4, 5),
    Tablet(11, 24, 14, 14, 38, 14, 18, 22, 46, 15, 15, 8, 5, 6),
}

/**
 * Samenvatting bovenaan het overzicht: Buiten | Vandaag | Actief naast elkaar,
 * daaronder "Alles uit/aan" over de volle breedte.
 */
@Composable
private fun SummaryCard(state: UiState, vm: AircoActions, size: SummarySize) {
    val units = state.units
    val outdoorTemperature = state.outdoorTemperature
    val onCount = units.count { it.power }
    val shape = RoundedCornerShape(size.corner.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.white(0.04f))
            .border(1.dp, Ink.white(0.08f), shape)
            .padding(start = size.padStart.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Bovenkant uitgelijnd: de kopjes staan altijd op één lijn, ook als een waarde
        // (met eenheid) net iets anders van hoogte is.
        Row(
            Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(size.gap.dp),
        ) {
            SummaryStat("Buiten", size, Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatValue("${outdoorLabel(outdoorTemperature)}°", null, size.value, size.unit)
                    if (outdoorTemperature != null) {
                        Spacer(Modifier.width(size.arrowGap.dp))
                        TrendArrows(state.outdoorTrend, size)
                    }
                }
            }
            StatDivider(size)
            EnergyStat(state, vm, size, Modifier.weight(1f))
            StatDivider(size)
            SummaryStat("Actief", size, Modifier.weight(1f)) {
                StatValue("$onCount", " / ${units.size}", size.value, size.unit)
            }
        }
        AllPowerButton(
            anyOn = onCount > 0,
            vm = vm,
            modifier = Modifier.fillMaxWidth().height(size.button.dp),
            corner = size.buttonCorner.dp,
            iconSize = size.icon.dp,
            arrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        )
    }
}

/**
 * "Vandaag" — tik om te wisselen naar "Gisteren" (hele vorige dag), nog eens
 * tikken voor terug. Gisteren wordt pas bij de eerste tik opgehaald.
 */
@Composable
private fun EnergyStat(state: UiState, vm: AircoActions, size: SummarySize, modifier: Modifier) {
    var yesterday by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    val kwh = if (yesterday) state.energyYesterdayKwh else state.energyTodayKwh
    val text = when {
        yesterday && kwh == null && state.energyYesterdayLoading -> "…"
        else -> energyLabel(kwh)
    }
    SummaryStat(
        if (yesterday) "Gisteren" else "Vandaag",
        size,
        modifier
            .testTag(TestTags.ENERGY_TODAY)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // geen vlak; het wisselen van kopje en getal is de feedback
                role = Role.Button,
                onClickLabel = if (yesterday) "Toon vandaag" else "Toon gisteren",
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (!yesterday) vm.loadEnergyYesterday()
                yesterday = !yesterday
            },
    ) {
        StatValue(text, if (kwh != null) " kWh" else null, size.value, size.kwhUnit)
    }
}

/**
 * ▲ boven ▼: warmer dan een half uur geleden = ▲ in warm oranje, kouder = ▼ in
 * koel blauw, gelijk of onbekend = beide gedimd.
 */
@Composable
private fun TrendArrows(trend: Int?, size: SummarySize) {
    val dim = Ink.white(0.16f)
    val up by animateColorAsState(if (trend == 1) TrendWarm else dim, tween(250), label = "trendUp")
    val down by animateColorAsState(if (trend == -1) TrendCool else dim, tween(250), label = "trendDown")
    val description = when (trend) {
        1 -> "Buiten warmer dan een half uur geleden"
        -1 -> "Buiten kouder dan een half uur geleden"
        0 -> "Buitentemperatuur gelijk gebleven"
        else -> "Trend buitentemperatuur onbekend"
    }
    Column(
        Modifier
            .testTag(TestTags.OUTDOOR_TREND)
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Triangle(pointsUp = true, color = { up }, size = size)
        Triangle(pointsUp = false, color = { down }, size = size)
    }
}

@Composable
private fun Triangle(pointsUp: Boolean, color: () -> Color, size: SummarySize) {
    androidx.compose.foundation.Canvas(Modifier.size(width = size.arrowW.dp, height = size.arrowH.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            if (pointsUp) {
                moveTo(this@Canvas.size.width / 2, 0f)
                lineTo(this@Canvas.size.width, this@Canvas.size.height)
                lineTo(0f, this@Canvas.size.height)
            } else {
                moveTo(this@Canvas.size.width / 2, this@Canvas.size.height)
                lineTo(0f, 0f)
                lineTo(this@Canvas.size.width, 0f)
            }
            close()
        }
        drawPath(path, color())
    }
}

/** Zelfde tinten als de modi Warm (c1) en Koel (c1). */
private val TrendWarm = Color(0xFFFFC15A)
private val TrendCool = Color(0xFF62E6FF)

@Composable
private fun RowScope.StatDivider(size: SummarySize) {
    Box(Modifier.align(Alignment.CenterVertically).width(1.dp).height(size.divider.dp).background(Ink.white(0.08f)))
}

/** Grote waarde met optioneel een kleine, gedempte eenheid erachter ("kWh", "/ 3"). */
@Composable
private fun StatValue(value: String, unit: String?, valueSp: Int, unitSp: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            value,
            fontFamily = Sora,
            fontWeight = FontWeight.Light,
            fontSize = valueSp.sp,
            letterSpacing = (-0.03 * valueSp).sp,
            color = Ink.Text,
            maxLines = 1,
            softWrap = false,
        )
        if (unit != null) {
            Text(
                unit,
                fontFamily = Sora,
                fontWeight = FontWeight.Light,
                fontSize = unitSp.sp,
                color = Ink.Dim,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(bottom = (valueSp * 0.15f).dp),
            )
        }
    }
}

/** "Vandaag"-waarde: kWh met één decimaal ("5,6"), of "–" zolang het onbekend is. */
internal fun energyLabel(kwh: Double?): String =
    kwh?.let { String.format(java.util.Locale.ROOT, "%.1f", it).replace('.', ',') } ?: "–"

/** "Buiten"-waarde: op 0,1° afgerond, of "–" zolang er geen meting is. */
private fun outdoorLabel(t: Double?): String = t?.let { fmtTemp(kotlin.math.round(it * 10) / 10) } ?: "–"

/** "Alles uit" / "Alles aan" voor alle bereikbare units, met de aan/uit-haptiek. */
@Composable
private fun AllPowerButton(
    anyOn: Boolean,
    vm: AircoActions,
    modifier: Modifier,
    corner: Dp,
    iconSize: Dp,
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
) {
    val view = LocalView.current
    val shape = RoundedCornerShape(corner)
    Row(
        modifier
            .shadow(8.dp, shape)
            .clip(shape)
            .background(AllPowerBrush)
            .border(1.dp, Ink.white(0.12f), shape)
            .clickable(role = Role.Button) {
                view.powerHaptic(turningOn = !anyOn)
                vm.setAllPower(!anyOn)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = arrangement,
    ) {
        Icon(Icons.Power, contentDescription = null, tint = Ink.TextStrong, modifier = Modifier.size(iconSize))
        Text(
            if (anyOn) "Alles uit" else "Alles aan",
            fontFamily = Manrope,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Ink.TextStrong,
        )
    }
}

private val AllPowerBrush = Brush.linearGradient(listOf(Color(0xFF262B36), Color(0xFF161920)))

@Composable
private fun SummaryStat(label: String, size: SummarySize, modifier: Modifier, value: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            fontFamily = Manrope,
            fontWeight = FontWeight.Bold,
            fontSize = size.label.sp,
            letterSpacing = 1.5.sp,
            color = Ink.Muted,
            maxLines = 1,
        )
        value()
    }
}

@Composable
private fun RoomOverviewCard(unit: AircoUnit, vm: AircoActions, onOpen: () -> Unit) {
    val on = unit.power
    val style = unit.mode.style()
    val range = unit.capabilities.rangeFor(unit.mode)
    val enabled = unit.isConnected
    val shape = RoundedCornerShape(26.dp)
    val border by animateColorAsState(if (on) style.c2.copy(alpha = 0.33f) else Ink.white(0.07f), tween(300), label = "cb")
    val glowAlpha by animateFloatAsState(if (on) 0.5f else 0f, tween(400), label = "cg")

    Column(
        Modifier
            .fillMaxWidth()
            .then(if (on) Modifier.shadow(20.dp, shape, ambientColor = style.c2, spotColor = Color.Black) else Modifier)
            .clip(shape)
            .background(
                if (on) Brush.linearGradient(listOf(Ink.white(0.075f), Ink.white(0.025f)))
                else Brush.linearGradient(listOf(Ink.white(0.03f), Ink.white(0.03f))),
            )
            .drawBehind {
                // Gloed rechtsboven in de kleur van de modus.
                if (glowAlpha > 0f) {
                    val c = Offset(size.width - 60.dp.toPx(), 40.dp.toPx())
                    drawCircle(
                        Brush.radialGradient(
                            0f to style.c2.copy(alpha = 0.47f * glowAlpha),
                            0.7f to Color.Transparent,
                            center = c,
                            radius = 110.dp.toPx(),
                        ),
                        radius = 110.dp.toPx(),
                        center = c,
                    )
                }
            }
            .border(1.dp, border, shape)
            .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
    ) {
        // Naam (→ bediening) + aan/uit
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "Open ${unit.name}", onClick = onOpen)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        unit.name,
                        fontFamily = Sora,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp,
                        letterSpacing = (-0.4).sp,
                        color = Ink.TextStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Chevron, contentDescription = null, tint = Ink.Dim, modifier = Modifier.size(16.dp))
                }
                Text(
                    overviewStatus(unit),
                    fontFamily = Manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = when {
                        unit.isInError || !unit.isConnected -> Ink.Error
                        on -> style.c1
                        else -> Ink.Muted
                    },
                )
            }
            PowerToggle(
                on = on,
                style = style,
                enabled = enabled,
                width = 58.dp,
                height = 34.dp,
                label = unit.name,
            ) { vm.setPower(unit, !on) }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MiniDial(
                target = unit.setTemperature,
                range = range ?: unit.capabilities.heatRange,
                on = on,
                style = style,
                showTarget = range != null,
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open ${unit.name}", onClick = onOpen),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(if (on) style.c2 else Ink.OffC2, CircleShape),
                        )
                        Text(unit.mode?.label ?: "—", fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFFDDE1EA))
                    }
                    Chip {
                        Icon(Icons.Fan, contentDescription = null, tint = Ink.Muted2, modifier = Modifier.size(13.dp))
                        Text(
                            if (unit.fanSpeed == FanSpeed.Auto) "Auto" else "Stand ${unit.fanSpeed.label}",
                            fontFamily = Manrope,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Color(0xFFDDE1EA),
                            maxLines = 1,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("KAMER", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.6.sp, color = Ink.Dim)
                        Text(
                            fmtRoom(unit.roomTemperature),
                            fontFamily = Sora,
                            fontWeight = FontWeight.Light,
                            fontSize = 20.sp,
                            color = Color(0xFFE4E8EF),
                        )
                    }
                    RoundStepButton(
                        Icons.Minus,
                        "${unit.name} temperatuur omlaag",
                        enabled && range != null && unit.setTemperature > range.start,
                        size = 44.dp,
                    ) { vm.changeTemperature(unit, -1) }
                    Spacer(Modifier.width(6.dp))
                    RoundStepButton(
                        Icons.Plus,
                        "${unit.name} temperatuur omhoog",
                        enabled && range != null && unit.setTemperature < range.endInclusive,
                        size = 44.dp,
                    ) { vm.changeTemperature(unit, +1) }
                }
            }
        }
    }
}

@Composable
private fun Chip(content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .height(26.dp)
            .clip(shape)
            .background(Ink.white(0.05f))
            .border(1.dp, Ink.white(0.08f), shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

private fun overviewStatus(unit: AircoUnit): String {
    if (!unit.isConnected) return "Offline"
    if (unit.isInError) return "Storing"
    if (!unit.power) return "Uit · standby"
    val t = fmtTemp(unit.setTemperature)
    return when (unit.mode) {
        Mode.Fan -> "Ventileert"
        Mode.Dry -> "Ontvochtigt"
        Mode.Automatic -> "Houdt $t° vast"
        Mode.Heat -> "Verwarmt naar $t°"
        Mode.Cool -> "Koelt naar $t°"
        null -> "Aan"
    }
}

// ----------------------------------------------------------------------------- brede schermen

/**
 * Foldable (Medium) en tablet (Expanded): links de lijst met ruimtes, rechts de
 * bediening van de gekozen ruimte. Ontwerp: "Foldable (open)" en "Tablet (liggend)".
 */
@Composable
private fun TwoPane(
    state: UiState,
    vm: AircoActions,
    wide: Boolean,
    listWidth: Dp,
    selected: AircoUnit?,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // --- Lijst
        Column(
            Modifier
                .width(listWidth)
                .fillMaxHeight()
                .testTag(TestTags.LIST_PANE)
                .background(if (wide) Ink.white(0.012f) else Color.Transparent)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = if (wide) 28.dp else 20.dp,
                    end = if (wide) 22.dp else 16.dp,
                    top = if (wide) 28.dp else 22.dp,
                    bottom = 24.dp,
                ),
        ) {
            Header(vm, title = "Alle ruimtes", onBack = null, titleSize = if (wide) 26.sp else 22.sp, showMore = wide)
            if (state.units.isEmpty()) {
                EmptyState(state, vm)
            } else {
                Spacer(Modifier.height(if (wide) 18.dp else 16.dp))
                SummaryCard(state, vm, if (wide) SummarySize.Tablet else SummarySize.Foldable)
                Spacer(Modifier.height(if (wide) 18.dp else 16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 8.dp)) {
                    state.units.forEach { u ->
                        key(u.id) {
                            RoomListRow(u, selected = u.id == selected?.id, wide = wide, vm = vm) { onSelect(u.id) }
                        }
                    }
                }
            }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(Ink.white(0.06f)))

        // --- Bediening
        if (selected != null) {
            if (wide) {
                TabletDetail(selected, vm, Modifier.weight(1f))
            } else {
                FoldableDetail(selected, vm, Modifier.weight(1f))
            }
        }
    }
}

/** Foldable: bediening onder elkaar, zoals op de telefoon, met "meer" naast aan/uit. */
@Composable
private fun FoldableDetail(unit: AircoUnit, vm: AircoActions, modifier: Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .testTag(TestTags.DETAIL_PANE)
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 20.dp),
    ) {
        DetailHeader(unit, vm, nameSize = 26.sp, trailing = {
            Spacer(Modifier.width(8.dp))
            MoreMenuButton(vm)
        })
        Spacer(Modifier.height(10.dp))
        DialBlock(unit, vm)
        Spacer(Modifier.height(22.dp))
        ModeSection(unit, unit.isConnected, vm)
        Spacer(Modifier.height(18.dp))
        FanSection(unit, unit.mode.style(), unit.isConnected, vm)
    }
}

/**
 * Tablet: kop bovenaan; daaronder één gecentreerde kolom (max 560dp breed) met
 * huidige temperatuur, de draaiknop (horizontaal gecentreerd), modus en ventilator.
 * Verticaal gecentreerd als het past; scrollt als het venster laag is (split-screen).
 */
@Composable
private fun TabletDetail(unit: AircoUnit, vm: AircoActions, modifier: Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .testTag(TestTags.DETAIL_PANE)
            .padding(start = 44.dp, end = 40.dp, top = 28.dp),
    ) {
        DetailHeader(unit, vm, nameSize = 34.sp, statusSize = 14.sp, toggleWidth = 76.dp, toggleHeight = 44.dp)
        // De rest vult de hoogte en centreert; past het niet (laag venster), dan scrollt het.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(top = 16.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                CurrentTempTile(unit, Modifier.widthIn(max = 560.dp).fillMaxWidth())
                DialBlock(unit, vm)
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 4.dp)) {
                    ModeSection(unit, unit.isConnected, vm)
                    Spacer(Modifier.height(18.dp))
                    FanSection(unit, unit.mode.style(), unit.isConnected, vm)
                }
            }
        }
    }
}

@Composable
private fun CurrentTempTile(unit: AircoUnit, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .clip(shape)
            .background(Ink.white(0.035f))
            .border(1.dp, Ink.white(0.07f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(TestTags.CURRENT_TEMP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "HUIDIGE TEMPERATUUR",
            fontFamily = Manrope,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            color = Ink.Dim,
            modifier = Modifier.weight(1f),
        )
        Text(
            fmtRoom(unit.roomTemperature),
            fontFamily = Sora,
            fontWeight = FontWeight.Light,
            fontSize = 24.sp,
            color = Color(0xFFE4E8EF),
        )
    }
}

/** Eén ruimte in de lijst: kiezen (links) en aan/uit (rechts) als losse aanraakdoelen. */
@Composable
private fun RoomListRow(unit: AircoUnit, selected: Boolean, wide: Boolean, vm: AircoActions, onSelect: () -> Unit) {
    val on = unit.power
    val style = unit.mode.style()
    val shape = RoundedCornerShape(if (wide) 22.dp else 18.dp)
    val border by animateColorAsState(
        when {
            selected && on -> style.c2.copy(alpha = 0.4f)
            selected -> Ink.white(0.18f)
            else -> Ink.white(0.06f)
        },
        tween(250),
        label = "rowBorder",
    )
    val dot = when {
        unit.isInError || !unit.isConnected -> Ink.Error
        on -> style.c2
        else -> Color(0xFF4A5160)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(TestTags.roomRow(unit.id))
            .then(if (selected && on && wide) Modifier.shadow(16.dp, shape, ambientColor = style.c2, spotColor = Color.Black) else Modifier)
            .clip(shape)
            .background(
                when {
                    selected && wide -> Brush.linearGradient(listOf(Ink.white(0.085f), Ink.white(0.03f)))
                    selected -> Brush.linearGradient(listOf(Ink.white(0.08f), Ink.white(0.08f)))
                    else -> Brush.linearGradient(listOf(Ink.white(0.025f), Ink.white(0.025f)))
                },
            )
            .border(1.dp, border, shape)
            .padding(
                start = if (wide) 16.dp else 12.dp,
                end = if (wide) 12.dp else 10.dp,
                top = if (wide) 12.dp else 8.dp,
                bottom = if (wide) 12.dp else 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = if (wide) 60.dp else 52.dp)
                .clickable(role = Role.Tab, onClickLabel = "Toon ${unit.name}", onClick = onSelect)
                .semantics { this.selected = selected },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp),
        ) {
            Box(
                Modifier
                    .size(if (wide) 9.dp else 8.dp)
                    .then(if (on) Modifier.shadow(6.dp, CircleShape, ambientColor = dot, spotColor = dot) else Modifier)
                    .background(dot, CircleShape),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (wide) 4.dp else 3.dp)) {
                Text(
                    unit.name,
                    fontFamily = Sora,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (wide) 17.sp else 15.sp,
                    color = Ink.TextStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    overviewStatus(unit),
                    fontFamily = Manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (wide) 12.sp else 11.sp,
                    color = when {
                        unit.isInError || !unit.isConnected -> Ink.Error
                        on -> style.c1
                        else -> Ink.Muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val temp = fmtRoom(unit.roomTemperature)
            if (wide) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(temp, fontFamily = Sora, fontWeight = FontWeight.Light, fontSize = 22.sp, color = Color(0xFFE4E8EF))
                    Text("KAMER", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.6.sp, color = Ink.Dim)
                }
            } else {
                Text(temp, fontFamily = Sora, fontWeight = FontWeight.Light, fontSize = 18.sp, color = Color(0xFFE4E8EF))
            }
        }
        PowerToggle(
            on = on,
            style = style,
            enabled = unit.isConnected,
            width = if (wide) 58.dp else 50.dp,
            height = if (wide) 34.dp else 30.dp,
            label = unit.name,
        ) { vm.setPower(unit, !on) }
    }
}
