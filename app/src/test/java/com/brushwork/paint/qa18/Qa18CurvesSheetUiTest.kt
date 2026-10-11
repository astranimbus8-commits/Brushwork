package com.brushwork.paint.qa18

import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VPath
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
 * v1.8 final QA (item 6, design §3.7) on the user's phone (392 dp), by finger: for EVERY Curves
 * entry on the Curve tool, every setting its sheet shows ("Turns", "Inner size", "Growth per
 * turn", "Waves", "Wave height", "Petals", "Petal divisor", "Clockwise", "Points per turn") is
 * changed with a finger (a slider drag, a toggle tap). Each change sets its own setting alone,
 * the open curve follows it live (its points and the canvas), and no hint says the settings are
 * gone. After the sheet closes (Back), the top row's Undo takes the changes back ONE per tap,
 * Redo brings them again, and the ✓ stores the curve with its settings (one step). The Path tool
 * does the same for a Rose and a Sine wave through the chip on its More row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.curvessheetsandbox"])
class Qa18CurvesSheetUiTest {

    /** A change of the setting [label] by finger, and the generator with that setting alone taken from [after]. */
    private class Change(val label: String, val act: (Qa18CurvesUi) -> Unit, val only: (CurveGenerator, CurveGenerator) -> CurveGenerator)

    private val changes = mapOf(
        CurveGenLabels18.TURNS to Change(CurveGenLabels18.TURNS, { it.dragSlider(CurveGenLabels18.TURNS, 0.15f, downPast = 0.2f) }) { b, a -> b.copy(turns = a.turns) },
        CurveGenLabels18.INNER to Change(CurveGenLabels18.INNER, { it.dragSlider(CurveGenLabels18.INNER, 0.4f, downPast = 0.5f) }) { b, a -> b.copy(inner = a.inner) },
        CurveGenLabels18.GROWTH to Change(CurveGenLabels18.GROWTH, { it.dragSlider(CurveGenLabels18.GROWTH, 0.2f, downPast = 0.5f) }) { b, a -> b.copy(growth = a.growth) },
        CurveGenLabels18.WAVES to Change(CurveGenLabels18.WAVES, { it.dragSlider(CurveGenLabels18.WAVES, 0.12f, downPast = 0.3f) }) { b, a -> b.copy(waves = a.waves) },
        CurveGenLabels18.WAVE_HEIGHT to Change(CurveGenLabels18.WAVE_HEIGHT, { it.dragSlider(CurveGenLabels18.WAVE_HEIGHT, 0.3f, downPast = 0.5f) }) { b, a -> b.copy(amplitude = a.amplitude) },
        CurveGenLabels18.PETALS to Change(CurveGenLabels18.PETALS, { it.dragSlider(CurveGenLabels18.PETALS, 0.2f, downPast = 0.4f) }) { b, a -> b.copy(petalsN = a.petalsN) },
        CurveGenLabels18.PETAL_DIVISOR to Change(CurveGenLabels18.PETAL_DIVISOR, { it.dragSlider(CurveGenLabels18.PETAL_DIVISOR, 0.25f, downPast = 0.4f) }) { b, a -> b.copy(petalsD = a.petalsD) },
        CurveGenLabels18.CLOCKWISE to Change(CurveGenLabels18.CLOCKWISE, { it.tap(CurveGenLabels18.CLOCKWISE) }) { b, a -> b.copy(clockwise = a.clockwise) },
        CurveGenLabels18.POINTS_PER_TURN to Change(CurveGenLabels18.POINTS_PER_TURN, { it.dragSlider(CurveGenLabels18.POINTS_PER_TURN, 0.25f, downPast = 0.3f) }) { b, a -> b.copy(density = a.density) },
    )

    /** The settings the sheet shows for [kind] (its visible labels, top to bottom). */
    private fun settingsOf(kind: CurveGenKind): List<String> = buildList {
        val spiral = kind != CurveGenKind.SINE && kind != CurveGenKind.ROSE
        if (spiral) add(CurveGenLabels18.TURNS)
        if (kind == CurveGenKind.ARCHIMEDEAN) add(CurveGenLabels18.INNER)
        if (kind == CurveGenKind.LOGARITHMIC) add(CurveGenLabels18.GROWTH)
        if (kind == CurveGenKind.SINE) { add(CurveGenLabels18.WAVES); add(CurveGenLabels18.WAVE_HEIGHT) }
        if (kind == CurveGenKind.ROSE) { add(CurveGenLabels18.PETALS); add(CurveGenLabels18.PETAL_DIVISOR) }
        if (spiral || kind == CurveGenKind.SINE) add(CurveGenLabels18.CLOCKWISE)
        add(CurveGenLabels18.POINTS_PER_TURN)
    }

    /**
     * A setting whose change shows on the canvas whatever the curve is now: on a Golden or
     * Logarithmic spiral more "Turns" add turns at the centre too small to see, so Clockwise
     * (the curve mirrored) is the one checked for the spirals and the wave; Petals for the rose.
     */
    private fun visibleSetting(kind: CurveGenKind) = if (kind == CurveGenKind.ROSE) CurveGenLabels18.PETALS else CurveGenLabels18.CLOCKWISE

    private fun labelOf(kind: CurveGenKind) = when (kind) {
        CurveGenKind.ARCHIMEDEAN -> CurveGenLabels18.ARCHIMEDEAN
        CurveGenKind.GOLDEN -> CurveGenLabels18.GOLDEN
        CurveGenKind.LOGARITHMIC -> CurveGenLabels18.LOGARITHMIC
        CurveGenKind.FERMAT -> CurveGenLabels18.FERMAT
        CurveGenKind.SINE -> CurveGenLabels18.SINE
        CurveGenKind.ROSE -> CurveGenLabels18.ROSE
    }

    /**
     * On the open [tool] (nothing open yet): Curves ▸ [kind], its chip, then each of [settings] by
     * finger; Back; Undo per change, Redo per change; the ✓ ([apply]).
     */
    private fun generate(q: Qa18CurvesUi, tool: CurveTool, kind: CurveGenKind, settings: List<String>, apply: String) {
        val c = q.c
        val what = "${tool.id.label}, ${labelOf(kind)}"
        assertEquals("$what: nothing open", 0, tool.pointCount)
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(labelOf(kind))
        val start = tool.generator ?: throw AssertionError("$what: no curve placed")
        assertEquals(kind, start.kind)
        StripTestKit.closeMoreRow()
        val stepsBefore = q.steps()
        c.message = null

        q.tap(CurveGenLabels18.CHANGE)
        assertTrue("$what: the sheet; shown: ${SmokeUi.shown().take(60)}", has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
        val seen = ArrayList<CurveGenerator>()
        seen += start
        for (label in settings) {
            val ch = changes.getValue(label)
            val before = tool.generator!!
            val anchors = tool.anchors
            val screen = q.capture()
            ch.act(q)
            val after = tool.generator ?: throw AssertionError("$what: \"$label\" lost the curve's settings")
            assertNotEquals("$what: \"$label\" changed the curve", before, after)
            assertEquals("$what: \"$label\" sets its own setting alone", ch.only(before, after), after)
            assertTrue("$what: \"$label\": the points follow ($before to $after, ${anchors.size} to ${tool.anchors.size} anchors)", anchors != tool.anchors)
            if (label == visibleSetting(kind)) {
                // The canvas above the sheet follows live (the sheet's own pixels left out).
                val r = maxOf(before.size, after.size) + 20f
                val shown = q.changedAboveSheet(screen, q.capture(), before.cx - r, before.cy - r, before.cx + r, before.cy + r)
                assertTrue("$what: \"$label\": the canvas follows ($shown px)", shown > 50)
            }
            seen += after
        }
        assertFalse("$what: no hint that the settings are gone", c.message == CurveGenLabels18.EDITED_HINT)
        Finger.back()
        assertFalse("$what: Back closed the sheet", has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
        assertEquals("$what: the open curve is not stored yet", stepsBefore, q.steps())

        // Undo: one change per tap, back to the curve as placed; Redo: one change per tap.
        for (i in seen.indices.reversed().drop(1)) {
            q.undo()
            assertEquals("$what: Undo takes back \"${settings[i]}\" alone", seen[i], tool.generator)
        }
        for (i in 1 until seen.size) {
            q.redo()
            assertEquals("$what: Redo brings \"${settings[i - 1]}\" back", seen[i], tool.generator)
        }
        val final = seen.last()
        assertNotNull("$what: the chip again", Finger.control(q.s, CurveGenLabels18.CHANGE) ?: run { StripTestKit.reach { SmokeUi.find(CurveGenLabels18.CHANGE, exact = true) } })
        StripTestKit.closeMoreRow()

        val layer = c.activeLayer
        val count = layer.vector!!.objects.size
        q.applyEdit(apply)
        assertEquals("$what: the ✓ is one step", stepsBefore + 1, q.steps())
        val stored = layer.vector!!.objects.last() as VPath
        assertEquals("$what: one more object", count + 1, layer.vector!!.objects.size)
        assertEquals("$what: stored with its settings", final, stored.generator)
        assertTrue("$what: the stored curve is its settings' (I16)", CurveGenerators.matches(stored))
        assertTrue("$what: drawn", q.inkAt(layer, q.onLine(stored, 0.3f)))
        q.shot(layer, "curves-ui-${tool.id.name.lowercase()}-${kind.name.lowercase()}.png")
        Smoke.assertQuiet(c, what)
    }

    private fun curveTool(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Curve")
        val tool = q.c.currentTool as CurveTool
        for (kind in CurveGenKind.entries) {
            generate(q, tool, kind, settingsOf(kind), "Apply curve edit")
            // A generated rose shows Closed while it is open; the next curve has the user's own setting again.
            assertFalse("after ${labelOf(kind)}: the next curve is open again", tool.settings.closed)
        }
    }

    private fun pathTool(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Path")
        val tool = q.c.currentTool as CurveTool
        generate(q, tool, CurveGenKind.ROSE, listOf(CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR, CurveGenLabels18.POINTS_PER_TURN), "Apply path edit")
        val rose = q.c.activeLayer.vector!!.objects.last() as VPath
        assertTrue("a Path rose is cyclic", rose.spline?.cyclic == true)
        assertFalse("the next path is not cyclic", tool.pathCyclic)
        generate(q, tool, CurveGenKind.SINE, listOf(CurveGenLabels18.WAVES, CurveGenLabels18.WAVE_HEIGHT, CurveGenLabels18.CLOCKWISE), "Apply path edit")
    }

    @Test
    fun everySheetSettingChangesItsCurveLiveAndUndoesOneTapAtATime() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 400_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 392f)
        h.section("the Curve tool: every Curves entry, every setting, by finger") { curveTool(q) }
        h.section("the Path tool: a rose and a wave from the More row's chip") { pathTool(q) }
        dog.interrupt()
        h.finish()
    }
}
