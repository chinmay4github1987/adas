package com.drivesentinel.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverspeedMonitorTest {

    @Test
    fun requiresTwoConsecutiveSamplesAboveThreshold() {
        val m = OverspeedMonitor(thresholdKmh = 80f)
        assertFalse(m.update(0, 85f).isOverspeed)
        assertFalse(m.update(1_000, 79f).isOverspeed) // spike rejected
        assertFalse(m.update(2_000, 85f).isOverspeed)
        val r = m.update(3_000, 86f)
        assertTrue(r.isOverspeed)
        assertTrue(r.speak)
    }

    @Test
    fun hysteresisClearsOnlyBelowThresholdMinusFive() {
        val m = OverspeedMonitor(thresholdKmh = 80f)
        m.update(0, 90f); m.update(1_000, 90f)
        assertTrue(m.isOverspeed)
        assertTrue(m.update(2_000, 79f).isOverspeed)
        assertTrue(m.update(3_000, 75.5f).isOverspeed)
        assertFalse(m.update(4_000, 74.9f).isOverspeed)
    }

    @Test
    fun repeatsVoiceEveryTenSecondsWhileOver() {
        val m = OverspeedMonitor(thresholdKmh = 80f)
        val spokenAt = mutableListOf<Long>()
        for (s in 0..25) {
            val t = s * 1_000L
            if (m.update(t, 95f).speak) spokenAt += t
        }
        assertEquals(listOf(1_000L, 11_000L, 21_000L), spokenAt)
    }

    @Test
    fun cooldownSurvivesQuickReentry() {
        val m = OverspeedMonitor(thresholdKmh = 80f)
        m.update(0, 90f)
        assertTrue(m.update(1_000, 90f).speak)
        m.update(2_000, 70f) // cleared
        m.update(3_000, 90f)
        val r = m.update(4_000, 90f) // over again, but only 3 s since last voice
        assertTrue(r.isOverspeed)
        assertFalse(r.speak)
        assertTrue(m.update(11_000, 90f).speak)
    }

    @Test
    fun unreliableSamplesAreIgnored() {
        val m = OverspeedMonitor(thresholdKmh = 80f)
        repeat(5) { assertFalse(m.update(it * 1_000L, 150f, reliable = false).isOverspeed) }
    }

    @Test
    fun raisingThresholdClearsOverspeed() {
        val m = OverspeedMonitor(thresholdKmh = 80f)
        m.update(0, 90f); m.update(1_000, 90f)
        assertTrue(m.isOverspeed)
        m.thresholdKmh = 100f
        assertFalse(m.isOverspeed)
    }

    @Test
    fun beepSynthRendersExpectedLength() {
        val pcm = BeepSynth.render(BeepSynth.ROBOT_PROMPT)
        assertEquals((90 + 30 + 120) * BeepSynth.SAMPLE_RATE / 1000, pcm.size)
        assertTrue(pcm.any { it > 1000 })
    }
}
