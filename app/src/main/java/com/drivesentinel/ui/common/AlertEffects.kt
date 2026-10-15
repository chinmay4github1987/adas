package com.drivesentinel.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drivesentinel.perception.fcw.FcwLevel
import com.drivesentinel.ui.theme.Neon

/**
 * Pulsing neon edge bars (left and/or right) with a blurred bloom layer (API 31+; on older
 * devices `blur` is a no-op and the gradient alone remains). The pulse is ~1.1 Hz — well under
 * the 3 Hz photosensitivity limit — and the animated value is read only in the draw phase.
 */
@Composable
fun NeonSideBars(
    left: Boolean,
    right: Boolean,
    color: Color = Neon.Red,
    barWidth: Dp = 64.dp,
) {
    if (!left && !right) return
    val transition = rememberInfiniteTransition(label = "neon")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.fillMaxSize()) {
        if (left) GlowBar(Alignment.CenterStart, fromLeft = true, color, barWidth) { pulse }
        if (right) GlowBar(Alignment.CenterEnd, fromLeft = false, color, barWidth) { pulse }
    }
}

@Composable
private fun BoxScope.GlowBar(
    alignment: Alignment,
    fromLeft: Boolean,
    color: Color,
    barWidth: Dp,
    pulse: () -> Float,
) {
    Box(Modifier.align(alignment).fillMaxHeight().width(barWidth)) {
        // Bloom: wide gradient, blurred.
        Box(
            Modifier
                .fillMaxSize()
                .blur(28.dp, BlurredEdgeTreatment.Unbounded)
                .drawBehind {
                    val c = color.copy(alpha = pulse())
                    val colors = if (fromLeft) listOf(c, Color.Transparent) else listOf(Color.Transparent, c)
                    drawRect(Brush.horizontalGradient(colors, startX = 0f, endX = size.width))
                },
        )
        // Core: thin bright tube on the screen edge.
        Box(
            Modifier
                .align(if (fromLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .width(7.dp)
                .drawBehind {
                    val p = pulse()
                    drawRect(
                        Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.2f * p), color.copy(alpha = 0.6f + 0.4f * p), color.copy(alpha = 0.2f * p)),
                        ),
                    )
                },
        )
    }
}

/** Centre radial flash: amber for WARNING (~1.25 Hz), red for CRITICAL (~2.5 Hz, < 3 Hz). */
@Composable
fun FcwFlash(level: FcwLevel, modifier: Modifier = Modifier.fillMaxSize()) {
    if (level == FcwLevel.NONE) return
    val critical = level == FcwLevel.CRITICAL
    val color = if (critical) Neon.Red else Neon.Amber
    val transition = rememberInfiniteTransition(label = "fcw")
    val a by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (critical) 200 else 400, easing = LinearEasing), RepeatMode.Reverse),
        label = "fcwAlpha",
    )
    Box(
        modifier.drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.55f * a), color.copy(alpha = 0.15f * a), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.45f),
                    radius = size.minDimension * 0.65f,
                ),
            )
        },
    )
}

enum class ArrowDirection { LEFT, RIGHT }

/** Three chevrons sweeping in [direction] (lane-change guidance). */
@Composable
fun ChevronArrows(direction: ArrowDirection, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "chevrons")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier) {
        val n = 3
        val step = size.width / (n + 1)
        val h = size.height
        val stroke = Stroke(width = h * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (i in 0 until n) {
            val idx = if (direction == ArrowDirection.RIGHT) i else n - 1 - i
            // brightness travels in the arrow direction
            val d = ((phase * n) - i).let { if (it < 0) it + n else it } % n
            val alpha = (1f - d / n).coerceIn(0.15f, 1f)
            val cx = step * (idx + 1)
            val w = step * 0.45f
            val path = Path().apply {
                if (direction == ArrowDirection.RIGHT) {
                    moveTo(cx - w, h * 0.15f); lineTo(cx + w, h * 0.5f); lineTo(cx - w, h * 0.85f)
                } else {
                    moveTo(cx + w, h * 0.15f); lineTo(cx - w, h * 0.5f); lineTo(cx + w, h * 0.85f)
                }
            }
            drawPath(path, color.copy(alpha = alpha), style = stroke)
        }
    }
}
