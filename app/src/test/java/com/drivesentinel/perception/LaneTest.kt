package com.drivesentinel.perception

import com.drivesentinel.perception.lane.ArgbFrame
import com.drivesentinel.perception.lane.ClassicalLaneDetector
import com.drivesentinel.perception.lane.LaneDepartureAnalyzer
import com.drivesentinel.perception.lane.LaneEstimate
import com.drivesentinel.perception.lane.LaneLine
import com.drivesentinel.perception.lane.LaneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LaneTest {

    /** Dark road with two bright painted lines converging toward a vanishing point. */
    private fun syntheticRoad(w: Int, h: Int, xbL: Float, xbR: Float, xt: Float = 0.5f): ArgbFrame {
        val px = IntArray(w * h) { 0xFF303030.toInt() }
        val yTop = (0.55f * h).toInt()
        for (y in yTop until h) {
            val s = (h - 1 - y).toFloat() / (h - 1 - yTop)
            for ((xb, xtop) in listOf(xbL to xt - 0.04f, xbR to xt + 0.04f)) {
                val x = ((xb + s * (xtop - xb)) * w).toInt()
                for (dx in -2..2) {
                    val xx = x + dx
                    if (xx in 0 until w) px[y * w + xx] = 0xFFF0F0F0.toInt()
                }
            }
        }
        return ArgbFrame(w, h, px)
    }

    @Test
    fun classicalDetectorFindsBothLines() {
        val det = ClassicalLaneDetector()
        val frame = syntheticRoad(320, 180, xbL = 0.2f, xbR = 0.8f)
        var est = LaneEstimate.NONE
        repeat(3) { est = det.detect(frame, it * 66L) }
        val l = est.left
        val r = est.right
        assertNotNull("left", l)
        assertNotNull("right", r)
        // expected x at the ROI bottom row (y = 0.95): extrapolate synthetic line
        fun expected(xb: Float, xtop: Float, y: Float): Float {
            val s = (1f - y) / (1f - 0.55f)
            return xb + s * (xtop - xb)
        }
        assertEquals(expected(0.2f, 0.46f, l!!.yBottom), l.xBottom, 0.04f)
        assertEquals(expected(0.8f, 0.54f, r!!.yBottom), r.xBottom, 0.04f)
        assertTrue(est.confidence > 0.3f)
    }

    @Test
    fun blankRoadHasNoLanes() {
        val det = ClassicalLaneDetector()
        val px = IntArray(320 * 180) { 0xFF404040.toInt() }
        val est = det.detect(ArgbFrame(320, 180, px), 0)
        assertEquals(0f, est.confidence, 0f)
    }

    private fun lanes(xl: Float, xr: Float, conf: Float = 0.8f) = LaneEstimate(
        LaneLine(xl, 0.46f, 0.95f, 0.6f, conf),
        LaneLine(xr, 0.54f, 0.95f, 0.6f, conf),
    )

    @Test
    fun centeredAndSuppressedBelow40() {
        val a = LaneDepartureAnalyzer()
        val st = a.update(lanes(0.15f, 0.85f), 60f, 0)
        assertEquals(LaneState.CENTERED, st.state)
        assertTrue(abs(st.offset) < 0.01f)
        val slow = a.update(lanes(0.45f, 1.15f), 30f, 100)
        assertTrue(slow.suppressed)
        assertEquals(LaneState.CENTERED, slow.state)
    }

    @Test
    fun slowDriftBecomesRightDrift() {
        val a = LaneDepartureAnalyzer()
        var state = LaneState.CENTERED
        // lanes slide left in the image ⇒ vehicle drifting right, slowly (0.1 lane widths/s)
        for (i in 0..60) {
            val shift = -0.007f * i
            state = a.update(lanes(0.15f + shift, 0.85f + shift), 80f, i * 100L).state
        }
        assertEquals(LaneState.RIGHT_DRIFT, state)
    }

    @Test
    fun fastMoveAndCrossingIsLaneChangeLeft() {
        val a = LaneDepartureAnalyzer()
        val seen = mutableSetOf<LaneState>()
        // lanes slide right quickly ⇒ vehicle moving left at ~1 lane width/s
        var t = 0L
        for (i in 0..12) {
            val shift = 0.07f * i
            seen += a.update(lanes(0.15f + shift, 0.85f + shift), 80f, t).state
            t += 100
        }
        // crossing completed: the old left line is now the right line
        seen += a.update(lanes(-0.45f, 0.25f), 80f, t).state
        assertTrue(seen.toString(), LaneState.CHANGING_LEFT in seen)
        assertFalse(LaneState.CHANGING_RIGHT in seen)
        assertFalse(LaneState.LEFT_DRIFT in seen)
    }

    @Test
    fun lowConfidenceIsUnavailable() {
        val a = LaneDepartureAnalyzer()
        val st = a.update(lanes(0.15f, 0.85f, conf = 0.1f), 80f, 0)
        assertFalse(st.available)
        assertEquals(LaneState.CENTERED, st.state)
    }
}
