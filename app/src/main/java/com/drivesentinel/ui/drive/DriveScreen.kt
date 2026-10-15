package com.drivesentinel.ui.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.drivesentinel.service.DriveService
import com.drivesentinel.session.DriveState
import com.drivesentinel.thermal.ThermalLevel
import com.drivesentinel.ui.common.Badge
import com.drivesentinel.ui.common.FcwFlash
import com.drivesentinel.ui.common.KeepScreenOn
import com.drivesentinel.ui.common.NeonSideBars
import com.drivesentinel.ui.common.SpeedLimitRing
import com.drivesentinel.ui.common.appViewModel
import com.drivesentinel.ui.hud.HudScreen
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.active) {
            CameraPreview(vm.camera)
            PerceptionOverlay(state.perception, settings.showDetections)
        } else {
            IdleHint(showRationale)
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

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            TopBar(state, settings)
            Spacer(Modifier.weight(1f))
            FcwBanner(state)
            CaptureChip(state.capture)
            Spacer(Modifier.height(8.dp))
            BottomBar(
                active = state.active,
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

@Composable
private fun IdleHint(showRationale: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DriveSentinel", color = Neon.Cyan, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        Text(
            "Mount the phone, then tap Start drive. Driver assistance only.",
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        if (showRationale) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Camera and location permissions are required. Enable them in system settings.",
                color = Neon.Amber,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TopBar(state: DriveState, settings: AppSettings) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // Speed card
        Row(
            Modifier.background(Neon.Panel, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (state.speed.gpsFix) state.speed.kmh.toInt().toString() else "--",
                    color = if (state.overspeed) Neon.Red else Color.White,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    if (state.speed.reliable) "km/h" else if (state.active) "km/h · GPS weak" else "km/h",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            SpeedLimitRing(settings.speedLimitKmh, 56.dp, flashing = state.overspeed)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.recording) Badge("● REC", Neon.Red)
                    val p = state.perception
                    val ai = when (p.detectorState) {
                        DetectorState.READY -> "AI ${p.accelerator ?: ""} ${"%.0f".format(p.aiFps)} fps · ${p.inferenceMs} ms"
                        else -> p.detectorState.label
                    }
                    Badge(ai, if (p.detectorState == DetectorState.READY) Neon.Green else Neon.Amber)
                    ThermalBadge(state.thermal, state.thermalHeadroom)
                }
                val lane = state.perception.laneStatus
                val laneText = when {
                    !settings.laneAssistEnabled -> "Lanes off"
                    !lane.available -> "Lanes: not found"
                    lane.suppressed -> "Lanes: < 40 km/h"
                    else -> "Lane ${lane.state.name.lowercase().replace('_', ' ')} (${"%+.2f".format(lane.offset)})"
                }
                Badge(laneText, if (lane.isDrifting) Neon.Amber else Neon.Cyan)
                state.camera.mode?.takeIf { it != com.drivesentinel.camera.CameraMode.FULL }?.let {
                    Badge(it.description, Neon.Amber, fontSize = 10.sp)
                }
                state.lastError?.let { Badge(it, Neon.Red, fontSize = 10.sp) }
            }
        }
    }
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
    Badge("THERMAL ${level.label}$hr", color)
}

@Composable
private fun FcwBanner(state: DriveState) {
    val fcw = state.perception.fcw
    if (fcw.level == FcwLevel.NONE) return
    val critical = fcw.level == FcwLevel.CRITICAL
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            if (critical) "BRAKE!" else "CAUTION – ${fcw.leadLabel ?: "vehicle"} ahead",
            color = if (critical) Neon.Red else Neon.Amber,
            fontSize = if (critical) 64.sp else 28.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun CaptureChip(capture: CaptureStatus) {
    val (text, color) = when (capture) {
        CaptureStatus.Idle -> return
        is CaptureStatus.Capturing -> "● Saving ${capture.trigger.label} clip…" to Neon.Amber
        is CaptureStatus.Saved -> "✓ Event saved" to Neon.Green
        is CaptureStatus.Failed -> "Event save failed: ${capture.reason}" to Neon.Red
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Badge(text, color, fontSize = 14.sp) }
}

@Composable
private fun BottomBar(
    active: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEvent: () -> Unit,
    onHud: () -> Unit,
    onEvents: () -> Unit,
    onSettings: () -> Unit,
    onCalibrate: () -> Unit,
    canCalibrate: Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) {
                Text("Stop drive")
            }
            Button(onClick = onEvent, colors = ButtonDefaults.buttonColors(containerColor = Neon.Red, contentColor = Color.White)) {
                Text("● EVENT", fontWeight = FontWeight.Black)
            }
        } else {
            Button(onClick = onStart) { Text("Start drive", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.weight(1f))
        if (canCalibrate) FilledTonalButton(onClick = onCalibrate) { Text("Centre lane") }
        FilledTonalButton(onClick = onHud) { Text("HUD") }
        FilledTonalButton(onClick = onEvents) { Text("Events") }
        FilledTonalButton(onClick = onSettings) { Text("Settings") }
    }
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
