package com.brushwork.paint.segmentation

import android.content.Context
import android.util.Log
import com.brushwork.paint.core.PixelBuffer
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * The bundled MediaPipe MagicTouch v1 interactive segmenter (Apache-2.0) on the LiteRT CPU
 * runtime, without the MediaPipe framework. Verified from the model file: one float32 input
 * [1, 512, 512, 4] = R, G, B, prior, every channel normalized as v / 255 (TFLite metadata: mean
 * 0, std 255), the prior = 1 where the user tapped / scribbled; one float32 output
 * [1, 512, 512, 1] that already went through a LOGISTIC op (object probability). All operators
 * are builtin (fp16 weights are DEQUANTIZEd), so no custom op resolver is needed.
 *
 * One process-wide interpreter, created lazily, released on memory pressure. A load failure
 * disables it for the process (the object select tool then grows a color region instead).
 */
internal class LiteRtMagicTouch private constructor(private val appContext: Context) : InteractiveModel {
    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var unavailable = false
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var inputFloats: FloatArray? = null
    private var outChannels = 1

    /** False once loading failed (no native runtime, missing or incompatible model). */
    val mayBeAvailable: Boolean get() = !unavailable

    override fun run(rgb: PixelBuffer, prior: FloatArray): FloatArray? {
        if (rgb.width != SIZE || rgb.height != SIZE || prior.size != SIZE * SIZE) return null
        synchronized(lock) {
            val interp = obtainLocked() ?: return null
            return try {
                infer(interp, rgb, prior).also { failures = 0 }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "object model out of memory", e)
                releaseLocked()
                null
            } catch (e: Exception) {
                // A native allocation failure under memory pressure also lands here: the first
                // failure only recreates the interpreter; a second one in a row disables it.
                releaseLocked()
                failures++
                if (failures >= MAX_FAILURES) {
                    Log.w(TAG, "object model inference failed again; using color selection from now on", e)
                    unavailable = true
                } else {
                    Log.w(TAG, "object model inference failed; the interpreter will be recreated", e)
                }
                null
            }
        }
    }

    /** Inference failures in a row (reset by a success). */
    private var failures = 0

    /** Loads the model now (e.g. when the object select tool is picked). */
    fun warmUp() {
        synchronized(lock) { obtainLocked() }
    }

    /** Frees the interpreter and its buffers; the next [run] recreates them. */
    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun infer(interp: Interpreter, rgb: PixelBuffer, prior: FloatArray): FloatArray {
        val n = SIZE * SIZE
        val f = inputFloats ?: FloatArray(n * 4).also { inputFloats = it }
        val px = rgb.pixels
        for (i in 0 until n) {
            val c = MaskOps.flattenOverWhite(px[i])
            f[i * 4] = ((c shr 16) and 0xFF) / 255f
            f[i * 4 + 1] = ((c shr 8) and 0xFF) / 255f
            f[i * 4 + 2] = (c and 0xFF) / 255f
            f[i * 4 + 3] = MaskOps.clamp01(prior[i])
        }
        val input = inputBuffer!!
        val output = outputBuffer!!
        input.clear()
        input.asFloatBuffer().put(f)
        input.rewind()
        output.clear()
        interp.run(input, output)
        output.rewind()
        val fb = output.asFloatBuffer()
        val out = FloatArray(n)
        if (outChannels == 1) {
            fb.get(out)
        } else {
            // Two channels (background, object) of logits: softmax = sigmoid of the difference.
            for (i in 0 until n) out[i] = 1f / (1f + exp(fb.get(i * 2) - fb.get(i * 2 + 1)))
        }
        for (i in 0 until n) out[i] = MaskOps.clamp01(out[i])
        return out
    }

    private fun obtainLocked(): Interpreter? {
        interpreter?.let { return it }
        if (unavailable) return null
        var created: Interpreter? = null
        try {
            created = Interpreter(mapModel(), Interpreter.Options().setNumThreads(NUM_THREADS))
            if (!configure(created)) {
                created.close()
                unavailable = true
                return null
            }
            interpreter = created
            return created
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "not enough memory for the object model", e)
            closeQuietly(created)
            return null
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (no native runtime), IOException (asset), IllegalArgumentException (bad model)...
            Log.w(TAG, "object model unavailable; using color selection", t)
            closeQuietly(created)
            unavailable = true
            return null
        }
    }

    private fun closeQuietly(i: Interpreter?) {
        try {
            i?.close()
        } catch (_: Throwable) {
        }
    }

    private fun configure(interp: Interpreter): Boolean {
        if (interp.inputTensorCount != 1 || interp.outputTensorCount < 1) {
            Log.w(TAG, "object model: unexpected tensor counts ${interp.inputTensorCount}/${interp.outputTensorCount}")
            return false
        }
        val inT = interp.getInputTensor(0)
        val outT = interp.getOutputTensor(0)
        val inShape = inT.shape(); val outShape = outT.shape()
        val inOk = inShape.contentEquals(intArrayOf(1, SIZE, SIZE, 4)) && inT.dataType() == DataType.FLOAT32
        val outOk = outShape.size == 4 && outShape[0] == 1 && outShape[1] == SIZE && outShape[2] == SIZE &&
            (outShape[3] == 1 || outShape[3] == 2) && outT.dataType() == DataType.FLOAT32
        if (!inOk || !outOk) {
            Log.w(TAG, "object model: unexpected signature in ${inShape.contentToString()} ${inT.dataType()}, out ${outShape.contentToString()} ${outT.dataType()}")
            return false
        }
        outChannels = outShape[3]
        inputBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4 * 4).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * outChannels * 4).order(ByteOrder.nativeOrder())
        return true
    }

    private fun releaseLocked() {
        try {
            interpreter?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing the object model", t)
        }
        interpreter = null
        inputBuffer = null
        outputBuffer = null
        inputFloats = null
    }

    /** Memory-maps the uncompressed asset (noCompress "tflite") straight from the APK. */
    private fun mapModel(): MappedByteBuffer =
        appContext.assets.openFd(MODEL_ASSET).use { afd ->
            afd.createInputStream().use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }

    companion object {
        private const val TAG = "Segmentation"
        const val MODEL_ASSET = "models/magic_touch.tflite"
        private const val SIZE = InteractiveSegmenter.MODEL_SIZE
        private const val NUM_THREADS = 4
        private const val MAX_FAILURES = 2

        @Volatile private var instance: LiteRtMagicTouch? = null

        fun get(context: Context): LiteRtMagicTouch =
            instance ?: synchronized(this) {
                instance ?: LiteRtMagicTouch(context.applicationContext ?: context).also { instance = it }
            }
    }
}
