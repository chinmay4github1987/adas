package com.drivesentinel.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Dashcam-style loop recording with CameraX: fixed-length segments (default 10 s) written to
 * the app cache dir; only the last [keepFinalized] finalized segments are kept, except those
 * protected by a [lock] (an event window). This is the pre-event buffer.
 *
 * A CameraX Recorder supports one active Recording at a time, so consecutive segments have a
 * small stop/start gap (typically a few hundred ms) — acceptable for evidence clips; a
 * gapless alternative is a MediaCodec circular encoder (Grafika pattern, see README).
 *
 * All public methods must be called from the main thread (the scope passed to [start]).
 */
class SegmentRecorder(
    private val context: Context,
    private val segmentMs: Long = 10_000,
    private val keepFinalized: Int = 3,
) {
    class Segment(val file: File, @Volatile var startMs: Long) {
        @Volatile var endMs: Long = -1L
        val finalized = CompletableDeferred<Boolean>()
        val isFinalized: Boolean get() = finalized.isCompleted
        fun overlaps(from: Long, to: Long): Boolean = startMs < to && (endMs < 0 || endMs > from)
    }

    private val dir = File(context.cacheDir, "segments").apply { mkdirs() }
    private val executor = ContextCompat.getMainExecutor(context)
    private val segments = mutableListOf<Segment>() // main thread only
    private val locks = mutableMapOf<Int, LongRange>()
    private var nextLockId = 1
    private var loop: Job? = null
    private var active: Recording? = null
    private val rotate = Channel<Unit>(Channel.CONFLATED)

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    init {
        // Stale segments from a previous process are useless (no timestamps): clear them.
        dir.listFiles()?.forEach { it.delete() }
    }

    fun start(recorder: Recorder, scope: CoroutineScope) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            try {
                while (isActive) {
                    val seg = begin(recorder) ?: run {
                        // The previous Recording may still be finalizing: wait for it, retry once.
                        segments.lastOrNull()?.let { withTimeoutOrNull(2_000) { it.finalized.await() } }
                        begin(recorder)
                    }
                    if (seg == null) {
                        delay(2_000) // recorder busy/error: retry
                        continue
                    }
                    _recording.value = true
                    withTimeoutOrNull(segmentMs) { rotate.receive() }
                    end()
                }
            } finally {
                end()
                _recording.value = false
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        end()
        _recording.value = false
    }

    /** Close the current segment now (so it finalizes) and immediately start the next one. */
    fun rotateNow() {
        rotate.trySend(Unit)
    }

    /** Protects segments overlapping [range] (elapsedRealtime ms) from pruning. */
    fun lock(range: LongRange): Int {
        val id = nextLockId++
        locks[id] = range
        return id
    }

    fun unlock(id: Int) {
        locks.remove(id)
        prune()
    }

    /**
     * Waits until every segment overlapping [fromMs]..[toMs] is finalized and returns them in
     * time order (only those whose file exists).
     */
    suspend fun segmentsCovering(fromMs: Long, toMs: Long, timeoutMs: Long = 8_000): List<Segment> {
        val wanted = segments.filter { it.overlaps(fromMs, toMs) }
        withTimeoutOrNull(timeoutMs) { wanted.forEach { it.finalized.await() } }
        return wanted
            .filter { it.isFinalized && it.file.exists() && it.file.length() > 1024 }
            .sortedBy { it.startMs }
    }

    @SuppressLint("MissingPermission") // no audio is recorded
    private fun begin(recorder: Recorder): Segment? {
        val file = File(dir, "seg_${System.currentTimeMillis()}.mp4")
        val seg = Segment(file, SystemClock.elapsedRealtime())
        val options = FileOutputOptions.Builder(file).build()
        return try {
            active = recorder.prepareRecording(context, options).start(executor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> seg.startMs = SystemClock.elapsedRealtime()
                    is VideoRecordEvent.Finalize -> {
                        val durMs = event.recordingStats.recordedDurationNanos / 1_000_000
                        seg.endMs = seg.startMs + durMs
                        val ok = !event.hasError() || event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
                        if (!ok) file.delete()
                        seg.finalized.complete(ok)
                        prune()
                    }
                    else -> Unit
                }
            }
            segments += seg
            seg
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not start segment", e)
            null
        }
    }

    private fun end() {
        active?.stop()
        active = null
    }

    private fun prune() {
        segments.removeAll { it.isFinalized && !it.file.exists() }
        val finalized = segments.filter { it.isFinalized }.sortedBy { it.startMs }
        val excess = finalized.size - keepFinalized
        if (excess <= 0) return
        finalized.take(excess).forEach { seg ->
            val locked = locks.values.any { seg.overlaps(it.first, it.last) }
            if (!locked) {
                seg.file.delete()
                segments.remove(seg)
            }
        }
    }

    private companion object {
        const val TAG = "SegmentRecorder"
    }
}
