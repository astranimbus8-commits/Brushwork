package com.brushwork.paint.v19.a9

import com.brushwork.paint.EditorController
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A9 budgets (design R17 (c), R18 and R14 (c)), on a desktop (PerfBudget scales them on CI):
 * an in-tool undo or redo step of an 896-point shape takes at most 1 ms; "Bevel points" making
 * those 896 points from a 64-point star takes at most 5 ms; a new shape joining the selected
 * shape layer (the conversion and the new object, one step) takes at most 5 ms.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class ShapeBudgetsTimingTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    /** A 64-point star (128 anchors) in Points mode, every point selected; bevels make 128 x 7 = 896 points. */
    private fun star(c: EditorController): ShapeTool {
        c.settings.shapeBevelSegments = 6
        val tool = rig.shapeTool(c)
        tool.update { it.copy(starPoints = 64) }
        rig.drawRect(c, tool, ShapeType.STAR)
        tool.setPointEditing(true)
        assertEquals(128, tool.pointCount)
        tool.selectPoints(PointSelection.all(128))
        return tool
    }

    @Test
    fun anUndoStepOfAn896PointShapeTakesAtMost1Ms() {
        val c = rig.controller()
        val tool = star(c)
        assertTrue(tool.bevelPoints())
        tool.closeBevelPopup()
        assertEquals(896, tool.pointCount)
        // Ten moves of the whole shape, each one step.
        tool.setPointEditing(false)
        val n = 10
        repeat(n) { i ->
            val d = if (i % 2 == 0) 16f else -16f
            rig.drag(c, 70f to 60f, 70f + d / 2f to 60f, 70f + d to 60f)
        }
        assertEquals("ten steps (the moves) on top of the bevel and the conversion", n + 2, generateSequence { if (tool.undoStep()) 1 else null }.count())
        repeat(n + 2) { assertTrue(tool.redoStep()) }
        val moved = tool.box
        PerfBudget.measuredWithin(PerfBudget.ms(1.0), what = "an in-tool undo or redo step of 896 points") {
            val t0 = System.nanoTime()
            repeat(n) { assertTrue(tool.undoStep()) }
            repeat(n) { assertTrue(tool.redoStep()) }
            (System.nanoTime() - t0) / 1e6 / (2 * n)
        }
        assertEquals(moved, tool.box)
        assertEquals(896, tool.pointCount)
    }

    @Test
    fun bevelPointsMakingAn896PointShapeTakesAtMost5Ms() {
        val c = rig.controller()
        val tool = star(c)
        // Warm-up.
        assertTrue(tool.bevelPoints())
        PerfBudget.measuredWithin(PerfBudget.ms(5.0), what = "Bevel points, 128 corners into 896 points") {
            tool.closeBevelPopup()
            assertTrue(tool.undoStep())
            assertEquals(128, tool.pointCount)
            tool.selectPoints(PointSelection.all(128))
            val t0 = System.nanoTime()
            assertTrue(tool.bevelPoints())
            val ms = (System.nanoTime() - t0) / 1e6
            assertEquals(896, tool.pointCount)
            ms
        }
    }

    @Test
    fun aNewShapeJoiningTheSelectedShapeLayerTakesAtMost5Ms() {
        fun once(): Double {
            val c = rig.controller()
            val tool = rig.shapeTool(c)
            rig.drawRect(c, tool)
            tool.commit()
            val layer = c.activeLayer
            assertTrue(layer.isShapeLayer && !layer.isVectorLayer)
            tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE) }
            rig.drag(c, 160f to 120f, 190f to 150f, 220f to 180f)
            val t0 = System.nanoTime()
            tool.commit()
            val ms = (System.nanoTime() - t0) / 1e6
            assertEquals("joined", 2, layer.vector!!.objects.count { it is VShape })
            return ms
        }
        once()
        PerfBudget.measuredWithin(PerfBudget.ms(5.0), what = "a new shape joining the selected shape layer") { once() }
    }
}
