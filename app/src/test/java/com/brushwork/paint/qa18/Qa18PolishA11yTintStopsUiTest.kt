package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.RED
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.filters.GradientEditing
import com.brushwork.paint.vector.VStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (accessibility, item 2) on the user's phone (392 dp): the Array sheet's Look tab,
 * its "Tint colors" bar, as a screen reader reads it, and by finger.
 *
 * - After the "Sunset" preset (one step "Array look"), each stop is a node over its marker named
 *   "Tint color k of 4, #RRGGBB" (k from the left, its color's hex), none Selected; the section's
 *   header "Tint colors" is still the only text that holds it.
 * - A finger on stop 2's marker selects it (that node alone Selected) and opens the "Stop color"
 *   picker, as before; "Cancel": nothing changes, no step.
 * - A screen reader's click on stop 4 selects it and opens its picker; a color typed and "OK"
 *   recolor that stop alone, ONE step "Array look", and its name says the new color. One undo
 *   (two fingers) restores the Sunset stops exactly, and the name.
 * - A render of the bar with a stop selected for the lead.
 *
 * One UI test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polisha11ytintstopssandbox"])
class Qa18PolishA11yTintStopsUiTest {

    @Test
    fun theTintColorsStopsAreNamedSelectedAndRecoloredInOneStep() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("the Look tab's Tint colors stops at 392 dp") { flow(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    private fun description(e: RobolectricUi.Element): String? = e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    /** The bar's stop nodes, left to right. */
    private fun stops(): List<RobolectricUi.Element> =
        RobolectricUi.elements().filter { e -> description(e)?.startsWith("Tint color ") == true }.sortedBy { it.bounds.left }

    private fun selection(): List<Boolean?> = stops().map { it.node.config.getOrNull(SemanticsProperties.Selected) }

    private fun names(t: List<VStop>): List<String> = t.mapIndexed { i, s -> ArrayLabels18.tintColor(i + 1, t.size, ColorUtils.toHex(s.color)) }

    private fun flow(u: Qa17ArrayUi) {
        u.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), widthDp = 392f)
        val c = u.c
        u.seed(c.activeLayer, 40f, 40f, 90f, 90f, RED)
        c.setSelection(u.rectSelection(36f, 36f, 94f, 94f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        val layer = c.activeLayer
        u.showArraySheet()
        u.press(ArrayLabels18.LOOK, 44f)

        // The Sunset preset by finger: one step; its four stops named from the left.
        var n = u.steps()
        u.fingerPress(SUNSET)
        u.settleRenders("Sunset")
        assertEquals("the preset: one step", n + 1, u.steps())
        val sunset = layer.array!!.spec.look!!.tint
        assertEquals("the preset's stops", GradientEditing.sorted(GradientEditing.presets.first { it.first == SUNSET }.second).map { it.position to it.color }, sunset.map { it.offset to it.color })
        u.intoView(GRADIENT) { requireNotNull(SmokeUi.find(GRADIENT, exact = true)) { "no \"$GRADIENT\"" }.node }
        assertEquals("one node per stop, named from the left", names(sunset), stops().map { description(it) })
        assertEquals("none selected", List(4) { false }, selection())
        assertTrue("each stop is clickable for a screen reader", stops().all { it.node.config.getOrNull(SemanticsActions.OnClick) != null })
        val bar = requireNotNull(SmokeUi.find(GRADIENT, exact = true)).node.boundsInWindow
        val density = u.s.density
        for ((i, e) in stops().withIndex()) {
            assertEquals("stop ${i + 1} over its marker", bar.left + 14f * density + sunset[i].offset * (bar.width - 28f * density), e.bounds.center.x, 1.5f * density)
            assertTrue("stop ${i + 1} under the bar", e.bounds.top >= bar.top + 34f * density && e.bounds.bottom <= bar.bottom + 0.5f)
        }
        // The section's header is unchanged (shown in capitals), and no stop's name holds "Tint colors".
        assertEquals("the header once", 1, SmokeUi.shown().count { it == ArrayLabels18.TINT_COLORS.uppercase() })
        assertTrue("no name holds the header's label", stops().none { description(it)!!.contains(ArrayLabels18.TINT_COLORS, ignoreCase = true) })

        // A finger on stop 2's marker: selected, its picker; Cancel changes nothing.
        n = u.steps()
        stops()[1].tap()
        settle(4)
        assertEquals("a finger selects stop 2", listOf(false, true, false, false), selection())
        assertTrue("the stop's color picker", SmokeUi.has("Stop color", exact = true))
        RobolectricUi.byText("Cancel").tap()
        settle(4)
        assertFalse("the picker closed", SmokeUi.has("Stop color", exact = true))
        assertEquals("no step", n, u.steps())
        assertEquals("the stops kept", sunset, layer.array!!.spec.look!!.tint)
        val v = u.s.activity.window.decorView
        val shot = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(shot))
        Qa18CurvesShots.save(shot, "polish-a11y-tint-stop-selected.png")

        // A screen reader's click on stop 4: selected, its picker; a color typed: one step.
        requireNotNull(stops()[3].node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        settle(4)
        assertEquals("the click selects stop 4", listOf(false, false, false, true), selection())
        SmokeUi.field("Hex").type("3366CC")
        settle(2)
        SmokeUi.click("OK", exact = true)
        u.settleRenders("the recolor")
        assertEquals("the recolor: ONE step", n + 1, u.steps())
        assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
        val recolored = layer.array!!.spec.look!!.tint
        assertEquals("stop 4 recolored", 0xFF3366CC.toInt(), recolored[3].color)
        assertEquals("the others kept", sunset.take(3), recolored.take(3))
        assertEquals("its name says the new color", ArrayLabels18.tintColor(4, 4, "#3366CC"), description(stops()[3]))
        assertNotNull(stops().singleOrNull { description(it) == ArrayLabels18.tintColor(4, 4, "#3366CC") })

        // One undo restores the Sunset stops exactly.
        u.ui.twoFingerUndo()
        u.settleRenders("undo")
        assertEquals("one undo", n, u.steps())
        assertEquals("the Sunset stops again", sunset, layer.array!!.spec.look!!.tint)
        assertEquals("their names again", names(sunset), stops().map { description(it) })
        Smoke.assertQuiet(c, "the tint stops' names")
    }

    private companion object {
        /** The filters' gradient editor's spoken name (v1.7). */
        const val GRADIENT = "Gradient editor"

        /** A Tint colors preset (four stops). */
        const val SUNSET = "Sunset"
    }
}
