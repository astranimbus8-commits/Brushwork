package com.brushwork.paint.v110.a3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED_TO_BLUE
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.ceil

/**
 * v1.10 (R4, A3 step 1): an array with `ArraySpec.hideSource` draws "all copies" minus copy 0,
 * for every source kind: the pixels, text and shape caches equal the full array's outside the
 * source's place and are empty inside it; a vector array's objects are the full array's without
 * the source objects (none with one instance); the export and Apply leave the source out too.
 * Placement does not change: `ArrayInstances.of` still returns copy 0 (I19).
 */
@RunWith(RobolectricTestRunner::class)
class CopiesOnlyDrawTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    /** Four instances 1.5 source widths apart (the copies never touch the source's place), with a look. */
    private val spec = ArraySpec(
        count = 4,
        relativeX = 1.5f,
        look = ArrayLook(opacityStart = 1f, opacityEnd = 0.5f, tint = RED_TO_BLUE, tintStrength = 0.5f),
    )
    private val hidden = spec.copy(hideSource = true)

    private fun render(draw: (Canvas) -> Unit): IntArray {
        val b = BitmapUtils.createLayerBitmap(W, H)
        draw(Canvas(b))
        return ArrayLookKit.pixels(b).also { b.recycle() }
    }

    /** [source] rounded out and grown by the copy layer margin (glyph and stroke overhang). */
    private fun zoneOf(source: RectF): Rect {
        val g = ceil(ArrayDraw.layerMargin(source)).toInt()
        return Rect().also { RectF(source).roundOut(it); it.inset(-g, -g) }
    }

    /** [copiesOnly] equals [all] outside [zone] and is transparent inside it, where [all] drew the source. */
    private fun assertAllMinusSource(what: String, all: IntArray, copiesOnly: IntArray, zone: Rect) {
        var sourcePixels = 0
        var copyPixels = 0
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (zone.contains(x, y)) {
                if (copiesOnly[i] != 0) assertEquals("$what: the source's place is empty at ($x, $y)", 0, copiesOnly[i])
                if (all[i] != 0) sourcePixels++
            } else {
                if (copiesOnly[i] != all[i]) assertEquals("$what: a copy's pixel at ($x, $y)", Integer.toHexString(all[i]), Integer.toHexString(copiesOnly[i]))
                if (all[i] != 0) copyPixels++
            }
        }
        assertTrue("$what: the full array drew the source", sourcePixels > 0)
        assertTrue("$what: the copies are drawn", copyPixels > 0)
    }

    @Test
    fun pixelsCacheIsAllCopiesMinusTheSource() {
        val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(GRAY) }
        val px = ArrayPixels(bmp, 20, 30)
        val all = render { ArrayDraw.drawPixels(it, LayerArray(spec, px)) }
        val copiesOnly = render { ArrayDraw.drawPixels(it, LayerArray(hidden, px)) }
        assertAllMinusSource("pixels", all, copiesOnly, Rect(20, 30, 60, 70))
        // While its source is edited the source alone shows, as in v1.9.
        val editing = render { ArrayDraw.drawPixels(it, LayerArray(hidden.copy(editingSource = true), px)) }
        assertEquals(GRAY, editing[50 * W + 40])
        assertEquals("placement keeps copy 0", 4, ArrayInstances.of(hidden, RectF(20f, 30f, 60f, 70f)).size)
    }

    @Test
    fun textCacheIsAllCopiesMinusTheSource() {
        val item = TextItem("H", spec = TextSpec(sizePx = 40f, bold = true, color = GRAY), cx = 40f, cy = 60f)
        val source = ArrayDraw.sourceBounds(LayerData(text = TextCodec.encode(item)))!!
        val draw = ArrayLookKit.textDraw(item)
        val all = render { ArrayDraw.drawWithArray(it, LayerArray(spec), source, draw) }
        val copiesOnly = render { ArrayDraw.drawWithArray(it, LayerArray(hidden), source, draw) }
        assertAllMinusSource("text", all, copiesOnly, zoneOf(source))
        // One instance: nothing at all.
        val one = render { ArrayDraw.drawWithArray(it, LayerArray(hidden.copy(count = 1)), source, draw) }
        assertTrue("one instance draws nothing", one.all { it == 0 })
    }

    @Test
    fun shapeCacheIsAllCopiesMinusTheSource() {
        val o = ShapeObject(ShapeType.ELLIPSE, cx = 40f, cy = 60f, w = 40f, h = 30f, style = ShapeStyle.FILL, strokeColor = GRAY, fillColor = GRAY)
        val source = ArrayDraw.sourceBounds(LayerData(shape = ShapeCodec.encode(o)))!!
        val draw = ArraySources.shapeDraw(o, com.brushwork.paint.model.ColorMode.RGB, W, H)
        val all = render { ArrayDraw.drawWithArray(it, LayerArray(spec), source, draw) }
        val copiesOnly = render { ArrayDraw.drawWithArray(it, LayerArray(hidden), source, draw) }
        assertAllMinusSource("shape", all, copiesOnly, zoneOf(source))
        // Without an array the source draws as in v1.9.
        val plain = render { ArrayDraw.drawWithArray(it, null, source, draw) }
        assertEquals(GRAY, plain[60 * W + 40])
    }

    @Test
    fun vectorObjectsAreAllCopiesMinusTheSourceObjects() {
        val objects = listOf(ArrayLookKit.box(20f, 30f, 40f, 50f, id = 1), ArrayLookKit.box(25f, 55f, 45f, 70f, id = 2))
        val v = VectorContent.EMPTY.plus(objects).first
        val all = ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(spec)))!!.objects
        val copiesOnly = ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(hidden)))!!.objects
        assertEquals("the full array ends with the source objects", v.objects, all.takeLast(v.objects.size))
        assertEquals(all.dropLast(v.objects.size), copiesOnly)
        assertTrue("no source id", copiesOnly.none { o -> v.objects.any { it.id == o.id } })
        // Memoized: the same instance for the same data.
        assertSame(
            ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(hidden))),
            ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(hidden))),
        )
        // One instance: no objects (the layer's data still holds the source).
        val one = ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(hidden.copy(count = 1))))!!
        assertTrue(one.objects.isEmpty())
        // Without the flag: v1.9 (the source alone, the same instance).
        assertSame(v, ArrayDraw.effectiveVector(LayerData(vector = v, array = LayerArray(spec.copy(count = 1)))))
    }

    @Test
    fun layerCachesOnTheEditorShowOnlyTheCopies() {
        val c = ArrayLookKit.controller(app)
        // Raster: "Array 1" from a 40 px gray square at (20, 30); copies 40 px apart.
        val raster = ArrayLookKit.pixelArray(c)
        ArrayLookKit.edit(c, raster, raster.array!!.spec.copy(hideSource = true))
        assertEquals("the source's place is empty", 0, raster.bitmap.getPixel(40, 50))
        assertEquals(GRAY, raster.bitmap.getPixel(80, 50))
        assertEquals(GRAY, raster.bitmap.getPixel(120, 50))
        // Vector: the same through the vector pipeline.
        val vector = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 130f, 60f, 170f)))
        ArrayLookKit.edit(c, vector, vector.array!!.spec.copy(hideSource = true))
        assertEquals(0, vector.bitmap.getPixel(40, 150))
        assertEquals(GRAY, vector.bitmap.getPixel(80, 150))
        assertTrue("the data keeps the source object", vector.vector!!.objects.size == 1)
    }

    @Test
    fun exportLeavesTheSourceOut() {
        val c = ArrayLookKit.controller(app)
        val vector = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 60f, 70f)))
        ArrayLookKit.edit(c, vector, vector.array!!.spec.copy(hideSource = true))
        val text = ArrayLookKit.textArray(c, TextItem("Go", spec = TextSpec(sizePx = 30f, bold = true, color = GRAY), cx = 60f, cy = 200f))
        val shownItems = scene(c).layers.first { it.name == text.name }.items.size
        ArrayLookKit.edit(c, text, text.array!!.spec.copy(hideSource = true))
        val s = scene(c)
        val v = s.layers.first { it.name == vector.name }.items.map { it as SceneItem.Shape }
        assertEquals("two copies, no source", 2, v.size)
        val sourceBox = RectF(20f, 30f, 60f, 70f)
        for (it in v) {
            val b = it.path.bounds()!!
            assertTrue("no item at the source's place", b.left > sourceBox.right - 1f)
        }
        val t = s.layers.first { it.name == text.name }.items
        assertEquals("the text's copies without its own outlines", shownItems / 3 * 2, t.size)
        assertNotEquals(0, t.size)
    }

    @Test
    fun applyBakesTheCopiesWithoutTheSource() {
        val c = ArrayLookKit.controller(app)
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 40f, cy = 50f, w = 40f, h = 40f, style = ShapeStyle.FILL, strokeColor = GRAY, fillColor = GRAY)
        val shape = ArrayLookKit.shapeArray(c, o)
        ArrayLookKit.edit(c, shape, shape.array!!.spec.copy(hideSource = true))
        assertTrue(ArrayOps.apply(c, shape))
        ArrayLookKit.settle(c)
        val shapes = shape.vector!!.objects
        assertEquals("one shape per copy, the source left out", 2, shapes.size)
        assertTrue(shapes.all { it is VShape && (it.shape.cx - 40f) > 30f })
        assertEquals(0, shape.bitmap.getPixel(40, 50))

        val vector = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 130f, 60f, 170f)))
        ArrayLookKit.edit(c, vector, vector.array!!.spec.copy(hideSource = true))
        assertTrue(ArrayOps.apply(c, vector))
        ArrayLookKit.settle(c)
        assertEquals(null, vector.array)
        assertEquals("the copies alone, real objects", 2, vector.vector!!.objects.size)
        assertEquals(0, vector.bitmap.getPixel(40, 150))
        assertEquals(GRAY, vector.bitmap.getPixel(80, 150))
    }

    private fun scene(c: com.brushwork.paint.EditorController) =
        runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }

    private companion object {
        const val W = 400
        const val H = 300
    }
}
