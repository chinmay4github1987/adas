package com.drivesentinel.perception.fcw

/**
 * Monocular time-to-collision from bounding-box **scale change** (no distance or speed needed).
 *
 * With apparent height h ∝ 1/Z and s = h(t) / h(t − T):  TTC(t) = T / (s − 1).
 * Following Mobileye practice, the reference frame is taken 0.5–1.0 s back rather than the
 * previous frame (consecutive frames are too noisy). Heights are median-of-3 filtered and the
 * inverse TTC (closing rate, which is finite when not approaching) is EMA-smoothed.
 */
class TtcCalculator(
    private val minWindowMs: Long = 500,
    private val maxWindowMs: Long = 1_000,
    private val alpha: Float = 0.35f,
) {
    private data class Sample(val tMs: Long, val h: Float)

    private val raw = ArrayDeque<Float>()
    private val history = ArrayDeque<Sample>()
    private var trackId = Int.MIN_VALUE
    private var invTtc = Float.NaN

    fun reset() {
        raw.clear(); history.clear(); invTtc = Float.NaN; trackId = Int.MIN_VALUE
    }

    /**
     * @param heightNorm box height (any consistent unit, e.g. normalized frame height)
     * @return TTC in seconds; [Float.POSITIVE_INFINITY] when not closing; `null` until enough
     *   history (≥ [minWindowMs]) exists for this track.
     */
    fun update(tMs: Long, heightNorm: Float, track: Int): Float? {
        if (track != trackId) {
            reset()
            trackId = track
        }
        raw.addLast(heightNorm)
        if (raw.size > 3) raw.removeFirst()
        val h = median(raw)
        history.addLast(Sample(tMs, h))
        while (history.isNotEmpty() && tMs - history.first().tMs > maxWindowMs) history.removeFirst()

        val ref = history.first()
        val dtMs = tMs - ref.tMs
        if (dtMs < minWindowMs || ref.h <= 0f) return null
        val s = h / ref.h
        val inst = (s - 1f) / (dtMs / 1000f) // 1/TTC
        invTtc = if (invTtc.isNaN()) inst else invTtc + alpha * (inst - invTtc)
        return if (invTtc > 1e-3f) 1f / invTtc else Float.POSITIVE_INFINITY
    }

    private fun median(q: ArrayDeque<Float>): Float = when (q.size) {
        1 -> q[0]
        2 -> (q[0] + q[1]) / 2f
        else -> {
            val a = q[0]; val b = q[1]; val c = q[2]
            maxOf(minOf(a, b), minOf(maxOf(a, b), c))
        }
    }
}

/** Pinhole distance estimate: Z ≈ f·H_real / h. */
object DistanceEstimator {
    /**
     * @param focalNormX focal length divided by sensor width (f_mm / sensorWidth_mm), i.e. the
     *   focal length in units of image width.
     * @param frameAspect analysis frame width / height.
     * @param boxHeightNorm box height as a fraction of frame height.
     */
    fun distanceMeters(focalNormX: Float, frameAspect: Float, realHeightM: Float, boxHeightNorm: Float): Float {
        if (boxHeightNorm <= 1e-4f) return Float.POSITIVE_INFINITY
        val focalNormY = focalNormX * frameAspect // f in units of image height (square pixels)
        return focalNormY * realHeightM / boxHeightNorm
    }

    /** Fallback when intrinsics are unknown: typical phone main camera ≈ 70° horizontal FOV. */
    fun focalNormFromHfov(hfovDegrees: Float): Float =
        (0.5 / kotlin.math.tan(Math.toRadians(hfovDegrees / 2.0))).toFloat()
}
