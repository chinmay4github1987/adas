package com.drivesentinel.perception.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YOLO-family detector on LiteRT (TFLite runtime, `org.tensorflow.lite.*` API from the
 * `com.google.ai.edge.litert` 1.x artifacts).
 *
 * * GPU delegate when the device is on the GPU compatibility list, CPU/XNNPACK fallback
 *   (also used if delegate creation or model preparation fails).
 * * Input: NHWC (or NCHW) float32 0..1, or uint8/int8 quantized; letterboxed with pad 114.
 * * Output: decoded by [YoloOutputDecoder] — `[1,84,8400]` (YOLOv8/11, NMS in Kotlin) or
 *   `[1,300,6]` (YOLO26/v10 end-to-end, NMS-free).
 *
 * Not thread-safe: create, use and close on the same (analysis) thread — a GPU delegate is
 * bound to the thread that created it.
 *
 * Why not the LiteRT 2.x `CompiledModel` API? See README "Inference runtime choice" and
 * `extras/CompiledModelDetector.kt.txt` for a drop-in sketch.
 */
class YoloDetector private constructor(
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    private val decoder: YoloOutputDecoder,
    val accelerator: String,
) : AutoCloseable {

    private val inputTensor = interpreter.getInputTensor(0)
    private val inputShape = inputTensor.shape() // [1,H,W,3] or [1,3,H,W]
    private val channelsFirst = inputShape.size == 4 && inputShape[1] == 3
    val inputHeight = if (channelsFirst) inputShape[2] else inputShape[1]
    val inputWidth = if (channelsFirst) inputShape[3] else inputShape[2]
    private val inputType = inputTensor.dataType()
    private val inputScale = inputTensor.quantizationParams().scale
    private val inputZero = inputTensor.quantizationParams().zeroPoint

    private val outputTensor = interpreter.getOutputTensor(0)
    val outputShape: IntArray = outputTensor.shape()
    private val outputType = outputTensor.dataType()
    private val outputScale = outputTensor.quantizationParams().scale
    private val outputZero = outputTensor.quantizationParams().zeroPoint

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(inputWidth * inputHeight * 3 * bytesPer(inputType))
        .order(ByteOrder.nativeOrder())
    private val outputCount = outputShape.fold(1) { a, b -> a * b }
    private val outputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(outputCount * bytesPer(outputType))
        .order(ByteOrder.nativeOrder())
    private val outputFloats = FloatArray(outputCount)

    private val letterboxBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(inputWidth * inputHeight)

    /** Wall time of the last [detect] call (preprocess + inference + decode), ms. */
    var lastLatencyMs: Long = 0
        private set

    val layout: YoloLayout get() = decoder.resolveLayout(outputShape)

    fun detect(frame: Bitmap): List<Detection> {
        val t0 = SystemClock.elapsedRealtime()
        val lb = Letterbox(frame.width, frame.height, inputWidth, inputHeight)
        letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
        letterboxCanvas.drawBitmap(
            frame, null,
            RectF(lb.padX, lb.padY, lb.padX + lb.scaledWidth, lb.padY + lb.scaledHeight),
            paint,
        )
        letterboxBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        fillInput()

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        readOutput()

        val result = decoder.decode(outputFloats, outputShape, lb)
        lastLatencyMs = SystemClock.elapsedRealtime() - t0
        return result
    }

    private fun fillInput() {
        inputBuffer.rewind()
        val plane = inputWidth * inputHeight
        when (inputType) {
            DataType.FLOAT32 -> {
                if (channelsFirst) {
                    val fb = inputBuffer.asFloatBuffer()
                    for (i in 0 until plane) fb.put(i, (pixels[i] shr 16 and 0xFF) / 255f)
                    for (i in 0 until plane) fb.put(plane + i, (pixels[i] shr 8 and 0xFF) / 255f)
                    for (i in 0 until plane) fb.put(2 * plane + i, (pixels[i] and 0xFF) / 255f)
                } else {
                    for (p in pixels) {
                        inputBuffer.putFloat((p shr 16 and 0xFF) / 255f)
                        inputBuffer.putFloat((p shr 8 and 0xFF) / 255f)
                        inputBuffer.putFloat((p and 0xFF) / 255f)
                    }
                }
            }
            DataType.UINT8, DataType.INT8 -> {
                // Quantize the 0..1 normalized value with the tensor's scale/zero-point.
                val scale = if (inputScale == 0f) 1f / 255f else inputScale
                val lo = if (inputType == DataType.INT8) -128 else 0
                val hi = if (inputType == DataType.INT8) 127 else 255
                fun q(v: Int): Byte = ((v / 255f) / scale + inputZero).toInt().coerceIn(lo, hi).toByte()
                if (channelsFirst) {
                    for (c in 0 until 3) {
                        val shift = 16 - 8 * c
                        for (i in 0 until plane) inputBuffer.put(q(pixels[i] shr shift and 0xFF))
                    }
                } else {
                    for (p in pixels) {
                        inputBuffer.put(q(p shr 16 and 0xFF))
                        inputBuffer.put(q(p shr 8 and 0xFF))
                        inputBuffer.put(q(p and 0xFF))
                    }
                }
            }
            else -> error("Unsupported input type $inputType")
        }
        inputBuffer.rewind()
    }

    private fun readOutput() {
        outputBuffer.rewind()
        when (outputType) {
            DataType.FLOAT32 -> outputBuffer.asFloatBuffer().get(outputFloats)
            DataType.UINT8 -> for (i in 0 until outputCount) {
                outputFloats[i] = ((outputBuffer.get(i).toInt() and 0xFF) - outputZero) * outputScale
            }
            DataType.INT8 -> for (i in 0 until outputCount) {
                outputFloats[i] = (outputBuffer.get(i).toInt() - outputZero) * outputScale
            }
            else -> error("Unsupported output type $outputType")
        }
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
        if (!letterboxBitmap.isRecycled) letterboxBitmap.recycle()
    }

    companion object {
        const val MODEL_ASSET = "yolo_detector.tflite"
        const val LABELS_ASSET = "coco_labels.txt"
        private const val TAG = "YoloDetector"

        fun modelExists(context: Context, asset: String = MODEL_ASSET): Boolean =
            runCatching { context.assets.openFd(asset).close() }.isSuccess

        /**
         * Loads the model, trying GPU first. Throws if the asset is missing or unreadable.
         * Call on the thread that will run inference.
         */
        fun create(
            context: Context,
            modelAsset: String = MODEL_ASSET,
            labelsAsset: String = LABELS_ASSET,
            preferGpu: Boolean = true,
            confThreshold: Float = 0.35f,
        ): YoloDetector {
            val model = loadMapped(context, modelAsset)
            val labels = context.assets.open(labelsAsset).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
            val decoder = YoloOutputDecoder(labels, confThreshold = confThreshold)

            if (preferGpu) {
                var delegate: GpuDelegate? = null
                try {
                    val compat = CompatibilityList()
                    val supported = compat.isDelegateSupportedOnThisDevice
                    if (supported) {
                        delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                        val options = Interpreter.Options().addDelegate(delegate)
                        val interpreter = Interpreter(model, options)
                        compat.close()
                        return YoloDetector(interpreter, delegate, decoder, "GPU")
                    }
                    compat.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU delegate unavailable, falling back to CPU", t)
                    delegate?.close()
                }
            }
            val options = Interpreter.Options()
                .setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                .setUseXNNPACK(true)
            return YoloDetector(Interpreter(model, options), null, decoder, "CPU")
        }

        private fun loadMapped(context: Context, asset: String): MappedByteBuffer =
            context.assets.openFd(asset).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }

        private fun bytesPer(type: DataType): Int = when (type) {
            DataType.FLOAT32, DataType.INT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            DataType.INT64 -> 8
            else -> 4
        }
    }
}
