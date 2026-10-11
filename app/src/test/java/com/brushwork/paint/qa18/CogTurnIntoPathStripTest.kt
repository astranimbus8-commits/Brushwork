package com.brushwork.paint.qa18

import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.vector.ShapeStripItems
import com.brushwork.paint.ui.vector.ShapeStripKit
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 integration (design §3.8, D21): an open cog WITH a hole never reaches Points mode ("Set the
 * hole size to 0 to edit points"), yet D21 keeps its "Turn into path" (a compound plain path). The
 * Shape strip offered "Turn into path" in Points mode only, so the user could not reach it for a
 * holed cog; the More row now shows it for an open shape whose Points mode is refused. A cog
 * without a hole keeps v1.7's rule (the item in Points mode only). On a 360 dp phone, the Shape
 * tool on the vector layer of a [StripRig].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.cogtopathsandbox"])
class CogTurnIntoPathStripTest {

    @Test
    fun aHoledCogTurnsIntoAPathFromTheMoreRow() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig(panel = false).show()
        try {
            val c = rig.c
            val cog: (ShapeSettings) -> ShapeSettings = {
                it.copy(type = ShapeType.COG, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, editable = true, cogTeeth = 12, cogHole = 0.4f)
            }

            // ---- A cog without a hole: "Turn into path" waits for Points mode (v1.7's rule).
            ShapeStripKit.open(rig, vector = true) { cog(it).copy(cogHole = 0f) }
            assertTrue(StripTestKit.openMoreRow())
            assertFalse("no hole: not before Points mode", PointLabels.TO_PATH in ShapeStripKit.moreHandles())
            StripTestKit.closeMoreRow()

            // ---- With a hole: Points is refused, "Turn into path" is on the More row, enabled.
            val t = ShapeStripKit.open(rig, vector = true, settings = cog)
            c.message = null
            assertNotNull("the Points toggle", StripTestKit.reach { SmokeUi.find(ShapeStripItems.POINTS_LABEL, exact = true) })
            SmokeUi.click(ShapeStripItems.POINTS_LABEL, exact = true)
            rig.settle()
            assertEquals(CogLabels18.HOLE_REFUSAL, c.message)
            StripTestKit.closeMoreRow()
            assertFalse("Points mode refused", t.pointsMode)
            assertNull(t.turnIntoPathRefusal)
            assertTrue(StripTestKit.openMoreRow())
            assertTrue("a holed cog: \"${PointLabels.TO_PATH}\" on the More row: ${ShapeStripKit.moreHandles()}", PointLabels.TO_PATH in ShapeStripKit.moreHandles())

            // The tap: the cog is placed (its own step), then ONE "Turn into path" step: a compound plain path.
            val layer = rig.doc.layers[1]
            val steps = c.undoManager.undoCount
            c.message = null
            SmokeUi.click(PointLabels.TO_PATH, exact = true)
            c.vectors.flushPending()
            rig.settle()
            assertEquals("the shape, then Turn into path", steps + 2, c.undoManager.undoCount)
            assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
            val p = layer.vector!!.objects.single() as? VPath ?: throw AssertionError("not a path: ${layer.vector!!.objects}")
            assertEquals("the outline and the hole", 2, p.subpaths.size)
            assertTrue("both closed", p.subpaths.all { it.closed })
            assertNull("a plain path (no spline)", p.spline)
            assertEquals(CogLabels18.COMPOUND_HINT, c.message)
            assertEquals("the Path tool takes it", ToolId.PATH, c.activeToolId)
        } finally {
            StripTestKit.closeMoreRow()
            rig.close()
        }
    }
}
