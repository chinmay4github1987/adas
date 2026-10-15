package com.drivesentinel.thermal

import com.drivesentinel.sensors.location.SpeedSample
import com.drivesentinel.sensors.location.SpeedSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalAndSpeedTest {

    @Test
    fun ladderStepsDownFpsAndPausesAtSevere() {
        val p = ThermalPolicy(recoveryMs = 30_000)
        assertEquals(15, p.update(0.5f, 0, 0).aiFps)
        assertEquals(8, p.update(0.86f, 0, 10_000).aiFps)
        assertEquals(4, p.update(0.96f, 0, 20_000).aiFps)
        assertEquals(ThermalLevel.SEVERE, p.update(1.01f, 0, 30_000))
        assertEquals(ThermalLevel.CRITICAL, p.update(0.5f, ThermalPolicy.Status.CRITICAL, 40_000))
        assertTrue(p.level.pauseRecording)
    }

    @Test
    fun recoveryIsGradual() {
        val p = ThermalPolicy(recoveryMs = 30_000)
        p.update(1.1f, 0, 0)
        assertEquals(ThermalLevel.SEVERE, p.update(0.5f, 0, 10_000))
        assertEquals(ThermalLevel.HOT, p.update(0.5f, 0, 40_000))
        assertEquals(ThermalLevel.HOT, p.update(0.5f, 0, 50_000))
        assertEquals(ThermalLevel.WARM, p.update(0.5f, 0, 70_000))
    }

    @Test
    fun nanHeadroomFallsBackToStatus() {
        val p = ThermalPolicy()
        assertEquals(ThermalLevel.HOT, p.classify(Float.NaN, ThermalPolicy.Status.MODERATE))
        assertEquals(ThermalLevel.NORMAL, p.classify(Float.NaN, ThermalPolicy.Status.NONE))
    }

    @Test
    fun speedSmootherConvertsAndRejectsPoorAccuracy() {
        val s = SpeedSmoother()
        val a = s.update(SpeedSample(0, 25f, 0.5f, 5f))
        assertEquals(90f, a.kmh, 0.01f)
        assertTrue(a.reliable)
        val bad = s.update(SpeedSample(1_000, 40f, 5f, 30f)) // 5 m/s accuracy → untrusted
        assertFalse(bad.reliable)
        assertEquals(90f, bad.kmh, 0.01f)
        val stopped = SpeedSmoother().update(SpeedSample(0, 0.5f, 0.3f, 4f))
        assertEquals(0f, stopped.kmh, 0f)
        val stale = s.checkStale(10_000)
        assertFalse(stale.reliable)
    }
}
