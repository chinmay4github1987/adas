package com.drivesentinel.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivesentinel.ui.theme.Neon

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

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    Box(
        modifier
            .background(Neon.Panel, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}
