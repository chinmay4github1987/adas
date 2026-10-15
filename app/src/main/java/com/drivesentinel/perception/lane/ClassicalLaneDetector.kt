package com.drivesentinel.perception.lane

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Classical lane-line detector in pure Kotlin (no OpenCV):
 *
 * 1. luma conversion inside a trapezoidal ROI,
 * 2. 3×3 Sobel gradients, keeping strong, non-horizontal edges (adaptive percentile threshold),
 * 3. Hough-style voting over straight lines parameterised by their x at the ROI bottom and top
 *    rows (a 2-D accumulator that maps naturally to "lane line" geometry),
 * 4. best left/right line by slope sign, then temporal EMA smoothing with outlier gating and a
 *    short hold when a line drops out (dashed markings).
 *
 * Works on highways with visible paint; it will not find lanes on unmarked roads (common in
 * Indian cities) — that is reported as low confidence, and the departure logic then stays quiet.
 * Feed it a downscaled frame (~320×180); cost is a few ms on a mid-range CPU.
 */
class ClassicalLaneDetector(private val config: Config = Config()) : LaneDetector {

    data class Config(
        /** ROI top row as a fraction of frame height (just below the horizon). */
        val roiTop: Float = 0.60f,
        /** ROI bottom row (above the bonnet). */
        val roiBottom: Float = 0.95f,
        /** Half-width of the trapezoid at the ROI top, as a fraction of frame width. */
        val roiTopHalfWidth: Float = 0.14f,
        /** Half-width at the ROI bottom. */
        val roiBottomHalfWidth: Float = 0.50f,
        /** Edge-strength percentile used as the adaptive threshold. */
        val edgePercentile: Float = 0.88f,
        /** Absolute floor for the |gx|+|gy| edge threshold (0..2040). */
        val minEdgeStrength: Int = 70,
        val xBottomBins: Int = 120,
        val xTopBins: Int = 64,
        /** Minimum normalised vote support for a line to count. */
        val minLineConfidence: Float = 0.22f,
        val smoothingAlpha: Float = 0.35f,
        /** Frames a line is held (with decaying confidence) after it disappears. */
        val holdFrames: Int = 8,
        /** Jumps larger than this (normalised x) are treated as outliers… */
        val maxJump: Float = 0.12f,
        /** …unless they persist for this many frames. */
        val outlierPersistFrames: Int = 3,
    )

    override val name: String = "Classical (Sobel + Hough)"

    private val left = SideTracker()
    private val right = SideTracker()

    private var luma = IntArray(0)

    override fun detect(frame: ArgbFrame, timestampMs: Long): LaneEstimate {
        val w = frame.width
        val h = frame.height
        val yT = (config.roiTop * h).roundToInt().coerceIn(1, h - 2)
        val yB = (config.roiBottom * h).roundToInt().coerceIn(yT + 2, h - 2)
        if (luma.size < w * h) luma = IntArray(w * h)

        // 1. Luma for the ROI rows (+1 row margin for Sobel).
        for (y in (yT - 1)..(yB + 1)) {
            val row = y * w
            for (x in 0 until w) {
                val p = frame.pixels[row + x]
                luma[row + x] = (77 * (p shr 16 and 0xFF) + 150 * (p shr 8 and 0xFF) + 29 * (p and 0xFF)) shr 8
            }
        }

        // 2. Sobel inside the trapezoid; collect edge pixels.
        val roiRows = yB - yT
        val maxEdges = roiRows * w
        val ex = IntArray(maxEdges)
        val ey = IntArray(maxEdges)
        val em = IntArray(maxEdges)
        var n = 0
        val hist = IntArray(2041)
        for (y in yT..yB) {
            val t = (yB - y).toFloat() / roiRows
            val half = (config.roiBottomHalfWidth + t * (config.roiTopHalfWidth - config.roiBottomHalfWidth)) * w
            val x0 = max(1, (w / 2f - half).toInt())
            val x1 = min(w - 2, (w / 2f + half).toInt())
            for (x in x0..x1) {
                val i = y * w + x
                val gx = (luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1]) -
                    (luma[i - w - 1] + 2 * luma[i - 1] + luma[i + w - 1])
                val gy = (luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1]) -
                    (luma[i - w - 1] + 2 * luma[i - w] + luma[i - w + 1])
                val agx = abs(gx)
                val agy = abs(gy)
                // Lane paint edges are steep in the image; reject near-horizontal edges
                // (shadows, vehicle bottoms, speed-breaker stripes).
                if (agx < agy * 0.45f) continue
                val m = min(2040, agx + agy)
                if (m < config.minEdgeStrength) continue
                ex[n] = x; ey[n] = y; em[n] = m; n++
                hist[m]++
            }
        }
        val threshold = percentile(hist, n, config.edgePercentile).coerceAtLeast(config.minEdgeStrength)

        // 3. Vote. Line through (xb, yB) and (xt, yT); for an edge at row y with
        //    s = (yB - y) / (yB - yT): x = xb + s (xt - xb)  ⇒  xb = (x - s·xt) / (1 - s).
        val nb = config.xBottomBins
        val nt = config.xTopBins
        val xbMin = -0.6f * w
        val xbMax = 1.6f * w
        val xtMin = 0.5f * w - config.roiTopHalfWidth * w * 1.6f
        val xtMax = 0.5f * w + config.roiTopHalfWidth * w * 1.6f
        val xbStep = (xbMax - xbMin) / nb
        val xtStep = (xtMax - xtMin) / nt
        val acc = FloatArray(nb * nt)
        for (k in 0 until n) {
            if (em[k] < threshold) continue
            val s = (yB - ey[k]).toFloat() / roiRows
            if (s > 0.92f) continue
            val inv = 1f / (1f - s)
            for (ti in 0 until nt) {
                val xt = xtMin + (ti + 0.5f) * xtStep
                val xb = (ex[k] - s * xt) * inv
                val bi = ((xb - xbMin) / xbStep).toInt()
                if (bi < 0 || bi >= nb) continue
                acc[bi * nt + ti] += 1f
            }
        }

        // 4. Best line per side, constrained by slope.
        val expected = roiRows * 1.5f
        val centerX = w / 2f
        var bestL = -1f; var bestLb = 0f; var bestLt = 0f
        var bestR = -1f; var bestRb = 0f; var bestRt = 0f
        for (bi in 1 until nb - 1) {
            val xb = xbMin + (bi + 0.5f) * xbStep
            for (ti in 1 until nt - 1) {
                val xt = xtMin + (ti + 0.5f) * xtStep
                val slope = (xt - xb) / roiRows // dx per row moving up
                val score = acc[(bi - 1) * nt + ti] + acc[bi * nt + ti] + acc[(bi + 1) * nt + ti] +
                    0.5f * (acc[bi * nt + ti - 1] + acc[bi * nt + ti + 1])
                if (xb < centerX && xt > xb && slope in 0.25f..4f) {
                    if (score > bestL) { bestL = score; bestLb = xb; bestLt = xt }
                } else if (xb > centerX && xt < xb && -slope in 0.25f..4f) {
                    if (score > bestR) { bestR = score; bestRb = xb; bestRt = xt }
                }
            }
        }
        val yBn = yB.toFloat() / h
        val yTn = yT.toFloat() / h
        val rawLeft = (bestL / expected).coerceIn(0f, 1f).takeIf { it >= config.minLineConfidence }
            ?.let { LaneLine(bestLb / w, bestLt / w, yBn, yTn, it) }
        val rawRight = (bestR / expected).coerceIn(0f, 1f).takeIf { it >= config.minLineConfidence }
            ?.let { LaneLine(bestRb / w, bestRt / w, yBn, yTn, it) }

        return LaneEstimate(left.update(rawLeft), right.update(rawRight))
    }

    fun reset() {
        left.reset(); right.reset()
    }

    private fun percentile(hist: IntArray, count: Int, p: Float): Int {
        if (count == 0) return Int.MAX_VALUE
        val target = (count * p).toInt()
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return hist.size - 1
    }

    /** EMA smoothing + outlier gating + hold for one lane side. */
    private inner class SideTracker {
        private var current: LaneLine? = null
        private var missing = 0
        private var outliers = 0

        fun update(raw: LaneLine?): LaneLine? {
            val cur = current
            if (raw == null) {
                missing++
                if (cur == null || missing > config.holdFrames) {
                    current = null
                    return null
                }
                current = cur.copy(confidence = cur.confidence * 0.8f)
                return current
            }
            missing = 0
            if (cur == null) {
                current = raw
                return raw
            }
            val jump = max(abs(raw.xBottom - cur.xBottom), abs(raw.xTop - cur.xTop))
            if (jump > config.maxJump) {
                outliers++
                if (outliers < config.outlierPersistFrames) {
                    current = cur.copy(confidence = cur.confidence * 0.9f)
                    return current
                }
                outliers = 0
                current = raw // the scene really changed (e.g. lane change completed)
                return raw
            }
            outliers = 0
            val a = config.smoothingAlpha
            current = LaneLine(
                xBottom = cur.xBottom + a * (raw.xBottom - cur.xBottom),
                xTop = cur.xTop + a * (raw.xTop - cur.xTop),
                yBottom = raw.yBottom,
                yTop = raw.yTop,
                confidence = cur.confidence + a * (raw.confidence - cur.confidence),
            )
            return current
        }

        fun reset() {
            current = null; missing = 0; outliers = 0
        }
    }
}
