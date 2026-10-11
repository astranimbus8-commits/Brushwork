package com.brushwork.paint.qa18

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.placement.TransformStripItems
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (items 6 and 7) on a vector layer, on the phone (392 dp), by finger: what the user
 * placed stays editable LATER.
 * - A cog placed with ✓ opens again by a tap; its "Number of teeth" and a "Hole size" are changed
 *   in "Shape settings"; ✓ is ONE step on the same object; the hole shows the page; Undo gives the
 *   cog back exactly as it was, Redo the changed one.
 * - A spiral placed with ✓ and then mirrored with the Transform tool's "Flip horizontally" keeps
 *   its settings (mirrored: Clockwise flips): a tap with the Curve tool opens it with its chip, the
 *   sheet's "Turns" changes it, and ✓ stores it still generated, on the same object.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.curvesreopensandbox"])
class Qa18CurvesReopenUiTest {

    private fun chipShown(q: Qa18CurvesUi): Boolean =
        Finger.control(q.s, CurveGenLabels18.CHANGE) != null || StripTestKit.reach { SmokeUi.find(CurveGenLabels18.CHANGE, exact = true) } != null

    private fun cog(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        val c = q.c
        q.tool("Shape")
        val tool = c.currentTool as ShapeTool
        q.tap("Shape type")
        q.tap(ShapeType.COG.label)
        q.tap("Shape style")
        q.tap(ShapeStyle.FILL.label)
        assertEquals(ShapeStyle.FILL, tool.settings.style)
        q.strokeDoc(Vec2(110f, 60f), Vec2(155f, 105f), Vec2(200f, 150f), Vec2(245f, 195f), Vec2(290f, 240f))
        q.applyEdit("Apply shape edit")
        val layer = c.activeLayer
        val placed = layer.vector!!.objects.single() as VShape
        assertEquals(ShapeType.COG, placed.shape.type)
        assertEquals(0f, placed.shape.cogHole)
        val centre = Vec2(placed.shape.cx, placed.shape.cy)
        assertTrue("filled", q.inkAt(layer, centre))

        // A tap inside it opens it; 7 teeth and a hole; ✓.
        q.tapDoc(centre + Vec2(10f, 0f))
        assertEquals("the tap opened the cog", 12, tool.settings.cogTeeth)
        assertNotNull("it is open", tool.box)
        q.tap("Shape settings")
        SmokeUi.typeAndDone(CogLabels18.TEETH, "7")
        q.dragSlider(CogLabels18.HOLE_SIZE, 0.5f)
        Finger.back()
        assertEquals(7, tool.settings.cogTeeth)
        val hole = tool.settings.cogHole
        assertTrue("a hole: $hole", hole > 0.2f)
        val steps = q.steps()
        q.applyEdit("Apply shape edit")
        assertEquals("✓ is ONE step", steps + 1, q.steps())
        val edited = layer.vector!!.objects.single() as VShape
        assertEquals("the same object", placed.id, edited.id)
        assertEquals(7, edited.shape.cogTeeth)
        assertEquals(hole, edited.shape.cogHole)
        assertEquals("after ✓ the tool has the user's own settings again (no hole)", 0f, tool.settings.cogHole)
        assertEquals("the same place", placed.shape.box, edited.shape.box)
        assertFalse("the hole shows the page", q.inkAt(layer, centre, 2))
        q.shot(layer, "curves-ui-cog-reopened-7-hole.png")

        q.undo()
        q.settleRenders("undo")
        assertEquals("Undo: the cog exactly as it was", placed, layer.vector!!.objects.single())
        assertTrue("filled again", q.inkAt(layer, centre))
        q.redo()
        q.settleRenders("redo")
        assertEquals(edited, layer.vector!!.objects.single())
        Smoke.assertQuiet(c, "a cog opened again")
    }

    private fun flippedSpiral(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        val c = q.c
        q.tool("Curve")
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.ARCHIMEDEAN)
        q.applyEdit("Apply curve edit")
        val layer = c.activeLayer
        val spiral = layer.vector!!.objects.single() as VPath
        val g = requireNotNull(spiral.generator)

        // The Transform tool mirrors the layer's objects.
        q.tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue("the transform box", Smoke.pumpUntil(Qa18CurvesUi.WAIT_MS) { settle(1); tt.transformState != null })
        val steps = q.steps()
        q.tap(TransformStripItems.FLIP_HORIZONTALLY)
        StripTestKit.closeMoreRow()
        q.applyEdit("Apply transform edit")
        q.settleRenders("the flip")
        assertEquals("the flip is ONE step", steps + 1, q.steps())
        val flipped = layer.vector!!.objects.single() as VPath
        assertEquals("the same object", spiral.id, flipped.id)
        val fg = flipped.generator ?: throw AssertionError("the flip dropped the spiral's settings")
        assertEquals("mirrored: Clockwise flips", !g.clockwise, fg.clockwise)
        assertEquals(g.turns, fg.turns)
        assertEquals(g.size, fg.size, 0.01f)
        assertTrue("still its settings' curve (I16)", CurveGenerators.matches(flipped))

        // Later, the Curve tool: a tap opens it generated; Turns changes it; ✓.
        q.tool("Curve")
        val curve = c.currentTool as CurveTool
        q.tapDoc(q.onLine(flipped, 0.9f))
        assertEquals("the mirrored spiral opens with its settings", fg, curve.generator)
        assertTrue("its chip", chipShown(q))
        StripTestKit.closeMoreRow()
        q.tap(CurveGenLabels18.CHANGE)
        q.dragSlider(CurveGenLabels18.TURNS, 0.15f, downPast = 0.2f)
        val turned = curve.generator ?: throw AssertionError("Turns lost the settings")
        assertNotEquals("Turns changed", fg.turns, turned.turns)
        assertEquals(fg.copy(turns = turned.turns), turned)
        Finger.back()
        val edits = q.steps()
        q.applyEdit("Apply curve edit")
        assertEquals("✓ is ONE step", edits + 1, q.steps())
        val stored = layer.vector!!.objects.single() as VPath
        assertEquals(spiral.id, stored.id)
        assertEquals("stored with the new Turns", turned, stored.generator)
        assertTrue(CurveGenerators.matches(stored))
        q.shot(layer, "curves-ui-spiral-flipped-turns.png")
        Smoke.assertQuiet(c, "a flipped spiral")
    }

    @Test
    fun aPlacedCogAndAFlippedSpiralStayEditable() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 392f)
        h.section("a cog opened again: teeth and a hole, ✓, Undo") { cog(q) }
        h.section("a spiral flipped by the Transform tool, opened again, Turns") { flippedSpiral(q) }
        dog.interrupt()
        h.finish()
    }
}
