package com.brushwork.paint.ui.layers

import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.editor.EditorPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every control of the ibisPaint layer window (design §3.7.7) does its action, driven by its
 * label as a user (or TalkBack) would, and records exactly one undo step with the expected name:
 * the header's ✕; the left column's add (and its long press), the canvas flips, duplicate,
 * import picture and new special layer; the strip's clear, layer mask, transform, layer flips,
 * merge down, delete, filters for this layer and ⋮; the Selection Layer row; the blend row; the
 * opacity row (−/+, typing, a drag — live through `liveAdjust` for an adjustment layer — and the
 * Percent increments); the transparency squares (a view setting: no step); the eye, a long press
 * on a row and the ≡ handle; and a linked text frame's badge and "Edit text".
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.windowactionssandbox"])
class LayerWindowActionsRobolectricTest {

    private lateinit var c: EditorController
    private val red = 0xFFFF0000.toInt()

    private fun steps() = c.undoManager.undoCount

    /** [block] adds exactly one undo step (named [label] when given). */
    private fun oneStep(what: String, label: String?, block: () -> Unit) {
        val before = steps()
        block()
        SmokeUi.settle()
        assertEquals("$what: one undo step", before + 1, steps())
        if (label != null) assertEquals("$what: step name", label, c.undoManager.undoLabel)
    }

    private fun undo() {
        c.undoManager.undo(c)
        SmokeUi.settle()
    }

    private fun click(label: String) = SmokeUi.click(label, exact = true)

    @Test
    fun everyControlDoesItsActionInOneStep() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        c = Smoke.controller(activity, Smoke.document(64, 48, layers = 2, whiteBottom = true))
        val (bottom, top) = c.doc.layers
        val density = activity.resources.displayMetrics.density
        var dismissed = 0
        var imports = 0
        val deleted = mutableListOf<Layer>()
        val panels = mutableListOf<EditorPanel>()
        var open by mutableStateOf(true)
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    if (open) {
                        LayersPanel(
                            c,
                            onDismiss = { dismissed++ },
                            onImportPicture = { imports++ },
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 92.dp).size(382.dp, 520.dp),
                            onLayerDeleted = { deleted += it },
                            onOpenPanel = { panels += it },
                        )
                    }
                }
            }
        }
        SmokeUi.settle()

        // ------------------------------------------------------------ header
        click(LayerLabels.CLOSE)
        assertEquals("✕ closes", 1, dismissed)
        assertTrue("\"n / max\" count", SmokeUi.has("2 / ${c.maxLayers}", exact = true))

        // ------------------------------------------------------------ left column
        oneStep("Add layer", "Add layer") { click(LayerLabels.ADD) }
        assertEquals(3, c.doc.layers.size)
        assertSame("the new layer is active, above the old one", c.doc.layers[2], c.activeLayer)
        undo()
        assertEquals(2, c.doc.layers.size)

        // A long press on + : the special layers.
        val add = SmokeUi.find(LayerLabels.ADD, exact = true)!!
        assertEquals(LayerLabels.ADD_LONG, add.node.config.getOrNull(SemanticsActions.OnLongClick)?.label)
        add.node.config.getOrNull(SemanticsActions.OnLongClick)!!.action!!.invoke()
        SmokeUi.settle()
        oneStep("New vector layer", null) { click(LayerLabels.NEW_VECTOR) }
        assertTrue(c.activeLayer.isVectorLayer)
        undo()

        click(LayerLabels.SPECIAL)
        assertTrue(SmokeUi.has(LayerLabels.NEW_VECTOR, exact = true))
        oneStep("New adjustment layer", null) { click(LayerLabels.NEW_ADJUSTMENT) }
        assertTrue(c.activeLayer.isAdjustmentLayer)
        c.selectTool(ToolId.BRUSH)
        undo()
        assertEquals(2, c.doc.layers.size)
        c.selectLayer(top)
        SmokeUi.settle()

        oneStep("Duplicate layer", "Duplicate layer") { click(LayerLabels.DUPLICATE) }
        assertEquals(3, c.doc.layers.size)
        undo()

        top.bitmap.setPixel(1, 1, red)
        top.markChanged()
        oneStep("Flip canvas horizontally", "Flip canvas horizontally") {
            val before = steps()
            click(LayerLabels.FLIP_CANVAS_H)
            assertTrue("the flip finishes", Smoke.pumpUntil { c.busyMessage == null && steps() > before })
        }
        assertEquals("the whole canvas mirrored", red, c.doc.layers[1].bitmap.getPixel(62, 1))
        undo()
        oneStep("Flip canvas vertically", "Flip canvas vertically") {
            val before = steps()
            click(LayerLabels.FLIP_CANVAS_V)
            assertTrue("the flip finishes", Smoke.pumpUntil { c.busyMessage == null && steps() > before })
        }
        assertEquals(red, c.doc.layers[1].bitmap.getPixel(1, 46))
        undo()
        assertEquals(red, c.doc.layers[1].bitmap.getPixel(1, 1))

        click(LayerLabels.IMPORT)
        assertEquals("Import picture asks the host", 1, imports)
        assertEquals("and closes the window first", 2, dismissed)

        // ------------------------------------------------------------ the right strip
        c.selectLayer(c.doc.layers[1])
        val layer = c.activeLayer
        SmokeUi.settle()
        oneStep("Clear layer", "Clear") { click(LayerLabels.CLEAR) }
        assertEquals(0, layer.bitmap.getPixel(1, 1))
        undo()
        assertEquals(red, layer.bitmap.getPixel(1, 1))

        click(LayerLabels.MASK)
        assertTrue("Layer mask opens the mask page", SmokeUi.has("Add gradient mask…", exact = true))
        oneStep("Add mask", "Add mask") { click("Add mask") }
        assertNotNull(layer.mask)
        assertTrue("the strip now clears the mask", SmokeUi.has(LayerLabels.CLEAR_MASK, exact = true))
        undo()
        assertNull(layer.mask)

        val beforeTransform = steps()
        click(LayerLabels.TRANSFORM)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        assertSame(layer, c.activeLayer)
        assertEquals("Transform layer closes the window", 3, dismissed)
        c.selectTool(ToolId.BRUSH)
        SmokeUi.settle()
        assertEquals("nothing moved, nothing recorded", beforeTransform, steps())
        assertEquals(2, c.doc.layers.size)

        oneStep("Flip layer horizontally", "Flip layer horizontally") { click(LayerLabels.FLIP_H) }
        assertEquals(red, layer.bitmap.getPixel(62, 1))
        assertEquals("the layer below is not flipped", -1, bottom.bitmap.getPixel(62, 1))
        undo()
        oneStep("Flip layer vertically", "Flip layer vertically") { click(LayerLabels.FLIP_V) }
        assertEquals(red, layer.bitmap.getPixel(1, 46))
        undo()

        oneStep("Merge down", "Merge down") { click(LayerLabels.MERGE) }
        assertEquals(1, c.doc.layers.size)
        assertEquals(red, c.doc.layers[0].bitmap.getPixel(1, 1))
        undo()
        assertEquals(2, c.doc.layers.size)

        c.selectLayer(layer)
        SmokeUi.settle()
        oneStep("Delete layer", "Delete layer") { click(LayerLabels.DELETE) }
        assertEquals(listOf(layer), deleted)
        assertEquals(1, c.doc.layers.size)
        assertFalse("the last layer can't be deleted", SmokeUi.isEnabled(LayerLabels.DELETE))
        undo()
        assertEquals(2, c.doc.layers.size)
        c.selectLayer(layer)
        SmokeUi.settle()

        click(LayerLabels.FILTERS)
        assertEquals(EditorPanel.FILTERS, panels.last())

        click(LayerLabels.MORE)
        assertTrue("⋮ shows the v1.5 menu", SmokeUi.has("Move layer down", exact = true))
        click("Rename…")
        SmokeUi.typeAndDone("Name", "Sky")
        assertEquals("Sky", layer.name)
        assertEquals("Rename layer", c.undoManager.undoLabel)
        undo()

        // ------------------------------------------------------------ the Selection Layer row
        click(LayerLabels.SELECTION_ROW)
        assertEquals(EditorPanel.SELECTION, panels.last())

        // ------------------------------------------------------------ the blend row
        oneStep("Clipping", "Clipping") { click(LayerLabels.CLIPPING) }
        assertTrue(layer.clipping)
        undo()
        oneStep("Alpha lock", "Lock alpha") { click(LayerLabels.ALPHA_LOCK) }
        assertTrue(layer.alphaLocked)
        undo()
        oneStep("Lock layer", "Lock layer") { click(LayerLabels.LOCK) }
        assertTrue(layer.locked)
        undo()
        click(LayerLabels.BLEND)
        oneStep("Blend mode", "Blend mode") { click(LayerBlendMode.MULTIPLY.label) }
        assertEquals(LayerBlendMode.MULTIPLY, layer.blendMode)
        assertTrue("the dropdown shows it", SmokeUi.has(LayerBlendMode.MULTIPLY.label, exact = true))
        undo()

        // ------------------------------------------------------------ the opacity row
        oneStep("Less layer opacity", "Opacity") { click(LayerLabels.LESS_OPACITY) }
        assertEquals(0.99f, layer.opacity, 1e-4f)
        oneStep("More layer opacity", "Opacity") { click(LayerLabels.MORE_OPACITY) }
        assertEquals(1f, layer.opacity, 1e-4f)

        // Typed values are exact; with increments on, −/+ go to the next multiple of 5 %.
        c.increments.update { it.copy(enabled = true) }
        click(LayerLabels.TYPE_OPACITY)
        oneStep("Type layer opacity", "Opacity") { SmokeUi.typeAndDone(LayerLabels.OPACITY, "37") }
        assertEquals("typed values are never stepped", 0.37f, layer.opacity, 1e-4f)
        oneStep("− with increments", "Opacity") { click(LayerLabels.LESS_OPACITY) }
        assertEquals(0.35f, layer.opacity, 1e-4f)
        oneStep("+ with increments", "Opacity") { click(LayerLabels.MORE_OPACITY) }
        assertEquals(0.40f, layer.opacity, 1e-4f)

        // A drag: one step, on multiples of the step while increments are on.
        oneStep("drag with increments", "Opacity") { dragSlider(density, 0.2f, 0.73f) }
        assertEquals(0.75f, layer.opacity, 0.026f)
        assertEquals("a multiple of 5 %", 0f, (layer.opacity * 100f / 5f).let { it - Math.round(it) }, 1e-3f)
        c.increments.update { it.copy(enabled = false) }
        oneStep("drag", "Opacity") { dragSlider(density, 0.9f, 0.43f) }
        assertEquals(0.43f, layer.opacity, 0.03f)

        // The slider's accessibility action sets the value in one step.
        val slider = SmokeUi.find(LayerLabels.OPACITY, exact = true)!!
        oneStep("setProgress", "Opacity") { slider.node.config.getOrNull(SemanticsActions.SetProgress)!!.action!!.invoke(0.6f) }
        assertEquals(0.6f, layer.opacity, 1e-4f)
        assertEquals("60%", SmokeUi.find(LayerLabels.OPACITY, exact = true)!!.stateDescription)
        assertTrue("the value shows it", SmokeUi.has("60%", exact = true))

        // An adjustment layer: the drag goes live to the layer (liveAdjust), without refreshing the
        // layer list on every move, and still records one step when the finger lifts.
        val adjustment = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(drawingColor = c.color), null)!!
        SmokeUi.settle()
        assertSame(adjustment, c.activeLayer)
        assertTrue("its row names the effect", SmokeUi.has(AdjustmentEffects.displayName(adjustment.adjustment!!), exact = true))
        assertTrue("the strip applies it to the layer below", SmokeUi.has(LayerLabels.APPLY_BELOW, exact = true))
        val before = steps()
        val touch = SliderTouch(density)
        touch.down(0.95f)
        touch.moveTo(0.8f)
        val version = c.layersVersion
        touch.moveTo(0.6f)
        touch.moveTo(0.5f)
        assertEquals("no step while dragging", before, steps())
        assertEquals("the layer follows the finger", 0.5f, adjustment.opacity, 0.03f)
        assertEquals("the layer list is not refreshed per move", version, c.layersVersion)
        touch.up(0.5f)
        assertEquals("one step on release", before + 1, steps())
        assertEquals("Opacity", c.undoManager.undoLabel)
        undo()
        assertEquals(1f, adjustment.opacity, 1e-4f)
        undo() // the adjustment layer
        c.selectLayer(layer)
        SmokeUi.settle()

        // ------------------------------------------------------------ the transparency squares
        var invalidations = 0
        c.onInvalidate = { invalidations++ }
        val stepsBefore = steps()
        for (t in listOf(TransparencyDisplay.DARK_CHECKER, TransparencyDisplay.WHITE, TransparencyDisplay.NONE, TransparencyDisplay.LIGHT_CHECKER)) {
            val n = invalidations
            click(LayerLabels.transparency(t))
            assertEquals(t, c.settings.transparencyDisplay)
            assertTrue("${t.label}: the canvas redraws", invalidations > n)
            val square = SmokeUi.find(LayerLabels.transparency(t), exact = true)!!
            assertEquals("${t.label} is the chosen square", true, square.node.config.getOrNull(SemanticsProperties.Selected))
        }
        assertEquals("a view setting, not an edit", stepsBefore, steps())
        c.onInvalidate = null

        // ------------------------------------------------------------ rows
        val n = c.doc.indexOf(layer) + 1
        oneStep("Hide layer", "Visibility") { click(LayerLabels.hide(n)) }
        assertFalse(layer.visible)
        assertTrue(SmokeUi.has(LayerLabels.show(n), exact = true))
        undo()

        // A long press on a row (its thumbnail) selects it and opens its ⋮ menu.
        val activityWindow = SmokeUi.windows().first()
        val thumb = LayerWindowProbe(density).tagged(LayerWindowTags.thumb(bottom.id))
        val rowTouch = Smoke.Touch(activityWindow)
        rowTouch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, thumb.center.x, thumb.center.y))
        rowTouch.holdRealTime(900)
        rowTouch.send(MotionEvent.ACTION_UP, Smoke.P(0, thumb.center.x, thumb.center.y))
        SmokeUi.settle()
        assertSame("the long-pressed layer is active", bottom, c.activeLayer)
        assertTrue("its ⋮ menu is open", SmokeUi.has("Rename…", exact = true))
        assertEquals("a long press in place reorders nothing", listOf(bottom, layer), c.doc.layers)
        LayerWindowProbe.pressBackOnPopups(activityWindow)
        assertFalse(SmokeUi.has("Rename…", exact = true))

        // The ≡ handle drags at once: the top layer goes below the other.
        c.selectLayer(layer)
        SmokeUi.settle()
        val handle = SmokeUi.find(LayerLabels.reorder(2), exact = true)!!
        val hb = handle.bounds
        val drag = Smoke.Touch(handle.window)
        drag.send(MotionEvent.ACTION_DOWN, Smoke.P(0, hb.center.x, hb.center.y))
        for (i in 1..12) {
            drag.idle(16)
            drag.send(MotionEvent.ACTION_MOVE, Smoke.P(0, hb.center.x, hb.center.y + i * 8f * density))
        }
        drag.idle(16)
        drag.send(MotionEvent.ACTION_UP, Smoke.P(0, hb.center.x, hb.center.y + 96f * density))
        SmokeUi.settle()
        assertEquals("the dragged layer is now at the bottom", listOf(layer, bottom), c.doc.layers)
        undo()
        assertEquals(listOf(bottom, layer), c.doc.layers)

        // ------------------------------------------------------------ a linked text frame
        val frame = c.addLayer()!!
        frame.textData = TextCodec.encode(
            TextItem(
                text = "Hello",
                spec = TextSpec(box = TextBoxSpec(width = 40f, minHeight = 20f)),
                cx = 32f, cy = 24f,
                thread = TextThreadSpec(storyId = 7, index = 0, story = "Hello world", start = 0, end = 5, overset = true, rev = 1),
            ),
        )
        assertTrue("a threaded item", TextCodec.decode(frame.textData)!!.threaded)
        c.notifyLayersChanged()
        SmokeUi.settle()
        assertTrue("the frame badge (⛓ 1/1 with a red +)", SmokeUi.has("Text frame 1 of 1, more text than fits", exact = true))
        assertFalse("the frame badge replaces the T badge", SmokeUi.has(LayerLabels.TEXT_BADGE, exact = true))
        click(LayerLabels.MORE)
        click("Edit text")
        // The Text frames tool opens a frame (textThreads.openForEditing, area D); until it does,
        // the Text tool edits it.
        assertTrue("an editing tool: ${c.activeToolId}", c.activeToolId == ToolId.TEXT_FRAMES || c.activeToolId == ToolId.TEXT)
        if (c.activeToolId == ToolId.TEXT) {
            val tool = c.tools.getValue(ToolId.TEXT) as TextTool
            tool.cancelEditor()
            tool.discard()
        }
        c.selectTool(ToolId.BRUSH)
        SmokeUi.settle()

        open = false
        SmokeUi.settle()
        Smoke.assertQuiet(c, "layer window actions")
    }

    /** Drags the layer opacity slider from fraction [from] to [to] with real touch events. */
    private fun dragSlider(density: Float, from: Float, to: Float) {
        val t = SliderTouch(density)
        t.down(from)
        val steps = 10
        for (i in 1..steps) t.moveTo(from + (to - from) * i / steps)
        t.up(to)
    }

    /** Touches on the opacity slider at track fractions (the thumb's centre runs r .. w − r). */
    private inner class SliderTouch(density: Float) {
        private val e = SmokeUi.find(LayerLabels.OPACITY, exact = true)!!
        private val b = e.bounds
        private val r = 11f * density
        private val touch = Smoke.Touch(e.window)
        private val y = b.center.y
        private fun x(f: Float) = b.left + r + f * (b.width - 2f * r)

        fun down(f: Float) = touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, x(f), y))
        fun moveTo(f: Float) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, x(f), y))
        }
        fun up(f: Float) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_UP, Smoke.P(0, x(f), y))
            SmokeUi.settle()
        }
    }
}
