package com.brushwork.paint.qa3

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.WrapContour
import com.brushwork.paint.tools.text.WrapFixtures
import com.brushwork.paint.tools.text.WrapFixtures.disc
import com.brushwork.paint.tools.text.WrapFixtures.pixels
import com.brushwork.paint.tools.text.WrapFixtures.setup
import com.brushwork.paint.tools.text.WrapFixtures.wrappedText
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Final QA (v1.5, §7 checklist 3): text wrapped around a picture, driven across the other areas
 * like a user would: the picture moved and pinched with Transform (pixels and vector objects),
 * painted with the clone stamp, filtered, masked with the Masks tool, merged into, duplicated
 * texts, undo / redo, and reload. Every edit of the picture re-flows the text INSIDE that edit's
 * own undo step (I2) and the text layer always equals a fresh rendering of its stored item (I1).
 */
@RunWith(RobolectricTestRunner::class)
class Qa3WrapFlowsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun itemOf(layer: Layer): TextItem = TextCodec.decode(layer.textData)!!

    private fun assertRendered(text: Layer, c: EditorController) {
        val fresh = WrapFixtures.render(itemOf(text), c.doc.width, c.doc.height)
        assertArrayEquals("the text layer is its item's rendering (I1)", pixels(fresh), pixels(text.bitmap))
    }

    private fun minX(item: TextItem) = item.wrap.polygons.flatMap { it.xs }.minOrNull() ?: Float.NaN

    /** Two fingers as the canvas delivers them: the first one's touch is cancelled, then the pinch. */
    private fun pinch(c: EditorController, a: Vec2, b: Vec2, scale: Float, move: Vec2 = Vec2(0f, 0f)): Boolean {
        c.pointerDown(ToolPoint(a.x, a.y))
        c.pointerCancel()
        val took = c.twoFingerStart(Vec2((a.x + b.x) / 2f, (a.y + b.y) / 2f), a, b)
        if (took) {
            for (i in 1..5) c.twoFingerGesture(Vec2(move.x * i / 5f, move.y * i / 5f), 1f + (scale - 1f) * i / 5f, 0f)
            c.twoFingerEnd(false)
        }
        return took
    }

    // ------------------------------------------------------------------ ids

    @Test
    fun aDeletedPicturesIdIsNotGivenToANewLayerAfterReload() = runBlocking<Unit> {
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        val s = setup(context, scope = scope)
        // The text is written first; the picture is added later (a higher layer id), as when a
        // photo is imported after the text was laid out.
        val text = wrappedText(s, source = s.picture)
        val photo = s.c.addLayer("Photo")!!
        assertTrue("the photo's id is the highest", photo.id > text.id)
        disc(photo, 110f, 150f, 50f)
        s.c.selectTool(ToolId.TEXT)
        assertTrue(s.tool.editLayer(text))
        s.tool.setWrapSource(photo)
        s.tool.commit()
        assertEquals(photo.id, itemOf(text).wrap.sourceLayerId)
        val wrapped = itemOf(text)
        s.c.deleteLayer(photo)
        assertEquals("the text keeps its outline", wrapped, itemOf(text))

        repo.save(s.c.doc, null)
        val doc = repo.load(s.c.doc.id)
        val c2 = EditorController(context, doc, scope, com.brushwork.paint.AppSettings(context))
        val t2 = doc.layers.first { it.id == text.id }
        assertEquals(wrapped, itemOf(t2))
        val fresh = c2.addLayer("New")!!
        assertNotEquals("a new layer never takes the deleted picture's id (the text would wrap around it)", photo.id, fresh.id)
        // Painting on the new layer leaves the text alone.
        val data = t2.textData
        c2.editWholeLayer(fresh, "Paint") { b -> Canvas(b).drawRect(0f, 0f, 200f, 300f, Paint().apply { color = WrapFixtures.BLACK }) }
        assertEquals(data, t2.textData)
        c2.dispose()
    }

    /**
     * Export SVG -> "New from SVG or PDF": the restored layers get new ids, so the restored text
     * must wrap around the restored PICTURE (not whichever layer now has the old id), and a
     * picture that was not part of the import must not be replaced by another layer.
     */
    @Test
    fun aBrushworkSvgRestoresTheWrapAroundItsOwnPicture() {
        val s = setup(context, scope = scope)
        // Background(1), Sketch(3), Picture(2), Text(4): ids not in layer order on purpose.
        s.c.selectLayer(s.background)
        val sketch = s.c.addLayer("Sketch")!!
        s.c.editWholeLayer(sketch, "Lines") { b -> Canvas(b).drawRect(0f, 280f, 400f, 300f, Paint().apply { color = WrapFixtures.BLACK }) }
        disc(s.picture, 110f, 150f, 50f)
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.TEXT)
        val text = wrappedText(s)
        val item = itemOf(text)
        assertEquals(s.picture.id, item.wrap.sourceLayerId)

        val options = com.brushwork.paint.exchange.export.ExportOptions(com.brushwork.paint.exchange.VectorFormat.SVG)
        val scene = runBlocking {
            com.brushwork.paint.exchange.export.ExportSceneBuilder(s.c, options, com.brushwork.paint.exchange.export.TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        }
        val out = java.io.ByteArrayOutputStream()
        runBlocking { com.brushwork.paint.exchange.export.SvgWriter(scene).write(out) }
        val svg = com.brushwork.paint.exchange.svg.SvgParser.parse(out.toByteArray())
        val payload = svg.payload()!!

        // A new artwork made for it (as the gallery does): its empty layer is replaced by the
        // import, whose layers get ids 2, 3, 4, 5 (the old picture's id 2 is now the background's).
        val fresh = Smoke.document(400, 300, layers = 1, whiteBottom = true)
        val c = Smoke.controller(context, fresh)
        val replace = fresh.layers.toList()
        val target = com.brushwork.paint.exchange.ImportTarget(400, 300, fresh.dpi, fresh.colorMode, com.brushwork.paint.exchange.ImportLayers.room(c) + replace.size, c.maxLayers)
        val prepared = com.brushwork.paint.exchange.PayloadImport.prepare(payload, { key -> svg.imageData(key)?.let { com.brushwork.paint.exchange.image.PngDecoder.decode(it) } }, target)
        com.brushwork.paint.exchange.PayloadImport.apply(c, prepared, replace)
        val pic2 = c.doc.layers.single { it.name == "Picture" }
        val bg2 = c.doc.layers.single { it.name == "Background" }
        val text2 = c.doc.layers.single { it.isTextLayer }
        val item2 = itemOf(text2)
        assertEquals("the same outline", item.wrap.polygons, item2.wrap.polygons)
        assertEquals("wraps around the restored picture", pic2.id, item2.wrap.sourceLayerId)
        // Painting on the restored background leaves the text alone; painting the picture re-flows it.
        val data = text2.textData
        c.editWholeLayer(bg2, "Fill") { b -> b.eraseColor(0xFFEEEEEE.toInt()) }
        assertEquals(data, text2.textData)
        c.editWholeLayer(pic2, "Scribble") { b -> Canvas(b).drawRect(250f, 40f, 320f, 110f, Paint().apply { color = WrapFixtures.BLACK }) }
        assertNotEquals(data, text2.textData)
        assertRendered(text2, c)

        // Imported without its picture (only the text layer is in the payload): the text keeps
        // its outline and no layer of the document is taken for its picture.
        val onlyText = payload.copy(layers = payload.layers.filter { it.textData != null }, activeLayer = 0)
        val other = Smoke.document(400, 300, layers = 6, whiteBottom = true)
        val c3 = Smoke.controller(context, other)
        val t3 = com.brushwork.paint.exchange.ImportTarget(400, 300, other.dpi, other.colorMode, com.brushwork.paint.exchange.ImportLayers.room(c3), c3.maxLayers)
        com.brushwork.paint.exchange.PayloadImport.apply(c3, com.brushwork.paint.exchange.PayloadImport.prepare(onlyText, { null }, t3))
        val text3 = c3.doc.layers.single { it.isTextLayer }
        val item3 = itemOf(text3)
        assertEquals(item.wrap.polygons, item3.wrap.polygons)
        assertTrue("still wraps (its kept outline)", item3.wrapActive)
        assertTrue("its picture is not any layer of this document", c3.doc.layers.none { it.id == item3.wrap.sourceLayerId })
        val data3 = text3.textData
        for (l in c3.doc.layers.filter { !it.isTextLayer }) c3.editWholeLayer(l, "Fill") { b -> b.eraseColor(0xFF808080.toInt()) }
        assertEquals("no other layer re-flows it", data3, text3.textData)
        val added = c3.addLayer("Later")!!
        assertNotEquals(item3.wrap.sourceLayerId, added.id)
    }

    // ------------------------------------------------------------------ turning wrap on

    /** Document x-extent of the pending text's box. */
    private fun boxXs(tool: TextTool): ClosedFloatingPointRange<Float> {
        val item = tool.item!!
        val block = tool.blockFor(item)
        val xs = item.corners(block.width, block.height).map { it.x }
        return xs.min()..xs.max()
    }

    /**
     * A long line typed without a fixed width runs past both canvas edges; turning wrap on gives
     * it the canvas width. The text must stay on the canvas (it used to keep the line's left
     * edge, far off the canvas, so the whole text vanished to the left).
     */
    @Test
    fun turningWrapOnForALongLineKeepsTheTextOnTheCanvas() {
        val s = setup(context, scope = scope)
        disc(s.picture, 200f, 125f, 30f)
        s.tool.startTextAt(200f, 120f)
        s.tool.setText("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore")
        s.tool.updateSpec { it.copy(sizePx = 16f, color = WrapFixtures.BLACK) }
        s.tool.confirmEditor()
        assertTrue("a line wider than the canvas", boxXs(s.tool).let { it.start < 0f && it.endInclusive > 400f })
        // The white background covers the whole canvas: never the default picture, even though
        // the line runs past the canvas (it covers only the part of the box on the canvas).
        assertEquals(s.picture, s.tool.defaultWrapSource())
        s.tool.openWrapSheet()
        assertEquals(s.picture.id, s.tool.item!!.wrap.sourceLayerId)
        assertTrue(s.tool.item!!.wrapActive)
        assertEquals("the canvas width", 400f, s.tool.item!!.spec.box.width, 0.5f)
        val xs = boxXs(s.tool)
        assertTrue("the box is on the canvas: $xs", xs.start >= -1f && xs.endInclusive <= 401f)
        val pending = s.tool.item!!
        assertTrue(s.tool.commitItem())
        val text = s.c.activeLayer
        val ink = WrapFixtures.inked(text.bitmap, 64)
        assertTrue("ink on the canvas: ${ink.size} ${text.name} $pending", ink.size > 300)
    }

    /**
     * The Wrap chip on a line running past the canvas, with no picture under it: the full-canvas
     * background must not be picked as its picture (it covered "less than 90 %" of the box only
     * because the box sticks out of the canvas, and wrapping around it hid the whole text).
     */
    @Test
    fun aLineRunningPastTheCanvasNeverWrapsAroundTheBackground() {
        val s = setup(context, scope = scope)
        disc(s.picture, 200f, 220f, 30f)
        s.tool.startTextAt(200f, 120f)
        s.tool.setText("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore")
        s.tool.updateSpec { it.copy(sizePx = 16f, color = WrapFixtures.BLACK) }
        s.tool.confirmEditor()
        assertEquals("no picture under the text", null, s.tool.defaultWrapSource())
        s.tool.openWrapSheet()
        assertTrue(s.tool.wrapSheetOpen)
        assertFalse("wrap stays off until a picture is chosen", s.tool.item!!.wrap.isOn)
    }

    /** A short text near the right edge grows to 8 em: it grows into the canvas, not past its edge. */
    @Test
    fun turningWrapOnNearTheRightEdgeGrowsIntoTheCanvas() {
        val s = setup(context, scope = scope)
        disc(s.picture, 200f, 200f, 30f)
        s.tool.startTextAt(370f, 60f)
        s.tool.setText("Hi there")
        s.tool.updateSpec { it.copy(sizePx = 16f, color = WrapFixtures.BLACK) }
        s.tool.confirmEditor()
        s.tool.setWrapSource(s.picture)
        assertEquals("8 em", 128f, s.tool.item!!.spec.box.width, 0.5f)
        val xs = boxXs(s.tool)
        assertTrue("the box stays on the canvas: $xs", xs.endInclusive <= 401f && xs.start >= -1f)
    }

    // ------------------------------------------------------------------ Transform

    @Test
    fun pinchingThePictureWithOneFingerOnItReflowsInOneStep() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 40f)
        val text = wrappedText(s)
        val before = itemOf(text)
        val textBefore = pixels(text.bitmap)
        val picBefore = pixels(s.picture.bitmap)
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.TRANSFORM)
        WrapFixtures.idle()
        val tt = s.c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertNotNull("lifted", tt.transformState)
        val steps = s.c.undoManager.undoCount
        // One finger on the disc, the other far right on the text: the picture scales.
        assertTrue(pinch(s.c, Vec2(110f, 150f), Vec2(330f, 150f), scale = 1.5f))
        tt.commit()
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        val after = itemOf(text)
        assertTrue("the outline grew with the picture", minX(after) < minX(before) - 10f)
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(before, itemOf(text))
        assertArrayEquals(textBefore, pixels(text.bitmap))
        assertArrayEquals(picBefore, pixels(s.picture.bitmap))
        s.c.redo()
        assertEquals(after, itemOf(text))
        assertRendered(text, s.c)
    }

    @Test
    fun movingAVectorPictureWithTransformReflowsInItsStep() {
        val s = setup(context, scope = scope)
        val vector = s.c.addVectorLayer()!!
        val square = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(60f, 100f, sharp = true), VAnchor(140f, 100f, sharp = true), VAnchor(140f, 200f, sharp = true), VAnchor(60f, 200f, sharp = true)), closed = true)),
            polyline = true,
            fill = VPaint.Solid(WrapFixtures.BLACK),
        )
        s.c.vectors.addObjects(vector, listOf(square), "Add square")
        s.c.settleVectorWork()
        Smoke.pump(100)
        s.c.selectTool(ToolId.TEXT)
        val text = wrappedText(s, source = vector)
        val before = itemOf(text)
        assertEquals(60f, minX(before), 1.5f)
        s.c.selectLayer(vector)
        s.c.selectTool(ToolId.TRANSFORM)
        val tt = s.c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("objects lifted", Smoke.pumpUntil(10_000) { tt.transformState != null })
        val steps = s.c.undoManager.undoCount
        tt.moveBy(120f, 0f)
        tt.commit()
        assertTrue(Smoke.pumpUntil(10_000) { s.c.settleVectorWork(); !tt.hasPendingWork })
        s.c.settleVectorWork()
        Smoke.pump(200)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertTrue("still a vector layer", vector.isVectorLayer)
        val after = itemOf(text)
        assertEquals("the outline moved with the objects", 180f, minX(after), 1.5f)
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(before, itemOf(text))
        s.c.redo()
        assertEquals(after, itemOf(text))
    }

    // ------------------------------------------------------------------ other edits of the picture

    @Test
    fun theCloneStampOnThePictureReflowsInsideItsStep() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        val before = itemOf(text)
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.CLONE)
        val clone = s.c.tools.getValue(ToolId.CLONE) as CloneTool
        clone.setSource(Vec2(110f, 150f))
        s.c.cloneBrush = s.c.cloneBrush.copy(size = 40f)
        val steps = s.c.undoManager.undoCount
        s.c.pointerDown(ToolPoint(110f, 60f))
        for (i in 1..20) s.c.pointerMove(ToolPoint(110f + 2f * i, 60f))
        s.c.pointerUp(ToolPoint(150f, 60f))
        Smoke.pump(100)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        val after = itemOf(text)
        assertNotEquals("the cloned disc re-flowed the text", before.wrap.polygons, after.wrap.polygons)
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(before, itemOf(text))
    }

    @Test
    fun aFilterOnThePictureReflowsInsideItsStep() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        val before = itemOf(text)
        s.c.selectLayer(s.picture)
        val steps = s.c.undoManager.undoCount
        s.c.startFilter(FilterRegistry.byId("blur.gaussian")!!)
        val session = s.c.filterSession!!
        session.update(session.filter.params.first().key, 12f)
        session.apply()
        assertTrue(Smoke.pumpUntil(20_000) { s.c.filterSession == null && s.c.busyMessage == null })
        Smoke.pump(100)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        val after = itemOf(text)
        assertTrue("the blurred disc is wider", minX(after) < minX(before))
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(before, itemOf(text))
    }

    @Test
    fun aGradientOnThePicturesOwnMaskReflowsInsideItsStep() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 50f)
        val text = wrappedText(s)
        val before = itemOf(text)
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.MASK)
        val mask = s.c.tools.getValue(ToolId.MASK) as MaskTool
        mask.chooseTarget(MaskTool.Target.ThisLayer)
        mask.arm(MaskTool.Kind.LINEAR)
        val steps = s.c.undoManager.undoCount
        // A linear gradient hiding the top half of the disc.
        s.c.pointerDown(ToolPoint(110f, 120f))
        for (i in 1..10) s.c.pointerMove(ToolPoint(110f, 120f + 3f * i))
        s.c.pointerUp(ToolPoint(110f, 150f))
        Smoke.pumpUntil(5_000) { s.picture.maskSpec != null }
        Smoke.pump(200)
        assertNotNull("an editable mask", s.picture.maskSpec)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        val after = itemOf(text)
        assertNotEquals("the masked part no longer blocks", before.wrap.polygons, after.wrap.polygons)
        assertEquals(s.c.textWrap.contours.polygons(s.picture, WrapContour.SHAPE), after.wrap.polygons)
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(before, itemOf(text))
    }

    @Test
    fun twoTextsAroundOnePictureReflowInOneStep() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        val copy = s.c.duplicateLayer(text)!!
        assertTrue(copy.isTextLayer)
        val a = itemOf(text)
        val b = itemOf(copy)
        val steps = s.c.undoManager.undoCount
        s.c.editWholeLayer(s.picture, "Scribble") { bmp -> Canvas(bmp).drawRect(250f, 60f, 330f, 120f, Paint().apply { color = WrapFixtures.BLACK }) }
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        assertNotEquals(a, itemOf(text))
        assertNotEquals(b, itemOf(copy))
        assertRendered(text, s.c)
        assertRendered(copy, s.c)
        s.c.undo()
        assertEquals(a, itemOf(text))
        assertEquals(b, itemOf(copy))
    }

    @Test
    fun mergingALayerIntoThePictureReflowsInsideMergeDown() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        val before = itemOf(text)
        // A layer right above the picture with more drawing, merged into it.
        s.c.selectLayer(s.picture)
        val upper = s.c.addLayer("Upper")!!
        s.c.editWholeLayer(upper, "Scribble") { bmp -> Canvas(bmp).drawRect(250f, 40f, 320f, 110f, Paint().apply { color = WrapFixtures.BLACK }) }
        assertEquals(s.c.doc.indexOf(s.picture) + 1, s.c.doc.indexOf(upper))
        val steps = s.c.undoManager.undoCount
        s.c.mergeDown(upper)
        assertEquals("one step", steps + 1, s.c.undoManager.undoCount)
        val after = itemOf(text)
        assertNotEquals("the merged drawing is part of the picture now", before.wrap.polygons, after.wrap.polygons)
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(before, itemOf(text))
    }

    // ------------------------------------------------------------------ undo / reload

    @Test
    fun afterUndoOpeningTheTextChangesNothing() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 40f)
        val text = wrappedText(s)
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.TRANSFORM)
        WrapFixtures.idle()
        val tt = s.c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tt.moveBy(150f, 0f)
        tt.commit()
        s.c.undo()
        val steps = s.c.undoManager.undoCount
        val data = text.textData
        s.c.selectTool(ToolId.TEXT)
        val tool = s.c.tools.getValue(ToolId.TEXT) as TextTool
        assertTrue(tool.editLayer(text))
        assertFalse("the text matches its restored picture: nothing to record", tool.hasUserChanges)
        tool.commit()
        assertEquals(steps, s.c.undoManager.undoCount)
        assertEquals(data, text.textData)
        // Redo, open again: still nothing to record.
        s.c.redo()
        val redone = text.textData
        assertTrue(tool.editLayer(text))
        assertFalse(tool.hasUserChanges)
        tool.commit()
        assertEquals(redone, text.textData)
    }

    @Test
    fun afterReloadEditingThePictureStillReflows() = runBlocking<Unit> {
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        repo.save(s.c.doc, null)
        val doc = repo.load(s.c.doc.id)
        val c2 = EditorController(context, doc, scope, com.brushwork.paint.AppSettings(context))
        val t2 = doc.layers.first { it.id == text.id }
        val pic2 = doc.layers.first { it.id == s.picture.id }
        val before = itemOf(t2)
        // Opening it right after the reload records nothing.
        c2.selectTool(ToolId.TEXT)
        val tool = c2.tools.getValue(ToolId.TEXT) as TextTool
        assertTrue(tool.editLayer(t2))
        assertFalse("a reloaded text matches its picture", tool.hasUserChanges)
        tool.commit()
        val steps = c2.undoManager.undoCount
        c2.selectLayer(pic2)
        c2.selectTool(ToolId.BRUSH)
        c2.brush = c2.brush.copy(size = 30f)
        c2.pointerDown(ToolPoint(250f, 60f))
        for (i in 1..20) c2.pointerMove(ToolPoint(250f + 3f * i, 60f + 6f * i))
        c2.pointerUp(ToolPoint(310f, 180f))
        assertEquals(steps + 1, c2.undoManager.undoCount)
        assertNotEquals(before, itemOf(t2))
        assertRendered(t2, c2)
        c2.dispose()
    }

    @Test
    fun textRenderingOfTheReloadedItemIsStable() {
        // Guard of the helper above: a text item renders identically twice.
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        val item = itemOf(text)
        assertArrayEquals(pixels(WrapFixtures.render(item, 400, 300)), pixels(WrapFixtures.render(item, 400, 300)))
        assertNotNull(TextRenderer.prepare(item))
    }
}
