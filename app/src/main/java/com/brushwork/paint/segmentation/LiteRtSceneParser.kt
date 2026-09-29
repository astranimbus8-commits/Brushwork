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
import kotlin.math.roundToInt

/**
 * Scene parser backed by the bundled Autoseg-EdgeTPU-S model (ADE20K-32, fused argmax) on the
 * LiteRT CPU runtime (4 threads, XNNPACK; no NNAPI). One process-wide interpreter, created lazily
 * and guarded by a lock so one inference runs at a time. Tensor shapes and types are verified at
 * load time; any load failure (missing native library, incompatible model) disables the parser
 * for the process and callers fall back to heuristics.
 */
internal class LiteRtSceneParser private constructor(private val appContext: Context) : SceneParser {
    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var unavailable = false
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var inputBytes: ByteArray? = null
    private var outputIsFloat = true
    private var lut = ByteArray(256)

    override fun parse(content: PixelBuffer, letterbox: Letterbox): ByteArray? {
        if (letterbox.size != SIZE) return null
        synchronized(lock) {
            val interp = obtainLocked() ?: return null
            val input = inputBuffer ?: return null
            val output = outputBuffer ?: return null
            val bytes = inputBytes ?: ByteArray(SIZE * SIZE * 3).also { inputBytes = it }
            return try {
                letterbox.fillInput(content, lut, bytes)
                input.clear()
                input.put(bytes)
                input.rewind()
                output.clear()
                interp.run(input, output)
                output.rewind()
                readClassIds(output)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "scene inference out of memory", e)
                null
            } catch (e: Exception) {
                // Interpreter errors are deterministic for a given model: stop retrying.
                Log.e(TAG, "scene inference failed; using heuristics from now on", e)
                releaseLocked()
                unavailable = true
                null
            }
        }
    }

    /** Loads the model now (e.g. while the user opens the selection panel). */
    fun warmUp() {
        synchronized(lock) { obtainLocked() }
    }

    /** Frees the interpreter and its buffers; the next [parse] recreates them. */
    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun readClassIds(output: ByteBuffer): ByteArray {
        val n = SIZE * SIZE
        val ids = ByteArray(n)
        val max = SceneClasses.COUNT - 1
        if (outputIsFloat) {
            val fb = output.asFloatBuffer()
            for (i in 0 until n) {
                val v = fb.get(i)
                ids[i] = if (v.isNaN()) 0 else v.roundToInt().coerceIn(0, max).toByte()
            }
        } else {
            val ib = output.asIntBuffer()
            for (i in 0 until n) ids[i] = ib.get(i).coerceIn(0, max).toByte()
        }
        return ids
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
            Log.w(TAG, "not enough memory for the scene model", e)
            created?.close()
            return null
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (no native runtime), IOException (asset), IllegalArgumentException (bad model)...
            Log.e(TAG, "scene model unavailable; using heuristics", t)
            try {
                created?.close()
            } catch (_: Throwable) {
            }
            unavailable = true
            return null
        }
    }

    /** Verifies the tensor signature and prepares buffers + the input quantization table. */
    private fun configure(interp: Interpreter): Boolean {
        if (interp.inputTensorCount != 1 || interp.outputTensorCount < 1) {
            Log.e(TAG, "unexpected tensor counts ${interp.inputTensorCount}/${interp.outputTensorCount}")
            return false
        }
        val inT = interp.getInputTensor(0)
        val outT = interp.getOutputTensor(0)
        val inShape = inT.shape()
        val outShape = outT.shape()
        val inType = inT.dataType()
        val outType = outT.dataType()
        val inOk = inShape.contentEquals(intArrayOf(1, SIZE, SIZE, 3)) && (inType == DataType.INT8 || inType == DataType.UINT8)
        val outOk = (outShape.contentEquals(intArrayOf(1, SIZE, SIZE)) || outShape.contentEquals(intArrayOf(1, SIZE, SIZE, 1))) &&
            (outType == DataType.FLOAT32 || outType == DataType.INT32)
        if (!inOk || !outOk) {
            Log.e(TAG, "unexpected model signature: in ${inShape.contentToString()} $inType, out ${outShape.contentToString()} $outType")
            return false
        }
        val q = inT.quantizationParams()
        lut = Letterbox.quantLut(q.scale, q.zeroPoint, signed = inType == DataType.INT8)
        outputIsFloat = outType == DataType.FLOAT32
        inputBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        return true
    }

    private fun releaseLocked() {
        try {
            interpreter?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing interpreter", t)
        }
        interpreter = null
        inputBuffer = null
        outputBuffer = null
        inputBytes = null
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
        const val MODEL_ASSET = "models/autoseg_edgetpu_s.tflite"
        private const val SIZE = Letterbox.MODEL_SIZE
        private const val NUM_THREADS = 4

        @Volatile private var instance: LiteRtSceneParser? = null

        fun get(context: Context): LiteRtSceneParser =
            instance ?: synchronized(this) {
                instance ?: LiteRtSceneParser(context.applicationContext ?: context).also { instance = it }
            }
    }
}
