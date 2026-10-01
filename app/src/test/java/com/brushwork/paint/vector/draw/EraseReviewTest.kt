package com.brushwork.paint.vector.draw

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5 A3 review (JVM): only the objects near the eraser get their full geometry (a layer of
 * many paths is not flattened whole at the first touch), the cheap boxes still hold every
 * path, and "To intersection" acts where the eraser meets a line's paint, as the other modes do.
 */
class EraseReviewTest {
    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, size: Float, n: Int = 31): VStroke {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        val pen = BrushLibrary.defaultBrush.copy(size = size, scatter = 0f)
        return VStroke(0, preset = pen, color = 0xFF000000.toInt(), seed = 1L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    /** A wavy open curve (automatic tangents) starting at ([x], [y]). */
    private fun curve(x: Float, y: Float, width: Float = 4f) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(x, y), VAnchor(x + 40f, y - 30f), VAnchor(x + 80f, y + 30f), VAnchor(x + 120f, y)))),
        tension = 0.2f,
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = width),
    )

    @Test
    fun onlyPathsNearTheEraserGetTheirFullGeometry() {
        val far = List(150) { curve(2000f + (it % 15) * 150f, 2000f + (it / 15) * 100f) }
        val near = curve(20f, 60f)
        val content = VectorContent(objects = far + near).let { c -> VectorContent(objects = c.objects.mapIndexed { i, o -> o.withId(i + 1L) }, nextId = c.objects.size + 1L) }
        val cache = TargetCache()
        val s = EraseSession(content, VectorEraseMode.OBJECT, cache)
        s.add(80f, 0f, 4f)
        s.add(80f, 120f, 4f)
        // The curve under the eraser goes; the far ones were only boxed.
        assertEquals(listOf(content.objects.last().id), content.objects.filter { o -> s.result()!!.byId(o.id) == null }.map { it.id })
        assertTrue("full geometry of ${cache.targetCount} objects", cache.targetCount <= 2)
        // The cheap box holds the whole curve (its flattened geometry) and its paint.
        for (o in listOf(near) + far.take(5)) {
            val b = cache.boundsOf(o)
            val tg = EraseTarget.of(o)
            assertTrue(b[0] <= tg.left && b[1] <= tg.top && b[2] >= tg.right && b[3] >= tg.bottom)
        }
    }

    @Test
    fun toIntersectionActsWhereTheEraserMeetsAThickLinesPaint() {
        // A 30 px line (y = 100) crossed by thin lines at x = 100 and x = 200.
        val content = VectorContent(
            objects = listOf(
                stroke(0f, 100f, 300f, 100f, size = 30f).withId(1),
                stroke(100f, 20f, 100f, 180f, size = 4f).withId(2),
                stroke(200f, 20f, 200f, 180f, size = 4f).withId(3),
            ),
            nextId = 4,
        )
        // A small eraser (radius 4) over the line's upper edge, 14 px from its centerline (it
        // touched only the centerline before: nothing happened).
        val s = EraseSession(content, VectorEraseMode.TO_INTERSECTION)
        s.add(140f, 86f, 4f)
        s.add(160f, 86f, 4f)
        val after = s.result()
        assertNotNull("the eraser over the line's paint takes its piece", after)
        val horizontal = after!!.objects.filterIsInstance<VStroke>().filter { st -> (0 until st.points.size).all { st.points.y[it] == 100f } }
        assertEquals(2, horizontal.size)
        assertEquals(100f, horizontal[0].points.x[horizontal[0].points.size - 1], 1e-3f)
        assertEquals(200f, horizontal[1].points.x[0], 1e-3f)
        // The thin crossing lines stay.
        assertEquals(content.byId(2), after.byId(2))
        assertEquals(content.byId(3), after.byId(3))
        // Far from the paint: nothing.
        assertNull(EraseSession(content, VectorEraseMode.TO_INTERSECTION).also { it.add(150f, 70f, 4f); it.add(160f, 70f, 4f) }.result())
        // An eraser sitting right on a crossing reaches exactly its spill into the pieces on both
        // sides of it (of both lines): none of them goes.
        assertNull(EraseSession(content, VectorEraseMode.TO_INTERSECTION).also { it.add(100f, 100f, 4f) }.result())
        // Moved a little into the middle piece of the thick line: that piece goes.
        val nudged = EraseSession(content, VectorEraseMode.TO_INTERSECTION).also { it.add(100f, 100f, 4f); it.add(130f, 100f, 4f) }.result()
        assertNotNull(nudged)
        assertEquals(4, nudged!!.objects.size)
    }

    @Test
    fun anEraserOnACrossingIsNotAPieceOfEither() {
        // Pieces [0, 30], [30, 70], [70, 100] of a 100 px line; touched exactly a spill around 30.
        val line = FlatLine(floatArrayOf(0f, 100f), floatArrayOf(0f, 0f))
        val t = Intervals().also { it.add(0.25f, 0.35f) }
        val out = Intervals()
        EraseMath.piecesToIntersection(line, listOf(0.3f, 0.7f), t, 5f, out)
        assertTrue(out.isEmpty)
        assertTrue(t.overlaps(0.35f, 0.5f))
        assertTrue(!t.reachesInto(0.35f, 0.5f))
    }
}
