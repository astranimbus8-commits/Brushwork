package com.brushwork.paint.qa16

import android.graphics.Bitmap
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.LetterScaleAlign
import com.brushwork.paint.tools.text.LetterScaleScope
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.placement.LETTER_SCALING_VERTICAL
import com.brushwork.paint.ui.placement.letterAlignLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, letter scaling beyond one line, through the editor as the user works it: two
 * lines (the ramp runs over the whole text, the lines keep their full-size pitch; "Edit text"
 * turns scaling on for a placed text), "Each paragraph" (every line starts again at full size),
 * vertical text (letters centred on the column, the Align chips off with their hint), text on a
 * shape (a line and a circle), and an outline with a box border. Every picture is written for
 * the lead (letters-*.png).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.letterslayoutsandbox"])
class LettersLayoutUiTest {

    private lateinit var h: ChromeHarness
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c
    private val text get() = c.currentTool as TextTool

    /** A new artwork, the Text tool, a tap at ([x], [y]), [words] typed, Bold, [px] px. */
    private fun start(w: Int, hh: Int, color: Int, x: Float, y: Float, words: String, px: Int = 59) {
        s = h.editor(Smoke.document(w, hh, layers = 2, whiteBottom = false)) { it.color = color; it.snapping.enabled = false }
        ui = Qa16Ui(s)
        ui.tool("Text")
        ui.tap(x, y)
        assertTrue("a tap opens the text editor", text.editorOpen)
        SmokeUi.field("Text").type(words)
        settle()
        click("Bold", exact = true)
        ui.textSizePx(px)
    }

    /** "Scale letters" on, the smallest letter typed. */
    private fun scale(percent: Int = 62) {
        click("Scale letters", exact = true)
        click("Type a value for Smallest letter", exact = true)
        SmokeUi.typeAndDone("Smallest letter", percent.toString())
    }

    /** OK, then ✓: one step; the picture (written as [shot]). */
    private fun commit(shot: String, bg: Int): Bitmap {
        click("OK", exact = true)
        val steps = c.undoManager.undoCount
        click("Apply text edit")
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertTrue(c.activeLayer.isTextLayer)
        val pic = c.compositor.renderFlattened(bg)
        Shots.save(pic, shot)
        Smoke.assertQuiet(c, shot)
        return pic
    }

    private fun spread(v: List<Double>) = v.max() - v.min()

    @Test
    fun linesParagraphsVerticalPathsOutlineAndBorder() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("two lines: the ramp runs over the whole text, lines keep their pitch; Edit text") { twoLines() }
        h.section("Each paragraph: every line starts at full size") { eachParagraph() }
        h.section("vertical text: centred on the column, Align off") { vertical() }
        h.section("on a line and on a circle") { onPaths() }
        h.section("outline and box border follow the scaled letters") { outlineAndBorder() }
        dog.interrupt()
        h.finish()
    }

    // ------------------------------------------------------------------ two lines

    private fun twoLines() {
        start(440, 200, WHITE, 220f, 100f, "ELTON\nJOHN")
        val plain = InkLetters.lines(commit("letters-two-lines-unscaled.png", BG), BG, WHITE)
        assertEquals("two lines: $plain", 2, plain.size)
        // Baselines: the bottoms of E (line 1) and H (line 2), both flat.
        val pitch = plain[1][2].stemBottom - plain[0][0].stemBottom
        val layer = c.activeLayer

        // Edit text (the strip of the active text layer): scaling on, Baseline, ✓.
        click("Edit text", exact = true)
        assertTrue("the editor opens for the layer", text.editorOpen && text.editingLayer === layer)
        assertTrue(has("Edit text", exact = true))
        scale()
        click(letterAlignLabel(LetterScaleAlign.BASELINE), exact = true)
        val lines = InkLetters.lines(commit("letters-two-lines.png", BG), BG, WHITE)
        assertEquals("still the same layer", layer, c.activeLayer)
        System.err.println("two lines: unscaled $plain\n  scaled $lines")
        assertEquals(2, lines.size)
        val (one, two) = lines
        assertTrue("ELTON: 4 or 5 runs (L and T may touch): $one", one.size in 4..5)
        assertEquals("JOHN: $two", 4, two.size)
        // The ramp runs over the 9 letters: E is letter 0, N letter 4, J letter 5, H letter 7, N letter 8.
        val full = one.first().stemHeight
        fun f(k: Int) = 1 - 0.38 * k / 8
        assertEquals("N (4)", full * f(4), one.last().stemHeight, 0.6)
        assertEquals("H (7)", full * f(7), two[2].stemHeight, 0.6)
        assertEquals("N (8)", full * f(8), two[3].stemHeight, 0.6)
        // Lines keep the full-size pitch (Baseline: bottoms on each line's base line).
        assertEquals("the line pitch is unchanged", pitch, two[2].stemBottom - one.first().stemBottom, 1.0)
        assertTrue("line 1 on one base line", spread(listOf(one.first().stemBottom, one.last().stemBottom)) <= LINE)
        assertTrue("line 2 on one base line", spread(listOf(two[2].stemBottom, two[3].stemBottom)) <= LINE)
    }

    // ------------------------------------------------------------------ each paragraph

    private fun eachParagraph() {
        start(440, 200, WHITE, 220f, 100f, "ELTON\nJOHN")
        scale()
        click(LetterScaleScope.EACH_PARAGRAPH.label, exact = true)
        assertEquals(LetterScaleScope.EACH_PARAGRAPH, text.item!!.spec.letterScale.scope)
        val lines = InkLetters.lines(commit("letters-each-paragraph.png", BG), BG, WHITE)
        System.err.println("each paragraph: $lines")
        assertEquals(2, lines.size)
        val (one, two) = lines
        val full = one.first().stemHeight
        // ELTON: 5 letters, 100 % → 62 %; JOHN: 4 letters, 100 % → 62 % again.
        assertEquals("N ends line 1 at 62 %", full * 0.62, one.last().stemHeight, 0.6)
        assertEquals("H: letter 2 of 4", full * (1 - 0.38 * 2 / 3), two[2].stemHeight, 0.6)
        assertEquals("N ends line 2 at 62 % too", full * 0.62, two[3].stemHeight, 0.6)
        // Center: each line's letters on its own centre line.
        assertTrue(spread(listOf(one.first().stemCentre, one.last().stemCentre)) <= LINE)
        assertTrue(spread(listOf(two[2].stemCentre, two[3].stemCentre)) <= LINE)
    }

    // ------------------------------------------------------------------ vertical

    private fun vertical() {
        start(200, 520, WHITE, 100f, 260f, "ELTON", px = 59)
        scale(40)
        click("Vertical text", exact = true)
        assertTrue(text.item!!.spec.vertical)
        assertTrue("the hint: ${SmokeUi.shown().take(80)}", has(LETTER_SCALING_VERTICAL, exact = true))
        for (a in LetterScaleAlign.entries) assertFalse("$a is off for vertical text", SmokeUi.isEnabled(letterAlignLabel(a), exact = true))
        val pic = commit("letters-vertical.png", BG)
        val stack = InkLetters.stacked(pic, BG, WHITE)
        System.err.println("vertical: $stack")
        assertEquals("five letters down the column: $stack", 5, stack.size)
        val centres = stack.map { (it.left + it.right) / 2.0 }
        assertTrue("centred on the column: $centres", spread(centres) <= 1.5)
        val heights = stack.map { it.height() }
        for (i in 1 until heights.size) assertTrue("shrinking down the column: $heights", heights[i] < heights[i - 1])
        assertEquals("the last letter is 40 % of the first", 0.4, heights.last().toDouble() / heights.first(), 0.06)
    }

    // ------------------------------------------------------------------ paths

    private fun onPaths() {
        // Along a line: like straight text, the letters shrink on one centre line.
        start(480, 200, WHITE, 240f, 100f, "ELTON JOHN", px = 48)
        scale()
        click(TextPathType.LINE.label, exact = true)
        assertEquals(TextPathType.LINE, text.item!!.path.type)
        val line = InkLetters.letters(commit("letters-path-line.png", BG), BG, WHITE)
        System.err.println("on a line: $line")
        assertTrue("8 or 9 letters: $line", line.size in 8..9)
        val first = line.first().stemHeight
        val last = line.last().stemHeight
        assertEquals("the last letter is 62 % of the first", 0.62, last / first, 0.04)
        assertTrue("centres on one line", spread(listOf(line.first().stemCentre, line.last().stemCentre)) <= LINE)
        assertTrue(c.activeLayer.item().path.isActive)
        assertTrue(c.activeLayer.item().spec.letterScale.isOn)

        // Around a circle: drawn (ink in every quarter it covers), one step, kept on the layer.
        start(400, 400, WHITE, 200f, 200f, "ELTON JOHN ELTON JOHN", px = 40)
        scale(40)
        click(TextPathType.CIRCLE.label, exact = true)
        val pic = commit("letters-path-circle.png", BG)
        val stored = c.activeLayer.item()
        assertEquals(TextPathType.CIRCLE, stored.path.type)
        assertTrue(stored.spec.letterScale.isOn)
        val ink = InkLetters.coverage(pic, BG, WHITE).sumOf { row -> row.count { it >= 0.5 } }
        assertTrue("letters drawn around the circle: $ink", ink > 2000)
    }

    // ------------------------------------------------------------------ outline and border

    private fun outlineAndBorder() {
        start(480, 200, BLACK, 240f, 100f, "ELTON JOHN", px = 48)
        scale()
        click("Type a value for Outline width", exact = true)
        SmokeUi.typeAndDone("Outline width", "4")
        click("Type a value for Padding", exact = true)
        SmokeUi.typeAndDone("Padding", "12")
        click("Type a value for Border width", exact = true)
        SmokeUi.typeAndDone("Border width", "3")
        val spec = text.item!!.spec
        assertEquals(4f, spec.strokeWidthPx, 0.01f)
        assertEquals(3f, spec.box.borderWidth, 0.01f)
        val pic = commit("letters-outline-border.png", GREY)
        val stored: Layer = c.activeLayer
        assertTrue(stored.item().spec.letterScale.isOn)
        // The black letters (and border) on grey: the white outline rings each letter, also the smallest.
        val dark = InkLetters.letters(pic, GREY, BLACK)
        System.err.println("outline and border, dark runs: $dark")
        // The border joins everything dark into one run: the box.
        assertEquals("one box: $dark", 1, dark.size)
        val box = dark.single()
        val px = IntArray(pic.width * pic.height).also { pic.getPixels(it, 0, pic.width, 0, 0, pic.width, pic.height) }
        fun white(x: Int, y: Int) = px[y * pic.width + x].let { android.graphics.Color.red(it) > 230 && android.graphics.Color.green(it) > 230 }
        // The white outline is all inside the box.
        val whites = (0 until pic.width).flatMap { x -> (0 until pic.height).filter { y -> white(x, y) }.map { x to it } }
        assertTrue("an outline was drawn", whites.size > 200)
        assertTrue("outline inside the box", whites.all { (x, y) -> x in box.left..box.right && y in box.top..box.bottom })
        // Inside the border: the letters, each ringed by the outline (white just above its black
        // ink), the first (100 %) and the last (62 %) alike.
        val inset = 6
        val inner = Bitmap.createBitmap(pic, box.left + inset, box.top + inset, box.right - box.left - 2 * inset, box.bottom - box.top - 2 * inset)
        val letters = InkLetters.letters(inner, GREY, BLACK)
        assertTrue("letters inside the box: $letters", letters.size >= 8)
        assertEquals("the last letter is 62 % of the first", 0.62, letters.last().stemHeight / letters.first().stemHeight, 0.04)
        for (l in listOf(letters.first(), letters.last())) {
            val x = box.left + inset + (l.left + l.right) / 2
            val inkTop = box.top + inset + l.top
            assertTrue("white outline just above the letter at x $x", (inkTop - 6 until inkTop).any { y -> (x - 3..x + 3).any { xx -> white(xx, y) } })
        }
        // The box hugs the scaled text: padding 12 + border 3 (+ outline 4) around the letters.
        val leftGap = letters.first().left + inset
        assertTrue("the box fits the scaled letters (gap $leftGap px)", leftGap in 12..24)
        val rightGap = (box.right - box.left) - (letters.last().right + inset)
        assertTrue("on both sides (gap $rightGap px)", rightGap in 12..26)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val BG = 0xFF090B0C.toInt()
        const val GREY = 0xFF808080.toInt()

        /** See [LettersEltonJohnUiTest]: glyph baselines land on whole pixels. */
        const val LINE = 1.25
    }
}
