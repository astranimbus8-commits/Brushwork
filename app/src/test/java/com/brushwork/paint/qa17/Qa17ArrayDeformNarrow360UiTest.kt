package com.brushwork.paint.qa17

import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.RED
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.placement.MeshStepperLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 final QA (items 3 and 16) on a narrow 360 dp phone: every control of the Array sheet in
 * each of its four modes (segments, fields, steppers, scrub handles, toggles, "Draw guide" /
 * "Use a path", "Edit source pixels") and its footer ("Remove array", "Apply array") comes
 * wholly into view by scrolling as a finger does, nothing wider than the screen; a value typed
 * and a segment tapped with a finger work there. The Free deform strip's controls are all
 * reachable and its steppers work under a finger.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.arraydeformnarrowsandbox"])
class Qa17ArrayDeformNarrow360UiTest {

    @Test
    fun arraySheetAndMeshStripAt360Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("the Array sheet: every mode's controls and the footer reachable at 360 dp") { sheet(Qa17ArrayUi(h)) }
        h.section("the Free deform strip: every control reachable at 360 dp") { mesh(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    /** A button, chip, segment or toggle [label]: scrolled wholly into view, at least 32 dp, inside the screen. */
    private fun reachable(u: Qa17ArrayUi, label: String) {
        u.ui.reach(label, 32f)
        assertTrue("\"$label\" wholly on the 360 dp screen", u.ui.wholly(label, 32f))
    }

    /** A number field [label]: scrolled wholly into view, inside the screen sideways, not squeezed. */
    private fun field(u: Qa17ArrayUi, label: String) {
        val n = u.intoView(label) { SmokeUi.field(label).node }
        val b = n.boundsInWindow
        val r = u.s.root
        assertTrue("\"$label\" inside the screen sideways: $b of $r", b.left >= r.left - 0.5f && b.right <= r.right + 0.5f)
        assertTrue("\"$label\" at least 64 dp wide: ${b.width / u.s.density}", b.width >= 64f * u.s.density)
    }

    /** A scrub handle (a field without steps): wholly into view and inside the screen. */
    private fun scrub(u: Qa17ArrayUi, field: String) {
        val label = "Drag sideways to change $field"
        val n = u.intoView(label) { requireNotNull(SmokeUi.find(label, exact = true)) { "no \"$label\"" }.node }
        val b = n.boundsInWindow
        assertTrue("\"$label\" inside the screen: $b", b.left >= u.s.root.left - 0.5f && b.right <= u.s.root.right + 0.5f)
    }

    private fun sheet(u: Qa17ArrayUi) {
        u.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), widthDp = 360f)
        val c = u.c
        u.seed(c.activeLayer, 40f, 40f, 90f, 90f, RED)
        c.setSelection(u.rectSelection(36f, 36f, 94f, 94f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val layer = c.activeLayer
        u.showArraySheet()

        for (m in listOf(ArrayLabels.LINE, ArrayLabels.CIRCLE, ArrayLabels.CURVE, ArrayLabels.TRANSFORM)) reachable(u, m)
        field(u, "Count")
        reachable(u, "Increase Count")
        reachable(u, "Decrease Count")
        field(u, "Relative X")
        field(u, "Relative Y")
        reachable(u, "Increase Relative Y")
        field(u, "Constant X")
        scrub(u, "Constant X")
        field(u, "Constant Y")
        scrub(u, "Constant Y")
        reachable(u, ArrayLabels.EDIT_SOURCE)
        reachable(u, ArrayLabels.REMOVE)
        reachable(u, ArrayLabels.APPLY)

        u.press(ArrayLabels.CIRCLE)
        u.settleRenders("circle")
        assertEquals(ArrayMode.CIRCLE, layer.array!!.spec.mode)
        field(u, "Sweep")
        reachable(u, "Increase Sweep")
        reachable(u, "Rotate copies")

        u.press(ArrayLabels.CURVE)
        u.settleRenders("curve")
        assertEquals(ArrayMode.CURVE, layer.array!!.spec.mode)
        reachable(u, "Draw guide")
        reachable(u, "Use a path")
        field(u, ArrayLabels.COPY_SPACING)
        reachable(u, "Align to curve")

        u.press(ArrayLabels.TRANSFORM)
        u.settleRenders("transform")
        assertEquals(ArrayMode.TRANSFORM, layer.array!!.spec.mode)
        field(u, "Move X")
        scrub(u, "Move X")
        field(u, "Move Y")
        field(u, "Turn")
        field(u, ArrayLabels.SCALE_PER_COPY)
        reachable(u, ArrayLabels.APPLY)

        // At 360 dp a typed value and a finger on a segment still work.
        val n = u.steps()
        u.typeField("Turn", "20")
        u.settleRenders("turn")
        assertEquals(n + 1, u.steps())
        assertEquals(20f, layer.array!!.spec.turnDeg, 0.01f)
        u.shot("narrow360-sheet")
        u.fingerPress(ArrayLabels.LINE)
        u.settleRenders("line")
        assertEquals("a finger on the Line segment", ArrayMode.LINE, layer.array!!.spec.mode)
        Smoke.assertQuiet(c, "array sheet at 360 dp")
    }

    private fun mesh(u: Qa17ArrayUi) {
        u.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), widthDp = 360f)
        val c = u.c
        u.seed(c.activeLayer, 20f, 20f, 380f, 280f, RED)
        u.tool("Transform")
        val t = c.currentTool as TransformTool
        assertTrue("lifted", Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { settle(1); t.lifted != null })
        u.press(TransformLabels17.FREE_DEFORM)
        assertEquals(TransformTool.Mode.MESH, t.mode)
        for (label in listOf(
            PointLabels.SELECT_SEVERAL, PointLabels.SELECT_ALL,
            MeshStepperLabels.FEWER_COLUMNS, MeshStepperLabels.MORE_COLUMNS,
            MeshStepperLabels.FEWER_ROWS, MeshStepperLabels.MORE_ROWS,
            TransformLabels17.SMOOTH, TransformLabels17.RESET,
            "Apply transform edit", "Discard transform edit",
        )) reachable(u, label)
        // Fingers on the steppers.
        u.fingerPress(MeshStepperLabels.MORE_COLUMNS)
        u.fingerPress(MeshStepperLabels.MORE_ROWS)
        assertEquals(4, t.meshColumns)
        assertEquals(4, t.meshRows)
        assertEquals(25, t.pointCount)
        val smooth = t.smoothMesh
        u.fingerPress(TransformLabels17.SMOOTH)
        assertEquals(!smooth, t.smoothMesh)
        Smoke.assertQuiet(c, "mesh strip at 360 dp")
    }
}
