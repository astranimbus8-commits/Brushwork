package com.brushwork.paint.qa16

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerWindowProbe
import com.brushwork.paint.ui.layers.LayerWindowProbe.Companion.isControl
import com.brushwork.paint.ui.layers.LayerWindowProbe.Companion.label
import com.brushwork.paint.ui.layers.LayerWindowTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * The layer window in the real editor (`EditorScreen`, opened from the bottom bar), driven as a
 * finger does: every control is TAPPED where it shows (so it must be on screen, inside the
 * window, and the topmost thing there), every edit is one undo step with its name (I2) and is
 * taken back by the top row's Undo; long presses and drags are real touch sequences. The
 * document: "Layer 1" (white), "Layer 2" and "Layer 3" (painted; the active one).
 */
internal class LayerWindowQaFlow(private val s: ChromeScreen, private val tag: String) {
    private val c = s.c
    private val live = LiveCanvas(s)
    private val probe = LayerWindowProbe(s.density)
    private val red = 0xFFFF0000.toInt()
    private val w get() = c.doc.width
    private val h get() = c.doc.height
    val log = mutableListOf<String>()

    fun steps() = c.undoManager.undoCount

    /** The layer window (window px of the activity), or null when it is closed. */
    fun window(): Rect? = s.placed().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == ChromeTags.LAYER_WINDOW }?.bounds

    private fun screen(): Rect = s.root

    /**
     * Taps the control labelled [label] where it shows: it must be placed on screen (inside the
     * layer window when it is in the activity's window and the window is open), and the finger
     * at its centre must land on it, not on another control inside or over it.
     */
    fun press(label: String, exact: Boolean = true, inWindow: Boolean = true) {
        settleRows()
        val e = SmokeUi.find(label, exact) ?: throw AssertionError("$tag: no \"$label\" on screen: ${SmokeUi.shown().take(120)}")
        val b = e.bounds
        assertTrue("$tag: \"$label\" has a size ($b)", b.width > 0f && b.height > 0f)
        assertTrue("$tag: \"$label\" is on screen ($b in ${screen()})", screen().contains(b.center))
        val lw = window()
        if (inWindow && lw != null && e.window === s.activity.window.decorView) {
            assertTrue("$tag: \"$label\" is inside the layer window ($b in $lw)", lw.contains(b.center))
        }
        assertLandsOn("\"$label\"", e, b.center)
        e.tap()
        settle()
    }

    /** The control a finger at [p] hits in [e]'s window: the last placed control there in tree order (the deepest, topmost one). */
    private fun hitAt(e: RobolectricUi.Element, p: Offset): SemanticsNode? =
        s.placed().lastOrNull { it.window === e.window && it.node.isControl() && it.bounds.contains(p) }?.node

    /** A finger at [p] lands on [e]'s control (itself or its nearest control ancestor). */
    private fun assertLandsOn(what: String, e: RobolectricUi.Element, p: Offset) {
        var k: SemanticsNode? = e.node
        while (k != null && !k.isControl()) k = k.parent
        val target = k ?: throw AssertionError("$tag: $what is not a control")
        val hit = hitAt(e, p)
        assertTrue("$tag: a tap at $what's centre lands on \"${hit?.label()}\" instead (${hit?.boundsInWindow})", hit?.id == target.id)
    }

    /**
     * Waits (as an eye does) until the rows stop moving: after a layer comes or goes, or moves,
     * the list animates the rows to their places, and a finger aims at where a row has landed.
     */
    fun settleRows() {
        if (window() == null) return
        var last: List<Rect>? = null
        repeat(40) {
            val now = s.placed().filter { it.node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("layers.row.") == true }.map { it.bounds }
            if (now == last) return
            last = now
            Smoke.pump(50)
        }
        throw AssertionError("$tag: the layer rows never stop moving")
    }

    /** A finger on [layer]'s ≡ handle dragged [rows] rows down (negative: up), in 16 moves. */
    fun dragReorder(layer: Layer, rows: Float) {
        settleRows()
        val handle = SmokeUi.find(LayerLabels.reorder(n(layer)), exact = true) ?: throw AssertionError("$tag: no ≡ of ${layer.name}")
        val lw = window() ?: throw AssertionError("$tag: the window is not open")
        assertTrue("$tag: ≡ of ${layer.name} inside the window", lw.contains(handle.bounds.center))
        assertLandsOn("≡ of ${layer.name}", handle, handle.bounds.center)
        val rowH = tagged(LayerWindowTags.row(layer.id)).node.size.height.toFloat()
        val hb = handle.bounds
        val t = Smoke.Touch(handle.window)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, hb.center.x, hb.center.y))
        for (i in 1..16) {
            t.idle(16)
            t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, hb.center.x, hb.center.y + i * rowH * rows / 16))
        }
        t.idle(16)
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, hb.center.x, hb.center.y + rowH * rows))
        settle()
    }

    /**
     * Scrolls the layer list (its scroll action, as a swipe would) until [layer]'s row is whole
     * inside it; a no-op when it is already.
     */
    fun reveal(layer: Layer) {
        fun row() = s.placed().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == LayerWindowTags.row(layer.id) }
        fun whole(): Boolean {
            val r = row() ?: return false
            val list = tagged(LayerWindowTags.ROWS).bounds
            return r.bounds.height >= r.node.size.height - 1f && r.bounds.top >= list.top - 0.5f && r.bounds.bottom <= list.bottom + 0.5f
        }
        var tries = 0
        settleRows()
        while (!whole()) {
            val list = tagged(LayerWindowTags.ROWS)
            var scroll = list.node.config.getOrNull(SemanticsActions.ScrollBy)?.action
            if (scroll == null) {
                // The scrollable may sit inside the tagged list.
                scroll = s.placed().lastOrNull { e -> e.window === list.window && list.bounds.contains(e.bounds.center) && e.node.config.getOrNull(SemanticsActions.ScrollBy) != null }
                    ?.node?.config?.getOrNull(SemanticsActions.ScrollBy)?.action
            }
            requireNotNull(scroll) { "$tag: ${layer.name}'s row is not whole and the list does not scroll" }
            // Rows are top first: a layer lower in the stack than the rows shown is further down.
            val shown = c.doc.layers.filter { l -> s.placed().any { it.node.config.getOrNull(SemanticsProperties.TestTag) == LayerWindowTags.row(l.id) } }
            val r = row()
            val down = when {
                r != null -> r.bounds.center.y > list.bounds.center.y
                shown.isNotEmpty() -> c.doc.indexOf(layer) < shown.minOf { c.doc.indexOf(it) }
                else -> true
            }
            scroll.invoke(0f, (if (down) 1f else -1f) * 40f * s.density)
            settle(2)
            if (++tries > 40) throw AssertionError("$tag: ${layer.name}'s row never scrolled into the list")
        }
    }

    /** A finger taps [layer]'s row on its thumbnail (where a row is tapped to pick it; the eye sits in the row's middle). */
    fun tapRow(layer: Layer) {
        reveal(layer)
        val row = SmokeUi.find(LayerLabels.selectRow(n(layer)), exact = true) ?: throw AssertionError("$tag: no row for ${layer.name}")
        val thumb = tagged(LayerWindowTags.thumb(layer.id)).bounds
        assertTrue("$tag: the thumbnail of ${layer.name} is inside the window", window()!!.contains(thumb.center))
        assertLandsOn("${layer.name}'s thumbnail", row, thumb.center)
        RobolectricUi.tap(row.window, thumb.center.x, thumb.center.y)
        settle()
    }

    /** The top row's Undo (a tap where it shows, the window open or not). */
    fun undo() {
        val n = steps()
        press("Undo", inWindow = false)
        assertEquals("$tag: Undo takes one step back", n - 1, steps())
    }

    /** [block] records exactly one undo step named [label] (any name when null). */
    fun oneStep(what: String, label: String?, block: () -> Unit) {
        val before = steps()
        block()
        settle()
        assertEquals("$tag $what: one undo step", before + 1, steps())
        if (label != null) assertEquals("$tag $what: step name", label, c.undoManager.undoLabel)
        log += "$what -> \"${c.undoManager.undoLabel}\""
    }

    private fun longPress(e: RobolectricUi.Element) {
        val b = e.bounds
        val t = Smoke.Touch(e.window)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, b.center.x, b.center.y))
        t.holdRealTime(900)
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, b.center.x, b.center.y))
        settle()
    }

    private fun tagged(tagName: String): RobolectricUi.Element =
        s.placed().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tagName } ?: throw AssertionError("$tag: nothing tagged $tagName")

    private fun n(layer: Layer) = c.doc.indexOf(layer) + 1

    fun open() {
        if (window() != null) return
        press("Open layers", exact = false, inWindow = false)
        assertNotNull("$tag: the layer window", window())
    }

    /**
     * The open window fits the screen, and every control in it (the left column, the strip, the
     * rows, the blend and opacity rows) is a 40 dp finger target whose centre is on screen, with
     * no string on two clickables (I10). [extra]: an adjustment layer and a masked, clipped,
     * locked layer show their badges in the rows too.
     */
    fun audit() {
        val lw = window() ?: throw AssertionError("$tag: the window is not open")
        val sc = screen()
        assertTrue("$tag: the window fits the screen's width ($lw in $sc)", lw.left >= sc.left - 0.5f && lw.right <= sc.right + 0.5f)
        val decor = s.activity.window.decorView
        probe.assertTouchTargets("$tag layer window", lw, decor)
        probe.assertUniqueMergedLabels("$tag layer window", lw, decor)
        val controls = probe.controlsIn(lw, decor)
        assertTrue("$tag: the window's controls (${controls.size})", controls.size >= 25)
        val off = controls.filter { (_, b) -> !sc.contains(b.center) }
        assertTrue("$tag: controls off screen: ${off.map { it.second }}", off.isEmpty())
        log += "audit: ${controls.size} controls in the ${"%.0f".format(lw.width / s.density)} x ${"%.0f".format(lw.height / s.density)} dp window"
    }

    /** Every action of the window, one step each, each taken back. */
    fun run() {
        val (bottom, middle, top) = c.doc.layers
        top.bitmap.setPixel(1, 1, red)
        top.markChanged()
        open()
        assertTrue("$tag: \"n / max\"", SmokeUi.has("3 / ${c.maxLayers}", exact = true))

        // ------------------------------------------------------------ the left column
        oneStep("Add layer", "Add layer") { press(LayerLabels.ADD) }
        assertEquals(4, c.doc.layers.size)
        assertSame("the new layer is active, above the old one", c.doc.layers[3], c.activeLayer)
        undo()
        assertEquals(3, c.doc.layers.size)

        val add = SmokeUi.find(LayerLabels.ADD, exact = true)!!
        assertEquals(LayerLabels.ADD_LONG, add.node.config.getOrNull(SemanticsActions.OnLongClick)?.label)
        longPress(add)
        assertTrue("$tag: a long press on + shows the special layers", SmokeUi.has(LayerLabels.NEW_VECTOR, exact = true))
        oneStep("New vector layer", null) { press(LayerLabels.NEW_VECTOR, inWindow = false) }
        assertTrue(c.activeLayer.isVectorLayer)
        undo()

        press(LayerLabels.SPECIAL)
        oneStep("New adjustment layer", null) { press(LayerLabels.NEW_ADJUSTMENT, inWindow = false) }
        val adjustment = c.activeLayer
        assertTrue(adjustment.isAdjustmentLayer)
        // The new adjustment layer opens in the Masks tool's Adjust sheet (the window gives way).
        assertEquals("$tag: the Masks tool", ToolId.MASK, c.activeToolId)
        log += "New adjustment layer -> tool ${c.activeToolId}, sheets ${SmokeUi.sheetTitles()}, window open: ${window() != null}"
        val steps = steps()
        press("Close", inWindow = false)
        assertEquals("$tag: closing the untouched sheet records nothing", steps, steps())
        open()
        assertTrue(
            "$tag: its row names the effect",
            SmokeUi.has(LayerLabels.rowState(n(adjustment), 100, adjustment.blendMode.label, AdjustmentEffects.displayName(adjustment.adjustment!!)), exact = true),
        )
        assertTrue("$tag: the strip applies it to the layer below", SmokeUi.has(LayerLabels.APPLY_BELOW, exact = true))
        assertFalse(SmokeUi.has(LayerLabels.MERGE, exact = true))
        undo()
        assertEquals(3, c.doc.layers.size)
        live.tool("Brush")
        open()
        c.selectLayer(top)
        settle()

        oneStep("Duplicate layer", "Duplicate layer") { press(LayerLabels.DUPLICATE) }
        assertEquals(4, c.doc.layers.size)
        undo()

        oneStep("Flip canvas horizontally", "Flip canvas horizontally") {
            val before = steps()
            press(LayerLabels.FLIP_CANVAS_H)
            assertTrue("the flip finishes", Smoke.pumpUntil { c.busyMessage == null && steps() > before })
        }
        assertEquals("the whole canvas mirrored", red, top.bitmap.getPixel(w - 2, 1))
        undo()
        oneStep("Flip canvas vertically", "Flip canvas vertically") {
            val before = steps()
            press(LayerLabels.FLIP_CANVAS_V)
            assertTrue("the flip finishes", Smoke.pumpUntil { c.busyMessage == null && steps() > before })
        }
        assertEquals(red, top.bitmap.getPixel(1, h - 2))
        undo()
        assertEquals(red, top.bitmap.getPixel(1, 1))

        // ------------------------------------------------------------ the right strip
        assertSame(top, c.activeLayer)
        oneStep("Clear layer", "Clear") { press(LayerLabels.CLEAR) }
        assertEquals(0, top.bitmap.getPixel(1, 1))
        undo()
        assertEquals(red, top.bitmap.getPixel(1, 1))

        press(LayerLabels.MASK)
        assertTrue("$tag: Layer mask opens the mask page", SmokeUi.has("Add mask", exact = true))
        oneStep("Add mask", "Add mask") { press("Add mask", inWindow = false) }
        assertNotNull(top.mask)
        if (!SmokeUi.has(LayerLabels.CLEAR_MASK, exact = true) && window() == null) open()
        assertTrue("$tag: the strip now clears the mask", SmokeUi.has(LayerLabels.CLEAR_MASK, exact = true))
        // The row's mask square switches what the brush edits (the mask, the content): no step.
        run {
            val steps = steps()
            val first = !top.editingMask
            repeat(2) { k ->
                val toMask = if (k == 0) first else !first
                press(if (toMask) LayerLabels.editMask(3) else LayerLabels.editContent(3))
                assertEquals("$tag: the mask square edits the ${if (toMask) "mask" else "content"}", toMask, top.editingMask)
                assertEquals("$tag: the row says MASK while the mask is edited", toMask, SmokeUi.has("MASK", exact = true))
                assertTrue("$tag: its label flips", SmokeUi.has(if (toMask) LayerLabels.editContent(3) else LayerLabels.editMask(3), exact = true))
            }
            assertEquals("$tag: switching the edit target records nothing", steps, steps())
            log += "mask square: edit target mask <-> content, no step"
        }
        undo()
        assertNull(top.mask)

        oneStep("Flip layer horizontally", "Flip layer horizontally") { press(LayerLabels.FLIP_H) }
        assertEquals(red, top.bitmap.getPixel(w - 2, 1))
        undo()
        oneStep("Flip layer vertically", "Flip layer vertically") { press(LayerLabels.FLIP_V) }
        assertEquals(red, top.bitmap.getPixel(1, h - 2))
        undo()

        oneStep("Merge down", "Merge down") { press(LayerLabels.MERGE) }
        assertEquals(2, c.doc.layers.size)
        assertEquals(red, c.doc.layers[1].bitmap.getPixel(1, 1))
        undo()
        assertEquals(listOf(bottom, middle, top), c.doc.layers)

        c.selectLayer(top)
        settle()
        oneStep("Delete layer", "Delete layer") { press(LayerLabels.DELETE) }
        assertEquals(2, c.doc.layers.size)
        undo()
        assertEquals(listOf(bottom, middle, top), c.doc.layers)
        c.selectLayer(top)
        settle()

        press(LayerLabels.MORE)
        assertTrue("$tag: ⋮ shows the v1.5 menu", SmokeUi.has("Rename…", exact = true))
        press("Rename…", inWindow = false)
        oneStep("Rename", "Rename layer") { SmokeUi.typeAndDone("Name", "Ink") }
        assertEquals("Ink", top.name)
        undo()
        assertEquals("Layer 3", top.name)
        if (window() == null) open()

        // ------------------------------------------------------------ the blend row
        oneStep("Clipping", "Clipping") { press(LayerLabels.CLIPPING) }
        assertTrue(top.clipping)
        undo()
        oneStep("Alpha lock", "Lock alpha") { press(LayerLabels.ALPHA_LOCK) }
        assertTrue(top.alphaLocked)
        undo()
        oneStep("Lock layer", "Lock layer") { press(LayerLabels.LOCK) }
        assertTrue(top.locked)
        assertTrue("$tag: the row says so", SmokeUi.has(LayerLabels.rowState(3, 100, "Normal", locked = true), exact = true))
        undo()
        press(LayerLabels.BLEND)
        oneStep("Blend mode", "Blend mode") { press(LayerBlendMode.MULTIPLY.label, inWindow = false) }
        assertEquals(LayerBlendMode.MULTIPLY, top.blendMode)
        assertTrue("$tag: the dropdown shows it", SmokeUi.has(LayerBlendMode.MULTIPLY.label, exact = true))
        undo()
        assertEquals(LayerBlendMode.NORMAL, top.blendMode)

        // ------------------------------------------------------------ the opacity row
        oneStep("Less layer opacity", "Opacity") { press(LayerLabels.LESS_OPACITY) }
        assertEquals(0.99f, top.opacity, 1e-4f)
        oneStep("More layer opacity", "Opacity") { press(LayerLabels.MORE_OPACITY) }
        assertEquals(1f, top.opacity, 1e-4f)
        press(LayerLabels.TYPE_OPACITY)
        oneStep("Type layer opacity", "Opacity") { SmokeUi.typeAndDone(LayerLabels.OPACITY, "37") }
        assertEquals(0.37f, top.opacity, 1e-4f)
        assertTrue("$tag: the value shows it", SmokeUi.has("37%", exact = true))
        val slider = SmokeUi.find(LayerLabels.OPACITY, exact = true) ?: throw AssertionError("$tag: no opacity slider")
        val before = steps()
        live.sliderDrag(slider, 0.37f, 0.8f, moves = 8) { assertEquals("$tag: no step under the finger", before, steps()) }
        assertEquals("$tag: opacity drag: one step", before + 1, steps())
        assertEquals("Opacity", c.undoManager.undoLabel)
        assertEquals(0.8f, top.opacity, 0.03f)
        repeat(4) { undo() }
        assertEquals(1f, top.opacity, 1e-4f)

        // ------------------------------------------------------------ rows
        oneStep("Hide layer", "Visibility") { press(LayerLabels.hide(3)) }
        assertFalse(top.visible)
        assertTrue(SmokeUi.has(LayerLabels.show(3), exact = true))
        undo()
        assertTrue(top.visible)

        tapRow(bottom)
        assertSame("$tag: a tap on a row selects it", bottom, c.activeLayer)

        // A long press on a row (its thumbnail) selects it and opens its ⋮ menu; nothing moves.
        longPress(tagged(LayerWindowTags.thumb(middle.id)))
        assertSame("$tag: the long-pressed layer is active", middle, c.activeLayer)
        assertTrue("$tag: its ⋮ menu is open", SmokeUi.has("Rename…", exact = true))
        assertEquals(listOf(bottom, middle, top), c.doc.layers)
        LayerWindowProbe.pressBackOnPopups(s.activity.window.decorView)
        assertFalse(SmokeUi.has("Rename…", exact = true))

        // The ≡ handle: the top layer dragged below the other two, one step.
        c.selectLayer(top)
        settle()
        oneStep("Reorder (≡ drag)", null) { dragReorder(top, 2.2f) }
        assertEquals("$tag: the dragged layer is now at the bottom", listOf(top, bottom, middle), c.doc.layers)
        undo()
        assertEquals(listOf(bottom, middle, top), c.doc.layers)

        // ------------------------------------------------------------ a linked text frame
        val frame = c.addLayer()!!
        frame.textData = TextCodec.encode(
            TextItem(
                text = "Hello",
                spec = TextSpec(box = TextBoxSpec(width = w * 0.4f, minHeight = h * 0.1f)),
                cx = w / 2f, cy = h / 2f,
                thread = TextThreadSpec(storyId = 7, index = 0, story = "Hello world", start = 0, end = 5, overset = true, rev = 1),
            ),
        )
        c.notifyLayersChanged()
        settle()
        val fn = n(frame)
        assertTrue("$tag: the frame badge", SmokeUi.has("Layer $fn: text frame 1 of 1, more text than fits", exact = true))
        press(LayerLabels.MORE)
        press("Edit text", inWindow = false)
        assertEquals("$tag: Edit text on a linked frame opens the Text frames tool", ToolId.TEXT_FRAMES, c.activeToolId)
        log += "Edit text on a frame -> ${c.activeToolId}, window open: ${window() != null}"
        live.tool("Brush")
        assertEquals(ToolId.BRUSH, c.activeToolId)
        if (window() == null) open()

        // ------------------------------------------------------------ the actions that leave the window
        c.selectLayer(top)
        settle()
        val stepsBefore = steps()
        press(LayerLabels.TRANSFORM)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        assertNull("$tag: Transform layer closes the window", window())
        live.tool("Brush")
        assertEquals("$tag: nothing moved, nothing recorded", stepsBefore, steps())

        open()
        press(LayerLabels.FILTERS)
        assertNull("$tag: Filters for this layer leaves the window", window())
        log += "Filters for this layer -> sheets ${SmokeUi.sheetTitles()}"
        assertTrue("$tag: the Filters panel: ${SmokeUi.shown().take(40)}", SmokeUi.sheetTitles().isNotEmpty())
        SmokeUi.click("Close", exact = true)
        assertEquals(stepsBefore, steps())

        open()
        press(LayerLabels.IMPORT)
        assertNull("$tag: Import picture closes the window first", window())
        assertEquals(stepsBefore, steps())
    }
}
