package com.brushwork.paint.v19.a1

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineEditing
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.vector.CurveGroupEdit
import com.brushwork.paint.ui.vector.PATH_WEIGHT_KEY
import com.brushwork.paint.v19.a1.A1Tools.steps
import com.brushwork.paint.v19.a1.A1Tools.withPoints
import com.brushwork.paint.vector.VSpline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 R1 (design 1 R1 (a)): the Path tool's several control points' weight
 * ([com.brushwork.paint.ui.vector.GroupWeightControl]) follows the thickness rules on 0.1–10:
 * "Mixed" and the thumb at the mean on the log track; a relative drag (each weight × thumb /
 * start mean, clamped), the factor while dragging, one in-tool step; TalkBack's set-progress is
 * a drag; typed "*2", "/2", "+1", "-1" and a number. Tool level, through [CurveGroupEdit].
 */
@RunWith(RobolectricTestRunner::class)
class GroupWeightControlTest {

    private fun pathWith(vararg weights: Float): Pair<CurveTool, CurveGroupEdit> {
        val (_, t) = withPoints(ToolId.PATH)
        t.selectPoints(PointSelection.of(t.pointCount, *IntArray(weights.size) { it + 1 }))
        t.setWeights(t.pointSelection.indices, weights)
        t.endNumericEdit()
        return t to CurveGroupEdit(t, CurveGroupEdit.Kind.WEIGHT)
    }

    @Test
    fun theThumbSitsAtTheMeanAndTheBoxReadsMixed() {
        val (t, e) = pathWith(1f, 2f, 4f)
        assertEquals(PointLabels.MIXED, e.text())
        assertEquals(7f / 3f, e.thumbValue(), 1e-5f)
        assertEquals(SplineEditing.weightToFraction(7f / 3f), e.fraction(), 1e-6f)
        t.setWeights(t.pointSelection.indices, floatArrayOf(2f, 2f, 2f))
        t.endNumericEdit()
        assertEquals("the shared value", "2", e.text())
    }

    @Test
    fun aDragScalesEachWeightAndIsOneStep() {
        val (t, e) = pathWith(1f, 2f, 4f)
        val before = steps(t)
        e.begin()
        e.dragTo(7f / 3f * 2f)
        assertEquals("the factor while dragging", "×2", e.text())
        e.dragTo(7f / 3f * 1.5f)
        e.end()
        assertArrayEquals(floatArrayOf(1.5f, 3f, 6f), t.selectedWeights(), 1e-4f)
        assertEquals("one drag, one step", before + 1, steps(t))
        assertEquals(PointLabels.MIXED, e.text())
        // Clamped to 0.1–10 (the ratios give way at the ends).
        e.begin()
        e.dragTo(10f)
        e.end()
        val w = t.selectedWeights()
        assertEquals(VSpline.MAX_WEIGHT, w[2], 0f)
        assertTrue(w.all { it in VSpline.MIN_WEIGHT..VSpline.MAX_WEIGHT })
        t.undoStep()
        assertArrayEquals("one undo", floatArrayOf(1.5f, 3f, 6f), t.selectedWeights(), 1e-4f)
    }

    @Test
    fun theFingersTravelRunsAlongTheLogTrack() {
        val (t, e) = pathWith(1f, 1f)
        e.begin()
        val start = SplineEditing.weightToFraction(1f)
        e.drag(0.25f)
        val thumb = SplineEditing.fractionToWeight(start + 0.25f)
        assertEquals(thumb, e.thumb!!, 0f)
        e.end()
        assertArrayEquals(floatArrayOf(thumb, thumb), t.selectedWeights(), 1e-5f)
        // With the Weight item's custom step the thumb lands on it.
        t.controller.increments.update { it.copy(enabled = true).withCustom(PATH_WEIGHT_KEY, 0.5f) }
        val e2 = CurveGroupEdit(t, CurveGroupEdit.Kind.WEIGHT)
        e2.begin()
        e2.drag(0.13f)
        val snapped = e2.thumb!!
        assertEquals(0f, snapped % 0.5f, 1e-5f)
        e2.end()
    }

    @Test
    fun talkBacksSetProgressIsOneDrag() {
        val (t, e) = pathWith(1f, 4f)
        val before = steps(t)
        e.slideTo(SplineEditing.weightToFraction(5f))
        e.end()
        assertEquals(5f, e.values().average().toFloat(), 0.05f)
        assertEquals(before + 1, steps(t))
    }

    @Test
    fun typedTextScalesAddsOrSets() {
        val (t, e) = pathWith(1f, 2f, 4f)
        assertTrue(e.type("*2"))
        assertArrayEquals(floatArrayOf(2f, 4f, 8f), t.selectedWeights(), 1e-5f)
        assertTrue(e.type("/2"))
        assertArrayEquals(floatArrayOf(1f, 2f, 4f), t.selectedWeights(), 1e-5f)
        assertTrue(e.type("+1"))
        assertArrayEquals(floatArrayOf(2f, 3f, 5f), t.selectedWeights(), 1e-5f)
        assertTrue(e.type("-1.5"))
        assertArrayEquals(floatArrayOf(0.5f, 1.5f, 3.5f), t.selectedWeights(), 1e-5f)
        assertTrue(e.type("3"))
        assertArrayEquals(floatArrayOf(3f, 3f, 3f), t.selectedWeights(), 1e-5f)
        assertTrue(e.type("*100"))
        assertArrayEquals("clamped", floatArrayOf(10f, 10f, 10f), t.selectedWeights(), 0f)
        assertFalse(e.type("abc"))
        // Each typed value is one step.
        assertTrue(e.type("1"))
        val before = steps(t)
        e.type("+1")
        e.type("+1")
        assertEquals(before + 2, steps(t))
        assertArrayEquals(floatArrayOf(3f, 3f, 3f), t.selectedWeights(), 1e-5f)
    }
}
