package com.brushwork.paint.v19.a1

import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.vector.CurveGroupEdit
import com.brushwork.paint.ui.vector.stepThickness
import com.brushwork.paint.v19.a1.A1Tools.CURVES
import com.brushwork.paint.v19.a1.A1Tools.steps
import com.brushwork.paint.v19.a1.A1Tools.withPoints
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 R2 (design 1 R2 (e)): point thickness never sticks at 0 %, on Polyline, Curve and Path.
 * Several points all at 0 % dragged to 50 % all read 50 % (one undo and one redo restore each
 * side exactly); points at 0 % and 40 % dragged from
 * their mean 20 % to 30 % read 10 % and 60 %; › from 0 % reads one step (5 %); one point at 0 %
 * goes up again by its slider and by ›. Tool level, through [CurveGroupEdit] (what the group
 * control runs), no composition.
 */
@RunWith(RobolectricTestRunner::class)
class ThicknessFromZeroTest {

    private fun group(t: CurveTool, vararg percents: Float, at: IntArray = IntArray(percents.size) { it + 1 }): CurveGroupEdit {
        t.selectPoints(PointSelection.of(t.pointCount, *at))
        t.setWidths(t.pointSelection.indices, FloatArray(percents.size) { percents[it] / 100f })
        t.endNumericEdit()
        return CurveGroupEdit(t, CurveGroupEdit.Kind.THICKNESS)
    }

    private fun percentsOf(t: CurveTool) = t.selectedWidths().map { it * 100f }.toFloatArray()

    @Test
    fun pointsAllAtZeroTakeTheThumb() {
        for (id in CURVES) {
            val (_, t) = withPoints(id)
            val e = group(t, 0f, 0f, 0f)
            assertEquals("$id", "0 %", e.text())
            assertEquals("$id: the thumb at the mean", 0f, e.fraction(), 0f)
            val before = steps(t)
            e.begin()
            e.dragTo(20f)
            assertEquals("$id: the value while dragging (no factor from 0)", "20 %", e.text())
            e.dragTo(50f)
            e.end()
            assertArrayEquals("$id", floatArrayOf(50f, 50f, 50f), percentsOf(t), 1e-3f)
            assertEquals("$id", "50 %", e.text())
            assertEquals("$id: one drag, one step", before + 1, steps(t))
            t.undoStep()
            assertArrayEquals("$id: one undo, back at 0", floatArrayOf(0f, 0f, 0f), percentsOf(t), 0f)
            assertTrue(t.redoStep())
            assertArrayEquals("$id: one redo, 50 % again", floatArrayOf(50f, 50f, 50f), percentsOf(t), 1e-3f)
        }
    }

    @Test
    fun aPointAtZeroRisesByTheDragsOffset() {
        for (id in CURVES) {
            val (_, t) = withPoints(id)
            val e = group(t, 0f, 40f)
            assertEquals("$id: the thumb sits at the mean, 20 %", 20f / 300f, e.fraction(), 1e-6f)
            e.begin()
            e.dragTo(30f)
            assertEquals("$id: the factor while dragging", "×1.5", e.text())
            e.end()
            assertArrayEquals("$id", floatArrayOf(10f, 60f), percentsOf(t), 1e-3f)
            // A drag down leaves a point at 0 % at 0 (the offset is negative).
            val e2 = group(t, 0f, 40f)
            e2.begin()
            e2.dragTo(10f)
            e2.end()
            assertArrayEquals("$id", floatArrayOf(0f, 20f), percentsOf(t), 1e-3f)
        }
    }

    @Test
    fun theFingersTravelMovesTheThumbByWholeSteps() {
        for (id in CURVES) {
            val (_, t) = withPoints(id)
            val e = group(t, 0f, 0f)
            e.begin()
            // 50 % of 300 %: a sixth of the track (plus a little, which the 5 % grid drops).
            e.drag(1f / 6f + 0.004f)
            assertEquals("$id", 50f, e.thumb!!, 0f)
            e.end()
            assertArrayEquals("$id", floatArrayOf(50f, 50f), percentsOf(t), 1e-3f)
            // Down to 0 and back up in one drag: the points follow the values captured at its start.
            val e2 = group(t, 40f, 80f)
            e2.begin()
            e2.dragTo(0f)
            assertArrayEquals("$id", floatArrayOf(0f, 0f), percentsOf(t), 0f)
            e2.dragTo(60f)
            e2.end()
            assertArrayEquals("$id", floatArrayOf(40f, 80f), percentsOf(t), 1e-3f)
        }
    }

    @Test
    fun theArrowFromZeroGivesOneStep() {
        for (id in CURVES) {
            val (_, t) = withPoints(id)
            val e = group(t, 0f, 0f, 12f)
            e.step(up = true)
            t.endNumericEdit()
            assertArrayEquals("$id: › moves each point to its next 5 % value", floatArrayOf(5f, 5f, 15f), percentsOf(t), 1e-3f)
            e.step(up = false)
            t.endNumericEdit()
            assertArrayEquals("$id: ‹", floatArrayOf(0f, 0f, 10f), percentsOf(t), 1e-3f)
            e.step(up = false)
            t.endNumericEdit()
            assertArrayEquals("$id: ‹ stops at 0", floatArrayOf(0f, 0f, 5f), percentsOf(t), 1e-3f)
        }
    }

    @Test
    fun onePointAtZeroGoesUpAgain() {
        for (id in CURVES) {
            val (_, t) = withPoints(id)
            t.select(2)
            t.setWidth(2, 0f)
            t.endNumericEdit()
            assertEquals("$id", 0f, t.widthOf(2), 0f)
            // The one-point slider (its drag sets the value it reaches).
            t.beginNumericEdit()
            t.setWidth(2, 0.25f)
            t.setWidth(2, 0.5f)
            t.endNumericEdit()
            assertEquals("$id: the slider from 0 %", 0.5f, t.widthOf(2), 0f)
            t.setWidth(2, 0f)
            t.endNumericEdit()
            // The one-point ›, as ThicknessControl calls it.
            t.setWidth(2, stepThickness(t.widthOf(2) * 100f, up = true, 5f) / 100f)
            t.endNumericEdit()
            assertEquals("$id: › from 0 % gives one step", 0.05f, t.widthOf(2), 1e-6f)
        }
    }
}
