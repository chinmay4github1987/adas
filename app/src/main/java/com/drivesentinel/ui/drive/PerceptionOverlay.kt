package com.drivesentinel.ui.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.drivesentinel.perception.PerceptionFrame
import com.drivesentinel.perception.detector.RoadClasses
import com.drivesentinel.perception.fcw.FcwLevel
import com.drivesentinel.ui.theme.Neon
import kotlin.math.max

/**
 * Draws detections, lane lines and the ego-lane corridor over the preview. Coordinates are
 * normalized to the analysis frame; the mapping replicates PreviewView FILL_CENTER (both use
 * cases are 16:9, so the crop matches).
 */
@Composable
fun PerceptionOverlay(frame: PerceptionFrame, showDetections: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Canvas(modifier) {
        val fw = frame.frameWidth.toFloat()
        val fh = frame.frameHeight.toFloat()
        val scale = max(size.width / fw, size.height / fh)
        val ox = (size.width - fw * scale) / 2f
        val oy = (size.height - fh * scale) / 2f
        fun mx(x: Float) = ox + x * fw * scale
        fun my(y: Float) = oy + y * fh * scale

        // Lanes + ego corridor.
        val l = frame.lanes.left
        val r = frame.lanes.right
        if (l != null && r != null) {
            val corridor = Path().apply {
                moveTo(mx(l.xBottom), my(l.yBottom)); lineTo(mx(l.xTop), my(l.yTop))
                lineTo(mx(r.xTop), my(r.yTop)); lineTo(mx(r.xBottom), my(r.yBottom)); close()
            }
            val tint = when {
                frame.laneStatus.isChanging -> Neon.Cyan
                frame.laneStatus.isDrifting -> Neon.Amber
                else -> Neon.Green
            }
            drawPath(corridor, tint.copy(alpha = 0.12f))
        }
        listOfNotNull(l, r).forEach { line ->
            drawLine(
                color = Neon.Green.copy(alpha = 0.4f + 0.6f * line.confidence),
                start = Offset(mx(line.xBottom), my(line.yBottom)),
                end = Offset(mx(line.xTop), my(line.yTop)),
                strokeWidth = 6f,
            )
        }

        if (!showDetections) return@Canvas
        val lead = frame.fcw.leadBox
        for (d in frame.detections) {
            val isLead = lead != null && d.box == lead
            val color = when {
                isLead && frame.fcw.level == FcwLevel.CRITICAL -> Neon.Red
                isLead && frame.fcw.level == FcwLevel.WARNING -> Neon.Amber
                isLead -> Neon.Magenta
                d.classId == RoadClasses.PERSON || d.classId == RoadClasses.COW || d.classId == RoadClasses.DOG -> Neon.Amber
                else -> Neon.Cyan
            }
            val tl = Offset(mx(d.box.left), my(d.box.top))
            val sz = Size(d.box.width * fw * scale, d.box.height * fh * scale)
            drawRect(color, topLeft = tl, size = sz, style = Stroke(width = if (isLead) 6f else 3f))
            val text = buildString {
                append(d.label).append(' ').append((d.score * 100).toInt()).append('%')
                if (isLead) {
                    frame.fcw.distanceMeters?.takeIf { it.isFinite() }?.let { append(" · ").append(it.toInt()).append(" m") }
                    frame.fcw.ttcSeconds?.takeIf { it.isFinite() && it < 10f }?.let { append(" · TTC ").append("%.1f".format(it)).append(" s") }
                }
            }
            val layout = measurer.measure(text, labelStyle)
            val labelTop = (tl.y - layout.size.height).coerceAtLeast(0f)
            drawRect(color, topLeft = Offset(tl.x, labelTop), size = Size(layout.size.width + 8f, layout.size.height.toFloat()))
            drawText(layout, topLeft = Offset(tl.x + 4f, labelTop))
        }
    }
}
