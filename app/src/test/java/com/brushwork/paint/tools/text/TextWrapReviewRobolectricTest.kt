package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.WrapFixtures.LOREM
import com.brushwork.paint.tools.text.WrapFixtures.disc
import com.brushwork.paint.tools.text.WrapFixtures.itemOf
import com.brushwork.paint.tools.text.WrapFixtures.pixels
import com.brushwork.paint.tools.text.WrapFixtures.render
import com.brushwork.paint.tools.text.WrapFixtures.setup
import com.brushwork.paint.tools.text.WrapFixtures.wrappedText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Review fixes of the text wrap (v1.5 §4.1): the wrapped layout depends on the item alone (the
 * committed pixels equal a fresh rendering after any drag), a stale outline is refreshed when the
 * text is opened, wrap keeps its fixed width, the Wrap chip opens the active text layer, tapping
 * away from a text placed in vector mode goes back to the vector layer, and the contour is traced
 * on the cropped grid.
 */
@RunWith(RobolectricTestRunner::class)
class TextWrapReviewRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A disc of radius [r] at ([cx], [cy]) as a 48-point outline. */
    private fun discPolygon(cx: Float, cy: Float, r: Float): WrapPolygon {
        val n = 48
        return WrapPolygon(
            List(n) { cx + r * cos(2.0 * Math.PI * it / n).toFloat() },
            List(n) { cy + r * sin(2.0 * Math.PI * it / n).toFloat() },
        )
    }

    @Test
    fun theWrappedLayoutDependsOnTheItemAlone() {
        // A text sliding down past a disc, laid out reusing the previous position's layout (as a
        // drag does), gives exactly the lines and height of a fresh layout at every position.
        val base = TextItem(
            LOREM, TextSpec(sizePx = 17f, lineSpacing = 1.15f, box = TextBoxSpec(width = 330f)), 200f, 40f,
            wrap = TextWrapSpec(sourceLayerId = 7, polygons = listOf(discPolygon(150f, 200f, 70f)), gapPx = 6f),
        )
        val positions = (0..60).map { 40f + it * 5.5f } + (0..60).map { 370f - it * 6.25f }
        val turns = listOf(0f, 0f, 12f)
        var prev: PreparedText? = null
        for ((i, y) in positions.withIndex()) {
            val item = base.copy(cy = y, cx = 200f + (i % 7) * 3f, rotationDeg = turns[i % turns.size])
            val reused = TextRenderer.prepare(item, prev)
            val fresh = TextRenderer.prepare(item)
            assertEquals("height at $y", fresh.block!!.height, reused.block!!.height, 0f)
            assertEquals("lines at $y", fresh.block!!.wrapLines, reused.block!!.wrapLines)
            prev = reused
        }
    }

    @Test
    fun aDraggedTextCommitsWhatItsItemRenders() {
        val s = setup(context)
        disc(s.picture, 150f, 150f, 60f)
        val tool = s.tool
        tool.startTextAt(200f, 70f)
        tool.setText(LOREM)
        tool.updateSpec { it.copy(sizePx = 15f, color = WrapFixtures.BLACK, box = it.box.copy(width = 330f)) }
        tool.confirmEditor()
        tool.setWrapSource(s.picture)
        assertTrue(tool.item!!.wrapActive)
        // Drag it down across the picture and part of the way back, with many frames.
        s.c.pointerDown(ToolPoint(200f, 70f))
        for (k in 1..40) s.c.pointerMove(ToolPoint(200f + k * 0.5f, 70f + k * 4f))
        for (k in 1..17) s.c.pointerMove(ToolPoint(220f - k * 0.7f, 230f - k * 3.3f))
        s.c.pointerUp(ToolPoint(208f, 174f))
        assertTrue(tool.item!!.cy > 150f)
        val steps = s.c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        val text = s.c.doc.layers.first { it.isTextLayer }
        assertArrayEquals("the layer is the rendering of its stored text (I1)", pixels(render(itemOf(text), 400, 300)), pixels(text.bitmap))
        // What the export sees is what the layer shows.
        val lines = TextExport.lines(itemOf(text))!!
        val wl = TextRenderer.prepare(itemOf(text)).block!!.wrapLines!!.filter { it.end > it.start }
        assertEquals(wl.size, lines.size)
    }

    @Test
    fun aStaleOutlineIsRefreshedWhenTheTextIsOpened() {
        val s = setup(context)
        disc(s.picture, 120f, 150f, 60f)
        // The mask hides the left part of the disc.
        s.picture.mask = BitmapUtils.createLayerBitmap(400, 300).also { m ->
            m.eraseColor(WrapFixtures.WHITE)
            Canvas(m).drawRect(0f, 0f, 120f, 300f, Paint().apply { color = WrapFixtures.BLACK })
        }
        s.picture.markChanged()
        val text = wrappedText(s)
        val stored = itemOf(text)
        assertTrue(stored.wrap.polygons.all { p -> p.xs.min() >= 118f })

        // Opened while up to date: nothing pending changes, a tap away records no step.
        assertTrue(s.tool.editLayer(text))
        assertFalse(s.tool.hasUserChanges)
        s.tool.discard()

        // Switching the mask off is a property change (no edit event): the text can't follow then.
        s.c.setMaskEnabled(s.picture, false)
        assertEquals(stored, itemOf(text))
        // Opened: it wraps around the picture as it is now, and ✓ records that as one step.
        assertTrue(s.tool.editLayer(text))
        assertTrue(s.tool.hasUserChanges)
        val fresh = s.c.textWrap.contours.polygons(s.picture, WrapContour.SHAPE)!!
        assertEquals(fresh, s.tool.item!!.wrap.polygons)
        assertTrue(fresh.any { p -> p.xs.min() < 70f })
        val steps = s.c.undoManager.undoCount
        s.tool.commit()
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        assertEquals("Edit text", s.c.undoManager.undoLabel)
        assertEquals(fresh, itemOf(text).wrap.polygons)
        assertArrayEquals(pixels(render(itemOf(text), 400, 300)), pixels(text.bitmap))
        s.c.undo()
        assertEquals(stored, itemOf(text))
    }

    @Test
    fun wrappedTextKeepsItsFixedWidth() {
        val s = setup(context)
        disc(s.picture, 120f, 150f, 50f)
        val tool = s.tool
        tool.startTextAt(200f, 150f)
        tool.setText("Auto width words")
        tool.confirmEditor()
        assertEquals(0f, tool.item!!.spec.box.width, 0f)
        tool.setWrapSource(s.picture)
        val width = tool.item!!.spec.box.width
        assertTrue("wrap fixes the width", width > 0f)
        tool.setFixedBox(false)
        assertEquals("refused", width, tool.item!!.spec.box.width, 0f)
        assertEquals(TextTool.WRAP_NEEDS_WIDTH, s.c.message)
        // Off: the box may fit the text again.
        tool.setWrapSource(null)
        tool.setFixedBox(false)
        assertEquals(0f, tool.item!!.spec.box.width, 0f)
    }

    @Test
    fun theWrapChipOpensTheActiveTextLayer() {
        val s = setup(context)
        disc(s.picture, 120f, 150f, 50f)
        val text = wrappedText(s)
        assertNull(s.tool.item)
        assertSame(text, s.c.activeLayer)
        s.tool.openWrapSheet()
        assertSame(text, s.tool.editingLayer)
        assertTrue(s.tool.wrapSheetOpen)
        assertEquals(itemOf(text), s.tool.item)
        s.tool.discard()
        assertFalse(s.tool.wrapSheetOpen)

        // A picture layer active and no text: a hint, nothing opens.
        s.c.selectLayer(s.picture)
        s.tool.openWrapSheet()
        assertNull(s.tool.item)
        assertFalse(s.tool.wrapSheetOpen)
        assertTrue(s.c.message!!.startsWith("Tap the canvas"))

        // A locked text layer: the lock message.
        text.locked = true
        s.c.selectLayer(text)
        s.tool.openWrapSheet()
        assertNull(s.tool.item)
        assertTrue(s.c.message!!.contains("locked"))
    }

    @Test
    fun tappingAwayFromATextPlacedInVectorModeGoesBackToTheVectorLayer() {
        val doc = Document("v", "v", 300, 200)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(300, 200)).also { it.bitmap.eraseColor(WrapFixtures.WHITE) }
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(300, 200))
        doc.activeLayerIndex = 1
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.toggleVectorMode()
        val vector = c.activeLayer
        assertTrue(vector.isVectorLayer)
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(80f, 50f)
        tool.setText("First")
        tool.confirmEditor()
        // Tap away: the text is placed (like ✓) and a new one starts where the finger was.
        c.pointerDown(ToolPoint(230f, 160f))
        c.pointerUp(ToolPoint(230f, 160f))
        assertEquals(1, c.doc.layers.count { it.isTextLayer })
        assertTrue("a new text is being typed", tool.editorOpen)
        assertSame("vector mode stays on", vector, c.activeLayer)
        // The new one cancelled: still on the vector layer.
        tool.cancelEditor()
        assertNull(tool.item)
        assertSame(vector, c.activeLayer)
        assertTrue(c.isVectorMode)
    }

    @Test
    fun theContourIsTracedOnTheCroppedGrid() {
        // The same shape at the grid's corner and far inside it: the same outline, moved.
        val shape = listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1, 3 to 1, 1 to 2, 2 to 2, 3 to 2, 4 to 2, 2 to 3, 3 to 3)
        fun grid(gw: Int, gh: Int, ox: Int, oy: Int) = ByteArray(gw * gh).also { g -> for ((x, y) in shape) g[(y + oy) * gw + x + ox] = -1 }
        val atCorner = WrapContourBuilder.polygons(grid(5, 4, 0, 0), 5, 4, 3)
        val inside = WrapContourBuilder.polygons(grid(120, 90, 70, 51), 120, 90, 3)
        assertEquals(1, atCorner.size)
        assertEquals(atCorner.size, inside.size)
        val a = atCorner.single()
        val b = inside.single()
        assertEquals(a.size, b.size)
        for (i in 0 until a.size) {
            assertEquals(a.xs[i] + 70f * 3f, b.xs[i], 1e-3f)
            assertEquals(a.ys[i] + 51f * 3f, b.ys[i], 1e-3f)
        }
        // Nothing covered: no outline.
        assertTrue(WrapContourBuilder.polygons(ByteArray(64 * 48), 64, 48, 2).isEmpty())
        // Covered everywhere: the whole grid.
        val full = WrapContourBuilder.polygons(ByteArray(10 * 8) { -1 }, 10, 8, 1).single()
        assertEquals(-0.5f + 0.5f, full.xs.min(), 0.6f)
        assertEquals(10f, full.xs.max(), 0.6f)
    }

    @Test
    fun aHiddenTextFollowsAPictureMovedWhileItWasHidden() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            hiddenTextFollows(scope)
        } finally {
            scope.cancel()
        }
    }

    private fun hiddenTextFollows(scope: CoroutineScope) {
        val s = setup(context, scope = scope)
        disc(s.picture, 110f, 150f, 50f)
        val text = wrappedText(s)
        val before = itemOf(text)
        text.visible = false
        s.c.selectLayer(s.picture)
        s.c.selectTool(ToolId.TRANSFORM)
        WrapFixtures.idle()
        val tt = s.c.tools.getValue(ToolId.TRANSFORM) as com.brushwork.paint.tools.transform.TransformTool
        val steps = s.c.undoManager.undoCount
        tt.moveBy(170f, 0f)
        tt.commit()
        assertEquals(steps + 1, s.c.undoManager.undoCount)
        val after = itemOf(text)
        assertNotEquals(before.wrap.polygons, after.wrap.polygons)
        text.visible = true
        assertTrue("clear of the moved disc", WrapFixtures.closestInk(text.bitmap, 280f, 150f) >= 50f + 6f - 2f)
        assertArrayEquals(pixels(render(after, 400, 300)), pixels(text.bitmap))
        s.c.undo()
        assertEquals(before, itemOf(text))
    }
}
