package com.brushwork.paint.array

import com.brushwork.paint.array.ArrayLookKit.BLUE
import com.brushwork.paint.array.ArrayLookKit.FADE_AND_TINT
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.array.ArrayLookKit.pixels
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VectorOps
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * v1.8 (area B, item 2, design §3.3): "Apply array" bakes every copy's look exactly as the cache
 * draws it, in one step "Apply array": a vector array's copies become real objects already
 * restyled (tinted colors, multiplied opacity), a shape array one restyled shape per copy (a copy
 * with opacity 0 is left out, as drawn), and a raster or text array keeps its cache's pixels as
 * they are. One undo brings the live array back with its look.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayLookApplyTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun oneStep(c: com.brushwork.paint.EditorController, label: String, block: () -> Unit) {
        val steps = c.undoManager.undoCount
        block()
        ArrayLookKit.settle(c)
        assertEquals("$label: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
    }

    /**
     * Every pixel of [a] within [tol] of [e] per PREMULTIPLIED channel (what shows: a fully
     * transparent pixel's color, or a faint one's unpremultiplied rounding, does not). With a
     * [width], a pixel on an anti-aliased edge of [e] (its 3×3 neighbourhood varies: an outline, or
     * the line between a stroke and its fill) is held to [edgeTol] instead: a turned shape drawn
     * under the copy's matrix and the same shape baked with its rotation rasterize their edge
     * coverage a little apart.
     */
    private fun assertClose(what: String, e: IntArray, a: IntArray, tol: Int, width: Int = 0, edgeTol: Int = tol) {
        fun valueAt(i: Int) = e[i]
        fun onEdge(i: Int): Boolean {
            if (width <= 0) return false
            val x = i % width
            val y = i / width
            val h = e.size / width
            val mid = valueAt(i)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until h && valueAt(ny * width + nx) != mid) return true
            }
            return false
        }
        var worst = 0
        var at = -1
        var worstEdge = 0
        var atEdge = -1
        for (i in e.indices) {
            val ea = e[i] ushr 24
            val aa = a[i] ushr 24
            var d = abs(ea - aa)
            for (s in intArrayOf(16, 8, 0)) d = maxOf(d, abs(((e[i] shr s) and 0xFF) * ea / 255 - ((a[i] shr s) and 0xFF) * aa / 255))
            if (d <= tol) continue
            if (onEdge(i)) { if (d > worstEdge) { worstEdge = d; atEdge = i } } else if (d > worst) { worst = d; at = i }
        }
        fun hex(i: Int) = if (i >= 0) "${Integer.toHexString(e[i])} vs ${Integer.toHexString(a[i])}" else ""
        assertTrue("$what: off by $worst at $at (${hex(at)})", worst <= tol)
        assertTrue("$what: an edge off by $worstEdge at $atEdge (${hex(atEdge)})", worstEdge <= edgeTol)
    }

    @Test
    fun vectorCopiesBecomeRestyledObjects() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 60f, 70f, stroke = 0xFF202020.toInt())))
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(count = 4, look = FADE_AND_TINT.copy(sizeEnd = 1.5f)))
        val shown = ArrayDraw.effectiveVector(layer.dataSnapshot())!!.objects
        val cache = pixels(layer.bitmap)
        oneStep(c, ArrayLabels.APPLY) { assertTrue(ArrayOps.apply(c, layer)) }
        assertNull(layer.array)
        val objects = layer.vector!!.objects
        assertEquals(shown.size, objects.size)
        // The same objects as drawn, with real ids.
        for (i in shown.indices) assertEquals(shown[i].withId(objects[i].id), objects[i])
        assertEquals("new ids", objects.size, objects.map { it.id }.toSet().size)
        assertTrue(objects.all { it.id < (1L shl ArrayDraw.COPY_ID_SHIFT) })
        assertEquals(VPaint.Solid(ArrayTint.apply(GRAY, BLUE, 1f)), (objects[0] as VPath).fill)
        assertEquals(0.5f, objects[0].opacity)
        assertClose("the layer looks the same", cache, pixels(layer.bitmap), 0)
        c.undo()
        ArrayLookKit.settle(c)
        assertEquals(FADE_AND_TINT.copy(sizeEnd = 1.5f), layer.array!!.spec.look)
        assertArrayEquals(cache, pixels(layer.bitmap))
    }

    @Test
    fun shapeCopiesBecomeRestyledShapes() {
        val c = ArrayLookKit.controller(app)
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 50f, cy = 60f, w = 40f, h = 40f, style = ShapeStyle.STROKE_FILL, strokeWidth = 6f, strokeColor = 0xFF203040.toInt(), fillColor = GRAY)
        val layer = ArrayLookKit.shapeArray(c, o)
        val look = ArrayLook(opacityStart = 0.8f, opacityEnd = 0f, tint = listOf(VStop(0f, RED), VStop(1f, BLUE)), tintStrength = 0.7f, turnEnd = 20f)
        val spec = ArraySpec(count = 4, relativeX = 1.3f, look = look)
        ArrayLookKit.edit(c, layer, spec)
        val cache = pixels(layer.bitmap)
        val source = ArrayDraw.sourceBounds(layer.dataSnapshot())!!
        val instances = ArrayInstances.of(spec, source)
        oneStep(c, ArrayLabels.APPLY) { assertTrue(ArrayOps.apply(c, layer)) }
        assertNull(layer.shapeData)
        assertNull(layer.array)
        val shapes = layer.vector!!.objects.map { it as VShape }
        // Copy 3 (End) has opacity 0: left out, as drawn. Copies 2, 1, then the source.
        assertEquals(3, shapes.size)
        val drawn = instances.filter { it.look == null || it.look!!.opacity > 0f }
        assertEquals(listOf(2, 1, 0), drawn.map { it.k })
        for ((i, inst) in drawn.withIndex()) {
            val expected = ArrayDraw.restyled(VectorOps.transformed(VShape(0L, shape = o), inst.matrix), inst.look) as VShape
            assertEquals("copy ${inst.k}", expected.shape, shapes[i].shape)
            assertEquals("copy ${inst.k}", expected.opacity, shapes[i].opacity)
        }
        assertEquals(o, shapes.last().shape)
        assertEquals(1f, shapes.last().opacity)
        // The vector layer looks as the shape array did: within a premultiplied rounding inside
        // the shapes, within anti-aliasing on the turned copies' edges.
        assertClose("the layer looks the same", cache, pixels(layer.bitmap), 3, layer.bitmap.width, edgeTol = 48)
    }

    @Test
    fun pixelAndTextArraysKeepTheirCache() {
        val c = ArrayLookKit.controller(app)
        val raster = ArrayLookKit.pixelArray(c)
        ArrayLookKit.edit(c, raster, raster.array!!.spec.copy(look = FADE_AND_TINT))
        val rasterCache = pixels(raster.bitmap)
        oneStep(c, ArrayLabels.APPLY) { assertTrue(ArrayOps.apply(c, raster)) }
        assertNull(raster.array)
        assertArrayEquals(rasterCache, pixels(raster.bitmap))

        val item = TextItem("Ab", spec = TextSpec(sizePx = 40f, color = 0xFF3060C0.toInt()), cx = 80f, cy = 200f)
        val text = ArrayLookKit.textArray(c, item)
        ArrayLookKit.edit(c, text, text.array!!.spec.copy(look = FADE_AND_TINT.copy(sizeEnd = 0.5f)))
        val textCache = pixels(text.bitmap)
        assertTrue(ArrayOps.apply(c, text))
        val tool = c.tools[ToolId.ARRAY] as ArrayTool
        assertNotNull(tool.pendingTextApply)
        oneStep(c, ArrayLabels.APPLY) { tool.answerTextApply(true) }
        assertNull(text.textData)
        assertNull(text.array)
        assertArrayEquals(textCache, pixels(text.bitmap))
        c.undo()
        ArrayLookKit.settle(c)
        assertEquals(FADE_AND_TINT.copy(sizeEnd = 0.5f), text.array!!.spec.look)
        assertArrayEquals(textCache, pixels(text.bitmap))
    }
}
