package com.brushwork.paint.v19.a5

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ErasableTool
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.VectorPreview
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.DoomPreview
import com.brushwork.paint.vector.draw.EraseSource
import com.brushwork.paint.vector.draw.ToolErase
import com.brushwork.paint.vector.draw.ToolEraseResult
import com.brushwork.paint.vector.draw.ToolEraseSession
import com.brushwork.paint.vector.draw.VectorDrawState
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.draw.VectorStrokeCapture
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 (R11 a and b, A5 step 6): a tool erases with its own outline ([ToolErase], driven here by
 * a fake [ErasableTool], as Curve and Shape drive it). Its footprint is what the tool would paint
 * (the stroke at its width, plus the fill of a filled closed outline); the layer shows the erase
 * live through the render override, exactly as the commit leaves it; the commit is ONE step with
 * the tool's label. Raster, text and mask targets lose pixels (inside the selection); vector and
 * legacy shape layers lose objects in the Eraser's three modes. A cancel leaves no trace;
 * whatever can't be erased is refused with a message.
 */
@RunWith(RobolectricTestRunner::class)
class ToolEraseSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 300
    private val h = 220
    private val red = 0xFFD02020.toInt()
    private val blue = 0xFF3070C0.toInt()

    /** One layer, active: "raster" (red), "text" (red pixels of a text), "vector" (no objects yet). */
    private fun setup(kind: String = "raster"): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)).also { l ->
            when (kind) {
                "raster" -> l.bitmap.eraseColor(red)
                "text" -> { l.textData = "{}"; l.bitmap.eraseColor(red) }
                "vector" -> l.vector = VectorContent.EMPTY
            }
        }
        doc.activeLayerIndex = 0
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private val EditorController.layer: Layer get() = doc.layers[0]

    /**
     * A tool that erases with its outline, as Curve, Polyline, Path and Shape do (A1, A9): erase
     * mode on, its outline opens the session and shows the erase; its commit ends it.
     */
    private class FakeOutlineTool(private val c: EditorController, private val source: EraseSource, private val step: String) : ErasableTool {
        override var eraseOn = false
            private set

        override fun setErase(on: Boolean) {
            if (!on) cancel()
            eraseOn = on
        }

        override val eraseLabel = EraseLabels19.WITH_CURVE
        override val erasingLabel = EraseLabels19.ERASING_CURVE

        var session: ToolEraseSession? = null
            private set

        /** The outline changed: the session (opened by the first one) shows its erase; null when refused. */
        fun outline(p: Path, width: Float, filled: Boolean = false): ToolEraseSession? {
            check(eraseOn)
            val s = session ?: ToolErase.begin(c, c.doc.activeLayer, source)?.also { session = it } ?: return null
            s.update(p, width, filled)
            return s
        }

        fun commit(): ToolEraseResult {
            val s = session ?: return ToolEraseResult.Nothing
            session = null
            return s.commit(step)
        }

        fun cancel() {
            session?.cancel()
            session = null
        }
    }

    private fun tool(c: EditorController, source: EraseSource = EraseSource.CURVE, step: String = EraseLabels19.STEP_LINE) =
        FakeOutlineTool(c, source, step).also { it.setErase(true) }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) = Path().apply { moveTo(x0, y0); lineTo(x1, y1) }

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** What the session shows the layer as, now. */
    private fun shown(s: ToolEraseSession): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        s.drawPreview(Canvas(b))
        return pixels(b)
    }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    /** [actual] is [expected] up to anti-aliasing: every premultiplied channel within 8 of 255. */
    private fun assertNearly(expected: IntArray, actual: IntArray) {
        fun ch(p: Int, k: Int): Int = if (k == 3) p ushr 24 else ((p ushr (8 * k)) and 0xFF) * (p ushr 24) / 255
        for (i in expected.indices) {
            val a = expected[i]
            val b = actual[i]
            val worst = (0 until 4).maxOf { k -> kotlin.math.abs(ch(a, k) - ch(b, k)) }
            assertTrue("(${i % w}, ${i / w}): ${Integer.toHexString(a)} against ${Integer.toHexString(b)}", worst <= 8)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val pen = BrushLibrary.defaultBrush.copy(size = 8f, taperStart = 20f, taperEnd = 20f)

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 31): VStroke {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = pen, color = 0xFF203040.toInt(), seed = 5L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, strokeWidth: Float = 3f) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(blue), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = strokeWidth),
    )

    private fun anchors(o: VObject) = (o as VPath).subpaths.flatMap { it.anchors }

    /** The pieces of a box cut by a vertical footprint at x = 100, 12 wide: their anchors keep clear of it. */
    private fun assertCutAt100(pieces: List<VObject>) {
        assertEquals(2, pieces.size)
        for (p in pieces) {
            assertTrue(p is VPath && p.fill == VPaint.Solid(blue) && !ObjectCopies.hasCopies(p))
        }
        val (left, right) = pieces.sortedBy { p -> anchors(p).minOf { it.x } }
        assertTrue(anchors(left).all { it.x <= 94.05f })
        assertTrue(anchors(right).all { it.x >= 105.95f })
    }

    // ------------------------------------------------------------------ pixels

    @Test
    fun aCurveErasesARasterLayerLiveAndCommitsWhatItShowedInOneStep() {
        val c = setup()
        val drawn = pixels(c.layer.bitmap)
        val steps = c.undoManager.undoCount
        val t = tool(c)
        val s = t.outline(line(100f, 30f, 100f, 200f), 10f)!!
        // Live, through the layer's render override (never the overlay); the layer itself is untouched.
        assertTrue(c.renderOverride is VectorPreview)
        assertSame(c.layer, c.renderOverride!!.layer)
        assertArrayEquals(drawn, pixels(c.layer.bitmap))
        // The outline moves: the preview follows it.
        t.outline(line(150f, 30f, 150f, 200f), 10f)
        val preview = shown(s)
        assertEquals(0, preview[100 * w + 150] ushr 24)
        assertEquals(red, preview[100 * w + 100])
        assertEquals(red, preview[100 * w + 160])
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        assertNull(c.renderOverride)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        assertArrayEquals("the commit is what the preview showed", preview, pixels(c.layer.bitmap))
        c.undo()
        assertArrayEquals(drawn, pixels(c.layer.bitmap))
    }

    @Test
    fun aFilledClosedOutlineErasesItsFillAndItsStroke() {
        val c = setup()
        val t = tool(c, EraseSource.SHAPE, EraseLabels19.STEP_SHAPE)
        val s = t.outline(rect(60f, 60f, 160f, 140f), 6f, filled = true)!!
        val preview = shown(s)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_SHAPE), t.commit())
        val after = c.layer.bitmap
        assertEquals("inside the fill", 0, after.getPixel(110, 100) ushr 24)
        assertEquals("on the stroke, outside the fill", 0, after.getPixel(162, 100) ushr 24)
        assertEquals("beyond the stroke", red, after.getPixel(166, 100))
        assertArrayEquals(preview, pixels(after))
        // Not filled: only the stroke goes.
        c.undo()
        t.outline(rect(60f, 60f, 160f, 140f), 6f, filled = false)
        t.commit()
        assertEquals(red, c.layer.bitmap.getPixel(110, 100))
        assertEquals(0, c.layer.bitmap.getPixel(160, 100) ushr 24)
    }

    @Test
    fun theSelectionLimitsTheEraseOfPixels() {
        val c = setup()
        c.setSelection(Selection.fromPath(rect(0f, 0f, 150f, 220f), w, h, antiAlias = false), recordUndo = false)
        val t = tool(c)
        val s = t.outline(line(20f, 100f, 280f, 100f), 10f)!!
        val preview = shown(s)
        assertEquals(0, preview[100 * w + 100] ushr 24)
        assertEquals(red, preview[100 * w + 200])
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        // (The offscreen layer of the selection is drawn in the commit's tiles: an edge pixel or two may round differently.)
        assertNearly(preview, pixels(c.layer.bitmap))
    }

    @Test
    fun inAMaskItPaintsBlackAsTheEraserDoes() {
        val c = setup()
        c.addMask(c.layer, fromSelection = false)
        c.layer.editingMask = true
        val drawn = pixels(c.layer.bitmap)
        val t = tool(c)
        t.outline(line(100f, 30f, 100f, 200f), 10f)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        val mask = c.layer.mask!!
        assertEquals(0xFF000000.toInt(), mask.getPixel(100, 100))
        assertEquals(-1, mask.getPixel(50, 100))
        assertArrayEquals("the content keeps its pixels", drawn, pixels(c.layer.bitmap))
    }

    @Test
    fun aTextLayerTurnsIntoPixelsInTheEraseStepWithItsToast() {
        val c = setup("text")
        val steps = c.undoManager.undoCount
        c.message = null
        val t = tool(c)
        t.outline(line(100f, 30f, 100f, 200f), 10f)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(c.layer.textData)
        assertNotNull("v1.8's rasterize toast", c.message)
        assertEquals(0, c.layer.bitmap.getPixel(100, 100) ushr 24)
        c.undo()
        assertEquals("{}", c.layer.textData)
        assertEquals(red, c.layer.bitmap.getPixel(100, 100))
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun whatCantBeErasedIsRefusedWithAMessage() {
        // A folder, an adjustment layer without a mask: the tools' own messages.
        run {
            val c = setup()
            c.layer.folder = FolderSpec()
            assertNull(tool(c).outline(line(10f, 10f, 50f, 50f), 4f))
            assertEquals(FolderLabels.PAINT_REFUSAL, c.message)
        }
        run {
            val c = setup()
            c.layer.adjustment = AdjustmentSpec(filterId = "adjust.invert")
            assertNull(tool(c).outline(line(10f, 10f, 50f, 50f), 4f))
            assertEquals(LayerToolRules.ADJUSTMENT_MESSAGE, c.message)
        }
        // A locked layer.
        run {
            val c = setup()
            c.layer.locked = true
            assertNull(tool(c).outline(line(10f, 10f, 50f, 50f), 4f))
            assertTrue("${c.message}", c.message!!.contains(c.layer.name))
        }
        // Pixels under the alpha lock (the Eraser's message); objects ignore it.
        run {
            val c = setup()
            c.layer.alphaLocked = true
            assertNull(tool(c).outline(line(10f, 10f, 50f, 50f), 4f))
            assertEquals(ToolEraseSession.alphaLockedMessage(c.layer), c.message)
            assertNull(c.renderOverride)
        }
        run {
            val c = setup("vector")
            c.layer.alphaLocked = true
            assertNotNull(tool(c).outline(line(10f, 10f, 50f, 50f), 4f))
        }
        // A shape that doesn't decode can't become objects: refused, never rasterized.
        run {
            val c = setup()
            c.layer.shapeData = "not a shape"
            assertNull(tool(c, EraseSource.SHAPE).outline(line(10f, 10f, 50f, 50f), 4f))
            assertEquals(VectorStrokeCapture.SHAPE_UNREADABLE_MESSAGE, c.message)
            assertEquals("not a shape", c.layer.shapeData)
        }
    }

    @Test
    fun aLayerLockedBeforeTheCommitRefusesItAndKeepsItsPixels() {
        val c = setup()
        val drawn = pixels(c.layer.bitmap)
        val steps = c.undoManager.undoCount
        val t = tool(c)
        t.outline(line(100f, 30f, 100f, 200f), 10f)
        c.layer.locked = true
        val r = t.commit()
        assertTrue("$r", r is ToolEraseResult.Refused)
        assertEquals(c.message, (r as ToolEraseResult.Refused).message)
        assertNull(c.renderOverride)
        assertEquals(steps, c.undoManager.undoCount)
        assertArrayEquals(drawn, pixels(c.layer.bitmap))
    }

    @Test
    fun aCancelLeavesNoTrace() {
        for (kind in listOf("raster", "vector")) {
            val c = setup(kind)
            if (kind == "vector") c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f)), "Add")
            val before = c.layer.vector
            val drawn = pixels(c.layer.bitmap)
            val steps = c.undoManager.undoCount
            val t = tool(c)
            t.outline(line(100f, 30f, 100f, 200f), 10f)
            assertNotNull(kind, c.renderOverride)
            // Erase mode off (or the tool's own cancel): the session ends without a step.
            t.setErase(false)
            idle()
            assertNull(kind, c.renderOverride)
            assertSame(kind, before, c.layer.vector)
            assertArrayEquals(kind, drawn, pixels(c.layer.bitmap))
            assertEquals(kind, steps, c.undoManager.undoCount)
        }
    }

    // ------------------------------------------------------------------ objects

    @Test
    fun inObjectModeEveryObjectItTouchesGoesAndThePreviewIsTheResult() {
        val c = setup("vector")
        c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f), stroke(20f, 180f, 280f, 180f), box(200f, 30f, 260f, 90f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val before = c.layer.vector!!
        val drawn = pixels(c.layer.bitmap)
        val steps = c.undoManager.undoCount
        val t = tool(c)
        val s = t.outline(line(100f, 30f, 100f, 200f), 6f)!!
        assertTrue(c.renderOverride is DoomPreview)
        assertSame(before, c.layer.vector)
        val preview = shown(s)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        // (Recorded before the commit returns.)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        assertNull(c.renderOverride)
        val after = c.layer.vector!!
        assertEquals(listOf(before.objects[2]), after.objects)
        assertArrayEquals(render(after), pixels(c.layer.bitmap))
        assertArrayEquals("the preview is the result", pixels(c.layer.bitmap), preview)
        c.undo()
        assertSame(before, c.layer.vector)
        assertArrayEquals(drawn, pixels(c.layer.bitmap))
    }

    @Test
    fun aFilledOutlineAlsoErasesWhatLiesInsideIt() {
        val c = setup("vector")
        c.vectors.addObjects(c.layer, listOf(stroke(120f, 110f, 180f, 110f), stroke(20f, 200f, 280f, 200f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val before = c.layer.vector!!
        val t = tool(c, EraseSource.SHAPE, EraseLabels19.STEP_SHAPE)
        // An ellipse with no stroke: the stroke inside never meets its outline.
        val oval = Path().apply { addOval(60f, 50f, 240f, 170f, Path.Direction.CW) }
        val s = t.outline(oval, 0f, filled = true)!!
        val preview = shown(s)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_SHAPE), t.commit())
        assertEquals(listOf(before.objects[1]), c.layer.vector!!.objects)
        assertArrayEquals(pixels(c.layer.bitmap), preview)
        // Its outline alone (not filled, no stroke) erases nothing.
        val t2 = tool(c, EraseSource.SHAPE, EraseLabels19.STEP_SHAPE)
        t2.outline(line(20f, 200f, 280f, 200f), 0f, filled = false)
        assertEquals(ToolEraseResult.Nothing, t2.commit())
    }

    @Test
    fun partialCutsStrokesAndFillsByTheFootprintInOneStep() {
        val c = setup("vector")
        c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f), stroke(20f, 180f, 280f, 180f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        val steps = c.undoManager.undoCount
        val t = tool(c)
        val s = t.outline(line(100f, 30f, 100f, 200f), 12f)!!
        // The footprint is taken out of the box in the preview.
        assertEquals(0, shown(s)[100 * w + 100] ushr 24)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        // A cut of a few shapes lands before the commit returns.
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(c.renderOverride)
        val after = c.layer.vector!!
        assertCutAt100(after.objects.filterIsInstance<VPath>())
        val strokes = after.objects.filterIsInstance<VStroke>()
        assertEquals(2, strokes.size)
        assertTrue(strokes[0].points.x.all { it < 100f } && strokes[1].points.x.all { it > 100f })
        assertArrayEquals(render(after), pixels(c.layer.bitmap))
    }

    @Test
    fun partialWithAFilledFootprintTakesAwayEverythingInsideIt() {
        val c = setup("vector")
        val inside = stroke(100f, 110f, 200f, 110f)
        val across = stroke(150f, 20f, 150f, 200f)
        c.vectors.addObjects(c.layer, listOf(inside, across, box(200f, 120f, 280f, 200f, strokeWidth = 0f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        val t = tool(c, EraseSource.SHAPE, EraseLabels19.STEP_SHAPE)
        t.outline(rect(60f, 60f, 240f, 160f), 2f, filled = true)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_SHAPE), t.commit())
        val after = c.layer.vector!!.objects
        // The stroke inside is gone; the one across keeps its two ends; the box keeps what lies outside.
        val strokes = after.filterIsInstance<VStroke>()
        assertEquals(2, strokes.size)
        assertTrue(strokes.all { s -> s.points.y.all { it < 60f } || s.points.y.all { it > 160f } })
        val pieces = after.filterIsInstance<VPath>()
        assertEquals(1, pieces.size)
        assertTrue(anchors(pieces[0]).all { it.x >= 240.95f || it.y >= 160.95f })
    }

    @Test
    fun toIntersectionErasesTheTouchedPieceUpToTheCrossing() {
        val c = setup("vector")
        c.vectors.addObjects(c.layer, listOf(stroke(20f, 100f, 280f, 100f), stroke(150f, 20f, 150f, 200f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.TO_INTERSECTION)
        val before = c.layer.vector!!
        val t = tool(c)
        t.outline(line(60f, 80f, 60f, 120f), 6f)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        val after = c.layer.vector!!.objects
        assertEquals(2, after.size)
        assertSame(before.objects[1], after.first { it.id == before.objects[1].id })
        val rest = after.first { it.id != before.objects[1].id } as VStroke
        assertTrue(rest.points.x.all { it >= 140f })
    }

    @Test
    fun aLegacyShapeLayerBecomesObjectsAndIsErasedInOneStep() {
        val c = setup()
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 100f, cy = 100f, w = 120f, h = 80f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, fillColor = blue)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, w, h))!!
        c.selectLayer(shape)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        val data = shape.dataSnapshot()
        val cache = pixels(shape.bitmap)
        val steps = c.undoManager.undoCount
        val t = tool(c, EraseSource.SHAPE, EraseLabels19.STEP_SHAPE)
        t.outline(line(100f, 30f, 100f, 200f), 12f)
        assertTrue(c.renderOverride is DoomPreview)
        assertTrue("nothing changes before the commit", shape.isShapeLayer)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_SHAPE), t.commit())
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_SHAPE, c.undoManager.undoLabel)
        assertNull(shape.shapeData)
        assertTrue(shape.isVectorLayer)
        assertCutAt100(shape.vector!!.objects)
        assertArrayEquals(render(shape.vector!!), pixels(shape.bitmap))
        c.undo()
        assertEquals(data, shape.dataSnapshot())
        assertArrayEquals(cache, pixels(shape.bitmap))
    }

    @Test
    fun anOutlineMovedAwayGivesBackWhatItHid() {
        val c = setup("vector")
        c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val drawn = pixels(c.layer.bitmap)
        val steps = c.undoManager.undoCount
        val t = tool(c)
        val s = t.outline(line(100f, 30f, 100f, 200f), 6f)!!
        assertFalse(shown(s).contentEquals(drawn))
        t.outline(line(250f, 10f, 250f, 40f), 6f)
        assertArrayEquals(drawn, shown(s))
        assertEquals(ToolEraseResult.Nothing, t.commit())
        assertEquals(steps, c.undoManager.undoCount)
        assertNull(c.renderOverride)
    }

    @Test
    fun theCommitErasesInTheModeChosenLast() {
        val c = setup("vector")
        c.vectors.addObjects(c.layer, listOf(box(40f, 60f, 160f, 140f)), "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        val t = tool(c)
        t.outline(line(100f, 30f, 100f, 200f), 12f)
        // The mode cell changes the mode before the commit: Partial, not Object.
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        assertCutAt100(c.layer.vector!!.objects)
    }

    @Test
    fun aCutOfManyShapesLandsFromTheBackground() {
        val c = setup("vector")
        VectorDrawState.of(c).cutDispatcher = Dispatchers.Unconfined
        val n = ToolEraseSession.SYNC_TARGETS + 10
        c.vectors.addObjects(c.layer, List(n) { k -> box(4f * k + 2f, 50f, 4f * k + 4.5f, 150f, strokeWidth = 0f) }, "Add")
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        val before = c.layer.vector!!
        val steps = c.undoManager.undoCount
        val t = tool(c)
        t.outline(line(0f, 100f, 300f, 100f), 10f)
        assertEquals(ToolEraseResult.Done(EraseLabels19.STEP_LINE), t.commit())
        // Not yet: the preview stays until it lands.
        assertSame(before, c.layer.vector)
        assertTrue(c.renderOverride is DoomPreview)
        idle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(EraseLabels19.STEP_LINE, c.undoManager.undoLabel)
        assertEquals(2 * n, c.layer.vector!!.objects.size)
        assertNull(c.renderOverride)
    }
}
