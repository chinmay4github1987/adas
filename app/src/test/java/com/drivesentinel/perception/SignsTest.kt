package com.drivesentinel.perception

import com.drivesentinel.perception.lane.ArgbFrame
import com.drivesentinel.perception.signs.LightColor
import com.drivesentinel.perception.signs.RedRingFinder
import com.drivesentinel.perception.signs.RoadSignMonitor
import com.drivesentinel.perception.signs.SignEvent
import com.drivesentinel.perception.signs.SpeedLimitParser
import com.drivesentinel.perception.signs.TrafficLightClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SignsTest {

    private val black = 0xFF101010.toInt()
    private val red = 0xFFE01010.toInt()
    private val amber = 0xFFFFA000.toInt()
    private val green = 0xFF00E0A0.toInt()
    private val white = 0xFFF0F0F0.toInt()
    private val road = 0xFF606060.toInt()

    /** A 10×30 three-lamp signal housing with only [lit] on. */
    private fun signal(lit: Int, litRow: Int): IntArray {
        val px = IntArray(10 * 30) { black }
        for (y in litRow * 10 + 2 until litRow * 10 + 8) for (x in 2 until 8) px[y * 10 + x] = lit
        return px
    }

    @Test
    fun classifiesLitLamp() {
        assertEquals(LightColor.RED, TrafficLightClassifier.classify(signal(red, 0), 10, 30))
        assertEquals(LightColor.AMBER, TrafficLightClassifier.classify(signal(amber, 1), 10, 30))
        assertEquals(LightColor.GREEN, TrafficLightClassifier.classify(signal(green, 2), 10, 30))
        assertEquals(LightColor.UNKNOWN, TrafficLightClassifier.classify(IntArray(300) { black }, 10, 30))
    }

    @Test
    fun parsesOnlyPlausibleLimits() {
        assertEquals(60, SpeedLimitParser.parse("60"))
        assertEquals(50, SpeedLimitParser.parse("5O\nkm/h"))
        assertEquals(100, SpeedLimitParser.parse("1 0 0"))
        assertNull(SpeedLimitParser.parse("10T"))   // weight limit
        assertNull(SpeedLimitParser.parse("STOP"))
        assertNull(SpeedLimitParser.parse("63"))    // not a posted value
        assertNull(SpeedLimitParser.parse(""))
    }

    private fun frameWith(draw: (x: Int, y: Int) -> Int?): ArgbFrame {
        val w = 160
        val h = 90
        return ArgbFrame(w, h, IntArray(w * h) { i -> draw(i % w, i / w) ?: road })
    }

    @Test
    fun findsRedRingButNotFilledDisc() {
        val ring = frameWith { x, y ->
            val d = hypot((x - 40).toFloat(), (y - 25).toFloat())
            when {
                d <= 8f -> white
                d <= 12f -> red
                else -> null
            }
        }
        val found = RedRingFinder.find(ring)
        assertEquals(1, found.size)
        assertEquals(40f / 160f, found[0].centerX, 0.02f)

        val disc = frameWith { x, y -> if (hypot((x - 40).toFloat(), (y - 25).toFloat()) <= 12f) red else null }
        assertTrue(RedRingFinder.find(disc).isEmpty())
    }

    @Test
    fun lightNeedsConfirmationAndRedOnlyAnnouncedWhileMoving() {
        val m = RoadSignMonitor(confirmFrames = 3)
        assertTrue(m.update(LightColor.RED, false, 40f, 0).events.isEmpty())
        assertTrue(m.update(LightColor.RED, false, 40f, 100).events.isEmpty())
        val third = m.update(LightColor.RED, false, 40f, 200)
        assertEquals(listOf<SignEvent>(SignEvent.Light(LightColor.RED)), third.events)
        assertEquals(LightColor.RED, third.status.light)

        val stopped = RoadSignMonitor(confirmFrames = 1)
        assertTrue(stopped.update(LightColor.RED, false, 0f, 0).events.isEmpty())
    }

    @Test
    fun greenAnnouncedAfterRedWhileStopped() {
        val m = RoadSignMonitor(confirmFrames = 1)
        m.update(LightColor.RED, false, 0f, 0)
        assertEquals(listOf<SignEvent>(SignEvent.Light(LightColor.GREEN)), m.update(LightColor.GREEN, false, 0f, 500).events)
        // Green without a preceding red (just driving past) stays silent.
        val n = RoadSignMonitor(confirmFrames = 1)
        assertTrue(n.update(LightColor.GREEN, false, 50f, 0).events.isEmpty())
    }

    @Test
    fun lightClearsAfterHold() {
        val m = RoadSignMonitor(confirmFrames = 1, holdMs = 1_000)
        m.update(LightColor.AMBER, false, 30f, 0)
        assertEquals(LightColor.AMBER, m.update(null, false, 30f, 900).status.light)
        assertNull(m.update(null, false, 30f, 1_100).status.light)
    }

    @Test
    fun stopSignAnnouncedOnceWithinCooldown() {
        val m = RoadSignMonitor(confirmFrames = 2, holdMs = 1_000, stopCooldownMs = 30_000)
        m.update(null, true, 30f, 0)
        assertEquals(listOf<SignEvent>(SignEvent.StopSign), m.update(null, true, 30f, 100).events)
        m.update(null, false, 30f, 2_000) // out of view
        m.update(null, true, 30f, 3_000)
        assertTrue(m.update(null, true, 30f, 3_100).events.isEmpty()) // cooldown
        assertTrue(m.update(null, true, 30f, 3_200).status.stopSign)
    }

    @Test
    fun speedLimitNeedsTwoAgreeingReads() {
        val m = RoadSignMonitor()
        m.onSpeedRead(60, 0)
        assertTrue(m.update(null, false, 50f, 0).events.isEmpty())
        m.onSpeedRead(80, 500) // disagreeing misread
        assertTrue(m.update(null, false, 50f, 500).events.isEmpty())
        m.onSpeedRead(60, 1_000)
        val u = m.update(null, false, 50f, 1_000)
        assertEquals(listOf<SignEvent>(SignEvent.SpeedLimit(60)), u.events)
        assertEquals(60, u.status.speedLimitKmh)

        // Same limit again: no repeat announcement. Stale single reads expire.
        m.onSpeedRead(60, 2_000); m.onSpeedRead(60, 2_100)
        assertTrue(m.update(null, false, 50f, 2_100).events.isEmpty())
        m.onSpeedRead(40, 3_000)
        assertFalse(m.update(null, false, 50f, 9_000).events.isNotEmpty())
    }
}
