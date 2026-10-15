package com.drivesentinel.perception.signs

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device OCR (ML Kit, bundled Latin model — works offline) for speed-limit sign crops.
 * One request in flight at a time and at most every [MIN_INTERVAL_MS], so it never stalls the
 * analysis thread; parsed limits are delivered via [onRead] on ML Kit's callback thread.
 */
class SpeedSignReader(private val onRead: (Int) -> Unit) : AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    @Volatile private var lastSubmitMs = Long.MIN_VALUE / 2
    private val direct = Executor { it.run() }

    fun canSubmit(nowMs: Long): Boolean = !busy.get() && nowMs - lastSubmitMs >= MIN_INTERVAL_MS

    /** Takes ownership of [crop] (recycled when done). */
    fun submit(crop: Bitmap, nowMs: Long) {
        if (!busy.compareAndSet(false, true)) {
            crop.recycle()
            return
        }
        lastSubmitMs = nowMs
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener(direct) { text -> SpeedLimitParser.parse(text.text)?.let(onRead) }
            .addOnFailureListener(direct) { e -> Log.w(TAG, "OCR failed", e) }
            .addOnCompleteListener(direct) {
                crop.recycle()
                busy.set(false)
            }
    }

    override fun close() = recognizer.close()

    private companion object {
        const val TAG = "SpeedSignReader"
        const val MIN_INTERVAL_MS = 350L
    }
}
