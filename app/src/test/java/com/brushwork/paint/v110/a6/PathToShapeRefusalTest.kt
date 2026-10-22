package com.brushwork.paint.v110.a6

import com.brushwork.paint.tools.points.PointBevel
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.convert.PathToShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.10 A6 (R11 (a) "Refusals"): paths of several separate pieces, of fewer than 2 points, of
 * damaged coordinates or all in one place, and of more than [PointBevel.MAX_POINTS_SHAPE] (1,000)
 * points can't become a shape, each with its own reason; [PathToShape.convert] throws for them.
 * 2 points and exactly 1,000 points convert.
 */
@RunWith(RobolectricTestRunner::class)
class PathToShapeRefusalTest {
    private val line = VStrokeStyle(color = 0xFF203080.toInt(), width = 4f)

    private fun path(anchors: List<VAnchor>, closed: Boolean = false) = VPath(0, subpaths = listOf(VSubpath(anchors, closed)), stroke = line)

    /** A closed ring of [n] smooth anchors. */
    private fun ring(n: Int) = path(List(n) { i -> (2.0 * PI * i / n).let { a -> VAnchor(200f + 120f * cos(a).toFloat(), 150f + 120f * sin(a).toFloat()) } }, closed = true)

    private fun assertRefused(reason: String, p: VPath) {
        assertEquals(reason, PathToShape.refusal(p))
        assertThrows(IllegalArgumentException::class.java) { PathToShape.convert(p) }
    }

    @Test
    fun severalPiecesAreRefused() {
        assertRefused(ObjectBarLabels110.REFUSE_PIECES, A6Paths.twoParts())
    }

    @Test
    fun tooSmallPathsAreRefused() {
        assertRefused(ObjectBarLabels110.REFUSE_SMALL, A6Paths.onePoint())
        assertRefused(ObjectBarLabels110.REFUSE_SMALL, VPath(0, subpaths = emptyList(), stroke = line))
        assertRefused(ObjectBarLabels110.REFUSE_SMALL, path(emptyList()))
        assertRefused(ObjectBarLabels110.REFUSE_SMALL, path(List(5) { VAnchor(100f, 100f) }, closed = true))
        assertRefused(ObjectBarLabels110.REFUSE_SMALL, path(listOf(VAnchor(100f, 100f), VAnchor(100.0002f, 100f))))
        assertRefused(ObjectBarLabels110.REFUSE_SMALL, path(listOf(VAnchor(100f, 100f), VAnchor(Float.NaN, 100f), VAnchor(200f, 50f))))
        assertRefused(ObjectBarLabels110.REFUSE_SMALL, path(listOf(VAnchor(100f, 100f), VAnchor(200f, 100f, inX = Float.POSITIVE_INFINITY, inY = 0f))))
        assertNull("two points are a line", PathToShape.refusal(path(listOf(VAnchor(100f, 100f), VAnchor(200f, 140f)))))
        assertEquals(2, PathToShape.convert(path(listOf(VAnchor(100f, 100f), VAnchor(200f, 140f)))).shape.shape.points!!.size)
    }

    @Test
    fun moreThanAThousandPointsAreRefused() {
        assertEquals(1000, PointBevel.MAX_POINTS_SHAPE)
        assertRefused(ObjectBarLabels110.REFUSE_POINTS, ring(1001))
        // Several pieces is the first reason.
        assertEquals(ObjectBarLabels110.REFUSE_PIECES, PathToShape.refusal(ring(1001).let { it.copy(subpaths = it.subpaths + A6Paths.curve().subpaths) }))
        val most = ring(1000)
        assertNull(PathToShape.refusal(most))
        assertEquals(1000, PathToShape.convert(most).shape.shape.points!!.size)
    }
}
