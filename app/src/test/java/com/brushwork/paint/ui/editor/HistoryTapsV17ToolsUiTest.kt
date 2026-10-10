package com.brushwork.paint.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryTapFingers.Companion.SEED
import com.brushwork.paint.ui.editor.HistoryTapFingers.Companion.seed
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.placement.MeshStepperLabels
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * v1.7 item 10 (design §3.10) over the v1.7 tools' own controls, on the user's 392 dp phone with
 * real multi-touch events: the first finger on a control that would act (a Symmetry ruler chip,
 * Pathfinder's "Unite shapes", a saved selection's row in the layer window, the Free deform
 * "Smooth mesh" chip and mesh stepper), the second finger beside it. Each time: exactly one undo
 * (the known last step "Seed", named in the feedback), the control under the fingers does
 * nothing, and three fingers then redo that step.
 *
 * The text editor's Kerning row: "Increase Kerning" kerns on touch-down. While the editor is open
 * the history waits for it (v1.3's rule, as for the hotbar's Undo: "Finish the text first: OK or
 * Cancel"), so two fingers there undo nothing and three redo nothing, and the kern the first
 * finger made is put back; after OK the same text, untouched, lets one tap undo "Seed".
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox; one
 * fresh editor per section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.historytapsv17toolssandbox"])
class HistoryTapsV17ToolsUiTest {

    @Test
    fun historyTapsOverTheV17ToolControls() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("the Symmetry tool's options") { symmetry(h) }
        h.section("the Pathfinder strip") { pathfinder(h) }
        h.section("the saved selections in the layer window") { savedSelections(h) }
        h.section("the Free deform chips and mesh steppers") { freeDeform(h) }
        h.section("the text editor's Kerning row") { kerning(h) }
        dog.interrupt()
        h.finish()
    }

    /** Two fingers ([first] on the control, [second] beside it): one undo of "Seed". */
    private fun HistoryTapFingers.undoesTheSeed(c: EditorController, first: Pair<Float, Float>, second: Pair<Float, Float>) {
        val steps = this.steps
        assertEquals(SEED, c.undoManager.undoLabel)
        twoFingers(first, second)
        val tool = c.currentTool
        assertEquals(
            "exactly one undo (${c.activeToolId}: pending ${tool.hasPendingWork}, user's ${tool.hasUserChanges}; shown ${SmokeUi.shown().filter { it.startsWith("Undo") || it.startsWith("Redo") }})",
            steps - 1, this.steps,
        )
        assertTrue("the feedback: ${SmokeUi.shown().take(60)}", saw("Undo: $SEED"))
        assertTrue(c.canRedo)
    }

    /** Three fingers along the bottom bar: one redo of "Seed", and no button there fires. */
    private fun HistoryTapFingers.redoesTheSeed(s: ChromeScreen) {
        val steps = this.steps
        val tool = s.c.activeToolId
        val windows = SmokeUi.windows().size
        val bar = region(ChromeTags.BOTTOM_BAR)
        threeFingers(bar.at(0.2f), bar.at(0.5f), bar.at(0.8f))
        assertEquals("one redo", steps + 1, this.steps)
        assertEquals(SEED, s.c.undoManager.undoLabel)
        assertTrue(saw("Redo: $SEED"))
        assertEquals("no button fired", tool, s.c.activeToolId)
        assertEquals("nothing opened", windows, SmokeUi.windows().size)
        assertNull("no tool menu", s.tagged(ChromeTags.TOOL_MENU))
    }

    private fun HistoryTapFingers.besideIt() = region(ChromeTags.TOP_ROW).at(0.5f, 0.9f)

    private fun pixelsOf(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun symmetry(h: ChromeHarness) {
        val s = h.editor()
        val c = s.c
        val f = HistoryTapFingers(s)
        seed(c)
        c.selectTool(ToolId.SYMMETRY)
        settle()
        assertEquals("picking the tool turns the mirror on", SymmetryType.MIRROR, c.symmetry.type)
        f.reach(SymmetryType.KALEIDOSCOPE.label, 32f)
        with(f) {
            undoesTheSeed(c, control(SymmetryType.KALEIDOSCOPE.label).at(0.5f), besideIt())
            assertEquals("the chip did nothing", SymmetryType.MIRROR, c.symmetry.type)
            assertEquals(ToolId.SYMMETRY, c.activeToolId)
            redoesTheSeed(s)
        }
        assertEquals(SymmetryType.MIRROR, c.symmetry.type)
        Smoke.assertQuiet(c, "symmetry")
    }

    private fun shapeLayer(c: EditorController, l: Float, t: Float, r: Float, b: Float, color: Int) {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = (l + r) / 2f, cy = (t + b) / 2f, w = r - l, h = b - t, style = ShapeStyle.FILL, fillColor = color)
        c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { canvas ->
            canvas.drawRect(l, t, r, b, Paint().apply { this.color = color })
        }!!
    }

    private fun pathfinder(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        shapeLayer(c, 60f, 60f, 180f, 180f, 0xFFDD2211.toInt())
        shapeLayer(c, 120f, 60f, 240f, 180f, 0xFF2244CC.toInt())
        seed(c)
        c.selectTool(ToolId.PATHFINDER)
        val tool = c.currentTool as PathfinderTool
        tool.computeDispatcher = Dispatchers.Unconfined
        settle()
        click(PathfinderLabels.SELECT_ALL, exact = true)
        assertEquals(2, tool.count)
        val layers = c.doc.layers.map { it.name }
        val unite = PathfinderOp.UNITE.description
        f.reach(unite)
        assertTrue("\"$unite\" acts", SmokeUi.isEnabled(unite))
        with(f) {
            undoesTheSeed(c, control(unite).at(0.5f), besideIt())
            assertEquals("Unite did nothing", layers, c.doc.layers.map { it.name })
            assertFalse(c.doc.layers.any { it.name == PathfinderLabels.resultLayer(1) })
            redoesTheSeed(s)
        }
        assertEquals(layers, c.doc.layers.map { it.name })
        Smoke.assertQuiet(c, "pathfinder")
    }

    private fun savedSelections(h: ChromeHarness) {
        val doc = Smoke.document(400, 300, layers = 2, whiteBottom = true)
        val rect = Selection.fromPath(Path().apply { addRect(40f, 40f, 160f, 120f, Path.Direction.CW) }, doc.width, doc.height, antiAlias = false)
        doc.savedSelections = listOf(SavedSelection.of(doc.newSelectionId(), "Sky", rect, 1L)!!)
        val saved = doc.savedSelections.single()
        val s = h.editor(doc)
        val c = s.c
        val f = HistoryTapFingers(s)
        seed(c)
        Finger.tap(s, "Open layers", exact = false)
        assertNotNull("the layer window", Finger.layerWindow(s))
        val active = c.doc.activeLayerIndex
        val windows = SmokeUi.windows().size
        with(f) {
            undoesTheSeed(c, region(V17Tags.savedSelectionRow(saved.id)).at(0.5f), besideIt())
            assertFalse("the row's menu did not open", SmokeUi.has(SavedSelectionLabels.LOAD, exact = true))
            assertEquals(windows, SmokeUi.windows().size)
            assertNull("nothing loaded", c.selection)
            assertNotNull("the layer window stays", Finger.layerWindow(s))
            // Three fingers over the layer window's rows: one redo, no row picked.
            val lw = region(ChromeTags.LAYER_WINDOW)
            val steps = this.steps
            threeFingers(lw.at(0.2f, 0.5f), lw.at(0.5f, 0.5f), lw.at(0.8f, 0.5f))
            assertEquals("one redo", steps + 1, this.steps)
            assertTrue(saw("Redo: $SEED"))
            assertEquals("no row picked", active, c.doc.activeLayerIndex)
            assertEquals(windows, SmokeUi.windows().size)
        }
        assertEquals(listOf(saved.id), c.doc.savedSelections.map { it.id })
        Smoke.assertQuiet(c, "saved selections")
    }

    private fun freeDeform(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        val layer = c.doc.layers[1]
        Canvas(layer.bitmap).drawRect(120f, 90f, 260f, 200f, Paint().apply { color = 0xFF2266CC.toInt() })
        layer.markChanged()
        seed(c)
        val pixels = pixelsOf(layer.bitmap)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        /** The layer lifted again (an undo drops an untouched lift), shown as a Free deform mesh. */
        fun freeDeform() {
            if (c.activeToolId == ToolId.TRANSFORM) c.selectTool(ToolId.BRUSH)
            c.selectLayer(layer)
            c.selectTool(ToolId.TRANSFORM)
            assertTrue("lifted", Smoke.pumpUntil { tool.transformState != null })
            tool.mode = TransformTool.Mode.MESH
            settle()
            assertTrue("Free deform", tool.isMeshShown)
            assertFalse("an untouched mesh", tool.hasUserChanges)
        }
        freeDeform()
        val smooth = tool.smoothMesh
        val columns = tool.meshColumns
        with(f) {
            // The first finger on the "Smooth mesh" chip, then on the "More mesh columns" stepper.
            for (label in listOf(TransformLabels17.SMOOTH, MeshStepperLabels.MORE_COLUMNS)) {
                if (tool.transformState == null) freeDeform()
                reach(label, 32f)
                undoesTheSeed(c, control(label).at(0.5f), besideIt())
                assertEquals("$label: the chip did nothing", smooth, tool.smoothMesh)
                assertEquals("$label: the stepper did nothing", columns, tool.meshColumns)
                assertFalse("$label: no mesh change", tool.isMeshChanged)
                // The undo let the untouched lift go (EditorController.undo), the layer as it was.
                assertFalse("$label: no pending deform", tool.hasPendingWork)
                assertTrue("$label: the layer as it was", pixels.contentEquals(pixelsOf(layer.bitmap)))
                redoesTheSeed(s)
            }
        }
        assertEquals(smooth, tool.smoothMesh)
        assertEquals(columns, tool.meshColumns)
        Smoke.assertQuiet(c, "free deform")
    }

    /** The text field's cursor or selection, [start] to [end] (what the Kerning row edits). */
    private fun select(start: Int, end: Int) {
        SmokeUi.field("Text").focus()
        settle(2)
        val set = SmokeUi.field("Text").node.config.getOrNull(SemanticsActions.SetSelection)?.action
            ?: throw AssertionError("the text field has no selection action")
        set(start, end, false)
        settle()
    }

    private fun kerning(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(200f, 150f)
        tool.setText("AVATAR")
        tool.commit()
        settle()
        val textLayer = c.activeLayer
        val stored = textLayer.textData
        assertNotNull("a text layer", TextCodec.decode(stored))
        seed(c)
        // The text layer opened again (nothing changed yet), the cursor between A and V.
        assertTrue(tool.editLayer(textLayer, openEditor = true))
        settle(20, 50)
        select(1, 1)
        val increase = "Increase ${KerningLabels.KERNING}"
        f.reach(increase)
        assertTrue("the row acts on a gap", SmokeUi.isEnabled(increase))
        assertFalse("nothing changed yet", tool.hasUserChanges)
        val kerns = tool.item!!.kerns
        val panel = s.dp(requireNotNull(SmokeUi.sheetPanel()) { "no text editor; shown: ${SmokeUi.shown().take(60)}" }.bounds)
        with(f) {
            // Both fingers inside the hosted editor: "Increase Kerning" kerns on touch-down. The
            // open editor holds the history as the hotbar's Undo finds it (v1.3: "Finish the text
            // first: OK or Cancel", so an undo never throws typed text away): the tap undoes
            // nothing, and the kern its first finger made is put back.
            val title = textIn("Edit text", panel).at(0.5f)
            val steps = this.steps
            holdThenSecondFinger(control(increase).at(0.5f), title) {
                assertNotEquals("the first finger kerned on touch-down", kerns, tool.item!!.kerns)
                assertTrue(tool.hasUserChanges)
            }
            assertTrue("it says why: ${SmokeUi.shown().take(60)}", saw(HistoryLabels.BLOCKED_BY_TEXT_EDITOR))
            assertEquals("nothing undone", steps, this.steps)
            assertEquals("the kern put back", kerns, tool.item!!.kerns)
            assertFalse(tool.hasUserChanges)
            assertTrue("the editor stays open", tool.editorOpen)
            // Three fingers on the row's − and +: no redo either, and the kern + made is put back.
            threeFingers(control(increase).at(0.5f), control("Decrease ${KerningLabels.KERNING}").at(0.5f), title)
            assertEquals(steps, this.steps)
            assertEquals("nothing kerned", kerns, tool.item!!.kerns)
            assertFalse(tool.hasUserChanges)
            assertTrue(tool.editorOpen)

            // OK: the untouched text stays pending, the history is free; one tap undoes "Seed".
            click("OK", exact = true)
            assertFalse(tool.editorOpen)
            undoesTheSeed(c, region(ChromeTags.OPTIONS_STRIP).at(0.5f), besideIt())
            assertNull("the untouched text is let go", tool.item)
            redoesTheSeed(s)
        }
        assertEquals("the layer as it was", stored, textLayer.textData)
        Smoke.assertQuiet(c, "kerning")
    }
}
