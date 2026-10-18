package com.brushwork.paint.v19.a5

import android.graphics.Path
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.draw.EraseCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R11 c, A5 step 9): the partial eraser's region cut at the lift ([EraseCut.cutAll], what
 * runs on `Dispatchers.Default`) takes at most 150 ms for 50 filled targets and 1.5 s for 500
 * ([EraseCut.MAX_TARGETS]), each target crossed by the eraser (x4 on CI). The targets are half
 * filled and stroked paths, half filled and stroked ellipses (a cut shape reopens as a path), on a
 * 512 × 512 document; the eraser is a round 6 px band zigzagging through every row of them.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class EraseCutPerfTest {

    /** [n] filled targets in a grid of rows of 25 (20 px cells), bottom first. */
    private fun targets(n: Int): List<VObject> = List(n) { i ->
        val x = 6f + (i % COLUMNS) * CELL
        val y = 6f + (i / COLUMNS) * CELL
        val id = (i + 1).toLong()
        if (i % 2 == 0) {
            VPath(
                id,
                subpaths = listOf(VSubpath(listOf(VAnchor(x, y), VAnchor(x + 14f, y), VAnchor(x + 14f, y + 14f), VAnchor(x, y + 14f)), closed = true)),
                fill = VPaint.Solid(0xFF3070C0.toInt()), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 2f),
            )
        } else {
            VShape(id, shape = ShapeObject(ShapeType.ELLIPSE, cx = x + 7f, cy = y + 7f, w = 14f, h = 14f, style = ShapeStyle.STROKE_FILL, strokeWidth = 2f))
        }
    }

    /** A round band of radius 3 through the middle of every row of [n] targets, back and forth. */
    private fun eraser(n: Int): Path {
        val rows = (n + COLUMNS - 1) / COLUMNS
        val centre = Path()
        for (r in 0 until rows) {
            val y = 6f + r * CELL + 7f
            val (a, b) = if (r % 2 == 0) 0f to 512f else 512f to 0f
            if (r == 0) centre.moveTo(a, y) else centre.lineTo(a, y)
            centre.lineTo(b, y)
        }
        return EraseCut.strokeRegion(centre, 3f)
    }

    private fun cutsWithin(n: Int, desktopMs: Double) {
        val t = targets(n)
        val e = eraser(n)
        var cuts: EraseCut.Cuts? = null
        val ms = PerfBudget.within(PerfBudget.ms(desktopMs), what = "the region cut of $n filled targets") { cuts = EraseCut.cutAll(t, e) }
        println("v19 A5 cut: $n filled targets in ${"%.1f".format(ms)} ms (budget ${PerfBudget.ms(desktopMs)})")
        val result = cuts!!
        assertEquals("every target is cut", n, result.pieces.size)
        assertTrue("each into two pieces", result.pieces.values.all { it.size == 2 })
        assertFalse(result.tooComplex)
    }

    @Test
    fun fiftyFilledTargetsAreCutWithin150Ms() = cutsWithin(50, 150.0)

    @Test
    fun fiveHundredFilledTargetsAreCutWithin1500Ms() = cutsWithin(EraseCut.MAX_TARGETS, 1500.0)

    private companion object {
        const val COLUMNS = 25
        const val CELL = 20f
    }
}
