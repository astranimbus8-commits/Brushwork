package com.brushwork.paint.ui.tools.strip

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.PointLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (F4, design §3.2 and §3.9): the strips' point and corner Undo / Redo are gone (the top
 * row's Undo / Redo reach the same in-tool steps), and so is the strips' "Select several" chip
 * (the X / Y pill's cell now). On every tool state of [StripInventoryTest] (raster and vector
 * layer, as each tool opens), and on the states where v1.7 showed them (Curve, Polyline and Path
 * with points and with one undone, "Select several" on; Lasso's polygon and curve with corners /
 * points and one undone; a pending shape in Points mode; Free deform), neither row of the strip
 * offers any of [GONE] or "Select several", each matched exactly against every text, description
 * and click label (`SmokeUi.has(label, exact = true)`), the More row closed and open.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.nostrippointundosandbox"])
class NoStripPointUndoTest {

    @Test
    fun noStripStateOffersAPointUndoRedoOrSelectSeveral() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig(panel = false).show()
        val problems = mutableListOf<String>()
        var checked = 0

        /** The strip as it shows now, its More row closed and then open (closed again after). */
        fun check(state: String) {
            for (open in listOf(false, true)) {
                if (open && !StripTestKit.openMoreRow()) continue
                val where = if (open) "$state (More row open)" else state
                for (label in GONE + PointLabels.SELECT_SEVERAL) {
                    if (SmokeUi.has(label, exact = true)) problems += "$where: \"$label\" is on the strip"
                }
            }
            StripTestKit.closeMoreRow()
            checked++
        }

        fun tap(x: Float, y: Float) {
            rig.c.pointerDown(ToolPoint(x, y))
            rig.c.pointerUp(ToolPoint(x, y))
            rig.settle()
        }

        try {
            // Every tool as it opens, on the raster and on the vector layer.
            for (vector in listOf(false, true)) for (id in ToolId.entries) {
                rig.select(id, vector)
                if (rig.c.activeToolId != id) continue
                check(id.name + if (vector) "@vector" else "")
            }

            for (vector in listOf(false, true)) {
                val at = if (vector) "@vector" else ""
                // Curve, Polyline and Path: points placed (v1.7: "Undo last point"), one undone
                // ("Redo point"), "Select several" on.
                for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
                    rig.select(id, vector)
                    val t = rig.c.currentTool as CurveTool
                    for (p in THREE) assertTrue("$id$at: a point at $p", t.addAnchor(p))
                    rig.settle()
                    // The check sees the strip: its points bar's "Select all points" is found.
                    // v1.8: "Select all points" is on the More row (SmokeUi.find opens it).
                    assertTrue("$id$at: the points bar", SmokeUi.find(PointLabels.SELECT_ALL, exact = true) != null)
                    StripTestKit.closeMoreRow()
                    check("$id$at, three points")
                    assertTrue("$id$at: a point undone", t.undoStep())
                    rig.settle()
                    check("$id$at, a point undone")
                    t.selectSeveral = true
                    rig.settle()
                    check("$id$at, Select several on")
                    t.discard()
                    rig.settle()
                }

                // A pending rectangle in Points mode (v1.7: "Undo point edit", "Redo point edit",
                // the "Select several" chip).
                rig.select(ToolId.SHAPE, vector)
                val shape = rig.c.currentTool as ShapeTool
                rig.c.pointerDown(ToolPoint(80f, 60f))
                rig.c.pointerMove(ToolPoint(160f, 120f))
                rig.c.pointerMove(ToolPoint(240f, 200f))
                rig.c.pointerUp(ToolPoint(240f, 200f))
                rig.settle()
                assertNotNull("SHAPE$at: a pending shape", shape.box)
                shape.setPointEditing(true)
                rig.settle()
                assertTrue("SHAPE$at: Points mode", shape.pointsMode)
                check("SHAPE$at, Points mode")
                shape.selectSeveral = true
                rig.settle()
                check("SHAPE$at, Points mode, Select several on")
                shape.discard()
                rig.settle()
            }

            // Lasso on the raster layer: a polygon's corners ("Undo last corner", one undone:
            // "Redo corner") and a curve's points ("Undo last point", "Redo point").
            rig.select(ToolId.LASSO)
            val lasso = rig.c.currentTool as LassoTool
            lasso.setKind(LassoKind.POLYGON)
            rig.settle()
            tap(80f, 60f); tap(300f, 70f); tap(260f, 220f)
            assertEquals("three polygon corners", 3, lasso.vertexCount)
            check("LASSO polygon, three corners")
            assertTrue("a corner undone", lasso.undoStep())
            rig.settle()
            check("LASSO polygon, a corner undone")
            lasso.discard()
            lasso.setKind(LassoKind.CURVE)
            rig.settle()
            tap(80f, 60f); tap(300f, 70f); tap(260f, 220f)
            assertEquals("three curve points", 3, lasso.curve.count)
            check("LASSO curve, three points")
            assertTrue("a curve point undone", lasso.undoStep())
            rig.settle()
            check("LASSO curve, a point undone")
            lasso.discard()
            lasso.setKind(LassoKind.FREEHAND)
            rig.settle()

            // Free deform over a painted raster layer, "Select several" on (v1.7: its strip chip).
            Canvas(rig.doc.layers[0].bitmap).drawRect(100f, 80f, 300f, 220f, Paint().apply { color = Color.RED })
            rig.select(ToolId.TRANSFORM)
            val transform = rig.c.currentTool as TransformTool
            assertTrue("something lifted", Smoke.pumpUntil { rig.settle(); transform.transformState != null })
            transform.mode = TransformTool.Mode.MESH
            rig.settle()
            assertTrue("the mesh shows", transform.isMeshShown)
            check("TRANSFORM free deform")
            transform.selectSeveral = true
            rig.settle()
            check("TRANSFORM free deform, Select several on")
            transform.discard()
            rig.select(ToolId.BRUSH)
        } finally {
            rig.close()
        }
        assertTrue("every state was checked ($checked)", checked > 2 * ToolId.entries.size)
        assertTrue("the strips:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        /** The six controls §3.2 removes from the strips, exactly as v1.7 labelled them. */
        val GONE = listOf("Undo last point", "Redo point", "Undo last corner", "Redo corner", "Undo point edit", "Redo point edit")

        val THREE = listOf(Vec2(80f, 80f), Vec2(200f, 60f), Vec2(300f, 180f))
    }
}
