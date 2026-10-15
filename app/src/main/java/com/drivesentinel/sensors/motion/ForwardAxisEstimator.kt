package com.drivesentinel.sensors.motion

import com.drivesentinel.core.Vec3

/**
 * Learns the vehicle's forward axis in *device* coordinates (the phone is mounted, so this is
 * fixed until the mount moves) by correlating horizontal linear acceleration with GPS speed
 * change: while the GPS says we are speeding up (or slowing down) by at least [minGpsAccel],
 * the mean horizontal acceleration over that GPS interval points forward (or backward).
 *
 * The axis is reported only once [minSamples] contributions agree (resultant / sum of norms ≥
 * [minConsistency]); until then callers fall back to the vector-magnitude approach.
 */
class ForwardAxisEstimator(
    private val minGpsAccel: Float = 0.7f,
    private val minSamples: Int = 4,
    private val minConsistency: Float = 0.6f,
    private val minHorizontalAccel: Float = 0.3f,
) {
    private var sum = Vec3.ZERO
    private var sumNorms = 0f
    private var count = 0

    private var intervalSum = Vec3.ZERO
    private var intervalN = 0
    private var lastSpeedT = -1L
    private var lastSpeed = 0f

    var axis: Vec3? = null
        private set

    val sampleCount: Int get() = count

    fun reset() {
        sum = Vec3.ZERO; sumNorms = 0f; count = 0; axis = null
        intervalSum = Vec3.ZERO; intervalN = 0; lastSpeedT = -1L
    }

    /** Feed the (low-pass filtered) horizontal linear acceleration, device frame, m/s². */
    fun onHorizontalAccel(horizontal: Vec3) {
        intervalSum += horizontal
        intervalN++
    }

    /** Feed a GPS speed sample (m/s). */
    fun onSpeed(tMs: Long, speedMps: Float) {
        if (lastSpeedT >= 0 && intervalN > 0) {
            val dt = (tMs - lastSpeedT) / 1000f
            if (dt in 0.3f..3f) {
                val gpsAccel = (speedMps - lastSpeed) / dt
                val mean = intervalSum * (1f / intervalN)
                if (kotlin.math.abs(gpsAccel) >= minGpsAccel && mean.norm >= minHorizontalAccel) {
                    val contribution = if (gpsAccel > 0) mean else mean * -1f
                    sum += contribution
                    sumNorms += contribution.norm
                    count++
                    if (count >= minSamples && sumNorms > 0f && sum.norm / sumNorms >= minConsistency) {
                        axis = sum.normalized()
                    }
                }
            }
        }
        lastSpeedT = tMs
        lastSpeed = speedMps
        intervalSum = Vec3.ZERO
        intervalN = 0
    }

    /** Seed a known axis (tests, or a persisted calibration). */
    fun setAxis(forward: Vec3) {
        axis = forward.normalized()
    }
}
