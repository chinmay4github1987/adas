package com.drivesentinel.events

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Lossless concatenation (remux, no re-encode) of consecutive MP4 segments recorded with the
 * same Recorder configuration, with optional trimming of the first/last part. The first part is
 * cut at the sync frame at or before its requested start so the clip always begins on a
 * keyframe. Video-only (DriveSentinel records no audio).
 */
object ClipConcatenator {

    /** A segment file and an optional sub-range in microseconds relative to the file start. */
    data class Part(val file: File, val startUs: Long? = null, val endUs: Long? = null)

    /** @return total duration written, in microseconds. */
    fun concat(parts: List<Part>, output: File): Long {
        require(parts.isNotEmpty()) { "nothing to concatenate" }
        output.delete()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var refWidth = 0
        var refHeight = 0
        var offsetUs = 0L
        var buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            for (part in parts) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(part.file.absolutePath)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    } ?: continue
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val max = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (max > buffer.capacity()) buffer = ByteBuffer.allocate(max)
                    }
                    if (muxTrack < 0) {
                        muxer.setOrientationHint(rotationOf(part.file))
                        muxTrack = muxer.addTrack(format)
                        muxer.start()
                        refWidth = w
                        refHeight = h
                    } else if (w != refWidth || h != refHeight) {
                        Log.w(TAG, "Skipping ${part.file.name}: ${w}x$h != ${refWidth}x$refHeight")
                        continue
                    }

                    part.startUs?.let { extractor.seekTo(it, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
                    var firstPts = -1L
                    var maxPts = 0L
                    var lastDelta = 33_333L
                    var prevPts = -1L
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val pts = extractor.sampleTime
                        if (part.endUs != null && pts > part.endUs) break
                        if (firstPts < 0) {
                            // Must start on a keyframe.
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) {
                                extractor.advance()
                                continue
                            }
                            firstPts = pts
                        }
                        val rel = pts - firstPts
                        info.set(
                            0, size, offsetUs + rel.coerceAtLeast(0),
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                        )
                        muxer.writeSampleData(muxTrack, buffer, info)
                        if (prevPts >= 0 && pts > prevPts) lastDelta = pts - prevPts
                        prevPts = pts
                        if (rel > maxPts) maxPts = rel
                        extractor.advance()
                    }
                    if (firstPts >= 0) offsetUs += maxPts + lastDelta
                } finally {
                    extractor.release()
                }
            }
        } finally {
            runCatching { if (muxTrack >= 0) muxer.stop() }
            muxer.release()
        }
        return offsetUs
    }

    private fun rotationOf(file: File): Int = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            r.release()
        }
    }.getOrDefault(0)

    private const val TAG = "ClipConcatenator"
}
