package com.drivesentinel.perception.detector

/** Greedy non-maximum suppression. */
object Nms {
    /**
     * @param classAgnostic when true a high-scoring box suppresses overlapping boxes of any
     *   class (useful to merge car/truck double-detections of the same vehicle).
     */
    fun apply(
        candidates: List<Detection>,
        iouThreshold: Float,
        maxDetections: Int,
        classAgnostic: Boolean = false,
    ): List<Detection> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending { it.score }
        val suppressed = BooleanArray(sorted.size)
        val kept = ArrayList<Detection>(minOf(maxDetections, sorted.size))
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val a = sorted[i]
            kept += a
            if (kept.size >= maxDetections) break
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val b = sorted[j]
                if ((classAgnostic || a.classId == b.classId) && a.box.iou(b.box) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }
}
