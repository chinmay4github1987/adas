package com.drivesentinel.perception.fcw

import com.drivesentinel.perception.detector.Detection
import com.drivesentinel.perception.detector.RoadClasses
import com.drivesentinel.perception.lane.LaneEstimate

/**
 * Picks the "lead vehicle": the largest lead-candidate box whose bottom centre lies in the
 * ego-lane corridor. The corridor comes from the detected lane lines when both are confident,
 * otherwise a fixed perspective trapezoid around the (calibrated) image centre. Maintains a
 * simple IoU-based track id so the TTC history is reset when the lead target changes
 * (e.g. a two-wheeler cutting in).
 */
class LeadVehicleSelector(private val config: Config = Config()) {

    data class Config(
        val horizonY: Float = 0.45f,
        val halfWidthAtHorizon: Float = 0.05f,
        val halfWidthAtBottom: Float = 0.30f,
        val laneConfidence: Float = 0.45f,
        val laneMargin: Float = 0.03f,
        val sameTrackIou: Float = 0.30f,
        val minBoxHeight: Float = 0.04f,
    )

    data class Lead(val detection: Detection, val trackId: Int)

    var cameraOffset: Float = 0f

    private var previous: Lead? = null
    private var nextId = 1

    fun reset() {
        previous = null
    }

    fun select(detections: List<Detection>, lanes: LaneEstimate?): Lead? {
        val best = detections
            .asSequence()
            .filter { it.classId in RoadClasses.LEAD_CANDIDATES }
            .filter { it.box.height >= config.minBoxHeight }
            .filter { inCorridor(it, lanes) }
            .maxByOrNull { it.box.area }

        if (best == null) {
            previous = null
            return null
        }
        val prev = previous
        val id = if (prev != null && prev.detection.box.iou(best.box) >= config.sameTrackIou) prev.trackId else nextId++
        return Lead(best, id).also { previous = it }
    }

    fun inCorridor(d: Detection, lanes: LaneEstimate?): Boolean {
        val y = d.box.bottom
        if (y <= config.horizonY) return false
        val x = d.box.centerX
        val slack = d.box.width * 0.25f
        val l = lanes?.left
        val r = lanes?.right
        if (l != null && r != null && l.confidence >= config.laneConfidence && r.confidence >= config.laneConfidence) {
            return x + slack >= l.xAt(y) - config.laneMargin && x - slack <= r.xAt(y) + config.laneMargin
        }
        val t = ((y - config.horizonY) / (1f - config.horizonY)).coerceIn(0f, 1f)
        val half = config.halfWidthAtHorizon + t * (config.halfWidthAtBottom - config.halfWidthAtHorizon)
        val c = 0.5f + cameraOffset
        return x + slack >= c - half && x - slack <= c + half
    }
}
