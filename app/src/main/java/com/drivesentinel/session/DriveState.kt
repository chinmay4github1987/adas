package com.drivesentinel.session

import com.drivesentinel.camera.CameraStatus
import com.drivesentinel.events.CaptureStatus
import com.drivesentinel.perception.PerceptionFrame
import com.drivesentinel.sensors.location.SpeedState
import com.drivesentinel.thermal.ThermalLevel

/** Single UI-facing snapshot of the running drive session. */
data class DriveState(
    val active: Boolean = false,
    val speed: SpeedState = SpeedState(),
    val speedLimitKmh: Int = 80,
    /** True when [speedLimitKmh] was read from a sign board this drive (else it is the Settings value). */
    val limitFromSign: Boolean = false,
    val overspeed: Boolean = false,
    val perception: PerceptionFrame = PerceptionFrame(),
    val thermal: ThermalLevel = ThermalLevel.NORMAL,
    val thermalHeadroom: Float = Float.NaN,
    val camera: CameraStatus = CameraStatus(),
    val recording: Boolean = false,
    val capture: CaptureStatus = CaptureStatus.Idle,
    val decelG: Float = 0f,
    val forwardAxisLearned: Boolean = false,
    val lastError: String? = null,
) {
    /** Settings are locked while moving (NHTSA-style: no manual interaction while driving). */
    val isMoving: Boolean get() = active && speed.kmh > 5f
}
