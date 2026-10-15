package com.drivesentinel.perception.lane

import kotlin.math.abs

/**
 * Turns lane boundaries into a lateral offset and a lane-keeping state.
 *
 * Offset `d = (xCam − laneCentre) / laneWidth` measured at the ROI bottom row. `xCam` is the
 * image centre plus a user calibration offset (phone mounts are rarely centred).
 *
 *  * `|d|` above [Config.driftThreshold] for ≥ [Config.driftSustainMs] while moving outward
 *    *slowly* → `LEFT_DRIFT` / `RIGHT_DRIFT` (typical of inattention/drowsiness).
 *  * a *fast* outward motion (|ḋ| ≥ [Config.changeRate]) past [Config.changeStart], or the
 *    offset flipping sign across a boundary (old right line becomes the new left line) →
 *    `CHANGING_LEFT` / `CHANGING_RIGHT`, held for [Config.changeHoldMs].
 *  * everything is suppressed below [Config.minSpeedKmh] (default 40 km/h) or when lane
 *    confidence is low — on unmarked roads it stays quiet instead of guessing.
 */
class LaneDepartureAnalyzer(private val config: Config = Config()) {

    data class Config(
        val minSpeedKmh: Float = 40f,
        val minConfidence: Float = 0.30f,
        val centeredThreshold: Float = 0.20f,
        val driftThreshold: Float = 0.30f,
        val driftSustainMs: Long = 500,
        val changeStart: Float = 0.28f,
        /** Lane widths per second that distinguish a deliberate lane change from a drift. */
        val changeRate: Float = 0.45f,
        val changeHoldMs: Long = 2_000,
        /** Plausible lane width at the bottom row (normalized image width). */
        val minLaneWidth: Float = 0.25f,
        val maxLaneWidth: Float = 1.6f,
        val defaultLaneWidth: Float = 0.75f,
        val rateAlpha: Float = 0.4f,
    )

    /** Camera position relative to image centre (normalised, + = mounted right of centre). */
    var cameraOffset: Float = 0f

    private var laneWidth = config.defaultLaneWidth
    private var lastOffset = Float.NaN
    private var lastTimeMs = 0L
    private var rate = 0f
    private var driftSince = -1L
    private var changeState: LaneState? = null
    private var changeUntilMs = 0L

    fun reset() {
        laneWidth = config.defaultLaneWidth
        lastOffset = Float.NaN
        rate = 0f
        driftSince = -1L
        changeState = null
    }

    fun update(estimate: LaneEstimate, speedKmh: Float, timeMs: Long, enabled: Boolean = true): LaneStatus {
        val offset = computeOffset(estimate)
        val confidence = estimate.confidence
        val suppressed = !enabled || speedKmh < config.minSpeedKmh

        if (offset.isNaN() || confidence < config.minConfidence) {
            lastOffset = Float.NaN
            driftSince = -1L
            val holding = changeState?.takeIf { timeMs < changeUntilMs && !suppressed }
            return LaneStatus(holding ?: LaneState.CENTERED, Float.NaN, confidence, suppressed, available = false)
        }

        // Smoothed lateral rate (lane widths / s). A sign flip with a big jump is a completed
        // crossing, not motion, so it does not feed the rate filter.
        var crossed: LaneState? = null
        if (!lastOffset.isNaN()) {
            val dt = ((timeMs - lastTimeMs).coerceAtLeast(1)) / 1000f
            val jump = offset - lastOffset
            if (abs(jump) > 0.6f && lastOffset * offset < 0f) {
                // Crossing the right line: d goes from ≈ +0.5 to ≈ −0.5 (and vice versa).
                crossed = if (lastOffset > 0f) LaneState.CHANGING_RIGHT else LaneState.CHANGING_LEFT
            } else if (dt < 1.5f) {
                rate += config.rateAlpha * (jump / dt - rate)
            }
        }
        lastOffset = offset
        lastTimeMs = timeMs

        if (suppressed) {
            driftSince = -1L
            changeState = null
            return LaneStatus(LaneState.CENTERED, offset, confidence, suppressed = true, available = true)
        }

        val outwardFast = (offset > config.changeStart && rate > config.changeRate) ||
            (offset < -config.changeStart && rate < -config.changeRate)
        when {
            crossed != null -> startChange(crossed, timeMs)
            outwardFast -> startChange(
                if (offset > 0) LaneState.CHANGING_RIGHT else LaneState.CHANGING_LEFT, timeMs,
            )
        }

        changeState?.let { st ->
            if (timeMs < changeUntilMs) {
                driftSince = -1L
                return LaneStatus(st, offset, confidence, suppressed = false, available = true)
            }
            changeState = null
        }

        val state = if (abs(offset) >= config.driftThreshold) {
            if (driftSince < 0) driftSince = timeMs
            if (timeMs - driftSince >= config.driftSustainMs) {
                if (offset > 0) LaneState.RIGHT_DRIFT else LaneState.LEFT_DRIFT
            } else {
                LaneState.CENTERED
            }
        } else {
            if (abs(offset) < config.centeredThreshold) driftSince = -1L
            LaneState.CENTERED
        }
        return LaneStatus(state, offset, confidence, suppressed = false, available = true)
    }

    private fun startChange(state: LaneState, timeMs: Long) {
        changeState = state
        changeUntilMs = timeMs + config.changeHoldMs
    }

    private fun computeOffset(e: LaneEstimate): Float {
        val l = e.left
        val r = e.right
        val xCam = 0.5f + cameraOffset
        val centre: Float = when {
            l != null && r != null -> {
                val w = r.xBottom - l.xBottom
                if (w !in config.minLaneWidth..config.maxLaneWidth) return Float.NaN
                laneWidth += 0.1f * (w - laneWidth)
                (l.xBottom + r.xBottom) / 2f
            }
            l != null -> l.xBottom + laneWidth / 2f
            r != null -> r.xBottom - laneWidth / 2f
            else -> return Float.NaN
        }
        return (xCam - centre) / laneWidth
    }
}
