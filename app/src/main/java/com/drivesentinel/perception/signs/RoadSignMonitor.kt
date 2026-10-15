package com.drivesentinel.perception.signs

/** Something the driver should hear about. */
sealed interface SignEvent {
    data class Light(val color: LightColor) : SignEvent
    data object StopSign : SignEvent
    data class SpeedLimit(val kmh: Int) : SignEvent
}

/** What is currently in view (debounced). */
data class RoadSignStatus(
    val light: LightColor? = null,
    val stopSign: Boolean = false,
    val speedLimitKmh: Int? = null,
)

/**
 * Debounces per-frame sign/light observations into stable state and announcement events.
 *
 *  * A light colour or STOP sign counts only after [confirmFrames] consecutive sightings, and is
 *    dropped after [holdMs] without one (detections flicker frame to frame).
 *  * Red/amber are announced only while moving (that is when they matter); green only after a
 *    red/amber while stopped ("you can go"). STOP is announced while moving, with a cooldown.
 *  * A speed limit is accepted when OCR reads the same value twice within [readWindowMs].
 */
class RoadSignMonitor(
    private val confirmFrames: Int = 3,
    private val holdMs: Long = 1_500,
    private val readWindowMs: Long = 4_000,
    private val lightCooldownMs: Long = 10_000,
    private val stopCooldownMs: Long = 30_000,
) {
    data class Update(val status: RoadSignStatus, val events: List<SignEvent>)

    private var candidate: LightColor? = null
    private var candidateCount = 0
    private var stableLight: LightColor? = null
    private var lastLightSeenMs = 0L
    private val lastLightAnnounceMs = HashMap<LightColor, Long>()

    private var stopCount = 0
    private var stopVisible = false
    private var lastStopSeenMs = 0L
    private var lastStopAnnounceMs = Long.MIN_VALUE / 2

    private val reads = ArrayDeque<Pair<Long, Int>>()
    private var confirmedLimit: Int? = null

    fun reset() {
        candidate = null; candidateCount = 0; stableLight = null; lastLightSeenMs = 0L
        lastLightAnnounceMs.clear()
        stopCount = 0; stopVisible = false; lastStopSeenMs = 0L; lastStopAnnounceMs = Long.MIN_VALUE / 2
        reads.clear(); confirmedLimit = null
    }

    /** An OCR result for a speed-limit sign candidate. */
    fun onSpeedRead(kmh: Int, nowMs: Long) {
        reads.addLast(nowMs to kmh)
    }

    fun update(light: LightColor?, stopSignSeen: Boolean, speedKmh: Float, nowMs: Long): Update {
        val events = mutableListOf<SignEvent>()
        updateLight(light, speedKmh, nowMs, events)
        updateStop(stopSignSeen, speedKmh, nowMs, events)
        updateLimit(nowMs, events)
        return Update(RoadSignStatus(stableLight, stopVisible, confirmedLimit), events)
    }

    private fun updateLight(light: LightColor?, speedKmh: Float, nowMs: Long, events: MutableList<SignEvent>) {
        if (light == null || light == LightColor.UNKNOWN) {
            if (nowMs - lastLightSeenMs > holdMs) {
                stableLight = null; candidate = null; candidateCount = 0
            }
            return
        }
        lastLightSeenMs = nowMs
        if (light == candidate) candidateCount++ else { candidate = light; candidateCount = 1 }
        if (candidateCount < confirmFrames || stableLight == light) return

        val previous = stableLight
        stableLight = light
        val announce = when (light) {
            LightColor.RED, LightColor.AMBER -> speedKmh >= MOVING_KMH
            LightColor.GREEN -> (previous == LightColor.RED || previous == LightColor.AMBER) && speedKmh < STOPPED_KMH
            LightColor.UNKNOWN -> false
        }
        val last = lastLightAnnounceMs[light] ?: (Long.MIN_VALUE / 2)
        if (announce && nowMs - last >= lightCooldownMs) {
            lastLightAnnounceMs[light] = nowMs
            events += SignEvent.Light(light)
        }
    }

    private fun updateStop(seen: Boolean, speedKmh: Float, nowMs: Long, events: MutableList<SignEvent>) {
        if (!seen) {
            if (nowMs - lastStopSeenMs > holdMs) { stopCount = 0; stopVisible = false }
            return
        }
        stopCount = if (nowMs - lastStopSeenMs > holdMs) 1 else stopCount + 1
        lastStopSeenMs = nowMs
        if (stopCount >= confirmFrames && !stopVisible) {
            stopVisible = true
            if (speedKmh >= MOVING_KMH && nowMs - lastStopAnnounceMs >= stopCooldownMs) {
                lastStopAnnounceMs = nowMs
                events += SignEvent.StopSign
            }
        }
    }

    private fun updateLimit(nowMs: Long, events: MutableList<SignEvent>) {
        while (reads.isNotEmpty() && nowMs - reads.first().first > readWindowMs) reads.removeFirst()
        val agreed = reads.groupingBy { it.second }.eachCount().filterValues { it >= 2 }.keys
        val value = reads.lastOrNull { it.second in agreed }?.second ?: return
        reads.clear()
        if (value != confirmedLimit) {
            confirmedLimit = value
            events += SignEvent.SpeedLimit(value)
        }
    }

    private companion object {
        const val MOVING_KMH = 10f
        const val STOPPED_KMH = 8f
    }
}
