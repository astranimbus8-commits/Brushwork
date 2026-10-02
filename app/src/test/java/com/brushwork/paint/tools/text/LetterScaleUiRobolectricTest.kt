package com.brushwork.paint.tools.text

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.placement.LETTER_SCALING_LTR_ONLY
import com.brushwork.paint.ui.placement.LETTER_SCALING_VERTICAL
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6 §3.5(a) in a real activity at the user's phone size (392 × 873 dp): the text editor's
 * "Letter scaling" section and the options strip's "Letters" chip (accessible label "Letter
 * scaling", I10) with its small sheet. "Scale letters" starts at 60 %, the slider value can be
 * typed, every chip applies live to the pending text, vertical text disables Align with its
 * hint, a right-to-left text gets the note, and the whole edit is one undo step on ✓ (Cancel
 * takes it back).
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.letterscalesandbox"])
class LetterScaleUiRobolectricTest {

    @Test
    fun theSectionTheChipAndTheSheet() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1, whiteBottom = true))
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        activity.setContent { BrushworkTheme { TextToolOptions(tool) } }
        SmokeUi.settle()

        // No text yet: the chip says what to do.
        assertTrue("the strip has the chip: ${SmokeUi.shown().take(40)}", SmokeUi.has("Letters", exact = true))
        SmokeUi.click("Letter scaling", exact = true)
        assertEquals(TextTool.LETTERS_NEED_TEXT, c.message)
        assertFalse(tool.lettersSheetOpen)

        // The editor's section.
        tool.startTextAt(300f, 400f)
        tool.setText("ELTON JOHN")
        SmokeUi.settle()
        assertTrue(SmokeUi.has("LETTER SCALING", exact = true))
        assertTrue(SmokeUi.has("Smallest letter", exact = true))
        SmokeUi.click("Scale letters", exact = true)
        val on = tool.item!!.spec.letterScale
        assertTrue(on.isOn)
        assertEquals("starts near the example", LetterScaleSpec.DEFAULT_ON_PERCENT, on.smallestPercent, 0f)
        assertEquals(LetterScaleAlign.CENTER, on.align)
        assertEquals(LetterScaleCurve.EVEN, on.curve)
        SmokeUi.click("End → beginning", exact = true)
        assertEquals(LetterScaleDirection.END_TO_START, tool.item!!.spec.letterScale.direction)
        SmokeUi.click("Baseline", exact = true)
        assertEquals(LetterScaleAlign.BASELINE, tool.item!!.spec.letterScale.align)
        SmokeUi.click("Same ratio", exact = true)
        assertEquals(LetterScaleCurve.RATIO, tool.item!!.spec.letterScale.curve)
        SmokeUi.click("Each paragraph", exact = true)
        assertEquals(LetterScaleScope.EACH_PARAGRAPH, tool.item!!.spec.letterScale.scope)
        // The smallest letter typed: 26 / 42 of the example.
        SmokeUi.click("Type a value for Smallest letter", exact = true)
        SmokeUi.typeAndDone("Smallest letter", "62")
        assertEquals(62f, tool.item!!.spec.letterScale.smallestPercent, 0f)
        assertEquals("nothing is history while editing", 0, c.undoManager.undoCount)
        // Cancel takes the scaling back (the new text is removed).
        SmokeUi.click("Cancel", exact = true)
        assertEquals(null, tool.item)

        // Again, kept with OK; then the strip's chip opens the small sheet.
        tool.startTextAt(300f, 400f)
        tool.setText("ELTON JOHN")
        SmokeUi.settle()
        SmokeUi.click("Scale letters", exact = true)
        SmokeUi.click("OK", exact = true)
        assertTrue(tool.item!!.spec.letterScale.isOn)
        SmokeUi.click("Letter scaling", exact = true)
        assertTrue(tool.lettersSheetOpen)
        SmokeUi.assertWindowsLaidOut(2)
        SmokeUi.click("Top", exact = true)
        assertEquals(LetterScaleAlign.TOP, tool.item!!.spec.letterScale.align)
        // Vertical text: Align doesn't apply, letters stay centred on the column.
        tool.toggleVertical()
        SmokeUi.settle()
        assertTrue(SmokeUi.has(LETTER_SCALING_VERTICAL, exact = true))
        assertFalse("Align is disabled", SmokeUi.isEnabled("Center", exact = true))
        tool.toggleVertical()
        SmokeUi.settle()
        assertTrue(SmokeUi.isEnabled("Center", exact = true))
        // A right-to-left text is drawn unscaled, and the sheet says so.
        tool.setText("مرحبا")
        SmokeUi.settle()
        assertTrue(SmokeUi.has(LETTER_SCALING_LTR_ONLY, exact = true))
        tool.setText("ELTON JOHN")
        SmokeUi.settle()
        assertFalse(SmokeUi.has(LETTER_SCALING_LTR_ONLY, exact = true))
        // Off with the switch: 100 %.
        SmokeUi.click("Scale letters", exact = true)
        assertFalse(tool.item!!.spec.letterScale.isOn)
        SmokeUi.click("Scale letters", exact = true)
        assertTrue(tool.item!!.spec.letterScale.isOn)

        // ✓: one step, the layer keeps the scaled text.
        tool.commit()
        SmokeUi.settle()
        assertFalse(tool.lettersSheetOpen)
        assertEquals(1, c.undoManager.undoCount)
        val layer = c.doc.layers.last { it.isTextLayer }
        val stored = TextCodec.decode(layer.textData)!!
        assertTrue(stored.spec.letterScale.isOn)
        assertEquals(LetterScaleAlign.TOP, stored.spec.letterScale.align)

        // The chip on an active text layer opens it for editing with the sheet.
        SmokeUi.click("Letter scaling", exact = true)
        assertTrue(tool.lettersSheetOpen)
        assertTrue(tool.editingLayer === layer)
        tool.discard()
        SmokeUi.settle()
        Smoke.assertQuiet(c, "letter scaling")
    }
}
