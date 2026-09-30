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
 * Scene parser backed by the bundled Autoseg-EdgeTPU-S model (ADE20K-32) on the LiteRT CPU
 * runtime (XNNPACK; no NNAPI). One process-wide interpreter, created lazily and guarded by a
 * lock so one inference runs at a time.
 *
 * The bundled file is the "fused argmax" export (class ids only). At load time a copy is
 * rewired in memory to output the per-class LOGITS instead ([TfliteLogitsPatch]: graph output =
 * the [1, 64, 64, 32] tensor before the finalizer, finalizer operators dropped), checked with a
 * self-test inference against the untouched model ([selfTest]), and used for soft
 * probabilities. The asset itself is never modified. Any failure falls back, in order, to the
 * rewired model without dropping operators, then to the original argmax model (class ids,
 * turned into soft block fractions by the pipeline), then to the heuristics.
 */
internal class LiteRtSceneParser private constructor(private val appContext: Context) : SceneParser {
    private enum class Variant { LOGITS_TRUNCATED, LOGITS, ARGMAX }

    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var variant: Variant? = null

    /** The patched model bytes: must stay reachable while the interpreter uses them. */
    private var modelBuffer: ByteBuffer? = null
    private var unavailable = false
    private val broken = HashSet<Variant>()
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var inputBytes: ByteArray? = null
    private var lut = ByteArray(256)
    private var outType = DataType.FLOAT32
    private var outScale = 1f
    private var outZero = 0
    private var gridW = 0
    private var gridH = 0

    override fun run(input: PixelBuffer): SceneScores? {
        if (input.width != SIZE || input.height != SIZE) return null
        synchronized(lock) {
            repeat(3) {
                val interp = obtainLocked() ?: return null
                val v = variant ?: return null
                try {
                    return infer(interp, v, input)
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "scene inference out of memory", e)
                    return null
                } catch (e: Exception) {
                    // Interpreter errors are deterministic for a given model: drop this variant.
                    releaseLocked()
                    broken += v
                    if (v == Variant.ARGMAX) {
                        Log.e(TAG, "scene inference failed; using heuristics from now on", e)
                        unavailable = true
                        return null
                    }
                    Log.w(TAG, "scene inference failed with the $v model; trying the next one", e)
                }
            }
            return null
        }
    }

    /** Loads the model now (e.g. while the user opens the selection panel). */
    fun warmUp() {
        synchronized(lock) { obtainLocked() }
    }

    /** Frees the interpreter and its buffers; the next [run] recreates them. */
    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun infer(interp: Interpreter, v: Variant, img: PixelBuffer): SceneScores {
        val input = inputBuffer!!
        val output = outputBuffer!!
        val bytes = inputBytes ?: ByteArray(SIZE * SIZE * 3).also { inputBytes = it }
        encode(img, lut, bytes)
        input.clear()
        input.put(bytes)
        input.rewind()
        output.clear()
        interp.run(input, output)
        output.rewind()
        return if (v == Variant.ARGMAX) SceneScores.Labels(SIZE, readClassIds(output, outType)) else SceneScores.Logits(gridW, gridH, readLogits(output))
    }

    /** The NHWC model input of [img] (SIZE², flattened over white), quantized through [lut]. */
    private fun encode(img: PixelBuffer, lut: ByteArray, bytes: ByteArray) {
        val px = img.pixels
        for (i in 0 until SIZE * SIZE) {
            val c = MaskOps.flattenOverWhite(px[i])
            bytes[i * 3] = lut[(c shr 16) and 0xFF]
            bytes[i * 3 + 1] = lut[(c shr 8) and 0xFF]
            bytes[i * 3 + 2] = lut[c and 0xFF]
        }
    }

    private fun readLogits(output: ByteBuffer): FloatArray {
        val n = gridW * gridH * SceneClasses.COUNT
        val out = FloatArray(n)
        when (outType) {
            DataType.FLOAT32 -> output.asFloatBuffer().get(out)
            DataType.INT8 -> for (i in 0 until n) out[i] = (output.get(i) - outZero) * outScale
            else -> for (i in 0 until n) out[i] = ((output.get(i).toInt() and 0xFF) - outZero) * outScale
        }
        return out
    }

    private fun readClassIds(output: ByteBuffer, type: DataType): ByteArray {
        val n = SIZE * SIZE
        val ids = ByteArray(n)
        val max = SceneClasses.COUNT - 1
        if (type == DataType.FLOAT32) {
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
        for (v in Variant.entries) {
            if (v in broken) continue
            var created: Interpreter? = null
            try {
                created = create(v)
                if (!configure(created, v) || !selfTest(created, v)) {
                    created.close()
                    releaseLocked()
                    broken += v
                    if (v == Variant.ARGMAX) break
                    continue
                }
                interpreter = created
                variant = v
                Log.i(TAG, "scene model ready ($v)")
                return created
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "not enough memory for the scene model", e)
                closeQuietly(created)
                releaseLocked()
                return null
            } catch (t: Throwable) {
                // UnsatisfiedLinkError (no native runtime), IOException (asset), IllegalArgumentException (bad model)...
                closeQuietly(created)
                releaseLocked()
                broken += v
                if (v == Variant.ARGMAX) {
                    Log.e(TAG, "scene model unavailable; using heuristics", t)
                    break
                }
                Log.w(TAG, "scene model variant $v unavailable", t)
            }
        }
        unavailable = true
        return null
    }

    private fun closeQuietly(i: Interpreter?) {
        try {
            i?.close()
        } catch (_: Throwable) {
        }
    }

    private fun create(v: Variant): Interpreter {
        val options = Interpreter.Options().setNumThreads(NUM_THREADS)
        if (v == Variant.ARGMAX) return Interpreter(mapModel(), options)
        val buf = readModel()
        TfliteLogitsPatch.patchInPlace(buf, SceneClasses.COUNT, truncate = v == Variant.LOGITS_TRUNCATED)
            ?: throw IllegalArgumentException("the scene model does not have the expected logits tensor")
        modelBuffer = buf
        return Interpreter(buf, options)
    }

    /** Verifies the tensor signature and prepares buffers + the input quantization table. */
    private fun configure(interp: Interpreter, v: Variant): Boolean {
        if (interp.inputTensorCount != 1 || interp.outputTensorCount < 1) {
            Log.w(TAG, "unexpected tensor counts ${interp.inputTensorCount}/${interp.outputTensorCount} ($v)")
            return false
        }
        val inT = interp.getInputTensor(0)
        val outT = interp.getOutputTensor(0)
        val inShape = inT.shape()
        val outShape = outT.shape()
        val inType = inT.dataType()
        val oType = outT.dataType()
        val inOk = inShape.contentEquals(intArrayOf(1, SIZE, SIZE, 3)) && (inType == DataType.INT8 || inType == DataType.UINT8)
        val outOk = if (v == Variant.ARGMAX) {
            (outShape.contentEquals(intArrayOf(1, SIZE, SIZE)) || outShape.contentEquals(intArrayOf(1, SIZE, SIZE, 1))) &&
                (oType == DataType.FLOAT32 || oType == DataType.INT32)
        } else {
            outShape.size == 4 && outShape[0] == 1 && outShape[1] == outShape[2] && outShape[1] in 16..256 &&
                outShape[3] == SceneClasses.COUNT && (oType == DataType.INT8 || oType == DataType.UINT8 || oType == DataType.FLOAT32)
        }
        if (!inOk || !outOk) {
            Log.w(TAG, "unexpected model signature ($v): in ${inShape.contentToString()} $inType, out ${outShape.contentToString()} $oType")
            return false
        }
        val q = inT.quantizationParams()
        lut = Letterbox.quantLut(q.scale, q.zeroPoint, signed = inType == DataType.INT8)
        outType = oType
        val oq = outT.quantizationParams()
        outScale = oq.scale
        outZero = oq.zeroPoint
        if (v != Variant.ARGMAX && oType != DataType.FLOAT32 && !(outScale > 0f && outScale.isFinite())) {
            Log.w(TAG, "logits tensor has no usable quantization ($outScale, $outZero)")
            return false
        }
        gridH = if (v == Variant.ARGMAX) SIZE else outShape[1]
        gridW = if (v == Variant.ARGMAX) SIZE else outShape[2]
        inputBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outT.numBytes()).order(ByteOrder.nativeOrder())
        return true
    }

    /**
     * One inference of the rewired model on a synthetic scene ([SceneSelfTest.image]). Its
     * logits must be finite and spread (range > 1), and their per-cell argmax must agree with
     * what the UNTOUCHED fused-argmax model answers for the same input
     * ([SceneSelfTest.argmaxAgreement] >= [SceneSelfTest.MIN_AGREEMENT]): that proves the right
     * tensor, dequantization and channel order, whatever classes the scene happens to get. If
     * the reference cannot run (or its answer is too mottled to judge), the sky half and the
     * ground half must at least get different classes. Catches a rewired graph that runs but
     * yields garbage.
     */
    private fun selfTest(interp: Interpreter, v: Variant): Boolean {
        if (v == Variant.ARGMAX) return true
        val img = SceneSelfTest.image(SIZE)
        val scores = infer(interp, v, img) as SceneScores.Logits
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
        for (x in scores.values) {
            if (!x.isFinite()) { Log.w(TAG, "self-test: non-finite logits ($v)"); return false }
            if (x < lo) lo = x
            if (x > hi) hi = x
        }
        if (hi - lo <= 1f) {
            Log.w(TAG, "self-test failed ($v): logit range ${hi - lo}")
            return false
        }
        val agreement = referenceLabels(img)?.let { SceneSelfTest.argmaxAgreement(scores, it, SIZE) }
        if (agreement != null) {
            val ok = agreement >= SceneSelfTest.MIN_AGREEMENT
            if (ok) Log.i(TAG, "self-test ($v): logits argmax agrees with the argmax model on ${(agreement * 100).roundToInt()} % of cells")
            else Log.w(TAG, "self-test failed ($v): logits argmax agrees with the argmax model on only ${(agreement * 100).roundToInt()} % of cells")
            return ok
        }
        val ok = SceneSelfTest.halvesDiffer(scores)
        if (!ok) Log.w(TAG, "self-test failed ($v): the sky and ground halves got the same class")
        return ok
    }

    /** Class ids of the untouched fused-argmax model for [img] (the self-test reference), or null. */
    private fun referenceLabels(img: PixelBuffer): ByteArray? {
        var ref: Interpreter? = null
        return try {
            ref = Interpreter(mapModel(), Interpreter.Options().setNumThreads(NUM_THREADS))
            if (ref.inputTensorCount != 1 || ref.outputTensorCount < 1) return null
            val inT = ref.getInputTensor(0)
            val outT = ref.getOutputTensor(0)
            val inType = inT.dataType()
            val oType = outT.dataType()
            val outShape = outT.shape()
            val inOk = inT.shape().contentEquals(intArrayOf(1, SIZE, SIZE, 3)) && (inType == DataType.INT8 || inType == DataType.UINT8)
            val outOk = (outShape.contentEquals(intArrayOf(1, SIZE, SIZE)) || outShape.contentEquals(intArrayOf(1, SIZE, SIZE, 1))) &&
                (oType == DataType.FLOAT32 || oType == DataType.INT32)
            if (!inOk || !outOk) return null
            val q = inT.quantizationParams()
            val bytes = ByteArray(SIZE * SIZE * 3)
            encode(img, Letterbox.quantLut(q.scale, q.zeroPoint, signed = inType == DataType.INT8), bytes)
            val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            input.put(bytes)
            input.rewind()
            val output = ByteBuffer.allocateDirect(outT.numBytes()).order(ByteOrder.nativeOrder())
            ref.run(input, output)
            output.rewind()
            readClassIds(output, oType)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "not enough memory for the self-test reference", e)
            null
        } catch (t: Exception) {
            Log.w(TAG, "self-test reference failed", t)
            null
        } finally {
            closeQuietly(ref)
        }
    }

    private fun releaseLocked() {
        try {
            interpreter?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closing interpreter", t)
        }
        interpreter = null
        variant = null
        modelBuffer = null
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

    /** A writable native-order copy of the asset (the logits patch edits it in place). */
    private fun readModel(): ByteBuffer {
        val bytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        require(bytes.size in 8..MAX_MODEL_BYTES) { "unexpected model size ${bytes.size}" }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buf.put(bytes)
        buf.rewind()
        return buf
    }

    companion object {
        private const val TAG = "Segmentation"
        const val MODEL_ASSET = "models/autoseg_edgetpu_s.tflite"
        private const val SIZE = Letterbox.MODEL_SIZE
        private const val NUM_THREADS = 4
        private const val MAX_MODEL_BYTES = 64 shl 20

        @Volatile private var instance: LiteRtSceneParser? = null

        fun get(context: Context): LiteRtSceneParser =
            instance ?: synchronized(this) {
                instance ?: LiteRtSceneParser(context.applicationContext ?: context).also { instance = it }
            }
    }
}
