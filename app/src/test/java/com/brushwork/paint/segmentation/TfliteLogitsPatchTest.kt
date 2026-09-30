package com.brushwork.paint.segmentation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The logits rewiring, verified on the model files that ship in the APK: the patched copy is
 * parsed again and must output the [1, 64, 64, 32] logits tensor, keep only the operators that
 * compute it, and differ from the original in exactly the three patched fields.
 */
class TfliteLogitsPatchTest {

    private fun asset(name: String): ByteArray {
        val candidates = listOf(File("src/main/assets/models/$name"), File("app/src/main/assets/models/$name"))
        val f = candidates.firstOrNull { it.isFile } ?: throw AssertionError("asset $name not found from ${File(".").absolutePath}")
        return f.readBytes()
    }

    private fun view(bytes: ByteArray) = TfliteModelView(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

    @Test
    fun bundledSceneModelIsTheFusedArgmaxExport() {
        val v = view(asset("autoseg_edgetpu_s.tflite"))
        assertEquals(1, v.inputs.size)
        assertArrayEquals(intArrayOf(1, 512, 512, 3), v.tensors[v.inputs[0]].shape)
        assertEquals(TfliteModelView.TYPE_INT8, v.tensors[v.inputs[0]].type)
        assertEquals(1, v.outputs.size)
        assertArrayEquals(intArrayOf(1, 512, 512), v.tensors[v.outputs[0]].shape)
        assertEquals(264, v.operators.size)
        val logits = TfliteLogitsPatch.findLogits(v, SceneClasses.COUNT)
        assertEquals(537, logits)
        assertTrue(v.tensors[logits].name.contains("seg-class-predict"))
    }

    @Test
    fun patchedModelOutputsTheLogitsAndDropsTheFinalizer() {
        val original = asset("autoseg_edgetpu_s.tflite")
        val bytes = original.copyOf()
        val r = TfliteLogitsPatch.patchInPlace(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), SceneClasses.COUNT, truncate = true)
        assertNotNull(r)
        r!!
        assertArrayEquals(intArrayOf(1, 64, 64, 32), r.shape)
        assertEquals(TfliteModelView.TYPE_INT8, r.type)
        assertTrue("scale ${r.scale}", r.scale > 0.1f && r.scale < 0.4f)
        assertEquals(252, r.operatorCount)
        assertEquals(264, r.originalOperatorCount)

        // Parse the patched file from scratch.
        val p = view(bytes)
        assertEquals(listOf(r.logitsTensor), p.outputs.toList())
        val out = p.tensors[p.outputs[0]]
        assertArrayEquals(intArrayOf(1, 64, 64, 32), out.shape)
        assertEquals(TfliteModelView.TYPE_INT8, out.type)
        assertEquals(1, out.scale.size)
        assertEquals(252, p.operators.size)
        assertEquals("the last kept operator writes the logits", r.logitsTensor, p.operators.last().outputs.single())
        assertTrue(p.signatureOutputs.isNotEmpty())
        assertTrue(p.signatureOutputs.all { it.tensorIndex == r.logitsTensor })
        // Every kept operator only reads tensors that are not produced by a dropped operator.
        val dropped = view(original).operators.drop(252).flatMap { it.outputs.toList() }.toSet()
        for (op in p.operators) for (t in op.inputs) assertTrue("op ${op.index} reads dropped tensor $t", t !in dropped)
        // The input is untouched.
        assertArrayEquals(intArrayOf(1, 512, 512, 3), p.tensors[p.inputs[0]].shape)

        // Exactly three 4-byte fields changed; the original array is untouched.
        assertEquals(original.size, bytes.size)
        val changed = original.indices.count { original[it] != bytes[it] }
        assertTrue("changed $changed bytes", changed in 3..12)
        assertEquals(TfliteLogitsPatch.findLogits(view(original), SceneClasses.COUNT), r.logitsTensor)
    }

    @Test
    fun untruncatedVariantKeepsEveryOperator() {
        val bytes = asset("autoseg_edgetpu_s.tflite")
        val r = TfliteLogitsPatch.patchInPlace(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), truncate = false)!!
        val p = view(bytes)
        assertEquals(264, p.operators.size)
        assertEquals(264, r.operatorCount)
        assertArrayEquals(intArrayOf(1, 64, 64, 32), p.tensors[p.outputs.single()].shape)
    }

    @Test
    fun patchingTwiceIsHarmlessAndGarbageIsRejected() {
        val bytes = asset("autoseg_edgetpu_s.tflite")
        TfliteLogitsPatch.patchInPlace(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))!!
        // Already patched: the finalizer's resize is gone, so there is no logits resize to find.
        assertNull(TfliteLogitsPatch.patchInPlace(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)))
        assertNull(TfliteLogitsPatch.patchInPlace(ByteBuffer.wrap(ByteArray(64)).order(ByteOrder.LITTLE_ENDIAN)))
        assertNull(TfliteLogitsPatch.patchInPlace(ByteBuffer.wrap("TFL3 garbage".toByteArray()).order(ByteOrder.LITTLE_ENDIAN)))
        val truncated = asset("autoseg_edgetpu_s.tflite").copyOf(200_000)
        assertNull(TfliteLogitsPatch.patchInPlace(ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN)))
        // A model without a 32-class logits tensor (MagicTouch) is left alone.
        val magic = asset("magic_touch.tflite")
        val copy = magic.copyOf()
        assertNull(TfliteLogitsPatch.patchInPlace(ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)))
        assertArrayEquals(magic, copy)
    }

    @Test
    fun bundledObjectModelMatchesWhatTheRunnerExpects() {
        val v = view(asset("magic_touch.tflite"))
        val input = v.tensors[v.inputs.single()]
        assertArrayEquals(intArrayOf(1, InteractiveSegmenter.MODEL_SIZE, InteractiveSegmenter.MODEL_SIZE, 4), input.shape)
        assertEquals(TfliteModelView.TYPE_FLOAT32, input.type)
        val output = v.tensors[v.outputs.single()]
        assertArrayEquals(intArrayOf(1, InteractiveSegmenter.MODEL_SIZE, InteractiveSegmenter.MODEL_SIZE, 1), output.shape)
        assertEquals(TfliteModelView.TYPE_FLOAT32, output.type)
        // The output is already a probability (LOGISTIC), and no custom op resolver is needed.
        assertEquals(14, v.operators.last().builtinCode)
        assertTrue(v.operators.all { it.customCode == null && it.builtinCode != 32 })
    }
}
