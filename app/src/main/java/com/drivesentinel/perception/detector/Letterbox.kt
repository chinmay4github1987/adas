package com.drivesentinel.perception.detector

import kotlin.math.min

/**
 * Aspect-preserving resize of a `srcWidth × srcHeight` frame into a `dstWidth × dstHeight`
 * model input with symmetric padding (Ultralytics "letterbox", pad value 114).
 */
data class Letterbox(
    val srcWidth: Int,
    val srcHeight: Int,
    val dstWidth: Int,
    val dstHeight: Int,
) {
    val scale: Float = min(dstWidth.toFloat() / srcWidth, dstHeight.toFloat() / srcHeight)
    val scaledWidth: Float = srcWidth * scale
    val scaledHeight: Float = srcHeight * scale
    val padX: Float = (dstWidth - scaledWidth) / 2f
    val padY: Float = (dstHeight - scaledHeight) / 2f

    /** Model-input pixel X → normalized source X (0..1). */
    fun toSourceX(xDst: Float): Float = (xDst - padX) / scale / srcWidth

    /** Model-input pixel Y → normalized source Y (0..1). */
    fun toSourceY(yDst: Float): Float = (yDst - padY) / scale / srcHeight
}
