package com.drivesentinel.ui.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesentinel.camera.CameraPipeline
import com.drivesentinel.data.settings.AppSettings
import com.drivesentinel.events.CaptureStatus
import com.drivesentinel.perception.DetectorState
import com.drivesentinel.perception.fcw.FcwLevel
import com.drivesentinel.perception.lane.LaneState
import com.drivesentinel.perception.signs.LightColor
import com.drivesentinel.service.DriveService
import com.drivesentinel.session.DriveState
import com.drivesentinel.thermal.ThermalLevel
import com.drivesentinel.ui.common.AppIcons
import com.drivesentinel.ui.common.Badge
import com.drivesentinel.ui.common.CockpitButton
import com.drivesentinel.ui.common.PrimaryActionButton
import com.drivesentinel.ui.common.SpeedGauge
import com.drivesentinel.ui.common.TechLabel
import com.drivesentinel.ui.common.cockpitBackground
import com.drivesentinel.ui.common.glass
import com.drivesentinel.ui.common.techShape
import com.drivesentinel.ui.common.FcwFlash
import com.drivesentinel.ui.common.KeepScreenOn
import com.drivesentinel.ui.common.NeonSideBars
import com.drivesentinel.ui.common.SpeedLimitRing
import com.drivesentinel.ui.common.appViewModel
import com.drivesentinel.ui.hud.HudScreen
import com.drivesentinel.ui.theme.Cockpit
import com.drivesentinel.ui.theme.Neon

@Composable
fun DriveScreen(onOpenSettings: () -> Unit, onOpenEvents: () -> Unit) {
    val vm = appViewModel { DriveViewModel(it.driveSession, it.settingsRepository, it.cameraPipeline) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var hud by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    KeepScreenOn(state.active || hud)

    var showRationale by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasEssentialPermissions(context)) DriveService.start(context) else showRationale = true
    }
    val startDrive = {
        if (hasEssentialPermissions(context)) {
            DriveService.start(context) // activity is visible → allowed to start a camera FGS
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    if (hud) {
        HudScreen(state = state, settings = settings, onExit = { hud = false })
        return
    }

    Box(Modifier.fillMaxSize().cockpitBackground()) {
        if (state.active) {
            CameraPreview(vm.camera)
            CockpitVignette()
            PerceptionOverlay(state.perception, settings.showDetections)
        }

        val lane = state.perception.laneStatus
        NeonSideBars(left = state.overspeed, right = state.overspeed, color = Neon.Red)
        if (!state.overspeed && lane.isDrifting && !lane.suppressed) {
            NeonSideBars(
                left = lane.state == LaneState.LEFT_DRIFT,
                right = lane.state == LaneState.RIGHT_DRIFT,
                color = Neon.Amber,
                barWidth = 40.dp,
            )
        }
        FcwFlash(state.perception.fcw.level)

        // Every panel lives in one flow (cluster → content → alerts → dock), so nothing can overlap.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            val landscape = maxWidth > maxHeight
            val compact = maxWidth < 600.dp
            val gaugeSize = if (landscape) {
                (maxHeight - 140.dp).coerceIn(100.dp, 170.dp) // leave room for the dock below
            } else {
                (maxWidth * 0.45f).coerceIn(130.dp, 190.dp)
            }

            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (landscape) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        InstrumentCluster(state, settings, gaugeSize)
                        if (state.active) {
                            StatusChips(state, settings, Modifier.weight(1f), alignEnd = true)
                        } else {
                            IdlePanel(showRationale, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                } else {
                    InstrumentCluster(state, settings, gaugeSize, Modifier.fillMaxWidth())
                    if (state.active) {
                        StatusChips(state, settings, Modifier.fillMaxWidth(), alignEnd = false)
                        Spacer(Modifier.weight(1f))
                    } else {
                        IdlePanel(showRationale, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                FcwBanner(state, compact)
                CaptureChip(state.capture)
                ControlDock(
                    active = state.active,
                    compact = compact,
                    onStart = startDrive,
                    onStop = { DriveService.stop(context) },
                    onEvent = {
                        if (!vm.manualEvent()) Toast.makeText(context, "Already saving an event", Toast.LENGTH_SHORT).show()
                    },
                    onHud = { hud = true },
                    onEvents = onOpenEvents,
                    onSettings = onOpenSettings,
                    onCalibrate = vm::calibrateCenter,
                    canCalibrate = state.active && lane.available,
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(camera: CameraPipeline) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    DisposableEffect(previewView) {
        camera.setSurfaceProvider(previewView.surfaceProvider)
        onDispose { camera.setSurfaceProvider(null) }
    }
}

/** Darkens the top and bottom of the camera feed so the cockpit panels stay legible. */
@Composable
private fun CockpitVignette() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.55f),
                0.25f to Color.Transparent,
                0.7f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.7f),
            ),
        ),
    )
}

private val PREFLIGHT = listOf(
    "Mount the phone with the rear camera facing the road",
    "Keep the lens clear of wipers and the dashboard edge",
    "Wait for GPS lock before relying on speed alerts",
)

/** Standby briefing; scrolls inside whatever space the layout gives it. */
@Composable
private fun IdlePanel(showRationale: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .glass(techShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Shield, contentDescription = null, tint = Neon.Cyan, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "DRIVESENTINEL",
                        color = Cockpit.Text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                    )
                    TechLabel("Driver assistance · standby", color = Neon.Cyan, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            TechLabel("Pre-drive check")
            Spacer(Modifier.height(6.dp))
            PREFLIGHT.forEachIndexed { i, item ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "%02d".format(i + 1),
                        color = Neon.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(item, color = Cockpit.Text.copy(alpha = 0.9f), fontSize = 15.sp)
                }
            }
            if (showRationale) {
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .glass(techShape(8.dp), edge = Neon.Amber, fill = Neon.Amber.copy(alpha = 0.08f))
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Neon.Amber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Camera and location access needed", color = Neon.Amber, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    PrimaryActionButton(
                        icon = Icons.Filled.Settings,
                        label = "Open app settings",
                        accent = Neon.Amber,
                        filled = false,
                        onClick = { openAppSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Speedometer + speed-limit sign. */
@Composable
private fun InstrumentCluster(state: DriveState, settings: AppSettings, gaugeSize: Dp, modifier: Modifier = Modifier) {
    // While driving, a limit read from a sign board overrides the Settings value.
    val limit = if (state.active) state.speedLimitKmh else settings.speedLimitKmh
    Row(
        modifier.glass(techShape(18.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedGauge(
            kmh = state.speed.kmh,
            limitKmh = limit,
            overspeed = state.overspeed,
            gpsFix = state.speed.gpsFix,
            reliable = state.speed.reliable,
            size = gaugeSize,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SpeedLimitRing(limit, gaugeSize * 0.4f, flashing = state.overspeed)
            Spacer(Modifier.height(6.dp))
            if (state.limitFromSign) TechLabel("From sign", color = Neon.Cyan, fontSize = 9.sp) else TechLabel("Limit", fontSize = 9.sp)
        }
    }
}

/** System status chips; wrap onto extra lines instead of overflowing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(state: DriveState, settings: AppSettings, modifier: Modifier, alignEnd: Boolean) {
    val p = state.perception
    val lane = p.laneStatus
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (alignEnd) Alignment.End else Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.recording) Badge("REC", Neon.Red, pulsing = true)
        p.signs.light?.let { Badge("Signal ${it.name}", lightColor(it), pulsing = it != LightColor.GREEN) }
        if (p.signs.stopSign) Badge("STOP sign", Neon.Red, pulsing = true)
        val ai = when (p.detectorState) {
            DetectorState.READY -> "AI ${p.accelerator ?: ""} ${"%.0f".format(p.aiFps)} fps · ${p.inferenceMs} ms"
            else -> p.detectorState.label
        }
        Badge(ai, if (p.detectorState == DetectorState.READY) Neon.Green else Neon.Amber)
        ThermalBadge(state.thermal, state.thermalHeadroom)
        val laneText = when {
            !settings.laneAssistEnabled -> "Lanes off"
            !lane.available -> "Lanes: searching"
            lane.suppressed -> "Lanes: < 40 km/h"
            else -> "Lane ${lane.state.name.lowercase().replace('_', ' ')} ${"%+.2f".format(lane.offset)}"
        }
        Badge(laneText, if (lane.isDrifting) Neon.Amber else Neon.Cyan, pulsing = lane.isDrifting)
        state.camera.mode?.takeIf { it != com.drivesentinel.camera.CameraMode.FULL }?.let {
            Badge(it.description, Neon.Amber, fontSize = 10.sp)
        }
        state.lastError?.let { Badge(it, Neon.Red, fontSize = 10.sp) }
    }
}

fun lightColor(c: LightColor): Color = when (c) {
    LightColor.RED -> Neon.Red
    LightColor.AMBER -> Neon.Amber
    LightColor.GREEN -> Neon.Green
    LightColor.UNKNOWN -> Cockpit.TextMuted
}

@Composable
fun ThermalBadge(level: ThermalLevel, headroom: Float) {
    val color = when (level) {
        ThermalLevel.NORMAL -> Neon.Green
        ThermalLevel.WARM -> Neon.Amber
        ThermalLevel.HOT -> Color(0xFFFF6D00)
        ThermalLevel.SEVERE, ThermalLevel.CRITICAL -> Neon.Red
    }
    val hr = if (headroom.isNaN()) "" else " ${"%.2f".format(headroom)}"
    Badge("Thermal ${level.label}$hr", color, pulsing = level >= ThermalLevel.HOT)
}

@Composable
private fun FcwBanner(state: DriveState, compact: Boolean) {
    val fcw = state.perception.fcw
    if (fcw.level == FcwLevel.NONE) return
    val critical = fcw.level == FcwLevel.CRITICAL
    val color = if (critical) Neon.Red else Neon.Amber
    val iconSize = when { critical && compact -> 40.dp; critical -> 56.dp; compact -> 24.dp; else -> 32.dp }
    val textSize = when { critical && compact -> 44.sp; critical -> 64.sp; compact -> 18.sp; else -> 26.sp }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.glass(techShape(14.dp), edge = color, fill = color.copy(alpha = 0.15f))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(12.dp))
            Text(
                if (critical) "BRAKE!" else "CAUTION · ${(fcw.leadLabel ?: "vehicle").uppercase()} AHEAD",
                color = color,
                fontSize = textSize,
                lineHeight = textSize,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun CaptureChip(capture: CaptureStatus) {
    val (text, color) = when (capture) {
        CaptureStatus.Idle -> return
        is CaptureStatus.Capturing -> "Saving ${capture.trigger.label} clip…" to Neon.Amber
        is CaptureStatus.Saved -> "Event saved" to Neon.Green
        is CaptureStatus.Failed -> "Event save failed: ${capture.reason}" to Neon.Red
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Badge(text, color, fontSize = 13.sp, pulsing = capture is CaptureStatus.Capturing)
    }
}

/**
 * Wide screens: one row (primary actions left, tools right).
 * Compact (portrait phones): primary actions on top, tools below; every button gets an equal share.
 */
@Composable
private fun ControlDock(
    active: Boolean,
    compact: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEvent: () -> Unit,
    onHud: () -> Unit,
    onEvents: () -> Unit,
    onSettings: () -> Unit,
    onCalibrate: () -> Unit,
    canCalibrate: Boolean,
) {
    val primary: @Composable RowScope.(Modifier) -> Unit = { m ->
        if (active) {
            PrimaryActionButton(AppIcons.Stop, "Stop", Neon.Red, onStop, filled = false, modifier = m)
            PrimaryActionButton(AppIcons.Record, "Mark event", Neon.Red, onEvent, modifier = m)
        } else {
            PrimaryActionButton(AppIcons.Play, "Start drive", Neon.Green, onStart, modifier = m)
        }
    }
    val tools: @Composable RowScope.(Modifier) -> Unit = { m ->
        if (canCalibrate) CockpitButton(AppIcons.CentreLane, "Centre", onCalibrate, accent = Neon.Amber, modifier = m)
        CockpitButton(AppIcons.Hud, "HUD", onHud, modifier = m)
        CockpitButton(AppIcons.Clips, "Events", onEvents, modifier = m)
        CockpitButton(Icons.Filled.Settings, "Settings", onSettings, modifier = m)
    }
    val dock = Modifier.fillMaxWidth().glass(techShape(20.dp), fill = Cockpit.GlassStrong).padding(10.dp)
    if (compact) {
        Column(dock, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { primary(Modifier.weight(1f)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { tools(Modifier.weight(1f)) }
        }
    } else {
        Row(dock, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            primary(Modifier)
            Spacer(Modifier.weight(1f))
            tools(Modifier)
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

private fun hasEssentialPermissions(context: Context): Boolean {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return granted(Manifest.permission.CAMERA) &&
        (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
}
