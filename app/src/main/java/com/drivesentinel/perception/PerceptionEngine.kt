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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

        val lanes = laneDetector?.let { ld ->
            val small = Bitmap.createScaledBitmap(bitmap, LANE_W, LANE_H, true)
            val px = IntArray(LANE_W * LANE_H)
            small.getPixels(px, 0, LANE_W, 0, 0, LANE_W, LANE_H)
            if (small !== bitmap) small.recycle()
            runCatching { ld.detect(ArgbFrame(LANE_W, LANE_H, px), now) }.getOrDefault(LaneEstimate.NONE)
        } ?: LaneEstimate.NONE

        val laneStatus = departure.update(lanes, speed, now, cfg.laneAssistEnabled)
        val aspect = bitmap.width.toFloat() / bitmap.height
        val fcwStatus = if (cfg.fcwEnabled && detector != null) {
            fcw.update(detections, lanes, speed, aspect, now)
        } else {
            FcwStatus()
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
                detectorState = detectorState,
                accelerator = detector?.accelerator,
                inferenceMs = detector?.lastLatencyMs ?: 0L,
                aiFps = fpsEma,
            ),
        )
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
