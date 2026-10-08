package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.MultiLayerRenderOverride
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 11, design §3.11 a): the Transform tool on a FOLDER moves every layer inside it
 * together, nested folders included, each by its own rule: a raster layer's pixels are
 * resampled, a vector layer's objects mapped exactly (its pixels a fresh render), a text kept as
 * text through the data map (a fake here; area D owns the real one). ONE step, and one undo
 * restores every layer exactly. While it is pending the preview draws all of them (a
 * multi-layer render override). Distort is refused ("Distort works on one layer") and a locked
 * layer inside refuses the lift with its reason.
 */
@RunWith(RobolectricTestRunner::class)
class TransformFolderRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 160
    private val h = 120

    private val red = 0xFFDD2211.toInt()

    /** A text map that moves the centre only (enough to see the text was kept as text). */
    private object MoveOnlyMaps : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = true
        override fun text(textData: String, m: FloatArray): String? = TextCodec.decode(textData)?.let { TextCodec.encode(moved(it, m)) }
        override fun shape(shapeData: String, m: FloatArray): String? = null
        override fun array(layer: Layer, m: FloatArray): LayerData? = null

        fun moved(t: TextItem, m: FloatArray): TextItem = t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5])
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFF2060C0.toInt()),
        stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 4f),
    )

    private val hello = TextItem("Hi", spec = TextSpec(sizePx = 32f), cx = 120f, cy = 90f)

    /**
     * Bottom first: Background (white), [Raster (red rect), [Vector (a box)] Inner] Folder, plus
     * [withText] a "Text" layer in Folder. The folder is active.
     */
    private fun setup(withText: Boolean = false): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        fun pixel(name: String, parent: Long) = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { it.parentId = parent }
        val bg = pixel("Background", Layer.ROOT_ID).also { it.bitmap.eraseColor(-1) }
        val folder = Layer.newFolder(doc.newLayerId(), "Folder")
        val inner = Layer.newFolder(doc.newLayerId(), "Inner").also { it.parentId = folder.id }
        val raster = pixel("Raster", folder.id).also { Canvas(it.bitmap).drawRect(Rect(20, 20, 60, 50), Paint().apply { color = red }) }
        val vector = pixel("Vector", inner.id).also { it.vector = VectorContent.EMPTY }
        val text = if (withText) pixel("Text", folder.id).also { l ->
            l.textData = TextCodec.encode(hello)
            TextRenderer.drawItem(Canvas(l.bitmap), hello, TextRenderer.prepare(hello), null)
        } else null
        doc.layers += listOfNotNull(bg, raster, vector, inner, text, folder)
        doc.activeLayerIndex = doc.layers.indexOf(folder)
        assertNull(LayerTree.check(doc.layers))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.vectors.addObjects(vector, listOf(box(70f, 30f, 100f, 60f)), "Add")
        return c
    }

    private fun EditorController.byName(name: String): Layer = doc.layers.first { it.name == name }

    private fun transform(c: EditorController, maps: DataMaps = MoveOnlyMaps): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.dataMaps = { maps }
        tool.snapToObjects = false
        return tool
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    @Test
    fun aFoldersRasterAndVectorLayersMoveTogetherInOneStep() {
        val c = setup()
        val raster = c.byName("Raster")
        val vector = c.byName("Vector")
        val rasterBefore = pixels(raster.bitmap)
        val vectorBefore = pixels(vector.bitmap)
        val contentBefore = vector.vector!!
        val tool = transform(c)
        tool.start()
        assertEquals(TransformTool.Lifted.FOLDER, tool.lifted)
        assertFalse("no text inside: every handle", tool.uniformOnly)
        // The box is both layers' content: the red rect and the vector box (with its stroke).
        val b0 = tool.transformState!!.bounds()
        assertEquals(20f, b0.left, 0.5f)
        assertEquals(20f, b0.top, 0.5f)
        assertTrue(b0.right >= 100f)
        val preview = c.renderOverride
        assertTrue("the preview draws every layer", preview is MultiLayerRenderOverride)
        assertEquals(setOf(raster, vector), (preview as MultiLayerRenderOverride).layers)

        tool.moveBy(12f, 8f)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        assertNull("the preview is gone", c.renderOverride)

        // The raster layer: its pixels moved by whole pixels, exactly.
        val expected = BitmapUtils.createLayerBitmap(w, h).also { Canvas(it).drawRect(Rect(32, 28, 72, 58), Paint().apply { color = red }) }
        assertArrayEquals("raster moved", pixels(expected), pixels(raster.bitmap))
        // The vector layer: still objects, moved exactly; its pixels a fresh render of them.
        val after = vector.vector!!
        assertEquals(1, after.objects.size)
        val ob = VectorOps.bounds(after.objects[0])
        val b4 = VectorOps.bounds(contentBefore.objects[0])
        assertEquals(b4.left + 12f, ob.left, 1e-3f)
        assertEquals(b4.top + 8f, ob.top, 1e-3f)
        assertArrayEquals("vector pixels are a fresh render", render(after), pixels(vector.bitmap))
        val rasterAfter = pixels(raster.bitmap)
        val vectorAfter = pixels(vector.bitmap)

        c.undo()
        assertArrayEquals("one undo: raster back", rasterBefore, pixels(raster.bitmap))
        assertArrayEquals("one undo: vector pixels back", vectorBefore, pixels(vector.bitmap))
        assertEquals("one undo: vector objects back", contentBefore, vector.vector)
        c.redo()
        assertArrayEquals(rasterAfter, pixels(raster.bitmap))
        assertArrayEquals(vectorAfter, pixels(vector.bitmap))
        assertEquals(after, vector.vector)
        assertSame("folders keep FOLDER_BITMAP", Layer.FOLDER_BITMAP, c.byName("Folder").bitmap)
        assertNull(LayerTree.check(c.doc.layers))
    }

    @Test
    fun aTextInsideIsKeptAsTextAndTheFolderScalesProportionally() {
        val c = setup(withText = true)
        val text = c.byName("Text")
        val tool = transform(c)
        tool.start()
        assertEquals(TransformTool.Lifted.FOLDER, tool.lifted)
        assertTrue("a text inside: proportional only", tool.uniformOnly)
        assertFalse(tool.sideHandlesShown)
        assertFalse(tool.flipsAllowed)
        tool.moveBy(-10f, -6f)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        val moved = MoveOnlyMaps.moved(hello, floatArrayOf(1f, 0f, -10f, 0f, 1f, -6f, 0f, 0f, 1f))
        assertEquals("still a text, moved", TextCodec.encode(moved), text.textData)
        val fresh = BitmapUtils.createLayerBitmap(w, h).also { TextRenderer.drawItem(Canvas(it), moved, TextRenderer.prepare(moved), null) }
        assertArrayEquals(pixels(fresh), pixels(text.bitmap))
        c.undo()
        assertEquals(TextCodec.encode(hello), text.textData)
    }

    @Test
    fun distortIsRefusedOnAFolder() {
        val c = setup()
        val tool = transform(c)
        tool.start()
        assertEquals(TransformTool.Lifted.FOLDER, tool.lifted)
        assertEquals(TransformTool.DISTORT_FOLDER_REFUSAL, tool.modeRefusal(TransformTool.Mode.DISTORT))
        assertFalse("no way out by rasterizing", tool.canRasterizeFor(TransformTool.Mode.DISTORT))
        tool.mode = TransformTool.Mode.DISTORT
        assertEquals(TransformTool.Mode.FREE, tool.mode)
        tool.discard()
        assertNull(tool.transformState)
    }

    @Test
    fun aLockedLayerInsideRefusesTheLift() {
        val c = setup()
        c.byName("Raster").locked = true
        val before = c.doc.layers.map { if (it.isFolder) 0 else pixels(it.bitmap).contentHashCode() }
        val steps = c.undoManager.undoCount
        val tool = transform(c)
        tool.start()
        assertNull("nothing lifted", tool.transformState)
        assertEquals("Layer \"Raster\" is locked", c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(before, c.doc.layers.map { if (it.isFolder) 0 else pixels(it.bitmap).contentHashCode() })
        assertNotEquals(TransformTool.Lifted.FOLDER, tool.lifted)
    }
}
