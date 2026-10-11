package com.brushwork.paint.fxsmoke

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.color.RobolectricUi.byDescription
import com.brushwork.paint.ui.color.RobolectricUi.byText
import com.brushwork.paint.ui.color.RobolectricUi.elements
import com.brushwork.paint.ui.color.RobolectricUi.hasText
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.8 (item 5, design §3.6): the fringe colors and the static's color picked on the real filter
 * panel ([FilterSessionPanel], hosted as EditorScreen hosts it) on a 360 dp phone, end to end:
 * Chromatic aberration (Moving) with a preset chip (the fourth, reached by sliding the chip row)
 * and a middle swatch picked through the unchanged color picker (a typed hex), then Glitch with a
 * preset and a static color picked the same way. Each apply is the filter's own result on the
 * layer (the general path: custom colors) and ONE undo step named after the filter; undo and redo
 * restore each state exactly.
 *
 * Own sandbox (see ColorPickerUiSmokeTest): Compose frames stall in later tests of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.fxsmoke.fringesandbox"])
class FringePanelSessionUiTest {

    private val rs = RecordingScope()

    @After
    fun tearDown() = rs.close()

    /**
     * The element [find] returns, after scrolling its scrolling ancestors (the panel's column, the
     * chip row) until it is fully inside them, like a user would (as FilterPanelInteractionTest).
     */
    private fun visible(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(6) {
            val el = find()
            val pos = el.node.positionInWindow
            val l = pos.x; val t = pos.y; val r = l + el.node.size.width; val b = t + el.node.size.height
            val scrollers = generateSequence(el.node.parent) { it.parent }
                .filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.toList().asReversed()
            for (n in scrollers) {
                val p = n.positionInWindow
                val vl = p.x; val vt = p.y; val vr = vl + n.size.width; val vb = vt + n.size.height
                val horizontal = n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                val delta = if (horizontal) {
                    when { r > vr -> r - vr + 8f; l < vl -> l - vl - 8f; else -> 0f }
                } else {
                    when { b > vb -> b - vb + 8f; t < vt -> t - vt - 8f; else -> 0f }
                }
                if (delta != 0f) {
                    val scroll = n.config[SemanticsActions.ScrollBy].action!!
                    if (horizontal) scroll(delta, 0f) else scroll(0f, delta)
                    settle(2, 50)
                    return@repeat
                }
            }
            return el
        }
        return find()
    }

    private fun tagged(tag: String): RobolectricUi.Element =
        elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag } ?: throw AssertionError("nothing tagged \"$tag\"")

    private fun chip(name: String) = tagged(V18Tags.FRINGE_PREFIX + name)

    /** The clickable element on the same row as [label], to its right (a color swatch). */
    private fun clickableRightOf(label: RobolectricUi.Element): RobolectricUi.Element {
        val row = label.bounds
        return elements().filter { e ->
            e.node.config.getOrNull(SemanticsActions.OnClick) != null &&
                e.bounds.left > row.right && e.bounds.center.y > row.top - 40 && e.bounds.center.y < row.bottom + 40
        }.minByOrNull { it.bounds.left } ?: throw AssertionError("nothing clickable right of the label")
    }

    /** In the open color picker: types [hex] (six digits) and taps OK. */
    private fun pickHex(hex: String) {
        assertTrue("the color picker is open", hasText("OK"))
        RobolectricUi.textFields().first().type(hex)
        settle(2)
        byText("OK").tap()
        settle(3)
        assertFalse("the color picker closed", hasText("OK"))
    }

    private fun session(c: com.brushwork.paint.EditorController, f: Filter): FilterSession {
        val s = c.filterSession ?: throw AssertionError("no session for ${f.id} (${c.message})")
        assertEquals(f.id, s.filter.id)
        return s
    }

    /** What [f] gives on the layer for [values], read back through a bitmap (as the apply writes it). */
    private fun direct(d: Fx.TestDoc, f: Filter, values: FilterValues): IntArray =
        Fx.roundTrip(Fx.direct(f, BitmapUtils.toPixelBuffer(d.paint.bitmap), values, 1f, d.controller.doc.dpi))

    @Test
    fun fringeAndStaticColorsPickedOnThePanelApplyAsOneUndoStepEach() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val d = Fx.newDoc(activity.applicationContext, rs.scope, 96, 72)
        val c = d.controller
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    val s = c.filterSession
                    if (s != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { FilterSessionPanel(s, Modifier.fillMaxWidth()) }
                }
            }
        }
        settle()
        val original = Fx.pixels(d.paint.bitmap)

        // ---------------------------------------------------------------- Chromatic aberration (Moving)
        val moving = FilterRegistry.byId("art.chromatic_aberration_moving") ?: throw AssertionError("Moving is registered")
        c.startFilter(moving)
        var s = session(c, moving)
        Fx.awaitPreview(s, "moving")
        settle(3)
        assertTrue(hasText(FringeLabels18.FRINGE_COLORS))
        assertArrayEquals(FringeColors.rgb, s.values.colors(FringeColors.KEY))
        // The fourth preset, past the chip row's edge on a 360 dp phone, is reached by sliding the row.
        visible { chip(FringeLabels18.BLUE_YELLOW) }.tap()
        assertArrayEquals(intArrayOf(FringeColors.BLUE, FringeColors.NONE, FringeColors.YELLOW), s.values.colors(FringeColors.KEY))
        // Reset to defaults brings RGB back; the chip again.
        byDescription("Reset to defaults").tap()
        settle(3)
        assertArrayEquals(FringeColors.rgb, s.values.colors(FringeColors.KEY))
        visible { chip(FringeLabels18.BLUE_YELLOW) }.tap()
        // The middle swatch (no color) opens the picker; a typed orange is picked.
        visible { byDescription(FringeLabels18.MIDDLE) }.tap()
        settle(3)
        pickHex("FF8000")
        val custom = intArrayOf(FringeColors.BLUE, 0xFFFF8000.toInt(), FringeColors.YELLOW)
        assertArrayEquals(custom, s.values.colors(FringeColors.KEY))
        assertNull("custom colors take the general path", FringeColors.oneHotFactors(custom))
        Fx.awaitPreview(s, "moving, custom colors")
        val movingValues = s.values.copy()
        val expectedMoving = direct(d, moving, movingValues)
        assertFalse("the colors reach the result", expectedMoving.contentEquals(direct(d, moving, movingValues.copy().set(FringeColors.KEY, FringeColors.rgb))))

        byDescription("Apply filter").tap()
        Fx.waitUntil("apply of Moving") { s.isClosed && c.busyMessage == null }
        settle(3)
        assertNull(c.filterSession)
        assertEquals("one undo step", 1, c.undoManager.undoCount)
        assertEquals(moving.name, c.undoManager.undoLabel)
        val afterMoving = Fx.pixels(d.paint.bitmap)
        assertTrue("applied != the filter's result: ${Fx.diff(expectedMoving, afterMoving, 96)}", expectedMoving.contentEquals(afterMoving))

        // ---------------------------------------------------------------- Glitch
        val glitch = FilterRegistry.byId("art.glitch") ?: throw AssertionError("Glitch is registered")
        c.startFilter(glitch)
        s = session(c, glitch)
        // Small bands, a fixed seed and full static: every kind of band shows on 72 rows.
        s.update("height", 6f); s.update("noise", 100f); s.update("seed", 4242)
        Fx.awaitPreview(s, "glitch")
        settle(3)
        visible { chip(FringeLabels18.MAGENTA_GREEN) }.tap()
        assertArrayEquals(intArrayOf(FringeColors.MAGENTA, FringeColors.NONE, FringeColors.GREEN), s.values.colors(FringeColors.KEY))
        // The static's swatch (right of its label) opens the picker; a typed red is picked.
        clickableRightOf(visible { byText(FringeLabels18.STATIC_COLOR) }).tap()
        settle(3)
        pickHex("FF2040")
        assertEquals(0xFFFF2040.toInt(), s.values.color(GlitchFilter.STATIC_COLOR_KEY))
        assertTrue("the panel shows the static's color", hasText(ColorUtils.toHex(0xFFFF2040.toInt())))
        Fx.awaitPreview(s, "glitch, colors")
        val glitchValues = s.values.copy()
        val expectedGlitch = direct(d, glitch, glitchValues)
        assertFalse("the fringes reach the result", expectedGlitch.contentEquals(direct(d, glitch, glitchValues.copy().set(FringeColors.KEY, FringeColors.rgb))))
        assertFalse("the static's color reaches the result", expectedGlitch.contentEquals(direct(d, glitch, glitchValues.copy().set(GlitchFilter.STATIC_COLOR_KEY, FringeColors.WHITE))))

        byDescription("Apply filter").tap()
        Fx.waitUntil("apply of Glitch") { s.isClosed && c.busyMessage == null }
        settle(3)
        assertEquals("one more undo step", 2, c.undoManager.undoCount)
        assertEquals(glitch.name, c.undoManager.undoLabel)
        val afterGlitch = Fx.pixels(d.paint.bitmap)
        assertTrue("applied != the filter's result: ${Fx.diff(expectedGlitch, afterGlitch, 96)}", expectedGlitch.contentEquals(afterGlitch))

        // ---------------------------------------------------------------- undo and redo, one step each
        c.undo()
        assertTrue("undo restores Moving's result", Fx.pixels(d.paint.bitmap).contentEquals(afterMoving))
        assertEquals(moving.name, c.undoManager.undoLabel)
        c.undo()
        assertTrue("undo restores the original", Fx.pixels(d.paint.bitmap).contentEquals(original))
        assertFalse(c.canUndo)
        c.redo()
        assertTrue("redo gives Moving's result", Fx.pixels(d.paint.bitmap).contentEquals(afterMoving))
        c.redo()
        assertTrue("redo gives Glitch's result", Fx.pixels(d.paint.bitmap).contentEquals(afterGlitch))
        assertEquals(2, c.undoManager.undoCount)
        Fx.assertNoErrorMessage(c, "fringe panel")
        rs.assertNoErrors("fringe panel")
    }
}
