package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.WrapFixtures.closestInk
import com.brushwork.paint.tools.text.WrapFixtures.disc
import com.brushwork.paint.tools.text.WrapFixtures.idle
import com.brushwork.paint.tools.text.WrapFixtures.itemOf
import com.brushwork.paint.tools.text.WrapFixtures.pixels
import com.brushwork.paint.tools.text.WrapFixtures.render
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Re-flow of wrapped text when its picture is edited (v1.5 §4.1e, I2): inside the edit's own
 * undo step, never on undo / redo, never for a deleted picture; and persistence.
 */
@RunWith(RobolectricTestRunner::class)
class TextWrapReflowRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val context get() = RuntimeEnvironment.getApplication()

    /** The text layer equals a fresh rendering of its stored item (I1). */
    private fun assertRendered(text: com.brushwork.paint.model.Layer, c: EditorController) {
        assertArrayEquals(pixels(render(itemOf(text), c.doc.width, c.doc.height)), pixels(text.bitmap))
    }

    private fun centroidX(item: TextItem): Float {
        val xs = item.wrap.polygons.flatMap { it.xs }
        return xs.average().toFloat()
    }

    @Test
    fun movingThePictureWithTransformReflowsInTheSameStep() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 50f)
        val text = wrappedText(s)
        val before = itemOf(text)
        val textBefore = pixels(text.bitmap)
        val picBefore = pixels(s.picture.bitmap)
        val reflows = s.c.textWrap.reflowCount

        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.TRANSFORM)
        idle()
        val tt = s.c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        val steps = s.c.undoManager.undoCount
        tt.moveBy(170f, 0f)
        tt.commit()
        assertEquals("one user action, one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals(reflows + 1, s.c.textWrap.reflowCount)
        val after = itemOf(text)
        assertEquals("the outline moved with the picture", centroidX(before) + 170f, centroidX(after), 2f)
        assertTrue(closestInk(text.bitmap, 280f, 150f) >= 50f + 6f - 2f)
        assertRendered(text, s.c)

        // One undo restores both the picture and the text; redo brings both back; neither re-flows.
        s.c.undo()
        assertEquals(before, itemOf(text))
        assertArrayEquals(textBefore, pixels(text.bitmap))
        assertArrayEquals(picBefore, pixels(s.picture.bitmap))
        s.c.redo()
        assertEquals(after, itemOf(text))
        assertRendered(text, s.c)
        assertEquals("undo / redo never re-flow", reflows + 1, s.c.textWrap.reflowCount)
    }

    @Test
    fun placingAPictureFoldedWithItsLayerIsStillOneStep() {
        val s = setup(context, scope = scope)
        val text = wrappedText(s)
        val steps = s.c.undoManager.undoCount
        // A pasted picture: a new layer, then its placement folded with it (mergeLastUndo(2)).
        val pasted = s.c.addLayer(label = EditorController.PASTE_LABEL)!!
        // The text wraps around the new layer (set directly: no step, the new layer stays the newest).
        val item = itemOf(text).let { it.copy(wrap = it.wrap.copy(sourceLayerId = pasted.id, polygons = emptyList())) }
        text.textData = TextCodec.encode(item)
        s.c.selectTool(ToolId.TRANSFORM)
        val tt = s.c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        val image = BitmapUtils.createLayerBitmap(80, 80).also { it.eraseColor(0xFF00AA00.toInt()) }
        tt.startPlacement(pasted, image, 60f, 110f, EditorController.PASTE_LABEL)
        tt.moveBy(5f, 0f)
        tt.commit()
        assertEquals("AddLayer + placement + re-flow are one step", steps + 1, s.c.undoManager.undoCount)
        assertEquals(EditorController.PASTE_LABEL, s.c.undoManager.undoLabel)
        val wrapped = itemOf(text)
        assertEquals("the outline of the placed square", 1, wrapped.wrap.polygons.size)
        assertEquals(65f, wrapped.wrap.polygons[0].xs.min(), 1f)
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(-1, s.c.doc.indexOf(pasted))
        assertEquals(item, itemOf(text))
    }

    @Test
    fun paintingOnThePictureReflowsInsideTheBrushStep() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        val before = itemOf(text)
        val textBefore = pixels(text.bitmap)
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.BRUSH)
        s.c.brush = s.c.brush.copy(size = 30f)
        val steps = s.c.undoManager.undoCount
        s.c.pointerDown(ToolPoint(200f, 60f))
        for (i in 1..30) s.c.pointerMove(ToolPoint(200f + 3f * i, 60f + 6f * i))
        s.c.pointerUp(ToolPoint(290f, 240f))
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        assertEquals("the step keeps the brush's label", "Brush", s.c.undoManager.undoLabel)
        val after = itemOf(text)
        assertNotEquals(before.wrap.polygons, after.wrap.polygons)
        assertRendered(text, s.c)
        s.c.undo()
        assertEquals(before, itemOf(text))
        assertArrayEquals(textBefore, pixels(text.bitmap))
        val reflows = s.c.textWrap.reflowCount
        s.c.redo()
        assertEquals(after, itemOf(text))
        s.c.undo()
        assertEquals("undo / redo never re-flow", reflows, s.c.textWrap.reflowCount)
    }

    @Test
    fun severalEventsOfOneEditReflowOnce() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        val reflows = s.c.textWrap.reflowCount
        val steps = s.c.undoManager.undoCount
        val p = Paint().apply { color = WrapFixtures.BLACK }
        // Two committed edits of the picture in one step (like a vector stroke's pixels + data).
        s.c.groupUndo("Shape") {
            for (r in listOf(Rect(250, 40, 300, 90), Rect(260, 200, 330, 260))) {
                val rec = s.c.beginEdit(s.picture, EditTarget.CONTENT)
                rec.touch(r)
                Canvas(s.picture.bitmap).drawRect(r, p)
                s.c.commitEdit(rec, "Shape")
            }
        }
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        assertEquals("one re-flow for the step", reflows + 1, s.c.textWrap.reflowCount)
        assertRendered(text, s.c)
        // A mask edit of the picture re-flows too.
        s.picture.mask = BitmapUtils.createLayerBitmap(400, 300).also { it.eraseColor(WrapFixtures.WHITE) }
        s.c.editWholeLayer(s.picture, "Mask", EditTarget.MASK) { m -> Canvas(m).drawRect(0f, 0f, 400f, 120f, Paint().apply { color = WrapFixtures.BLACK }) }
        assertEquals(reflows + 2, s.c.textWrap.reflowCount)
        assertTrue("the hidden part no longer blocks", itemOf(text).wrap.polygons.all { p2 -> p2.ys.min() >= 118f })
    }

    @Test
    fun aVectorEditOfThePictureReflows() {
        val s = setup(context, scope = scope)
        val vector = s.c.addVectorLayer()!!
        val text = wrappedText(s, source = vector)
        assertTrue("an empty vector layer: nothing to wrap around yet", itemOf(text).wrap.polygons.isEmpty())
        val square = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(60f, 100f, sharp = true), VAnchor(160f, 100f, sharp = true), VAnchor(160f, 200f, sharp = true), VAnchor(60f, 200f, sharp = true)), closed = true)),
            polyline = true,
            fill = VPaint.Solid(WrapFixtures.BLACK),
        )
        val steps = s.c.undoManager.undoCount
        assertEquals(1, s.c.vectors.addObjects(vector, listOf(square), "Add square").size)
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        val polys = itemOf(text).wrap.polygons
        assertEquals(1, polys.size)
        assertEquals(60f, polys[0].xs.min(), 1.5f)
        assertEquals(200f, polys[0].ys.max(), 1.5f)
        assertRendered(text, s.c)
        assertTrue("the vector layer stays a vector layer", vector.isVectorLayer)
        s.c.undo()
        assertTrue(itemOf(text).wrap.polygons.isEmpty())
    }

    @Test
    fun lockedTextsStayHiddenOnesFollowAndOpenOnesReflowLive() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 30f)
        val text = wrappedText(s)
        val data = text.textData
        fun scribble(y: Float) = s.c.editWholeLayer(s.picture, "Scribble") { b -> Canvas(b).drawRect(250f, y, 300f, y + 40f, Paint().apply { color = WrapFixtures.BLACK }) }
        text.locked = true
        scribble(20f)
        assertEquals(data, text.textData)
        assertNull("no locked-layer message", s.c.message)
        text.locked = false

        // Hidden: it still follows its picture (right when shown again), inside the edit's step.
        text.visible = false
        val hiddenSteps = s.c.undoManager.undoCount
        val hiddenReflows = s.c.textWrap.reflowCount
        scribble(80f)
        assertEquals("one step", hiddenSteps + 1, s.c.undoManager.undoCount)
        assertEquals(hiddenReflows + 1, s.c.textWrap.reflowCount)
        assertNotEquals(data, text.textData)
        assertNull("no hidden-layer message", s.c.message)
        assertEquals(s.c.textWrap.contours.polygons(s.picture, WrapContour.SHAPE), itemOf(text).wrap.polygons)
        assertRendered(text, s.c)
        assertFalse("still hidden", text.visible)
        val hiddenData = text.textData
        s.c.undo()
        assertEquals("undo restores the hidden text too", data, text.textData)
        s.c.redo()
        assertEquals(hiddenData, text.textData)
        text.visible = true

        // Open in the Text tool: the layer is left alone, the pending text re-flows live.
        s.c.selectTool(ToolId.TEXT)
        assertTrue(s.tool.editLayer(text))
        assertFalse("up to date: opening changes nothing", s.tool.hasUserChanges)
        val pending = s.tool.item!!.wrap.polygons
        val steps = s.c.undoManager.undoCount
        scribble(200f)
        assertEquals(hiddenData, text.textData)
        assertNotEquals("re-flowed live", pending, s.tool.item!!.wrap.polygons)
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        s.tool.commit()
        assertEquals(s.tool.item, null)
        assertEquals(s.c.textWrap.contours.polygons(s.picture, WrapContour.SHAPE), itemOf(text).wrap.polygons)
        assertRendered(text, s.c)
    }

    @Test
    fun aDeletedPictureLeavesTheTextAsItWas() {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 50f)
        val text = wrappedText(s)
        val item = itemOf(text)
        val textPixels = pixels(text.bitmap)
        s.c.deleteLayer(s.picture)
        assertEquals(item, itemOf(text))
        assertArrayEquals(textPixels, pixels(text.bitmap))
        // Edits elsewhere don't touch it.
        s.c.editWholeLayer(s.background, "Fill") { it.eraseColor(0xFFEEEEEE.toInt()) }
        assertEquals(item, itemOf(text))

        // Opened again: it keeps its outline; the sheet says the layer is gone.
        s.c.selectTool(ToolId.TEXT)
        assertTrue(s.tool.editLayer(text))
        assertFalse("loading changes nothing", s.tool.hasUserChanges)
        assertTrue(s.tool.wrapSourceDeleted)
        assertTrue(s.tool.item!!.wrapActive)
        s.tool.setWrapGap(14f)
        // Box contour from the kept outline.
        s.tool.setWrapContour(WrapContour.BOX)
        assertEquals(4, s.tool.item!!.wrap.polygons.single().size)
        s.tool.commit()
        assertTrue(closestInk(text.bitmap, 110f, 150f) >= 50f)
        assertRendered(text, s.c)
    }

    @Test
    fun wrappedTextSurvivesSaveAndLoad() = runBlocking<Unit> {
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 50f)
        val text = wrappedText(s, gap = 9f, sides = WrapSides.BOTH)
        val px = pixels(text.bitmap)
        repo.save(s.c.doc, s.c.compositor.renderThumbnail(64))
        val loaded = repo.load(s.c.doc.id)
        val l2 = loaded.layers.first { it.id == text.id }
        assertEquals(text.textData, l2.textData)
        assertArrayEquals(px, pixels(l2.bitmap))
        val item = itemOf(l2)
        assertEquals(s.picture.id, item.wrap.sourceLayerId)
        assertEquals(9f, item.wrap.gapPx, 0f)
        assertEquals(WrapSides.BOTH, item.wrap.sides)
        assertTrue(item.wrap.polygons.isNotEmpty())
        // Loaded, it renders exactly as before (the outline is stored, not traced again).
        assertArrayEquals(px, pixels(render(item, 400, 300)))
    }
}
