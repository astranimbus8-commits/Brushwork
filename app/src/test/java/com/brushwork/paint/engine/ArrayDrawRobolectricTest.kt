package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
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
import com.brushwork.paint.vector.LayerDataTransforms
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayerOps
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.geom.ObjectIndex
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.sin

/**
 * v1.7 F4 (design §3.3 d, §4.5, I14): `ArrayDraw` is the one seam through which every cache
 * writer shows a live array. Without an array every writer draws exactly as in v1.6 (the lambda
 * once, the same dirty area, the content itself); with 3 copies the copies are present, and each
 * case checks the cache against the copies drawn by hand (the source under each `ArrayLayout`
 * matrix, or a fresh render of the expanded objects): a text whose width changes (relative X
 * 100 %, the Text tool's dirty rectangle covers the source only), a shape redrawn through a
 * partial dirty rectangle, a vector object erased with the vector eraser, a vector-mode brush
 * stroke appended (`keepLayerData` + `appendData`); and flips, canvas operations, layer
 * operations and background renders carry the copies.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayDrawRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        ArrayDraw.clearCaches()
        scope.cancel()
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 480
    private val h = 200

    /** Background and "Vector 1" (empty, active). */
    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.color = 0xFF2060C0.toInt()
            // The tools are made now (a painting tool brings back its stored brush when it is made).
            it.tools
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[1]

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** A fresh render of [content] on a [cw] x [ch] canvas. */
    private fun render(content: VectorContent, cw: Int = w, ch: Int = h): IntArray {
        val b = BitmapUtils.createLayerBitmap(cw, ch)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, cw, ch), tips = TipCache(), document = Rect(0, 0, cw, ch))
        return pixels(b)
    }

    /** What a vector layer's cache must be: a fresh render of its expanded content. */
    private fun fresh(c: EditorController, layer: Layer): IntArray =
        render(ArrayDraw.effectiveVector(layer.dataSnapshot())!!, c.doc.width, c.doc.height)

    /** The copies drawn by hand: [drawSource] under each ArrayLayout matrix k = N - 1 down to 1, then at identity. */
    private fun byHand(spec: ArraySpec, source: RectF, drawSource: (Canvas) -> Unit): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        val cv = Canvas(b)
        val ms = ArrayLayout.matrices(spec, source)
        assertEquals(spec.count, ms.size)
        for (k in ms.size - 1 downTo 1) {
            cv.save()
            cv.concat(Matrix().apply { setValues(ms[k]) })
            drawSource(cv)
            cv.restore()
        }
        drawSource(cv)
        return pixels(b)
    }

    /** Asserts that the [width] px wide columns from [x0] repeat [copies] times, [dx] px apart (every row). */
    private fun assertCopiesShifted(p: IntArray, x0: Int, width: Int, dx: Int, copies: Int) {
        for (k in 1..copies) {
            for (y in 0 until h) {
                val src = IntArray(width) { p[y * w + x0 + it] }
                val copy = IntArray(width) { p[y * w + x0 + k * dx + it] }
                assertArrayEquals("copy $k, row $y", src, copy)
            }
        }
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, color: Int = 0xFFE04020.toInt()) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(color),
        stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 4f),
    )

    private fun text(item: TextItem): (Canvas) -> Unit = { cv -> TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null) }

    private fun textBounds(item: TextItem): RectF = TextRenderer.prepare(item).docBounds(item)

    private fun shape(o: ShapeObject): (Canvas) -> Unit = { cv ->
        cv.drawRect(o.cx - o.w / 2f, o.cy - o.h / 2f, o.cx + o.w / 2f, o.cy + o.h / 2f, Paint().apply { color = 0xFF2244CC.toInt() })
    }

    private fun shapeBounds(o: ShapeObject): RectF = VectorOps.bounds(VShape(0L, shape = ShapeCodec.decode(ShapeCodec.encode(o))!!))

    private fun EditorController.drag(pts: List<ToolPoint>) {
        pointerDown(pts.first())
        for (p in pts.subList(1, pts.size - 1)) pointerMove(p)
        pointerUp(pts.last())
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 24): List<ToolPoint> = List(n) { k ->
        val t = k / (n - 1f)
        ToolPoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + 6f * sin(t * 6f), 1f, k.toLong())
    }

    private fun commit(c: EditorController, label: String, op: (CanvasSnapshot) -> CanvasResult) {
        c.vectors.flushPending()
        val snap = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, label, snap, op(snap))
    }

    // ------------------------------------------------------------------ without an array

    @Test
    fun withoutAnArrayEveryCacheWriterDrawsAsInV16() {
        var calls = 0
        val paint = Paint().apply { color = 0xFF3366AA.toInt(); isAntiAlias = true }
        val draw: (Canvas) -> Unit = { cv -> calls++; cv.drawRect(10.5f, 20.25f, 90f, 70.75f, paint) }
        val direct = BitmapUtils.createLayerBitmap(w, h).also { draw(Canvas(it)) }
        // The seam: the lambda once, untouched (exactly the pixels of calling it), also with one
        // instance, while editing a raster source, and for an empty source rectangle.
        val cases = listOf(
            null to RectF(10f, 20f, 90f, 71f),
            LayerArray(ArraySpec(count = 1)) to RectF(10f, 20f, 90f, 71f),
            LayerArray(ArraySpec(editingSource = true)) to RectF(10f, 20f, 90f, 71f),
            LayerArray(ArraySpec()) to RectF(),
        )
        for ((array, source) in cases) {
            calls = 0
            val b = BitmapUtils.createLayerBitmap(w, h)
            ArrayDraw.drawWithArray(Canvas(b), array, source, draw)
            assertEquals("$array: the lambda once", 1, calls)
            assertArrayEquals("$array: the lambda's own pixels", pixels(direct), pixels(b))
        }
        // Vector data: the content itself, the same instance; no cache bounds.
        val v = VectorContent.EMPTY.plus(listOf(box(20f, 30f, 60f, 70f))).first
        assertSame(v, ArrayDraw.effectiveVector(LayerData(vector = v)))
        assertSame(v, ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(ArraySpec(count = 1)))))
        assertNull(ArrayDraw.effectiveVector(LayerData(text = "x")))
        assertNull(ArrayDraw.cacheBounds(LayerData(vector = v)))

        val c = setup()
        // addLayerWithContent: the lambda once, on a new layer.
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 50f, cy = 45f, w = 80f, h = 50f)
        calls = 0
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = draw)!!
        assertEquals(1, calls)
        assertArrayEquals(pixels(direct), pixels(layer.bitmap))
        // updateShapeLayer clears and redraws exactly its dirty rectangle, as in v1.6: a pixel
        // outside it stays.
        layer.bitmap.setPixel(300, 150, 0xFF00FF00.toInt())
        val expected = BitmapUtils.copy(layer.bitmap)
        val dirty = Rect(0, 0, 120, 100)
        val redraw: (Canvas) -> Unit = { cv -> cv.drawCircle(60f, 50f, 30f, paint) }
        Canvas(expected).apply {
            save()
            clipRect(dirty)
            drawColor(0, PorterDuff.Mode.CLEAR)
            redraw(this)
            restore()
        }
        assertTrue(c.updateShapeLayer(layer, ShapeCodec.encode(o.copy(type = ShapeType.ELLIPSE)), "Edit shape", dirty, redraw))
        assertArrayEquals(pixels(expected), pixels(layer.bitmap))
        assertEquals(0xFF00FF00.toInt(), layer.bitmap.getPixel(300, 150))

        // A vector layer: diffs and renders its own objects (a pure move still shifts the cache).
        val vec = c.vec
        c.vectors.addObjects(vec, listOf(box(200f, 30f, 240f, 70f), box(320f, 100f, 360f, 150f)), "Add")
        assertArrayEquals(render(vec.vector!!), pixels(vec.bitmap))
        val shifts = c.vectors.shiftCount
        val moved = vec.vector!!.let { cv -> cv.copy(objects = cv.objects.map { if (it.id == 1L) VectorOps.transformed(it, floatArrayOf(1f, 0f, 12f, 0f, 1f, 5f, 0f, 0f, 1f)) else it }) }
        c.vectors.update(vec, moved, "Move", shift = VectorLayers.ShiftHint(setOf(1L), 12, 5))
        assertEquals(shifts + 1, c.vectors.shiftCount)
        assertSame(moved, vec.vector)
        assertSame(moved, ArrayDraw.effectiveVector(vec.dataSnapshot()))
    }

    // ------------------------------------------------------------------ the four cases of §3.3 (d)

    @Test
    fun aTextLayersCopiesFollowItsWidthAndLeaveNoStalePixels() {
        val c = setup()
        val hi = TextItem("Hi", spec = TextSpec(sizePx = 40f), cx = 70f, cy = 100f)
        val layer = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(hi), draw = text(hi))!!
        // Count 3, relative X 100 % (the defaults).
        val spec = ArraySpec(count = 3, relativeX = 1f)
        assertTrue(c.updateLayerData(layer, layer.dataSnapshot().copy(array = LayerArray(spec)), "Array", null, draw = text(hi)))
        val narrow = byHand(spec, textBounds(hi), text(hi))
        assertArrayEquals("three copies of the text", narrow, pixels(layer.bitmap))
        val steps = c.undoManager.undoCount

        // The text gets wider; the Text tool's dirty rectangle covers the old and new text only.
        val hello = hi.copy(text = "Hello")
        assertTrue(textBounds(hello).width() > textBounds(hi).width() + 20f)
        val dirty = Rect().also { r -> RectF(textBounds(hi)).apply { union(textBounds(hello)) }.roundOut(r) }
        assertTrue(c.updateTextLayer(layer, TextCodec.encode(hello), "Edit text", dirty, draw = text(hello)))
        val wide = byHand(spec, textBounds(hello), text(hello))
        assertNotEquals(narrow.toList(), wide.toList())
        assertArrayEquals("copies 2 and 3 moved with the width, nothing stale", wide, pixels(layer.bitmap))
        assertEquals(steps + 1, c.undoManager.undoCount)

        c.undo()
        assertEquals(TextCodec.encode(hi), layer.textData)
        assertArrayEquals(narrow, pixels(layer.bitmap))
        c.redo()
        assertArrayEquals(wide, pixels(layer.bitmap))
    }

    @Test
    fun aShapeLayerRedrawnThroughAPartialDirtyRectangleRedrawsItsCopies() {
        val c = setup()
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 60f, cy = 90f, w = 60f, h = 40f, style = ShapeStyle.FILL)
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = shape(o))!!
        val spec = ArraySpec(count = 3, relativeX = 1.25f, relativeY = 0.25f)
        assertTrue(c.updateLayerData(layer, layer.dataSnapshot().copy(array = LayerArray(spec)), "Array", null, draw = shape(o)))
        assertArrayEquals(byHand(spec, shapeBounds(o), shape(o)), pixels(layer.bitmap))
        // Wider, redrawn through a rectangle that covers one corner of the source only.
        val o2 = o.copy(w = 90f)
        assertTrue(c.updateShapeLayer(layer, ShapeCodec.encode(o2), "Edit shape", Rect(30, 70, 60, 90), draw = shape(o2)))
        assertArrayEquals(byHand(spec, shapeBounds(o2), shape(o2)), pixels(layer.bitmap))
        c.undo()
        assertArrayEquals(byHand(spec, shapeBounds(o), shape(o)), pixels(layer.bitmap))
    }

    @Test
    fun aVectorObjectErasedWithTheVectorEraserTakesItsCopiesAlong() {
        val c = setup()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(box(20f, 30f, 60f, 70f), box(70f, 100f, 110f, 150f, 0xFF20A040.toInt())), "Add")
        // Whole-pixel copies, so each copy's pixels are exactly the source's moved.
        val spec = ArraySpec(count = 3, relativeX = 0f, constantX = 150f)
        var applied: Boolean? = null
        c.vectors.updateArray(layer, LayerArray(spec), "Array") { applied = it }
        assertEquals(true, applied)
        assertEquals(LayerArray(spec), layer.array)
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
        assertCopiesShifted(pixels(layer.bitmap), 0, 140, 150, 2)
        val before = layer.vector!!
        val steps = c.undoManager.undoCount

        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.drag(listOf(ToolPoint(40f, 15f), ToolPoint(40f, 50f), ToolPoint(40f, 85f)))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(listOf(2L), layer.vector!!.objects.map { it.id })
        val cache = pixels(layer.bitmap)
        assertArrayEquals(fresh(c, layer), cache)
        assertCopiesShifted(cache, 0, 140, 150, 2)
        // Nothing is left where the erased box's copies were.
        for (k in 1..2) for (y in 25 until 76) for (x in 15 until 66) assertEquals("copy $k at $x,$y", 0, cache[y * w + x + 150 * k])

        c.undo()
        assertSame(before, layer.vector)
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
        c.redo()
        assertArrayEquals(cache, pixels(layer.bitmap))
    }

    @Test
    fun aVectorModeBrushStrokeReRendersEveryCopyInItsStep() {
        val c = setup()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(box(20f, 40f, 60f, 80f)), "Add")
        // Relative X 100 %: a stroke that widens the source moves every copy.
        val spec = ArraySpec(count = 3, relativeX = 1f)
        c.vectors.updateArray(layer, LayerArray(spec), "Array")
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
        val before = layer.vector!!
        val beforeCache = pixels(layer.bitmap)
        val steps = c.undoManager.undoCount

        c.selectTool(ToolId.BRUSH)
        c.brush = BrushLibrary.defaultBrush.copy(size = 6f)
        c.drag(line(30f, 120f, 130f, 150f))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Brush", c.undoManager.undoLabel)
        val after = layer.vector!!
        assertEquals(2, after.objects.size)
        val s = after.objects.last() as VStroke
        assertEquals(LayerArray(spec), layer.array)
        assertArrayEquals("the live stroke and every copy, nothing stale", fresh(c, layer), pixels(layer.bitmap))
        // The stroke's copies: ids id + (k shl 53), placed by the matrices of the new bounds.
        val eff = ArrayDraw.effectiveVector(layer.dataSnapshot())!!
        val ms = ArrayLayout.matrices(spec, ObjectIndex.of(after).unionBounds())
        val sb = VectorOps.bounds(s)
        for (k in 1..2) {
            val copy = eff.objects.single { it.id == s.id + (k.toLong() shl ArrayDraw.COPY_ID_SHIFT) }
            val cb = VectorOps.bounds(copy)
            assertEquals(sb.left + ms[k][2], cb.left, 1e-2f)
            assertEquals(sb.top + ms[k][5], cb.top, 1e-2f)
        }

        c.undo()
        assertSame(before, layer.vector)
        assertArrayEquals(beforeCache, pixels(layer.bitmap))
        c.redo()
        assertSame(after, layer.vector)
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
    }

    // ------------------------------------------------------------------ the expanded content

    @Test
    fun theExpandedContentReusesUnchangedCopies() {
        val v = VectorContent.EMPTY.plus(listOf(box(20f, 30f, 60f, 70f), box(70f, 100f, 110f, 150f))).first
        val spec = ArraySpec(count = 4, relativeX = 0f, constantX = 100f)
        val e = ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(spec)))!!
        // Copies 3, 2, 1, then the source objects themselves (the same instances, on top).
        assertEquals(8, e.objects.size)
        val shift = { k: Int -> k.toLong() shl ArrayDraw.COPY_ID_SHIFT }
        assertEquals(listOf(1L + shift(3), 2L + shift(3), 1L + shift(2), 2L + shift(2), 1L + shift(1), 2L + shift(1), 1L, 2L), e.objects.map { it.id })
        assertSame(v.objects[0], e.objects[6])
        assertSame(v.objects[1], e.objects[7])
        // The same data again: the same result.
        assertSame(e, ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(spec))))
        // One object replaced (the bounds and so the matrices kept): the other's copies are reused.
        val v2 = v.copy(objects = listOf(v.objects[0], box(72f, 100f, 108f, 150f).withId(2L)))
        val e2 = ArrayDraw.effectiveVector(LayerData(vector = v2, array = LayerArray(spec)))!!
        for (i in listOf(0, 2, 4)) assertSame(e.objects[i], e2.objects[i])
        for (i in listOf(1, 3, 5)) assertNotEquals(e.objects[i], e2.objects[i])
        // The cache bounds are the copies' union, measured from the objects' paint bounds.
        val src = ObjectIndex.of(v).unionBounds()
        assertEquals(src, ArrayDraw.sourceBounds(LayerData(vector = v, array = LayerArray(spec))))
        val cb = ArrayDraw.cacheBounds(LayerData(vector = v, array = LayerArray(spec)))!!
        assertEquals(src.left, cb.left, 1e-3f)
        assertEquals(src.right + 300f, cb.right, 1e-3f)
    }

    @Test
    fun aRasterArrayDrawsItsPixelsOncePerCopy() {
        val src = BitmapUtils.createLayerBitmap(40, 30).also { it.eraseColor(0xFFCC3300.toInt()) }
        val a = LayerArray(ArraySpec(count = 3, relativeX = 0f, constantX = 100f), ArrayPixels(src, 10, 20))
        val b = BitmapUtils.createLayerBitmap(w, h)
        ArrayDraw.drawPixels(Canvas(b), a)
        for (k in 0..2) {
            assertEquals(0xFFCC3300.toInt(), b.getPixel(10 + 100 * k, 20))
            assertEquals(0xFFCC3300.toInt(), b.getPixel(49 + 100 * k, 49))
            assertEquals(0, b.getPixel(50 + 100 * k, 35))
        }
        assertEquals(RectF(10f, 20f, 250f, 50f), ArrayDraw.cacheBounds(LayerData(array = a)))
        // Editing the source: the source alone.
        val e = BitmapUtils.createLayerBitmap(w, h)
        ArrayDraw.drawPixels(Canvas(e), a.copy(spec = a.spec.copy(editingSource = true)))
        assertEquals(0xFFCC3300.toInt(), e.getPixel(10, 20))
        assertEquals(0, e.getPixel(110, 20))
    }

    // ------------------------------------------------------------------ flips, canvas and layer operations

    @Test
    fun flipsAndCanvasOperationsCarryTheCopies() {
        val c = setup()
        val layer = c.vec
        // A grainy stroke: it doesn't mirror or turn into itself, so the layer is drawn again.
        val n = 20
        val grainy = VStroke(
            0, preset = BrushLibrary.defaultBrush.copy(size = 8f, grain = 0.6f), color = 0xFF203040.toInt(), seed = 3L, stylus = false,
            points = PackedPoints(FloatArray(n) { 200f + 50f * it / (n - 1) }, FloatArray(n) { 30f + 25f * it / (n - 1) }, FloatArray(n) { 1f }),
        )
        c.vectors.addObjects(layer, listOf(grainy, box(215f, 40f, 245f, 60f)), "Add")
        // A circle whose centre follows the source (null).
        val spec = ArraySpec(mode = ArrayMode.CIRCLE, count = 4)
        c.vectors.updateArray(layer, LayerArray(spec), "Array")
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
        val content = layer.vector!!
        val mirror = floatArrayOf(-1f, 0f, w.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f)

        c.flipLayer(layer, horizontal = true)
        val flipped = layer.array!!
        assertEquals(LayerDataTransforms.mappedArray(LayerArray(spec), content, mirror), flipped)
        assertNotNull("the centre is fixed where the old one maps to", flipped.spec.centerX)
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
        c.undo()
        assertEquals(LayerArray(spec), layer.array)
        assertSame(content, layer.vector)
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))

        commit(c, "Rotate 90° clockwise") { s -> CanvasOps.rotate(s, CanvasRotation.CW_90) }
        assertEquals(h, c.doc.width)
        assertNotNull(layer.array)
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
        commit(c, "Resize image") { s -> CanvasOps.resizeImage(s, c.doc.width / 2, c.doc.height / 2, Resample.BILINEAR) }
        assertNotNull(layer.array)
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
    }

    @Test
    fun layerOperationsAndBackgroundRendersKeepTheCopies() {
        val c = setup()
        val layer = c.vec
        c.vectors.addObjects(layer, listOf(box(20f, 30f, 60f, 70f), box(70f, 100f, 110f, 150f, 0xFF20A040.toInt())), "Add")
        val spec = ArraySpec(count = 3, relativeX = 0f, constantX = 150f)
        c.vectors.updateArray(layer, LayerArray(spec), "Array")

        // A background render draws the expanded content and lands with the data, as one step.
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val steps = c.undoManager.undoCount
        c.vectors.update(layer, layer.vector!!.without(setOf(1L)), "Delete")
        c.vectors.flushPending()
        c.vectors.policy = VectorLayers.Policy.SYNC
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(listOf(2L), layer.vector!!.objects.map { it.id })
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))
        c.undo()
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))

        // New objects on an arrayed layer get their copies (under the source), one step.
        val ids = c.vectors.addObjects(layer, listOf(box(25f, 160f, 55f, 190f, 0xFF4040C0.toInt())), "Add")
        assertEquals(listOf(3L), ids)
        assertArrayEquals(fresh(c, layer), pixels(layer.bitmap))

        // Duplicate with a selection: the copy keeps the array, measured from its own objects.
        val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(m).drawRect(Rect(80, 110, 100, 130), Paint().apply { color = 0xFF000000.toInt() })
        val dup = VectorLayerOps.duplicateTouched(c, layer, Selection.wrap(m))!!
        assertEquals(listOf(2L), dup.vector!!.objects.map { it.id })
        assertEquals(layer.array, dup.array)
        assertArrayEquals(fresh(c, dup), pixels(dup.bitmap))

        // A merge would repeat the upper objects with the lower array: the raster merge does it.
        assertFalse(VectorLayerOps.mergeVector(c, dup, layer))

        // Rasterize: the pixels keep the copies, the array goes with the objects.
        val cache = pixels(layer.bitmap)
        assertTrue(VectorLayerOps.rasterize(c, layer))
        assertNull(layer.vector)
        assertNull(layer.array)
        assertArrayEquals(cache, pixels(layer.bitmap))
        c.undo()
        assertNotNull(layer.array)

        // Removing the array re-renders the copies' tiles away.
        c.vectors.updateArray(layer, null, "Remove array")
        assertNull(layer.array)
        assertArrayEquals(render(layer.vector!!), pixels(layer.bitmap))
    }
}
