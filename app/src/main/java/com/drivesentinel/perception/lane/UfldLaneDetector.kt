package com.drivesentinel.perception.lane

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * **Stub** for an Ultra-Fast-Lane-Detection v2 (MIT) TFLite model, e.g. the CULane ResNet18
 * row-anchor model converted ONNX → TFLite (PINTO model zoo / onnx2tf), saved as
 * `assets/ufld_v2.tflite`.
 *
 * What is implemented: model loading, ImageNet-normalized NHWC preprocessing (nearest-neighbour
 * resize from the ARGB frame), and inference. What is NOT implemented: decoding the row-anchor
 * outputs (`loc_row [1, gridCells, rowAnchors, 4]`, `exist_row [1, 2, rowAnchors, 4]`, plus the
 * column-anchor pair) into lane polylines — see `TODO(ufld-decode)`. Until then [isAvailable] is
 * false and [LaneDetectorFactory] uses [ClassicalLaneDetector].
 */
class UfldLaneDetector private constructor(private val interpreter: Interpreter) : LaneDetector {

    override val name: String = "UFLD v2 (TFLite, stub)"

    /** Flip to true once TODO(ufld-decode) is done. */
    override val isAvailable: Boolean = false

    private val inShape = interpreter.getInputTensor(0).shape() // [1, H, W, 3] e.g. 320×1600
    private val inH = inShape[1]
    private val inW = inShape[2]
    private val input = ByteBuffer.allocateDirect(inH * inW * 3 * 4).order(ByteOrder.nativeOrder())
    private val outputs: Map<Int, ByteBuffer> = (0 until interpreter.outputTensorCount).associateWith { i ->
        val t = interpreter.getOutputTensor(i)
        ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder())
    }

    override fun detect(frame: ArgbFrame, timestampMs: Long): LaneEstimate {
        input.rewind()
        // UFLD crops the top of the image (CULane: crop_ratio 0.6 of a 320-row input → uses the
        // lower part). Here: simple full-frame resize; adjust to match your export.
        for (y in 0 until inH) {
            val sy = y * frame.height / inH
            for (x in 0 until inW) {
                val p = frame.pixels[sy * frame.width + x * frame.width / inW]
                input.putFloat(((p shr 16 and 0xFF) / 255f - 0.485f) / 0.229f)
                input.putFloat(((p shr 8 and 0xFF) / 255f - 0.456f) / 0.224f)
                input.putFloat(((p and 0xFF) / 255f - 0.406f) / 0.225f)
            }
        }
        input.rewind()
        outputs.values.forEach { it.rewind() }
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        // TODO(ufld-decode): for each of the 4 lanes and each row anchor, take softmax-expectation
        //  over grid cells of loc_row where argmax(exist_row) == 1, map cell → x, row anchor → y,
        //  then fit a line/poly to the two ego lanes (indices 1 and 2) and return LaneLine(s).
        return LaneEstimate.NONE
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET = "ufld_v2.tflite"

        fun createOrNull(context: Context): UfldLaneDetector? = runCatching {
            val buffer = context.assets.openFd(MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).use {
                    it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            UfldLaneDetector(Interpreter(buffer, Interpreter.Options().setNumThreads(2)))
        }.onFailure { Log.i("UfldLaneDetector", "No UFLD model (${it.message}); using classical") }.getOrNull()
    }
}

/** Chooses the best available lane detector. */
object LaneDetectorFactory {
    fun create(context: Context): LaneDetector {
        val ufld = UfldLaneDetector.createOrNull(context)
        if (ufld != null && ufld.isAvailable) return ufld
        ufld?.close()
        return ClassicalLaneDetector()
    }
}
