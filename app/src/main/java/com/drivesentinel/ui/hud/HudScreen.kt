package com.drivesentinel.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivesentinel.data.settings.AppSettings
import com.drivesentinel.perception.fcw.FcwLevel
import com.drivesentinel.perception.lane.LaneState
import com.drivesentinel.session.DriveState
import com.drivesentinel.thermal.ThermalLevel
import com.drivesentinel.ui.common.ArrowDirection
import com.drivesentinel.ui.common.ChevronArrows
import com.drivesentinel.ui.common.FcwFlash
import com.drivesentinel.ui.common.HudWindowMode
import com.drivesentinel.ui.common.NeonSideBars
import com.drivesentinel.ui.common.SpeedLimitRing
import com.drivesentinel.ui.common.TechLabel
import com.drivesentinel.ui.drive.ThermalBadge
import com.drivesentinel.ui.drive.lightColor
import com.drivesentinel.ui.theme.Neon

/**
 * Windshield HUD: pure black (OLED off → transparent in the reflection), huge high-contrast
 * glyphs, no camera preview, mirrored horizontally with `graphicsLayer(scaleX = -1f)` so it
 * reads correctly in the glass. Max brightness + immersive while shown (keep-screen-on via DriveScreen).
 * Double-tap anywhere to exit.
 */
@Composable
fun HudScreen(state: DriveState, settings: AppSettings, onExit: () -> Unit) {
    HudWindowMode(enabled = true) // keep-screen-on is applied by the caller (DriveScreen)

    val fcw = state.perception.fcw.level
    val lane = state.perception.laneStatus
    val changing = lane.isChanging && !lane.suppressed
    val holdLane = changing && fcw != FcwLevel.NONE

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onExit() }) }
            .graphicsLayer { scaleX = if (settings.hudMirror) -1f else 1f },
    ) {
        NeonSideBars(left = state.overspeed, right = state.overspeed, color = Neon.Red, barWidth = 80.dp)
        if (!state.overspeed && lane.isDrifting && !lane.suppressed) {
            NeonSideBars(
                left = lane.state == LaneState.LEFT_DRIFT,
                right = lane.state == LaneState.RIGHT_DRIFT,
                color = Neon.Amber,
                barWidth = 48.dp,
            )
        }
        FcwFlash(fcw)
        // Portrait phones are ~360-420 dp wide: scale the glyphs so nothing clips.
        val compact = maxWidth < 600.dp

        // Speed-limit ring (top-right of the *reflected* image).
        Box(Modifier.align(Alignment.TopEnd).padding(28.dp)) {
            SpeedLimitRing(if (state.active) state.speedLimitKmh else settings.speedLimitKmh, if (compact) 80.dp else 110.dp, highContrast = true, flashing = state.overspeed)
        }
        // FCW indicator (top-left).
        Box(Modifier.align(Alignment.TopStart).padding(28.dp)) {
            Column {
                FcwIndicator(fcw, compact)
                state.perception.signs.light?.let {
                    Text("● ${it.name}", color = lightColor(it), fontSize = if (compact) 28.sp else 40.sp, fontWeight = FontWeight.Black)
                }
                if (state.perception.signs.stopSign) {
                    Text("STOP", color = Neon.Red, fontSize = if (compact) 28.sp else 40.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        if (state.thermal >= ThermalLevel.HOT) {
            Box(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                ThermalBadge(state.thermal, state.thermalHeadroom)
            }
        }

        Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
            TechLabel("Double-tap to exit", color = Color.White.copy(alpha = 0.3f))
        }

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (state.speed.gpsFix) state.speed.kmh.toInt().toString() else "--",
                color = if (state.overspeed) Neon.Red else Color.White,
                fontSize = if (compact) 130.sp else 190.sp,
                lineHeight = if (compact) 130.sp else 190.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
            Text("km/h", color = Color.White.copy(alpha = 0.8f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LaneGuidance(lane.state, changing, holdLane, lane.suppressed, compact)
        }
    }
}

@Composable
private fun FcwIndicator(level: FcwLevel, compact: Boolean) {
    val (text, color) = when (level) {
        FcwLevel.NONE -> "FCW" to Color.White.copy(alpha = 0.25f)
        FcwLevel.WARNING -> "CAUTION" to Neon.Amber
        FcwLevel.CRITICAL -> "BRAKE!" to Neon.Red
    }
    Text(text, color = color, fontSize = when {
        level == FcwLevel.CRITICAL -> if (compact) 44.sp else 64.sp
        else -> if (compact) 28.sp else 40.sp
    }, fontWeight = FontWeight.Black)
}

@Composable
private fun LaneGuidance(state: LaneState, changing: Boolean, holdLane: Boolean, suppressed: Boolean, compact: Boolean) {
    val big = if (compact) 28.sp else 40.sp
    val arrow = if (compact) Modifier.size(width = 72.dp, height = 40.dp) else Modifier.size(width = 180.dp, height = 70.dp)
    when {
        holdLane -> {
            Text("HOLD LANE", color = Neon.Red, fontSize = if (compact) 40.sp else 56.sp, fontWeight = FontWeight.Black)
        }
        changing -> {
            val dir = if (state == LaneState.CHANGING_LEFT) ArrowDirection.LEFT else ArrowDirection.RIGHT
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (dir == ArrowDirection.LEFT) ChevronArrows(dir, Neon.Cyan, arrow)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${if (compact) "" else "Lane change "}${if (dir == ArrowDirection.LEFT) "LEFT" else "RIGHT"}",
                    color = Neon.Cyan,
                    fontSize = big,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(12.dp))
                if (dir == ArrowDirection.RIGHT) ChevronArrows(dir, Neon.Cyan, arrow)
            }
        }
        !suppressed && (state == LaneState.LEFT_DRIFT || state == LaneState.RIGHT_DRIFT) -> {
            Text(
                if (state == LaneState.LEFT_DRIFT) "◀ DRIFTING LEFT" else "DRIFTING RIGHT ▶",
                color = Neon.Amber,
                fontSize = big,
                fontWeight = FontWeight.Black,
            )
        }
        else -> Spacer(Modifier.height(56.dp))
    }
}
