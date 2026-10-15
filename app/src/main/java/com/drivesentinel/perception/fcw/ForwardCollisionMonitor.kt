package com.drivesentinel.perception.fcw

import com.drivesentinel.core.BBox
import com.drivesentinel.perception.detector.Detection
import com.drivesentinel.perception.detector.RoadClasses
import com.drivesentinel.perception.lane.LaneEstimate

enum class FcwLevel { NONE, WARNING, CRITICAL }

enum class FcwSensitivity(val multiplier: Float) { LOW(0.8f), MEDIUM(1.0f), HIGH(1.2f) }

data class FcwStatus(
    val level: FcwLevel = FcwLevel.NONE,
    val ttcSeconds: Float? = null,
    val distanceMeters: Float? = null,
    val leadBox: BBox? = null,
    val leadLabel: String? = null,
)

/**
 * Converts a TTC stream into warning levels: WARNING below 2.7 s, CRITICAL below 1.5 s
 * (scaled by sensitivity), only above 15 km/h, and only after N consecutive confirming frames
 * so a single noisy frame or a momentary cut-in does not fire. Release uses hysteresis.
 */
class FcwEvaluator(private val config: Config = Config()) {
    data class Config(
        val warnTtc: Float = 2.7f,
        val criticalTtc: Float = 1.5f,
        val minSpeedKmh: Float = 15f,
        val warnConfirmFrames: Int = 3,
        val criticalConfirmFrames: Int = 2,
        val releaseMargin: Float = 0.4f,
    )

    var sensitivity: FcwSensitivity = FcwSensitivity.MEDIUM

    private var warnCount = 0
    private var critCount = 0
    private var level = FcwLevel.NONE

    fun reset() {
        warnCount = 0; critCount = 0; level = FcwLevel.NONE
    }

    fun evaluate(ttc: Float?, speedKmh: Float): FcwLevel {
        if (ttc == null || speedKmh < config.minSpeedKmh) {
            reset()
            return level
        }
        val warn = config.warnTtc * sensitivity.multiplier
        val crit = config.criticalTtc * sensitivity.multiplier
        warnCount = if (ttc < warn) warnCount + 1 else 0
        critCount = if (ttc < crit) critCount + 1 else 0
        level = when {
            critCount >= config.criticalConfirmFrames -> FcwLevel.CRITICAL
            level == FcwLevel.CRITICAL && ttc < crit + config.releaseMargin -> FcwLevel.CRITICAL
            warnCount >= config.warnConfirmFrames -> FcwLevel.WARNING
            level != FcwLevel.NONE && ttc < warn + config.releaseMargin -> FcwLevel.WARNING
            else -> FcwLevel.NONE
        }
        return level
    }
}

/** Lead selection → TTC → distance → level, for one frame. Single-threaded. */
class ForwardCollisionMonitor(
    private val selector: LeadVehicleSelector = LeadVehicleSelector(),
    private val ttc: TtcCalculator = TtcCalculator(),
    private val evaluator: FcwEvaluator = FcwEvaluator(),
) {
    /** f_mm / sensorWidth_mm. Default ≈ 70° HFOV until real intrinsics arrive. */
    var focalNormX: Float = DistanceEstimator.focalNormFromHfov(70f)
    var sensitivity: FcwSensitivity
        get() = evaluator.sensitivity
        set(value) { evaluator.sensitivity = value }
    var cameraOffset: Float
        get() = selector.cameraOffset
        set(value) { selector.cameraOffset = value }

    fun reset() {
        selector.reset(); ttc.reset(); evaluator.reset()
    }

    fun update(
        detections: List<Detection>,
        lanes: LaneEstimate?,
        speedKmh: Float,
        frameAspect: Float,
        tMs: Long,
    ): FcwStatus {
        val lead = selector.select(detections, lanes)
        if (lead == null) {
            ttc.reset()
            evaluator.evaluate(null, speedKmh)
            return FcwStatus()
        }
        val box = lead.detection.box
        val t = ttc.update(tMs, box.height, lead.trackId)
        val distance = DistanceEstimator.distanceMeters(
            focalNormX, frameAspect, RoadClasses.realHeightMeters(lead.detection.classId), box.height,
        )
        val level = evaluator.evaluate(t, speedKmh)
        return FcwStatus(level, t, distance, box, lead.detection.label)
    }
}
