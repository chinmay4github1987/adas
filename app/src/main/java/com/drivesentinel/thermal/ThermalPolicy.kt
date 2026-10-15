package com.drivesentinel.thermal

/**
 * Thermal step-down ladder (research notes §6): headroom 0.85 ≈ LIGHT, 0.95 ≈ MODERATE,
 * ≥ 1.0 ≈ SEVERE. AI fps 15 → 8 → 4 → paused; at CRITICAL recording is paused too.
 */
enum class ThermalLevel(val aiFps: Int, val pauseRecording: Boolean, val label: String) {
    NORMAL(15, false, "OK"),
    WARM(8, false, "WARM"),
    HOT(4, false, "HOT"),
    SEVERE(0, false, "AI PAUSED"),
    CRITICAL(0, true, "CRITICAL"),
}

/**
 * Pure mapping from (headroom, platform status) to [ThermalLevel], with slow recovery: the
 * level steps *up* (hotter) immediately, but only steps *down* one level after the cooler
 * condition has held for [recoveryMs].
 */
class ThermalPolicy(private val recoveryMs: Long = 30_000) {

    /** Mirrors PowerManager.THERMAL_STATUS_* values (0 = NONE … 6 = SHUTDOWN). */
    object Status {
        const val NONE = 0
        const val LIGHT = 1
        const val MODERATE = 2
        const val SEVERE = 3
        const val CRITICAL = 4
        const val EMERGENCY = 5
        const val SHUTDOWN = 6
    }

    var level: ThermalLevel = ThermalLevel.NORMAL
        private set
    private var coolerSinceMs = -1L

    fun classify(headroom: Float, status: Int): ThermalLevel {
        val byStatus = when {
            status >= Status.CRITICAL -> ThermalLevel.CRITICAL
            status == Status.SEVERE -> ThermalLevel.SEVERE
            status == Status.MODERATE -> ThermalLevel.HOT
            status == Status.LIGHT -> ThermalLevel.WARM
            else -> ThermalLevel.NORMAL
        }
        val byHeadroom = when {
            headroom.isNaN() -> ThermalLevel.NORMAL // unsupported / rate-limited
            headroom >= 1.0f -> ThermalLevel.SEVERE
            headroom >= 0.95f -> ThermalLevel.HOT
            headroom >= 0.85f -> ThermalLevel.WARM
            else -> ThermalLevel.NORMAL
        }
        return maxOf(byStatus, byHeadroom)
    }

    fun update(headroom: Float, status: Int, nowMs: Long): ThermalLevel {
        val target = classify(headroom, status)
        when {
            target >= level -> {
                level = target
                coolerSinceMs = -1L
            }
            else -> {
                if (coolerSinceMs < 0) coolerSinceMs = nowMs
                if (nowMs - coolerSinceMs >= recoveryMs) {
                    level = ThermalLevel.entries[level.ordinal - 1]
                    coolerSinceMs = nowMs
                }
            }
        }
        return level
    }
}
