package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.TransformLabels17
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 11, design §3.11 d): the Transform tool's routing of text, shape and arrayed layers
 * to the data lift, with FAKE `TextTransforms` / `ShapeTransforms` / `ArrayTransforms` (the real
 * bodies belong to areas D, C and E; the tests that need them run at F's merge gate). A layer
 * whose map answers commits ONE step carrying the fake's data, its pixels a fresh rendering of
 * it; a `null` answer keeps the v1.6 pixel lift (text, shape) or refuses (array); a text has no
 * side handles and no flips; Distort is refused with its caption, and "Rasterize and deform"
 * rasterizes as its own step before it distorts.
 */
@RunWith(RobolectricTestRunner::class)
class DataLiftRoutingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 200
    private val h = 120

    /**
     * Maps that move a text's or a shape's centre by the matrix (enough to see whose data was
     * committed); each kind answers only while its flag is on. [textMoves]: false = only the
     * identity is answered (a map that declines what the user did).
     */
    private class FakeMaps(var text: Boolean = true, var shape: Boolean = true, var array: Boolean = true, var textMoves: Boolean = true) : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = text && (textMoves || isIdentity(m))

        override fun text(textData: String, m: FloatArray): String? {
            if (!textCanMap(m)) return null
            return TextCodec.encode(mappedText(TextCodec.decode(textData)!!, m))
        }

        override fun shape(shapeData: String, m: FloatArray): String? {
            if (!shape) return null
            val o = ShapeCodec.decode(shapeData)!!
            return ShapeCodec.encode(o.copy(cx = m[0] * o.cx + m[1] * o.cy + m[2], cy = m[3] * o.cx + m[4] * o.cy + m[5]))
        }

        override fun array(layer: Layer, m: FloatArray): LayerData? {
            if (!array) return null
            val d = layer.dataSnapshot()
            return d.copy(text = d.text?.let { TextCodec.encode(mappedText(TextCodec.decode(it)!!, m)) })
        }

        companion object {
            fun mappedText(t: TextItem, m: FloatArray): TextItem = t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5])
            fun isIdentity(m: FloatArray): Boolean = m.contentEquals(DataRender.IDENTITY)
        }
    }

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.activeLayerIndex = 0
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun transform(c: EditorController, maps: DataMaps): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.dataMaps = { maps }
        tool.snapToObjects = false
        return tool
    }

    private val hello = TextItem("Hello", spec = TextSpec(sizePx = 48f), cx = 90f, cy = 60f)

    private fun text(item: TextItem): (Canvas) -> Unit = { cv -> TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null) }

    private fun textLayer(c: EditorController, item: TextItem = hello): Layer =
        c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item), draw = text(item))!!

    private val box = ShapeObject(ShapeType.RECTANGLE, cx = 90f, cy = 60f, w = 100f, h = 60f, style = ShapeStyle.FILL)

    private fun shapeLayer(c: EditorController): Layer {
        val o = ShapeCodec.decode(ShapeCodec.encode(box))!!
        return c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(box)) { cv ->
            cv.drawRect(o.cx - o.w / 2f, o.cy - o.h / 2f, o.cx + o.w / 2f, o.cy + o.h / 2f, android.graphics.Paint().apply { color = BLUE })
        }!!
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun EditorController.drag(from: Pair<Float, Float>, to: Pair<Float, Float>) {
        pointerDown(ToolPoint(from.first, from.second, 1f, 0L))
        for (k in 1..8) {
            val t = k / 8f
            pointerMove(ToolPoint(from.first + (to.first - from.first) * t, from.second + (to.second - from.second) * t, 1f, k.toLong()))
        }
        pointerUp(ToolPoint(to.first, to.second, 1f, 9L))
    }

    @Test
    fun aTextLayerIsMovedAsDataInOneStepWithoutSideHandlesOrFlips() {
        val c = setup()
        val layer = textLayer(c)
        val before = pixels(layer.bitmap)
        val tool = transform(c, FakeMaps())
        tool.start()
        assertEquals(TransformTool.Lifted.TEXT, tool.lifted)
        assertTrue("a text scales proportionally only", tool.uniformOnly)
        assertFalse("no side handles on a text", tool.sideHandlesShown)
        assertFalse("no flips on a text", tool.flipsAllowed)
        assertTrue("its corners keep the aspect ratio", tool.keepAspect)
        assertEquals(TransformLabels17.RASTERIZE_TO_DEFORM, tool.modeRefusal(TransformTool.Mode.DISTORT))
        assertTrue(tool.canRasterizeFor(TransformTool.Mode.DISTORT))
        tool.mode = TransformTool.Mode.DISTORT
        assertEquals("Distort is not switched to", TransformTool.Mode.FREE, tool.mode)

        // A flip does nothing; a drag that starts on the right side's middle moves the box (no side handle there).
        val b0 = tool.transformState!!.bounds()
        tool.flip(horizontal = true)
        assertEquals(1f, tool.transformState!!.sx, 0f)
        c.drag(b0.right to (b0.top + b0.bottom) / 2f, b0.right + 20f to (b0.top + b0.bottom) / 2f + 10f)
        val b1 = tool.transformState!!.bounds()
        assertEquals("moved, not resized", b0.width, b1.width, 1e-3f)
        assertEquals(b0.left + 20f, b1.left, 1e-3f)
        assertEquals(b0.top + 10f, b1.top, 1e-3f)

        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        val moved = hello.copy(cx = hello.cx + 20f, cy = hello.cy + 10f)
        assertEquals("the fake's data", TextCodec.encode(moved), layer.textData)
        val fresh = BitmapUtils.createLayerBitmap(w, h).also { text(moved)(Canvas(it)) }
        assertArrayEquals("the pixels are a fresh rendering of the data", pixels(fresh), pixels(layer.bitmap))

        c.undo()
        assertEquals(TextCodec.encode(hello), layer.textData)
        assertArrayEquals(before, pixels(layer.bitmap))
        c.redo()
        assertEquals(TextCodec.encode(moved), layer.textData)
        assertArrayEquals(pixels(fresh), pixels(layer.bitmap))
    }

    @Test
    fun aShapeLayerKeepsItsSidesAndFlipsAndCommitsTheFakesData() {
        val c = setup()
        val layer = shapeLayer(c)
        val tool = transform(c, FakeMaps())
        tool.start()
        assertEquals(TransformTool.Lifted.SHAPE, tool.lifted)
        assertFalse(tool.uniformOnly)
        assertTrue(tool.sideHandlesShown)
        assertTrue(tool.flipsAllowed)
        assertEquals(TransformLabels17.RASTERIZE_TO_DEFORM, tool.modeRefusal(TransformTool.Mode.DISTORT))

        // The right side's handle resizes the width only.
        val b0 = tool.transformState!!.bounds()
        c.drag(b0.right to (b0.top + b0.bottom) / 2f, b0.right + 20f to (b0.top + b0.bottom) / 2f)
        val b1 = tool.transformState!!.bounds()
        assertEquals(b0.width + 20f, b1.width, 0.5f)
        assertEquals(b0.height, b1.height, 1e-3f)
        tool.reset()
        tool.moveBy(-30f, 12f)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        val o = ShapeCodec.decode(layer.shapeData)
        assertNotNull("still a shape layer", o)
        assertEquals(box.cx - 30f, o!!.cx, 1e-3f)
        assertEquals(box.cy + 12f, o.cy, 1e-3f)
        c.undo()
        assertEquals(ShapeCodec.encode(box), layer.shapeData)
    }

    @Test
    fun aNullMapKeepsTheV16PixelLiftOfTextAndShape() {
        val c = setup()
        val maps = FakeMaps(text = false, shape = false)
        val t = textLayer(c)
        val tool = transform(c, maps)
        tool.start()
        assertEquals("pixels, as in v1.6", TransformTool.Lifted.PIXELS, tool.lifted)
        assertNull("Distort as in v1.6", tool.modeRefusal(TransformTool.Mode.DISTORT))
        assertTrue(tool.sideHandlesShown)
        tool.moveBy(10f, 0f)
        tool.commit()
        assertNull("the pixel edit rasterized the text (v1.6)", t.textData)
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)

        val s = shapeLayer(c)
        tool.start()
        assertEquals(TransformTool.Lifted.PIXELS, tool.lifted)
        tool.discard()
        assertNotNull(s.shapeData)
    }

    @Test
    fun aTextWhoseMapDeclinesTheTransformIsResampledAsInV16() {
        val c = setup()
        val layer = textLayer(c)
        val tool = transform(c, FakeMaps(textMoves = false))
        tool.start()
        assertEquals("the identity is answered: a data lift", TransformTool.Lifted.TEXT, tool.lifted)
        tool.moveBy(15f, 5f)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertNull("rasterized in the same step", layer.textData)
        c.undo()
        assertEquals(TextCodec.encode(hello), layer.textData)
    }

    @Test
    fun anArrayIsMappedWhenItsMapAnswersAndRefusedOtherwise() {
        val c = setup()
        val hi = TextItem("Hi", spec = TextSpec(sizePx = 24f), cx = 40f, cy = 60f)
        val layer = textLayer(c, hi)
        val spec = ArraySpec(count = 3, relativeX = 1f)
        assertTrue(c.updateLayerData(layer, layer.dataSnapshot().copy(array = LayerArray(spec)), "Array", null, draw = text(hi)))
        val maps = FakeMaps(array = false)
        val tool = transform(c, maps)
        val steps = c.undoManager.undoCount
        tool.start()
        assertNull("refused: nothing lifted", tool.transformState)
        assertEquals(TransformTool.ARRAY_REFUSAL, c.message)
        assertEquals(steps, c.undoManager.undoCount)

        maps.array = true
        tool.start()
        assertEquals(TransformTool.Lifted.ARRAY, tool.lifted)
        assertEquals(ArrayLabels.DEFORM_REFUSAL, tool.modeRefusal(TransformTool.Mode.DISTORT))
        assertFalse("an array is applied by hand, not rasterized here", tool.canRasterizeFor(TransformTool.Mode.DISTORT))
        tool.moveBy(12f, 20f)
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val moved = hi.copy(cx = hi.cx + 12f, cy = hi.cy + 20f)
        assertEquals(TextCodec.encode(moved), layer.textData)
        assertEquals("still a live array", LayerArray(spec), layer.array)

        // The pixels equal the same data rendered from scratch.
        val ref = textLayer(c, moved)
        assertTrue(c.updateLayerData(ref, ref.dataSnapshot().copy(array = LayerArray(spec)), "Array", null, draw = text(moved)))
        assertArrayEquals(pixels(ref.bitmap), pixels(layer.bitmap))
    }

    @Test
    fun rasterizeAndDeformRasterizesAsItsOwnStepThenDistorts() {
        val c = setup()
        val layer = textLayer(c)
        val tool = transform(c, FakeMaps())
        tool.start()
        tool.moveBy(10f, 0f)
        val steps = c.undoManager.undoCount
        assertTrue(tool.rasterizeAndDeform(TransformTool.Mode.DISTORT))
        assertEquals("the move, then the rasterize", steps + 2, c.undoManager.undoCount)
        assertEquals(TransformTool.RASTERIZE_TEXT_LABEL, c.undoManager.undoLabel)
        assertNull(layer.textData)
        assertEquals(TransformTool.Lifted.PIXELS, tool.lifted)
        assertEquals(TransformTool.Mode.DISTORT, tool.mode)
        assertTrue(tool.flipsAllowed)
        val fresh = BitmapUtils.createLayerBitmap(w, h).also { text(hello.copy(cx = hello.cx + 10f))(Canvas(it)) }
        assertArrayEquals("the moved text's pixels", pixels(fresh), pixels(layer.bitmap))

        // A corner dragged in Distort: a pixel edit of the (now raster) layer.
        val q = tool.transformState!!.corner(2)
        c.drag(q.x to q.y, q.x + 15f to q.y + 8f)
        assertTrue(tool.transformState!!.isDistorted)
        tool.commit()
        assertEquals(steps + 3, c.undoManager.undoCount)
        assertNotEquals(pixels(fresh).toList(), pixels(layer.bitmap).toList())
        c.undo()
        c.undo()
        assertEquals("undo brings the text back", TextCodec.encode(hello.copy(cx = hello.cx + 10f)), layer.textData)
    }

    private companion object {
        const val BLUE = 0xFF2244CC.toInt()
    }
}
