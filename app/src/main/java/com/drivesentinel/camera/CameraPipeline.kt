package com.drivesentinel.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.annotation.MainThread
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Which use-case combination the device accepted. */
enum class CameraMode(val description: String) {
    FULL("Preview + AI + 720p video + photo"),
    NO_IMAGE_CAPTURE("Preview + AI + 720p video (photos from AI frames)"),
    LOW_VIDEO("Preview + AI + SD video (photos from AI frames)"),
    ANALYSIS_ONLY("Preview + AI only (no video)"),
}

data class CameraStatus(
    val bound: Boolean = false,
    val mode: CameraMode? = null,
    val error: String? = null,
    /** f_mm / sensorWidth_mm — focal length in units of image width (for FCW distance). */
    val focalNormX: Float? = null,
)

/**
 * Owns CameraX use cases. Bound to the **service** lifecycle so recording and AI continue when
 * the activity is not visible; the activity only attaches/detaches the preview surface.
 *
 * Tries Preview + ImageAnalysis + VideoCapture(HD) + ImageCapture in a single
 * `bindToLifecycle` call, and steps down if the device rejects the combination (CameraX throws
 * IllegalArgumentException for unsupported stream combinations at bind time).
 */
class CameraPipeline(private val context: Context) {

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    var recorder: Recorder? = null
        private set

    private val _status = MutableStateFlow(CameraStatus())
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val analysisSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
        )
        .build()

    private val previewSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .build()

    @MainThread
    suspend fun bind(
        owner: LifecycleOwner,
        analysisExecutor: Executor,
        analyzer: ImageAnalysis.Analyzer,
    ): CameraStatus {
        val cameraProvider = provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
        cameraProvider.unbindAll()
        val rotation = displayRotation()

        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        fun videoCapture(quality: Quality): Pair<VideoCapture<Recorder>, Recorder> {
            val rec = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
            return VideoCapture.Builder(rec).setTargetRotation(rotation).build() to rec
        }

        val photo = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(previewSelector)
            .setTargetRotation(rotation)
            .build()

        data class Attempt(val mode: CameraMode, val quality: Quality?, val withPhoto: Boolean)
        val attempts = listOf(
            Attempt(CameraMode.FULL, Quality.HD, true),
            Attempt(CameraMode.NO_IMAGE_CAPTURE, Quality.HD, false),
            Attempt(CameraMode.LOW_VIDEO, Quality.SD, false),
            Attempt(CameraMode.ANALYSIS_ONLY, null, false),
        )

        var lastError: Throwable? = null
        for (attempt in attempts) {
            val useCases = mutableListOf<UseCase>(preview, analysis)
            val video = attempt.quality?.let { videoCapture(it) }
            video?.let { useCases += it.first }
            if (attempt.withPhoto) useCases += photo
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray(),
                )
                this.preview = preview
                this.imageCapture = if (attempt.withPhoto) photo else null
                this.recorder = video?.second
                val st = CameraStatus(bound = true, mode = attempt.mode, focalNormX = focalNormX(camera, rotation))
                _status.value = st
                Log.i(TAG, "Camera bound: ${attempt.mode.description}")
                return st
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Combination ${attempt.mode} unsupported: ${e.message}")
                lastError = e
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Bind failed for ${attempt.mode}", e)
                lastError = e
            }
        }
        val st = CameraStatus(error = lastError?.message ?: "Camera unavailable")
        _status.value = st
        return st
    }

    @MainThread
    fun unbind() {
        provider?.unbindAll()
        preview = null
        imageCapture = null
        recorder = null
        _status.value = CameraStatus()
    }

    /** Attach (or detach with null) the on-screen preview. Safe to call before [bind]. */
    @MainThread
    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        surfaceProvider = provider
        preview?.setSurfaceProvider(provider)
    }

    val hasImageCapture: Boolean get() = imageCapture != null

    /** Full-resolution still via ImageCapture, upright. Null if ImageCapture isn't bound. */
    suspend fun takePicture(): Bitmap? {
        val capture = imageCapture ?: return null
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = runCatching { image.toUprightBitmap() }.getOrNull()
                    image.close()
                    cont.resume(bmp)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "takePicture failed", exception)
                    cont.resume(null)
                }
            })
        }
    }

    private fun displayRotation(): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_90
    }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun focalNormX(camera: Camera, displayRotation: Int): Float? = runCatching {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        // Upright frames are rotated by 90/270° in portrait, so image width then spans the sensor height.
        val quarterTurn = camera.cameraInfo.getSensorRotationDegrees(displayRotation) % 180 != 0
        val imageWidthMm = if (quarterTurn) sensor?.height else sensor?.width
        if (focal != null && imageWidthMm != null && imageWidthMm > 0f) focal / imageWidthMm else null
    }.getOrNull()

    private companion object {
        const val TAG = "CameraPipeline"
    }
}

/** Converts an RGBA/JPEG ImageProxy to an upright Bitmap (applies rotationDegrees). */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val bmp = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bmp
    val m = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: Exception) {
            cont.resumeWithException(e.cause ?: e)
        }
    }, Runnable::run)
    cont.invokeOnCancellation { cancel(false) }
}
