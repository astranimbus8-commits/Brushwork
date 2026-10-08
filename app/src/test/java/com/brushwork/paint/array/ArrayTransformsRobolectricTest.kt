package com.brushwork.paint.array

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTransforms
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.hypot

/**
 * v1.7 (item 11, §3.11; area E): `ArrayTransforms.mapped` maps an arrayed layer's spec AND its
 * source, so that the copies of the mapped source land where the old copies map to: in every
 * mode, for a flip, a turn with a uniform scale and a move, on shape, raster and vector sources.
 * What it refuses: no array, a raster source being edited, a projective map, and a text the Text
 * tool's transforms can't map.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayTransformsRobolectricTest {
    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private var nextId = 1L

    private fun layer(): Layer = Layer(nextId++, "L", BitmapUtils.createLayerBitmap(400, 300))

    private val guide = VSubpath(listOf(VAnchor(60f, 200f), VAnchor(160f, 120f), VAnchor(300f, 220f)))

    /** One spec per mode, centres and pivots left to follow the source. */
    private val specs = listOf(
        ArraySpec(mode = ArrayMode.LINE, count = 4, relativeX = 1f, relativeY = 0.5f, constantX = 6f),
        ArraySpec(mode = ArrayMode.CIRCLE, count = 6, sweepDeg = 360f),
        ArraySpec(mode = ArrayMode.CIRCLE, count = 4, sweepDeg = 120f, rotateCopies = false),
        ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = guide),
        ArraySpec(mode = ArrayMode.TRANSFORM, count = 4, moveX = 30f, moveY = 10f, turnDeg = 25f, scale = 0.9f),
    )

    private fun about(m: Matrix): FloatArray = FloatArray(9).also { m.getValues(it) }

    private val maps = listOf(
        "flip" to about(Matrix().apply { setScale(-1f, 1f, 200f, 150f) }),
        "turn and scale" to about(Matrix().apply { setRotate(30f, 180f, 140f); postScale(1.25f, 1.25f, 180f, 140f) }),
        "move" to about(Matrix().apply { setTranslate(17.5f, -9.25f) }),
        "quarter turn" to about(Matrix().apply { setRotate(90f, 200f, 150f) }),
    )

    private fun apply(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

    /** Where each copy takes the source's centre. */
    private fun centres(spec: ArraySpec, source: RectF): List<Pair<Float, Float>> =
        ArrayLayout.matrices(spec, source).map { k -> apply(k, source.centerX(), source.centerY()) }

    /** The copies of [after] sit where [m] takes the copies of [before] (within [tol] px). */
    private fun assertCopiesFollow(where: String, before: LayerData, after: LayerData, m: FloatArray, tol: Float) {
        val old = centres(before.array!!.spec, ArrayDraw.sourceBounds(before)!!)
        val new = centres(after.array!!.spec, ArrayDraw.sourceBounds(after)!!)
        assertEquals("$where: copies", old.size, new.size)
        for (k in old.indices) {
            val (x, y) = apply(m, old[k].first, old[k].second)
            val d = hypot(x - new[k].first, y - new[k].second)
            assertTrue("$where: copy $k is $d px off", d <= tol)
        }
    }

    @Test
    fun aShapeArrayFollowsEveryMapInEveryMode() {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 120f, cy = 90f, w = 60f, h = 40f)
        for (spec in specs) for ((name, m) in maps) {
            val l = layer().also { it.shapeData = ShapeCodec.encode(o); it.array = LayerArray(spec) }
            val after = ArrayTransforms.mapped(l, m)
            assertNotNull("${spec.mode} $name", after)
            assertNotNull(ShapeCodec.decode(after!!.shape!!))
            assertCopiesFollow("${spec.mode} $name (shape)", l.dataSnapshot(), after, m, 0.05f)
        }
    }

    @Test
    fun rasterSourcePixelsAreResampledAndMovedExactly() {
        val bmp = BitmapUtils.createLayerBitmap(40, 30).also { it.eraseColor(0xFF22AA44.toInt()) }
        val px = ArrayPixels(bmp, 100, 70)
        for (spec in specs) for ((name, m) in maps) {
            val l = layer().also { it.array = LayerArray(spec, px) }
            val after = ArrayTransforms.mapped(l, m)
            assertNotNull("${spec.mode} $name", after)
            assertCopiesFollow("${spec.mode} $name (pixels)", l.dataSnapshot(), after!!, m, 1.6f)
        }
        // A whole-pixel move shares the pixels; the spec is unchanged.
        val l = layer().also { it.array = LayerArray(specs[0], px) }
        val moved = ArrayTransforms.mapped(l, about(Matrix().apply { setTranslate(12f, -5f) }))!!.array!!
        assertSame(bmp, moved.pixels!!.bitmap)
        assertEquals(112, moved.pixels.left)
        assertEquals(65, moved.pixels.top)
        assertEquals(specs[0], moved.spec)
        // Turned a quarter: the resampled source is 30 x 40.
        val turned = ArrayTransforms.mapped(l, maps[3].second)!!.array!!.pixels!!
        assertEquals(30, turned.bitmap.width)
        assertEquals(40, turned.bitmap.height)
        assertEquals(0xFF22AA44.toInt(), turned.bitmap.getPixel(15, 20))
        // While its source is being painted, the layer transforms as pixels: refused here.
        val editing = layer().also { it.array = LayerArray(specs[0].copy(editingSource = true), px) }
        assertNull(ArrayTransforms.mapped(editing, maps[0].second))
    }

    @Test
    fun aVectorArrayMapsItsObjectsAndSpec() {
        val box = VPath(
            0, subpaths = listOf(VSubpath(listOf(VAnchor(100f, 60f, true), VAnchor(150f, 60f, true), VAnchor(150f, 100f, true), VAnchor(100f, 100f, true)), closed = true)),
            fill = VPaint.Solid(0xFFE04020.toInt()),
        )
        val content = VectorContent.EMPTY.plus(listOf(box)).first
        for (spec in specs) for ((name, m) in maps) {
            val l = layer().also { it.vector = content; it.array = LayerArray(spec) }
            val after = ArrayTransforms.mapped(l, m)!!
            assertEquals(content.objects.map { it.id }, after.vector!!.objects.map { it.id })
            assertCopiesFollow("${spec.mode} $name (vector)", l.dataSnapshot(), after, m, 0.6f)
        }
    }

    @Test
    fun whatIsRefused() {
        val o = ShapeObject(ShapeType.ELLIPSE, cx = 120f, cy = 90f, w = 60f, h = 40f)
        val shape = layer().also { it.shapeData = ShapeCodec.encode(o); it.array = LayerArray(ArraySpec()) }
        assertNull("projective", ArrayTransforms.mapped(shape, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f)))
        assertNull("singular", ArrayTransforms.mapped(shape, floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        assertNull("no array", ArrayTransforms.mapped(layer().also { it.shapeData = ShapeCodec.encode(o) }, maps[0].second))
        // A text array maps as the Text tool's transforms map text (area D), else it is refused.
        val item = TextItem("Hi", spec = TextSpec(sizePx = 40f), cx = 150f, cy = 90f)
        val text = layer().also { it.textData = TextCodec.encode(item); it.array = LayerArray(ArraySpec()) }
        val m = about(Matrix().apply { setRotate(20f, 150f, 90f) })
        val mapped = ArrayTransforms.mapped(text, m)
        if (TextTransforms.canMap(m) && TextTransforms.mapped(text.textData!!, m) != null) assertNotNull(mapped) else assertNull(mapped)
    }

    @Test
    fun theCacheDrawOfARasterArrayDrawsEveryCopy() {
        val bmp = BitmapUtils.createLayerBitmap(10, 10).also { it.eraseColor(-0x1000000) }
        val data = LayerData(array = LayerArray(ArraySpec(count = 3), ArrayPixels(bmp, 5, 5)))
        val out = BitmapUtils.createLayerBitmap(60, 20)
        ArrayTransforms.cacheDraw(data, ColorMode.RGB, 60, 20)!!.invoke(Canvas(out))
        for (k in 0 until 3) assertEquals("copy $k", -0x1000000, out.getPixel(10 + 10 * k, 10))
        assertNull(ArrayTransforms.cacheDraw(LayerData(), ColorMode.RGB, 60, 20))
    }
}
