package com.brushwork.paint.qa18

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.8 final QA verification, item 4 (design §3.5): Color select by the flows the select tester
 * did not drive, at the T606's 392 dp, on two layers: line art (the active layer, transparent
 * around black lines) over a painted ground (a red that turns to yellow, a blue, a gray). With
 * Refer: Layer a tap on a transparent spot takes no sample ("Select colors" stays off) and a tap
 * on a line takes its black; a two-finger tap takes the sample back and a three-finger tap
 * brings it again (no document step); "Select colors" selects the lines only (ONE step); "Clear
 * samples" is one Undo away. With Refer: Canvas the red end's sample selects a soft band along
 * the gradient; "Anti-alias" off makes it hard, "Invert range" its complement (one step each,
 * Undo exact). The pending bar's ✕ takes the samples back to the last selection's; leaving the
 * tool keeps them and selects nothing. "Use the current color" adds the brush's color, and
 * "Intersect with selection" keeps what both select.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.selectcolorrefinesandbox"])
class Qa18SelectColorRefineUiTest {

    private companion object {
        const val WAIT_MS = 20_000L
        const val SIZE = 512

        /** The ground's red is flat left of this x, then turns to yellow at the right edge. */
        const val RED_END = 160
        const val GRADIENT_ROWS = 200
        const val BLACK = 0xFF000000.toInt()
    }

    private val h = ChromeHarness()
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private lateinit var tool: ColorSelectTool
    private val c get() = s.c

    @Test
    fun lineArtGradientInvertDiscardAndLeave() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        h.section("color select over line art at 392 dp") { run() }
        dog.interrupt()
        h.finish()
    }

    private fun steps() = c.undoManager.undoCount

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** The ground: red to yellow (rows above [GRADIENT_ROWS]), then a blue left and a gray right half. */
    private fun ground(bmp: Bitmap) {
        val px = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            px[y * SIZE + x] = when {
                y < GRADIENT_ROWS -> rgb(255, if (x < RED_END) 0 else ((x - RED_END) * 255f / (SIZE - 1 - RED_END)).roundToInt(), 0)
                x < SIZE / 2 -> rgb(0, 0, 255)
                else -> rgb(128, 128, 128)
            }
        }
        bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
    }

    /** Black line art: one line across the gradient, one down the blue, one down the gray. */
    private fun isLine(x: Int, y: Int) =
        (y in 120..123 && x in 32..479) || (x in 120..123 && y in 220..479) || (x in 380..383 && y in 220..479)

    private fun lines(bmp: Bitmap) {
        val px = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) if (isLine(x, y)) px[y * SIZE + x] = BLACK
        bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
    }

    private fun bytes(): ByteArray = BitmapUtils.alpha8ToBytes(c.selection!!.mask)

    private fun a(b: ByteArray, x: Int, y: Int) = b[y * SIZE + x].toInt() and 0xFF

    private fun waitIdle(what: String) = assertTrue("$what: the tool settles", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle })

    private fun newPreview(after: Int, what: String) =
        assertTrue("$what: a new preview", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && (tool.preview?.version ?: 0) > after })

    /** Taps "Select colors" and waits for the new selection: ONE step "Color select". */
    private fun selectColors(what: String): ByteArray {
        val prev = c.selection
        val n = steps()
        assertTrue("$what: \"Select colors\" is on", SmokeUi.isEnabled(ColorSelectLabels18.SELECT_COLORS))
        SmokeUi.tap(ColorSelectLabels18.SELECT_COLORS, exact = true)
        assertTrue("$what: a new selection", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && c.busyMessage == null && c.selection != null && c.selection !== prev })
        settle()
        assertEquals("$what: ONE step", n + 1, steps())
        assertEquals(ToolId.COLOR_SELECT.label, c.undoManager.undoLabel)
        return bytes()
    }

    /** Taps [label] on the More row. */
    private fun moreRow(label: String) {
        assertTrue("the More row opens", StripTestKit.openMoreRow() || StripTestKit.moreRowShown())
        SmokeUi.tap(label, exact = true)
        settle()
        waitIdle(label)
        StripTestKit.closeMoreRow()
    }

    private fun run() {
        val doc = Smoke.document(SIZE, SIZE, layers = 2)
        ground(doc.layers[0].bitmap)
        lines(doc.layers[1].bitmap)
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        assertEquals("the line art is the active layer", doc.layers[1], c.activeLayer)

        ui.tool(ToolId.COLOR_SELECT.label)
        tool = c.currentTool as ColorSelectTool
        assertTrue("the proxy", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.proxyReady && tool.idle && tool.preview != null })
        StripTestKit.closeMoreRow()
        assertEquals("Refer starts on the layer", SampleSource.LAYER, tool.settings.source)
        val discard = "Discard ${ToolId.COLOR_SELECT.label.lowercase()} edit"
        val before = steps()

        // ---- Refer: Layer. A tap where the line art is transparent takes no sample.
        ui.tap(200f, 300f)
        waitIdle("a transparent spot")
        assertTrue("no sample from a transparent pixel: ${tool.sampleColors}", tool.sampleColors.isEmpty())
        assertFalse("nothing pending", tool.hasPendingWork)
        assertFalse("no ✕ shows", SmokeUi.has(discard, exact = true))
        assertFalse("\"Select colors\" stays off with nothing to select", SmokeUi.isEnabled(ColorSelectLabels18.SELECT_COLORS))

        // ---- A tap on a line takes its black.
        var v = tool.preview!!.version
        ui.tap(250f, 121.5f)
        newPreview(v, "the black")
        assertEquals("the line's black", listOf(BLACK), tool.sampleColors)
        assertTrue("the ✕ shows: ${SmokeUi.shown().take(60)}", SmokeUi.has(discard, exact = true))

        // ---- Two fingers take the sample back, three bring it again: no document step.
        v = tool.preview!!.version
        Fingers18.twoFingerTap(s, 256f, 400f)
        newPreview(v, "two fingers")
        assertTrue("a two-finger tap takes the sample back", tool.sampleColors.isEmpty())
        v = tool.preview!!.version
        Fingers18.threeFingerTap(s, 256f, 400f)
        newPreview(v, "three fingers")
        assertEquals("a three-finger tap brings it again", listOf(BLACK), tool.sampleColors)
        assertEquals("no document step", before, steps())

        // ---- "Select colors": the lines and nothing else.
        val linesSel = selectColors("the lines")
        var wrong = 0
        for (y in 0 until SIZE) for (x in 0 until SIZE) if (a(linesSel, x, y) != if (isLine(x, y)) 255 else 0) wrong++
        assertEquals("the selection is the lines, exactly", 0, wrong)
        assertFalse("the samples' steps end with the selection", tool.hasPendingWork)

        // ---- "Clear samples", then the top row's Undo: the black is back, the selection untouched.
        moreRow(ColorSelectLabels18.CLEAR_SAMPLES)
        assertTrue("cleared", tool.sampleColors.isEmpty())
        Finger.tap(s, "Undo")
        waitIdle("undo the clear")
        assertEquals("Undo brings the cleared sample back", listOf(BLACK), tool.sampleColors)
        assertEquals("no document step taken back", before + 1, steps())
        assertArrayEquals("the selection stays", linesSel, bytes())

        // ---- Refer: Canvas; the samples cleared; a tap on the red end of the gradient.
        moreRow(ColorSelectLabels18.CLEAR_SAMPLES)
        assertTrue("the More row opens", StripTestKit.openMoreRow() || StripTestKit.moreRowShown())
        SmokeUi.tap("Refer: ${SampleSource.LAYER.label}", exact = true)
        settle()
        SmokeUi.tap(SampleSource.CANVAS.label, exact = true)
        settle()
        assertEquals(SampleSource.CANVAS, tool.settings.source)
        assertTrue("the canvas's proxy", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.proxyReady && tool.idle && tool.preview != null })
        StripTestKit.closeMoreRow()
        v = tool.preview!!.version
        ui.tap(100f, 60f)
        newPreview(v, "the red")
        assertEquals("the canvas's red", listOf(rgb(255, 0, 0)), tool.sampleColors)

        // ---- Soft: the band fades along the gradient.
        val soft = selectColors("the red, soft")
        val row = 60
        val along = (0 until SIZE).map { a(soft, it, row) }
        val partial = along.count { it in 1..254 }
        println("Qa18 color refine: along the gradient ${along.filterIndexed { i, _ -> i % 16 == 0 }}, $partial partial")
        assertEquals("the flat red is wholly in", 255, along[RED_END - 1])
        assertEquals("the yellow is out", 0, along.last())
        assertTrue("soft edges: $partial partly selected pixels along the row", partial >= 3)
        assertTrue("the band only fades along the gradient", along.zipWithNext().all { (p, q) -> q <= p })
        assertEquals("the line over the gradient is black on the canvas: out", 0, a(soft, 250, 121))
        assertEquals("the blue is out", 0, a(soft, 60, 300))
        val (l, t) = s.screen(0f, 0f)
        val (r, b) = s.screen(SIZE.toFloat(), 260f)
        Qa18SelectShots.crop(s, "color-refine-soft", l.toInt(), t.toInt(), r.toInt(), b.toInt())

        // ---- "Anti-alias" off: every pixel in or out, where the soft band is at least half.
        moreRow("Anti-alias")
        assertFalse(tool.settings.antiAlias)
        val hard = selectColors("the red, hard")
        var off = 0
        for (i in hard.indices) {
            val want = if ((soft[i].toInt() and 0xFF) >= 128) 255 else 0
            if ((hard[i].toInt() and 0xFF) != want) off++
        }
        assertEquals("anti-alias off: 0 or 255, cut at half the soft band", 0, off)
        moreRow("Anti-alias")
        assertTrue(tool.settings.antiAlias)

        // ---- "Invert range": the soft band's complement.
        moreRow(ColorSelectLabels18.INVERT_RANGE)
        assertTrue(tool.settings.invert)
        val inverse = selectColors("the red, inverted")
        var worst = 0
        for (i in inverse.indices) worst = maxOf(worst, abs((inverse[i].toInt() and 0xFF) + (soft[i].toInt() and 0xFF) - 255))
        assertTrue("inverted: the complement of the soft band (off by $worst)", worst <= 1)
        assertEquals("the yellow is in", 255, a(inverse, SIZE - 1, row))
        assertEquals("the blue is in", 255, a(inverse, 60, 300))
        moreRow(ColorSelectLabels18.INVERT_RANGE)
        assertEquals("three selections, one step each", before + 4, steps())

        // ---- Undo is exact: the hard band, then the inverse again.
        Finger.tap(s, "Undo")
        settle()
        assertEquals(before + 3, steps())
        assertArrayEquals("Undo: the hard band", hard, bytes())
        Finger.tap(s, "Redo")
        settle()
        assertArrayEquals("Redo: the inverse", inverse, bytes())

        // ---- A sample more, then the pending bar's ✕: the samples of the last selection.
        v = tool.preview!!.version
        ui.tap(100f, 300f)
        newPreview(v, "the blue")
        assertEquals(listOf(rgb(255, 0, 0), rgb(0, 0, 255)), tool.sampleColors)
        assertTrue("the ✕ shows", SmokeUi.has(discard, exact = true))
        SmokeUi.tap(discard, exact = true)
        waitIdle("✕")
        assertEquals("✕: back to the last selection's samples", listOf(rgb(255, 0, 0)), tool.sampleColors)
        assertFalse("nothing pending", tool.hasPendingWork)
        assertEquals("✕ makes no step", before + 4, steps())
        assertArrayEquals("and keeps the selection", inverse, bytes())

        // ---- Leaving the tool with a new sample: nothing selected, the samples kept.
        ui.tap(100f, 300f)
        waitIdle("the blue again")
        assertEquals(2, tool.sampleColors.size)
        ui.tool(ToolId.BRUSH.label)
        settle()
        assertEquals("leaving makes no step", before + 4, steps())
        assertArrayEquals("leaving selects nothing", inverse, bytes())
        ui.tool(ToolId.COLOR_SELECT.label)
        assertTrue("back", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.proxyReady && tool.idle && tool.preview != null })
        assertEquals("the samples are kept", listOf(rgb(255, 0, 0), rgb(0, 0, 255)), tool.sampleColors)
        assertFalse("their steps ended with the tool", tool.hasPendingWork)
        Qa18SelectShots.screen(s, "color-refine-back")

        // ---- "Use the current color" (the brush's black), then "Intersect with selection".
        v = tool.preview!!.version
        moreRow(ColorSelectLabels18.USE_CURRENT)
        newPreview(v, "the brush's color")
        assertEquals("the brush's color is a sample", listOf(rgb(255, 0, 0), rgb(0, 0, 255), c.color or BLACK), tool.sampleColors)
        SmokeUi.tap(MagneticLabels18.SELECTION_MODE, exact = true)
        SmokeUi.tap("Intersect with selection", exact = true)
        settle()
        val both = selectColors("intersected")
        assertEquals("four selections, one step each", before + 5, steps())
        assertEquals("the blue: in both", 255, a(both, 60, 300))
        assertEquals("the line down the gray: black, in both", 255, a(both, 381, 300))
        assertEquals("the gray: in neither", 0, a(both, 450, 300))
        assertEquals("the flat red: not in the inverse", 0, a(both, 100, 60))
        assertEquals("the yellow: not a sample's", 0, a(both, SIZE - 1, row))
        Smoke.assertQuiet(c, "color select over line art")
    }
}
