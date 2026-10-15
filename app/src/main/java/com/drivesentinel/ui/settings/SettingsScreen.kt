package com.drivesentinel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.drivesentinel.data.settings.AppSettings
import com.drivesentinel.data.settings.BrakeSensitivity
import com.drivesentinel.data.settings.SettingsRepository
import com.drivesentinel.perception.fcw.FcwSensitivity
import com.drivesentinel.session.DriveSession
import com.drivesentinel.ui.common.appViewModel
import com.drivesentinel.ui.theme.Neon
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsViewModel(private val repo: SettingsRepository, session: DriveSession) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Settings are locked while the vehicle is moving. */
    val locked: StateFlow<Boolean> = session.state.map { it.isMoving }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun update(transform: (AppSettings) -> AppSettings) {
        if (locked.value) return
        viewModelScope.launch { repo.update(transform) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it.settingsRepository, it.driveSession) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val enabled = !locked

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        if (locked) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(Neon.Amber.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).padding(12.dp),
            ) {
                Text("Settings are locked while the vehicle is moving.", color = Neon.Amber, fontWeight = FontWeight.Bold)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Section("Speed") }
            item {
                SliderRow(
                    title = "Overspeed alert at",
                    valueLabel = "${s.speedLimitKmh} km/h",
                    value = s.speedLimitKmh.toFloat(), range = 30f..140f, steps = 21, enabled = enabled,
                    hint = "Clears 5 km/h below; voice repeats every 10 s. Cars: 120 expressway / 100 divided / 70 city (MoRTH 2018); local limits may be lower.",
                ) { v -> vm.update { it.copy(speedLimitKmh = (v / 5f).roundToInt() * 5) } }
            }
            item { SwitchRow("Voice prompts", "Robotic voice after the alert tone. Tones still play when off.", s.voiceEnabled, enabled) { v -> vm.update { it.copy(voiceEnabled = v) } } }

            item { Section("Driver assistance") }
            item { SwitchRow("Lane assist", "Lane drift / lane change detection above 40 km/h.", s.laneAssistEnabled, enabled) { v -> vm.update { it.copy(laneAssistEnabled = v) } } }
            item { SwitchRow("Forward collision warning", "Warn at TTC < 2.7 s, critical < 1.5 s, above 15 km/h.", s.fcwEnabled, enabled) { v -> vm.update { it.copy(fcwEnabled = v) } } }
            item {
                ChoiceRow("FCW sensitivity", FcwSensitivity.entries, s.fcwSensitivity, enabled) { v -> vm.update { it.copy(fcwSensitivity = v) } }
            }
            item {
                ChoiceRow("Hard-brake sensitivity", BrakeSensitivity.entries, s.brakeSensitivity, enabled, label = { "${it.name.lowercase()} (${it.thresholdG} g)" }) { v ->
                    vm.update { it.copy(brakeSensitivity = v) }
                }
            }
            item { SwitchRow("Accelerometer-only brake detection", "Skip the GPS speed-drop confirmation (more false triggers from potholes).", s.brakeAccelOnly, enabled) { v -> vm.update { it.copy(brakeAccelOnly = v) } } }
            item {
                SliderRow(
                    title = "Camera mount offset",
                    valueLabel = "%+.2f".format(s.cameraOffset),
                    value = s.cameraOffset, range = -0.3f..0.3f, steps = 11, enabled = enabled,
                    hint = "Where the phone sits relative to the car's centre line (+ = right). Or use \"Centre lane\" while driving centred.",
                ) { v -> vm.update { it.copy(cameraOffset = v) } }
            }
            item { SwitchRow("Show detection boxes", "Overlay boxes, labels, distance and TTC on the preview.", s.showDetections, enabled) { v -> vm.update { it.copy(showDetections = v) } } }

            item { Section("HUD") }
            item { SwitchRow("Mirror HUD", "Flip horizontally for windshield reflection.", s.hudMirror, enabled) { v -> vm.update { it.copy(hudMirror = v) } } }

            item { Section("Storage") }
            item {
                SliderRow(
                    title = "Event storage quota",
                    valueLabel = if (s.storageQuotaMb >= 1024) "%.1f GB".format(s.storageQuotaMb / 1024f) else "${s.storageQuotaMb} MB",
                    value = s.storageQuotaMb.toFloat(), range = 512f..8192f, steps = 14, enabled = enabled,
                    hint = "Oldest events are deleted automatically when the quota is exceeded. Footage stays on this device.",
                ) { v -> vm.update { it.copy(storageQuotaMb = ((v / 512f).roundToInt() * 512)) } }
            }
            item { Section("About") }
            item {
                Text(
                    "Driver assistance only — may fail to warn or warn falsely. Keep the phone mounted; do not interact while driving.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(title.uppercase(), color = Neon.Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        HorizontalDivider(color = Neon.Cyan.copy(alpha = 0.3f))
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(hint, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    hint: String,
    onChange: (Float) -> Unit,
) {
    // Local drag state; persist on release to avoid a DataStore write per pixel.
    var drag by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(valueLabel, color = Neon.Cyan, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = { onChange(drag) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
        Text(hint, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun <E : Enum<E>> ChoiceRow(
    title: String,
    options: List<E>,
    selected: E,
    enabled: Boolean,
    label: (E) -> String = { e -> e.name.lowercase().replaceFirstChar { it.uppercase() } },
    onSelect: (E) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, enabled = enabled)
            }
        }
    }
}
