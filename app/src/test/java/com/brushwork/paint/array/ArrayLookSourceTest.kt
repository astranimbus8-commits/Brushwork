package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.array.ArrayLookKit.BLUE
import com.brushwork.paint.array.ArrayLookKit.FADE_AND_TINT
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED_TO_BLUE
import com.brushwork.paint.array.ArrayLookKit.pixels
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
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
 * v1.8 (area B, item 2, I19): an array without a look (`look == null`, or a look that sanitizes
 * to none) draws, bounds, copies and saves exactly as v1.7 did: every per-copy caller now reads
 * `ArrayInstances`, and this checks it against v1.7's own algorithms ([ArrayLookKit]'s
 * `v17Draw`, `v17Pixels`, `v17Vector` over `ArrayLayout`), bit for bit, for every mode and
 * source kind. With a real look the source itself (k = 0) is still drawn untouched.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayLookSourceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 400
    private val h = 300

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val guide = VSubpath(listOf(VAnchor(40f, 250f, outX = 80f, outY = -120f), VAnchor(260f, 120f, inX = -60f, inY = 80f), VAnchor(380f, 260f)))

    /** One spec per mode (and a few odd ones): what v1.7 drew. */
    private val specs = listOf(
        ArraySpec(),
        ArraySpec(count = 1),
        ArraySpec(count = 2, relativeX = 0.5f, relativeY = 0.25f, constantX = 7.5f),
        ArraySpec(mode = ArrayMode.CIRCLE, count = 7, sweepDeg = 270f, rotateCopies = true),
        ArraySpec(mode = ArrayMode.CIRCLE, count = 5, centerX = 200f, centerY = 150f, rotateCopies = false),
        ArraySpec(mode = ArrayMode.CURVE, count = 6, guide = guide, spacing = 0f, alignToCurve = true),
        ArraySpec(mode = ArrayMode.TRANSFORM, count = 9, moveX = 31.5f, moveY = 12f, turnDeg = 17f, scale = 0.85f),
        ArraySpec(mode = ArrayMode.TRANSFORM, count = 4, moveX = 20f, turnDeg = -45f, scale = 1.2f, pivotX = 100f, pivotY = 80f),
        ArraySpec(editingSource = true, count = 5),
    )

    /** Looks that draw as none: they sanitize to `look == null`. */
    private val noLooks = listOf(
        null,
        ArrayLook(),
        ArrayLook(tint = RED_TO_BLUE, tintStrength = 0f),
        ArrayLook(easing = ArrayEasing.EASE_IN_OUT),
        ArrayLook(tint = listOf(com.brushwork.paint.vector.VStop(0.5f, BLUE)), tintStrength = 1f),
    )

    private fun bitmap(): Bitmap = BitmapUtils.createLayerBitmap(w, h)

    @Test
    fun withoutALookEveryInstanceIsArrayLayoutsMatrixBitForBit() {
        val sources = listOf(RectF(30f, 40f, 90f, 110f), RectF(0.5f, 0.25f, 33.75f, 12.5f), RectF(150f, 100f, 151f, 300f))
        for (spec0 in specs.filter { !it.editingSource }) for (look in noLooks) for (src in sources) {
            val spec = spec0.copy(look = look)
            assertNull("$look sanitizes to none", spec.sanitized().look)
            val ms = ArrayLayout.matrices(spec0, src)
            val inst = ArrayInstances.of(spec, src)
            assertEquals(ms.size, inst.size)
            assertEquals("draw order", (ms.size - 1 downTo 0).toList(), inst.map { it.k })
            for (i in inst) {
                assertArrayEquals("$spec0 $look k=${i.k}", ms[i.k], i.matrix, 0f)
                assertTrue(ms[i.k].contentEquals(i.matrix))
                assertNull(i.look)
            }
            assertEquals(ArrayLayout.bounds(spec0, src), ArrayInstances.bounds(spec, src))
        }
    }

    @Test
    fun textAndShapeDrawsWithoutALookAreV17BitForBit() {
        val item = TextItem("Wave", spec = TextSpec(sizePx = 36f, bold = true, color = 0xFF2266AA.toInt()), cx = 90f, cy = 70f)
        val text = ArrayLookKit.textDraw(item)
        val textBounds = ArrayDraw.sourceBounds(LayerData(text = com.brushwork.paint.tools.text.TextCodec.encode(item)))!!
        val o = ShapeObject(ShapeType.STAR, cx = 70f, cy = 60f, w = 50f, h = 46f, rotation = 12f, style = ShapeStyle.STROKE_FILL, strokeColor = 0xFF113355.toInt(), fillColor = 0xFFCC8833.toInt())
        val shape = ArraySources.shapeDraw(o, com.brushwork.paint.model.ColorMode.RGB, w, h)
        val shapeBounds = ArrayDraw.sourceBounds(LayerData(shape = com.brushwork.paint.tools.vector.ShapeCodec.encode(o)))!!
        for ((draw, src) in listOf(text to textBounds, shape to shapeBounds)) for (spec0 in specs) for (look in noLooks) {
            val array = LayerArray(spec0.copy(look = look))
            val expected = bitmap().also { ArrayLookKit.v17Draw(Canvas(it), LayerArray(spec0), src, draw) }
            val actual = bitmap().also { ArrayDraw.drawWithArray(Canvas(it), array, src, draw) }
            assertArrayEquals("${spec0.mode} ${spec0.count} $look", pixels(expected), pixels(actual))
        }
        // Without an array at all: the lambda's own pixels.
        val alone = bitmap().also { text(Canvas(it)) }
        assertArrayEquals(pixels(alone), pixels(bitmap().also { ArrayDraw.drawWithArray(Canvas(it), null, textBounds, text) }))
    }

    @Test
    fun pixelCopiesWithoutALookAreV17BitForBit() {
        // A pattern with sharp edges and partial alpha, so bilinear sampling shows.
        val src = Bitmap.createBitmap(37, 29, Bitmap.Config.ARGB_8888)
        for (y in 0 until 29) for (x in 0 until 37) src.setPixel(x, y, if ((x / 4 + y / 3) % 2 == 0) 0xFF2050C0.toInt() else (0x80000000.toInt() or (x * 6 shl 16) or (y * 8)))
        val px = ArrayPixels(src, 41, 52)
        for (spec0 in specs) for (look in noLooks) {
            val expected = bitmap().also { ArrayLookKit.v17Pixels(Canvas(it), LayerArray(spec0, px)) }
            val actual = bitmap().also { ArrayDraw.drawPixels(Canvas(it), LayerArray(spec0.copy(look = look), px)) }
            assertArrayEquals("${spec0.mode} ${spec0.count} $look", pixels(expected), pixels(actual))
        }
    }

    @Test
    fun vectorCopiesWithoutALookAreV17sObjects() {
        val v = VectorContent.EMPTY.plus(
            listOf(
                ArrayLookKit.box(20f, 30f, 60f, 70f, stroke = 0xFF101010.toInt()),
                VShape(0, opacity = 0.75f, shape = ShapeObject(ShapeType.ELLIPSE, cx = 100f, cy = 50f, w = 30f, h = 20f)),
            ),
        ).first
        for (spec0 in specs) for (look in noLooks) {
            ArrayDraw.clearCaches()
            val data = LayerData(vector = v, array = LayerArray(spec0.copy(look = look)))
            val expected = ArrayLookKit.v17Vector(LayerData(vector = v, array = LayerArray(spec0)))
            val actual = ArrayDraw.effectiveVector(data)!!.objects
            assertEquals("${spec0.mode} ${spec0.count} $look", expected, actual)
            // The source objects are the same instances, last (on top).
            for (i in v.objects.indices) assertSame(v.objects[i], actual[actual.size - v.objects.size + i])
            if (expected.size == v.objects.size) assertSame(v, ArrayDraw.effectiveVector(data))
            assertEquals(ArrayLayout.bounds(spec0, ArrayDraw.sourceBounds(data)!!), ArrayDraw.cacheBounds(data))
        }
    }

    @Test
    fun aSavedSpecWithoutALookIsWrittenAsInV17() {
        for (spec0 in specs) {
            val json = ArrayCodec.encodeSpec(spec0)
            assertFalse(json, json.contains("look"))
            // A look that sanitizes to none is not written either (the edit keeps the sanitized spec).
            for (look in noLooks) assertEquals(json, ArrayCodec.encodeSpec(spec0.copy(look = look).sanitized()))
        }
        val withLook = ArraySpec(look = FADE_AND_TINT)
        val json = ArrayCodec.encodeSpec(withLook)
        assertTrue(json.contains("look"))
        assertEquals(withLook, ArrayCodec.decodeSpec(json))
    }

    @Test
    fun layersWithoutALookShowV17sCaches() {
        val c = ArrayLookKit.controller(app)
        // Raster: the cache is v1.7's pixel copies; an identity look edits nothing visible.
        val raster = ArrayLookKit.pixelArray(c)
        ArrayLookKit.edit(c, raster, ArraySpec(mode = ArrayMode.CIRCLE, count = 6, look = ArrayLook(tint = RED_TO_BLUE, tintStrength = 0f)))
        assertNull(raster.array!!.spec.look)
        val expected = bitmap().also { ArrayLookKit.v17Pixels(Canvas(it), raster.array!!) }
        assertArrayEquals(pixels(expected), pixels(raster.bitmap))
        // Text: the cache is v1.7's draw.
        val item = TextItem("Hi", spec = TextSpec(sizePx = 40f), cx = 80f, cy = 200f)
        val text = ArrayLookKit.textArray(c, item)
        ArrayLookKit.edit(c, text, ArraySpec(mode = ArrayMode.TRANSFORM, count = 5, moveX = 50f, turnDeg = 10f, scale = 0.9f, look = ArrayLook()))
        assertNull(text.array!!.spec.look)
        val textSrc = ArrayDraw.sourceBounds(text.dataSnapshot())!!
        val textExpected = bitmap().also { ArrayLookKit.v17Draw(Canvas(it), text.array, textSrc, ArrayLookKit.textDraw(item)) }
        assertArrayEquals(pixels(textExpected), pixels(text.bitmap))
    }

    @Test
    fun withALookTheSourceItselfIsDrawnUntouched() {
        // Copies two widths apart (relative X 200 %), the last one grown: none reaches the source.
        val look = FADE_AND_TINT.copy(sizeEnd = 1.4f, turnEnd = 25f)
        val spec = ArraySpec(count = 3, relativeX = 2f, look = look)
        val src = RectF(30f, 40f, 90f, 100f)
        val inst = ArrayInstances.of(spec, src)
        assertEquals(0, inst.last().k)
        assertNull(inst.last().look)
        assertTrue(inst.last().matrix.contentEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        val paint = Paint().apply { color = GRAY; isAntiAlias = true }
        val draw: (Canvas) -> Unit = { cv -> cv.drawCircle(60f, 70f, 28.5f, paint) }
        val alone = bitmap().also { draw(Canvas(it)) }
        val arrayed = bitmap().also { ArrayDraw.drawWithArray(Canvas(it), LayerArray(spec), src, draw) }
        val a = pixels(alone)
        val b = pixels(arrayed)
        for (y in 30 until 110) for (x in 20 until 100) assertEquals("($x, $y)", a[y * w + x], b[y * w + x])
        assertNotEquals("the copies are there", 0, arrayed.getPixel(60 + 120, 70))
        // Pixels: the source's own pixels, unfiltered, at its place.
        val bmp = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(GRAY) }
        val px = bitmap().also { ArrayDraw.drawPixels(Canvas(it), LayerArray(spec, ArrayPixels(bmp, 30, 40))) }
        for (y in 40 until 100) for (x in 30 until 90) assertEquals(GRAY, px.getPixel(x, y))
        // Vector: the source objects are the same instances, unstyled.
        val v = VectorContent.EMPTY.plus(listOf(ArrayLookKit.box(30f, 40f, 90f, 100f))).first
        val out = ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(spec)))!!.objects
        assertEquals(3, out.size)
        assertSame(v.objects[0], out.last())
    }
}
