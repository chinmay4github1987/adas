package com.drivesentinel.perception

import com.drivesentinel.core.BBox
import com.drivesentinel.perception.detector.Detection
import com.drivesentinel.perception.detector.RoadClasses
import com.drivesentinel.perception.fcw.DistanceEstimator
import com.drivesentinel.perception.fcw.FcwEvaluator
import com.drivesentinel.perception.fcw.FcwLevel
import com.drivesentinel.perception.fcw.FcwSensitivity
import com.drivesentinel.perception.fcw.ForwardCollisionMonitor
import com.drivesentinel.perception.fcw.TtcCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtcCalculatorTest {

    /** Box height of a 1.5 m car at distance z with focal 0.9 (image-height units). */
    private fun height(z: Float) = 0.9f * 1.5f / z

    @Test
    fun constantClosingSpeedGivesTrueTtc() {
        val calc = TtcCalculator()
        val z0 = 30f
        val v = 10f // m/s closing
        var last: Float? = null
        for (frame in 0..22) { // 15 fps for ~1.5 s
            val t = frame * 1000L / 15
            val z = z0 - v * t / 1000f
            last = calc.update(t, height(z), track = 1)
        }
        val tEnd = 22 * 1000f / 15
        val trueTtc = (z0 - v * tEnd / 1000f) / v
        assertNotNull(last)
        // EMA on the closing rate lags slightly; within 15 %.
        assertEquals(trueTtc, last!!, trueTtc * 0.15f)
    }

    @Test
    fun recedingTargetIsInfinite() {
        val calc = TtcCalculator()
        var last: Float? = null
        for (frame in 0..20) {
            val t = frame * 66L
            last = calc.update(t, height(10f + frame * 0.5f), track = 7)
        }
        assertEquals(Float.POSITIVE_INFINITY, last!!, 0f)
    }

    @Test
    fun needsHalfSecondOfHistoryAndResetsOnTrackChange() {
        val calc = TtcCalculator()
        assertNull(calc.update(0, 0.1f, 1))
        assertNull(calc.update(200, 0.11f, 1))
        assertNotNull(calc.update(600, 0.12f, 1))
        // new lead target → history cleared
        assertNull(calc.update(700, 0.3f, 2))
    }

    @Test
    fun evaluatorThresholdsSpeedGateAndConfirmation() {
        val ev = FcwEvaluator()
        // below 15 km/h: never warns
        repeat(5) { assertEquals(FcwLevel.NONE, ev.evaluate(1.0f, 10f)) }
        // 2.0 s at 50 km/h → WARNING after 3 frames
        assertEquals(FcwLevel.NONE, ev.evaluate(2.0f, 50f))
        assertEquals(FcwLevel.NONE, ev.evaluate(2.0f, 50f))
        assertEquals(FcwLevel.WARNING, ev.evaluate(2.0f, 50f))
        // 1.2 s → CRITICAL after 2 frames
        assertEquals(FcwLevel.WARNING, ev.evaluate(1.2f, 50f))
        assertEquals(FcwLevel.CRITICAL, ev.evaluate(1.2f, 50f))
        // hysteresis: 1.7 s keeps CRITICAL (1.5 + 0.4 margin)
        assertEquals(FcwLevel.CRITICAL, ev.evaluate(1.7f, 50f))
        // clear road
        assertEquals(FcwLevel.NONE, ev.evaluate(Float.POSITIVE_INFINITY, 50f))
    }

    @Test
    fun sensitivityScalesThresholds() {
        val ev = FcwEvaluator().apply { sensitivity = FcwSensitivity.LOW } // warn < 2.16 s
        repeat(4) { assertEquals(FcwLevel.NONE, ev.evaluate(2.4f, 60f)) }
        val high = FcwEvaluator().apply { sensitivity = FcwSensitivity.HIGH } // warn < 3.24 s
        repeat(2) { high.evaluate(3.0f, 60f) }
        assertEquals(FcwLevel.WARNING, high.evaluate(3.0f, 60f))
    }

    @Test
    fun distanceFromBoxHeight() {
        // focalNormX 0.7 (≈ 71° HFOV), 16:9 frame → f = 1.244 image heights.
        val d = DistanceEstimator.distanceMeters(0.7f, 16f / 9f, 1.5f, 0.1f)
        assertEquals(0.7f * 16f / 9f * 1.5f / 0.1f, d, 1e-3f)
        assertEquals(70f, Math.toDegrees(2 * Math.atan(0.5 / DistanceEstimator.focalNormFromHfov(70f))).toFloat(), 1e-3f)
    }

    @Test
    fun monitorWarnsForApproachingLeadInCorridorOnly() {
        val monitor = ForwardCollisionMonitor()
        var level = FcwLevel.NONE
        var ttc: Float? = null
        for (frame in 0..30) {
            val t = frame * 66L
            val z = 20f - 8f * t / 1000f // closing at 8 m/s → ends ≈ 4 m away
            val h = height(z)
            val box = BBox(0.45f, 0.7f - h, 0.55f, 0.7f)
            val status = monitor.update(listOf(Detection(box, RoadClasses.CAR, "car", 0.9f)), null, 60f, 16f / 9f, t)
            level = status.level
            ttc = status.ttcSeconds
        }
        assertEquals(FcwLevel.CRITICAL, level)
        assertTrue(ttc!! < 1.5f)

        // Same vehicle far off to the side is not the lead.
        val side = ForwardCollisionMonitor()
        for (frame in 0..30) {
            val t = frame * 66L
            val h = height(20f - 8f * t / 1000f)
            val box = BBox(0.02f, 0.7f - h, 0.12f, 0.7f)
            level = side.update(listOf(Detection(box, RoadClasses.CAR, "car", 0.9f)), null, 60f, 16f / 9f, t).level
        }
        assertEquals(FcwLevel.NONE, level)
    }
}
