package com.drivesentinel.sensors.motion

import com.drivesentinel.core.G
import com.drivesentinel.core.Vec3
import kotlin.math.acos
import kotlin.math.max

/**
 * Hard-brake detector (research notes §4):
 *
 * * gravity-removed linear acceleration is split into vertical (along gravity) and horizontal
 *   parts and low-pass filtered (~4 Hz at 50 Hz sampling);
 * * longitudinal deceleration = −(horizontal · forward) when the forward axis is known
 *   ([ForwardAxisEstimator]); otherwise |horizontal| (vector-magnitude fallback, which needs the
 *   GPS confirmation to disambiguate braking from acceleration/cornering);
 * * candidate when deceleration ≥ [Config.thresholdG] for ≥ [Config.sustainMs] and the event
 *   is not vertical-dominated (potholes / speed breakers);
 * * confirmed when GPS speed drops ≥ [Config.speedDropKmh] within [Config.confirmWindowMs]
 *   from a pre-event speed ≥ [Config.minSpeedKmh] — or immediately in accelerometer-only mode;
 * * [Config.cooldownMs] debounce (20 s).
 *
 * Timestamps are milliseconds on one monotonic clock (SensorEvent.timestamp and
 * Location.elapsedRealtimeNanos share the elapsedRealtime base).
 */
class BrakeDetector(
    config: Config = Config(),
    private val axisEstimator: ForwardAxisEstimator = ForwardAxisEstimator(),
) {
    data class Config(
        val thresholdG: Float = 0.40f,
        val sustainMs: Long = 300,
        val speedDropKmh: Float = 10f,
        val confirmWindowMs: Long = 2_500,
        val cooldownMs: Long = 20_000,
        val minSpeedKmh: Float = 15f,
        val accelOnly: Boolean = false,
        /** EMA weight per sample; 0.3 at 50 Hz ≈ 4 Hz cut-off. */
        val lowPassAlpha: Float = 0.3f,
        val verticalDominanceRatio: Float = 1.5f,
    )

    enum class Method { PROJECTED, MAGNITUDE, ACCEL_ONLY }

    data class BrakeEvent(
        val timeMs: Long,
        val peakDecelG: Float,
        val speedDropKmh: Float?,
        val preSpeedKmh: Float?,
        val method: Method,
    )

    private data class Candidate(val startMs: Long, var peakMs2: Float, val projected: Boolean)

    var config: Config = config

    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private var hFiltered = Vec3.ZERO
    private var vFiltered = 0f
    private var gravityDir: Vec3? = null
    private var aboveSinceMs = -1L
    private var peakMs2 = 0f
    private var pending: Candidate? = null
    private var lastFireMs = Long.MIN_VALUE / 2

    val forwardAxis: Vec3? get() = axisEstimator.axis

    /** Current longitudinal deceleration estimate in g (for UI/debug). */
    var currentDecelG: Float = 0f
        private set

    fun reset() {
        speeds.clear(); hFiltered = Vec3.ZERO; vFiltered = 0f; gravityDir = null
        aboveSinceMs = -1L; pending = null; axisEstimator.reset()
    }

    fun onSpeed(tMs: Long, speedKmh: Float): BrakeEvent? {
        speeds.addLast(tMs to speedKmh)
        while (speeds.isNotEmpty() && tMs - speeds.first().first > 8_000) speeds.removeFirst()
        axisEstimator.onSpeed(tMs, speedKmh / 3.6f)
        return checkPending(tMs)
    }

    fun onMotion(tMs: Long, linear: Vec3, gravity: Vec3): BrakeEvent? {
        val g = gravity.normalized()
        if (g == Vec3.ZERO) return null
        trackMount(g)

        val vertical = linear dot g
        val horizontal = linear - g * vertical
        val a = config.lowPassAlpha
        hFiltered += (horizontal - hFiltered) * a
        vFiltered += (kotlin.math.abs(vertical) - vFiltered) * a
        axisEstimator.onHorizontalAccel(hFiltered)

        val axis = axisEstimator.axis
        val decel = if (axis != null) -(hFiltered dot axis) else hFiltered.norm
        currentDecelG = decel / G

        val verticalDominant = vFiltered > config.verticalDominanceRatio * hFiltered.norm
        if (decel >= config.thresholdG * G && !verticalDominant) {
            if (aboveSinceMs < 0) {
                aboveSinceMs = tMs
                peakMs2 = 0f
            }
            peakMs2 = max(peakMs2, decel)
            pending?.let { it.peakMs2 = max(it.peakMs2, decel) }
            if (pending == null && tMs - aboveSinceMs >= config.sustainMs && tMs - lastFireMs >= config.cooldownMs) {
                pending = Candidate(aboveSinceMs, peakMs2, projected = axis != null)
            }
        } else {
            aboveSinceMs = -1L
        }
        return checkPending(tMs)
    }

    private fun checkPending(nowMs: Long): BrakeEvent? {
        val p = pending ?: return null
        val pre = speeds.filter { it.first in (p.startMs - 1_500)..p.startMs }.maxOfOrNull { it.second }
            ?: speeds.lastOrNull { it.first <= p.startMs }?.second

        if (config.accelOnly) {
            if (pre != null && pre < config.minSpeedKmh) {
                pending = null
                return null
            }
            return fire(nowMs, p, null, pre, Method.ACCEL_ONLY)
        }

        if (pre != null && pre >= config.minSpeedKmh) {
            val minAfter = speeds
                .filter { it.first in (p.startMs - 1_000)..(p.startMs + config.confirmWindowMs) }
                .minOfOrNull { it.second }
            if (minAfter != null && pre - minAfter >= config.speedDropKmh) {
                return fire(nowMs, p, pre - minAfter, pre, if (p.projected) Method.PROJECTED else Method.MAGNITUDE)
            }
        }
        if (nowMs - p.startMs > config.confirmWindowMs + 1_000) pending = null // not confirmed
        return null
    }

    private fun fire(nowMs: Long, p: Candidate, drop: Float?, pre: Float?, method: Method): BrakeEvent {
        pending = null
        lastFireMs = nowMs
        return BrakeEvent(p.startMs, p.peakMs2 / G, drop, pre, method)
    }

    /** If the gravity direction swings > 20°, the phone was re-mounted: relearn the axis. */
    private fun trackMount(g: Vec3) {
        val prev = gravityDir
        if (prev == null) {
            gravityDir = g
            return
        }
        val angle = Math.toDegrees(acos((prev dot g).coerceIn(-1f, 1f).toDouble()))
        if (angle > 20.0) {
            axisEstimator.reset()
            gravityDir = g
        } else {
            gravityDir = (prev + (g - prev) * 0.01f).normalized()
        }
    }
}
