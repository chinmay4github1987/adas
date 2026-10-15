package com.drivesentinel.sensors

import com.drivesentinel.core.G
import com.drivesentinel.core.Vec3
import com.drivesentinel.sensors.motion.BrakeDetector
import com.drivesentinel.sensors.motion.ForwardAxisEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone mounted in landscape facing the road: gravity along device +x, the rear camera looks
 * along device −z, so vehicle "forward" = −z and a braking deceleration shows up as +z.
 */
class BrakeDetectorTest {
    private val gravity = Vec3(G, 0f, 0f)
    private val forward = Vec3(0f, 0f, -1f)

    private fun detector(config: BrakeDetector.Config = BrakeDetector.Config()): BrakeDetector {
        val axis = ForwardAxisEstimator().apply { setAxis(forward) }
        return BrakeDetector(config, axis)
    }

    /** Drive at 50 Hz: [decelG] for [durationMs] starting at [startMs]; GPS at 1 Hz with [speedAt]. */
    private fun simulate(
        d: BrakeDetector,
        endMs: Long,
        accelAt: (Long) -> Vec3,
        speedAt: (Long) -> Float,
    ): List<BrakeDetector.BrakeEvent> {
        val events = mutableListOf<BrakeDetector.BrakeEvent>()
        var t = 0L
        while (t <= endMs) {
            d.onMotion(t, accelAt(t), gravity)?.let { events += it }
            if (t % 1_000 == 0L) d.onSpeed(t, speedAt(t))?.let { events += it }
            t += 20
        }
        return events
    }

    private fun brakeProfile(start: Long, dur: Long, g: Float) = { t: Long ->
        if (t in start until start + dur) Vec3(0f, 0f, g * G) else Vec3.ZERO
    }

    /** 60 km/h, then losing 0.5 g ≈ 17.6 km/h per second while braking. */
    private fun speedProfile(start: Long, dur: Long, g: Float) = { t: Long ->
        val braking = ((t - start).coerceIn(0, dur)) / 1000f
        (60f - braking * g * G * 3.6f).coerceAtLeast(0f)
    }

    @Test
    fun sustainedDecelWithGpsDropTriggers() {
        val d = detector()
        val events = simulate(d, 8_000, brakeProfile(3_000, 1_500, 0.5f), speedProfile(3_000, 1_500, 0.5f))
        assertEquals(1, events.size)
        val e = events[0]
        assertTrue("peak ${e.peakDecelG}", e.peakDecelG > 0.4f)
        assertTrue(e.speedDropKmh!! >= 10f)
        assertEquals(BrakeDetector.Method.PROJECTED, e.method)
    }

    @Test
    fun shortSpikeBelowSustainTimeIsIgnored() {
        val d = detector()
        val events = simulate(d, 8_000, brakeProfile(3_000, 200, 0.8f), speedProfile(3_000, 1_500, 0.5f))
        assertTrue(events.isEmpty())
    }

    @Test
    fun withoutGpsSpeedDropNothingFires() {
        val d = detector()
        val events = simulate(d, 8_000, brakeProfile(3_000, 1_000, 0.5f)) { 60f }
        assertTrue(events.isEmpty())
    }

    @Test
    fun accelerometerOnlyModeFiresWithoutGps() {
        val d = detector(BrakeDetector.Config(accelOnly = true))
        val events = simulate(d, 8_000, brakeProfile(3_000, 1_000, 0.5f)) { 60f }
        assertEquals(1, events.size)
        assertEquals(BrakeDetector.Method.ACCEL_ONLY, events[0].method)
    }

    @Test
    fun forwardAccelerationIsNotBraking() {
        val d = detector(BrakeDetector.Config(accelOnly = true))
        val events = simulate(d, 6_000, { t -> if (t in 2_000..3_500) Vec3(0f, 0f, -0.5f * G) else Vec3.ZERO }) { 60f }
        assertTrue(events.isEmpty())
    }

    @Test
    fun verticalPotholeSpikeIsRejected() {
        val d = detector(BrakeDetector.Config(accelOnly = true))
        // big vertical jolt with a smaller longitudinal component
        val events = simulate(d, 6_000, { t ->
            if (t in 2_000..2_600) Vec3(1.2f * G, 0f, 0.45f * G) else Vec3.ZERO
        }) { 60f }
        assertTrue(events.isEmpty())
    }

    @Test
    fun cooldownDebouncesRepeatedEvents() {
        val d = detector(BrakeDetector.Config(accelOnly = true))
        val accel = { t: Long ->
            if (t in 1_000..2_000 || t in 6_000..7_000 || t in 25_000..26_000) Vec3(0f, 0f, 0.5f * G) else Vec3.ZERO
        }
        val events = simulate(d, 30_000, accel) { 60f }
        assertEquals(2, events.size) // 1 s and 25 s; the 6 s one is inside the 20 s cooldown
    }

    @Test
    fun belowMinimumSpeedNothingFires() {
        val d = detector()
        val events = simulate(d, 8_000, brakeProfile(3_000, 1_500, 0.5f)) { t -> if (t < 3_000) 12f else 0f }
        assertTrue(events.isEmpty())
    }

    @Test
    fun learnsForwardAxisFromGpsAcceleration() {
        val est = ForwardAxisEstimator()
        var speed = 5f
        for (sec in 0 until 6) {
            // accelerating at ~1.5 m/s² along −z (forward) with lateral noise
            repeat(50) { i -> est.onHorizontalAccel(Vec3(0f, if (i % 2 == 0) 0.3f else -0.3f, -1.5f)) }
            speed += 1.5f
            est.onSpeed(sec * 1_000L, speed)
        }
        val axis = est.axis
        assertNotNull(axis)
        assertTrue("axis=$axis", axis!!.z < -0.95f)
    }

    @Test
    fun magnitudeFallbackStillNeedsGpsConfirmation() {
        val d = BrakeDetector(BrakeDetector.Config(), ForwardAxisEstimator()) // no axis yet
        assertNull(d.forwardAxis)
        val none = simulate(d, 8_000, brakeProfile(3_000, 1_000, 0.5f)) { 60f }
        assertTrue(none.isEmpty())
        val d2 = BrakeDetector(BrakeDetector.Config(), ForwardAxisEstimator())
        val events = simulate(d2, 8_000, brakeProfile(3_000, 1_500, 0.5f), speedProfile(3_000, 1_500, 0.5f))
        assertEquals(1, events.size)
    }
}
