package com.brushwork.paint.qa17

import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.tools.INCREMENTS_LABEL
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
 * v1.7 items 9 and 13 (design §3.9, §3.13) on the REAL tools, driven by the labels the user sees
 * (the existing pill tests use a fake tool; RequestCoverageV17 covers the Curve tool and Transform's
 * Scale X on a shape):
 * - **Shape** (a pending rectangle, then a placed one): the pill names "Center"; in Points mode with
 *   no point selected it still shows the shape's centre; row 2 with "Keep scale proportions" off
 *   changes Scale Y alone, on it keeps the ratio; the trash reads "Delete shape". Folded, the pill
 *   is `[✥][🗑]` and the trash still deletes the placed shape's layer (one step "Delete shape"); a
 *   two-finger tap brings it back.
 * - **Path** (three points): X typed as "100/2", "(3+4)*2", "/2", "*1.5" and "-10" on the selected
 *   point, the live "= 50 px" readout first; a real finger held on "#" opens the Step popup for
 *   lengths without switching increments; with no point selected the pill shows "Center" (the
 *   points' centre) and the trash reads "Delete path"; folded, it deletes the path; two fingers
 *   bring it back.
 * - **Polyline:** "Delete selected points" with one point selected, "Delete polyline" with all.
 * - **Text:** row 2 is Scale X alone, "Keep scale proportions" on and disabled; Scale X "200" then
 *   "/2" doubles the type and halves it back; "Delete text" drops a new text.
 * - **Transform** on a shape layer: row 2, no trash (Transform deletes nothing, by design); keep off,
 *   Scale Y 50 halves the shape's height alone.
 * At 360 dp ([Qa17ChromePill360UiTest]) every cell of both rows is whole and inside the screen for
 * the real Shape and Text tools. One test per class, own sandbox; one fresh editor per section.
 */
internal object Qa17ChromePill {

    /** The pill's row 1 label (what is being placed). */
    private fun rowLabel(s: ChromeScreen, label: String): Boolean = Finger.element(s, label) != null

    /** The state ("200 px") of the "[axis] slider" cell. */
    private fun axisState(axis: String): String? = SmokeUi.find("$axis slider", exact = true)?.stateDescription

    /** The number the "[axis] slider" shows ("1,234.5 px" or "−10 px"). */
    private fun axisValue(axis: String): Float {
        val st = axisState(axis) ?: throw AssertionError("no $axis slider")
        return st.substringBefore(" ").replace(",", "").replace('−', '-').toFloat()
    }

    /** Taps the control labelled [label] with a real finger. */
    private fun finger(s: ChromeScreen, label: String) {
        s.touch.idle(300)
        Finger.tap(s, label)
        settle(4)
    }

    private fun tapTrash(s: ChromeScreen) {
        val r = requireNotNull(s.tagged(V17Tags.PILL_TRASH)) { "no trash cell; shown: ${SmokeUi.shown().take(60)}" }
        s.touch.idle(300)
        Finger.tapAt(s, r.center.x, r.center.y)
        settle(4)
    }

    private fun trashLabel(): String? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) == V17Tags.PILL_TRASH
    }?.node?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun keepState(): Pair<ToggleableState?, Boolean> {
        val e = SmokeUi.find(PillLabels.KEEP_PROPORTIONS, exact = true) ?: throw AssertionError("no keep cell")
        return e.node.config.getOrNull(SemanticsProperties.ToggleableState) to !e.node.config.contains(SemanticsProperties.Disabled)
    }

    private fun typeIn(cell: String, field: String, text: String) {
        click("Type $cell")
        SmokeUi.typeAndDone(field, text)
    }

    private fun twoFingersOnCanvas(s: ChromeScreen) {
        s.touch.idle(400)
        s.touch.twoFingerTap(s.screen(140f, 150f), s.screen(260f, 150f))
        settle(4)
    }

    // ================================================================== Shape

    fun shape(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val ui = Qa16Ui(s)
        ui.tool("Shape")
        val tool = c.currentTool as ShapeTool
        ui.stroke(120f to 90f, 200f to 150f, 280f to 210f)
        val b0 = requireNotNull(tool.box) { "no pending shape" }
        assertTrue("the pill names the shape's centre", rowLabel(s, "Center"))
        assertEquals(b0.cx, axisValue("X"), 0.06f)
        assertEquals(b0.cy, axisValue("Y"), 0.06f)
        assertNotNull("row 2", s.tagged(V17Tags.PILL_SCALE_ROW))
        for (l in listOf(PillLabels.SCALE_X, PillLabels.SCALE_Y, PillLabels.KEEP_PROPORTIONS, PillLabels.SCALE_INCREMENTS)) assertTrue(l, SmokeUi.has(l, exact = true))
        assertEquals(PillLabels.deleteObject("shape"), trashLabel())

        // Points mode with no point selected: still the shape's centre.
        click("Points", exact = true)
        assertTrue(tool.pointsMode)
        assertTrue("no point selected", tool.pointSelection.isEmpty)
        assertTrue("\"Center\" with no point selected", rowLabel(s, "Center"))
        assertEquals(b0.cx, axisValue("X"), 0.06f)
        assertEquals("the trash still deletes the shape", PillLabels.deleteObject("shape"), trashLabel())

        // Keep proportions off (a real finger): Scale Y alone.
        assertEquals(ToggleableState.On to true, keepState())
        finger(s, PillLabels.KEEP_PROPORTIONS)
        assertEquals("off", ToggleableState.Off, keepState().first)
        typeIn(PillLabels.SCALE_Y, PillLabels.SCALE_Y, "50")
        val b1 = tool.box!!
        assertEquals("the width stays", b0.w, b1.w, 0.05f)
        assertEquals("half the height", b0.h / 2f, b1.h, 0.05f)
        assertEquals("100 %", SmokeUi.find(PillLabels.SCALE_X, exact = true)?.stateDescription)
        assertEquals("50 %", SmokeUi.find(PillLabels.SCALE_Y, exact = true)?.stateDescription)
        // On again: Scale X 200 takes Y along (50 → 100).
        finger(s, PillLabels.KEEP_PROPORTIONS)
        assertEquals(ToggleableState.On, keepState().first)
        typeIn(PillLabels.SCALE_X, PillLabels.SCALE_X, "200")
        val b2 = tool.box!!
        assertEquals("twice as wide", b0.w * 2f, b2.w, 0.05f)
        assertEquals("the ratio kept: Y 50 → 100", b0.h, b2.h, 0.05f)
        assertEquals("the centre stays", b0.cx, b2.cx, 0.05f)
        Qa17Shots.screen(s, "pill-shape-392")
        // A finger drags Scale X 40 dp to the right (1 % per dp): wider, the ratio kept.
        val cell = requireNotNull(Finger.element(s, PillLabels.SCALE_X)) { "no Scale X" }
        Finger.slowDrag(s, cell.center.x to cell.center.y, cell.center.x + 40f to cell.center.y)
        val b3 = tool.box!!
        val pct = SmokeUi.find(PillLabels.SCALE_X, exact = true)!!.stateDescription!!.substringBefore(" ").toFloat()
        assertTrue("dragged wider: $pct %", pct > 220f && b3.w > b2.w * 1.1f)
        assertEquals("the ratio kept", b2.h / b2.w, b3.h / b3.w, 0.01f)

        // Placed, then opened again by a finger on its outline.
        val layers = c.doc.layers.size
        click("Apply shape edit")
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); tool.box == null && c.busyMessage == null && !c.vectors.isRendering })
        assertEquals("a shape layer", layers + 1, c.doc.layers.size)
        val placed = c.activeLayer
        assertNotNull(placed.shapeData)
        val steps = c.undoManager.undoCount
        ui.tap(b3.cx - b3.w / 2f, b3.cy)
        assertTrue("the shape is open again", Smoke.pumpUntil(10_000) { settle(1); tool.box != null })
        // Folded: [✥][🗑], and the trash deletes the shape's layer.
        finger(s, "Fold the X / Y strip")
        assertNull("no row 2 folded", s.tagged(V17Tags.PILL_SCALE_ROW))
        assertNull("no X folded", SmokeUi.find("X slider", exact = true))
        assertNull("no # folded", Finger.element(s, INCREMENTS_LABEL))
        assertTrue(SmokeUi.has("Unfold the X / Y strip", exact = true))
        assertEquals(PillLabels.deleteObject("shape"), trashLabel())
        tapTrash(s)
        assertNull("the shape is closed", tool.box)
        assertEquals("its layer is gone", layers, c.doc.layers.size)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DELETE_SHAPE, c.undoManager.undoLabel)
        twoFingersOnCanvas(s)
        assertEquals("two fingers bring it back", layers + 1, c.doc.layers.size)
        assertNotNull(c.doc.layers.firstOrNull { it.id == placed.id }?.shapeData)
        assertTrue("the feedback names the step", SmokeUi.has("Undo: ${HistoryLabels.DELETE_SHAPE}", exact = true))
        // Opened again, the pill is still folded (remembered); a finger unfolds it.
        ui.tap(b3.cx - b3.w / 2f, b3.cy)
        assertTrue("open again", Smoke.pumpUntil(10_000) { settle(1); tool.box != null })
        assertTrue("still folded", SmokeUi.has("Unfold the X / Y strip", exact = true))
        finger(s, "Unfold the X / Y strip")
        assertNotNull("unfolded", SmokeUi.find("X slider", exact = true))
        assertNotNull(s.tagged(V17Tags.PILL_SCALE_ROW))
        Smoke.assertQuiet(c, "shape")
    }

    // ================================================================== Path

    fun path(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val ui = Qa16Ui(s)
        ui.tool("Path")
        val tool = c.currentTool as CurveTool
        ui.tap(100f, 200f)
        ui.tap(200f, 100f)
        ui.tap(300f, 200f)
        assertEquals(3, tool.pointCount)
        // A new point is not selected; a finger on the middle one selects it.
        ui.tap(200f, 100f)
        assertTrue("one point selected", tool.pointSelection.isSingle)
        val i = tool.pointSelection.primary
        assertTrue("the pill names it", rowLabel(s, "Point ${i + 1}"))
        assertNotNull("row 2", s.tagged(V17Tags.PILL_SCALE_ROW))
        assertEquals("one of three selected", PillLabels.DELETE_POINTS, trashLabel())

        // X typed as expressions on the selected point; the readout first.
        click("Type X")
        assertNotNull("the operator keys", s.tagged(V17Tags.OPERATOR_KEYS))
        SmokeUi.field("X").type("100/2")
        settle(4)
        assertTrue("the result shows: ${SmokeUi.shown().take(60)}", SmokeUi.has("= 50 px", exact = true))
        SmokeUi.typeAndDone("X", "100/2")
        assertEquals("100/2", 50f, tool.pointAt(i).x, 1e-3f)
        val y = tool.pointAt(i).y
        for ((text, want) in listOf("(3+4)*2" to 14f, "/2" to 7f, "*1.5" to 10.5f, "-10" to -10f)) {
            typeIn("X", "X", text)
            assertEquals("\"$text\"", want, tool.pointAt(i).x, 1e-3f)
            assertEquals("Y stays", y, tool.pointAt(i).y, 1e-3f)
        }
        // Garbage is refused: the readout says so and OK is off.
        click("Type X")
        SmokeUi.field("X").type("3+*")
        settle(4)
        assertTrue("Check the expression: ${SmokeUi.shown().take(60)}", SmokeUi.has("Check the expression", exact = true))
        assertFalse("OK is off", SmokeUi.isEnabled("OK"))
        click("Cancel", exact = true)
        assertEquals(-10f, tool.pointAt(i).x, 1e-3f)

        // A real finger held on "#": the Step popup for lengths; increments stay off.
        assertFalse(c.increments.enabled)
        val hash = requireNotNull(Finger.element(s, INCREMENTS_LABEL)) { "no #" }
        val (hx, hy) = Finger.px(s, hash.center.x, hash.center.y)
        s.touch.idle(300)
        s.touch.send(MotionEvent.ACTION_DOWN, P(0, hx, hy))
        s.touch.holdRealTime(900)
        s.touch.send(MotionEvent.ACTION_UP, P(0, hx, hy))
        settle(4)
        assertTrue("the Step popup: ${SmokeUi.shown().take(60)}", SmokeUi.has("Step for lengths", exact = true))
        assertFalse("a long-press doesn't switch increments", c.increments.enabled)
        click("Cancel", exact = true)
        assertFalse(SmokeUi.has("Step for lengths", exact = true))

        // No point selected: "Center", the points' centre; the trash deletes the path.
        click(PointLabels.SELECT_ALL, exact = true)
        click(PointLabels.DESELECT_ALL, exact = true)
        assertTrue(tool.pointSelection.isEmpty)
        assertTrue("\"Center\" with no point selected", rowLabel(s, "Center"))
        val pts = (0 until 3).map { tool.pointAt(it) }
        val cx = (pts.minOf { it.x } + pts.maxOf { it.x }) / 2f
        assertEquals("the points' centre", cx, axisValue("X"), 0.5f)
        assertEquals(PillLabels.deleteObject("path"), trashLabel())
        finger(s, "Fold the X / Y strip")
        assertNull(s.tagged(V17Tags.PILL_SCALE_ROW))
        tapTrash(s)
        assertEquals("the path is gone", 0, tool.pointCount)
        assertNull("no pill without a path", SmokeUi.find("Unfold the X / Y strip", exact = true))
        twoFingersOnCanvas(s)
        assertEquals("two fingers bring it back", 3, tool.pointCount)
        assertEquals(-10f, tool.pointAt(i).x, 1e-3f)
        click("Unfold the X / Y strip", exact = true)
        Smoke.assertQuiet(c, "path")
    }

    // ================================================================== Polyline

    fun polyline(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val ui = Qa16Ui(s)
        ui.tool("Polyline")
        val tool = c.currentTool as CurveTool
        ui.tap(100f, 200f)
        ui.tap(200f, 100f)
        ui.tap(300f, 200f)
        assertEquals(3, tool.pointCount)
        ui.tap(200f, 100f)
        assertTrue("one point selected", tool.pointSelection.isSingle)
        assertEquals(PillLabels.DELETE_POINTS, trashLabel())
        click(PointLabels.SELECT_ALL, exact = true)
        assertEquals("all selected: the whole polyline", PillLabels.deleteObject("polyline"), trashLabel())
        tapTrash(s)
        assertEquals(0, tool.pointCount)
        twoFingersOnCanvas(s)
        assertEquals(3, tool.pointCount)
        Smoke.assertQuiet(c, "polyline")
    }

    // ================================================================== Text

    fun text(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val ui = Qa16Ui(s)
        ui.tool("Text")
        val tool = c.currentTool as TextTool
        ui.tap(200f, 150f)
        assertTrue("the text editor", tool.editorOpen)
        SmokeUi.field("Text").type("Hi")
        settle()
        click("OK", exact = true)
        val size = requireNotNull(tool.item) { "no text" }.spec.sizePx
        assertTrue("Center", rowLabel(s, "Center"))
        assertNotNull("row 2", s.tagged(V17Tags.PILL_SCALE_ROW))
        assertTrue(SmokeUi.has(PillLabels.SCALE_X, exact = true))
        assertFalse("no Scale Y for text", SmokeUi.has(PillLabels.SCALE_Y, exact = true))
        assertEquals("keep on and fixed", ToggleableState.On to false, keepState())
        assertEquals(PillLabels.deleteObject("text"), trashLabel())
        typeIn(PillLabels.SCALE_X, PillLabels.SCALE_X, "200")
        assertEquals("twice the type", size * 2f, tool.item!!.spec.sizePx, 0.05f)
        assertEquals("200 %", SmokeUi.find(PillLabels.SCALE_X, exact = true)?.stateDescription)
        typeIn(PillLabels.SCALE_X, PillLabels.SCALE_X, "/2")
        assertEquals("\"/2\" of 200 %", size, tool.item!!.spec.sizePx, 0.05f)
        // A finger on the disabled chain does nothing.
        finger(s, PillLabels.KEEP_PROPORTIONS)
        assertEquals(ToggleableState.On to false, keepState())
        tapTrash(s)
        assertNull("a new text is dropped", tool.item)
        assertNull("no pill", SmokeUi.find("Fold the X / Y strip", exact = true))
        Smoke.assertQuiet(c, "text")
    }

    // ================================================================== Transform

    fun transform(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val ui = Qa16Ui(s)
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 200f, cy = 150f, w = 120f, h = 80f, style = ShapeStyle.FILL, fillColor = 0xFF2244CC.toInt())
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { cv ->
            cv.drawRect(140f, 110f, 260f, 190f, Paint().apply { color = 0xFF2244CC.toInt() })
        }!!
        settle()
        ui.tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        assertEquals(TransformTool.Lifted.SHAPE, tt.lifted)
        assertNotNull("row 2", s.tagged(V17Tags.PILL_SCALE_ROW))
        assertTrue(SmokeUi.has(PillLabels.SCALE_Y, exact = true))
        assertNull("Transform has no trash cell", s.tagged(V17Tags.PILL_TRASH))
        finger(s, PillLabels.KEEP_PROPORTIONS)
        assertEquals(ToggleableState.Off, keepState().first)
        typeIn(PillLabels.SCALE_Y, PillLabels.SCALE_Y, "50")
        val before = c.undoManager.undoCount
        click("Apply transform edit")
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); !tt.hasPendingWork && c.busyMessage == null })
        assertEquals("one step", before + 1, c.undoManager.undoCount)
        val scaled = ShapeCodec.decode(layer.shapeData) ?: throw AssertionError("no longer a shape")
        assertEquals("the width stays", 120f, scaled.w, 0.5f)
        assertEquals("half the height", 40f, scaled.h, 0.5f)
        Smoke.assertQuiet(c, "transform")
    }

    // ================================================================== 360 dp

    /** Every cell of the pill shows whole and inside the screen. */
    private fun assertWhole(s: ChromeScreen, what: String) {
        val labels = listOf(
            "Fold the X / Y strip", "X slider", "Y slider", INCREMENTS_LABEL,
            PillLabels.KEEP_PROPORTIONS, PillLabels.SCALE_X, PillLabels.SCALE_Y, PillLabels.SCALE_INCREMENTS,
        )
        val w = s.widthDp
        val cells = labels.mapNotNull { l -> SmokeUi.find(l, exact = true)?.let { l to it } } +
            listOfNotNull(RobolectricUi.elements().lastOrNull { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == V17Tags.PILL_TRASH }?.let { "trash" to it })
        assertTrue("$what: the cells ${cells.map { it.first }}", cells.size >= 7)
        for ((l, e) in cells) {
            val n = e.node
            val r = s.dp(n.boundsInWindow)
            assertTrue("$what: \"$l\" whole ($r of ${n.size})", n.boundsInWindow.width >= n.size.width - 1f && n.boundsInWindow.height >= n.size.height - 1f)
            assertTrue("$what: \"$l\" inside the screen ($r of $w dp)", r.left >= -0.5f && r.right <= w + 0.5f)
        }
    }

    fun narrow(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val ui = Qa16Ui(s)
        assertEquals(360f, s.widthDp, 0.5f)
        ui.tool("Shape")
        ui.stroke(120f to 90f, 280f to 210f)
        assertNotNull((c.currentTool as ShapeTool).box)
        assertWhole(s, "shape")
        Qa17Shots.screen(s, "pill-shape-360")
        click("Apply shape edit")
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); (c.currentTool as? ShapeTool)?.box == null && c.busyMessage == null })
        ui.tool("Text")
        val text = c.currentTool as TextTool
        ui.tap(200f, 250f)
        SmokeUi.field("Text").type("Narrow")
        settle()
        click("OK", exact = true)
        assertNotNull(text.item)
        assertWhole(s, "text")
        Smoke.assertQuiet(c, "360 dp")
    }

    fun run(name: String, sections: List<Pair<String, (ChromeHarness) -> Unit>>) {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val h = ChromeHarness()
        for ((what, block) in sections) h.section("$name: $what") { block(h) }
        dog.interrupt()
        h.finish()
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.chromepill392sandbox"])
class Qa17ChromePillToolsUiTest {
    @Test
    fun thePillOnTheRealTools() = Qa17ChromePill.run(
        "392 dp",
        listOf(
            "Shape" to Qa17ChromePill::shape,
            "Path" to Qa17ChromePill::path,
            "Polyline" to Qa17ChromePill::polyline,
            "Text" to Qa17ChromePill::text,
            "Transform" to Qa17ChromePill::transform,
        ),
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.chromepill360sandbox"])
class Qa17ChromePill360UiTest {
    @Test
    fun thePillIsWholeAt360dp() = Qa17ChromePill.run("360 dp", listOf("Shape and Text" to Qa17ChromePill::narrow))
}
