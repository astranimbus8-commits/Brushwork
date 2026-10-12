package com.brushwork.paint.qa18

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa18.Qa18MirrorsKit.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.symmetry.SymmetryStripItems
import com.brushwork.paint.vector.ObjectCopies
import org.junit.Assert.assertArrayEquals
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
 * v1.8 polish, the verifier's flow around items 1 and 2 (mirrors follow-ups) on the narrowest
 * supported phone (360 dp), every step by a finger, the way the refusal tells the user to go:
 * - The Perspective array's default cell refuses a clone stroke with
 *   [MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE], laid out whole at 360 dp (no overflow, at most
 *   three lines); no pixel, no step.
 * - From the Clone stamp, the top row's "Ruler" panel, "Place on canvas" (the Symmetry tool: the
 *   only one that shows and drags the cell's corners), the four corners dragged outward, "Done":
 *   back to the Clone stamp (item 1 through the panel, from the tool the QA finding named), its
 *   source kept.
 * - The same stroke then paints in ONE step; Undo restores the exact pixels, Redo the exact result.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishmirrorsverifyclonesandbox"])
class Qa18PolishMirrorsVerifyCloneUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    private fun steps() = c.undoManager.undoCount

    private fun counted(): Int = CloneTool.copiesCounted(SymmetryType.PERSPECTIVE_ARRAY, ObjectCopies.mapsAt(c, A.x, A.y), A.x, A.y, W, H)

    private fun strokeAlong(a: Vec2, b: Vec2) =
        ui.stroke(*Array(11) { i -> (a.x + (b.x - a.x) * i / 10f) to (a.y + (b.y - a.y) * i / 10f) })

    /** The shown refusal's text layout: it must fit (no visual overflow) in at most three lines. */
    private fun assertLaidOutWhole(label: String) {
        val node = SmokeUi.find(label, exact = true)?.node ?: throw AssertionError("not shown: $label")
        val get = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertNotNull("the refusal is a text with a layout", get)
        val layouts = mutableListOf<TextLayoutResult>()
        get!!.invoke(layouts)
        val layout = layouts.single()
        println("Qa18PolishMirrorsVerifyClone: the refusal at 360 dp, ${layout.lineCount} lines, ${node.boundsInWindow}")
        assertFalse("360 dp: the refusal is not cut off", layout.hasVisualOverflow)
        assertTrue("360 dp: at most three lines (${layout.lineCount})", layout.lineCount in 1..3)
        assertEquals("360 dp: every character laid out", label.length, layout.layoutInput.text.length)
    }

    private fun flow(h: ChromeHarness) {
        s = h.editor(Smoke.document(W, H, layers = 2, whiteBottom = true)) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the narrowest phone, 360 dp", 360f, s.widthDp, 1f)
        Qa18MirrorsKit.rulerOn(ui, SymmetryType.PERSPECTIVE_ARRAY)

        // The Clone stamp, its hard pen, "Set source" and a tap on the R.
        Qa18MirrorsKit.seedLettered(c, c.activeLayer, emptyList(), SOURCE)
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        c.updatePreset(ToolId.CLONE, pen.copy(size = 16f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f, taperStart = 0f, taperEnd = 0f))
        ui.tool("Clone stamp")
        val clone = c.currentTool as CloneTool
        ui.reach("Set source", 32f)
        click("Set source", exact = true)
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        ui.tap(SOURCE.x, SOURCE.y)
        val source = clone.anchor.source
        assertNotNull("the tap set the source", source)

        // The default cell: refused with the message that says where and what to drag.
        val n = counted()
        assertTrue("the default cell: more than 32 copies ($n)", n > CloneTool.MAX_MAPS)
        val layer = c.activeLayer
        val before = pixels(layer.bitmap)
        val n0 = steps()
        strokeAlong(A, B)
        assertTrue(
            "the refusal shows: ${SmokeUi.shown().take(40)}",
            Smoke.pumpUntil(10_000) { settle(1); SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE, exact = true) },
        )
        assertFalse("not the other rulers' message", SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY, exact = true))
        assertEquals("refused: no step", n0, steps())
        assertArrayEquals("refused: no pixel", before, pixels(layer.bitmap))
        assertLaidOutWhole(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE)
        Qa18PolishMirrorsKit.shoot(s, "verify-clone-360-refused")

        // What it says, through the top row's Ruler panel: "Place on canvas" (the Symmetry tool).
        Finger.tap(s, "Ruler")
        settle()
        assertTrue("the Ruler panel: ${SmokeUi.shown().take(40)}", SmokeUi.has("Use ruler", exact = true))
        assertEquals("opening the panel keeps the clone stamp", ToolId.CLONE, c.activeToolId)
        ui.reach(PLACE_ON_CANVAS, 32f)
        // reach scrolls the sheet by a finger: let it come to rest, or the tap only stops it.
        Smoke.pump(1_000)
        settle()
        Finger.tap(s, PLACE_ON_CANVAS)
        settle()
        assertEquals("\"$PLACE_ON_CANVAS\": the Symmetry tool", ToolId.SYMMETRY, c.activeToolId)
        assertTrue("the panel closed", Smoke.pumpUntil(5_000) { settle(1); !SmokeUi.has("Use ruler", exact = true) })
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        for ((from, to) in CORNERS_OUT) {
            val was = c.symmetry
            QaCurves.drag(s, from, Vec2(to.x - from.x, to.y - from.y))
            assertNotEquals("the corner at $from moved", was, c.symmetry)
        }
        assertEquals("still the Perspective array", SymmetryType.PERSPECTIVE_ARRAY, c.symmetry.type)
        ui.reach(SymmetryStripItems.DONE_LABEL, 32f)
        Finger.tap(s, SymmetryStripItems.DONE_LABEL)
        settle()
        assertEquals("Done: back to the clone stamp the user came from", ToolId.CLONE, c.activeToolId)
        assertEquals("the same clone stamp, its source kept", source, (c.currentTool as CloneTool).anchor.source)
        assertEquals("no tool change is a step", n0, steps())
        val m = counted()
        println("Qa18PolishMirrorsVerifyClone: $n copies counted, $m after the corners went outward")
        assertTrue("corners outward: 32 or fewer ($n to $m)", m <= CloneTool.MAX_MAPS)

        // The same stroke paints in ONE step; Undo and Redo are exact.
        assertTrue(
            "the refusal goes",
            Smoke.pumpUntil(15_000) { settle(1); !SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE, exact = true) },
        )
        strokeAlong(A, B)
        settle()
        assertEquals("the stroke: ONE step", n0 + 1, steps())
        val after = pixels(layer.bitmap)
        assertFalse("the stroke painted", before.contentEquals(after))
        assertFalse("no refusal", SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE, exact = true) || SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY, exact = true))
        Finger.tap(s, "Undo")
        settle()
        assertEquals("Undo takes the one step", n0, steps())
        assertArrayEquals("Undo: the exact pixels", before, pixels(layer.bitmap))
        Finger.tap(s, "Redo")
        settle()
        assertEquals("Redo brings it back", n0 + 1, steps())
        assertArrayEquals("Redo: the exact result", after, pixels(layer.bitmap))
        assertEquals("still the clone stamp", ToolId.CLONE, c.activeToolId)
        Smoke.assertQuiet(c, "verify clone")
    }

    @Test
    fun theRefusalLeadsThroughTheRulerPanelBackToTheCloneStampAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        h.section("Clone stamp, Perspective array, 360 dp") { flow(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300
        const val PLACE_ON_CANVAS = "Place on canvas"

        /** As Qa18PolishMirrorsCloneUiTest: the stroke and the source. */
        val A = Vec2(170f, 190f)
        val B = Vec2(200f, 190f)
        val SOURCE = Vec2(200f, 150f)

        /** The default cell's corners on 400 × 300 (TL 176,120 TR 224,120 BR 230,180 BL 170,180), each dragged away from the cell. */
        val CORNERS_OUT = listOf(
            Vec2(230f, 180f) to Vec2(262f, 205f), Vec2(170f, 180f) to Vec2(138f, 205f),
            Vec2(176f, 120f) to Vec2(158f, 105f), Vec2(224f, 120f) to Vec2(242f, 105f),
        )
    }
}
