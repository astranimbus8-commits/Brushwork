package com.brushwork.paint.qa18

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18MirrorsKit.inkNear
import com.brushwork.paint.qa18.Qa18MirrorsKit.map
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.assist.RulerStripItems
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.symmetry.SymmetryStripItems
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (mirrors follow-ups, item 1) on the user's phone (392 dp), every step by a finger:
 * the Symmetry and the Ruler tools' "Done" goes back to the tool the user came from when a ruler
 * works for it (`EditorController.doneTool`), no longer to the last painting tool.
 * - The Curve ("Plain line"), then the Symmetry tool from the tool menu, its ruler dropdown,
 *   "Rotation ruler", Done: the Curve tool again with the ruler on. Three taps and ✓ draw ONE
 *   path in ONE step carrying the Rotation's copies (one per map), every copy drawn.
 * - The top row's "Ruler" panel: from the Polyline, "Place on canvas" (the Symmetry tool) then
 *   Done is the Polyline; from the Path, "Edit on canvas" (the Ruler tool) then Done is the Path;
 *   from the Path, the Symmetry tool, then the panel's "Edit on canvas" (Symmetry to Ruler keeps
 *   where Done goes) and Done: still the Path.
 * - From the Lasso (no ruler works for it), Done is the last painting tool (the Brush).
 * None of the tool changes is an undo step.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishmirrorsdonesandbox"])
class Qa18PolishMirrorsDoneUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    private fun document(): Document = Smoke.document(W, H, layers = 1, whiteBottom = true).also { d ->
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
    }

    private fun steps() = c.undoManager.undoCount

    /** The strip's "Done", by a finger. */
    private fun done(label: String, what: String) {
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        ui.reach(label, 32f)
        Finger.tap(s, label)
        settle()
        assertFalse("$what: Done left the tool", c.activeToolId == ToolId.SYMMETRY || c.activeToolId == ToolId.RULER)
    }

    /** The top row's "Ruler" panel, then its [button], by a finger: the panel closes on [tool]. */
    private fun rulerPanel(button: String, tool: ToolId, what: String) {
        val from = c.activeToolId
        Finger.tap(s, "Ruler")
        settle()
        assertTrue("$what: the Ruler panel: ${SmokeUi.shown().take(40)}", SmokeUi.has("Use ruler", exact = true))
        assertEquals("$what: opening the panel keeps the tool", from, c.activeToolId)
        ui.reach(button, 32f)
        // reach scrolls the sheet by a finger: let the scroll (and its overscroll at the end of
        // the sheet) come to rest, or the tap only stops it, as on a phone.
        Smoke.pump(1_000)
        settle()
        Finger.tap(s, button)
        settle()
        assertEquals("$what: \"$button\"", tool, c.activeToolId)
        assertTrue("$what: the panel closed", Smoke.pumpUntil(5_000) { settle(1); !SmokeUi.has("Use ruler", exact = true) })
    }

    private fun flow(h: ChromeHarness) {
        s = h.editor(document()) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
        val layer = c.doc.layers[1]
        c.color = INK

        // The Curve, a plain line.
        ui.tool("Curve")
        val tool = c.currentTool as CurveTool
        click(CurveLabels17.STROKE_KIND, exact = true)
        click(CurveStroke.PLAIN.label, exact = true)
        settle()
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        assertEquals(ToolId.CURVE, c.activeToolId)
        val n0 = steps()

        // The Symmetry tool (it turns the Mirror on), its ruler dropdown, "Rotation ruler", Done.
        ui.tool("Symmetry")
        settle()
        assertEquals(ToolId.SYMMETRY, c.activeToolId)
        val current = c.symmetry.type.label
        ui.reach(current, 32f)
        Finger.tap(s, current)
        settle()
        SmokeUi.click(SymmetryType.ROTATION.label, exact = true)
        settle()
        assertEquals("the ruler", SymmetryType.ROTATION, c.symmetry.type)
        done(SymmetryStripItems.DONE_LABEL, "Curve, Symmetry")
        assertEquals("Done: back to the Curve (the tool the user came from)", ToolId.CURVE, c.activeToolId)
        assertEquals("the same Curve tool", tool, c.currentTool)
        assertEquals("the ruler stays on", SymmetryType.ROTATION, c.symmetry.type)
        assertEquals("the last painting tool is still the Brush", ToolId.BRUSH, c.lastPaintTool)
        assertEquals("no tool change is a step", n0, steps())

        // The next taps draw a curve with copies.
        val maps = ObjectCopies.mapsAt(c, TAPS[0].x, TAPS[0].y)
        assertEquals("Rotation (6 divisions): six maps", 6, maps.size)
        for (m in maps) for (p in TAPS) {
            val q = map(m, p)!!
            assertTrue("every copy of $p on the canvas ($q)", q.x in 4f..(W - 4f) && q.y in 4f..(H - 4f))
        }
        for (p in TAPS) ui.tap(p.x, p.y)
        assertEquals("three points (no tap picked a copy's point)", 3, tool.pointCount)
        assertTrue("the copies follow while it is drawn", tool.hasCopies)
        Finger.tap(s, "Apply curve edit")
        assertTrue(
            "the curve: done",
            Smoke.pumpUntil(10_000) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering },
        )
        settle()
        assertEquals("the curve: ONE step", n0 + 1, steps())
        val path = layer.vector!!.objects.single() as VPath
        assertEquals("one path carrying the six copies", CopyMaps.of(maps), path.copies)
        for ((k, m) in maps.withIndex()) for (p in TAPS) {
            assertTrue("copy $k drawn at ${map(m, p)}", inkNear(layer.bitmap, map(m, p)!!, 3) >= 200)
        }
        val shots = Qa18PolishMirrorsKit.shoot(s, "done-curve")
        println("Qa18PolishMirrorsDone: ${shots.joinToString()}")
        val n1 = steps()

        // The top row's Ruler panel: Polyline, "Place on canvas" (the Symmetry tool), Done.
        ui.tool("Polyline")
        assertEquals(ToolId.POLYLINE, c.activeToolId)
        rulerPanel(PLACE_ON_CANVAS, ToolId.SYMMETRY, "Polyline")
        done(SymmetryStripItems.DONE_LABEL, "Polyline, the panel's Symmetry")
        assertEquals("Done: back to the Polyline", ToolId.POLYLINE, c.activeToolId)

        // Path, "Edit on canvas" (the Ruler tool), Done.
        ui.tool("Path")
        assertEquals(ToolId.PATH, c.activeToolId)
        rulerPanel(EDIT_ON_CANVAS, ToolId.RULER, "Path")
        done(RulerStripItems.DONE_LABEL, "Path, the panel's Ruler")
        assertEquals("Done: back to the Path", ToolId.PATH, c.activeToolId)

        // Path, the Symmetry tool, then (its Ruler panel) the Ruler tool, Done: still the Path.
        ui.tool("Symmetry")
        assertEquals(ToolId.SYMMETRY, c.activeToolId)
        rulerPanel(EDIT_ON_CANVAS, ToolId.RULER, "Path, Symmetry")
        done(RulerStripItems.DONE_LABEL, "Path, Symmetry, Ruler")
        assertEquals("Done: still back to the Path", ToolId.PATH, c.activeToolId)

        // The Lasso: no ruler works for it, so Done is the last painting tool.
        ui.tool("Lasso")
        assertEquals(ToolId.LASSO, c.activeToolId)
        ui.tool("Symmetry")
        done(SymmetryStripItems.DONE_LABEL, "Lasso, Symmetry")
        assertEquals("from the Lasso: the last painting tool", ToolId.BRUSH, c.activeToolId)
        assertEquals("no tool change is a step", n1, steps())
        assertEquals("the curve kept", path, layer.vector!!.objects.single())
        Smoke.assertQuiet(c, "Done")
    }

    @Test
    fun doneGoesBackToTheToolTheUserCameFrom() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("Done after the Symmetry and Ruler tools") { flow(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300
        val INK = 0xFF101010.toInt()

        /** The Ruler panel's buttons (v1.6 / v1.7 texts). */
        const val PLACE_ON_CANVAS = "Place on canvas"
        const val EDIT_ON_CANVAS = "Edit on canvas"

        /**
         * The curve's three taps: 195°, 210° and 225° around the canvas centre (the Rotation's
         * centre), 100, 60 and 100 px out, so every copy of a tap lies 40 px or more from every tap.
         */
        val TAPS = listOf(Vec2(103.4f, 124.1f), Vec2(148f, 120f), Vec2(129.3f, 79.3f))
    }
}
