package com.drivesentinel.events

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.drivesentinel.BuildConfig
import com.drivesentinel.camera.CameraPipeline
import com.drivesentinel.camera.SegmentRecorder
import com.drivesentinel.data.events.DriveEvent
import com.drivesentinel.data.events.EventRepository
import com.drivesentinel.data.events.EventTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data class Capturing(val trigger: EventTrigger, val postRollEndsAtMs: Long) : CaptureStatus
    data class Saved(val event: DriveEvent) : CaptureStatus
    data class Failed(val reason: String) : CaptureStatus
}

/** Facts about the moment of the event, recorded in the JSON sidecar. */
data class EventContext(
    val speedKmh: Float? = null,
    val location: Location? = null,
    val peakDecelG: Float? = null,
    val speedDropKmh: Float? = null,
    val ttcSeconds: Float? = null,
    val leadLabel: String? = null,
)

/**
 * On an event: burst of 3 snapshots (ImageCapture, or the latest AI frame as fallback),
 * lock the ±10 s window in the segment ring, wait for the post-roll, rotate the current
 * segment so it finalizes, remux the overlapping segments into one trimmed MP4, publish
 * everything to MediaStore with a JSON sidecar, and enforce the storage quota.
 *
 * Runs on the session's main-thread scope (SegmentRecorder is main-thread confined);
 * file work hops to IO.
 */
class EventCaptureManager(
    private val context: Context,
    private val camera: CameraPipeline,
    private val segments: SegmentRecorder,
    private val latestFrame: () -> Bitmap?,
    private val media: MediaStoreWriter,
    private val repository: EventRepository,
    private val preRollMs: Long = 10_000,
    private val postRollMs: Long = 10_000,
) {
    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private var job: Job? = null
    val isBusy: Boolean get() = job?.isActive == true

    /** @return false if a capture is already in progress (its window already covers this one). */
    fun trigger(
        scope: CoroutineScope,
        trigger: EventTrigger,
        ctx: EventContext,
        quotaBytes: Long,
    ): Boolean {
        if (isBusy) return false
        job = scope.launch { capture(trigger, ctx, quotaBytes) }
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
        _status.value = CaptureStatus.Idle
    }

    private suspend fun capture(trigger: EventTrigger, ctx: EventContext, quotaBytes: Long) {
        val eventMs = SystemClock.elapsedRealtime()
        val wallMs = System.currentTimeMillis()
        val id = "DS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(wallMs)) + "_" + trigger.name
        val from = eventMs - preRollMs
        val to = eventMs + postRollMs
        _status.value = CaptureStatus.Capturing(trigger, to)
        val lockId = segments.lock(from..to)
        try {
            // 1. Snapshot burst.
            val photos = mutableListOf<Uri>()
            var thumbSource: Bitmap? = null
            repeat(BURST) { i ->
                val bmp = camera.takePicture() ?: latestFrame()
                if (bmp != null) {
                    media.saveJpeg(bmp, "${id}_$i.jpg", wallMs)?.let(photos::add)
                    if (thumbSource == null) thumbSource = bmp
                }
                if (i < BURST - 1) delay(BURST_INTERVAL_MS)
            }
            val thumbPath = thumbSource?.let { writeThumbnail(it, repository.thumbnailFile(id)) }

            // 2. Clip from the segment ring (if video is running).
            var videoUri: Uri? = null
            if (segments.recording.value) {
                delay((to - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                segments.rotateNow()
                val covering = segments.segmentsCovering(from, to)
                if (covering.isNotEmpty()) {
                    val tmp = File(context.cacheDir, "$id.mp4")
                    withContext(Dispatchers.IO) {
                        ClipConcatenator.concat(
                            covering.map { seg ->
                                ClipConcatenator.Part(
                                    file = seg.file,
                                    startUs = if (from > seg.startMs) (from - seg.startMs) * 1_000 else null,
                                    endUs = if (seg.endMs in 0 until to) null else (to - seg.startMs) * 1_000,
                                )
                            },
                            tmp,
                        )
                    }
                    videoUri = media.saveVideo(tmp, "$id.mp4", wallMs)
                    tmp.delete()
                }
            }

            // 3. Metadata sidecar + index.
            val sizeBytes = withContext(Dispatchers.IO) {
                (listOfNotNull(videoUri) + photos).sumOf { media.sizeOf(it.toString()) }
            }
            var event = DriveEvent(
                id = id,
                trigger = trigger,
                wallTimeMs = wallMs,
                speedKmh = ctx.speedKmh,
                latitude = ctx.location?.latitude,
                longitude = ctx.location?.longitude,
                locationAccuracyM = ctx.location?.takeIf { it.hasAccuracy() }?.accuracy,
                headingDeg = ctx.location?.takeIf { it.hasBearing() }?.bearing,
                peakDecelG = ctx.peakDecelG,
                speedDropKmh = ctx.speedDropKmh,
                ttcSeconds = ctx.ttcSeconds,
                leadLabel = ctx.leadLabel,
                preRollMs = preRollMs,
                postRollMs = postRollMs,
                videoUri = videoUri?.toString(),
                photoUris = photos.map { it.toString() },
                sidecarUri = null,
                thumbnailPath = thumbPath,
                sizeBytes = sizeBytes,
                appVersion = BuildConfig.VERSION_NAME,
            )
            val sidecar = media.saveJson(event.toJson().toString(2), "$id.json", wallMs)
            event = event.copy(sidecarUri = sidecar?.toString())
            repository.add(event)
            val removed = repository.enforceQuota(quotaBytes)
            if (removed > 0) Log.i(TAG, "Quota: auto-deleted $removed oldest event(s)")

            _status.value = CaptureStatus.Saved(event)
            delay(5_000)
            if (_status.value is CaptureStatus.Saved) _status.value = CaptureStatus.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Event capture failed", t)
            _status.value = CaptureStatus.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            segments.unlock(lockId)
        }
    }

    private suspend fun writeThumbnail(src: Bitmap, file: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            val w = 320
            val h = (src.height * w.toFloat() / src.width).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(src, w, h, true)
            file.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            file.absolutePath
        }.getOrNull()
    }

    private companion object {
        const val TAG = "EventCapture"
        const val BURST = 3
        const val BURST_INTERVAL_MS = 350L
    }
}
