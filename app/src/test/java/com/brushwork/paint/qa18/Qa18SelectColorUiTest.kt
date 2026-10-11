package com.brushwork.paint.qa18

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18SelectPictures.Disc
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.colorrange.ColorPreset
import com.brushwork.paint.tools.select.colorrange.ColorRange
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * v1.8 final QA, item 4 (design §3.5): Color select on a photo-like picture at the T606's 392 dp,
 * by finger and labels. Two colors tapped on the canvas and a third from "Add from the color
 * picker"; the Fuzziness slider dragged by a finger, the red preview read after each move (0
 * keeps only the exact colors, a wider one the whole discs); "Select colors" makes ONE step. Each
 * of the thirteen preset chips previews what it names; a second selection is one undo away;
 * "Magentas" on a picture with no magenta says "No pixels match these colors" and adds no step.
 * Then through the selection: "Save selection" on the selection bar, "Fill" from its Selection
 * menu (one step, undone) and the Grayscale filter, which change the selected pixels only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.selectcolorsandbox"])
class Qa18SelectColorUiTest {

    private companion object {
        const val WAIT_MS = 20_000L
    }

    private val h = ChromeHarness()
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private lateinit var tool: ColorSelectTool
    private val c get() = s.c

    @Test
    fun samplesFuzzinessPresetsAndTheSelectionAtWork() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        h.section("color select at 392 dp") { run() }
        dog.interrupt()
        h.finish()
    }

    private fun steps() = c.undoManager.undoCount

    private fun stateOf(label: String): String? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
    }?.node?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun deltaE(a: Int, b: Int): Float {
        val la = FloatArray(3); val lb = FloatArray(3)
        ColorRange.lab(a, la)
        ColorRange.lab(b, lb)
        return sqrt((0 until 3).sumOf { ((la[it] - lb[it]) * (la[it] - lb[it])).toDouble() }).toFloat()
    }

    /** The red preview's strength (0..255) at document pixel ([x], [y]). */
    private fun previewAt(x: Int, y: Int): Int {
        val pv = tool.preview!!
        return pv.mask[(y * pv.height / c.doc.height) * pv.width + x * pv.width / c.doc.width].toInt() and 0xFF
    }

    /** The preview's mean strength over the 21 × 21 px square at [d]'s centre. */
    private fun previewMean(d: Disc): Int {
        var sum = 0
        for (y in d.y.toInt() - 10..d.y.toInt() + 10) for (x in d.x.toInt() - 10..d.x.toInt() + 10) sum += previewAt(x, y)
        return sum / (21 * 21)
    }

    /** The share of [d]'s inner disc (54 px) the preview takes in at least [min]. */
    private fun previewShare(d: Disc, min: Int = 1): Float {
        var n = 0; var hit = 0
        for (y in (d.y - 54).toInt()..(d.y + 54).toInt()) for (x in (d.x - 54).toInt()..(d.x + 54).toInt()) {
            if (hypot(x + 0.5f - d.x, y + 0.5f - d.y) > 54f) continue
            n++
            if (previewAt(x, y) >= min) hit++
        }
        return hit / n.toFloat()
    }

    private fun newPreview(after: Int, what: String) =
        assertTrue("$what: a new preview", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && (tool.preview?.version ?: 0) > after })

    /** Taps "Select colors" and waits for the new selection it makes. */
    private fun selectColors(what: String) {
        val prev = c.selection
        SmokeUi.tap(ColorSelectLabels18.SELECT_COLORS, exact = true)
        assertTrue("$what: a new selection", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && c.busyMessage == null && c.selection != null && c.selection !== prev })
        settle()
    }

    /**
     * A finger tap on [label] once its strip stands still. [Qa16Ui.reach] scrolls a strip with
     * its ScrollBy action, which animates; a tap on a moving strip only stops it (as on a phone),
     * so the tap waits for the animation to end (`Qa17LayersSavedSelectionsUiTest` does the same).
     */
    private fun tapStill(label: String) {
        Smoke.pump(600)
        settle()
        SmokeUi.tap(label, exact = true)
        settle()
    }

    private fun selectionBytes(): ByteArray = BitmapUtils.alpha8ToBytes(c.selection!!.mask)

    /** In the open color picker: the hex typed, then OK. */
    private fun pickHex(hex: String) {
        assertTrue("the color picker is open: ${SmokeUi.shown().take(60)}", SmokeUi.has("OK", exact = true))
        SmokeUi.field("Hex").type(hex)
        settle(2)
        SmokeUi.tap("OK", exact = true)
        settle()
        assertFalse("the color picker closed", SmokeUi.has("OK", exact = true))
    }

    private fun run() {
        val doc = Smoke.document(Qa18SelectPictures.SIZE, Qa18SelectPictures.SIZE, layers = 1)
        Qa18SelectPictures.colors(doc.layers[0].bitmap)
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        val layer = c.activeLayer
        val picture = Fx.pixels(layer.bitmap)

        ui.tool(ToolId.COLOR_SELECT.label)
        tool = c.currentTool as ColorSelectTool
        assertTrue("the proxy", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.proxyReady && tool.idle && tool.preview != null })
        StripTestKit.closeMoreRow()
        assertEquals(40, tool.settings.fuzziness)

        // ---- Two colors tapped on the canvas, a third from the color picker.
        var v = tool.preview!!.version
        ui.tap(Disc.RED.x, Disc.RED.y)
        newPreview(v, "the red")
        v = tool.preview!!.version
        ui.tap(Disc.YELLOW.x, Disc.YELLOW.y)
        newPreview(v, "the yellow")
        assertEquals("two samples", 2, tool.sampleColors.size)
        assertTrue("the red's pixel: ${Integer.toHexString(tool.sampleColors[0])}", deltaE(tool.sampleColors[0], Disc.RED.color) < 10f)
        assertTrue("the yellow's pixel: ${Integer.toHexString(tool.sampleColors[1])}", deltaE(tool.sampleColors[1], Disc.YELLOW.color) < 10f)
        v = tool.preview!!.version
        SmokeUi.tap(ColorSelectLabels18.FROM_PICKER, exact = true)
        settle()
        pickHex("3CA546")
        assertEquals("the picker added the green", Disc.GREEN.color, tool.sampleColors.last())
        newPreview(v, "the green")
        val sampled = listOf(Disc.RED, Disc.YELLOW, Disc.GREEN)
        val far = listOf(Disc.BLUE, Disc.CYAN, Disc.DARK, Disc.WHITE, Disc.GRAY)
        println("Qa18 color select, Fuzziness 40: whole ${sampled.map { previewShare(it, 255) }}, the rest ${far.map { previewMean(it) }}")
        for (d in sampled) assertTrue("$d wholly in the preview at 40: ${previewShare(d, 255)}", previewShare(d, 255) >= 0.97f)
        for (d in far) assertEquals("$d out of the preview at 40", 0, previewMean(d))
        Qa18SelectShots.screen(s, "color-1-three-samples")

        // ---- Fuzziness: its panel's slider, moved by a finger; the preview read after each move.
        SmokeUi.tap(ColorSelectLabels18.FUZZINESS, exact = true)
        val slider = ui.slider(ColorSelectLabels18.FUZZINESS)
        assertTrue("the slider is in the panel's own window", slider.window !== s.activity.window.decorView)
        val b = slider.bounds
        val pad = 10f * s.density
        fun xOf(f: Int) = b.left + pad + f / 200f * (b.width - 2f * pad)
        val y = b.center.y
        val win = slider.window
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            win.dispatchTouchEvent(e)
            e.recycle()
        }
        val watched = mutableListOf<String>()
        fun watch(x: Float): Int {
            val before = tool.preview!!.version
            val f0 = tool.settings.fuzziness
            send(MotionEvent.ACTION_MOVE, x)
            settle(2)
            if (tool.settings.fuzziness != f0) newPreview(before, "Fuzziness ${tool.settings.fuzziness}")
            watched += "${tool.settings.fuzziness}: red ${(previewShare(Disc.RED) * 100).toInt()} %"
            return tool.settings.fuzziness
        }
        send(MotionEvent.ACTION_DOWN, xOf(40))
        settle(2)
        for (k in 1..4) watch(xOf(40) + (b.left - 20f - xOf(40)) * k / 4f)
        assertEquals("the slider's left end", 0, tool.settings.fuzziness)
        // At 0 only the exact colors count: the tapped pixel and the few of its very color.
        val exact = previewShare(Disc.RED)
        assertTrue("Fuzziness 0: some of the red, not the disc ($exact)", exact > 0f && exact < 0.2f)
        Qa18SelectShots.screen(s, "color-2-fuzziness-0")
        // Back up the track; where 50 is comes from the values the finger has just read.
        val x1 = xOf(10); val x2 = xOf(30)
        val v1 = watch(x1); val v2 = watch(x2)
        assertTrue("the value follows the finger: $v1 then $v2", v2 > v1)
        val x50 = x2 + (50 - v2) * (x2 - x1) / (v2 - v1)
        watch((x2 + x50) / 2f)
        watch(x50)
        send(MotionEvent.ACTION_UP, x50)
        settle()
        val fz = tool.settings.fuzziness
        println("Qa18 color select, the Fuzziness drag: $watched")
        assertTrue("about 50 where the finger lifted: $fz", fz in 46..54)
        // The cyan is out of the green's reach at [fz] (ΔE76 from the colors, as the tool's).
        val cyanToGreen = deltaE(Disc.CYAN.color, Disc.GREEN.color)
        assertTrue("the cyan is more than $fz from the green: ΔE $cyanToGreen", cyanToGreen >= fz + 3f)
        for (d in sampled) assertTrue("$d wholly in again at $fz: ${previewShare(d, 255)}", previewShare(d, 255) >= 0.97f)
        for (d in listOf(Disc.BLUE, Disc.DARK)) assertEquals("$d out at $fz", 0, previewMean(d))
        Qa18SelectShots.screen(s, "color-3-fuzziness-$fz")
        Finger.back()
        assertFalse("the panel closed", SmokeUi.windows().any { it === win })
        StripTestKit.closeMoreRow()
        assertEquals("the chip says it", "$fz", stateOf(ColorSelectLabels18.FUZZINESS))

        // ---- Select colors: ONE step; the sampled discs, nothing far from them.
        var before = steps()
        selectColors("the samples")
        assertEquals("one step", before + 1, steps())
        assertEquals(ToolId.COLOR_SELECT.label, c.undoManager.undoLabel)
        val first = c.selection!!
        for (d in sampled) assertEquals("$d selected", 255, first.alphaAt(d.x.toInt(), d.y.toInt()))
        for (d in listOf(Disc.BLUE, Disc.DARK, Disc.CYAN)) assertEquals("$d not", 0, first.alphaAt(d.x.toInt(), d.y.toInt()))
        val firstBytes = selectionBytes()
        Qa18SelectShots.screen(s, "color-4-selected")

        // ---- The thirteen presets: each previews what it names, at Fuzziness [fz].
        // The picture has no magenta up to Fuzziness 100 (past it, a rim pixel or two rounded
        // toward red's 0° start to count, at 1 / 255): checked here, pixel by pixel.
        val magentas = ColorRange.Params.of(ColorPreset.MAGENTAS, 100, IntArray(0))
        assertTrue(fz <= 100)
        assertEquals("no pixel of the picture is a magenta", 0, picture.count { ColorRange.colorMember(it, 0, 0, 0, magentas) > 0f })
        class Probe(val preset: ColorPreset, val ins: List<Disc>, val outs: List<Disc>)
        val probes = listOf(
            Probe(ColorPreset.REDS, listOf(Disc.RED), listOf(Disc.GREEN, Disc.BLUE, Disc.CYAN)),
            Probe(ColorPreset.YELLOWS, listOf(Disc.YELLOW), listOf(Disc.BLUE, Disc.CYAN)),
            Probe(ColorPreset.GREENS, listOf(Disc.GREEN), listOf(Disc.RED, Disc.BLUE)),
            Probe(ColorPreset.CYANS, listOf(Disc.CYAN), listOf(Disc.RED, Disc.YELLOW)),
            Probe(ColorPreset.BLUES, listOf(Disc.BLUE), listOf(Disc.RED, Disc.YELLOW, Disc.GREEN)),
            Probe(ColorPreset.MAGENTAS, emptyList(), Disc.entries),
            Probe(ColorPreset.HIGHLIGHTS, listOf(Disc.WHITE), listOf(Disc.DARK, Disc.GRAY)),
            Probe(ColorPreset.MIDTONES, listOf(Disc.GRAY), listOf(Disc.WHITE, Disc.DARK)),
            Probe(ColorPreset.SHADOWS, listOf(Disc.DARK), listOf(Disc.WHITE, Disc.YELLOW)),
            Probe(ColorPreset.VIVID, listOf(Disc.RED, Disc.BLUE), listOf(Disc.GRAY, Disc.WHITE)),
            Probe(ColorPreset.MUTED, listOf(Disc.GRAY, Disc.WHITE), listOf(Disc.RED, Disc.BLUE)),
            Probe(ColorPreset.SKIN, listOf(Disc.SKIN), listOf(Disc.BLUE, Disc.GREEN)),
            Probe(ColorPreset.SAMPLED, sampled, listOf(Disc.BLUE, Disc.DARK)),
        )
        assertEquals("every preset", ColorPreset.entries.toSet(), probes.map { it.preset }.toSet())
        for (p in probes) {
            v = tool.preview!!.version
            SmokeUi.tap(ColorSelectLabels18.COLOR_RANGE, exact = true)
            SmokeUi.tap(p.preset.label, exact = true)
            assertEquals(p.preset, tool.settings.preset)
            newPreview(v, p.preset.label)
            StripTestKit.closeMoreRow()
            assertEquals("the chip says it", p.preset.label, stateOf(ColorSelectLabels18.COLOR_RANGE))
            val means = Disc.entries.associateWith { previewMean(it) }
            println("Qa18 color select, ${p.preset.label} at $fz: $means")
            for (d in p.ins) assertTrue("${p.preset.label}: $d in the preview (${means[d]})", means.getValue(d) >= 200)
            for (d in p.outs) assertTrue("${p.preset.label}: $d out of the preview (${means[d]})", means.getValue(d) <= 4)
            when (p.preset) {
                ColorPreset.BLUES -> {
                    Qa18SelectShots.screen(s, "color-5-blues")
                    // A second selection: one step; one undo brings the first back.
                    before = steps()
                    selectColors("Blues")
                    assertEquals(before + 1, steps())
                    // 215° is 25° from Blues' 240°: just inside the band's soft edge, as the preview said.
                    val blue = c.selection!!.alphaAt(Disc.BLUE.x.toInt(), Disc.BLUE.y.toInt())
                    assertTrue("the blue is selected: $blue", blue >= 240)
                    assertEquals(0, c.selection!!.alphaAt(Disc.RED.x.toInt(), Disc.RED.y.toInt()))
                    Finger.tap(s, "Undo")
                    assertTrue("undone", Smoke.pumpUntil(WAIT_MS) { settle(1); steps() == before && c.busyMessage == null })
                    settle()
                    assertArrayEquals("one undo: the first selection again", firstBytes, selectionBytes())
                }
                ColorPreset.MAGENTAS -> {
                    val pv = tool.preview!!
                    assertTrue("an empty preview", pv.mask.all { it.toInt() == 0 })
                    // Nothing to select: said, no step, the selection stays.
                    before = steps()
                    val kept = c.selection
                    SmokeUi.tap(ColorSelectLabels18.SELECT_COLORS, exact = true)
                    assertTrue("the check ends", Smoke.pumpUntil(WAIT_MS) { settle(1); !tool.busy && tool.idle && c.busyMessage == null })
                    settle()
                    assertTrue("the message shows: ${SmokeUi.shown().take(60)}", SmokeUi.has(ColorSelectLabels18.NO_MATCH, exact = true))
                    assertEquals("no step", before, steps())
                    assertSame("the selection stays", kept, c.selection)
                    Qa18SelectShots.screen(s, "color-6-no-match")
                }
                ColorPreset.SKIN -> Qa18SelectShots.screen(s, "color-7-skin-tones")
                else -> {}
            }
        }
        StripTestKit.closeMoreRow()

        // ---- Save selection, on the selection bar.
        assertNotNull("the selection bar", s.tagged(ChromeTags.SELECTION_BAR))
        before = steps()
        ui.reach(SavedSelectionLabels.SAVE)
        tapStill(SavedSelectionLabels.SAVE)
        assertTrue("saved",Smoke.pumpUntil(WAIT_MS) { settle(1); c.doc.savedSelections.isNotEmpty() })
        settle()
        assertEquals(1, c.doc.savedSelections.size)
        assertEquals(HistoryLabels.SAVE_SELECTION, c.undoManager.undoLabel)
        assertEquals(before + 1, steps())

        // ---- Fill (the bar's Selection menu): the selected pixels only; one step, one undo.
        val mask = selectionBytes()
        val fill = c.color or (0xFF shl 24)
        ui.reach("Selection menu")
        tapStill("Selection menu")
        ui.reach("Fill")
        tapStill("Fill")
        assertTrue("filled", Smoke.pumpUntil(WAIT_MS) { settle(1); c.busyMessage == null && steps() == before + 2 })
        settle()
        assertEquals("Fill selection", c.undoManager.undoLabel)
        val filled = Fx.pixels(layer.bitmap)
        var outside = 0; var whole = 0; var wrong = 0
        for (i in filled.indices) {
            val a = mask[i].toInt() and 0xFF
            if (a == 0 && filled[i] != picture[i]) outside++
            if (a == 255) { whole++; if (filled[i] != fill) wrong++ }
        }
        println("Qa18 color select, Fill: $whole px whole, $wrong of them not the fill, $outside changed outside")
        assertTrue("a good part of the picture is selected: $whole px", whole > 25_000)
        assertEquals("nothing outside the selection changes", 0, outside)
        assertEquals("the selected pixels take the color", 0, wrong)
        Qa18SelectShots.screen(s, "color-8-filled")
        Finger.tap(s, "Undo")
        assertTrue("undone", Smoke.pumpUntil(WAIT_MS) { settle(1); steps() == before + 1 && c.busyMessage == null })
        settle()
        assertArrayEquals("one undo: the picture again", picture, Fx.pixels(layer.bitmap))

        // ---- A filter through the selection: Grayscale changes the selected pixels only.
        val gray = FilterRegistry.byId("adjust.grayscale") ?: throw AssertionError("Grayscale is registered")
        ui.tool("Filters")
        SmokeUi.assertPanelShown("Filters")
        SmokeUi.field("Search filters").let { it.focus(); settle(2); it.type("Grayscale") }
        settle(4)
        SmokeUi.click(gray.name, exact = true)
        val session = c.filterSession ?: throw AssertionError("no Grayscale session (${c.message})")
        Fx.awaitPreview(session, gray.name)
        settle()
        Qa18SelectShots.screen(s, "color-9-grayscale-preview")
        SmokeUi.click("Apply filter", exact = true)
        assertTrue("applied", Smoke.pumpUntil(WAIT_MS * 2) { settle(1); c.filterSession == null && c.busyMessage == null })
        settle()
        assertEquals(before + 2, steps())
        assertEquals(gray.name, c.undoManager.undoLabel)
        val grayed = Fx.pixels(layer.bitmap)
        outside = 0; wrong = 0
        for (i in grayed.indices) {
            val a = mask[i].toInt() and 0xFF
            val p = grayed[i]
            if (a == 0 && p != picture[i]) outside++
            if (a == 255 && ((p shr 16) and 0xFF != p and 0xFF || (p shr 8) and 0xFF != p and 0xFF)) wrong++
        }
        println("Qa18 color select, Grayscale: $wrong whole px not gray, $outside changed outside")
        assertEquals("nothing outside the selection changes", 0, outside)
        assertEquals("the selected pixels turn gray", 0, wrong)
        Qa18SelectShots.screen(s, "color-10-grayscale-applied")
        Smoke.assertQuiet(c, "color select")
    }
}
