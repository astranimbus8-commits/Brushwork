package com.brushwork.paint.qa110

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 QA (R5, A3; I27): an array's colors recolored in its Look tab, at 392 and 360 dp and at
 * each font scale, by the labels the user sees. An array made from a two-tone selection (the
 * selection bar's "Array"); in the sheet's Look tab [ArrayLabels18.TINT_STRENGTH] typed 100 gives
 * the copies v1.9's default tint (ONE step [ArrayLabels18.HISTORY_LOOK]); then
 * [ArrayLabels110.RECOLORED] (in the row tagged [V110Tags.ARRAY_COLOR_MODE]) is ONE step
 * [ArrayLabels18.HISTORY_LOOK] that sets [ArrayColorMode.RECOLOR], renames the strength field
 * [ArrayLabels110.RECOLOR_STRENGTH] and redraws the copies in other colors; [ArrayLabels110.TINTED]
 * is one step back to the tinted pixels, byte for byte; Undo gives the recolored look and its
 * pixels again, and a second Undo the tinted ones. The source keeps its pixels throughout (I19),
 * and each screen's texts stay inside their boxes ([Qa19Ui.assertNoTextOverflow]). The colors
 * themselves are `ArrayRecolorMatchesRecolorTest`'s.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.arrayrecolorsandbox"])
class Qa110ArrayRecolorUiTest {

    @Test
    fun anArraysColorsRecoloredInItsLookTab() = Qa19Ui.uiTest { h ->
        for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val dp = if (width == Qa19Ui.Q360) 360 else 392
            val where = "at $dp dp and font scale $scale"
            h.section(where) {
                RuntimeEnvironment.setQualifiers(width)
                val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), fontScale = scale) { c ->
                    c.snapping.enabled = false
                    val src = c.doc.layers[1]
                    Canvas(src.bitmap).apply {
                        drawRect(40f, 40f, 80f, 80f, Paint().apply { color = LIGHT })
                        drawRect(50f, 50f, 70f, 70f, Paint().apply { color = DARK })
                    }
                    c.selectLayer(src)
                    c.setSelection(ArrayLookKit.rectSelection(c, 36f, 36f, 84f, 84f), recordUndo = false)
                }
                assertEquals("$where: the phone's width", dp, s.activity.resources.configuration.screenWidthDp)
                recolor(s, where)
            }
        }
    }

    private lateinit var layer: Layer

    private val look get() = layer.array!!.spec.look

    /** Renders and the tool's work landed, the screen settled. */
    private fun landed(c: EditorController, where: String) {
        assertTrue("$where: the work landed", Smoke.pumpUntil { Qa19Ui.settle(1); !c.vectors.isRendering && !c.arrayRenders.isPending && c.busyMessage == null })
        Qa19Ui.settle()
    }

    /** Runs [block] and asserts it recorded ONE step [ArrayLabels18.HISTORY_LOOK]. */
    private fun lookStep(c: EditorController, where: String, block: () -> Unit) {
        val steps = c.undoManager.undoCount
        block()
        landed(c, where)
        assertEquals("$where: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(where, ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
    }

    /** The array layer's pixels. */
    private fun pixels(): IntArray {
        val b = layer.bitmap
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun recolor(s: ChromeScreen, where: String) {
        val c = s.c
        val ui = Qa16Ui(s)

        // The array, from the selection bar's "Array" (its description: "Array from selection").
        ui.reach(ArrayLabels.FROM_SELECTION)
        Qa19Ui.click(ArrayLabels.FROM_SELECTION, exact = true)
        landed(c, "$where, the array")
        c.setSelection(null, recordUndo = false)
        Qa19Ui.settle()
        assertEquals("$where: in the Array tool", ToolId.ARRAY, c.activeToolId)
        layer = c.activeLayer
        assertNotNull("$where: a live array", layer.array)
        assertNull("$where: no look yet", look)
        assertNotNull("$where: the sheet opens", s.tagged(V17Tags.ARRAY_SHEET))
        val plain = pixels()

        // The Look tab: the tint at full strength (v1.9's default colors).
        ui.reach(ArrayLabels18.LOOK, 44f)
        Qa19Ui.click(ArrayLabels18.LOOK, exact = true)
        assertNotNull("$where: the color mode row", s.tagged(V110Tags.ARRAY_COLOR_MODE))
        lookStep(c, "$where, Tint strength 100") { typeField(s, ArrayLabels18.TINT_STRENGTH, "100") }
        assertEquals("$where: tinted", ArrayColorMode.TINT, look!!.colorMode)
        assertEquals("$where: full strength", 1f, look!!.tintStrength, 1e-4f)
        assertTrue("$where: the default tint colors", look!!.tint.isNotEmpty())
        val tinted = pixels()
        assertFalse("$where: the tint redraws the copies", tinted.contentEquals(plain))
        Qa19Ui.assertNoTextOverflow("$where, the Look tab, tinted")

        // Recolored: one step, the field renamed, other pixels.
        ui.reach(ArrayLabels110.RECOLORED, 48f)
        lookStep(c, "$where, ${ArrayLabels110.RECOLORED}") { Qa19Ui.click(ArrayLabels110.RECOLORED, exact = true) }
        assertEquals("$where: recolored", ArrayColorMode.RECOLOR, look!!.colorMode)
        assertEquals("$where: the strength kept", 1f, look!!.tintStrength, 1e-4f)
        assertTrue("$where: the field renamed", Qa19Ui.has(ArrayLabels110.RECOLOR_STRENGTH, exact = true))
        assertFalse("$where: the tint's name gone", Qa19Ui.has(ArrayLabels18.TINT_STRENGTH, exact = true))
        val recolored = pixels()
        assertFalse("$where: recoloring redraws the copies", recolored.contentEquals(tinted))
        Qa19Ui.assertNoTextOverflow("$where, the Look tab, recolored")

        // Tinted again: one step, the tinted pixels byte for byte.
        ui.reach(ArrayLabels110.TINTED, 48f)
        lookStep(c, "$where, ${ArrayLabels110.TINTED}") { Qa19Ui.click(ArrayLabels110.TINTED, exact = true) }
        assertEquals("$where: tinted again", ArrayColorMode.TINT, look!!.colorMode)
        assertTrue("$where: the field's own name back", Qa19Ui.has(ArrayLabels18.TINT_STRENGTH, exact = true))
        assertTrue("$where: the tinted pixels again", pixels().contentEquals(tinted))

        // Undo: recolored again, then tinted.
        c.undo()
        landed(c, "$where, undo")
        assertEquals("$where: undo recolors", ArrayColorMode.RECOLOR, look!!.colorMode)
        assertTrue("$where: undo: the recolored pixels", pixels().contentEquals(recolored))
        assertTrue("$where: undo: the field renamed", Qa19Ui.has(ArrayLabels110.RECOLOR_STRENGTH, exact = true))
        c.undo()
        landed(c, "$where, undo twice")
        assertEquals("$where: undo twice: tinted", ArrayColorMode.TINT, look!!.colorMode)
        assertTrue("$where: undo twice: the tinted pixels", pixels().contentEquals(tinted))
        assertEquals("$where: still in the Array tool", ToolId.ARRAY, c.activeToolId)
        Smoke.assertQuiet(c, where)
        ArrayDraw.clearCaches()
    }

    /**
     * Types [value] into the number field [label] like a user: the field scrolled wholly into
     * view in its sheet (60 dp at a time, as a finger scrolls), then focus, text and Done.
     */
    private fun typeField(s: ChromeScreen, label: String, value: String) {
        repeat(40) {
            val n = SmokeUi.field(label).node
            if (whole(s, n)) {
                SmokeUi.typeAndDone(label, value)
                return
            }
            var p = n.parent
            while (p != null && (p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null || p.config.getOrNull(SemanticsActions.ScrollBy) == null)) p = p.parent
            val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is cut and nothing scrolls it" }
            val top = n.positionInWindow.y
            scroll.invoke(0f, if (top < p!!.boundsInWindow.top) -60f * s.density else 60f * s.density)
            Qa19Ui.settle(2)
        }
        throw AssertionError("\"$label\" never came wholly into view")
    }

    /** Whether [n] shows whole: not cut by a scrolling parent, inside the screen. */
    private fun whole(s: ChromeScreen, n: SemanticsNode): Boolean {
        val b = n.boundsInWindow
        return b.height >= n.size.height - 1f && b.top >= s.root.top && b.bottom <= s.root.bottom
    }

    private companion object {
        /** The square's light ring and its dark centre (two tones for the recolor to keep apart). */
        const val LIGHT = 0xFF4FA3E0.toInt()
        const val DARK = 0xFF1C3A73.toInt()
    }
}
