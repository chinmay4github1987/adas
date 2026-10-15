package com.drivesentinel.perception

import com.drivesentinel.core.BBox
import com.drivesentinel.perception.detector.Detection
import com.drivesentinel.perception.detector.Letterbox
import com.drivesentinel.perception.detector.Nms
import com.drivesentinel.perception.detector.RoadClasses
import com.drivesentinel.perception.detector.YoloLayout
import com.drivesentinel.perception.detector.YoloOutputDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloOutputDecoderTest {

    private val labels = List(80) { "c$it" }.toMutableList().apply {
        this[0] = "person"; this[2] = "car"; this[4] = "airplane"
    }
    private val decoder = YoloOutputDecoder(labels, confThreshold = 0.3f, iouThreshold = 0.45f)

    /** 640×360 source letterboxed into 640×640: scale 1, padY 140. */
    private val lb = Letterbox(640, 360, 640, 640)

    private fun channelsFirst(anchors: Int, fill: (set: (c: Int, i: Int, v: Float) -> Unit) -> Unit): FloatArray {
        val channels = 84
        val out = FloatArray(channels * anchors)
        fill { c, i, v -> out[c * anchors + i] = v }
        return out
    }

    @Test
    fun letterboxMapsBackToSourceFrame() {
        assertEquals(1f, lb.scale, 1e-6f)
        assertEquals(140f, lb.padY, 1e-6f)
        assertEquals(0.5f, lb.toSourceY(320f), 1e-6f)
        assertEquals(0.25f, lb.toSourceX(160f), 1e-6f)
    }

    @Test
    fun resolvesLayouts() {
        assertEquals(YoloLayout.CHANNELS_FIRST, decoder.resolveLayout(intArrayOf(1, 84, 8400)))
        assertEquals(YoloLayout.CHANNELS_LAST, decoder.resolveLayout(intArrayOf(1, 8400, 84)))
        assertEquals(YoloLayout.END_TO_END, decoder.resolveLayout(intArrayOf(1, 300, 6)))
    }

    @Test
    fun decodesChannelsFirstNormalizedWithNmsAndClassFilter() {
        val n = 10
        val out = channelsFirst(n) { set ->
            // car, normalized xywh in model space: centre (0.5, 0.5), 0.2 × 0.1
            set(0, 3, 0.5f); set(1, 3, 0.5f); set(2, 3, 0.2f); set(3, 3, 0.1f); set(4 + 2, 3, 0.9f)
            // near-duplicate car, lower score → suppressed by NMS
            set(0, 4, 0.505f); set(1, 4, 0.5f); set(2, 4, 0.2f); set(3, 4, 0.1f); set(4 + 2, 4, 0.8f)
            // person elsewhere
            set(0, 7, 0.2f); set(1, 7, 0.55f); set(2, 7, 0.05f); set(3, 7, 0.15f); set(4 + 0, 7, 0.6f)
            // airplane (not a road class) → filtered out
            set(0, 8, 0.8f); set(1, 8, 0.4f); set(2, 8, 0.1f); set(3, 8, 0.1f); set(4 + 4, 8, 0.95f)
            // below threshold
            set(0, 9, 0.3f); set(1, 9, 0.5f); set(2, 9, 0.1f); set(3, 9, 0.1f); set(4 + 2, 9, 0.1f)
        }
        val dets = decoder.decode(out, intArrayOf(1, 84, n), lb)
        assertEquals(2, dets.size)
        val car = dets[0]
        assertEquals("car", car.label)
        assertEquals(0.9f, car.score, 1e-6f)
        // x: 0.5±0.1 of 640 → 256..384 px → 0.4..0.6 of source width
        assertEquals(0.4f, car.box.left, 1e-4f)
        assertEquals(0.6f, car.box.right, 1e-4f)
        // y: model 0.45..0.55 × 640 = 288..352 px → minus pad 140 → 148..212 of 360
        assertEquals(148f / 360f, car.box.top, 1e-4f)
        assertEquals(212f / 360f, car.box.bottom, 1e-4f)
        assertEquals("person", dets[1].label)
        assertTrue(dets.none { it.classId == 4 })
    }

    @Test
    fun decodesPixelCoordinatesAndChannelsLast() {
        val n = 3
        val channels = 84
        val out = FloatArray(n * channels)
        fun set(c: Int, i: Int, v: Float) { out[i * channels + c] = v }
        set(0, 1, 320f); set(1, 1, 320f); set(2, 1, 128f); set(3, 1, 64f); set(4 + RoadClasses.TRUCK, 1, 0.7f)
        val dets = decoder.decode(out, intArrayOf(1, n, channels), lb)
        assertEquals(1, dets.size)
        assertEquals(RoadClasses.TRUCK, dets[0].classId)
        assertEquals(0.4f, dets[0].box.left, 1e-4f)
        assertEquals(0.6f, dets[0].box.right, 1e-4f)
    }

    @Test
    fun decodesEndToEndWithoutNms() {
        val rows = 300
        val out = FloatArray(rows * 6)
        fun row(r: Int, x1: Float, y1: Float, x2: Float, y2: Float, s: Float, c: Int) {
            val b = r * 6
            out[b] = x1; out[b + 1] = y1; out[b + 2] = x2; out[b + 3] = y2; out[b + 4] = s; out[b + 5] = c.toFloat()
        }
        row(0, 0.4f, 0.45f, 0.6f, 0.55f, 0.88f, RoadClasses.CAR)
        // Overlapping second box of a different object is kept: the model already ran NMS.
        row(1, 0.41f, 0.45f, 0.61f, 0.55f, 0.5f, RoadClasses.BUS)
        row(2, 0.1f, 0.1f, 0.2f, 0.2f, 0.9f, 4) // airplane, filtered
        row(3, 0.1f, 0.5f, 0.2f, 0.6f, 0.1f, RoadClasses.PERSON) // low score
        val dets = decoder.decode(out, intArrayOf(1, rows, 6), lb)
        assertEquals(2, dets.size)
        assertEquals(RoadClasses.CAR, dets[0].classId)
        assertEquals(0.4f, dets[0].box.left, 1e-4f)
        assertEquals(148f / 360f, dets[0].box.top, 1e-4f)
        assertEquals(RoadClasses.BUS, dets[1].classId)
    }

    @Test
    fun nmsIsClassAwareByDefault() {
        val a = Detection(BBox(0.1f, 0.1f, 0.3f, 0.3f), 2, "car", 0.9f)
        val b = Detection(BBox(0.11f, 0.1f, 0.31f, 0.3f), 7, "truck", 0.8f)
        val c = Detection(BBox(0.12f, 0.1f, 0.32f, 0.3f), 2, "car", 0.7f)
        val aware = Nms.apply(listOf(c, b, a), 0.5f, 10)
        assertEquals(listOf(a, b), aware)
        val agnostic = Nms.apply(listOf(c, b, a), 0.5f, 10, classAgnostic = true)
        assertEquals(listOf(a), agnostic)
    }

    @Test
    fun iouIsCorrect() {
        val a = BBox(0f, 0f, 2f, 2f)
        val b = BBox(1f, 1f, 3f, 3f)
        assertEquals(1f / 7f, a.iou(b), 1e-6f)
    }
}

class YoloxDecodeTest {
    @Test
    fun decodesYoloxObjectnessLayout() {
        val labels = List(80) { "c$it" }
        val decoder = YoloOutputDecoder(labels, confThreshold = 0.3f)
        val n = 4
        val ch = 85
        val out = FloatArray(n * ch)
        fun set(c: Int, i: Int, v: Float) { out[i * ch + c] = v }
        // pixel coords, obj 0.9 × car 0.8 = 0.72
        set(0, 2, 320f); set(1, 2, 320f); set(2, 2, 128f); set(3, 2, 64f); set(4, 2, 0.9f); set(5 + RoadClasses.CAR, 2, 0.8f)
        // high class score but low objectness → dropped
        set(0, 3, 100f); set(1, 3, 300f); set(2, 3, 50f); set(3, 3, 50f); set(4, 3, 0.2f); set(5 + RoadClasses.CAR, 3, 0.99f)
        val shape = intArrayOf(1, n, ch)
        assertEquals(YoloLayout.YOLOX, decoder.resolveLayout(shape))
        val dets = decoder.decode(out, shape, Letterbox(640, 360, 640, 640))
        assertEquals(1, dets.size)
        assertEquals(0.72f, dets[0].score, 1e-5f)
        assertEquals(0.4f, dets[0].box.left, 1e-4f)
    }
}
