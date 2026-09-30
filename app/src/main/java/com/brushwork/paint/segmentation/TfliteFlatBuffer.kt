package com.brushwork.paint.segmentation

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal read-only view of a TFLite model flatbuffer (the parts of schema.fbs the logits patch
 * needs: operator codes, the first subgraph's tensors / inputs / outputs / operators, and the
 * signature defs). Pure JVM, no TensorFlow dependency. Every read is bounds-checked; a damaged
 * file throws [IllegalArgumentException] or [IndexOutOfBoundsException].
 *
 * FlatBuffer layout reminder: the root starts with a uoffset to the root table; a table starts
 * with an soffset to its vtable (vtable = table - soffset); the vtable holds uint16 sizes and one
 * uint16 offset per field (0 = absent). Vectors are a uint32 length followed by the elements;
 * vectors of tables hold uoffsets relative to each element's own position.
 */
internal class TfliteModelView(buffer: ByteBuffer) {
    private val bb: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    private val limit = bb.limit()

    class Tensor(val index: Int, val name: String, val shape: IntArray, val type: Int, val scale: FloatArray, val zeroPoint: LongArray)

    class Operator(val index: Int, val builtinCode: Int, val customCode: String?, val inputs: IntArray, val outputs: IntArray)

    /** A signature output (name, tensor index) and the byte position of its tensor_index field. */
    class SignatureOutput(val name: String?, val tensorIndex: Int, val fieldPosition: Int)

    val tensors: List<Tensor>
    val operators: List<Operator>
    val inputs: IntArray
    val outputs: IntArray

    /** Byte position of the subgraph's `outputs` vector (its uint32 length). */
    val outputsVectorPosition: Int

    /** Byte position of the subgraph's `operators` vector (its uint32 length). */
    val operatorsVectorPosition: Int
    val signatureOutputs: List<SignatureOutput>

    init {
        require(limit >= 8) { "not a flatbuffer" }
        val ident = ByteArray(4) { bb.get(4 + it) }
        require(String(ident, Charsets.US_ASCII) == "TFL3") { "not a TFLite model" }
        val model = table(0)
        val codes = vector(model, 1)
        val builtin = IntArray(length(codes))
        val custom = arrayOfNulls<String>(builtin.size)
        for (i in builtin.indices) {
            val c = element(codes, i)
            val dep = field(c, 0)
            val code = field(c, 3)
            val deprecated = if (dep != 0) bb.get(dep).toInt() else 0
            val full = if (code != 0) i32(code) else 0
            builtin[i] = maxOf(deprecated, full)
            custom[i] = string(c, 1)
        }
        val subgraphs = vector(model, 2)
        require(length(subgraphs) >= 1) { "no subgraph" }
        val sg = element(subgraphs, 0)
        val tv = vector(sg, 0)
        tensors = List(length(tv)) { i ->
            val t = element(tv, i)
            val typeField = field(t, 1)
            val q = field(t, 4)
            val qt = if (q != 0) q + i32(q) else 0
            Tensor(
                index = i,
                name = string(t, 3) ?: "",
                shape = ints(t, 0),
                type = if (typeField != 0) bb.get(typeField).toInt() else 0,
                scale = if (qt != 0) floats(qt, 2) else FloatArray(0),
                zeroPoint = if (qt != 0) longs(qt, 3) else LongArray(0),
            )
        }
        inputs = ints(sg, 1)
        outputs = ints(sg, 2)
        outputsVectorPosition = vector(sg, 2)
        operatorsVectorPosition = vector(sg, 3)
        operators = List(length(operatorsVectorPosition)) { i ->
            val op = element(operatorsVectorPosition, i)
            val oc = field(op, 0)
            val opcode = if (oc != 0) i32(oc) else 0
            require(opcode in builtin.indices) { "bad opcode index $opcode" }
            Operator(i, builtin[opcode], custom[opcode], ints(op, 1), ints(op, 2))
        }
        val sigs = vector(model, 7)
        val sigOuts = ArrayList<SignatureOutput>()
        for (s in 0 until length(sigs)) {
            val sig = element(sigs, s)
            val outs = vector(sig, 1)
            for (j in 0 until length(outs)) {
                val m = element(outs, j)
                val f = field(m, 1)
                sigOuts += SignatureOutput(string(m, 0), if (f != 0) i32(f) else 0, f)
            }
        }
        signatureOutputs = sigOuts
        for (t in inputs + outputs) require(t in tensors.indices) { "bad tensor index $t" }
    }

    /** Index of the operator that writes tensor [t], or -1. */
    fun producerOf(t: Int): Int = operators.indexOfFirst { t in it.outputs }

    // ---------------------------------------------------------------- flatbuffer primitives

    private fun check(pos: Int, size: Int) {
        if (pos < 0 || size < 0 || pos.toLong() + size > limit) throw IndexOutOfBoundsException("offset $pos (+$size) outside $limit")
    }

    private fun i32(pos: Int): Int { check(pos, 4); return bb.getInt(pos) }

    private fun table(uoffsetPos: Int): Int = uoffsetPos + i32(uoffsetPos)

    /** Absolute position of field [id] of the table at [t], or 0 if absent. */
    private fun field(t: Int, id: Int): Int {
        val vt = t - i32(t)
        check(vt, 4)
        val vtLen = bb.getShort(vt).toInt() and 0xFFFF
        val off = 4 + 2 * id
        if (off + 2 > vtLen) return 0
        check(vt + off, 2)
        val o = bb.getShort(vt + off).toInt() and 0xFFFF
        return if (o == 0) 0 else t + o
    }

    /** Position of the vector field [id] (its uint32 length), or 0 if absent. */
    private fun vector(t: Int, id: Int): Int {
        val f = field(t, id)
        return if (f == 0) 0 else f + i32(f)
    }

    private fun length(v: Int): Int {
        if (v == 0) return 0
        val n = i32(v)
        require(n >= 0) { "negative vector length" }
        check(v + 4, n) // at least one byte per element
        return n
    }

    private fun element(v: Int, i: Int): Int {
        val p = v + 4 + 4 * i
        return p + i32(p)
    }

    private fun string(t: Int, id: Int): String? {
        val v = vector(t, id)
        if (v == 0) return null
        val n = length(v)
        val bytes = ByteArray(n)
        for (i in 0 until n) bytes[i] = bb.get(v + 4 + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun ints(t: Int, id: Int): IntArray {
        val v = vector(t, id)
        val n = length(v)
        check(v + 4, n * 4)
        return IntArray(n) { bb.getInt(v + 4 + 4 * it) }
    }

    private fun floats(t: Int, id: Int): FloatArray {
        val v = vector(t, id)
        val n = length(v)
        check(v + 4, n * 4)
        return FloatArray(n) { bb.getFloat(v + 4 + 4 * it) }
    }

    private fun longs(t: Int, id: Int): LongArray {
        val v = vector(t, id)
        val n = length(v)
        check(v + 4, n * 8)
        return LongArray(n) { bb.getLong(v + 4 + 8 * it) }
    }

    companion object {
        const val TYPE_FLOAT32 = 0
        const val TYPE_INT32 = 2
        const val TYPE_UINT8 = 3
        const val TYPE_INT8 = 9

        const val OP_RESIZE_BILINEAR = 23
    }
}

/**
 * Rewires the bundled Autoseg-EdgeTPU "fused argmax" model so that it outputs its per-class
 * LOGITS (the [1, 64, 64, 32] tensor that feeds the finalizer's first RESIZE_BILINEAR) instead
 * of the argmax class map. Three in-place edits, the file size never changes:
 *
 * 1. `subgraphs[0].outputs[0]` = the logits tensor;
 * 2. every signature output that named the old output tensor names the logits tensor;
 * 3. (optional) the `operators` vector length is cut right after the op that produces the
 *    logits. Operators run in vector order (TFLite's execution plan), so nothing after that op
 *    can contribute to the logits; the finalizer (resize, reduce-max, argmax arithmetic,
 *    resize, squeeze, dequantize) is skipped instead of computed and thrown away.
 *
 * The edits are reversible (the dropped operators stay in the file), so the original can always
 * be restored by re-reading the untouched asset.
 */
internal object TfliteLogitsPatch {

    class Result(
        val logitsTensor: Int,
        val shape: IntArray,
        val type: Int,
        val scale: Float,
        val zeroPoint: Int,
        val originalOutput: Int,
        val operatorCount: Int,
        val originalOperatorCount: Int,
    )

    /**
     * Finds the logits tensor: the input of the FIRST RESIZE_BILINEAR operator whose input is a
     * rank-4 [1, h, w, classes] tensor (the model's FPN also resizes, but with 96 channels).
     */
    fun findLogits(view: TfliteModelView, classes: Int): Int {
        for (op in view.operators) {
            if (op.builtinCode != TfliteModelView.OP_RESIZE_BILINEAR || op.inputs.isEmpty()) continue
            val t = op.inputs[0]
            if (t !in view.tensors.indices) continue
            val s = view.tensors[t].shape
            if (s.size == 4 && s[0] == 1 && s[3] == classes && s[1] in 8..512 && s[2] in 8..512) return t
        }
        return -1
    }

    /**
     * Applies the patch to [model] in place (a writable little-endian or native-order buffer
     * holding the whole file). Returns null (and changes nothing) when the model does not have
     * the expected structure.
     */
    fun patchInPlace(model: ByteBuffer, classes: Int = SceneClasses.COUNT, truncate: Boolean = true): Result? {
        val view = try {
            TfliteModelView(model)
        } catch (e: RuntimeException) {
            return null
        }
        if (view.outputs.size != 1) return null
        val logits = findLogits(view, classes)
        if (logits < 0) return null
        val producer = view.producerOf(logits)
        if (producer < 0) return null
        val t = view.tensors[logits]
        if (t.type != TfliteModelView.TYPE_INT8 && t.type != TfliteModelView.TYPE_UINT8 && t.type != TfliteModelView.TYPE_FLOAT32) return null
        val oldOutput = view.outputs[0]
        val bb = model.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(view.outputsVectorPosition + 4, logits)
        for (s in view.signatureOutputs) {
            if (s.tensorIndex == oldOutput && s.fieldPosition != 0) bb.putInt(s.fieldPosition, logits)
        }
        val kept = if (truncate) producer + 1 else view.operators.size
        if (truncate) bb.putInt(view.operatorsVectorPosition, kept)
        return Result(
            logitsTensor = logits,
            shape = t.shape.copyOf(),
            type = t.type,
            scale = t.scale.firstOrNull() ?: 0f,
            zeroPoint = (t.zeroPoint.firstOrNull() ?: 0L).toInt(),
            originalOutput = oldOutput,
            operatorCount = kept,
            originalOperatorCount = view.operators.size,
        )
    }
}
