package com.drivesentinel.perception.detector

import com.drivesentinel.core.BBox

/** Output tensor layouts produced by common YOLO TFLite/LiteRT exports. */
enum class YoloLayout {
    /** Detect at runtime from the tensor shape. */
    AUTO,

    /** YOLOv8 / YOLO11 raw head: `[1, 4 + nc, N]` (xywh + per-class scores), needs NMS. */
    CHANNELS_FIRST,

    /** Transposed raw head: `[1, N, 4 + nc]`, needs NMS. */
    CHANNELS_LAST,

    /** End-to-end, NMS-free (YOLO26 / YOLOv10): `[1, K, 6]` = x1, y1, x2, y2, score, classId. */
    END_TO_END,

    /**
     * YOLOX (Apache-2.0) exported with decode-in-inference: `[1, N, 5 + nc]` =
     * cx, cy, w, h (input pixels), objectness, per-class scores. Score = obj × cls. Needs NMS.
     */
    YOLOX,
}

/**
 * Pure-Kotlin decoder for YOLO-family detector outputs.
 *
 * Box coordinates may be either normalized (0..1, the Ultralytics TFLite default) or in
 * model-input pixels; this is detected automatically per frame. Results are returned in
 * normalized coordinates of the *source* frame (letterbox padding removed).
 */
class YoloOutputDecoder(
    private val labels: List<String>,
    private val confThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    private val maxDetections: Int = 50,
    private val allowedClasses: Set<Int>? = RoadClasses.ALLOWED,
    private val layout: YoloLayout = YoloLayout.AUTO,
    private val classAgnosticNms: Boolean = false,
) {
    private val numClasses: Int get() = labels.size

    fun resolveLayout(shape: IntArray): YoloLayout {
        if (layout != YoloLayout.AUTO) return layout
        require(shape.size == 3) { "Expected a rank-3 output tensor, got ${shape.contentToString()}" }
        val d1 = shape[1]
        val d2 = shape[2]
        val channels = 4 + numClasses
        return when {
            // [1, 300, 6] – checked first; a 2-class raw head would also have 6 channels but
            // would come with thousands of anchors, not a few hundred.
            d2 == 6 && d1 <= 1000 -> YoloLayout.END_TO_END
            d1 == channels -> YoloLayout.CHANNELS_FIRST
            d2 == channels -> YoloLayout.CHANNELS_LAST
            d2 == channels + 1 -> YoloLayout.YOLOX
            d1 < d2 -> YoloLayout.CHANNELS_FIRST
            else -> YoloLayout.CHANNELS_LAST
        }
    }

    fun decode(output: FloatArray, shape: IntArray, letterbox: Letterbox): List<Detection> =
        when (resolveLayout(shape)) {
            YoloLayout.END_TO_END -> decodeEndToEnd(output, shape, letterbox)
            YoloLayout.CHANNELS_FIRST -> decodeRaw(output, shape[1], shape[2], channelsFirst = true, letterbox)
            YoloLayout.CHANNELS_LAST -> decodeRaw(output, shape[2], shape[1], channelsFirst = false, letterbox)
            YoloLayout.YOLOX -> decodeRaw(output, shape[2], shape[1], channelsFirst = false, letterbox, objectness = true)
            YoloLayout.AUTO -> error("unreachable")
        }

    private fun decodeRaw(
        out: FloatArray,
        channels: Int,
        anchors: Int,
        channelsFirst: Boolean,
        lb: Letterbox,
        objectness: Boolean = false,
    ): List<Detection> {
        val clsStart = if (objectness) 5 else 4
        val nc = channels - clsStart
        require(nc > 0) { "Output has $channels channels; need $clsStart + numClasses" }
        fun at(c: Int, i: Int): Float = if (channelsFirst) out[c * anchors + i] else out[i * channels + c]

        // Normalized or pixel coordinates? Ultralytics TFLite exports are normalized.
        var maxCoord = 0f
        for (i in 0 until anchors) {
            val v = at(2, i)
            if (v > maxCoord) maxCoord = v
            val x = at(0, i)
            if (x > maxCoord) maxCoord = x
        }
        val normalized = maxCoord <= NORMALIZED_LIMIT
        val sx = if (normalized) lb.dstWidth.toFloat() else 1f
        val sy = if (normalized) lb.dstHeight.toFloat() else 1f

        val candidates = ArrayList<Detection>()
        for (i in 0 until anchors) {
            val obj = if (objectness) at(4, i) else 1f
            if (obj < confThreshold) continue
            var best = -1
            var bestScore = confThreshold
            for (c in 0 until nc) {
                if (allowedClasses != null && c !in allowedClasses) continue
                val s = obj * at(clsStart + c, i)
                if (s > bestScore) {
                    bestScore = s
                    best = c
                }
            }
            if (best < 0) continue
            val cx = at(0, i) * sx
            val cy = at(1, i) * sy
            val w = at(2, i) * sx
            val h = at(3, i) * sy
            val box = BBox(
                lb.toSourceX(cx - w / 2f), lb.toSourceY(cy - h / 2f),
                lb.toSourceX(cx + w / 2f), lb.toSourceY(cy + h / 2f),
            ).clamped()
            if (box.area <= 0f) continue
            candidates += Detection(box, best, labelFor(best), bestScore)
        }
        return Nms.apply(candidates, iouThreshold, maxDetections, classAgnosticNms)
    }

    private fun decodeEndToEnd(out: FloatArray, shape: IntArray, lb: Letterbox): List<Detection> {
        val rows = shape[1]
        val stride = shape[2]
        var maxCoord = 0f
        for (r in 0 until rows) {
            val base = r * stride
            if (out[base + 4] < confThreshold) continue
            for (k in 0..3) if (out[base + k] > maxCoord) maxCoord = out[base + k]
        }
        val normalized = maxCoord <= NORMALIZED_LIMIT
        val sx = if (normalized) lb.dstWidth.toFloat() else 1f
        val sy = if (normalized) lb.dstHeight.toFloat() else 1f

        val result = ArrayList<Detection>()
        for (r in 0 until rows) {
            val base = r * stride
            val score = out[base + 4]
            if (score < confThreshold) continue
            val cls = out[base + 5].toInt()
            if (allowedClasses != null && cls !in allowedClasses) continue
            val box = BBox(
                lb.toSourceX(out[base] * sx), lb.toSourceY(out[base + 1] * sy),
                lb.toSourceX(out[base + 2] * sx), lb.toSourceY(out[base + 3] * sy),
            ).clamped()
            if (box.area <= 0f) continue
            result += Detection(box, cls, labelFor(cls), score)
        }
        // Already NMS'd by the model; keep the strongest few.
        return result.sortedByDescending { it.score }.take(maxDetections)
    }

    private fun labelFor(classId: Int): String = labels.getOrElse(classId) { "class$classId" }

    private companion object {
        /** Coordinates at or below this are treated as normalized (0..1, with slack for edges). */
        const val NORMALIZED_LIMIT = 2f
    }
}
