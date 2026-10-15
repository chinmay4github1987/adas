package com.drivesentinel.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Line icons not in material-icons-core (the extended set adds megabytes to an unminified APK).
 * Drawn on a 24×24 grid; colour comes from the Icon tint.
 */
object AppIcons {
    val Stop: ImageVector = filled("Stop") {
        moveTo(6f, 6f); horizontalLineTo(18f); verticalLineTo(18f); horizontalLineTo(6f); close()
    }

    val Record: ImageVector = filled("Record") {
        moveTo(12f, 5f)
        arcToRelative(7f, 7f, 0f, true, true, 0f, 14f)
        arcToRelative(7f, 7f, 0f, true, true, 0f, -14f)
        close()
    }

    val Play: ImageVector = filled("Play") {
        moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
    }

    /** Speed dial — HUD mode. */
    val Hud: ImageVector = line("Hud") {
        moveTo(3.5f, 17f); arcTo(8.5f, 8.5f, 0f, false, true, 20.5f, 17f)
        moveTo(12f, 17f); lineTo(16f, 10.5f)
        moveTo(6f, 12.5f); lineTo(7.2f, 13.2f)
        moveTo(12f, 8.5f); lineTo(12f, 10f)
    }

    /** Video camera — saved events. */
    val Clips: ImageVector = line("Clips") {
        moveTo(3f, 7f); horizontalLineTo(16f); verticalLineTo(17f); horizontalLineTo(3f); close()
        moveTo(16f, 10.5f); lineTo(21f, 8f); verticalLineTo(16f); lineTo(16f, 13.5f)
    }

    /** Converging lane lines with a centre marker — lane calibration. */
    val CentreLane: ImageVector = line("CentreLane") {
        moveTo(5f, 21f); lineTo(10f, 3f)
        moveTo(19f, 21f); lineTo(14f, 3f)
        moveTo(12f, 18f); lineTo(12f, 14f)
        moveTo(12f, 11f); lineTo(12f, 8f)
    }

    val Photo: ImageVector = line("Photo") {
        moveTo(3f, 5f); horizontalLineTo(21f); verticalLineTo(19f); horizontalLineTo(3f); close()
        moveTo(6f, 16f); lineTo(10f, 11f); lineTo(13f, 14f); lineTo(15f, 12f); lineTo(18f, 16f)
    }

    /** Shield with a scan line — app mark. */
    val Shield: ImageVector = line("Shield") {
        moveTo(12f, 2.5f); lineTo(20f, 5.5f); verticalLineTo(11.5f)
        curveTo(20f, 16.5f, 16.5f, 20f, 12f, 21.5f)
        curveTo(7.5f, 20f, 4f, 16.5f, 4f, 11.5f)
        verticalLineTo(5.5f); close()
        moveTo(8f, 12f); horizontalLineTo(16f)
    }

    private fun filled(name: String, block: PathBuilder.() -> Unit) = builder(name)
        .path(fill = SolidColor(Color.Black), pathBuilder = block)
        .build()

    private fun line(name: String, block: PathBuilder.() -> Unit) = builder(name)
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
        .build()

    private fun builder(name: String) =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
}
