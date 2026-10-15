@file:OptIn(ExperimentalMaterial3Api::class)

package nl.jan.airco.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.jan.airco.AircoViewModel
import nl.jan.airco.UiState
import nl.jan.airco.data.AircoUnit
import java.util.Locale

@Composable
fun UnitsScreen(state: UiState, vm: AircoViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Airco") },
                actions = {
                    IconButton(onClick = { vm.refresh(userInitiated = true) }) { Icon(Icons.Default.Refresh, "Vernieuwen") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Uitloggen") },
                                onClick = { menuOpen = false; vm.logout() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.pullRefreshing,
            onRefresh = { vm.refresh(userInitiated = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // Gewone scrollende Column i.p.v. LazyColumn: bij een handvol kaarten
            // worden ze zo één keer opgebouwd en niet bij elke scroll opnieuw.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.units.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp)) {
                        when {
                            state.loading -> Text("Laden…")
                            state.loadError != null -> {
                                Text("Kon je airco's niet ophalen", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(state.loadError, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { vm.refresh(userInitiated = true) }) { Text("Opnieuw proberen") }
                            }
                            else -> Text("Geen airco's gevonden. Trek omlaag om te vernieuwen.")
                        }
                    }
                } else {
                    val showBuilding = remember(state.units) { state.units.map { it.building }.distinct().size > 1 }
                    state.units.forEach { unit ->
                        key(unit.id) { UnitCard(unit, showBuilding, vm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnitCard(unit: AircoUnit, showBuilding: Boolean, vm: AircoViewModel) {
    val enabled = unit.isConnected
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (unit.power) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Kop: naam + aan/uit
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(unit.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    // Altijd één regel, zodat de kaart niet van hoogte verandert als de data binnenkomt.
                    val sub = buildList {
                        if (showBuilding) add(unit.building)
                        add("Kamer ${unit.roomTemperature?.let(::fmt) ?: "–"}°")
                        if (!unit.isConnected) add("offline")
                    }.joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                if (unit.isInError) {
                    Icon(Icons.Default.Warning, "Storing", tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp))
                }
                Switch(checked = unit.power, enabled = enabled, onCheckedChange = { vm.setPower(unit, it) })
            }

            Spacer(Modifier.height(16.dp))

            // Temperatuur
            val range = unit.capabilities.rangeFor(unit.mode)
            Row(
                Modifier.fillMaxWidth().alpha(if (unit.power) 1f else 0.6f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                FilledTonalIconButton(
                    onClick = { vm.changeTemperature(unit, -1) },
                    enabled = enabled && range != null && unit.setTemperature > range.start,
                    modifier = Modifier.size(56.dp),
                ) { Text("−", fontSize = 28.sp) }
                Text(
                    if (range == null) "—" else "${fmt(unit.setTemperature)}°",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
                FilledTonalIconButton(
                    onClick = { vm.changeTemperature(unit, +1) },
                    enabled = enabled && range != null && unit.setTemperature < range.endInclusive,
                    modifier = Modifier.size(56.dp),
                ) { Text("+", fontSize = 28.sp) }
            }

            Spacer(Modifier.height(16.dp))

            // Modus
            Text("Modus", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            val modes = unit.capabilities.modes
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = unit.power && unit.mode == mode,
                        onClick = { vm.setMode(unit, mode) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        icon = {},
                    ) { Text(mode.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Ventilator
            Text("Ventilator", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            val speeds = unit.capabilities.fanSpeeds
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                speeds.forEachIndexed { i, speed ->
                    SegmentedButton(
                        selected = unit.fanSpeed == speed,
                        onClick = { vm.setFanSpeed(unit, speed) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(i, speeds.size),
                        icon = {},
                    ) { Text(speed.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

private fun fmt(t: Double): String =
    if (t % 1.0 == 0.0) t.toInt().toString() else String.format(Locale("nl", "NL"), "%.1f", t)
