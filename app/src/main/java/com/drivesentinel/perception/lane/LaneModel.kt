package com.drivesentinel.perception.lane

/** Packed ARGB_8888 pixels (as from `Bitmap.getPixels`) — pure data, no Android types. */
class ArgbFrame(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size >= width * height) { "pixel buffer too small" }
    }
}

/**
 * A straight lane boundary in normalized image coordinates, defined by its x position at
 * the bottom ([yBottom]) and top ([yTop]) rows of the lane ROI.
 */
data class LaneLine(
    val xBottom: Float,
    val xTop: Float,
    val yBottom: Float,
    val yTop: Float,
    val confidence: Float,
) {
    fun xAt(y: Float): Float {
        val t = (yBottom - y) / (yBottom - yTop)
        return xBottom + t * (xTop - xBottom)
    }
}

data class LaneEstimate(
    val left: LaneLine?,
    val right: LaneLine?,
) {
    val confidence: Float
        get() = when {
            left != null && right != null -> (left.confidence + right.confidence) / 2f
            left != null -> left.confidence * 0.6f
            right != null -> right.confidence * 0.6f
            else -> 0f
        }

    companion object {
        val NONE = LaneEstimate(null, null)
    }
}

/** Pluggable lane detector. Implementations are called from a single analysis thread. */
interface LaneDetector : AutoCloseable {
    val name: String

    /** True when the detector can actually run (e.g. its model asset exists). */
    val isAvailable: Boolean get() = true

    fun detect(frame: ArgbFrame, timestampMs: Long): LaneEstimate

    override fun close() {}
}

enum class LaneState { CENTERED, LEFT_DRIFT, RIGHT_DRIFT, CHANGING_LEFT, CHANGING_RIGHT }

data class LaneStatus(
    val state: LaneState = LaneState.CENTERED,
    /** Lateral offset in lane widths; + = vehicle right of lane centre. NaN if unknown. */
    val offset: Float = Float.NaN,
    val confidence: Float = 0f,
    /** True when warnings are suppressed (below the minimum speed or lane assist off). */
    val suppressed: Boolean = true,
    /** True when the lane estimate was good enough to compute an offset. */
    val available: Boolean = false,
) {
    val isChanging: Boolean get() = state == LaneState.CHANGING_LEFT || state == LaneState.CHANGING_RIGHT
    val isDrifting: Boolean get() = state == LaneState.LEFT_DRIFT || state == LaneState.RIGHT_DRIFT
}
