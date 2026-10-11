package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18SelectPictures.Disc
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.colorrange.ColorPreset
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.selection.ColorSelectStrip
import com.brushwork.paint.ui.vector.ShapeStripKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * v1.8 final QA (Color select's polish): the "Color range" chip is 92 dp, and "Sampled colors"
 * did not fit it at the phone's widths (cut to "Sampled …", the samples unseen). With samples
 * the chip shows them as a row of swatches, one per sample (eight at most), clear of its ▾; with
 * none it says "None yet" (not "Sampled": a text inside another is against §4.8 rule 4, and the
 * popup shows "Sampled colors"). Its name, width and state ("Sampled colors") stay. The other
 * presets show their names; "Vivid colors" and "Muted colors" are cut by a letter or two, which
 * the fixed chip allows as its state says them whole (ARCHITECTURE, "StripValueButton"). Driven
 * by the visible controls: canvas taps for the samples, the chip's popup for the presets, the
 * More row's "Clear samples".
 */
internal object Qa18SelectRangeChipKit {
    private const val WAIT_MS = 20_000L

    /** The presets whose names the chip cuts (its state says them whole). */
    private val STATED_WHOLE = setOf(ColorPreset.VIVID, ColorPreset.MUTED)

    private fun isChip(n: SemanticsNode) =
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == ColorSelectLabels18.COLOR_RANGE } == true

    private fun underChip(n: SemanticsNode) = generateSequence(n.parent) { it.parent }.any(::isChip)

    private fun chip(): RobolectricUi.Element = RobolectricUi.elements().lastOrNull { it.node.layoutInfo.isPlaced && isChip(it.node) }
        ?: throw AssertionError("no \"${ColorSelectLabels18.COLOR_RANGE}\" chip: ${SmokeUi.shown().take(60)}")

    private fun state(): String? = chip().node.config.getOrNull(SemanticsProperties.StateDescription)

    /** The texts drawn inside the chip (its name and, unless swatches show, its value). */
    private fun texts(): List<String> = RobolectricUi.elements().map { it.node }
        .filter { it.layoutInfo.isPlaced && underChip(it) }
        .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** The chip's texts that do not fit it (cut, ellipsized). */
    private fun cut(): List<String> = ShapeStripKit.clippedTexts { underChip(it) }

    /** The chip as drawn now: its pixels (row by row) and its window bounds (px). */
    private fun pixels(s: ChromeScreen): Pair<IntArray, Rect> {
        val decor = s.activity.window.decorView
        val b = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(b))
        val r = chip().bounds
        val l = r.left.toInt(); val t = r.top.toInt()
        val w = r.width.toInt(); val h = r.height.toInt()
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, l, t, w, h)
        b.recycle()
        return px to r
    }

    private fun near(a: Int, b: Int) = (0..16 step 8).all { sh -> abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)) <= 2 }

    /** For each of [colors]: the mean x (window px) of the chip's pixels of that color, or null with fewer than [min]. */
    private fun swatchXs(s: ChromeScreen, colors: List<Int>, min: Int = 20): List<Float?> {
        val (px, r) = pixels(s)
        val w = r.width.toInt()
        return colors.map { c ->
            var n = 0; var sx = 0f
            for (i in px.indices) if (near(px[i], c)) { n++; sx += i % w }
            if (n >= min) r.left + sx / n else null
        }
    }

    private fun pick(preset: ColorPreset, tool: ColorSelectTool) {
        SmokeUi.tap(ColorSelectLabels18.COLOR_RANGE, exact = true)
        settle()
        SmokeUi.tap(preset.label, exact = true)
        assertTrue("${preset.label} picked", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.settings.preset == preset && tool.idle })
        settle()
        assertEquals("the chip says its preset whole", preset.label, state())
    }

    private fun shot(s: ChromeScreen, name: String) {
        val r = chip().bounds
        Qa18SelectShots.crop(s, name, 0, (r.top - 8f * s.density).toInt(), s.activity.window.decorView.width, (r.bottom + 8f * s.density).toInt())
    }

    fun run(s: ChromeScreen, widthDp: Int) {
        val c = s.c
        val ui = Qa16Ui(s)
        assertEquals("the phone is $widthDp dp wide", widthDp.toFloat(), s.widthDp, 1f)
        ui.tool(ToolId.COLOR_SELECT.label)
        val tool = c.currentTool as ColorSelectTool
        assertTrue("the proxy", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.proxyReady && tool.idle && tool.preview != null })
        StripTestKit.closeMoreRow()
        val chip = chip().bounds
        assertEquals("the chip keeps its 92 dp", ColorSelectStrip.RANGE_DP.toFloat(), chip.width / s.density, 1f)
        assertEquals(ColorSelectLabels18.SAMPLED, state())

        // ---- Every preset through the chip's popup: its name, "None yet" for the samples (none taken).
        val cutBy = linkedMapOf<ColorPreset, List<String>>()
        cutBy[ColorPreset.SAMPLED] = cut()
        val shown = linkedMapOf(ColorPreset.SAMPLED to texts())
        for (p in ColorPreset.entries.drop(1) + ColorPreset.SAMPLED) {
            pick(p, tool)
            cutBy[p] = cut()
            shown[p] = texts()
        }
        println("Qa18 range chip at $widthDp dp: cut $cutBy; texts $shown")
        val wrongCut = cutBy.filter { (p, cut) -> cut.isNotEmpty() && p !in STATED_WHOLE }
        assertTrue("no other preset's value is cut: $wrongCut", wrongCut.isEmpty())
        for (p in ColorPreset.entries) {
            val value = if (p == ColorPreset.SAMPLED) ColorSelectLabels18.NO_SAMPLES else p.label
            assertEquals("${p.label}: the chip's texts", listOf(ColorSelectLabels18.COLOR_RANGE, value), shown[p])
        }
        shot(s, "chip-$widthDp-no-sample")

        // ---- Samples tapped on the canvas: one swatch each, in order, the ▾ kept clear.
        val discs = listOf(Disc.RED, Disc.YELLOW, Disc.GREEN, Disc.CYAN, Disc.BLUE, Disc.SKIN, Disc.WHITE, Disc.DARK)
        for ((k, d) in discs.withIndex()) {
            val v = tool.preview!!.version
            ui.tap(d.x, d.y)
            assertTrue("sample ${k + 1}", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.sampleColors.size == k + 1 && tool.idle && tool.preview!!.version > v })
            settle()
            if (k == 1 || k == discs.lastIndex) {
                val colors = tool.sampleColors
                // Each dot as large as the row takes (12 dp for two, 6 dp for eight): its inside (the
                // 1 dp rim off) mostly of its sample's color.
                val inside = (ColorSelectStrip.swatchDp(colors.size) - 2) * s.density
                val xs = swatchXs(s, colors, min = (0.6f * Math.PI.toFloat() / 4f * inside * inside).toInt())
                println("Qa18 range chip at $widthDp dp, ${colors.size} samples (${ColorSelectStrip.swatchDp(colors.size)} dp): swatch x $xs in ${chip.left}..${chip.right}")
                if (k == 1) shot(s, "chip-$widthDp-two-samples")
                assertTrue("every sample has its swatch: $xs", xs.all { it != null })
                val sorted = xs.filterNotNull()
                assertEquals("the swatches in the samples' order", sorted.sorted(), sorted)
                val inner = chip.left + 6f * s.density..chip.right - 18f * s.density
                assertTrue("the swatches stay clear of the ▾: $sorted in $inner", sorted.all { it in inner })
                assertEquals("no value text with swatches", listOf(ColorSelectLabels18.COLOR_RANGE), texts())
                assertEquals("the state still says it whole", ColorSelectLabels18.SAMPLED, state())
                assertTrue("nothing cut: ${cut()}", cut().isEmpty())
            }
        }
        assertEquals("eight samples", 8, tool.sampleColors.size)
        shot(s, "chip-$widthDp-eight-samples")

        // ---- Another preset: its name; back to the samples: the swatches again.
        val eight = tool.sampleColors
        pick(ColorPreset.REDS, tool)
        assertEquals(listOf(ColorSelectLabels18.COLOR_RANGE, ColorSelectLabels18.REDS), texts())
        assertTrue("no swatch with Reds", swatchXs(s, eight).all { it == null })
        pick(ColorPreset.SAMPLED, tool)
        assertTrue("the swatches again", swatchXs(s, eight).all { it != null })

        // ---- "Clear samples" (the More row): "None yet" again, no swatch.
        assertTrue("the More row opens", StripTestKit.openMoreRow())
        SmokeUi.tap(ColorSelectLabels18.CLEAR_SAMPLES, exact = true)
        assertTrue("cleared", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.sampleColors.isEmpty() && tool.idle })
        StripTestKit.closeMoreRow()
        settle()
        assertFalse(StripTestKit.moreRowShown())
        assertEquals(listOf(ColorSelectLabels18.COLOR_RANGE, ColorSelectLabels18.NO_SAMPLES), texts())
        assertTrue("no swatch left", swatchXs(s, eight).all { it == null })
        assertTrue("nothing cut: ${cut()}", cut().isEmpty())
        Smoke.assertQuiet(c, "the range chip at $widthDp dp")
    }
}
