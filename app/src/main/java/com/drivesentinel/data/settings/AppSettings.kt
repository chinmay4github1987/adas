package com.drivesentinel.data.settings

import com.drivesentinel.perception.fcw.FcwSensitivity

/** Hard-brake sensitivity → longitudinal deceleration threshold (research: 0.3–0.5 g band). */
enum class BrakeSensitivity(val thresholdG: Float) { LOW(0.50f), MEDIUM(0.40f), HIGH(0.30f) }

data class AppSettings(
    /** Overspeed threshold. Default 80 km/h per spec; note M1 cars may do 100–120 on expressways. */
    val speedLimitKmh: Int = 80,
    /** Voice prompts on/off. When off, alert tones still play. */
    val voiceEnabled: Boolean = true,
    val hudMirror: Boolean = true,
    val laneAssistEnabled: Boolean = true,
    val fcwEnabled: Boolean = true,
    val fcwSensitivity: FcwSensitivity = FcwSensitivity.MEDIUM,
    val brakeSensitivity: BrakeSensitivity = BrakeSensitivity.MEDIUM,
    /** Trigger hard-brake events from the accelerometer alone (no GPS speed-drop confirmation). */
    val brakeAccelOnly: Boolean = false,
    /** Storage quota for saved events; oldest events are auto-deleted beyond it. */
    val storageQuotaMb: Int = 2_048,
    /** Phone mount offset from the car's centre line (normalized image width, + = right). */
    val cameraOffset: Float = 0f,
    val showDetections: Boolean = true,
    val disclaimerAccepted: Boolean = false,
)
