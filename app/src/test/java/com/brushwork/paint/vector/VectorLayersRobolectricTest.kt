package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.edit.VectorEditSession
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

/**
 * v1.5 F2 (§5.10 item 10): the synchronous reference [VectorLayers] — addObjects / update with
 * undo and redo keep the cache equal to a fresh render of the content (I1) as one step each (I2),
 * hit tests and touching, and the [VectorEditSession] hole, floating, inner adoption and commit.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLayersRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 320
    private val h = 240

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private val EditorController.vec: Layer get() = doc.layers[1]

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** A fresh render of [content] over the whole document. */
    private fun render(content: VectorContent, exclude: Set<Long> = emptySet()): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), exclude, TipCache())
        return pixels(b)
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, color: Int = 0xFF2050C0.toInt(), seed: Long = 7L): VStroke {
        val n = 12
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) + if (it % 2 == 0) 0f else 3f }
        val ps = FloatArray(n) { 1f }
        return VStroke(0, preset = BrushLibrary.defaultBrush.copy(size = 10f), color = color, seed = seed, stylus = false, points = PackedPoints(xs, ys, ps))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, color: Int = 0xFFE04020.toInt(), opacity: Float = 1f) = VPath(
        0, opacity = opacity,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(color),
        stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 3f),
    )

    private fun ellipse(cx: Float, cy: Float) = VShape(
        0, shape = ShapeObject(ShapeType.ELLIPSE, cx = cx, cy = cy, w = 60f, h = 40f, style = ShapeStyle.STROKE_FILL, strokeWidth = 5f, strokeColor = 0xFF006030.toInt(), fillColor = 0xFF60D090.toInt()),
    )

    // ------------------------------------------------------------------ addObjects / update

    @Test
    fun addObjectsDrawsTheNewObjectsOverTheCacheAsOneStep() {
        val c = setup()
        val layer = c.vec
        val ids = c.vectors.addObjects(layer, listOf(stroke(30f, 40f, 200f, 60f), box(60f, 80f, 140f, 150f)), "Add")
        assertEquals(listOf(1L, 2L), ids)
        assertEquals(1, c.undoManager.undoCount)
        val first = layer.vector!!
        assertEquals(listOf(1L, 2L), first.objects.map { it.id })
        assertArrayEquals(render(first), pixels(layer.bitmap))
        // A second batch lands on top, drawn over the existing cache.
        val more = c.vectors.addObjects(layer, listOf(ellipse(120f, 110f), box(200f, 150f, 280f, 220f, opacity = 0.6f)), "Add")
        assertEquals(listOf(3L, 4L), more)
        assertEquals(2, c.undoManager.undoCount)
        val second = layer.vector!!
        assertArrayEquals(render(second), pixels(layer.bitmap))

        c.undo()
        assertSame(first, layer.vector)
        assertArrayEquals(render(first), pixels(layer.bitmap))
        c.undo()
        assertEquals(VectorContent.EMPTY, layer.vector)
        assertTrue(pixels(layer.bitmap).all { it == 0 })
        c.redo()
        c.redo()
        assertSame(second, layer.vector)
        assertArrayEquals(render(second), pixels(layer.bitmap))
    }

    @Test
    fun addObjectsRefusesRasterLockedAndHiddenLayers() {
        val c = setup()
        assertTrue(c.vectors.addObjects(c.doc.layers[0], listOf(box(10f, 10f, 50f, 50f)), "Add").isEmpty())
        c.vec.locked = true
        assertTrue(c.vectors.addObjects(c.vec, listOf(box(10f, 10f, 50f, 50f)), "Add").isEmpty())
        c.vec.locked = false
        c.vec.visible = false
        assertTrue(c.vectors.addObjects(c.vec, listOf(box(10f, 10f, 50f, 50f)), "Add").isEmpty())
        assertEquals(0, c.undoManager.undoCount)
        assertEquals(VectorContent.EMPTY, c.vec.vector)
        assertTrue(c.vectors.addObjects(c.vec, emptyList(), "Add").isEmpty())
        // Objects entirely off the canvas: a data-only step.
        c.vec.visible = true
        val off = c.vectors.addObjects(c.vec, listOf(box(-200f, -200f, -100f, -100f)), "Add")
        assertEquals(1, off.size)
        assertEquals(1, c.undoManager.undoCount)
        assertTrue(pixels(c.vec.bitmap).all { it == 0 })
    }

    @Test
    fun updateReRendersWhatChangedAsOneStepWithUndoAndRedo() {
        val c = setup()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(stroke(30f, 40f, 200f, 60f), box(60f, 80f, 140f, 150f), ellipse(110f, 120f)), "Add")
        val s0 = layer.vector!!
        val states = arrayListOf(s0)
        var applied: Boolean? = null

        // Replace the box with a moved copy (same id, same z).
        val moved = (s0.byId(2) as VPath).let { p -> VectorOps.transformed(p, floatArrayOf(1f, 0f, 70f, 0f, 1f, 30f, 0f, 0f, 1f)) }
        val s1 = s0.replaced(mapOf(2L to listOf(moved)))
        c.vectors.update(layer, s1, "Move") { applied = it }
        assertEquals(true, applied)
        assertSame(s1, layer.vector)
        assertEquals(2, c.undoManager.undoCount)
        assertArrayEquals(render(s1), pixels(layer.bitmap))
        states += s1

        // Delete the stroke.
        val s2 = s1.without(setOf(1L))
        c.vectors.update(layer, s2, "Delete")
        assertArrayEquals(render(s2), pixels(layer.bitmap))
        states += s2

        // Z-order: the ellipse goes to the bottom.
        val s3 = s2.copy(objects = s2.objects.sortedBy { if (it.id == 3L) 0 else 1 })
        c.vectors.update(layer, s3, "To back")
        assertEquals(listOf(3L, 2L), layer.vector!!.objects.map { it.id })
        assertArrayEquals(render(s3), pixels(layer.bitmap))
        states += s3
        assertEquals(4, c.undoManager.undoCount)

        for (i in states.indices.reversed().drop(1)) {
            c.undo()
            assertSame(states[i], layer.vector)
            assertArrayEquals("undo to state $i", render(states[i]), pixels(layer.bitmap))
        }
        for (i in 1 until states.size) {
            c.redo()
            assertSame(states[i], layer.vector)
            assertArrayEquals("redo to state $i", render(states[i]), pixels(layer.bitmap))
        }
    }

    @Test
    fun updateWithDirtyTilesAndRefusals() {
        val c = setup()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(box(20f, 20f, 100f, 100f), box(180f, 120f, 300f, 220f)), "Add")
        val s0 = layer.vector!!
        // Recolor the second box; the caller names the tiles that change (two of them).
        val recolored = (s0.byId(2) as VPath).copy(fill = VPaint.Solid(0xFF3060F0.toInt()))
        val s1 = s0.replaced(mapOf(2L to listOf(recolored)))
        c.vectors.update(layer, s1, "Recolor", dirty = listOf(Rect(170, 110, 256, 230), Rect(256, 110, 310, 230)))
        assertArrayEquals(render(s1), pixels(layer.bitmap))
        assertEquals(2, c.undoManager.undoCount)

        // The same content: applied, no step.
        var applied: Boolean? = null
        c.vectors.update(layer, s1, "Nothing") { applied = it }
        assertEquals(true, applied)
        assertEquals(2, c.undoManager.undoCount)
        // An equal copy: the same (no re-render, no step, the layer keeps its instance).
        applied = null
        c.vectors.update(layer, s1.copy(objects = s1.objects.toList()), "Equal") { applied = it }
        assertEquals(true, applied)
        assertEquals(2, c.undoManager.undoCount)
        assertSame(s1, layer.vector)

        // Locked: refused, nothing changes.
        layer.locked = true
        c.vectors.update(layer, s0, "Undo recolor") { applied = it }
        assertEquals(false, applied)
        assertSame(s1, layer.vector)
        assertEquals(2, c.undoManager.undoCount)
        layer.locked = false

        // Not a vector layer.
        c.vectors.update(c.doc.layers[0], s0, "No") { applied = it }
        assertEquals(false, applied)
        assertNull(c.doc.layers[0].vector)
    }

    @Test
    fun appendDataAndAddObjectsAgreeWithAFreshRender() {
        // A live brush stroke commits its pixels and appends the data (A3's pattern); later
        // re-renders of other objects keep the stroke's pixels equal to its replay.
        val c = setup()
        val layer = c.vec
        val s = stroke(40f, 50f, 260f, 90f)
        c.groupUndo("Brush") {
            c.keepLayerData(layer) {
                val rec = c.beginEdit(layer)
                rec.touch(Rect(0, 0, w, h))
                VectorLayerRenderer.render(Canvas(layer.bitmap), VectorContent(objects = listOf(s)), Rect(0, 0, w, h), tips = TipCache())
                c.commitEdit(rec, "Brush")
            }
            c.vectors.appendData(layer, listOf(s), "Brush")
        }
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(1, layer.vector!!.objects.size)
        c.vectors.addObjects(layer, listOf(box(100f, 60f, 160f, 120f)), "Add")
        val after = layer.vector!!.without(setOf(2L))
        c.vectors.update(layer, after, "Delete")
        assertArrayEquals(render(after), pixels(layer.bitmap))
    }

    @Test
    fun partialReRendersOfLargeCurvedObjectsEqualAFreshRender() {
        // Skia's anti-aliasing of a path depends on where the clip cuts it, so dirty regions that
        // cut through large curved, rotated and faded objects must still leave the cache exactly
        // equal to a fresh render (re-renders cover whole renderer tiles, I1).
        val dw = 900
        val dh = 700
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", dw, dh)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(dw, dh)).also { it.vector = VectorContent.EMPTY }
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val layer = doc.layers[0]
        fun px(b: Bitmap) = IntArray(dw * dh).also { b.getPixels(it, 0, dw, 0, 0, dw, dh) }
        fun fresh(content: VectorContent): IntArray {
            val b = BitmapUtils.createLayerBitmap(dw, dh)
            VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, dw, dh), tips = TipCache())
            return px(b)
        }
        val ring = VShape(
            0, shape = ShapeObject(ShapeType.ELLIPSE, cx = 450f, cy = 350f, w = 760f, h = 470f, rotation = 23f, style = ShapeStyle.STROKE_FILL, strokeWidth = 9f, strokeColor = 0xFF203060.toInt(), fillColor = 0xFFB0D0F0.toInt()),
        )
        val wave = VPath(
            0, subpaths = listOf(VSubpath(listOf(VAnchor(30f, 600f), VAnchor(250f, 80f), VAnchor(520f, 640f), VAnchor(870f, 90f)))), tension = 0.2f,
            stroke = VStrokeStyle(color = 0xFF802010.toInt(), width = 7f),
        )
        val blob = VPath(
            0, opacity = 0.55f,
            subpaths = listOf(VSubpath(listOf(VAnchor(150f, 150f), VAnchor(760f, 200f), VAnchor(700f, 560f), VAnchor(180f, 520f)), closed = true)),
            fill = VPaint.Radial(450f, 350f, 330f, listOf(VStop(0f, 0xFFF0E040.toInt()), VStop(1f, 0xFF40A060.toInt()))),
        )
        val star = VShape(0, opacity = 0.7f, shape = ShapeObject(ShapeType.STAR, cx = 420f, cy = 330f, w = 300f, h = 280f, rotation = 11f, style = ShapeStyle.STROKE_FILL, strokeWidth = 5f))
        c.vectors.addObjects(layer, listOf(ring, blob, wave, star, box(100f, 300f, 140f, 340f)), "Add")
        assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
        // Small edits whose regions cut through the large objects.
        val moves = listOf(floatArrayOf(1f, 0f, 37f, 0f, 1f, 11f, 0f, 0f, 1f), floatArrayOf(1f, 0f, 260f, 0f, 1f, -90f, 0f, 0f, 1f), floatArrayOf(1f, 0f, 133f, 0f, 1f, 171f, 0f, 0f, 1f))
        for (m in moves) {
            val content = layer.vector!!
            c.vectors.update(layer, content.replaced(mapOf(5L to listOf(VectorOps.transformed(content.byId(5)!!, m)))), "Move")
            assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
        }
        val s = layer.vector!!
        c.vectors.update(layer, s.copy(objects = s.objects.sortedBy { if (it.id == 5L) 0 else 1 }), "To back")
        assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
        c.vectors.update(layer, layer.vector!!.without(setOf(4L)), "Delete")
        assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
        repeat(3) { c.undo() }
        assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
        repeat(3) { c.redo() }
        assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
    }

    @Test
    fun liveStrokesStayEqualToTheirReplayUnderPartialReRenders() {
        // Strokes drawn live (random seeds) and kept as objects (A3's commit pattern), then edits
        // whose re-rendered regions cut through them: the cache stays exactly a fresh render.
        val dw = 800
        val dh = 600
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", dw, dh)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(dw, dh)).also { it.vector = VectorContent.EMPTY }
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val layer = doc.layers[0]
        fun px(b: Bitmap) = IntArray(dw * dh).also { b.getPixels(it, 0, dw, 0, 0, dw, dh) }
        fun fresh(content: VectorContent): IntArray {
            val b = BitmapUtils.createLayerBitmap(dw, dh)
            VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, dw, dh), tips = TipCache())
            return px(b)
        }
        c.selectTool(com.brushwork.paint.tools.ToolId.BRUSH)
        val brush = c.tools.getValue(com.brushwork.paint.tools.ToolId.BRUSH) as com.brushwork.paint.brush.BrushTool
        brush.strokeHook = { info ->
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ps = ArrayList<Float>()
            com.brushwork.paint.brush.StrokeHook.Record(object : com.brushwork.paint.brush.StrokeRecorder {
                override val replacesStroke = false
                override val ignoresSelection = true
                override fun point(x: Float, y: Float, rawPressure: Float) { xs += x; ys += y; ps += rawPressure }
                override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
                    val s = VStroke(0, preset = info.preset, color = info.color, seed = info.seed, stylus = info.isStylus, points = PackedPoints(xs.toFloatArray(), ys.toFloatArray(), ps.toFloatArray()))
                    var ok = false
                    c.groupUndo(label) {
                        ok = c.keepLayerData(info.layer) { commitPixels() }
                        c.vectors.appendData(info.layer, listOf(s), label)
                    }
                    return ok
                }
                override fun cancel() {}
            })
        }
        for ((i, id) in listOf("chalk", "softround", "pencil", "airbrush", "gpen").withIndex()) {
            c.brush = BrushLibrary.byId(id)!!
            val stylus = i % 2 == 1
            val pts = List(40) { k ->
                val t = k / 39f
                com.brushwork.paint.tools.ToolPoint(40f + 700f * t, 80f + 100f * i + 60f * kotlin.math.sin(t * 9f), if (stylus) 0.3f + 0.7f * t else 0.5f, k.toLong(), isStylus = stylus)
            }
            brush.onDown(pts.first())
            for (p in pts.subList(1, pts.size - 1)) brush.onMove(p)
            brush.onUp(pts.last())
        }
        assertEquals(5, layer.vector!!.objects.size)
        assertEquals(5, c.undoManager.undoCount)
        assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
        // A small box moved across the strokes: each re-render cuts through some of them.
        c.vectors.addObjects(layer, listOf(box(100f, 100f, 140f, 150f)), "Add")
        for (m in listOf(floatArrayOf(1f, 0f, 133f, 0f, 1f, 57f, 0f, 0f, 1f), floatArrayOf(1f, 0f, 251f, 0f, 1f, 181f, 0f, 0f, 1f), floatArrayOf(1f, 0f, -300f, 0f, 1f, 90f, 0f, 0f, 1f))) {
            val content = layer.vector!!
            c.vectors.update(layer, content.replaced(mapOf(6L to listOf(VectorOps.transformed(content.byId(6)!!, m)))), "Move")
            assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
        }
        // Deleting a stroke re-renders only its area: the others there are replayed.
        c.vectors.update(layer, layer.vector!!.without(setOf(2L)), "Delete")
        assertArrayEquals(fresh(layer.vector!!), px(layer.bitmap))
    }

    @Test
    fun aListenersAmendJoinsTheVectorEditsStep() {
        // I2: an edit listener that records a follow-up step (text wrap re-flow) amends the step
        // of addObjects / update / appendData / an edit-session commit: each stays ONE step,
        // and one undo takes back both.
        val c = setup()
        val layer = c.vec
        var followUps = 0
        var undone = 0
        val listener = com.brushwork.paint.EditListener { e ->
            if (e.layer !== layer) return@EditListener
            c.amendLastStep {
                c.pushUndo(object : com.brushwork.paint.engine.UndoAction {
                    override val label = "Follow-up"
                    override val byteSize = 0L
                    override fun undo(c: EditorController) { undone++ }
                    override fun redo(c: EditorController) { undone-- }
                })
            }
            followUps++
        }
        c.addEditListener(listener)
        c.vectors.addObjects(layer, listOf(box(40f, 40f, 120f, 120f), stroke(30f, 160f, 280f, 200f)), "Add")
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(1, followUps)
        // Off the canvas: the data-only step is amended too.
        c.vectors.addObjects(layer, listOf(box(-300f, -300f, -200f, -200f)), "Add")
        assertEquals(2, c.undoManager.undoCount)
        assertEquals(2, followUps)
        val s0 = layer.vector!!
        c.vectors.update(layer, s0.without(setOf(1L)), "Delete")
        assertEquals(3, c.undoManager.undoCount)
        assertEquals(3, followUps)
        // A live brush stroke (A3's pattern): pixels kept as data, then the data appended. Both
        // edits are reported; both follow-ups join the one step.
        val s = stroke(60f, 60f, 200f, 90f, seed = 3L)
        c.groupUndo("Brush") {
            c.keepLayerData(layer) {
                val rec = c.beginEdit(layer)
                rec.touch(Rect(0, 0, w, h))
                VectorLayerRenderer.render(Canvas(layer.bitmap), VectorContent(objects = listOf(s)), Rect(0, 0, w, h), tips = TipCache())
                c.commitEdit(rec, "Brush")
            }
            c.vectors.appendData(layer, listOf(s), "Brush")
        }
        assertEquals(4, c.undoManager.undoCount)
        assertEquals(5, followUps)
        var session: VectorEditSession? = null
        c.vectors.beginEdit(layer, setOf(2L)) { session = it }
        session!!.commit(listOf(VectorOps.transformed(layer.vector!!.byId(2)!!, floatArrayOf(1f, 0f, 10f, 0f, 1f, 5f, 0f, 0f, 1f))), "Edit")
        assertEquals(5, c.undoManager.undoCount)
        assertEquals(6, followUps)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        // Undo / redo fire no listeners and take back each step with its follow-up.
        c.undo()
        assertEquals(1, undone)
        assertEquals(6, followUps)
        c.undo()
        assertEquals(3, undone)
        c.redo()
        c.redo()
        assertEquals(0, undone)
        assertEquals(6, followUps)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
        c.removeEditListener(listener)
    }

    // ------------------------------------------------------------------ hit tests, touching

    @Test
    fun hitTestFindsTheTopmostAndCyclesThroughOverlaps() {
        val c = setup()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(box(40f, 40f, 140f, 140f), box(100f, 100f, 200f, 200f), stroke(10f, 220f, 300f, 220f)), "Add")
        val p = Vec2(120f, 120f) // inside both boxes
        assertEquals(2L, c.vectors.hitTest(layer, p, 2f)?.id)
        assertEquals(1L, c.vectors.hitTest(layer, p, 2f, below = 2L)?.id)
        // Nothing under the bottom one: back to the top.
        assertEquals(2L, c.vectors.hitTest(layer, p, 2f, below = 1L)?.id)
        assertEquals(3L, c.vectors.hitTest(layer, Vec2(150f, 223f), 1f)?.id)
        assertNull(c.vectors.hitTest(layer, Vec2(300f, 20f), 4f))
        // The tolerance reaches a nearby object.
        assertNull(c.vectors.hitTest(layer, Vec2(150f, 236f), 1f))
        assertEquals(3L, c.vectors.hitTest(layer, Vec2(150f, 236f), 10f)?.id)
        assertNull(c.vectors.hitTest(c.doc.layers[0], p, 2f))
    }

    @Test
    fun touchingListsTheObjectsUnderTheSelection() {
        val c = setup()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(box(20f, 20f, 80f, 80f), box(200f, 20f, 260f, 80f), stroke(20f, 200f, 300f, 200f)), "Add")
        fun sel(r: Rect): Selection {
            val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(m).drawRect(r, Paint().apply { color = 0xFF000000.toInt() })
            return Selection.wrap(m)
        }
        assertEquals(setOf(1L), c.vectors.touching(layer, sel(Rect(50, 50, 120, 120))))
        assertEquals(setOf(1L, 2L), c.vectors.touching(layer, sel(Rect(70, 30, 210, 40))))
        assertEquals(setOf(3L), c.vectors.touching(layer, sel(Rect(150, 195, 160, 205))))
        // Between the objects: nothing.
        assertEquals(emptySet<Long>(), c.vectors.touching(layer, sel(Rect(120, 120, 180, 160))))
        assertEquals(emptySet<Long>(), c.vectors.touching(layer, Selection.empty(w, h)))
    }

    // ------------------------------------------------------------------ edit sessions

    /** The session's preview of the layer content (document px). */
    private fun drawn(s: VectorEditSession): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        assertTrue(s.drawContent(Canvas(b)))
        return pixels(b)
    }

    private fun threeObjects(c: EditorController): VectorContent {
        // The middle box overlaps both others.
        c.vectors.addObjects(c.vec, listOf(box(30f, 30f, 130f, 130f), box(90f, 90f, 200f, 170f, color = 0xFF2080E0.toInt()), stroke(60f, 150f, 280f, 120f)), "Add")
        return c.vec.vector!!
    }

    @Test
    fun anEditSessionShowsTheOtherObjectsInTheHoleAndTheEditedOnesFloating() {
        val c = setup()
        val content = threeObjects(c)
        val steps = c.undoManager.undoCount
        val cacheBefore = pixels(c.vec.bitmap)
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.vec, setOf(2L)) { session = it }
        val s = session
        assertNotNull(s)
        s!!
        assertSame(s, c.renderOverride)
        assertNull(s.inner)
        assertEquals(setOf(2L), s.ids)
        assertEquals(1f, s.floatingScale)
        // The hole: exactly the layer without the edited object.
        assertArrayEquals(render(content, exclude = setOf(2L)), drawn(s))
        // The floating bitmap: the edited object alone, where floatingRect says.
        val fl = s.floating!!
        val fr = s.floatingRect
        assertEquals(fr.width(), fl.width)
        assertEquals(fr.height(), fl.height)
        val alone = render(VectorContent(objects = listOf(content.byId(2)!!)))
        val flPx = IntArray(fl.width * fl.height).also { fl.getPixels(it, 0, fl.width, 0, 0, fl.width, fl.height) }
        var painted = 0
        for (y in 0 until fl.height) for (x in 0 until fl.width) {
            val dx = fr.left + x; val dy = fr.top + y
            val expect = if (dx in 0 until w && dy in 0 until h) alone[dy * w + dx] else 0
            assertEquals("floating ($x, $y)", expect, flPx[y * fl.width + x])
            if (expect != 0) painted++
        }
        assertTrue(painted > 1000)
        // The preview is drawn over the hole; the layer is untouched.
        s.drawPreview = { cv -> cv.drawRect(Rect(0, 0, 10, 10), Paint().apply { color = 0xFFFF0000.toInt() }) }
        assertEquals(0xFFFF0000.toInt(), drawn(s)[5 * w + 5])
        assertArrayEquals(cacheBefore, pixels(c.vec.bitmap))
        assertSame(content, c.vec.vector)
        assertEquals(steps, c.undoManager.undoCount)

        s.cancel()
        assertFalse(s.isOpen)
        assertNull(c.renderOverride)
        assertSame(content, c.vec.vector)
        assertArrayEquals(cacheBefore, pixels(c.vec.bitmap))
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun aPaintingToolsLiveOverrideIsAdoptedWithoutTheOldPixels() {
        val c = setup()
        val content = threeObjects(c)
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.vec, setOf(2L)) { session = it }
        val s = session!!
        // A painting tool installs its live stroke override (it draws the layer's bitmap under its stroke).
        val live = object : LayerRenderOverride {
            override val layer: Layer get() = c.vec
            override fun drawContent(canvas: Canvas): Boolean {
                canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                canvas.drawRect(Rect(300, 0, 310, 10), Paint().apply { color = 0xFF00FF00.toInt() })
                return true
            }
        }
        c.renderOverride = live
        s.adoptInner()
        assertSame(s, c.renderOverride)
        assertSame(live, s.inner)
        val px = drawn(s)
        val expected = render(content, exclude = setOf(2L))
        for (i in px.indices) {
            val x = i % w; val y = i / w
            if (x in 300 until 310 && y < 10) assertEquals(0xFF00FF00.toInt(), px[i]) else assertEquals("($x, $y)", expected[i], px[i])
        }
        // Calling it again changes nothing; cancelling gives the painting tool its override back.
        s.adoptInner()
        assertSame(live, s.inner)
        s.cancel()
        assertSame(live, c.renderOverride)
        // A closed session adopts nothing.
        s.adoptInner()
        assertSame(live, c.renderOverride)
    }

    @Test
    fun committingReplacesTheEditedObjectsInPlaceAsOneStep() {
        val c = setup()
        val content = threeObjects(c)
        val steps = c.undoManager.undoCount
        val cacheBefore = pixels(c.vec.bitmap)
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.vec, setOf(2L)) { session = it }
        val moved = VectorOps.transformed(content.byId(2)!!, floatArrayOf(1f, 0f, 40f, 0f, 1f, -50f, 0f, 0f, 1f))
        var done: Boolean? = null
        session!!.commit(listOf(moved), "Edit path") { done = it }
        assertEquals(true, done)
        assertNull(c.renderOverride)
        val after = c.vec.vector!!
        assertEquals(listOf(1L, 2L, 3L), after.objects.map { it.id })
        assertEquals(moved, after.byId(2))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertArrayEquals(render(after), pixels(c.vec.bitmap))
        // A committed session does nothing more.
        session!!.commit(listOf(moved), "Again") { done = it }
        assertEquals(false, done)
        assertEquals(steps + 1, c.undoManager.undoCount)
        c.undo()
        assertSame(content, c.vec.vector)
        assertArrayEquals(cacheBefore, pixels(c.vec.bitmap))
    }

    @Test
    fun extraReplacementsGetNewIdsRightAboveTheEditedObjects() {
        val c = setup()
        val content = threeObjects(c)
        val extra = box(220f, 20f, 300f, 60f).copy(id = 999L)
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.vec, setOf(1L, 2L)) { session = it }
        // Object 1 is replaced, object 2 has no replacement (removed), the extra is new.
        session!!.commit(listOf(content.byId(1)!!, extra), "Split")
        val a = c.vec.vector!!
        assertEquals(listOf(1L, 4L, 3L), a.objects.map { it.id })
        assertEquals(5L, a.nextId)
        assertArrayEquals(render(a), pixels(c.vec.bitmap))

        // The topmost edited object without a replacement does not hand its id to a new piece.
        c.vectors.beginEdit(c.vec, setOf(4L)) { session = it }
        session!!.commit(listOf(box(10f, 180f, 60f, 230f).copy(id = 4242L)), "Replace")
        val b = c.vec.vector!!
        assertEquals(listOf(1L, 5L, 3L), b.objects.map { it.id })
        assertArrayEquals(render(b), pixels(c.vec.bitmap))
    }

    @Test
    fun liftingEveryObjectUsesTheCacheAndAnEmptyHole() {
        val c = setup()
        val content = threeObjects(c)
        var session: VectorEditSession? = null
        c.vectors.beginEdit(c.vec, content.objects.map { it.id }.toSet()) { session = it }
        val s = session!!
        assertTrue(drawn(s).all { it == 0 })
        val fl = s.floating!!
        val fr = s.floatingRect
        val cache = pixels(c.vec.bitmap)
        for (y in 0 until fl.height) for (x in 0 until fl.width) {
            val dx = fr.left + x; val dy = fr.top + y
            val expect = if (dx in 0 until w && dy in 0 until h) cache[dy * w + dx] else 0
            assertEquals(expect, fl.getPixel(x, y))
        }
        s.cancel()
    }

    @Test
    fun editSessionsAreRefusedWhenThereIsNothingToEdit() {
        val c = setup()
        threeObjects(c)
        fun begin(layer: Layer, ids: Set<Long>): VectorEditSession? {
            var s: VectorEditSession? = null
            var called = false
            c.vectors.beginEdit(layer, ids) { s = it; called = true }
            assertTrue(called)
            return s
        }
        assertNull(begin(c.doc.layers[0], setOf(1L)))
        assertNull(begin(c.vec, emptySet()))
        assertNull(begin(c.vec, setOf(77L)))
        c.vec.locked = true
        assertNull(begin(c.vec, setOf(1L)))
        c.vec.locked = false
        assertNull(c.renderOverride)
        // Ids that are gone are ignored.
        assertEquals(setOf(1L), begin(c.vec, setOf(1L, 77L))!!.ids)
    }

    @Test
    fun theObjectSelectionFollowsTheLayer() {
        val c = setup()
        threeObjects(c)
        c.vectors.setSelection(c.vec, setOf(1L, 3L))
        assertSame(c.vec, c.vectors.selectedLayer)
        assertEquals(setOf(1L, 3L), c.vectors.selectedIds)
        c.vectors.update(c.vec, c.vec.vector!!.without(setOf(3L)), "Delete")
        assertEquals(setOf(1L), c.vectors.selectedIds)
        c.vectors.setSelection(null, setOf(1L))
        assertNull(c.vectors.selectedLayer)
        assertTrue(c.vectors.selectedIds.isEmpty())
        c.vectors.dispose()
        assertFalse(c.vectors.isRendering)
    }
}
