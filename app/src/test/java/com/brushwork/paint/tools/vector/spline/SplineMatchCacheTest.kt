package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 review fix (area B): [SplineBezier.matches] keeps its last answer for the same (immutable)
 * path instance only, so a tap and the reopen that follows it convert a big spline once; any
 * other instance, equal or not, is checked again.
 */
@RunWith(RobolectricTestRunner::class)
class SplineMatchCacheTest {

    private fun path(dy: Float = 0f, weight: Float = 2f): VPath {
        val pts = List(40) { i -> VSplinePoint(10f + i * 9f, 100f + ((i * 37) % 23) * 4f, weight = if (i % 3 == 0) weight else 1f) }
        val sp = VSpline(pts, order = 5)
        val stored = SplineBezier.toSubpath(sp)
        // [dy] moves the control points away from the stored Bézier form (a stale spline).
        return VPath(id = 7, subpaths = listOf(stored), spline = sp.copy(points = sp.points.map { it.copy(y = it.y + dy) }))
    }

    @Test
    fun theLastAnswerIsKeptForTheSameInstanceOnly() {
        val good = path()
        val stale = path(dy = 30f)
        assertTrue(SplineBezier.matches(good))
        assertTrue(SplineBezier.matches(good))
        assertFalse(SplineBezier.matches(stale))
        assertFalse(SplineBezier.matches(stale))
        assertTrue(SplineBezier.matches(good))
        // An equal copy is checked on its own (same answer); a copy with a stale spline is not taken for it.
        assertTrue(SplineBezier.matches(good.copy()))
        assertFalse(SplineBezier.matches(good.copy(spline = stale.spline)))
        assertFalse(SplineBezier.matches(good.copy(spline = null)))
    }
}
