package com.brushwork.paint.v110.g2vector

import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.v110.a6.A6Paths
import com.brushwork.paint.v110.a6.A6Render
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.convert.PathToShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 G2 (V1, R11; the lead's ruling on an open path with a fill): "Turn into shape" of an open
 * path with a fill and no line gives a closed outline with the fill alone, the open handles kept
 * and the chord as its straight closing segment, so it renders exactly as the path did (every
 * pixel), with the note [ObjectBarLabels110.NOTE_CLOSED_FILL]. Curve, Polyline and Path-tool paths,
 * 2 anchors (a point is added in the middle of the chord), a "closed" path of 2 anchors (it draws
 * as an open one), live copies. An open path with a fill AND a line stays a line that draws the
 * line exactly and loses the fill ([ObjectBarLabels110.NOTE_OPEN_FILL]), as before.
 */
@RunWith(RobolectricTestRunner::class)
class G2VectorOpenFillConvertTest {
    private val fill = VPaint.Solid(RED)

    /** A path of 2 anchors whose one segment bulges up (a Path-tool-like Bézier with explicit handles). */
    private fun arc(closed: Boolean) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(80f, 220f, true, outX = 50f, outY = -150f), VAnchor(320f, 220f, true, inX = -60f, inY = -140f)), closed)),
        fill = fill,
    )

    /** [a] and [b] render the same pixels, every one of them, and something is drawn. */
    private fun assertSamePixels(what: String, a: VObject, b: VObject) {
        val pa = A6Render.render(a)
        val pb = A6Render.render(b)
        assertTrue("$what: something is drawn", A6Render.ink(pa).count { it } > 100)
        var off = 0
        var worst = 0
        for (i in pa.indices) {
            if (pa[i] == pb[i]) continue
            off++
            for (s in 0..24 step 8) worst = maxOf(worst, kotlin.math.abs(((pa[i] ushr s) and 0xFF) - ((pb[i] ushr s) and 0xFF)))
        }
        assertEquals("$what: pixels that differ (largest channel difference $worst)", 0, off)
    }

    @Test
    fun anOpenFillWithoutALineBecomesAClosedFillThatLooksTheSame() {
        val mirror = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), A6Render.W, A6Render.H))
        val cases = listOf(
            "Curve" to A6Paths.curve(closed = false),
            "Polyline" to A6Paths.polyline(closed = false),
            "Path tool" to A6Paths.spline(cyclic = false),
            "2 anchors" to arc(closed = false),
            "closed with 2 anchors" to arc(closed = true),
            "with live copies" to A6Paths.curve(closed = false).copy(copies = mirror),
        )
        for ((what, p) in cases) {
            val path = p.copy(fill = fill, stroke = null)
            val r = PathToShape.convert(path)
            val o = r.shape.shape
            assertEquals("$what: the note", listOf(ObjectBarLabels110.NOTE_CLOSED_FILL), r.notes)
            assertEquals("$what: a closed outline", ShapeType.RECTANGLE, o.type)
            assertEquals("$what: the fill alone", ShapeStyle.FILL, o.style)
            assertEquals("$what: its color", RED, o.fillColor)
            val n = path.subpaths[0].anchors.size
            assertEquals("$what: the points (one more in the chord's middle for 2)", maxOf(n, 3), o.points!!.size)
            assertSamePixels(what, path, r.shape)
        }
        // A gradient keeps its first color, now with the fill: two notes in their order.
        val g = PathToShape.convert(A6Paths.curve(closed = false).copy(stroke = null, fill = VPaint.Linear(0f, 0f, 1f, 1f, listOf(VStop(0f, RED), VStop(1f, BLUE)))))
        assertEquals(listOf(ObjectBarLabels110.NOTE_GRADIENT, ObjectBarLabels110.NOTE_CLOSED_FILL), g.notes)
        assertEquals(ShapeStyle.FILL, g.shape.shape.style)
    }

    @Test
    fun anOpenFillWithALineStaysALineAndLosesTheFill() {
        for ((what, p) in listOf("open Curve" to A6Paths.curve(closed = false), "closed with 2 anchors" to arc(closed = true).copy(stroke = A6Paths.curve().stroke))) {
            val path = p.copy(fill = fill)
            val r = PathToShape.convert(path)
            assertEquals("$what: the note", listOf(ObjectBarLabels110.NOTE_OPEN_FILL), r.notes)
            assertEquals("$what: a line", ShapeType.LINE, r.shape.shape.type)
            assertEquals("$what: the line alone", ShapeStyle.STROKE, r.shape.shape.style)
            // The line exactly as the path drew it, without the fill.
            assertSamePixels(what, path.copy(fill = null), r.shape)
        }
    }

    private companion object {
        const val RED = 0xFFE04020.toInt()
        const val BLUE = 0xFF203080.toInt()
    }
}
