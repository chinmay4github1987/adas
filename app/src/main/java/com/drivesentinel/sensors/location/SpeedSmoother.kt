package com.drivesentinel.sensors.location

/** One GNSS speed reading (Doppler-derived speed from `Location.getSpeed()`). */
data class SpeedSample(
    val elapsedMs: Long,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val horizontalAccuracyM: Float?,
)

data class SpeedState(
    val kmh: Float = 0f,
    val rawKmh: Float = 0f,
    /** False when there is no recent fix or speed accuracy is poor (alerts must not trigger). */
    val reliable: Boolean = false,
    val accuracyKmh: Float? = null,
    val gpsFix: Boolean = false,
    val lastFixMs: Long = 0L,
)

/**
 * Accuracy-aware EMA smoother for 1 Hz GNSS speed.
 *
 * * samples without speed, or with speed accuracy worse than [maxSpeedAccuracyMps], are not
 *   trusted; the smoothed value is held and marked unreliable;
 * * the EMA weight shrinks for less accurate samples;
 * * below [standstillMps] the speed is clamped to 0 (GNSS jitters 0.3–1 m/s at rest);
 * * [staleAfterMs] without a fix → unreliable ("GPS lost", e.g. tunnels, flyovers).
 */
class SpeedSmoother(
    private val alpha: Float = 0.45f,
    private val maxSpeedAccuracyMps: Float = 2.0f,
    private val standstillMps: Float = 0.8f,
    private val staleAfterMs: Long = 2_500,
) {
    private var smoothed = Float.NaN
    private var lastGoodMs = Long.MIN_VALUE / 2
    var state = SpeedState()
        private set

    fun reset() {
        smoothed = Float.NaN
        state = SpeedState()
    }

    fun update(sample: SpeedSample): SpeedState {
        val speed = sample.speedMps
        val acc = sample.speedAccuracyMps
        val trusted = speed != null && speed.isFinite() && (acc == null || acc <= maxSpeedAccuracyMps)
        if (trusted) {
            val v = if (speed!! < standstillMps) 0f else speed
            // Weight newer samples less when their reported accuracy is mediocre.
            val a = if (acc == null) alpha * 0.8f else alpha * (1f - (acc / maxSpeedAccuracyMps) * 0.5f)
            smoothed = if (smoothed.isNaN()) v else smoothed + a * (v - smoothed)
            if (v == 0f && smoothed < standstillMps) smoothed = 0f
            lastGoodMs = sample.elapsedMs
        }
        state = SpeedState(
            kmh = if (smoothed.isNaN()) 0f else smoothed * 3.6f,
            rawKmh = (speed ?: 0f) * 3.6f,
            reliable = trusted && !smoothed.isNaN(),
            accuracyKmh = acc?.let { it * 3.6f },
            gpsFix = true,
            lastFixMs = sample.elapsedMs,
        )
        return state
    }

    /** Call periodically (e.g. 1 Hz) to age out a lost fix. */
    fun checkStale(nowMs: Long): SpeedState {
        if (nowMs - lastGoodMs > staleAfterMs && state.reliable) {
            state = state.copy(reliable = false, gpsFix = nowMs - state.lastFixMs <= staleAfterMs)
        }
        return state
    }
}
