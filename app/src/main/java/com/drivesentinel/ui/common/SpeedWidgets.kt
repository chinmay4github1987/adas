package com.drivesentinel.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivesentinel.ui.theme.Cockpit
import com.drivesentinel.ui.theme.Neon
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Road-sign style speed-limit ring (red ring, white/black centre). */
@Composable
fun SpeedLimitRing(limitKmh: Int, size: Dp, highContrast: Boolean = false, flashing: Boolean = false) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.12f
            if (!highContrast) drawCircle(Color.White, radius = this.size.minDimension / 2f - stroke / 2f)
            drawCircle(
                if (flashing) Neon.Red else Color(0xFFE53935),
                radius = this.size.minDimension / 2f - stroke / 2f,
                style = Stroke(width = stroke),
            )
        }
        Text(
            text = limitKmh.toString(),
            color = if (highContrast) Color.White else Color.Black,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.36f).sp,
        )
    }
}

private const val GAUGE_START = 135f
private const val GAUGE_SWEEP = 270f
private const val GAUGE_SEGMENTS = 36

/**
 * Segmented instrument-cluster speedometer. Segments light up to the current speed and shift
 * cyan → amber (within 10 km/h of the limit) → red (overspeed). A red tick marks the limit.
 */
@Composable
fun SpeedGauge(
    kmh: Float,
    limitKmh: Int,
    overspeed: Boolean,
    gpsFix: Boolean,
    reliable: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val scaleMax = max(160, (limitKmh + 40) / 20 * 20).toFloat()
    val shown by animateFloatAsState(if (gpsFix) kmh.coerceIn(0f, scaleMax) else 0f, tween(400), label = "speed")
    val accent = when {
        overspeed -> Neon.Red
        gpsFix && kmh >= limitKmh - 10 -> Neon.Amber
        else -> Neon.Cyan
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = center
            val segStroke = r * 0.13f
            val segR = r - segStroke / 2f - r * 0.06f
            val segSweep = GAUGE_SWEEP / GAUGE_SEGMENTS
            val lit = (shown / scaleMax * GAUGE_SEGMENTS)

            // Outer hairline ring.
            drawArc(Cockpit.Edge, GAUGE_START, GAUGE_SWEEP, false, style = Stroke(1.dp.toPx()),
                topLeft = Offset(c.x - r + 1f, c.y - r + 1f), size = androidx.compose.ui.geometry.Size(2 * r - 2f, 2 * r - 2f))

            for (i in 0 until GAUGE_SEGMENTS) {
                val on = i < lit
                val color = if (on) accent else Cockpit.Track
                drawArc(
                    color = color,
                    startAngle = GAUGE_START + i * segSweep + 1.2f,
                    sweepAngle = segSweep - 2.4f,
                    useCenter = false,
                    topLeft = Offset(c.x - segR, c.y - segR),
                    size = androidx.compose.ui.geometry.Size(2 * segR, 2 * segR),
                    style = Stroke(segStroke, cap = StrokeCap.Butt),
                    alpha = if (on) 0.6f + 0.4f * (i + 1) / GAUGE_SEGMENTS else 1f,
                )
            }

            // Speed-limit marker.
            val limitAngle = Math.toRadians((GAUGE_START + GAUGE_SWEEP * (limitKmh / scaleMax)).toDouble())
            val inner = segR - segStroke * 0.9f
            val outer = r
            drawLine(
                Neon.Red,
                Offset(c.x + inner * cos(limitAngle).toFloat(), c.y + inner * sin(limitAngle).toFloat()),
                Offset(c.x + outer * cos(limitAngle).toFloat(), c.y + outer * sin(limitAngle).toFloat()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Inner dashed ring.
            drawCircle(Cockpit.EdgeDim, radius = segR - segStroke, style = Stroke(1.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (gpsFix) kmh.toInt().toString() else "--",
                color = if (overspeed) Neon.Red else Cockpit.Text,
                fontSize = (size.value * 0.30f).sp,
                lineHeight = (size.value * 0.30f).sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
            TechLabel("km/h", fontSize = (size.value * 0.07f).sp)
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                GlowDot(if (!gpsFix) Neon.Red else if (reliable) Neon.Green else Neon.Amber, pulsing = !gpsFix, size = 6.dp)
                Spacer(Modifier.width(4.dp))
                TechLabel(if (!gpsFix) "No GPS" else if (reliable) "GPS lock" else "GPS weak", fontSize = 9.sp)
            }
        }
    }
}

/** Status chip: glowing light + monospace label on dark glass. */
@Composable
fun Badge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    pulsing: Boolean = false,
) {
    Row(
        modifier
            .glass(techShape(6.dp), edge = color.copy(alpha = 0.45f), fill = Neon.Panel)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(color, pulsing = pulsing, size = 6.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text.uppercase(),
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
    }
}
