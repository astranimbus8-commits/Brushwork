package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTransforms
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.ui.common.TransformLabels17
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 (item 11, design §3.11 a) with area D's real `TextTransforms`: transforming a text layer
 * keeps it a text (sharp, editable), in ONE step that undo takes back exactly, and its pixels are
 * a fresh rendering of the stored text (I1). Distort and Free deform ask to rasterize first.
 *
 * Linked frames (lead decision): a Transform SCALE of one linked frame scales the WHOLE story's
 * type, and the story re-flows through every frame in the same undo step; a pure MOVE changes
 * nothing but that frame's position.
 *
 * GUARDED: every test is skipped while `TextTransforms` is the foundation stub (`canMap` false);
 * it runs once area D's implementation is merged.
 */
@RunWith(RobolectricTestRunner::class)
class TransformTextObjectRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    @Before
    fun guard() = assumeTrue("area D's TextTransforms is still the foundation stub", TextTransforms.canMap(MOVE_AND_SCALE))

    private val app get() = RuntimeEnvironment.getApplication()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun itemOf(layer: Layer): TextItem {
        assertNotNull("still a text", layer.textData)
        return TextCodec.decode(layer.textData)!!
    }

    private fun rendered(item: TextItem, w: Int, h: Int): IntArray =
        pixels(BitmapUtils.createLayerBitmap(w, h).also { TextRenderer.drawItem(Canvas(it), item, TextRenderer.prepare(item), null) })

    /** A 240 x 160 document with one text layer, "Hello" in 32 px at (120, 80), active. */
    private fun textSetup(): Pair<EditorController, Layer> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 240, 160)
        val item = TextItem("Hello", spec = TextSpec(sizePx = 32f), cx = 120f, cy = 80f)
        val layer = Layer(doc.newLayerId(), "Text", BitmapUtils.createLayerBitmap(240, 160))
        layer.textData = TextCodec.encode(item)
        TextRenderer.drawItem(Canvas(layer.bitmap), item, TextRenderer.prepare(item), null)
        doc.layers += layer
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return c to layer
    }

    private fun transform(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.snapToObjects = false
        tool.start()
        assertEquals("kept as a text", TransformTool.Lifted.TEXT, tool.lifted)
        return tool
    }

    @Test
    fun aMovedAndScaledTextStaysATextInOneStep() {
        val (c, layer) = textSetup()
        val before = layer.textData
        val pixelsBefore = pixels(layer.bitmap)
        val tool = transform(c)
        assertTrue("proportional only", tool.uniformOnly)
        assertEquals(TransformLabels17.RASTERIZE_TO_DEFORM, tool.modeRefusal(TransformTool.Mode.DISTORT))
        assertEquals(TransformLabels17.RASTERIZE_TO_FREE_DEFORM, tool.modeRefusal(TransformTool.Mode.MESH))

        tool.moveBy(10f, 6f)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val moved = itemOf(layer)
        assertEquals(130f, moved.cx, 0.01f)
        assertEquals(86f, moved.cy, 0.01f)
        assertEquals(32f, moved.spec.sizePx, 0.01f)
        assertArrayEquals("I1: the pixels are the stored text", rendered(moved, 240, 160), pixels(layer.bitmap))

        val tool2 = transform(c)
        tool2.setScalePercent(150.0)
        tool2.endNumericEdit()
        tool2.commit()
        assertEquals(steps + 2, c.undoManager.undoCount)
        val scaled = itemOf(layer)
        assertEquals("the type is scaled", 48f, scaled.spec.sizePx, 0.05f)
        assertArrayEquals(rendered(scaled, 240, 160), pixels(layer.bitmap))

        c.undo()
        c.undo()
        assertEquals("undo restores the text exactly", before, layer.textData)
        assertArrayEquals(pixelsBefore, pixels(layer.bitmap))
    }

    @Test
    fun aTextTurnedThirtyAndScaledTwoHundredStaysATextInOneUndo() {
        val (c, layer) = textSetup()
        val before = layer.textData
        val pixelsBefore = pixels(layer.bitmap)
        val tool = transform(c)
        tool.setRotation(30.0)
        tool.setScalePercent(200.0)
        tool.endNumericEdit()
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val t = itemOf(layer)
        assertEquals("the type doubled", 64f, t.spec.sizePx, 0.05f)
        assertEquals("turned 30°", 30f, t.rotationDeg, 0.05f)
        assertArrayEquals("I1: the pixels are the stored text", rendered(t, 240, 160), pixels(layer.bitmap))
        c.undo()
        assertEquals("one undo restores the text exactly", before, layer.textData)
        assertArrayEquals(pixelsBefore, pixels(layer.bitmap))
    }

    @Test
    fun scalingOneLinkedFrameScalesTheStorysTypeAndReflowsItInOneStep() {
        val s = FrameFixtures.setup(app)
        val first = FrameFixtures.newFrame(s, 20f, 20f, 180f, 140f, size = 16f)
        val second = FrameFixtures.linkFrame(s, first, 210f, 20f, 380f, 140f)
        val storyId = FrameFixtures.itemOf(first).thread.storyId
        assertTrue(storyId != 0L)
        val firstBefore = first.textData
        val secondBefore = second.textData
        val secondBox = FrameFixtures.itemOf(second).let { listOf(it.cx, it.cy) }
        val c = s.c
        c.selectLayer(first)
        val tool = transform(c)
        tool.setScalePercent(150.0)
        tool.endNumericEdit()
        val steps = c.undoManager.undoCount
        tool.commit()

        assertEquals("ONE step for the scale and the re-flow", steps + 1, c.undoManager.undoCount)
        assertEquals("the first frame's type", 24f, FrameFixtures.itemOf(first).spec.sizePx, 0.05f)
        assertEquals("the WHOLE story's type", 24f, FrameFixtures.itemOf(second).spec.sizePx, 0.05f)
        assertEquals("the other frame stays where it is", secondBox, FrameFixtures.itemOf(second).let { listOf(it.cx, it.cy) })
        FrameFixtures.assertWhole(c, storyId)

        c.undo()
        assertEquals("one undo: the first frame back", firstBefore, first.textData)
        assertEquals("one undo: the second frame back", secondBefore, second.textData)
    }

    @Test
    fun movingOneLinkedFrameChangesNothingElse() {
        val s = FrameFixtures.setup(app)
        val first = FrameFixtures.newFrame(s, 20f, 20f, 180f, 140f, size = 16f)
        val second = FrameFixtures.linkFrame(s, first, 210f, 20f, 380f, 140f)
        val before = FrameFixtures.itemOf(first)
        val secondBefore = second.textData
        val c = s.c
        c.selectLayer(first)
        val tool = transform(c)
        tool.moveBy(15f, 10f)
        val steps = c.undoManager.undoCount
        tool.commit()

        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("the other frame is untouched", secondBefore, second.textData)
        val after = FrameFixtures.itemOf(first)
        assertEquals(before.cx + 15f, after.cx, 0.01f)
        assertEquals(before.cy + 10f, after.cy, 0.01f)
        assertEquals("the type is kept", before.spec.sizePx, after.spec.sizePx, 0f)
        assertEquals("the slice is kept", before.thread.start to before.thread.end, after.thread.start to after.thread.end)
        assertEquals(before.text, after.text)
    }

    private companion object {
        /** A move and a 1.5 x proportional scale (row-major 3 x 3): a text can take it. */
        val MOVE_AND_SCALE = floatArrayOf(1.5f, 0f, 10f, 0f, 1.5f, 5f, 0f, 0f, 1f)
    }
}
