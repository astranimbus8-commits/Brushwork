package com.brushwork.paint.array

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit.pixels
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.8 (area B, item 2, design §3.3): with End size 200 % the last copy doubles about its own
 * centre, past where v1.7's placement alone reaches. Everything that bounds a cache follows it:
 * `ArrayDraw.cacheBounds` (`ArrayInstances.bounds`), so the render's dirty rectangle covers the
 * grown copy and an undo leaves no stale pixels; `DataRender.paintBounds` (the transform tools'
 * lift box and hit area); and the export (see [ArrayLookExportTest]).
 */
@RunWith(RobolectricTestRunner::class)
class ArrayBoundsWithSizeGradientTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val grow = ArrayLook(sizeEnd = 2f)

    /** The last copy's bounds: [source] mapped by its matrix. */
    private fun lastCopy(spec: ArraySpec, source: RectF): RectF {
        val last = ArrayInstances.of(spec, source).first()
        return RectF(source).also { Matrix().apply { setValues(last.matrix) }.mapRect(it) }
    }

    private fun assertCovers(what: String, outer: RectF, inner: RectF) {
        assertTrue("$what: $outer covers $inner", outer.left <= inner.left + 1e-3f && outer.top <= inner.top + 1e-3f && outer.right >= inner.right - 1e-3f && outer.bottom >= inner.bottom - 1e-3f)
    }

    /** [layer]'s array grown (End size 200 %) in one edit: its bounds, its cache, its undo. */
    private fun check(c: EditorController, layer: Layer, fresh: (Layer) -> IntArray) {
        val before = pixels(layer.bitmap)
        val spec0 = layer.array!!.spec
        val spec = spec0.copy(look = grow)
        ArrayLookKit.edit(c, layer, spec)
        val d = layer.dataSnapshot()
        val source = ArrayDraw.sourceBounds(d)!!
        val last = lastCopy(spec, source)
        val bounds = ArrayDraw.cacheBounds(d)!!
        assertEquals(ArrayInstances.bounds(spec, source), bounds)
        assertCovers("cache bounds", bounds, last)
        assertTrue("past v1.7's placement", bounds.right > ArrayLayout.bounds(spec0, source).right + 1f)
        // The transform tools' lift box and hit area.
        assertEquals(bounds, DataRender.paintBounds(d))
        // The cache shows the whole grown copy: as a fresh draw of everything.
        assertArrayEquals("${layer.name}: the cache", fresh(layer), pixels(layer.bitmap))
        // Some of the grown copy lies past v1.7's placement, and it is drawn.
        val v17 = ArrayLayout.bounds(spec0, source)
        var past = 0
        for (y in maxOf(0, last.top.toInt()) until minOf(layer.bitmap.height, last.bottom.toInt())) {
            for (x in v17.right.toInt() + 1 until minOf(layer.bitmap.width, last.right.toInt())) if (layer.bitmap.getPixel(x, y) ushr 24 != 0) past++
        }
        assertNotEquals("${layer.name}: pixels past v1.7's placement", 0, past)
        // One undo: v1.7's copies, no stale pixels where the grown copy was.
        c.undo()
        ArrayLookKit.settle(c)
        assertArrayEquals("${layer.name}: undone", before, pixels(layer.bitmap))
    }

    @Test
    fun textAndShapeCachesCoverTheGrownLastCopy() {
        val c = ArrayLookKit.controller(app, 400, 300)
        val item = TextItem("Go", spec = TextSpec(sizePx = 36f, bold = true), cx = 60f, cy = 60f)
        val text = ArrayLookKit.textArray(c, item)
        check(c, text) { l ->
            val b = BitmapUtils.createLayerBitmap(400, 300)
            ArrayDraw.drawWithArray(Canvas(b), l.array, ArrayDraw.sourceBounds(l.dataSnapshot())!!, ArrayLookKit.textDraw(item))
            pixels(b)
        }
        val o = ShapeObject(ShapeType.ELLIPSE, cx = 50f, cy = 200f, w = 40f, h = 30f, style = ShapeStyle.FILL, fillColor = 0xFF20A040.toInt())
        val shape = ArrayLookKit.shapeArray(c, o)
        check(c, shape) { l ->
            val b = BitmapUtils.createLayerBitmap(400, 300)
            ArrayDraw.drawWithArray(Canvas(b), l.array, ArrayDraw.sourceBounds(l.dataSnapshot())!!, ArraySources.shapeDraw(o, c.doc.colorMode, 400, 300))
            pixels(b)
        }
    }

    @Test
    fun rasterAndVectorCachesCoverTheGrownLastCopy() {
        val c = ArrayLookKit.controller(app, 400, 300)
        val raster = ArrayLookKit.pixelArray(c, l = 20, t = 30, size = 30)
        check(c, raster) { l ->
            val b = BitmapUtils.createLayerBitmap(400, 300)
            ArrayDraw.drawPixels(Canvas(b), l.array!!)
            pixels(b)
        }
        val vector = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 200f, 50f, 240f, stroke = 0xFF101010.toInt())))
        check(c, vector) { l ->
            val b = BitmapUtils.createLayerBitmap(400, 300)
            VectorLayerRenderer.render(Canvas(b), ArrayDraw.effectiveVector(l.dataSnapshot())!!, Rect(0, 0, 400, 300), tips = TipCache(), document = Rect(0, 0, 400, 300))
            pixels(b)
        }
    }
}
