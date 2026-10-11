package com.brushwork.paint.exchange.export

import android.graphics.Matrix
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayLookKit.BLUE
import com.brushwork.paint.array.ArrayLookKit.FADE_AND_TINT
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.vector.VPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.8 (area B, item 2, design §3.3): an Array look exports as the cache draws it. A vector
 * array's copies are their restyled objects (tinted paints, opacity multiplied: R12); a text
 * array's copies their outlines, each part's paint tinted and its opacity multiplied (the same
 * rule applied to the outlines); a raster array its cache, the looks in its pixels. The export
 * reaches the last copy grown by End size.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayLookExportTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun scene(c: EditorController): ExportScene =
        runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }

    private fun ExportScene.of(layer: Layer): SceneLayer = layers.first { it.name == layer.name }

    private fun union(items: List<SceneItem>): RectF = RectF().also { r ->
        for (it in items) (it as SceneItem.Shape).path.bounds()?.let { b -> RectF(b.left, b.top, b.right, b.bottom).let { f -> if (r.isEmpty) r.set(f) else r.union(f) } }
    }

    /** [r] mapped by the last copy's matrix (copy N − 1, End size and turn in it). */
    private fun lastCopy(layer: Layer, r: RectF): RectF {
        val spec = layer.array!!.spec
        val source = ArrayDraw.sourceBounds(layer.dataSnapshot())!!
        return RectF(r).also { Matrix().apply { setValues(ArrayInstances.of(spec, source).first().matrix) }.mapRect(it) }
    }

    private fun copies(layer: Layer): Int = ArrayInstances.of(layer.array!!.spec, ArrayDraw.sourceBounds(layer.dataSnapshot())!!).size

    @Test
    fun vectorCopiesExportAsTheirRestyledObjects() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 60f, 70f)))
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(look = FADE_AND_TINT))
        val items = scene(c).of(layer).items.map { it as SceneItem.Shape }
        assertEquals("one shape per copy", 3, items.size)
        // Copy 2 (End) at the bottom: blue, half; copy 1 (Start): red; the source on top, as it is.
        assertEquals(VPaint.Solid(ArrayTint.apply(GRAY, BLUE, 1f)), items[0].fill)
        assertEquals(0.5f, items[0].opacity, 1e-6f)
        assertEquals(VPaint.Solid(ArrayTint.apply(GRAY, RED, 1f)), items[1].fill)
        assertEquals(1f, items[1].opacity, 1e-6f)
        assertEquals(VPaint.Solid(GRAY), items[2].fill)
        assertEquals(1f, items[2].opacity, 1e-6f)
    }

    @Test
    fun textCopiesExportAsTheirTintedOutlines() {
        val c = ArrayLookKit.controller(app)
        val color = 0xFF3060C0.toInt()
        val item = TextItem("Hi", spec = TextSpec(sizePx = 40f, color = color), cx = 70f, cy = 80f)
        val layer = ArrayLookKit.textArray(c, item)
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(look = FADE_AND_TINT))
        val items = scene(c).of(layer).items.map { it as SceneItem.Shape }
        assertEquals("one outline per copy", 3, items.size)
        assertEquals(VPaint.Solid(ArrayTint.apply(color, BLUE, 1f)), items[0].fill)
        assertEquals(0.5f, items[0].opacity, 1e-6f)
        assertEquals(VPaint.Solid(ArrayTint.apply(color, RED, 1f)), items[1].fill)
        assertEquals(1f, items[1].opacity, 1e-6f)
        assertEquals(VPaint.Solid(color), items[2].fill)
        assertEquals(1f, items[2].opacity, 1e-6f)
    }

    @Test
    fun aRasterArrayExportsItsCacheWithTheLooksInIt() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.pixelArray(c)
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(look = FADE_AND_TINT))
        val image = (scene(c).of(layer).items.single() as SceneItem.Image).image
        val px = runBlocking { image.source.load() }.pixels
        fun at(x: Int, y: Int) = px[(y - image.top) * image.width + x - image.left]
        assertEquals(layer.bitmap.getPixel(80, 50), at(80, 50))
        assertEquals(layer.bitmap.getPixel(120, 50), at(120, 50))
        assertEquals(GRAY, at(40, 50))
    }

    @Test
    fun theExportReachesTheGrownLastCopy() {
        val c = ArrayLookKit.controller(app)
        val grow = ArrayLook(sizeEnd = 2f)
        val vector = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 50f, 60f)))
        ArrayLookKit.edit(c, vector, vector.array!!.spec.copy(look = grow))
        val text = ArrayLookKit.textArray(c, TextItem("Go", spec = TextSpec(sizePx = 30f, bold = true), cx = 60f, cy = 200f))
        ArrayLookKit.edit(c, text, text.array!!.spec.copy(look = grow))
        val s = scene(c)
        for (layer in listOf(vector, text)) {
            val items = s.of(layer).items
            // Copy N − 1's items first, the source's last: as many per copy.
            val per = items.size / copies(layer)
            assertEquals("${layer.name}: the same items per copy", per * copies(layer), items.size)
            val own = union(items.takeLast(per))
            val grown = union(items.take(per))
            val expected = lastCopy(layer, own)
            for ((what, e, a) in listOf(Triple("left", expected.left, grown.left), Triple("top", expected.top, grown.top), Triple("right", expected.right, grown.right), Triple("bottom", expected.bottom, grown.bottom))) {
                assertEquals("${layer.name}: the last copy's $what", e, a, 0.5f)
            }
            assertEquals("${layer.name}: twice the source's width", 2f * own.width(), grown.width(), 0.5f)
            val exported = union(items)
            assertTrue("${layer.name}: within the cache bounds", ArrayDraw.cacheBounds(layer.dataSnapshot())!!.let { b -> b.left <= exported.left + 0.5f && b.right >= exported.right - 0.5f && b.top <= exported.top + 0.5f && b.bottom >= exported.bottom - 0.5f })
        }
    }

    @Test
    fun withoutALookTheExportIsAsInV17() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 60f, 70f)))
        ArrayLookKit.edit(c, layer, ArraySpec(count = 4, relativeX = 1.5f))
        val items = scene(c).of(layer).items.map { it as SceneItem.Shape }
        val expected = ArrayLookKit.v17Vector(layer.dataSnapshot())
        assertEquals(expected.size, items.size)
        for ((i, o) in expected.withIndex()) {
            val b = com.brushwork.paint.vector.VectorOps.bounds(o)
            val p = items[i].path.bounds()!!.let { RectF(it.left, it.top, it.right, it.bottom) }
            assertEquals(b.centerX(), p.centerX(), 0.5f)
            assertEquals(VPaint.Solid(GRAY), items[i].fill)
            assertEquals(1f, items[i].opacity, 0f)
        }
    }
}
