package com.brushwork.paint.qa18

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18MirrorsKit.divisions
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
import com.brushwork.paint.vector.ObjectCopies
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (mirrors follow-ups, item 2) on the user's phone (392 dp), every step by a finger:
 * the clone stamp's refusal of more than 32 copies says how to fix it under the Perspective array.
 * - The Perspective array with its default cell (more than 32 copies counted on any canvas): a
 *   clone stroke is refused with [MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE] (drag the cell's
 *   corners outward), not the cap's plain message; no pixel, no step.
 * - Doing what it says (the Symmetry tool, its four corners dragged outward, Done: back to the
 *   clone stamp) brings the count to 32 or fewer, and the same stroke paints in ONE step.
 * - Kaleidoscope 17 (34 copies) keeps [MirrorLabels18.CLONE_TOO_MANY]; no pixel, no step.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishmirrorsclonesandbox"])
class Qa18PolishMirrorsCloneUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    private fun editor(h: ChromeHarness) {
        s = h.editor(Smoke.document(W, H, layers = 2, whiteBottom = true)) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
    }

    private fun steps() = c.undoManager.undoCount

    /** The clone stamp with its hard pen, "Set source" and a finger on [source]; the layer seeded with a gradient and an R there. */
    private fun cloneReady(source: Vec2): CloneTool {
        Qa18MirrorsKit.seedLettered(c, c.activeLayer, emptyList(), source)
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        c.updatePreset(ToolId.CLONE, pen.copy(size = 16f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f, taperStart = 0f, taperEnd = 0f))
        ui.tool("Clone stamp")
        val clone = c.currentTool as CloneTool
        ui.reach("Set source", 32f)
        click("Set source", exact = true)
        assertTrue("Set source arms the next tap", clone.armed)
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        ui.tap(source.x, source.y)
        assertTrue("the tap set the source", clone.anchor.source != null)
        return clone
    }

    private fun strokeAlong(a: Vec2, b: Vec2) =
        ui.stroke(*Array(11) { i -> (a.x + (b.x - a.x) * i / 10f) to (a.y + (b.y - a.y) * i / 10f) })

    private fun counted(type: SymmetryType): Int {
        val maps = ObjectCopies.mapsAt(c, A.x, A.y)
        return CloneTool.copiesCounted(type, maps, A.x, A.y, W, H)
    }

    /** One stroke [A] to [B] refused with [message] (and never with [not]): no step, no pixel. */
    private fun refused(what: String, message: String, not: String) {
        val layer = c.activeLayer
        val before = pixels(layer.bitmap)
        val n0 = steps()
        strokeAlong(A, B)
        assertTrue(
            "$what: the message says why: ${SmokeUi.shown().take(40)}",
            Smoke.pumpUntil(10_000) { settle(1); SmokeUi.has(message, exact = true) },
        )
        assertFalse("$what: not the other message", SmokeUi.has(not, exact = true))
        assertEquals("$what: no step", n0, steps())
        assertArrayEquals("$what: no pixel", before, pixels(layer.bitmap))
    }

    @Test
    fun theCloneRefusalUnderThePerspectiveArraySaysHowToFixIt() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()

        h.section("Perspective array, the default cell") {
            editor(h)
            Qa18MirrorsKit.rulerOn(ui, SymmetryType.PERSPECTIVE_ARRAY)
            cloneReady(SOURCE)
            val n = counted(SymmetryType.PERSPECTIVE_ARRAY)
            println("Qa18PolishMirrorsClone: the default cell, $n copies counted")
            assertTrue("the default cell: more than 32 copies ($n)", n > CloneTool.MAX_MAPS)
            refused("the default cell", MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE, MirrorLabels18.CLONE_TOO_MANY)
            Qa18PolishMirrorsKit.shoot(s, "clone-perspective-refused")

            // What the message says: the cell's four corners dragged outward on the canvas.
            Qa18MirrorsKit.rulerOn(ui, SymmetryType.PERSPECTIVE_ARRAY, emptyList(), CORNERS_OUT)
            assertEquals("Done: back to the clone stamp", ToolId.CLONE, c.activeToolId)
            val m = counted(SymmetryType.PERSPECTIVE_ARRAY)
            println("Qa18PolishMirrorsClone: corners dragged outward, $m copies counted")
            assertTrue("corners outward: fewer copies ($n to $m)", m < n)
            assertTrue("corners outward: 32 or fewer ($m)", m <= CloneTool.MAX_MAPS)
            val layer = c.activeLayer
            val before = pixels(layer.bitmap)
            val n0 = steps()
            // The first refusal's message goes on its own (a short snackbar), so the render shows
            // what this stroke brings.
            assertTrue(
                "the first refusal's message goes",
                Smoke.pumpUntil(15_000) { settle(1); !SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE, exact = true) },
            )
            strokeAlong(A, B)
            assertEquals("the bigger cell: the stroke paints in ONE step", n0 + 1, steps())
            assertFalse("pixels changed", before.contentEquals(pixels(layer.bitmap)))
            settle()
            assertTrue("no refusal this time", c.message != MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE)
            assertFalse("no refusal shown", SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE, exact = true) || SmokeUi.has(MirrorLabels18.CLONE_TOO_MANY, exact = true))
            Qa18PolishMirrorsKit.shoot(s, "clone-perspective-corners-out")
            Finger.tap(s, "Undo")
            assertEquals("Undo takes the one step", n0, steps())
            assertArrayEquals("Undo: the exact pixels", before, pixels(layer.bitmap))
            Smoke.assertQuiet(c, "perspective")
        }
        h.section("Kaleidoscope 17 keeps the cap's message") {
            editor(h)
            Qa18MirrorsKit.rulerOn(ui, SymmetryType.KALEIDOSCOPE, listOf(divisions("17")))
            assertEquals("Divisions 17 typed", 17, c.symmetry.divisions)
            cloneReady(SOURCE)
            assertEquals("34 copies", 34, counted(SymmetryType.KALEIDOSCOPE))
            refused("Kaleidoscope 17", MirrorLabels18.CLONE_TOO_MANY, MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE)
            Qa18PolishMirrorsKit.shoot(s, "clone-kaleidoscope17-refused")
            Smoke.assertQuiet(c, "kaleidoscope")
        }

        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300

        /** The stroke and the source (as Qa18MirrorsCloneUiTest's perspective case). */
        val A = Vec2(170f, 190f)
        val B = Vec2(200f, 190f)
        val SOURCE = Vec2(200f, 150f)

        /** The default cell's corners (400 × 300: TL 176,120 TR 224,120 BR 230,180 BL 170,180), each dragged away from the cell. */
        val CORNERS_OUT = listOf(
            Vec2(230f, 180f) to Vec2(262f, 205f), Vec2(170f, 180f) to Vec2(138f, 205f),
            Vec2(176f, 120f) to Vec2(158f, 105f), Vec2(224f, 120f) to Vec2(242f, 105f),
        )
    }
}
