package com.brushwork.paint.v110.a6

import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.convert.PathToShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R11 (a), "Convert with a warning"): lossy paths still convert, each loss with one
 * note in a fixed order: a gradient fill becomes its first color, varying thickness (or a taper)
 * becomes the mean thickness, a generator is dropped (its outline kept), an open path's fill is
 * dropped. A path that loses nothing has no note.
 */
@RunWith(RobolectricTestRunner::class)
class PathToShapeNotesTest {

    private fun widths(p: VPath, vararg w: Float): VPath =
        p.copy(subpaths = listOf(p.subpaths[0].copy(anchors = p.subpaths[0].anchors.mapIndexed { i, a -> a.copy(width = w[i % w.size]) })))

    @Test
    fun nothingLostNoNote() {
        for (p in listOf(A6Paths.curve(), A6Paths.curve(true).copy(fill = VPaint.Solid(RED)), A6Paths.polyline(), A6Paths.spline(true))) {
            assertNull(PathToShape.refusal(p))
            assertTrue("$p", PathToShape.convert(p).notes.isEmpty())
        }
    }

    @Test
    fun aGradientBecomesItsFirstColor() {
        val stops = listOf(VStop(0.7f, GREEN), VStop(0.1f, RED), VStop(1f, BLUE))
        for (fill in listOf(VPaint.Linear(0f, 0f, 100f, 0f, stops), VPaint.Radial(100f, 100f, 50f, stops))) {
            val r = PathToShape.convert(A6Paths.curve(closed = true).copy(fill = fill))
            assertEquals(listOf(ObjectBarLabels110.NOTE_GRADIENT), r.notes)
            assertEquals("the stop nearest the start", RED, r.shape.shape.fillColor)
            assertEquals(ShapeStyle.STROKE_FILL, r.shape.shape.style)
        }
    }

    @Test
    fun varyingThicknessBecomesTheMean() {
        val p = widths(A6Paths.curve(), 1f, 3f) // 1, 3, 1, 3: mean 2
        val r = PathToShape.convert(p)
        assertEquals(listOf(ObjectBarLabels110.NOTE_WIDTH), r.notes)
        assertEquals("4 px x the mean factor 2", 8f, r.shape.shape.strokeWidth, 1e-4f)
        // A taper is a varying thickness too.
        val tapered = A6Paths.curve().let { it.copy(stroke = it.stroke!!.copy(taperPercent = 30f)) }
        assertEquals(listOf(ObjectBarLabels110.NOTE_WIDTH), PathToShape.convert(tapered).notes)
        // Without a line there is no thickness to lose.
        assertTrue(PathToShape.convert(widths(A6Paths.curve(true), 1f, 3f).copy(stroke = null, fill = VPaint.Solid(RED))).notes.isEmpty())
    }

    @Test
    fun aGeneratorIsDroppedTheOutlineKept() {
        val r = PathToShape.convert(A6Paths.curve().copy(generator = CurveGenerator()))
        assertEquals(listOf(ObjectBarLabels110.NOTE_GENERATED), r.notes)
        assertEquals("the outline's points", 4, r.shape.shape.points!!.size)
    }

    @Test
    fun anOpenPathLosesItsFill() {
        val r = PathToShape.convert(A6Paths.curve(closed = false).copy(fill = VPaint.Solid(RED)))
        assertEquals(listOf(ObjectBarLabels110.NOTE_OPEN_FILL), r.notes)
        assertEquals("a line only", ShapeStyle.STROKE, r.shape.shape.style)
        // An open path's gradient is dropped with the fill: one note, not two.
        val g = PathToShape.convert(A6Paths.curve(closed = false).copy(fill = VPaint.Linear(0f, 0f, 1f, 1f, listOf(VStop(0f, RED), VStop(1f, BLUE)))))
        assertEquals(listOf(ObjectBarLabels110.NOTE_OPEN_FILL), g.notes)
    }

    @Test
    fun severalNotesInTheirOrder() {
        val closed = widths(A6Paths.curve(true), 1f, 2f).copy(
            fill = VPaint.Linear(0f, 0f, 1f, 1f, listOf(VStop(0f, RED), VStop(1f, BLUE))),
            generator = CurveGenerator(),
        )
        assertEquals(listOf(ObjectBarLabels110.NOTE_GRADIENT, ObjectBarLabels110.NOTE_WIDTH, ObjectBarLabels110.NOTE_GENERATED), PathToShape.convert(closed).notes)
        val open = widths(A6Paths.curve(false), 1f, 2f).copy(fill = VPaint.Solid(RED), generator = CurveGenerator())
        assertEquals(listOf(ObjectBarLabels110.NOTE_WIDTH, ObjectBarLabels110.NOTE_GENERATED, ObjectBarLabels110.NOTE_OPEN_FILL), PathToShape.convert(open).notes)
    }

    private companion object {
        const val RED = 0xFFE04020.toInt()
        const val GREEN = 0xFF20C040.toInt()
        const val BLUE = 0xFF203080.toInt()
    }
}
