package com.drivesentinel.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivesentinel.ui.theme.Cockpit
import com.drivesentinel.ui.theme.Neon

/** Chamfered "sci-fi" panel shape used throughout the cockpit UI. */
fun techShape(cut: Dp = 12.dp): Shape = CutCornerShape(topStart = cut, bottomEnd = cut)

/** Translucent glass panel with a glowing gradient edge. */
fun Modifier.glass(shape: Shape = techShape(), edge: Color = Cockpit.Edge, fill: Color = Cockpit.Glass): Modifier =
    this
        .background(fill, shape)
        .border(1.dp, Brush.linearGradient(listOf(edge, edge.copy(alpha = 0.08f), edge)), shape)

/** Deep background with a faint targeting grid and a vignette. */
fun Modifier.cockpitBackground(): Modifier = this
    .background(Cockpit.Background)
    .drawBehind {
        val step = 36.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(Cockpit.Grid, Offset(x, 0f), Offset(x, size.height)); x += step }
        var y = 0f
        while (y < size.height) { drawLine(Cockpit.Grid, Offset(0f, y), Offset(size.width, y)); y += step }
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x2200E5FF), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.35f),
                radius = size.maxDimension * 0.6f,
            ),
        )
    }

/** Small uppercase, letter-spaced label — the "instrument" voice of the UI. */
@Composable
fun TechLabel(text: String, color: Color = Cockpit.TextMuted, fontSize: TextUnit = 11.sp, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier,
    )
}

/** Status light; [pulsing] makes it breathe (recording, warnings). */
@Composable
fun GlowDot(color: Color, pulsing: Boolean = false, size: Dp = 8.dp) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "dot")
        val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotAlpha")
        a
    } else {
        1f
    }
    Box(
        Modifier
            .size(size)
            .alpha(alpha)
            .shadow(6.dp, CircleShape, ambientColor = color, spotColor = color)
            .background(color, CircleShape),
    )
}

/**
 * Large primary action (Start, Stop, Mark event): 64 dp tall, glowing, haptic on press.
 * [filled] = solid accent fill; otherwise an outlined glass variant.
 */
@Composable
fun PrimaryActionButton(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val shape = techShape(16.dp)
    val content = if (filled) Color.Black else accent
    Row(
        modifier
            .height(64.dp)
            .defaultMinSize(minWidth = 64.dp)
            .shadow(if (filled) 16.dp else 0.dp, shape, ambientColor = accent, spotColor = accent)
            .then(
                if (filled) {
                    Modifier.background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.75f))), shape)
                } else {
                    Modifier.glass(shape, edge = accent, fill = Cockpit.GlassStrong)
                },
            )
            .clickable(role = Role.Button, onClickLabel = label) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            label.uppercase(),
            color = content,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            fontSize = 16.sp,
        )
    }
}

/** Secondary cockpit control: icon over a tech label, 72×64 dp touch target. */
@Composable
fun CockpitButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Neon.Cyan,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val shape = techShape(10.dp)
    Column(
        modifier
            .defaultMinSize(minWidth = 72.dp, minHeight = 64.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .glass(shape, edge = accent.copy(alpha = 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        TechLabel(label, color = Cockpit.Text, fontSize = 10.sp)
    }
}

/** Top bar for secondary screens: chamfered back button, title and optional subtitle. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .glass(techShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Back") {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBack()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Neon.Cyan)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Cockpit.Text, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            subtitle?.let { TechLabel(it) }
        }
        trailing()
    }
}

/** Section heading with a glowing rule that fades out to the right. */
@Composable
fun SectionHeading(title: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TechLabel(title, color = Neon.Cyan, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Cockpit.Edge, Color.Transparent))),
        )
    }
}
