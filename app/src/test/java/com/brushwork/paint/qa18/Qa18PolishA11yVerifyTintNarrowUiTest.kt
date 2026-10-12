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
import com.brushwork.paint.vector.VStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish, verifier (item 2) on a narrow phone (360 dp): the Array sheet's Look tab, its
 * "Tint colors" stops, through Undo AND Redo (the fixer's 392 dp test stops at the Undo).
 *
 * - After "Sunset", each stop's node lies over its marker, wholly on the phone, named
 *   "Tint color k of 4, #RRGGBB".
 * - A screen reader's click on stop 3 and a typed color: ONE step "Array look", its name says
 *   the new color. Undo (two fingers): the Sunset stops and names. Redo (three fingers): the new
 *   color and its name again, the step back.
 * A render of the bar after the Redo.
 *
 * One UI test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polisha11yverifytintsandbox"])
class Qa18PolishA11yVerifyTintNarrowUiTest {

    @Test
    fun theTintStopsNamesFollowUndoAndRedoAt360Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("the Tint colors stops through Undo and Redo at 360 dp") { flow(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    private fun description(e: RobolectricUi.Element): String? = e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    private fun stops(): List<RobolectricUi.Element> =
        RobolectricUi.elements().filter { e -> description(e)?.startsWith("Tint color ") == true }.sortedBy { it.bounds.left }

    private fun names(t: List<VStop>): List<String> = t.mapIndexed { i, s -> ArrayLabels18.tintColor(i + 1, t.size, ColorUtils.toHex(s.color)) }

    private fun flow(u: Qa17ArrayUi) {
        u.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true), widthDp = 360f)
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
        u.fingerPress("Sunset")
        u.settleRenders("Sunset")
        val sunset = layer.array!!.spec.look!!.tint
        u.intoView(GRADIENT) { requireNotNull(SmokeUi.find(GRADIENT, exact = true)) { "no \"$GRADIENT\"" }.node }
        assertEquals("one node per stop", names(sunset), stops().map { description(it) })
        val bar = requireNotNull(SmokeUi.find(GRADIENT, exact = true)).node.boundsInWindow
        val density = u.s.density
        val width = u.s.activity.window.decorView.width
        for ((i, e) in stops().withIndex()) {
            assertEquals("stop ${i + 1} over its marker at 360 dp", bar.left + 14f * density + sunset[i].offset * (bar.width - 28f * density), e.bounds.center.x, 1.5f * density)
            assertTrue("stop ${i + 1} on the phone: ${e.bounds}", e.bounds.left >= 0f && e.bounds.right <= width + 0.5f)
        }

        // A screen reader's click on stop 3, a color typed: ONE step.
        val n = u.steps()
        requireNotNull(stops()[2].node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        settle(4)
        assertEquals("the click selects stop 3", listOf(false, false, true, false), stops().map { it.node.config.getOrNull(SemanticsProperties.Selected) })
        SmokeUi.field("Hex").type("20C040")
        settle(2)
        SmokeUi.click("OK", exact = true)
        u.settleRenders("the recolor")
        assertEquals("ONE step", n + 1, u.steps())
        assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
        val recolored = layer.array!!.spec.look!!.tint
        assertEquals(0xFF20C040.toInt(), recolored[2].color)
        assertEquals("the new name", names(recolored), stops().map { description(it) })

        // Undo: Sunset and its names; Redo: the new color and its name.
        u.ui.twoFingerUndo()
        u.settleRenders("undo")
        assertEquals(n, u.steps())
        assertEquals("the Sunset stops", sunset, layer.array!!.spec.look!!.tint)
        assertEquals("the Sunset names", names(sunset), stops().map { description(it) })
        u.ui.threeFingerRedo()
        u.settleRenders("redo")
        assertEquals("the step back", n + 1, u.steps())
        assertEquals("the recolor again", recolored, layer.array!!.spec.look!!.tint)
        assertEquals("its name again", names(recolored), stops().map { description(it) })
        assertEquals(ArrayLabels18.tintColor(3, 4, "#20C040"), description(stops()[2]))
        val v = u.s.activity.window.decorView
        val shot = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(shot))
        Qa18CurvesShots.save(shot, "polish-a11y-verify-tint-redo-360.png")
        Smoke.assertQuiet(c, "the tint stops through undo and redo")
    }

    private companion object {
        const val GRADIENT = "Gradient editor"
    }
}
