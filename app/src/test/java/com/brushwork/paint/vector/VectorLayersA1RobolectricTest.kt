package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * v1.5 A1 (§6 A1 acceptance): tile-set dirty regions, the pure-move fast path ([VectorLayers.ShiftHint]),
 * deleting one of two crossing strokes, z-order moves that repaint one object, and indexed hit tests.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLayersA1RobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setup(w: Int = 900, h: Int = 700): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private val EditorController.layer: Layer get() = doc.layers[0]

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(c: EditorController, content: VectorContent = c.layer.vector!!): IntArray {
        val w = c.doc.width; val h = c.doc.height
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, preset: com.brushwork.paint.brush.BrushPreset = BrushLibrary.defaultBrush.copy(size = 12f), seed: Long = 5L): VStroke {
        val n = 16
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = preset, color = 0xFF2050C0.toInt(), seed = seed, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, color: Int = 0xFFE04020.toInt()) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(color), stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 3f),
    )

    private fun shape(cx: Float, cy: Float) = VShape(
        0, shape = ShapeObject(ShapeType.ELLIPSE, cx = cx, cy = cy, w = 70f, h = 44f, rotation = 17f, style = ShapeStyle.STROKE_FILL, strokeWidth = 5f, strokeColor = 0xFF006030.toInt(), fillColor = 0xFF60D090.toInt()),
    )

    private fun translate(dx: Float, dy: Float) = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    @Test
    fun deletingObjectsReRendersOnlyTheirTiles() {
        val c = setup()
        // Two objects far apart, in tiles (0, 0) and (3, 2).
        c.vectors.addObjects(c.layer, listOf(box(20f, 20f, 120f, 120f), box(800f, 560f, 880f, 650f)), "Add")
        // A marker in a tile between them (no object paints there): a re-render would erase it.
        c.layer.bitmap.setPixel(450, 330, 0xFF00FF00.toInt())
        val content = c.layer.vector!!
        c.vectors.update(c.layer, content.without(setOf(1L, 2L)), "Delete")
        assertEquals("only their tiles were re-rendered", 0xFF00FF00.toInt(), c.layer.bitmap.getPixel(450, 330))
        assertEquals(0, c.layer.bitmap.getPixel(60, 60))
        assertEquals(0, c.layer.bitmap.getPixel(840, 600))
        c.undo()
        assertEquals(0xFF00FF00.toInt(), c.layer.bitmap.getPixel(450, 330))
        assertSame(content, c.layer.vector)
    }

    @Test
    fun movingTheTopObjectToTheBackRepaintsOnlyIt() {
        val c = setup()
        c.vectors.addObjects(c.layer, listOf(box(20f, 20f, 200f, 200f), box(600f, 400f, 800f, 600f), box(100f, 100f, 300f, 300f, 0xFF2040E0.toInt())), "Add")
        c.layer.bitmap.setPixel(700, 500, 0xFF00FF00.toInt()) // inside object 2's tiles only
        val s = c.layer.vector!!
        val back = s.copy(objects = listOf(s.objects[2], s.objects[0], s.objects[1]))
        c.vectors.update(c.layer, back, "To back")
        assertEquals("object 2 was not repainted", 0xFF00FF00.toInt(), c.layer.bitmap.getPixel(700, 500))
        c.layer.bitmap.setPixel(700, 500, fresh(c)[500 * 900 + 700])
        assertArrayEquals(fresh(c), px(c.layer.bitmap))
    }

    @Test
    fun deletingOneOfTwoCrossingStrokesLeavesTheOtherAsIfDrawnAlone() {
        val c = setup()
        for ((id, preset) in listOf("chalk" to BrushLibrary.byId("chalk")!!, "soft" to BrushLibrary.byId("softround")!!.copy(size = 40f), "pen" to BrushLibrary.defaultBrush.copy(size = 14f))) {
            val layer = c.layer
            c.vectors.update(layer, VectorContent.EMPTY, "Clear")
            val a = stroke(60f, 80f, 840f, 620f, preset, seed = 3L)
            val b = stroke(60f, 620f, 840f, 80f, preset, seed = 4L)
            c.vectors.addObjects(layer, listOf(a, b), "Add")
            val content = layer.vector!!
            val survivor = content.objects[1]
            c.vectors.update(layer, content.without(setOf(content.objects[0].id)), "Delete")
            val alone = fresh(c, VectorContent(objects = listOf(survivor)))
            val got = px(layer.bitmap)
            var maxDiff = 0
            for (i in got.indices) maxDiff = max(maxDiff, channelDiff(got[i], alone[i]))
            assertTrue("$id: max diff $maxDiff", maxDiff <= 2)
        }
    }

    private fun channelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    // ------------------------------------------------------------------ the pure-move fast path

    @Test
    fun anIsolatedMoveShiftsTheCachePixelsExactly() {
        val c = setup()
        val layer = c.layer
        c.vectors.addObjects(layer, listOf(stroke(100f, 100f, 260f, 180f), shape(200f, 400f), box(600f, 80f, 700f, 160f)), "Add")
        val before = px(layer.bitmap)
        val s = layer.vector!!
        val (dx, dy) = 37 to 11
        val moved = s.replaced(mapOf(1L to listOf(VectorOps.transformed(s.byId(1)!!, translate(dx.toFloat(), dy.toFloat()))), 2L to listOf(VectorOps.transformed(s.byId(2)!!, translate(dx.toFloat(), dy.toFloat())))))
        var applied: Boolean? = null
        c.vectors.update(layer, moved, "Transform objects", shift = VectorLayers.ShiftHint(setOf(1L, 2L), dx, dy)) { applied = it }
        assertEquals(true, applied)
        assertSame(moved, layer.vector)
        assertEquals(2, c.undoManager.undoCount)
        val after = px(layer.bitmap)
        val w = 900; val h = 700
        // Exactly the old pixels, moved: the strokes' and shape's area shifted, the box untouched.
        val oldArea = Rect(); val bounds = android.graphics.RectF()
        bounds.union(VectorOps.bounds(s.byId(1)!!)); bounds.union(VectorOps.bounds(s.byId(2)!!)); bounds.roundOut(oldArea)
        for (y in 0 until h) for (x in 0 until w) {
            val inNew = oldArea.contains(x - dx, y - dy)
            val inOld = oldArea.contains(x, y)
            val expect = when {
                inNew -> before[(y - dy) * w + (x - dx)]
                inOld -> 0
                else -> before[y * w + x]
            }
            if (expect != after[y * w + x]) throw AssertionError("($x, $y): ${Integer.toHexString(expect)} != ${Integer.toHexString(after[y * w + x])}")
        }
        // ... which is a re-render's pixels but at a few anti-aliased edge pixels.
        val ref = fresh(c)
        var differ = 0
        var maxDiff = 0
        for (i in ref.indices) { val d = channelDiff(ref[i], after[i]); if (d > 0) differ++; maxDiff = max(maxDiff, d) }
        assertTrue("$differ pixels differ", differ < 300)
        c.undo()
        assertArrayEquals(before, px(layer.bitmap))
        assertSame(s, layer.vector)
    }

    @Test
    fun movesTheFastPathCanNotTakeAreReRendered() {
        val c = setup()
        val layer = c.layer
        // Overlapping objects: the moved box's pixels can't be separated from the ellipse's.
        c.vectors.addObjects(layer, listOf(shape(200f, 200f), box(180f, 180f, 260f, 240f), stroke(500f, 400f, 700f, 500f, BrushLibrary.byId("chalk")!!)), "Add")
        val s = layer.vector!!
        val moved = s.replaced(mapOf(2L to listOf(VectorOps.transformed(s.byId(2)!!, translate(40f, 13f)))))
        c.vectors.update(layer, moved, "Move", shift = VectorLayers.ShiftHint(setOf(2L), 40, 13))
        assertArrayEquals("overlapping: re-rendered", fresh(c), px(layer.bitmap))
        // A grain brush moved by a non-multiple of the grain tile: re-rendered (grain is anchored to the document).
        val s2 = layer.vector!!
        val moved2 = s2.replaced(mapOf(3L to listOf(VectorOps.transformed(s2.byId(3)!!, translate(21f, -7f)))))
        c.vectors.update(layer, moved2, "Move", shift = VectorLayers.ShiftHint(setOf(3L), 21, -7))
        assertArrayEquals("grain: re-rendered", fresh(c), px(layer.bitmap))
        // A hint that doesn't describe the change (other objects changed too): re-rendered.
        val s3 = layer.vector!!
        val wrong = s3.replaced(mapOf(1L to listOf((s3.byId(1) as VShape).copy(opacity = 0.5f))))
        c.vectors.update(layer, wrong, "Move", shift = VectorLayers.ShiftHint(setOf(3L), 5, 5))
        assertArrayEquals("not a pure move: re-rendered", fresh(c), px(layer.bitmap))
        // Moving off the canvas edge and back in can't use pixels that were never on the canvas.
        val edge = layer.vector!!.plus(listOf(box(860f, 300f, 950f, 360f))).first.also { c.vectors.update(layer, it, "Add") }
        val id = edge.objects.last().id
        val back = edge.replaced(mapOf(id to listOf(VectorOps.transformed(edge.byId(id)!!, translate(-120f, 0f)))))
        c.vectors.update(layer, back, "Move", shift = VectorLayers.ShiftHint(setOf(id), -120, 0))
        assertArrayEquals("from off the canvas: re-rendered", fresh(c), px(layer.bitmap))
    }

    // ------------------------------------------------------------------ hit tests

    @Test
    fun indexedHitTestsAgreeWithAScanAndStayFast() {
        val c = setup(1024, 1024)
        val r = Random(21)
        val objs = ArrayList<VObject>()
        repeat(2000) {
            val x = r.nextFloat() * 1000f
            val y = r.nextFloat() * 1000f
            objs += when (it % 3) {
                0 -> stroke(x, y, x + r.nextFloat() * 80f - 40f, y + r.nextFloat() * 80f - 40f)
                1 -> box(x, y, x + 5f + r.nextFloat() * 30f, y + 5f + r.nextFloat() * 30f)
                else -> shape(x, y)
            }
        }
        val content = VectorContent.EMPTY.plus(objs).first
        c.layer.restoreData(c.layer.dataSnapshot().copy(vector = content))
        val pts = List(300) { Vec2(r.nextFloat() * 1024f, r.nextFloat() * 1024f) }
        for (p in pts) {
            val scan = content.objects.lastOrNull { VectorOps.hit(it, p, 3f) }
            assertEquals("hit at $p", scan?.id, c.vectors.hitTest(c.layer, p, 3f)?.id)
        }
        // Warm up, then time (relative to a scan of every object, so it holds on any machine):
        // a tap among 2000 objects looks at a few of them only.
        repeat(50) { c.vectors.hitTest(c.layer, pts[it % pts.size], 3f); content.objects.lastOrNull { o -> VectorOps.hit(o, pts[it % pts.size], 3f) } }
        var indexed = Long.MAX_VALUE
        var scanned = Long.MAX_VALUE
        repeat(3) {
            val t0 = System.nanoTime()
            for (p in pts) c.vectors.hitTest(c.layer, p, 3f)
            val t1 = System.nanoTime()
            for (p in pts) content.objects.lastOrNull { VectorOps.hit(it, p, 3f) }
            val t2 = System.nanoTime()
            indexed = minOf(indexed, t1 - t0); scanned = minOf(scanned, t2 - t1)
        }
        println("hit test among 2000 objects: ${"%.3f".format(indexed / 1e6 / pts.size)} ms per tap (a scan: ${"%.3f".format(scanned / 1e6 / pts.size)} ms)")
        assertTrue("indexed ${indexed / 1000} us vs scan ${scanned / 1000} us", indexed * 4 <= scanned)
    }

    @Test
    fun strokeHitsFollowTheDabsNotTheLargestRadius() {
        val c = setup()
        // A finger stroke with a long taper: thin at its ends, full width in the middle.
        val preset = BrushLibrary.defaultBrush.copy(size = 30f, taperStart = 200f, taperEnd = 200f)
        val s = VStroke(0, preset = preset, color = -16777216, seed = 1, stylus = false, points = PackedPoints(floatArrayOf(100f, 400f, 700f), floatArrayOf(300f, 300f, 300f), floatArrayOf(1f, 1f, 1f)))
        c.vectors.addObjects(c.layer, listOf(s), "Add")
        // 12 px off the centre line: inside the full width, outside the thin tip.
        assertNotEquals(null, c.vectors.hitTest(c.layer, Vec2(400f, 312f), 0f))
        assertNull(c.vectors.hitTest(c.layer, Vec2(104f, 312f), 0f))
        assertNotEquals(null, c.vectors.hitTest(c.layer, Vec2(104f, 312f), 12f))
    }
}
