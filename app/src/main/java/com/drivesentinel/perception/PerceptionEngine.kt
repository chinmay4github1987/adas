package com.drivesentinel.perception

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.drivesentinel.camera.toUprightBitmap
import com.drivesentinel.perception.detector.Detection
import com.drivesentinel.perception.detector.YoloDetector
import com.drivesentinel.perception.fcw.FcwSensitivity
import com.drivesentinel.perception.fcw.FcwStatus
import com.drivesentinel.perception.fcw.ForwardCollisionMonitor
import com.drivesentinel.perception.lane.ArgbFrame
import com.drivesentinel.perception.lane.LaneDepartureAnalyzer
import com.drivesentinel.perception.lane.LaneDetector
import com.drivesentinel.perception.lane.LaneDetectorFactory
import com.drivesentinel.perception.lane.LaneEstimate
import com.drivesentinel.perception.lane.LaneStatus
import com.drivesentinel.perception.detector.RoadClasses
import com.drivesentinel.perception.signs.LightColor
import com.drivesentinel.perception.signs.RedRingFinder
import com.drivesentinel.perception.signs.RoadSignMonitor
import com.drivesentinel.perception.signs.RoadSignStatus
import com.drivesentinel.perception.signs.SignEvent
import com.drivesentinel.perception.signs.SpeedSignReader
import com.drivesentinel.perception.signs.TrafficLightClassifier
import com.drivesentinel.core.BBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

enum class DetectorState(val label: String) {
    LOADING("AI loading"),
    READY("AI ready"),
    NO_MODEL("No model – lanes only"),
    FAILED("AI failed"),
    PAUSED("AI paused (thermal)"),
}

/** Everything one analysed frame produced; normalized coordinates of the upright frame. */
data class PerceptionFrame(
    val timestampMs: Long = 0L,
    val frameWidth: Int = 16,
    val frameHeight: Int = 9,
    val detections: List<Detection> = emptyList(),
    val lanes: LaneEstimate = LaneEstimate.NONE,
    val laneStatus: LaneStatus = LaneStatus(),
    val fcw: FcwStatus = FcwStatus(),
    val signs: RoadSignStatus = RoadSignStatus(),
    /** Announcements produced by this frame (edge events, not state). */
    val signEvents: List<SignEvent> = emptyList(),
    val detectorState: DetectorState = DetectorState.LOADING,
    val accelerator: String? = null,
    val inferenceMs: Long = 0L,
    val aiFps: Float = 0f,
)

/** Inputs the engine reads every frame; written from other threads. */
data class PerceptionConfig(
    val laneAssistEnabled: Boolean = true,
    val fcwEnabled: Boolean = true,
    val fcwSensitivity: FcwSensitivity = FcwSensitivity.MEDIUM,
    val cameraOffset: Float = 0f,
    val signsEnabled: Boolean = true,
)

/**
 * CameraX analyzer: frame → (YOLO detections, lane estimate) → lane state + FCW.
 * Runs on the single analysis thread; the detector is created lazily on that thread (GPU
 * delegate thread affinity). Frame rate is throttled to [targetFps] (thermal ladder); with
 * KEEP_ONLY_LATEST, skipped frames are simply dropped.
 */
class PerceptionEngine(private val context: Context) : ImageAnalysis.Analyzer {

    @Volatile var targetFps: Int = 15
    @Volatile var speedKmh: Float = 0f
    @Volatile var config: PerceptionConfig = PerceptionConfig()
    @Volatile var focalNormX: Float? = null

    /** Called on the analysis thread after every processed frame. */
    @Volatile var onFrame: ((PerceptionFrame) -> Unit)? = null

    private val _frames = MutableStateFlow(PerceptionFrame())
    val frames: StateFlow<PerceptionFrame> = _frames.asStateFlow()

    private val latest = AtomicReference<Bitmap?>(null)

    /** Latest upright analysis frame (for the snapshot fallback). */
    fun latestFrame(): Bitmap? = latest.get()

    private var detector: YoloDetector? = null
    private var detectorState = DetectorState.LOADING
    private var laneDetector: LaneDetector? = null
    private val departure = LaneDepartureAnalyzer()
    private val fcw = ForwardCollisionMonitor()
    private val signs = RoadSignMonitor()
    private var signReader: SpeedSignReader? = null
    private val ocrReads = ConcurrentLinkedQueue<Int>()
    private var lastRunMs = 0L
    private var lastSnapshotMs = 0L
    private var fpsEma = 0f
    @Volatile private var released = false

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            if (released) return
            val now = SystemClock.elapsedRealtime()
            val fps = targetFps
            if (fps <= 0) {
                // AI paused: still refresh the snapshot frame ~1 Hz for event photos.
                if (now - lastSnapshotMs >= 1_000) {
                    latest.set(proxy.toUprightBitmap())
                    lastSnapshotMs = now
                }
                if (_frames.value.detectorState != DetectorState.PAUSED) {
                    fcw.reset()
                    publish(PerceptionFrame(timestampMs = now, detectorState = DetectorState.PAUSED))
                }
                return
            }
            if (now - lastRunMs < 1_000L / fps) return
            val dt = now - lastRunMs
            lastRunMs = now
            if (dt in 1..2_000) fpsEma += 0.2f * (1_000f / dt - fpsEma)

            val bitmap = proxy.toUprightBitmap()
            latest.set(bitmap)
            lastSnapshotMs = now
            process(bitmap, now)
        }
    }

    private fun process(bitmap: Bitmap, now: Long) {
        ensureModels()
        val cfg = config
        val speed = speedKmh
        focalNormX?.let { fcw.focalNormX = it }
        fcw.sensitivity = cfg.fcwSensitivity
        fcw.cameraOffset = cfg.cameraOffset
        departure.cameraOffset = cfg.cameraOffset

        val detections = detector?.let { d ->
            runCatching { d.detect(bitmap) }
                .onFailure { Log.e(TAG, "Detector failed; disabling", it); disableDetector() }
                .getOrNull()
        }.orEmpty()

        // One downscaled frame shared by the lane detector and the sign finder.
        val small = if (laneDetector != null || cfg.signsEnabled) downscale(bitmap) else null
        val lanes = laneDetector?.let { ld ->
            small?.let { runCatching { ld.detect(it, now) }.getOrDefault(LaneEstimate.NONE) }
        } ?: LaneEstimate.NONE

        val laneStatus = departure.update(lanes, speed, now, cfg.laneAssistEnabled)
        val aspect = bitmap.width.toFloat() / bitmap.height
        val fcwStatus = if (cfg.fcwEnabled && detector != null) {
            fcw.update(detections, lanes, speed, aspect, now)
        } else {
            FcwStatus()
        }

        val signUpdate = if (cfg.signsEnabled) {
            processSigns(bitmap, small, detections, speed, now)
        } else {
            signs.reset()
            ocrReads.clear()
            null
        }

        publish(
            PerceptionFrame(
                timestampMs = now,
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                detections = detections,
                lanes = lanes,
                laneStatus = laneStatus,
                fcw = fcwStatus,
                signs = signUpdate?.status ?: RoadSignStatus(),
                signEvents = signUpdate?.events.orEmpty(),
                detectorState = detectorState,
                accelerator = detector?.accelerator,
                inferenceMs = detector?.lastLatencyMs ?: 0L,
                aiFps = fpsEma,
            ),
        )
    }

    private fun downscale(bitmap: Bitmap): ArgbFrame {
        val scaled = Bitmap.createScaledBitmap(bitmap, LANE_W, LANE_H, true)
        val px = IntArray(LANE_W * LANE_H)
        scaled.getPixels(px, 0, LANE_W, 0, 0, LANE_W, LANE_H)
        if (scaled !== bitmap) scaled.recycle()
        return ArgbFrame(LANE_W, LANE_H, px)
    }

    /** Traffic lights + STOP from the detector; speed-limit boards via red-ring finder + OCR. */
    private fun processSigns(
        bitmap: Bitmap,
        small: ArgbFrame?,
        detections: List<Detection>,
        speed: Float,
        now: Long,
    ): RoadSignMonitor.Update {
        while (true) signs.onSpeedRead(ocrReads.poll() ?: break, now)

        val light = detections
            .filter { it.classId == RoadClasses.TRAFFIC_LIGHT && it.score >= 0.4f }
            .maxByOrNull { it.box.area }
            ?.let { classifyLight(bitmap, it.box) }
        val stop = detections.any { it.classId == RoadClasses.STOP_SIGN && it.score >= 0.5f }

        val reader = signReader ?: SpeedSignReader { ocrReads.add(it) }.also { signReader = it }
        if (small != null && reader.canSubmit(now)) {
            RedRingFinder.find(small).firstOrNull()?.let { ring -> crop(bitmap, ring, margin = 0.15f, minSide = 160)?.let { reader.submit(it, now) } }
        }
        return signs.update(light, stop, speed, now)
    }

    private fun classifyLight(bitmap: Bitmap, box: BBox): LightColor {
        val c = crop(bitmap, box, margin = 0f, minSide = 0, maxSide = 48) ?: return LightColor.UNKNOWN
        val px = IntArray(c.width * c.height)
        c.getPixels(px, 0, c.width, 0, 0, c.width, c.height)
        c.recycle()
        return TrafficLightClassifier.classify(px, c.width, c.height)
    }

    /** Crops a normalized box (+ margin) and rescales so its longer side is within [minSide, maxSide]. */
    private fun crop(bitmap: Bitmap, box: BBox, margin: Float, minSide: Int, maxSide: Int = Int.MAX_VALUE): Bitmap? {
        val mx = box.width * margin
        val my = box.height * margin
        val b = BBox(box.left - mx, box.top - my, box.right + mx, box.bottom + my).clamped()
        val x = (b.left * bitmap.width).toInt()
        val y = (b.top * bitmap.height).toInt()
        val w = ((b.right * bitmap.width).toInt() - x).coerceAtMost(bitmap.width - x)
        val h = ((b.bottom * bitmap.height).toInt() - y).coerceAtMost(bitmap.height - y)
        if (w < 2 || h < 2) return null
        // createBitmap returns the source itself for a full-frame crop; never hand that out to be recycled.
        val raw = Bitmap.createBitmap(bitmap, x, y, w, h).let { if (it === bitmap) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        val side = maxOf(w, h)
        val target = side.coerceIn(minSide, maxSide)
        if (target == side) return raw
        val scale = target.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(raw, maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()), true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun publish(frame: PerceptionFrame) {
        _frames.value = frame
        onFrame?.invoke(frame)
    }

    private fun ensureModels() {
        if (laneDetector == null) laneDetector = LaneDetectorFactory.create(context)
        if (detectorState != DetectorState.LOADING) return
        detectorState = if (!YoloDetector.modelExists(context)) {
            Log.w(TAG, "assets/${YoloDetector.MODEL_ASSET} not found – see README to export one")
            DetectorState.NO_MODEL
        } else {
            runCatching { YoloDetector.create(context) }
                .onSuccess {
                    detector = it
                    Log.i(TAG, "Detector on ${it.accelerator}, output ${it.outputShape.contentToString()} → ${it.layout}")
                }
                .onFailure { Log.e(TAG, "Detector load failed", it) }
                .fold({ DetectorState.READY }, { DetectorState.FAILED })
        }
    }

    private fun disableDetector() {
        detector?.close()
        detector = null
        detectorState = DetectorState.FAILED
    }

    /** Must be invoked on the analysis executor (GPU delegate thread affinity). */
    fun releaseOnAnalysisThread() {
        released = true
        detector?.close()
        detector = null
        laneDetector?.close()
        laneDetector = null
        signReader?.close()
        signReader = null
        signs.reset()
        ocrReads.clear()
        detectorState = DetectorState.LOADING
        fcw.reset()
        departure.reset()
        latest.set(null)
    }

    /** Re-arm after [releaseOnAnalysisThread] for a new drive session. */
    fun rearm() {
        released = false
        lastRunMs = 0L
        _frames.value = PerceptionFrame()
    }

    private companion object {
        const val TAG = "PerceptionEngine"
        const val LANE_W = 320
        const val LANE_H = 180
    }
}
