package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
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
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.5 A1: every [VectorLayerOps] operation through the controller entry points (layers window,
 * selection bar), each one step with undo and redo, and vector layers staying vector layers.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLayerOpsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 400
    private val h = 300

    /** Background, then two vector layers ("Vector 1" below "Vector 2"); Vector 2 active. */
    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.layers += Layer(doc.newLayerId(), "Vector 2", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 2
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private val EditorController.v1: Layer get() = doc.layers.first { it.name == "Vector 1" }
    private val EditorController.v2: Layer get() = doc.layers.first { it.name == "Vector 2" }

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, seed: Long = 3L): VStroke {
        val n = 10
        return VStroke(
            0, preset = BrushLibrary.defaultBrush.copy(size = 10f), color = 0xFF2050C0.toInt(), seed = seed, stylus = false,
            points = PackedPoints(FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }, FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }, FloatArray(n) { 1f }),
        )
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, color: Int = 0xFFE04020.toInt()) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(color),
    )

    private fun shape(type: ShapeType, cx: Float, cy: Float) = VShape(
        0, shape = ShapeObject(type, cx = cx, cy = cy, w = 80f, h = 50f, rotation = 15f, style = ShapeStyle.STROKE_FILL, strokeWidth = 4f, strokeColor = 0xFF003060.toInt(), fillColor = 0xFF90C0F0.toInt()),
    )

    private fun rectSelection(r: Rect): Selection {
        val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(m).drawRect(r, Paint().apply { color = 0xFF000000.toInt() })
        return Selection.wrap(m)
    }

    private fun maxDiff(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF)))
        return m
    }

    @Test
    fun rasterizeKeepsThePixelsAndDropsTheObjects() {
        val c = setup()
        c.vectors.addObjects(c.v2, listOf(stroke(20f, 20f, 300f, 200f), box(50f, 150f, 150f, 250f)), "Add")
        val content = c.v2.vector!!
        val pixels = px(c.v2.bitmap)
        assertTrue(VectorLayerOps.rasterize(c, c.v2))
        assertNull(c.v2.vector)
        assertArrayEquals(pixels, px(c.v2.bitmap))
        assertEquals(2, c.undoManager.undoCount)
        assertEquals("Rasterize vector layer", c.undoManager.undoLabel)
        c.undo()
        assertSame(content, c.v2.vector)
        c.redo()
        assertNull(c.v2.vector)
        assertFalse("not a vector layer any more", VectorLayerOps.rasterize(c, c.v2))
    }

    @Test
    fun mergingTwoVectorLayersConcatenatesTheirObjects() {
        val c = setup()
        c.vectors.addObjects(c.v1, listOf(box(20f, 20f, 200f, 160f), stroke(30f, 250f, 380f, 220f)), "Add")
        c.vectors.addObjects(c.v2, listOf(shape(ShapeType.ELLIPSE, 150f, 120f), stroke(40f, 40f, 360f, 280f, seed = 8L), box(250f, 30f, 330f, 90f, 0xFF30B030.toInt())), "Add")
        val lower = c.v1.vector!!
        val upper = c.v2
        val steps = c.undoManager.undoCount
        c.mergeDown(upper)
        assertEquals(-1, c.doc.indexOf(upper))
        val merged = c.v1.vector!!
        assertEquals(5, merged.objects.size)
        assertEquals("lower objects first, upper re-id'd on top", listOf(1L, 2L, 3L, 4L, 5L), merged.objects.map { it.id })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Merge down", c.undoManager.undoLabel)
        assertSame(c.v1, c.activeLayer)
        // The merged cache is the rendering of the merged objects (within a level where upper objects overlap).
        assertTrue(maxDiff(fresh(merged), px(c.v1.bitmap)) <= 2)
        c.undo()
        assertSame(lower, c.v1.vector)
        assertTrue(c.doc.indexOf(upper) >= 0)
        assertEquals(3, upper.vector!!.objects.size)
        c.redo()
        assertEquals(5, c.v1.vector!!.objects.size)
    }

    @Test
    fun mergesThatWouldLookDifferentAreRasterMerges() {
        for (setupUpper in listOf<(Layer) -> Unit>({ it.opacity = 0.5f }, { it.blendMode = LayerBlendMode.MULTIPLY }, { it.mask = BitmapUtils.createMaskBitmap(w, h) })) {
            val c = setup()
            c.vectors.addObjects(c.v1, listOf(box(20f, 20f, 200f, 160f)), "Add")
            c.vectors.addObjects(c.v2, listOf(box(100f, 100f, 300f, 260f)), "Add")
            setupUpper(c.v2)
            c.mergeDown(c.v2)
            assertNull("raster merge", c.v1.vector)
            c.undo()
            assertNotNull(c.v1.vector)
        }
        // The lower layer's opacity would fade the upper objects too.
        val c = setup()
        c.vectors.addObjects(c.v2, listOf(box(100f, 100f, 300f, 260f)), "Add")
        c.v1.opacity = 0.4f
        c.mergeDown(c.v2)
        assertNull(c.v1.vector)
    }

    @Test
    fun duplicateWithASelectionCopiesTheTouchedObjects() {
        val c = setup()
        c.vectors.addObjects(c.v2, listOf(box(20f, 20f, 100f, 100f), box(250f, 150f, 350f, 250f), stroke(20f, 280f, 380f, 280f)), "Add")
        c.setSelection(rectSelection(Rect(240, 140, 300, 200)), recordUndo = false)
        val steps = c.undoManager.undoCount
        val copy = c.duplicateLayer(c.v2)!!
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Duplicate selection", c.undoManager.undoLabel)
        assertEquals(listOf(2L), copy.vector!!.objects.map { it.id })
        assertArrayEquals(fresh(copy.vector!!), px(copy.bitmap))
        assertEquals(c.doc.indexOf(c.v2) + 1, c.doc.indexOf(copy))
        assertSame(copy, c.activeLayer)
        c.undo()
        assertEquals(-1, c.doc.indexOf(copy))
        // Everything touched: the cache is copied.
        c.setSelection(Selection.all(w, h), recordUndo = false)
        val all = c.duplicateLayer(c.v2)!!
        assertEquals(3, all.vector!!.objects.size)
        assertArrayEquals(px(c.v2.bitmap), px(all.bitmap))
        // Nothing touched: an empty vector layer (never a raster partial copy).
        c.undo()
        c.setSelection(rectSelection(Rect(150, 30, 200, 80)), recordUndo = false)
        val none = c.duplicateLayer(c.v2)!!
        assertEquals(VectorContent.EMPTY.objects, none.vector!!.objects)
    }

    @Test
    fun flippingAVectorLayerMirrorsItsObjectsExactly() {
        val c = setup()
        c.vectors.addObjects(c.v2, listOf(shape(ShapeType.RECTANGLE, 100f, 80f), shape(ShapeType.STAR, 280f, 200f), stroke(30f, 250f, 380f, 140f), box(200f, 20f, 260f, 70f)), "Add")
        val before = c.v2.vector!!
        val pixels = px(c.v2.bitmap)
        c.flipLayer(c.v2, horizontal = true)
        val after = c.v2.vector!!
        assertEquals(before.objects.map { it.id }, after.objects.map { it.id })
        assertTrue("a symmetric rectangle stays a shape", after.objects[0] is VShape)
        assertTrue("a star stays a shape", after.objects[1] is VShape)
        assertEquals(400f - 100f, (after.objects[0] as VShape).shape.cx, 1e-3f)
        assertEquals(400f - 30f, (after.objects[2] as VStroke).points.x[0], 1e-3f)
        // The pixels are mirrored exactly; the mirrored objects render to (almost) the same pixels.
        val flipped = px(c.v2.bitmap)
        for (y in 0 until h) for (x in 0 until w) assertEquals(pixels[y * w + (w - 1 - x)], flipped[y * w + x])
        val f = fresh(after)
        var off = 0
        for (i in f.indices) if (abs((f[i] ushr 24) - (flipped[i] ushr 24)) > 40) off++
        assertTrue("$off pixels differ by more than an edge's anti-aliasing", off < 400)
        assertEquals(1 + 1, c.undoManager.undoCount)
        c.undo()
        assertSame(before, c.v2.vector)
        assertArrayEquals(pixels, px(c.v2.bitmap))
        // Twice is the original geometry.
        val twice = VectorLayerOps.flipped(VectorLayerOps.flipped(before, w, h, false)!!, w, h, false)!!
        for ((a, b) in before.objects.zip(twice.objects)) {
            val ba = VectorOps.bounds(a); val bb = VectorOps.bounds(b)
            assertEquals(ba.left, bb.left, 0.05f); assertEquals(ba.bottom, bb.bottom, 0.05f)
        }
    }

    @Test
    fun clearRemovesTheTouchedObjectsOrAll() {
        val c = setup()
        c.vectors.addObjects(c.v2, listOf(box(20f, 20f, 100f, 100f), box(250f, 150f, 350f, 250f), stroke(20f, 280f, 380f, 280f)), "Add")
        val all = c.v2.vector!!
        c.setSelection(rectSelection(Rect(240, 140, 300, 200)), recordUndo = false)
        c.clearLayer(c.v2)
        assertEquals(listOf(1L, 3L), c.v2.vector!!.objects.map { it.id })
        assertArrayEquals(fresh(c.v2.vector!!), px(c.v2.bitmap))
        assertEquals("Clear", c.undoManager.undoLabel)
        // Nothing touched: handled, nothing changes, never rasterized.
        val steps = c.undoManager.undoCount
        c.setSelection(rectSelection(Rect(150, 30, 200, 80)), recordUndo = false)
        c.clearLayer(c.v2)
        assertEquals(steps, c.undoManager.undoCount)
        assertNotNull(c.v2.vector)
        // No selection: every object.
        c.setSelection(null, recordUndo = false)
        c.clearLayer(c.v2)
        assertTrue(c.v2.vector!!.objects.isEmpty())
        assertTrue(px(c.v2.bitmap).all { it == 0 })
        c.undo(); c.undo()
        assertSame(all, c.v2.vector)
        assertArrayEquals(fresh(all), px(c.v2.bitmap))
    }

    @Test
    fun fillAddsAFilledPathOfTheSelectionOutline() {
        val c = setup()
        // A rectangle selection: the path's outline is the selection's pixel edges.
        c.setSelection(rectSelection(Rect(40, 30, 140, 90)), recordUndo = false)
        c.fillLayer(c.v2, 0xFF00AA00.toInt())
        val p = c.v2.vector!!.objects.single() as VPath
        assertEquals(VFillRule.EVENODD, p.fillRule)
        assertEquals(VPaint.Solid(0xFF00AA00.toInt()), p.fill)
        val xs = p.subpaths.flatMap { s -> s.anchors.map { it.x } }
        val ys = p.subpaths.flatMap { s -> s.anchors.map { it.y } }
        assertEquals(40f, xs.min(), 1e-3f); assertEquals(140f, xs.max(), 1e-3f)
        assertEquals(30f, ys.min(), 1e-3f); assertEquals(90f, ys.max(), 1e-3f)
        assertEquals("Fill", c.undoManager.undoLabel)
        val f = px(c.v2.bitmap)
        assertEquals(0xFF00AA00.toInt(), f[60 * w + 90])
        assertEquals(0, f[100 * w + 90])
        // A ring selection: one path with its hole.
        val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(m).drawPath(Path().apply { addCircle(250f, 180f, 80f, Path.Direction.CW); addCircle(250f, 180f, 40f, Path.Direction.CCW); fillType = Path.FillType.EVEN_ODD }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        c.setSelection(Selection.wrap(m), recordUndo = false)
        c.fillLayer(c.v2, 0xFF0000FF.toInt())
        val ring = c.v2.vector!!.objects.last() as VPath
        assertEquals(2, ring.subpaths.size)
        val g = px(c.v2.bitmap)
        assertEquals("inside the ring", 0xFF0000FF.toInt(), g[180 * w + 310])
        assertEquals("the hole", 0, g[180 * w + 250])
        // No selection: the canvas rectangle.
        c.setSelection(null, recordUndo = false)
        c.fillLayer(c.v1, 0xFFFFFF00.toInt())
        assertTrue(px(c.v1.bitmap).all { it == 0xFFFFFF00.toInt() })
        assertEquals(1, c.v1.vector!!.objects.size)
        c.undo()
        assertTrue(c.v1.vector!!.objects.isEmpty())
        assertTrue(px(c.v1.bitmap).all { it == 0 })
    }

    @Test
    fun grayscaleDocumentsKeepTheirVectorLayersConstrained() {
        val c = setup()
        c.doc.colorMode = ColorMode.GRAYSCALE
        c.vectors.addObjects(c.v2, listOf(box(20f, 20f, 200f, 160f, 0xFFE04020.toInt())), "Add")
        val p = c.v2.bitmap.getPixel(100, 100)
        assertEquals((p shr 16) and 0xFF, (p shr 8) and 0xFF)
        assertEquals((p shr 8) and 0xFF, p and 0xFF)
        assertNotNull(c.v2.vector)
    }
}
