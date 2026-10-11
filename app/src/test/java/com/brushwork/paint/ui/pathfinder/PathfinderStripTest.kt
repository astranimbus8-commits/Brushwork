package com.brushwork.paint.ui.pathfinder

import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.placement.PlacementStripKit
import com.brushwork.paint.ui.placement.PlacementStripKit.assertSplits
import com.brushwork.paint.ui.placement.PlacementStripKit.back
import com.brushwork.paint.ui.placement.PlacementStripKit.clippedTexts
import com.brushwork.paint.ui.placement.PlacementStripKit.inspect
import com.brushwork.paint.ui.placement.PlacementStripKit.key
import com.brushwork.paint.ui.placement.PlacementStripKit.overlayWindowShown
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Handles
import com.brushwork.paint.ui.placement.PlacementStripKit.row1Texts
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 G (design §3.9 "Pathfinder"): the Pathfinder tool's converted strip at 360 and 392 dp, on
 * the raster and the vector layer (the VECTOR cell):
 * - row 1 only, every configuration: the operation dropdown, "Select all objects" and the status
 *   text (the hint, the picked count or "Working…"); no More row, no toggle;
 * - the ten operations live in the dropdown, each announced by its v1.7 description (the
 *   inventory opens it: `movedInto`), disabled without two picks;
 * - no label cut by its item's width (the hint fits its two lines);
 * - picking an operation runs it as one step, and the button then shows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.pathfinder.pathfinderstripsandbox"])
class PathfinderStripTest {

    private fun shapeLayer(c: EditorController, l: Float, t: Float, r: Float, b: Float, color: Int) {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = (l + r) / 2f, cy = (t + b) / 2f, w = r - l, h = b - t, style = ShapeStyle.FILL, fillColor = color)
        c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { canvas ->
            canvas.drawRect(l, t, r, b, Paint().apply { this.color = color })
        }!!
    }

    @Test
    fun thePathfinderStripKeepsItsControlsAtBothWidths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val row1 = listOf(PathfinderStripItems.OPS, PathfinderStripItems.SELECT_ALL, PathfinderStripItems.STATUS)
        PlacementStripKit.atEachWidth { rig, dp ->
            for (vector in listOf(false, true)) {
                val k = key(ToolId.PATHFINDER, vector)
                rig.select(ToolId.PATHFINDER, vector)
                inspect(rig, k)
                assertNull("$k: no hint (the status stays on row 1)", rig.spec.hint)
                assertSplits(k, rig.spec) { _, _ -> row1 }
                assertEquals("$k at $dp dp: row 1", listOf(PathfinderOp.UNITE.description, PathfinderLabels.SELECT_ALL), row1Handles())
                assertTrue("$k at $dp dp: the status on row 1: ${row1Texts()}", PathfinderLabels.HINT in row1Texts())
                assertNull("$k: no toggle", StripTestKit.toggle())
                assertEquals("$k at $dp dp: row 1 texts fit", emptyList<String>(), clippedTexts())
                assertEquals("$k: every operation is inside the dropdown", PathfinderOp.entries.map { it.description }.toSet(), rig.spec.movedInto.keys)
            }

            // Two shapes to combine.
            val c = rig.c
            shapeLayer(c, 20f, 20f, 100f, 100f, 0xFFDD2211.toInt())
            shapeLayer(c, 70f, 20f, 150f, 100f, 0xFF2244CC.toInt())
            rig.select(ToolId.PATHFINDER)
            val tool = c.currentTool as PathfinderTool
            tool.computeDispatcher = Dispatchers.Unconfined

            // Without picks the operations are disabled.
            SmokeUi.click(PathfinderOp.UNITE.description, exact = true)
            rig.settle()
            assertTrue("$dp dp: the dropdown", overlayWindowShown(rig))
            for (op in PathfinderOp.entries) assertFalse("${op.description}: needs 2 objects", SmokeUi.isEnabled(op.description))
            back(rig)
            assertFalse(overlayWindowShown(rig))

            // Select all: the count on row 1, the operations come alive.
            SmokeUi.click(PathfinderLabels.SELECT_ALL, exact = true)
            rig.settle()
            assertEquals(2, tool.count)
            assertTrue(PathfinderTool.picked(2) in row1Texts())
            assertEquals("$dp dp: the count fits", emptyList<String>(), clippedTexts())
            SmokeUi.click(PathfinderOp.UNITE.description, exact = true)
            rig.settle()
            for (op in PathfinderOp.entries) assertTrue(op.description, SmokeUi.isEnabled(op.description))

            // Minus front: one step; the button shows it from now on.
            val steps = c.undoManager.undoCount
            SmokeUi.click(PathfinderOp.MINUS_FRONT.description, exact = true)
            rig.settle()
            assertFalse("the dropdown closed", overlayWindowShown(rig))
            assertEquals(steps + 1, c.undoManager.undoCount)
            assertEquals(HistoryLabels.pathfinder(PathfinderOp.MINUS_FRONT.label), c.undoManager.undoLabel)
            assertTrue(PathfinderLabels.resultLayer(1) in c.doc.layers.map { it.name })
            assertEquals(listOf(PathfinderOp.MINUS_FRONT.description, PathfinderLabels.SELECT_ALL), row1Handles())
            assertEquals("$dp dp: texts fit", emptyList<String>(), clippedTexts())
            assertTrue(PathfinderLabels.HINT in row1Texts())
        }
    }
}
