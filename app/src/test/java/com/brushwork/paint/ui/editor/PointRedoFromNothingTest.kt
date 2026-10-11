package com.brushwork.paint.ui.editor

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.LambdaAction
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 (F4, design §3.2 and V9): the strips' "Redo point" / "Redo corner" are gone, and the top
 * row's Redo does what they did. v1.7's strip buttons also brought back the points of a NEW path
 * (or polygon / curve lasso) taken back one by one down to nothing, when no work is pending any
 * more; the top row's Redo now does that too, by a finger: Curve, Polyline, Path, the polygon
 * lasso and the curve lasso, the button enabled and its feedback naming the point / corner. The
 * document's own redo steps come first, as v1.7's top row had them (a step undone after the
 * points is redone before them), and nothing changes while there is nothing to bring back.
 * Another tool ends those steps (Curve, as in v1.7; the lasso's too since this change).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.pointredofromnothingsandbox"])
class PointRedoFromNothingTest {

    private val three = listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 160f))

    /** The top row's Undo / Redo, by a finger. */
    private fun undo(s: ChromeScreen) = Finger.tap(s, "Undo")
    private fun redo(s: ChromeScreen) = Finger.tap(s, "Redo")

    private fun assertNear(what: String, a: Vec2, b: Vec2, tol: Float = 0.05f) =
        assertTrue("$what: $a vs $b", abs(a.x - b.x) <= tol && abs(a.y - b.y) <= tol)

    /** Redo is offered (the state, the button, the feedback) for [step] of the current tool. */
    private fun assertRedoOffers(s: ChromeScreen, what: String, step: String) {
        assertTrue("$what: Redo can act", canRedoNow(s.c))
        assertTrue("$what: the Redo button is enabled", SmokeUi.isEnabled("Redo"))
        assertEquals("$what: the feedback", "Redo: $step", HistoryLabels.redo(s.c))
    }

    @Test
    fun theTopRowRedoBringsBackPointsTakenBackToNothing() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()

        for (label in listOf("Curve", "Polyline", "Path")) {
            h.section("$label: every point taken back, then brought back one by one") {
                val s = h.editor()
                val c = s.c
                QaCurves.tool(s, label)
                val tool = c.currentTool as CurveTool
                QaCurves.snapOff(c)
                for (p in three) QaCurves.tap(s, p)
                assertEquals("$label: three points by three taps", 3, tool.pointCount)
                val placed = (0 until 3).map { tool.pointAt(it) }
                assertFalse("$label: the document has nothing to redo", c.canRedo)
                repeat(3) { undo(s) }
                assertEquals("$label: every point taken back", 0, tool.pointCount)
                assertFalse("$label: nothing pending", tool.hasPendingWork)
                assertFalse("$label: the document history untouched", c.canUndo)
                assertRedoOffers(s, label, "last point")
                for (n in 1..3) {
                    redo(s)
                    assertEquals("$label: redo $n", n, tool.pointCount)
                }
                for (i in 0 until 3) assertNear("$label: point $i", placed[i], tool.pointAt(i))
                assertFalse("$label: nothing left to redo", canRedoNow(c))
                tool.discard()
                settle()
                assertFalse("$label: discarded, nothing to redo", canRedoNow(c))
                Smoke.assertQuiet(c, label)
            }
        }

        h.section("Lasso: every polygon corner, then every curve point, taken back and brought back") {
            val s = h.editor()
            val c = s.c
            QaCurves.tool(s, "Lasso")
            val tool = c.currentTool as LassoTool
            tool.setKind(LassoKind.POLYGON)
            settle()
            for (p in three) QaCurves.tap(s, p)
            assertEquals("three corners by three taps", 3, tool.vertexCount)
            repeat(3) { undo(s) }
            assertEquals("every corner taken back", 0, tool.vertexCount)
            assertFalse("nothing pending", tool.hasPendingWork)
            assertRedoOffers(s, "polygon", "last corner")
            for (n in 1..3) {
                redo(s)
                assertEquals("polygon: redo $n", n, tool.vertexCount)
            }
            assertFalse("polygon: nothing left to redo", canRedoNow(c))
            tool.discard()
            tool.setKind(LassoKind.CURVE)
            settle()
            for (p in three) QaCurves.tap(s, p)
            assertEquals("three curve points by three taps", 3, tool.curve.count)
            repeat(3) { undo(s) }
            assertEquals("every curve point taken back", 0, tool.curve.count)
            assertFalse("nothing pending", tool.hasPendingWork)
            assertRedoOffers(s, "curve lasso", "last point")
            for (n in 1..3) {
                redo(s)
                assertEquals("curve lasso: redo $n", n, tool.curve.count)
            }
            assertFalse("curve lasso: nothing left to redo", canRedoNow(c))
            tool.discard()
            tool.setKind(LassoKind.FREEHAND)
            settle()
            assertFalse("freehand has no steps", canRedoNow(c))
            Smoke.assertQuiet(c, "Lasso")
        }

        h.section("another tool ends the steps taken back to nothing") {
            val s = h.editor()
            val c = s.c
            QaCurves.tool(s, "Curve")
            val curve = c.currentTool as CurveTool
            QaCurves.snapOff(c)
            QaCurves.tap(s, three[0])
            undo(s)
            assertTrue("Curve: the point can come back", canRedoNow(c))
            c.selectTool(ToolId.BRUSH)
            settle()
            c.selectTool(ToolId.CURVE)
            settle()
            assertEquals(0, curve.redoCount)
            assertFalse("Curve: after another tool, nothing to redo", canRedoNow(c))
            QaCurves.tool(s, "Lasso")
            val lasso = c.currentTool as LassoTool
            for (kind in listOf(LassoKind.POLYGON, LassoKind.CURVE)) {
                lasso.setKind(kind)
                settle()
                QaCurves.tap(s, three[0])
                QaCurves.tap(s, three[1])
                repeat(2) { undo(s) }
                assertFalse("$kind: nothing pending", lasso.hasPendingWork)
                assertTrue("$kind: the corners can come back", canRedoNow(c))
                c.selectTool(ToolId.BRUSH)
                settle()
                c.selectTool(ToolId.LASSO)
                settle()
                assertEquals(kind, lasso.kind)
                assertFalse("$kind: after another tool, nothing to redo", canRedoNow(c))
            }
            lasso.setKind(LassoKind.FREEHAND)
            settle()
            Smoke.assertQuiet(c, "another tool")
        }

        h.section("the document's redo comes first, then the point") {
            val s = h.editor()
            val c = s.c
            QaCurves.tool(s, "Curve")
            val tool = c.currentTool as CurveTool
            QaCurves.snapOff(c)
            c.pushUndo(LambdaAction("Fill", onUndo = {}, onRedo = {}))
            settle()
            QaCurves.tap(s, three[0])
            assertEquals(1, tool.pointCount)
            undo(s)
            assertEquals("the point taken back", 0, tool.pointCount)
            undo(s)
            assertTrue("then the document's step", c.canRedo)
            assertFalse(c.canUndo)
            assertRedoOffers(s, "the document's step first (as v1.7)", "Fill")
            redo(s)
            assertFalse("the document's step is redone", c.canRedo)
            assertTrue(c.canUndo)
            assertEquals("the point waits", 0, tool.pointCount)
            assertRedoOffers(s, "then the point", "last point")
            redo(s)
            assertEquals("the point is back", 1, tool.pointCount)
            assertNear("where it was", three[0], tool.pointAt(0), tol = 1f)
            assertFalse("nothing left to redo", canRedoNow(c))
            tool.discard()
            settle()
            Smoke.assertQuiet(c, "document first")
        }

        dog.interrupt()
        h.finish()
    }
}
