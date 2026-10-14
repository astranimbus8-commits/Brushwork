package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.TextExportMode
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.layers.FrameInfoCache
import com.brushwork.paint.vector.VPaint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (F2, design 2.5, I24): a text layer of TWO texts through every reader of the 25 former
 * one-text `TextCodec` sites, on real Skia (Robolectric NATIVE graphics), plus the Text tool's
 * editing paths (fact V32): editing the bottom text keeps the other text (I1: the pixels equal
 * the data's rendering after ✓), the in-layer preview shows both texts, and emptying the bottom
 * text leaves the other one in the layer in one "Edit text" step (I2); the pill's trash on the
 * open text removes only that text, in one "Delete text" step.
 */
@RunWith(RobolectricTestRunner::class)
class TextMultiItemReadersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val black = 0xFF000000.toInt()
    private val red = 0xFFCC2222.toInt()

    private val alpha = TextItem("Alpha", TextSpec(sizePx = 32f, color = black), 80f, 60f)
    private val beta = TextItem("Beta", TextSpec(sizePx = 28f, color = red), 200f, 150f)

    // ------------------------------------------------------------------ helpers

    private fun newController(w: Int = 300, h: Int = 200): Pair<EditorController, TextTool> {
        val d = Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        val c = EditorController(context, d, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** [items] drawn in picture order (bottom first) into a fresh [w] x [h] bitmap, as a text layer is drawn. */
    private fun renderAll(items: List<TextItem>, w: Int, h: Int): Bitmap = BitmapUtils.createLayerBitmap(w, h).also { b ->
        val cv = Canvas(b)
        for (t in items) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null)
    }

    /** I1: [layer]'s pixels equal a fresh rendering of every text it stores. */
    private fun assertRendered(layer: Layer, what: String) {
        val items = TextCodec.decodeAll(layer.textData)
        assertTrue("$what: the layer holds text", items.isNotEmpty())
        assertArrayEquals("$what: pixels equal a fresh rendering of every stored text (I1)", pixels(renderAll(items, layer.width, layer.height)), pixels(layer.bitmap))
    }

    /** The document as the compositor draws it now (tool previews included). */
    private fun composite(c: EditorController): Bitmap =
        BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height).also { c.compositor.drawDocument(Canvas(it), null, target = null) }

    /** Pixels where [item] alone paints fully opaque, as indices into a [w] x [h] bitmap. */
    private fun solidInk(item: TextItem, w: Int, h: Int): List<Int> {
        val px = pixels(renderAll(listOf(item), w, h))
        return px.indices.filter { (px[it] ushr 24) == 0xFF }
    }

    /** A text layer added with the Text tool ([alpha]) that then becomes [alpha] + [beta] (pixels and data, one setup step). */
    private fun twoTextLayer(c: EditorController, tool: TextTool): Layer {
        tool.startTextAt(alpha.cx, alpha.cy)
        tool.setText(alpha.text)
        tool.updateSpec { it.copy(sizePx = alpha.spec.sizePx, color = black) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)
        val first = TextCodec.decodeAll(layer.textData).single()
        val both = listOf(first, beta)
        assertTrue(c.updateTextLayer(layer, TextCodec.encodeAll(both), "Setup", null) { cv -> for (t in both) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null) })
        assertEquals(2, TextCodec.decodeAll(layer.textData).size)
        assertRendered(layer, "set-up")
        return layer
    }

    // ------------------------------------------------------------------ the Text tool (fact V32)

    @Test
    fun editingTheBottomTextKeepsTheOtherText() {
        val (c, tool) = newController()
        val layer = twoTextLayer(c, tool)
        val before = TextCodec.decodeAll(layer.textData)
        val steps = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer))
        assertEquals("the bottom text is the one edited", before[0], tool.item)
        tool.setText("Alpha two")
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        assertNull(tool.item)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("Edit text", c.undoManager.undoLabel)
        val after = TextCodec.decodeAll(layer.textData)
        assertEquals("both texts are stored", 2, after.size)
        assertEquals("Alpha two", after[0].text)
        assertEquals("the other text is kept as it was", before[1], after[1])
        assertRendered(layer, "after ✓")
        // The other text's pixels are there (it was not cleared by the re-render).
        val px = pixels(layer.bitmap)
        val betaInk = solidInk(beta, layer.width, layer.height)
        assertTrue(betaInk.isNotEmpty())
        assertTrue("the other text's pixels stay", betaInk.all { (px[it] ushr 24) == 0xFF })
        // Undo brings back both texts as they were.
        c.undo()
        assertEquals(before, TextCodec.decodeAll(layer.textData))
        assertRendered(layer, "after undo")
    }

    @Test
    fun thePreviewShowsBothTexts() {
        val (c, tool) = newController()
        val layer = twoTextLayer(c, tool)
        assertTrue(tool.editLayer(layer))
        tool.setText("Alpha two")
        val shown = pixels(composite(c))
        val w = layer.width
        val h = layer.height
        val betaInk = solidInk(beta, w, h)
        assertTrue(betaInk.isNotEmpty())
        assertTrue("the other text shows while the bottom one is edited", betaInk.all { shown[it] == red })
        val editedInk = solidInk(tool.item!!, w, h)
        assertTrue(editedInk.isNotEmpty())
        assertTrue("the edited text shows", editedInk.all { (shown[it] ushr 24) == 0xFF })
        // Nothing was stored yet.
        assertEquals(2, TextCodec.decodeAll(layer.textData).size)
        assertEquals("Alpha", TextCodec.decodeAll(layer.textData)[0].text)
    }

    @Test
    fun emptyingTheBottomTextLeavesTheOtherTextInOneStep() {
        val (c, tool) = newController()
        val layer = twoTextLayer(c, tool)
        val before = TextCodec.decodeAll(layer.textData)
        val layers = c.doc.layers.size
        val name = layer.name
        val steps = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer, openEditor = true))
        tool.setText("")
        tool.confirmEditor()
        assertNull("the edit ended", tool.item)
        assertTrue("the layer stays", c.doc.indexOf(layer) >= 0)
        assertEquals(layers, c.doc.layers.size)
        assertEquals("its name stays", name, layer.name)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("Edit text", c.undoManager.undoLabel)
        assertEquals("only the other text is left", listOf(before[1]), TextCodec.decodeAll(layer.textData))
        assertRendered(layer, "after emptying")
        c.undo()
        assertEquals(before, TextCodec.decodeAll(layer.textData))
        assertRendered(layer, "after undo")
        // The ✓ path (commitItem) does the same.
        assertTrue(tool.editLayer(layer))
        tool.setText("   ")
        assertTrue(tool.commitItem())
        assertEquals(listOf(before[1]), TextCodec.decodeAll(layer.textData))
        assertEquals("Edit text", c.undoManager.undoLabel)
        assertRendered(layer, "after ✓ of an emptied text")
    }

    @Test
    fun thePillTrashDeletesOnlyTheOpenTextOfALayerOfSeveral() {
        val (c, tool) = newController()
        val layer = twoTextLayer(c, tool)
        val before = TextCodec.decodeAll(layer.textData)
        val layers = c.doc.layers.size
        val name = layer.name
        val steps = c.undoManager.undoCount
        assertTrue(tool.editLayer(layer))
        tool.setText("Alpha changed")
        assertTrue(tool.deleteObject())
        assertNull("the edit ended", tool.item)
        assertTrue("the layer stays", c.doc.indexOf(layer) >= 0)
        assertEquals(layers, c.doc.layers.size)
        assertEquals("its name stays", name, layer.name)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DELETE_TEXT, c.undoManager.undoLabel)
        assertEquals("only the other text is left", listOf(before[1]), TextCodec.decodeAll(layer.textData))
        assertRendered(layer, "after the trash")
        c.undo()
        assertEquals(before, TextCodec.decodeAll(layer.textData))
        assertRendered(layer, "after undo")
        // A layer of one text is deleted whole, as in v1.8.
        // v1.9 (R14): the switch keeps the new text out of the open text layer.
        c.settings.textNewLayerEachTime = true
        tool.startTextAt(150f, 120f)
        tool.setText("Solo")
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val solo = c.activeLayer
        assertEquals(1, TextCodec.decodeAll(solo.textData).size)
        val count = c.doc.layers.size
        assertTrue(tool.editLayer(solo))
        assertTrue(tool.deleteObject())
        assertEquals(count - 1, c.doc.layers.size)
        assertTrue(c.doc.indexOf(solo) < 0)
        assertEquals(HistoryLabels.DELETE_TEXT, c.undoManager.undoLabel)
    }

    @Test
    fun aNewTextAndAOneTextLayerStayAsInV18() {
        val (c, tool) = newController()
        tool.startTextAt(120f, 80f)
        tool.setText("Solo")
        tool.updateSpec { it.copy(sizePx = 30f, color = black) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        val item = TextCodec.decodeAll(layer.textData).single()
        assertEquals("one text: the bytes v1.8 wrote", TextCodec.encode(item), layer.textData)
        assertFalse(layer.textData!!.contains("\"more\""))
        assertRendered(layer, "new text")
        // Emptying the only text deletes the layer (v1.8).
        val count = c.doc.layers.size
        assertTrue(tool.editLayer(layer))
        tool.setText("")
        tool.confirmEditor()
        assertEquals(count - 1, c.doc.layers.size)
        assertTrue(c.doc.indexOf(layer) < 0)
    }

    // ------------------------------------------------------------------ the readers of the 2.5 table

    @Test
    fun arraySourcesAndArrayDrawUseEveryText() {
        val w = 300
        val h = 200
        val json = TextCodec.encodeAll(listOf(alpha, beta))
        val data = LayerData(text = json)
        val draw = ArraySources.sourceDraw(data, ColorMode.RGB, w, h)
        assertNotNull(draw)
        val drawn = BitmapUtils.createLayerBitmap(w, h).also { draw!!(Canvas(it)) }
        assertArrayEquals("the copy source is every text", pixels(renderAll(listOf(alpha, beta), w, h)), pixels(drawn))
        val expected = RectF(TextRenderer.prepare(alpha).docBounds(alpha)).apply { union(TextRenderer.prepare(beta).docBounds(beta)) }
        assertEquals("the source bounds are the union", expected, ArrayDraw.sourceBounds(data))
        // One text: its own bounds, as in v1.8.
        assertEquals(TextRenderer.prepare(alpha).docBounds(alpha), ArrayDraw.sourceBounds(LayerData(text = TextCodec.encodeAll(listOf(alpha)))))
        assertNull(ArrayDraw.sourceBounds(LayerData(text = "not json")))
        assertEquals("an empty text still has (empty) bounds", RectF(), ArrayDraw.sourceBounds(LayerData(text = TextCodec.encodeAll(listOf(TextItem())))))
        assertFalse(ArraySources.isLinkedText(Layer(1L, "t", BitmapUtils.createLayerBitmap(4, 4)).also { it.textData = json }))
    }

    @Test
    fun importRelinksEveryText() {
        val a = alpha.copy(wrap = TextWrapSpec(sourceLayerId = 5L))
        val b = beta.copy(wrap = TextWrapSpec(sourceLayerId = 5L))
        val c3 = alpha.copy(text = "Gamma", cy = 120f, wrap = TextWrapSpec(sourceLayerId = 7L))
        val data = LayerData(text = TextCodec.encodeAll(listOf(a, b, c3)))
        var next = 900L
        var asked = 0
        val out = ImportLayers.relinked(data, mapOf(7L to 70L)) { asked++; next++ }
        val items = TextCodec.decodeAll(out.text)
        assertEquals(3, items.size)
        assertEquals("one unused id for the missing picture", 1, asked)
        assertEquals(900L, items[0].wrap.sourceLayerId)
        assertEquals("texts of the same missing picture share it", 900L, items[1].wrap.sourceLayerId)
        assertEquals(70L, items[2].wrap.sourceLayerId)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), items.map { it.text })
        // Nothing to move: the data itself.
        val plain = LayerData(text = TextCodec.encodeAll(listOf(alpha, beta)))
        assertSame(plain, ImportLayers.relinked(plain, emptyMap()) { error("no id needed") })
        val same = LayerData(text = TextCodec.encodeAll(listOf(a, b)))
        assertSame(same, ImportLayers.relinked(same, mapOf(5L to 5L)) { error("no id needed") })
    }

    @Test
    fun transformsMapEveryText() {
        val json = TextCodec.encodeAll(listOf(alpha, beta))
        val moved = TextTransforms.mapped(json, floatArrayOf(1f, 0f, 30f, 0f, 1f, -10f, 0f, 0f, 1f))
        assertNotNull(moved)
        val items = TextCodec.decodeAll(moved)
        assertEquals(2, items.size)
        assertEquals(alpha.cx + 30f, items[0].cx, 1e-3f)
        assertEquals(alpha.cy - 10f, items[0].cy, 1e-3f)
        assertEquals(beta.cx + 30f, items[1].cx, 1e-3f)
        assertEquals(beta.cy - 10f, items[1].cy, 1e-3f)
        assertEquals(listOf("Alpha", "Beta"), items.map { it.text })
        assertSame("the identity gives the data itself", json, TextTransforms.mapped(json, DataRender.IDENTITY))
        assertNull(TextTransforms.mapped("not json", DataRender.IDENTITY))
    }

    @Test
    fun dataRenderDrawsEveryText() {
        val (c, _) = newController()
        val layer = c.doc.layers[0]
        val data = LayerData(text = TextCodec.encodeAll(listOf(alpha, beta)))
        assertTrue(DataRender.apply(c, layer, data, "Lift", Rect(), allowHidden = false))
        assertEquals(2, TextCodec.decodeAll(layer.textData).size)
        assertRendered(layer, "data lift")
    }

    @Test
    fun textExportOutlinesUniteEveryText() {
        val d = Document("t", "t", 300, 200)
        val layer = Layer(d.newLayerId(), "T", BitmapUtils.createLayerBitmap(300, 200)).also { it.textData = TextCodec.encodeAll(listOf(alpha, beta)) }
        val path = TextExport.outlines(d, layer)
        assertNotNull(path)
        val got = RectF().also { path!!.computeBounds(it, true) }
        val a = RectF().also { TextExport.outlines(alpha)!!.computeBounds(it, true) }
        val b = RectF().also { TextExport.outlines(beta)!!.computeBounds(it, true) }
        val want = RectF(a).apply { union(b) }
        assertEquals(want.left, got.left, 0.5f)
        assertEquals(want.top, got.top, 0.5f)
        assertEquals(want.right, got.right, 0.5f)
        assertEquals(want.bottom, got.bottom, 0.5f)
        // One text: the path v1.8 gave (its letters).
        val one = Layer(d.newLayerId(), "One", BitmapUtils.createLayerBitmap(300, 200)).also { it.textData = TextCodec.encodeAll(listOf(alpha)) }
        val single = RectF().also { TextExport.outlines(d, one)!!.computeBounds(it, true) }
        assertEquals(a, single)
    }

    @Test
    fun exportWritesOneElementPerTextInPictureOrder() {
        val (c, tool) = newController()
        val layer = twoTextLayer(c, tool)
        fun itemsOf(options: ExportOptions): List<SceneItem> = runBlocking {
            ExportSceneBuilder(c, options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        }.layers.single { it.name == layer.name }.items
        val svg = itemsOf(ExportOptions(VectorFormat.SVG, text = TextExportMode.EDITABLE))
        val texts = svg.filterIsInstance<SceneItem.Text>()
        assertEquals("one <text> per text", 2, texts.size)
        assertEquals("picture order", listOf("Alpha", "Beta"), texts.map { t -> t.lines.joinToString("") { it.text } })
        assertEquals(red, texts[1].style.color)
        val pdf = itemsOf(ExportOptions(VectorFormat.PDF))
        assertTrue("outlines, not pixels", pdf.isNotEmpty() && pdf.all { it is SceneItem.Shape })
        val fills = pdf.mapNotNull { ((it as SceneItem.Shape).fill as? VPaint.Solid)?.color }
        assertTrue("both texts' letters", black in fills && red in fills)
    }

    @Test
    fun textReflowsEveryWrappedTextOfTheLayer() {
        val s = WrapFixtures.setup(context, scope = scope)
        WrapFixtures.disc(s.picture, 110f, 150f, 35f)
        val text = WrapFixtures.wrappedText(s)
        val w0 = TextCodec.decodeAll(text.textData).single()
        val both = listOf(w0, w0.copy(cy = w0.cy + 24f, spec = w0.spec.copy(color = red)))
        assertTrue(s.c.updateTextLayer(text, TextCodec.encodeAll(both), "Setup", null) { cv -> for (t in both) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null) })
        val before = TextCodec.decodeAll(text.textData)
        assertEquals(2, before.size)
        assertRendered(text, "set-up")
        val reflow = s.c.textWrap
        val n0 = reflow.reflowCount
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.BRUSH)
        s.c.brush = s.c.brush.copy(size = 24f)
        s.c.pointerDown(ToolPoint(250f, 60f))
        for (i in 1..20) s.c.pointerMove(ToolPoint(250f + 70f * i / 20f, 60f + 80f * i / 20f))
        s.c.pointerUp(ToolPoint(320f, 140f))
        assertEquals("one re-flow for the layer", n0 + 1, reflow.reflowCount)
        val after = TextCodec.decodeAll(text.textData)
        assertEquals("both texts are kept", 2, after.size)
        assertNotEquals("the bottom text re-flowed", before[0].wrap.polygons, after[0].wrap.polygons)
        assertNotEquals("the other text re-flowed", before[1].wrap.polygons, after[1].wrap.polygons)
        assertEquals(after[0].wrap.polygons, after[1].wrap.polygons)
        assertEquals(before[1].cy, after[1].cy, 0f)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertRendered(text, "after the re-flow")
        // The re-flow is part of the stroke's step.
        s.c.undo()
        assertEquals(before, TextCodec.decodeAll(text.textData))
        assertRendered(text, "after undo")
        s.c.dispose()
    }

    @Test
    fun framesThreadsAndTheirReadersOnALayerOfSeveralTexts() {
        val s = FrameFixtures.setup(context)
        val frame = FrameFixtures.newFrame(s, 20f, 20f, 200f, 120f, text = "A short story")
        val frameItems = TextCodec.decodeAll(frame.textData)
        assertEquals("StoryWriter writes one text", 1, frameItems.size)
        val frameItem = frameItems.single()
        assertTrue(frameItem.threaded)
        // A layer of a plain text with a frame above it (never made by the app, I24): the first framed text counts.
        val mixed = Layer(999L, "Mixed", BitmapUtils.createLayerBitmap(4, 4)).also { it.textData = TextCodec.encodeAll(listOf(alpha, frameItem)) }
        assertEquals(frameItem.thread.storyId, FrameInfoCache().threadOf(mixed)?.storyId)
        assertTrue(ArraySources.isLinkedText(mixed))
        assertNull("item 0 is no frame", s.c.textThreads.frameOf(mixed))
        val plain = Layer(998L, "Plain", BitmapUtils.createLayerBitmap(4, 4)).also { it.textData = TextCodec.encodeAll(listOf(alpha, beta)) }
        assertNull(FrameInfoCache().threadOf(plain))
        assertNull(s.c.textThreads.frameOf(plain))
        // Joining a layer of several texts is refused: none of its texts is lost.
        val both = listOf(alpha.copy(cx = 300f, cy = 200f), beta.copy(cx = 300f, cy = 250f))
        val json = TextCodec.encodeAll(both)
        val target = s.c.addLayerWithContent("Two texts", "Setup", textData = json) { cv -> for (t in both) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null) }
        assertNotNull(target)
        val steps = s.c.undoManager.undoCount
        assertFalse(s.tool.join(frame, target!!))
        assertEquals(json, target.textData)
        assertEquals(steps, s.c.undoManager.undoCount)
        assertEquals("A short story", s.c.textThreads.frameOf(frame)!!.thread.story)
        // A layer of one text still joins.
        val one = listOf(beta.copy(cx = 300f, cy = 60f))
        val single = s.c.addLayerWithContent("One text", "Setup", textData = TextCodec.encodeAll(one)) { cv -> TextRenderer.drawItem(cv, one[0], TextRenderer.prepare(one[0]), null) }
        assertTrue(s.tool.join(frame, single!!))
        assertTrue(s.c.textThreads.isFrame(single))
    }
}
