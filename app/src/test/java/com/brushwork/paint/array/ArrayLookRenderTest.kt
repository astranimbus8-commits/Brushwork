package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.array.ArrayLookKit.BLUE
import com.brushwork.paint.array.ArrayLookKit.FADE_AND_TINT
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.array.ArrayLookKit.WHITE
import com.brushwork.paint.array.ArrayLookKit.assertColor
import com.brushwork.paint.array.ArrayLookKit.pixels
import com.brushwork.paint.array.ArrayLookKit.withAlpha
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.CopyLook
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.roundToInt

/**
 * v1.8 (area B, item 2, design §3.3): an Array look shows in every source kind's cache. Pixel
 * copies draw through their own paint (opacity as alpha, the tint as a color matrix), text and
 * shape copies inside a layer with that paint, vector copies as restyled objects (every paint
 * color tinted, opacity multiplied: R12). The look runs from the first copy (Start) to the last
 * (End) along the pace; a copy with opacity 0 is left out and a copy that only grows or turns
 * draws as a plain copy, without a layer.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayLookRenderTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    /** Copy k's color under [look] for a source color [src] (tint and opacity as the copy's paint applies them). */
    private fun expected(src: Int, look: CopyLook?): Int {
        if (look == null) return src
        val tinted = if (look.tintStrength > 0f) ArrayTint.apply(src, look.tintColor, look.tintStrength) else src
        return withAlpha(tinted, (look.opacity * 255f).roundToInt())
    }

    /** Where [x], [y] of the source lands in instance [k] (device px, rounded). */
    private fun mapped(spec: ArraySpec, source: RectF, k: Int, x: Float, y: Float): Pair<Int, Int> {
        val m = ArrayInstances.of(spec, source).first { it.k == k }.matrix
        val p = floatArrayOf(x, y)
        Matrix().apply { setValues(m) }.mapPoints(p)
        return p[0].toInt() to p[1].toInt()
    }

    @Test
    fun pixelCopiesDrawThroughTheirOpacityAndTint() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.pixelArray(c)
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(look = FADE_AND_TINT))
        assertEquals(FADE_AND_TINT, layer.array!!.spec.look)
        // Copy 1 (Start): opaque, red; copy 2 (End): half, blue; the source untouched.
        assertEquals(GRAY, layer.bitmap.getPixel(40, 50))
        assertColor("copy 1", ArrayTint.apply(GRAY, RED, 1f), layer.bitmap.getPixel(80, 50))
        assertColor("copy 2", withAlpha(ArrayTint.apply(GRAY, BLUE, 1f), 128), layer.bitmap.getPixel(120, 50))
        assertEquals("nothing past the last copy", 0, layer.bitmap.getPixel(160, 50))
        // One undo brings v1.7's copies back.
        c.undo()
        ArrayLookKit.settle(c)
        for (k in 0 until 3) assertEquals("copy $k", GRAY, layer.bitmap.getPixel(40 + 40 * k, 50))
    }

    @Test
    fun shapeCopiesDrawInsideALayerWithTheirLook() {
        val c = ArrayLookKit.controller(app)
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 60f, cy = 60f, w = 40f, h = 40f, style = ShapeStyle.FILL, strokeColor = GRAY, fillColor = GRAY)
        val layer = ArrayLookKit.shapeArray(c, o)
        val look = ArrayLook(opacityStart = 0.75f, opacityEnd = 0.25f, tint = listOf(VStop(0f, RED), VStop(0.5f, WHITE), VStop(1f, BLUE)), tintStrength = 0.5f)
        val spec = ArraySpec(count = 4, relativeX = 1.25f, look = look)
        ArrayLookKit.edit(c, layer, spec)
        val source = ArrayDraw.sourceBounds(layer.dataSnapshot())!!
        val instances = ArrayInstances.of(spec, source)
        for (inst in instances) {
            val (x, y) = mapped(spec, source, inst.k, 60f, 60f)
            assertColor("copy ${inst.k}", expected(GRAY, inst.look), layer.bitmap.getPixel(x, y))
        }
        // The middle stop is white at t = 0.5: copies 1..3 sit at t = 0, 1/2, 1.
        assertEquals(WHITE, instances.first { it.k == 2 }.look!!.tintColor)
    }

    @Test
    fun textCopiesDrawInsideALayerWithTheirLook() {
        val c = ArrayLookKit.controller(app)
        val item = TextItem("H", spec = TextSpec(sizePx = 60f, bold = true, color = WHITE), cx = 60f, cy = 80f)
        val layer = ArrayLookKit.textArray(c, item)
        val look = ArrayLook(opacityStart = 0.5f, opacityEnd = 0.5f, tint = listOf(VStop(0f, RED), VStop(1f, RED)), tintStrength = 1f)
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(look = look))
        val source = ArrayDraw.sourceBounds(layer.dataSnapshot())!!
        val spec = layer.array!!.spec
        fun strongest(k: Int): Int {
            val m = Matrix().apply { setValues(ArrayInstances.of(spec, source).first { it.k == k }.matrix) }
            val r = RectF(source).also { m.mapRect(it) }
            var best = 0
            for (y in r.top.toInt() + 1 until r.bottom.toInt() - 1) for (x in r.left.toInt() + 1 until r.right.toInt() - 1) {
                if (x !in 0 until layer.bitmap.width || y !in 0 until layer.bitmap.height) continue
                val p = layer.bitmap.getPixel(x, y)
                if ((p ushr 24) > (best ushr 24)) best = p
            }
            return best
        }
        assertEquals("the source is white and opaque", WHITE, strongest(0))
        for (k in 1..2) assertColor("copy $k: red, half", withAlpha(RED, 128), strongest(k))
    }

    @Test
    fun vectorCopiesAreRestyledObjects() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 60f, 70f, stroke = 0xFF202020.toInt())))
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(look = FADE_AND_TINT))
        val objects = ArrayDraw.effectiveVector(layer.dataSnapshot())!!.objects
        assertEquals(3, objects.size)
        val copy2 = objects[0] as VPath
        val copy1 = objects[1] as VPath
        assertSame(layer.vector!!.objects[0], objects[2])
        assertEquals(VPaint.Solid(ArrayTint.apply(GRAY, RED, 1f)), copy1.fill)
        assertEquals(1f, copy1.opacity)
        assertEquals(ArrayTint.apply(0xFF202020.toInt(), RED, 1f), copy1.stroke!!.color)
        assertEquals(VPaint.Solid(ArrayTint.apply(GRAY, BLUE, 1f)), copy2.fill)
        assertEquals(0.5f, copy2.opacity)
        // The cache shows them: copy 2's inside is half blue.
        assertColor("copy 2", withAlpha(ArrayTint.apply(GRAY, BLUE, 1f), 128), layer.bitmap.getPixel(40 + 80, 50))
        assertColor("copy 1", ArrayTint.apply(GRAY, RED, 1f), layer.bitmap.getPixel(40 + 40, 50))
        assertEquals(GRAY, layer.bitmap.getPixel(40, 50))
    }

    @Test
    fun restyledTintsEveryPaintColorAndMultipliesOpacity() {
        val look = CopyLook(opacity = 0.5f, tintColor = 0xFF00FF00.toInt(), tintStrength = 0.6f)
        fun t(argb: Int) = ArrayTint.apply(argb, look.tintColor, look.tintStrength)
        val stroke = VStroke(
            7, opacity = 0.8f, preset = BrushLibrary.defaultBrush, color = 0xFF336699.toInt(), seed = 1L, stylus = false,
            points = PackedPoints(floatArrayOf(0f, 10f), floatArrayOf(0f, 10f), floatArrayOf(1f, 1f)),
        )
        val s = ArrayDraw.restyled(stroke, look) as VStroke
        assertEquals(t(0xFF336699.toInt()), s.color)
        assertEquals(0.4f, s.opacity, 1e-6f)
        assertEquals(stroke.points, s.points)
        assertEquals(7L, s.id)

        val stops = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0x80FFFFFF.toInt()))
        val linear = ArrayLookKit.box(0f, 0f, 10f, 10f).copy(fill = VPaint.Linear(0f, 0f, 10f, 0f, stops), stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 2f))
        val l = ArrayDraw.restyled(linear, look) as VPath
        assertEquals(VPaint.Linear(0f, 0f, 10f, 0f, stops.map { it.copy(color = t(it.color)) }), l.fill)
        assertEquals("a stop's alpha is kept", 0x80, (l.fill as VPaint.Linear).stops[1].color ushr 24)
        assertEquals(t(0xFF000000.toInt()), l.stroke!!.color)
        assertEquals(linear.subpaths, l.subpaths)
        val radial = linear.copy(fill = VPaint.Radial(5f, 5f, 5f, stops))
        assertEquals(VPaint.Radial(5f, 5f, 5f, stops.map { it.copy(color = t(it.color)) }), (ArrayDraw.restyled(radial, look) as VPath).fill)
        val bare = linear.copy(fill = null, stroke = null)
        assertEquals(bare.copy(opacity = 0.5f), ArrayDraw.restyled(bare, look))

        val shape = VShape(3, opacity = 1f, shape = ShapeObject(ShapeType.ELLIPSE, strokeColor = 0xFF102030.toInt(), fillColor = 0xFFF0E0D0.toInt()))
        val sh = ArrayDraw.restyled(shape, look) as VShape
        assertEquals(t(0xFF102030.toInt()), sh.shape.strokeColor)
        assertEquals(t(0xFFF0E0D0.toInt()), sh.shape.fillColor)
        assertEquals(0.5f, sh.opacity)
        assertEquals(shape.shape.copy(strokeColor = sh.shape.strokeColor, fillColor = sh.shape.fillColor), sh.shape)

        // Opacity alone: colors kept. No look, or a look that changes nothing: the same instance.
        val faded = ArrayDraw.restyled(shape, CopyLook(0.25f, 0xFF00FF00.toInt(), 0f)) as VShape
        assertEquals(shape.shape, faded.shape)
        assertEquals(0.25f, faded.opacity)
        assertSame(shape, ArrayDraw.restyled(shape, null))
        assertSame(shape, ArrayDraw.restyled(shape, CopyLook(1f, 0xFF00FF00.toInt(), 0f)))
    }

    @Test
    fun theLookRunsAlongThePaceFromStartToEnd() {
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(GRAY) }
        val green = 0xFF00FF00.toInt()
        val look = ArrayLook(
            opacityStart = 1f, opacityEnd = 0f, easing = ArrayEasing.EASE_IN,
            tint = listOf(VStop(0f, RED), VStop(0.5f, green), VStop(1f, BLUE)), tintStrength = 1f,
        )
        // Six instances: copies 1..5 at t = (k − 1) / 4, eased (t²).
        val spec = ArraySpec(count = 6, relativeX = 1.5f, look = look)
        val out = BitmapUtils.createLayerBitmap(260, 40)
        ArrayDraw.drawPixels(Canvas(out), LayerArray(spec, ArrayPixels(bmp, 10, 10)))
        for (k in 1..5) {
            val t = ArrayInstances.ease(ArrayEasing.EASE_IN, (k - 1) / 4f)
            val opacity = 1f - t
            val tint = ArrayTint.sample(look.tint, t)
            val x = 20 + 30 * k
            if (opacity == 0f) {
                assertEquals("copy $k: opacity 0, left out", 0, out.getPixel(x, 20))
            } else {
                assertColor("copy $k", withAlpha(ArrayTint.apply(GRAY, tint, 1f), (opacity * 255f).roundToInt()), out.getPixel(x, 20))
            }
        }
        // Copy 3 sits at t = 0.25: halfway from red to green.
        assertEquals(ColorUtils.lerp(RED, green, 0.5f), ArrayTint.sample(look.tint, ArrayInstances.ease(ArrayEasing.EASE_IN, 0.5f)))
        assertEquals(GRAY, out.getPixel(20, 20))
    }

    @Test
    fun sizeAndTurnShowAndAPlainCopyNeedsNoLayer() {
        // End size 200 %: the last copy doubles about its own centre.
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(GRAY) }
        val spec = ArraySpec(count = 3, relativeX = 3f, look = ArrayLook(sizeEnd = 2f))
        val out = BitmapUtils.createLayerBitmap(300, 100)
        ArrayDraw.drawPixels(Canvas(out), LayerArray(spec, ArrayPixels(bmp, 20, 40)))
        // Copy 2 (unscaled: x 140..160, y 40..60) now covers x 130..170, y 30..70.
        assertEquals(GRAY, out.getPixel(132, 32))
        assertEquals(GRAY, out.getPixel(167, 67))
        assertEquals(0, out.getPixel(128, 50))
        // Copy 1 (Start, 100 %) is unchanged: x 80..100.
        assertEquals(GRAY, out.getPixel(81, 41))
        assertEquals(0, out.getPixel(78, 50))

        // A copy that only grows or turns is drawn as a plain copy: bit for bit the source under its matrix.
        val turn = ArraySpec(count = 3, relativeX = 2f, look = ArrayLook(turnEnd = 30f, sizeStart = 0.8f))
        val paint = Paint().apply { color = 0xFF3366AA.toInt(); isAntiAlias = true }
        val draw: (Canvas) -> Unit = { cv -> cv.drawRect(20.5f, 30.25f, 60f, 70.75f, paint) }
        val src = RectF(20f, 30f, 61f, 71f)
        val byHand = BitmapUtils.createLayerBitmap(300, 100).also { b ->
            val cv = Canvas(b)
            for (inst in ArrayInstances.of(turn, src)) {
                cv.save()
                cv.concat(Matrix().apply { setValues(inst.matrix) })
                draw(cv)
                cv.restore()
            }
        }
        val drawn = BitmapUtils.createLayerBitmap(300, 100).also { ArrayDraw.drawWithArray(Canvas(it), LayerArray(turn), src, draw) }
        assertArrayEquals(pixels(byHand), pixels(drawn))
    }

    @Test
    fun aCopyWithOpacityZeroIsLeftOut() {
        var calls = 0
        val draw: (Canvas) -> Unit = { cv -> calls++; cv.drawRect(10f, 10f, 30f, 30f, Paint().apply { color = GRAY }) }
        val spec = ArraySpec(count = 3, look = ArrayLook(opacityStart = 0.5f, opacityEnd = 0f))
        val out = BitmapUtils.createLayerBitmap(100, 40)
        ArrayDraw.drawWithArray(Canvas(out), LayerArray(spec), RectF(10f, 10f, 30f, 30f), draw)
        assertEquals("the source and copy 1", 2, calls)
        assertColor("copy 1 at half", withAlpha(GRAY, 128), out.getPixel(40, 20))
        assertEquals("copy 2 left out", 0, out.getPixel(60, 20))
        assertTrue(ArrayDraw.changesPixels(CopyLook(0.99f, 0, 0f)))
        assertTrue(ArrayDraw.changesPixels(CopyLook(1f, 0, 0.01f)))
        assertTrue(!ArrayDraw.changesPixels(CopyLook(1f, RED, 0f)))
    }
}
