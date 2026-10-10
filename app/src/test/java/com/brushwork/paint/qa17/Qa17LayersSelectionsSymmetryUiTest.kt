package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.MarqueeShape
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 final QA, layers cluster, verifier's flow (items 14 and 18 together, design §3.14 and
 * §3.18) on a 360 dp phone, by fingers and the labels shown:
 *
 * 1. A rectangle dragged with "Select shape" on the left, kept with the layer window's "Add
 *    selection layer" (the + on the "Selection Layer" row): one "Save selection" step.
 * 2. The Brush user's way to symmetry: the top row's "Ruler" opens the Ruler sheet, its "Symmetry
 *    rulers" section's "Mirror ruler" chip turns it on (no step, the tool stays Brush, the guides
 *    show). A finger stroke inside the selection near the axis: its mirrored copy is painted only
 *    where it falls inside the selection, the rest of the copy is cut away; one step.
 * 3. A new rectangle on the right; the saved row's menu "Update from selection": one "Update saved
 *    selection" step, the saved mask is the new rectangle. Two fingers over the layer window undo
 *    it (the old mask back, the active selection untouched), three fingers redo it.
 */
internal class Qa17LayersSelectionsSymmetry(private val h: ChromeHarness) {
    private lateinit var s: ChromeScreen
    private var u: Qa17LayersUi? = null
    private val l: Qa17LayersUi get() = u!!
    private val c: EditorController get() = s.c

    fun release() {
        u?.release()
        u = null
    }

    /** A blue-grey Layer 1 under a clear Layer 2 (active, painted on). */
    private fun document(): Document = Smoke.document(W, H, layers = 2, whiteBottom = false).also { d ->
        d.layers[0].bitmap.eraseColor(B)
        d.layers[0].markChanged()
    }

    private fun rect(r: IntArray): BooleanArray =
        BooleanArray(W * H) { i -> val x = i % W; val y = i / W; x >= r[0] && x < r[2] && y >= r[1] && y < r[3] }

    private fun bytes(m: BooleanArray): ByteArray = ByteArray(m.size) { if (m[it]) -1 else 0 }

    private fun bytes(sel: Selection?): ByteArray = BitmapUtils.alpha8ToBytes(requireNotNull(sel) { "no selection" }.mask)

    private fun assertMask(what: String, want: BooleanArray, got: ByteArray) {
        assertEquals(want.size, got.size)
        var off = 0
        for (i in want.indices) if ((got[i].toInt() and 0xFF) != (if (want[i]) 255 else 0)) off++
        assertEquals("$what: pixels off the expected mask", 0, off)
    }

    private fun saved(): ByteArray = bytes(c.doc.savedSelections.single().toSelection(W, H))

    /** "Select shape" (a rectangle): a finger drag from corner to corner of [r]. */
    private fun drag(r: IntArray) {
        if (c.activeToolId != ToolId.MARQUEE) l.ui.tool("Select shape")
        val t = c.currentTool as MarqueeTool
        assertEquals("a rectangle", MarqueeShape.RECTANGLE, t.settings.shape)
        val before = c.selection
        l.ui.stroke(r[0].toFloat() to r[1].toFloat(), (r[0] + r[2]) / 2f to (r[1] + r[3]) / 2f, r[2].toFloat() to r[3].toFloat())
        assertTrue("the rectangle is selected", Smoke.pumpUntil { settle(1); c.selection != null && c.selection !== before && !t.busy })
        settle()
        assertMask("the dragged rectangle", rect(r), bytes(c.selection))
    }

    /** Non-blank pixels of the overlays the canvas draws (guides, the selection's outline...). */
    private fun overlay(): Int {
        val cv = s.canvas
        val b = Bitmap.createBitmap(cv.width, cv.height, Bitmap.Config.ARGB_8888)
        try {
            c.drawOverlays(Canvas(b), 0f)
            val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            return px.count { it != 0 }
        } finally {
            b.recycle()
        }
    }

    private fun overWindow(vararg fx: Float): List<Pair<Float, Float>> {
        val w = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        return fx.map { Finger.px(s, w.left + w.width * it, w.top + w.height * 0.45f) }
    }

    private fun layer2(): IntArray = c.doc.layers[1].bitmap.let { b -> IntArray(W * H).also { b.getPixels(it, 0, W, 0, 0, W, H) } }

    // ================================================================== 1. saved from the layer window

    private lateinit var sa: BooleanArray
    private lateinit var sb: BooleanArray

    fun saveFromTheLayerWindow() {
        u?.release()
        s = h.editor(document()) { it.snapping.enabled = false }
        u = Qa17LayersUi(s)
        settle()
        assertEquals("a 360 dp phone", 360f, s.widthDp, 1f)
        sa = rect(SA); sb = rect(SB)
        drag(SA)
        l.openLayers()
        l.ui.reach(SavedSelectionLabels.ADD_LAYER, 40f)
        Smoke.pump(600)
        settle()
        assertTrue("\"Add selection layer\" is enabled", SmokeUi.isEnabled(SavedSelectionLabels.ADD_LAYER))
        l.oneStep("Add selection layer", HistoryLabels.SAVE_SELECTION) {
            Finger.tap(s, SavedSelectionLabels.ADD_LAYER)
            assertTrue("landed", Smoke.pumpUntil { settle(1); c.pendingSavedSelections.isEmpty() })
        }
        assertEquals(listOf("Selection 1"), c.doc.savedSelections.map { it.name })
        assertTrue("its row", has("Selection 1", exact = true))
        assertMask("saved", sa, saved())
        assertMask("the selection stays", sa, bytes(c.selection))
        l.closeLayers()
    }

    // ================================================================== 2. the mirror from the Ruler sheet, under the selection

    fun mirrorUnderTheSelection() {
        l.ui.tool("Brush")
        assertEquals(ToolId.BRUSH, c.activeToolId)
        c.color = INK
        c.brush = c.brush.copy(size = 6f, opacity = 1f, pressureSize = false)
        val withoutGuides = overlay()
        val steps = l.steps()
        // The top row's "Ruler": the Ruler sheet, its symmetry section's "Mirror ruler".
        Finger.tap(s, "Ruler")
        assertTrue("the Ruler sheet: ${SmokeUi.sheetTitles()}", SmokeUi.sheetTitles().contains("Ruler"))
        // (A section header shows in capitals.)
        assertTrue("its symmetry section; shown ${SmokeUi.shown()}", has("SYMMETRY RULERS", exact = true))
        l.ui.reach(SymmetryType.MIRROR.label, 32f)
        click(SymmetryType.MIRROR.label, exact = true)
        assertEquals(SymmetryType.MIRROR, c.symmetry.type)
        Finger.back()
        settle()
        assertTrue("the sheet closed: ${SmokeUi.sheetTitles()}", SmokeUi.sheetTitles().isEmpty())
        assertEquals("a ruler is no step", steps, l.steps())
        assertEquals("still the Brush", ToolId.BRUSH, c.activeToolId)
        assertTrue("the mirror's guide shows (${overlay()} vs $withoutGuides px)", overlay() > withoutGuides + 100)
        // The axis: the vertical through the middle.
        val maps = SymmetryMaps.transforms(c.symmetry, W, H)
        assertEquals(2, maps.size)
        val m = maps[1]
        assertEquals("mirrored across x = ${W / 2}", W - 40f, m[0] * 40f + m[1] * 60f + m[2], 0.01f)

        // A stroke at y 60 from x 40 to 112 (inside the selection); its copy runs from x 200 to
        // 128: inside the selection up to x 150, cut away beyond.
        val before = layer2()
        l.oneStep("a mirrored stroke", "Brush") { l.ui.stroke(40f to 60f, 76f to 60f, 112f to 60f) }
        val after = layer2()
        fun a(x: Int, y: Int) = after[y * W + x] ushr 24
        assertTrue("the stroke", a(60, 60) == 255 && a(100, 60) == 255)
        assertTrue("its copy inside the selection", a(135, 60) == 255 && a(145, 60) == 255)
        assertEquals("its copy outside the selection is cut away", 0, a(170, 60))
        assertEquals("its copy outside the selection is cut away", 0, a(190, 60))
        var outside = 0
        for (i in after.indices) if (!sa[i] && after[i] != before[i]) outside++
        assertEquals("nothing painted outside the selection", 0, outside)
        Qa17LayersShots.saveGrid(
            "selsym-1-mirror-in-selection",
            listOf(Qa17LayersShots.tinted(l.pixels(), bytes(sa), RED)),
            W, H, 1,
        )
        Smoke.assertQuiet(c, "mirror under the selection")
    }

    // ================================================================== 3. update from selection, undone and redone over the window

    fun updateUndoRedo() {
        drag(SB)
        val picture = l.pixels()
        l.openLayers()
        l.ui.reach("Selection 1", 40f)
        Finger.tap(s, "Selection 1")
        assertTrue("its menu", has(SavedSelectionLabels.UPDATE, exact = true))
        assertTrue("\"Update from selection\" is enabled", SmokeUi.isEnabled(SavedSelectionLabels.UPDATE))
        l.oneStep("Update from selection", HistoryLabels.UPDATE_SAVED_SELECTION) {
            click(SavedSelectionLabels.UPDATE, exact = true)
            assertTrue("landed", Smoke.pumpUntil { settle(1); c.pendingSavedSelections.isEmpty() })
        }
        assertEquals("the same entry, renamed nothing", listOf("Selection 1"), c.doc.savedSelections.map { it.name })
        assertMask("updated", sb, saved())
        assertArrayEquals("the picture is unchanged", picture, l.pixels())
        if (SmokeUi.sheetTitles().isNotEmpty() || has(SavedSelectionLabels.UPDATE, exact = true)) Finger.back()
        settle()
        assertNotNull("the layer window is open", s.tagged(ChromeTags.LAYER_WINDOW))

        // Two fingers over the layer window: the old mask back; the active selection untouched.
        val n = l.steps()
        val (a, b) = overWindow(0.3f, 0.7f)
        s.touch.idle(400)
        s.touch.twoFingerTap(a, b)
        settle(4)
        assertEquals("one step back", n - 1, l.steps())
        assertTrue("the feedback", SmokeUi.shown().any { it == "Undo: ${HistoryLabels.UPDATE_SAVED_SELECTION}" })
        assertMask("undone: the old mask", sa, saved())
        assertMask("undone: the selection is untouched", sb, bytes(c.selection))
        assertArrayEquals("undone: the picture is unchanged", picture, l.pixels())
        // Three fingers: redone.
        val (a3, b3, d3) = overWindow(0.2f, 0.5f, 0.8f)
        s.touch.idle(400)
        s.touch.threeFingerTap(a3, b3, d3)
        settle(4)
        assertEquals("one step forward", n, l.steps())
        assertMask("redone: the new mask", sb, saved())
        assertMask("redone: the selection is untouched", sb, bytes(c.selection))
        Qa17LayersShots.saveGrid(
            "selsym-2-updated-saved-mask",
            listOf(
                Qa17LayersShots.tinted(picture, bytes(sa), RED),
                Qa17LayersShots.tinted(picture, saved(), YELLOW),
            ),
            W, H, 2,
        )
        l.closeLayers()
        Smoke.assertQuiet(c, "update, undo, redo")
    }

    companion object {
        const val W = 240
        const val H = 180
        val B = 0xFF8CB4DC.toInt()
        val INK = 0xFF2040C0.toInt()
        val RED = 0xFFE02020.toInt()
        val YELLOW = 0xFFF0D020.toInt()
        /** The left rectangle (crosses the mirror axis at x 120 up to x 150) and the right one. */
        val SA = intArrayOf(20, 30, 150, 160)
        val SB = intArrayOf(130, 40, 230, 170)

        fun run() {
            ShadowLog.stream = null
            SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 60_000)
            val h = ChromeHarness()
            val t = Qa17LayersSelectionsSymmetry(h)
            val times = mutableListOf<String>()
            fun timed(name: String, block: () -> Unit) {
                val t0 = System.nanoTime()
                block()
                times += "$name ${(System.nanoTime() - t0) / 1_000_000} ms"
            }
            // One editor for 1 to 3 (a section closes its editors).
            h.section("saved selection, mirror under it, update at 360 dp") {
                timed("1 save from the layer window") { t.saveFromTheLayerWindow() }
                timed("2 mirror under the selection") { t.mirrorUnderTheSelection() }
                timed("3 update, undo, redo") { t.updateUndoRedo() }
            }
            println("Qa17LayersSelectionsSymmetry times: $times")
            t.release()
            dog.interrupt()
            h.finish()
        }
    }
}

/** Items 14 and 18 together on a 360 dp phone: a saved selection from the layer window, the mirror from the Ruler sheet under it, its update undone by fingers. */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.layersselsym360sandbox"])
class Qa17LayersSelectionsSymmetryUiTest {
    @Test
    fun aSavedSelectionFromTheLayerWindowLimitsAMirrorStrokeAndItsUpdateIsUndoneByFingersAt360dp() = Qa17LayersSelectionsSymmetry.run()
}
