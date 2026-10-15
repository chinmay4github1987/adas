package com.drivesentinel.alerts

/**
 * Overspeed state machine with hysteresis and a repeat cooldown.
 *
 *  * enters OVER when speed > threshold for [confirmSamples] consecutive reliable samples
 *    (≈ 2 s at 1 Hz GPS, rejecting a single spiky fix);
 *  * leaves OVER only when speed < threshold − [hysteresisKmh] (default 5 km/h);
 *  * while OVER, asks for a voice alert at most every [repeatCooldownMs] (default 10 s).
 *
 * Unreliable samples (poor speed accuracy / stale fix) never trigger, and do not clear state.
 */
class OverspeedMonitor(
    thresholdKmh: Float = 80f,
    private val hysteresisKmh: Float = 5f,
    private val repeatCooldownMs: Long = 10_000,
    private val confirmSamples: Int = 2,
) {
    data class Result(val isOverspeed: Boolean, val speak: Boolean)

    var thresholdKmh: Float = thresholdKmh
        set(value) {
            field = value
            if (isOverspeed && lastSpeed < value - hysteresisKmh) isOverspeed = false
        }

    var isOverspeed: Boolean = false
        private set

    private var aboveCount = 0
    private var lastAlertMs = Long.MIN_VALUE / 2
    private var lastSpeed = 0f

    fun reset() {
        isOverspeed = false
        aboveCount = 0
        lastAlertMs = Long.MIN_VALUE / 2
    }

    fun update(timeMs: Long, speedKmh: Float, reliable: Boolean = true): Result {
        if (!reliable) return Result(isOverspeed, speak = false)
        lastSpeed = speedKmh
        var speak = false
        if (!isOverspeed) {
            aboveCount = if (speedKmh > thresholdKmh) aboveCount + 1 else 0
            if (aboveCount >= confirmSamples) {
                isOverspeed = true
                if (timeMs - lastAlertMs >= repeatCooldownMs) {
                    speak = true
                    lastAlertMs = timeMs
                }
            }
        } else {
            if (speedKmh < thresholdKmh - hysteresisKmh) {
                isOverspeed = false
                aboveCount = 0
            } else if (timeMs - lastAlertMs >= repeatCooldownMs) {
                speak = true
                lastAlertMs = timeMs
            }
        }
        return Result(isOverspeed, speak)
    }
}
