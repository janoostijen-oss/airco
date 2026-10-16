@file:OptIn(ExperimentalMaterial3Api::class)

package nl.jan.airco.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import nl.jan.airco.AircoViewModel
import nl.jan.airco.UiState
import nl.jan.airco.data.AircoUnit
import nl.jan.airco.data.FanSpeed
import nl.jan.airco.data.Mode


@Composable
fun UnitsScreen(state: UiState, vm: AircoViewModel) {
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

    // null = overzicht "Alle ruimtes"; anders de bedieningspagina van die unit.
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = state.units.firstOrNull { it.id == detailId }
    BackHandler(enabled = detail != null) { detailId = null }

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
            // Eigen scrollpositie per scherm.
            key(detail == null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 24.dp),
                ) {
                    Header(
                        vm,
                        title = if (detail == null) "Alle ruimtes" else "Airco",
                        onBack = if (detail == null) null else ({ detailId = null }),
                    )

                    when {
                        state.units.isEmpty() -> EmptyState(state, vm)
                        detail == null -> Overview(state.units, state.outdoorTemperature, vm, onOpen = { detailId = it })
                        else -> {
                            if (state.units.size > 1) {
                                Spacer(Modifier.height(18.dp))
                                RoomSwitcher(state.units, detail.id) { detailId = it }
                            }
                            Spacer(Modifier.height(22.dp))
                            UnitControls(detail, vm)
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp)) {
            Snackbar(it, containerColor = Color(0xFF1C2029), contentColor = Ink.Text)
        }
    }
}

// ----------------------------------------------------------------------------- header

@Composable
private fun Header(vm: AircoViewModel, title: String, onBack: (() -> Unit)?) {
    var spin by remember { mutableFloatStateOf(0f) }
    val spinAnim by animateFloatAsState(spin, tween(600), label = "spin")
    var menuOpen by remember { mutableStateOf(false) }

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
                fontSize = 24.sp,
                letterSpacing = (-0.5).sp,
                color = Ink.Text,
            )
        }
        GlassIconButton(Icons.Refresh, "Vernieuwen", iconRotation = spinAnim) {
            spin += 360f
            vm.refresh(userInitiated = true)
        }
        Spacer(Modifier.width(8.dp))
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
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    label: String,
    iconRotation: Float = 0f,
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
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).rotate(iconRotation))
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

@Composable
private fun ColumnScope.UnitControls(unit: AircoUnit, vm: AircoViewModel) {
    val on = unit.power
    val style = unit.mode.style()
    val range = unit.capabilities.rangeFor(unit.mode)
    val enabled = unit.isConnected

    // Titel + aan/uit
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                unit.name,
                fontFamily = Sora,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                letterSpacing = (-0.8).sp,
                color = Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    "Kamer ${unit.roomTemperature?.let(::fmtTemp) ?: "–"}° · ",
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = Ink.Muted2,
                )
                Text(
                    statusText(unit),
                    fontFamily = Manrope,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = when {
                        unit.isInError || !unit.isConnected -> Ink.Error
                        on -> style.c1
                        else -> Ink.Dim
                    },
                )
            }
        }
        PowerToggle(on = on, style = style, enabled = enabled) { vm.setPower(unit, !on) }
    }

    // Draaiknop
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        TemperatureDial(
            target = unit.setTemperature,
            range = range ?: unit.capabilities.heatRange,
            step = unit.capabilities.step,
            on = on,
            style = style,
            adjustable = enabled && range != null,
            onChange = { vm.setTemperature(unit, it) },
        )
    }

    // −/+ in de opening onderaan de knop
    Row(
        Modifier
            .width(268.dp)
            .align(Alignment.CenterHorizontally)
            .offset(y = (-58).dp)
            // Deze rij ligt over de onderkant van de draaiknop. Zelf aanraakdoel zijn
            // (zonder iets te consumeren) voorkomt dat een tik op het label doorvalt
            // naar de knop eronder; scrollen blijft gewoon werken.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
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
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        RoundStepButton(Icons.Plus, "Temperatuur omhoog", enabled && range != null && unit.setTemperature < range.endInclusive) {
            vm.changeTemperature(unit, +1)
        }
    }

    // De −/+ rij schuift 58dp omhoog; die ruimte hieronder niet laten staan.
    Column(Modifier.offset(y = (-34).dp)) {
        ModeSection(unit, enabled, vm)
        Spacer(Modifier.height(20.dp))
        FanSection(unit, style, enabled, vm)
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
            .clickable(enabled = enabled, role = Role.Switch, onClick = onToggle)
            .semantics {
                contentDescription = "$label ${if (on) "aan, tik om uit te zetten" else "uit, tik om aan te zetten"}".trim()
            },
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
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
    Box(
        Modifier
            .size(size)
            .shadow(if (size > 48.dp) 10.dp else 6.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF222731), Color(0xFF14171D))))
            .border(1.dp, Ink.white(0.09f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
private fun ModeSection(unit: AircoUnit, enabled: Boolean, vm: AircoViewModel) {
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
private fun FanSection(unit: AircoUnit, style: ModeStyle, enabled: Boolean, vm: AircoViewModel) {
    val on = unit.power
    val c1 = if (on) style.c1 else Ink.OffC1
    val c2 = if (on) style.c2 else Ink.OffC2
    val speeds = unit.capabilities.fanSpeeds
    val bars = speeds.filter { it != FanSpeed.Auto }
    val level = bars.indexOf(unit.fanSpeed) + 1 // 0 = auto
    val isAuto = unit.fanSpeed == FanSpeed.Auto

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        SectionTitle("Ventilator", Modifier.weight(1f))
        Text(
            if (isAuto) "Automatisch" else "Stand $level van ${bars.size}",
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
        Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            bars.forEachIndexed { i, speed ->
                val n = i + 1
                val lit = !isAuto && n <= level
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
                    FanBar(height = (10 + n * 6).dp, lit = lit, on = on, c1 = c1, c2 = c2)
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
private fun EmptyState(state: UiState, vm: AircoViewModel) {
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
private fun Overview(units: List<AircoUnit>, outdoorTemperature: Double?, vm: AircoViewModel, onOpen: (String) -> Unit) {
    Spacer(Modifier.height(18.dp))
    SummaryStrip(units, outdoorTemperature, vm)
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        units.forEach { u -> key(u.id) { RoomOverviewCard(u, vm, onOpen = { onOpen(u.id) }) } }
    }
}

@Composable
private fun SummaryStrip(units: List<AircoUnit>, outdoorTemperature: Double?, vm: AircoViewModel) {
    // Gemiddelde van alle buitenunits, elk met de meest recente meting (berekend in het ViewModel).
    val outdoor = outdoorTemperature?.let { fmtTemp(kotlin.math.round(it * 10) / 10) } ?: "–"
    val onCount = units.count { it.power }
    val anyOn = onCount > 0
    val shape = RoundedCornerShape(22.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.white(0.04f))
            .border(1.dp, Ink.white(0.08f), shape)
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SummaryStat("Buiten", Modifier.weight(1f)) {
            Text("$outdoor°", fontFamily = Sora, fontWeight = FontWeight.Light, fontSize = 26.sp, letterSpacing = (-0.8).sp, color = Ink.Text)
        }
        Box(Modifier.width(1.dp).height(40.dp).background(Ink.white(0.08f)))
        SummaryStat("Actief", Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$onCount", fontFamily = Sora, fontWeight = FontWeight.Light, fontSize = 26.sp, color = Ink.Text)
                Text(
                    " / ${units.size}",
                    fontFamily = Sora,
                    fontWeight = FontWeight.Light,
                    fontSize = 15.sp,
                    color = Ink.Dim,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        val btnShape = RoundedCornerShape(15.dp)
        Row(
            Modifier
                .height(46.dp)
                .shadow(8.dp, btnShape)
                .clip(btnShape)
                .background(Brush.linearGradient(listOf(Color(0xFF262B36), Color(0xFF161920))))
                .border(1.dp, Ink.white(0.12f), btnShape)
                .clickable(role = Role.Button) { vm.setAllPower(!anyOn) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Power, contentDescription = null, tint = Ink.TextStrong, modifier = Modifier.size(15.dp))
            Text(
                if (anyOn) "Alles uit" else "Alles aan",
                fontFamily = Manrope,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Ink.TextStrong,
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, modifier: Modifier, value: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            fontFamily = Manrope,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = Ink.Muted,
            maxLines = 1,
        )
        value()
    }
}

@Composable
private fun RoomOverviewCard(unit: AircoUnit, vm: AircoViewModel, onOpen: () -> Unit) {
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
                            "${unit.roomTemperature?.let(::fmtTemp) ?: "–"}°",
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
