package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.FramesUi
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.placement.MeshStepperLabels
import com.brushwork.paint.ui.placement.TransformStripItems
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (request items 8 and 9, QA: icon-only strip items had a name for a screen reader
 * but no long-press bubble). On the user's phone (392 × 873 dp), with real touches: every tool of
 * the tool menu picked on a raster and on a vector layer, then the states that show more icons
 * (Transform in box modes and in Free deform, a polygon and a curve lasso with a selection, an
 * open text, Text frames with a frame, Masks with a brush part, Frame divider with a frame layer,
 * selected vector objects): a finger held on each icon-only control of the strip (row 1 and its
 * More row) and of the bar under it shows the control's name in the bubble, under it, and changes
 * nothing else (no step, no window, the tool and the strip as they were). A tap still acts. A
 * popup opened from the strip (Free deform's mesh size) shows no bubble: it is not a strip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishchromelongpresssandbox"])
class Qa18PolishChromeLongPressTest {

    private val seen = linkedSetOf<String>()
    private val held = mutableListOf<Qa18PolishChromeBubbles.Held>()

    @Test
    fun everyIconOnlyStripItemShowsItsNameOnALongPress() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 1_500_000)
        val h = ChromeHarness()
        for (vector in listOf(false, true)) h.section("every tool${if (vector) " (vector)" else ""}") { everyTool(h, vector) }
        h.section("Transform with a lifted picture") { transform(h) }
        h.section("a polygon lasso and a curve lasso with a selection") { lasso(h) }
        h.section("an open text") { text(h) }
        h.section("Text frames, a frame selected") { textFrames(h) }
        h.section("Masks, a brush part") { masks(h) }
        h.section("Frame divider with a frame layer") { frameDivider(h) }
        h.section("selected vector objects") { objects(h) }
        h.section("a popup opened from the strip") { popup(h) }
        h.section("what was held") {
            val problems = held.flatMap { it.problems }
            System.err.println("[polish-chrome] held ${held.size} icon-only controls: ${seen.joinToString(", ")}")
            problems.forEach { System.err.println("[polish-chrome] PROBLEM $it") }
            assertTrue("every icon-only control shows its name and does nothing else:\n" + problems.joinToString("\n"), problems.isEmpty())
            val names = held.map { it.name }.toSet()
            val asked = listOf(
                TransformStripItems.ROTATE_CCW_LABEL, TransformStripItems.ROTATE_CW_LABEL, TransformStripItems.FIT_LABEL,
                TransformStripItems.RESET_LABEL, "Deselect", "Selection menu", "Discard polygon",
            )
            assertTrue("the controls QA named were held: missing ${asked - names}", names.containsAll(asked))
        }
        dog.interrupt()
        h.finish()
    }

    private fun census(s: ChromeScreen, where: String) {
        held += Qa18PolishChromeBubbles.census(s, where, seen)
    }

    /** Closes windows a pick left open (as a finger on Back would). */
    private fun calm(s: ChromeScreen) {
        Smoke.pump(300); SmokeUi.settle()
        repeat(3) { if (SmokeUi.windows().size > 1) Finger.back() }
    }

    private fun everyTool(h: ChromeHarness, vector: Boolean) {
        val s = h.editor(Qa18MenusShots.doc(vector))
        for (id in ToolMenu.tools) {
            s.c.selectTool(id)
            calm(s)
            if (s.c.activeToolId != id) continue
            census(s, "${id.label}${if (vector) " (vector)" else ""}")
        }
        Smoke.assertQuiet(s.c, "every tool")
    }

    private fun lifted(h: ChromeHarness): Pair<ChromeScreen, TransformTool> {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c ->
            check(c.editWholeLayer(c.doc.layers[1], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        }
        Qa18MenusShots.pick(s, ToolId.TRANSFORM)
        val t = s.c.currentTool as TransformTool
        assertTrue("the picture is lifted", Smoke.pumpUntil(10_000) { SmokeUi.settle(); t.transformState != null })
        return s to t
    }

    private fun transform(h: ChromeHarness) {
        val (s, t) = lifted(h)
        for (mode in TransformTool.Mode.entries) {
            t.mode = mode
            calm(s)
            census(s, "Transform (${mode.label})")
        }
        t.mode = TransformTool.Mode.FREE
        calm(s)
        // A tap still acts: the More row's "Rotate 90° clockwise" turns the picture.
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); SmokeUi.settle()
        val before = t.transformState
        Finger.tap(s, TransformStripItems.ROTATE_CW_LABEL)
        Smoke.pump(300); SmokeUi.settle()
        assertNotEquals("a tap on \"${TransformStripItems.ROTATE_CW_LABEL}\" turns the picture", before, t.transformState)
        Smoke.assertQuiet(s.c, "Transform")
    }

    private fun lasso(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c -> c.selectAll() }
        Qa18MenusShots.pick(s, ToolId.LASSO)
        val t = s.c.currentTool as LassoTool
        Finger.tap(s, "Freehand lasso")
        Smoke.pump(300); SmokeUi.settle()
        SmokeUi.tap("Polygon lasso", exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals(LassoKind.POLYGON, t.kind)
        census(s, "Polygon lasso, a selection, the bar shown")
        // Two corners: the bar hides; row 1 offers Close and Discard, the More row Deselect and the menu.
        Fingers18.tap(s, 120f, 90f)
        Fingers18.tap(s, 260f, 110f)
        assertEquals("two corners", 2, t.vertexCount)
        assertNull("the bar hides", s.tagged(ChromeTags.SELECTION_BAR))
        census(s, "Polygon lasso, two corners")
        // Taps still act: Deselect drops the selection, Discard the corners.
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); SmokeUi.settle()
        Finger.tap(s, "Deselect")
        Smoke.pump(300); SmokeUi.settle()
        assertNull("a tap on Deselect drops the selection", s.c.selection)
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); SmokeUi.settle()
        Finger.tap(s, "Discard polygon")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("a tap on Discard drops the corners", 0, t.vertexCount)

        // A curve with a point picked: its Sharp / Delete icons.
        Finger.tap(s, "Polygon lasso")
        Smoke.pump(300); SmokeUi.settle()
        SmokeUi.tap("Curve lasso", exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals(LassoKind.CURVE, t.kind)
        Fingers18.tap(s, 80f, 50f)
        Fingers18.tap(s, 320f, 50f)
        Fingers18.tap(s, 320f, 190f)
        t.curve.select(1)
        Smoke.pump(300); SmokeUi.settle()
        census(s, "Curve lasso, a point picked")
        // The mode dropdown still drops on a tap.
        Finger.tap(s, MagneticLabels18.SELECTION_MODE)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("a tap on the mode drops its menu", 2, SmokeUi.windows().size)
        Finger.back()
        Smoke.assertQuiet(s.c, "Lasso")
    }

    private fun text(h: ChromeHarness) {
        for (vector in listOf(false, true)) {
            val s = h.editor(Qa18MenusShots.doc(vector))
            Qa18MenusShots.pick(s, ToolId.TEXT)
            val t = s.c.currentTool as TextTool
            t.startTextAt(200f, 150f)
            t.setText("Strip")
            t.confirmEditor()
            Smoke.pump(300); SmokeUi.settle()
            assertNotNull("an open text", t.item)
            census(s, "Text, a text open${if (vector) " (vector)" else ""}")
            Smoke.assertQuiet(s.c, "Text")
        }
    }

    private fun textFrames(h: ChromeHarness) {
        // As the frames tests draw one: a finger drags the frame out, the story is typed, the frame tapped.
        val s = h.editor(Smoke.document(600, 800, layers = 1, whiteBottom = true)) { it.color = FrameFixtures.BLACK; it.snapping.enabled = false }
        val f = FramesUi(s)
        f.pick()
        f.drawFrame(40f, 40f, 300f, 240f)
        f.typeStory(FrameFixtures.STORY, 14)
        f.select(s.c.activeLayer)
        calm(s)
        assertNotNull("a frame selected", f.tool.selected)
        census(s, "Text frames, a frame selected")
        Smoke.assertQuiet(s.c, "Text frames")
    }

    private fun masks(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        Qa18MenusShots.pick(s, ToolId.MASK)
        val c = s.c
        val t = c.currentTool as MaskTool
        t.arm(MaskTool.Kind.LINEAR)
        FrameFixtures.drag(c, 120f, 150f, 280f, 150f)
        calm(s)
        assertTrue("an adjustment layer", c.activeLayer.isAdjustmentLayer)
        census(s, "Masks, a linear part")
        t.arm(MaskTool.Kind.BRUSH)
        FrameFixtures.drag(c, 150f, 100f, 250f, 200f)
        assertTrue("a brush part picked", Smoke.pumpUntil(10_000) { SmokeUi.settle(); t.selected is BrushMask })
        calm(s)
        census(s, "Masks, a brush part")
        Smoke.assertQuiet(s.c, "Masks")
    }

    private fun frameDivider(h: ChromeHarness) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        Qa18MenusShots.pick(s, ToolId.FRAME_DIVIDER)
        assertTrue("a frame layer", (s.c.currentTool as FrameDividerTool).createFrameLayer())
        calm(s)
        census(s, "Frame divider, a frame layer")
        Smoke.assertQuiet(s.c, "Frame divider")
    }

    private fun objects(h: ChromeHarness) {
        val kit = ObjectTestKit(400, 300)
        val doc = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 1
        }
        val s = h.editor(doc) { c ->
            val layer = c.doc.layers[1]
            c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 120f, 110f), kit.stroke(60f, 200f, 200f, 230f)), "Add")
        }
        for (id in listOf(ToolId.OBJECT_SELECT, ToolId.SHAPE, ToolId.BRUSH)) {
            s.c.selectTool(id)
            calm(s)
            s.c.vectors.setSelection(s.c.doc.layers[1], setOf(1L))
            Smoke.pumpUntil(10_000) { !s.c.vectors.isRendering }
            calm(s)
            if (s.tagged(ChromeTags.SELECTION_BAR) == null) continue
            census(s, "${id.label}, an object selected")
        }
        Smoke.assertQuiet(s.c, "objects")
    }

    /** Free deform's "Mesh size" popup: its − / + are icons in a popup, not on a strip: no bubble. */
    private fun popup(h: ChromeHarness) {
        val (s, t) = lifted(h)
        t.mode = TransformTool.Mode.MESH
        calm(s)
        Finger.tap(s, TransformLabels18.MESH_SIZE)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the mesh size popup", 2, SmokeUi.windows().size)
        val plus = RobolectricUi.elements().lastOrNull { e ->
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(MeshStepperLabels.MORE_COLUMNS) == true
        } ?: throw AssertionError("no \"${MeshStepperLabels.MORE_COLUMNS}\" in the popup")
        assertTrue("it is in the popup's window", plus.window !== s.activity.window.decorView)
        val b = plus.bounds
        var bubble: String? = "?"
        Qa18PolishChromeBubbles.longPressIn(plus.window, RectF(b.left, b.top, b.right, b.bottom)) { bubble = Fingers18.bubble() }
        assertNull("no bubble in a popup", bubble)
        assertNull("no bubble in a popup after the finger lifts", Fingers18.bubble())
        assertNull("no bubble tag anywhere", s.tagged(V18Tags.LONG_PRESS_BUBBLE))
        System.err.println("[polish-chrome] a long press on \"${MeshStepperLabels.MORE_COLUMNS}\" in the popup: ${t.meshColumns} columns")
        Finger.back()
        Smoke.assertQuiet(s.c, "the mesh popup")
    }
}
