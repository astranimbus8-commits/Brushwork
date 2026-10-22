package com.brushwork.paint.v110.a3

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 (R4, R5, I27; A3 review) at 392 and 360 dp, each at font scale 1, 1.3 and 2.0: the two
 * texts A3 adds to the Array sheet keep inside their boxes. On a copies-only layer the footer's
 * [ArrayLabels110.REMOVE_WITH_LAYER] and "Apply array" are both wholly on screen, at least 44 dp,
 * the remove words on one line at font scale 1 (also at 360 dp), and no "Edit source pixels".
 * In the Look tab the [ArrayLabels110.TINTED] | [ArrayLabels110.RECOLORED] segments (tag
 * [V110Tags.ARRAY_COLOR_MODE]) are one row across the sheet, each at least 48 dp, whose words wrap
 * (never cut) at 2.0; [ArrayLabels110.RECOLORED] tapped renames the strength field
 * [ArrayLabels110.RECOLOR_STRENGTH], which fits too. The entries themselves and their steps are
 * `ArrayBehindEntriesUiTest`'s (392 dp); the full walk at both widths is step 7's
 * `Qa110ArrayBehindUiTest` and `Qa110ArrayRecolorUiTest`.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a3.fontscalesandbox"])
class ArrayBehindFontScaleUiTest {

    @Test
    fun theSheetTextsAtBothWidthsAndEveryFontScale() = Qa19Ui.uiTest { h ->
        for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val dp = width.substringBefore("dp").removePrefix("w").toFloat()
            val where = "at $dp dp and font scale $scale"
            h.section(where) {
                RuntimeEnvironment.setQualifiers(width)
                val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), fontScale = scale) { c ->
                    c.snapping.enabled = false
                    val src = c.doc.layers[1]
                    Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = 0xFF2244CC.toInt() })
                    c.selectLayer(src)
                    c.setSelection(ArrayLookKit.rectSelection(c, 20f, 30f, 60f, 70f), recordUndo = false)
                }
                assertEquals("the phone is $dp dp wide", dp, s.widthDp, 1f)
                sheetTexts(s, where, scale)
            }
        }
    }

    private fun sheetTexts(s: ChromeScreen, where: String, scale: Float) {
        val c = s.c
        val ui = Qa16Ui(s)
        assertTrue(ArrayBehind.fromSelectionBelow(c))
        ArrayLookKit.settle(c)
        SmokeUi.settle()
        val made = c.activeLayer
        assertTrue(ArrayBehind.isCopiesOnly(made))
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertNotNull("$where: the sheet opens", s.tagged(V17Tags.ARRAY_SHEET))

        // The footer: both buttons whole, the remove words on one line at font scale 1.
        assertTrue("$where: \"${ArrayLabels110.REMOVE_WITH_LAYER}\" wholly on screen", ui.wholly(ArrayLabels110.REMOVE_WITH_LAYER, 44f))
        assertTrue("$where: \"${ArrayLabels.APPLY}\" wholly on screen", ui.wholly(ArrayLabels.APPLY, 44f))
        val remove = requireNotNull(Qa19Ui.textLayout(textNode(ArrayLabels110.REMOVE_WITH_LAYER))) { "no layout of the remove words" }
        if (scale == 1f) assertEquals("$where: the remove words on one line", 1, remove.lineCount)
        assertFalse("$where: no source edit on a copies-only layer", SmokeUi.has(ArrayLabels.EDIT_SOURCE, exact = true))
        noOverflowInSheet("$where, the Placement tab")

        // The Look tab: the two segments, one row across the sheet, 48 dp; the words wrap, never cut.
        ui.reach(ArrayLabels18.LOOK, 44f)
        SmokeUi.click(ArrayLabels18.LOOK, exact = true)
        SmokeUi.settle(4)
        for (w in listOf(ArrayLabels110.TINTED, ArrayLabels110.RECOLORED)) {
            ui.reach(w, 48f)
            assertTrue("$where: \"$w\" wholly on screen, at least 48 dp", ui.wholly(w, 48f))
        }
        val row = requireNotNull(s.tagged(V110Tags.ARRAY_COLOR_MODE)) { "$where: no color mode row" }
        val sheet = requireNotNull(s.tagged(V17Tags.ARRAY_SHEET))
        assertTrue("$where: the row spans the sheet: ${row.width} of ${sheet.width} dp", row.width >= sheet.width - 48f)
        val tinted = textNode(ArrayLabels110.TINTED).boundsInWindow
        val recolored = textNode(ArrayLabels110.RECOLORED).boundsInWindow
        assertTrue("$where: one row", recolored.top < tinted.bottom && tinted.top < recolored.bottom)
        noOverflowInSheet("$where, the Look tab")

        SmokeUi.click(ArrayLabels110.RECOLORED, exact = true)
        SmokeUi.settle(4)
        assertEquals("$where: a mode alone waits", ArrayColorMode.RECOLOR, (c.tools.getValue(ToolId.ARRAY) as com.brushwork.paint.tools.array.ArrayTool).plainColorMode)
        assertTrue("$where: the strength field renamed", SmokeUi.has(ArrayLabels110.RECOLOR_STRENGTH, exact = true))
        noOverflowInSheet("$where, Recolored")
        Smoke.assertQuiet(c, where)
        ArrayDraw.clearCaches()
    }

    /**
     * [Qa19Ui.textOverflows] for the texts inside the Array sheet only: the selection stays on the
     * main layer, so the lead's selection bar shows above the sheet, and its texts are checked by
     * its own tests.
     */
    private fun noOverflowInSheet(where: String) {
        val sheet = requireNotNull(RobolectricUi.elements().lastOrNull { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == V17Tags.ARRAY_SHEET }) { "$where: no sheet" }.bounds
        val bad = Qa19Ui.textOverflows { e -> sheet.contains(e.bounds.center) }
        assertTrue("texts overflow their box $where:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** The text node showing exactly [label] (the unmerged tree), for its layout. */
    private fun textNode(label: String): SemanticsNode {
        val n = requireNotNull(SmokeUi.find(label, exact = true)?.node) { "no \"$label\"; shown: ${SmokeUi.shown().take(60)}" }
        fun withText(x: SemanticsNode): SemanticsNode? =
            if (x.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == label) x else x.children.firstNotNullOfOrNull(::withText)
        return withText(n) ?: n
    }
}
