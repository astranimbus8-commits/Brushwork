package com.brushwork.paint.qa18

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
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.fxsmoke.RecordingScope
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.color.RobolectricUi.byDescription
import com.brushwork.paint.ui.color.RobolectricUi.byText
import com.brushwork.paint.ui.color.RobolectricUi.elements
import com.brushwork.paint.ui.color.RobolectricUi.hasText
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.8 QA (item 5, design §3.6; accessibility): a filter panel's color swatch (`ColorControl`,
 * Glitch's "Color of the static" and a v1.7 one, Glow (Outer)'s "Color") is a button TalkBack
 * names: "Choose " + its label in lower case, its color (hex) as its state. The name is no
 * substring of another text on the panel, nor has one (SmokeUi's case-sensitive `contains`), so
 * the label's text and the swatch are each reached alone. Found by that name, a tap opens the
 * color picker titled after the label; the picked color is the parameter's and the new state.
 *
 * One test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.lookswatchsandbox"])
class Qa18LookSwatchA11yTest {

    private val rs = RecordingScope()

    @After
    fun tearDown() = rs.close()

    /** [find]'s element after scrolling the panel's column until it is wholly inside (as a user would). */
    private fun visible(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(6) {
            val el = find()
            val t = el.node.positionInWindow.y
            val b = t + el.node.size.height
            val scroller = generateSequence(el.node.parent) { it.parent }
                .firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null && it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) == null }
                ?: return el
            val vt = scroller.positionInWindow.y
            val vb = vt + scroller.size.height
            val delta = when { b > vb -> b - vb + 8f; t < vt -> t - vt - 8f; else -> 0f }
            if (delta == 0f) return el
            scroller.config[SemanticsActions.ScrollBy].action!!(0f, delta)
            settle(2, 50)
        }
        return find()
    }

    /** Every text and description on screen, one entry per place it shows. */
    private fun shown(): List<String> = elements().flatMap { e ->
        e.node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            e.node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.isNotBlank() }

    /** The element described exactly [name]. */
    private fun described(name: String): RobolectricUi.Element =
        elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true }
            ?: throw AssertionError("nothing described \"$name\" (the swatch has no description)")

    @Test
    fun theColorSwatchSaysWhatItPicksAndOpensThePicker() {
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

        // (filter, the Color parameter's key, the color typed in the picker)
        val cases = listOf(
            Triple("art.glitch", GlitchFilter.STATIC_COLOR_KEY, "FF2040"),
            Triple("style.glow_outer", "color", "20C040"),
        )
        for ((id, key, hex) in cases) {
            val f = FilterRegistry.byId(id) ?: throw AssertionError("$id is registered")
            val p = f.params.single { it.key == key } as FilterParam.Color
            if (f is GlitchFilter) assertEquals(FringeLabels18.STATIC_COLOR, p.label)
            c.startFilter(f)
            val s = c.filterSession ?: throw AssertionError("no session for $id (${c.message})")
            Fx.awaitPreview(s, id)
            settle(3)

            val name = "Choose ${p.label.lowercase()}"
            val swatch = visible { described(name) }
            assertNotNull("$id: the swatch is a button", swatch.node.config.getOrNull(SemanticsActions.OnClick))
            assertEquals("$id: the swatch's state is its color", ColorUtils.toHex(s.values.color(key)), swatch.stateDescription)
            // Right of its label, on the label's row.
            val label = byText(p.label)
            assertTrue("$id: the swatch right of \"${p.label}\"", swatch.bounds.left >= label.bounds.right)
            assertTrue("$id: on the label's row", swatch.bounds.center.y > label.bounds.top - 30 && swatch.bounds.center.y < label.bounds.bottom + 60)
            // The name and the label each match one place only (case-sensitive substrings).
            val others = shown().toMutableList().apply { remove(name) }
            val clash = others.filter { it.contains(name) || name.contains(it) }
            assertTrue("$id: \"$name\" meets another text: $clash", clash.isEmpty())
            assertEquals("$id: \"${p.label}\" shows once on the panel", 1, shown().count { it.contains(p.label) })

            // Found by its name, the swatch opens the picker titled after the label.
            swatch.tap()
            settle(3)
            assertTrue("$id: the color picker is open", hasText("OK"))
            assertEquals("$id: the picker's title is the label", 2, elements().count { e ->
                e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == p.label } == true
            })
            RobolectricUi.textFields().first().type(hex)
            settle(2)
            byText("OK").tap()
            settle(3)
            assertFalse("$id: the picker closed", hasText("OK"))
            val picked = (0xFF000000.toInt()) or hex.toInt(16)
            assertEquals("$id: the picked color", picked, s.values.color(key))
            assertEquals("$id: the swatch says the new color", ColorUtils.toHex(picked), visible { described(name) }.stateDescription)

            byDescription("Cancel filter").tap()
            Fx.waitUntil("cancel of $id") { s.isClosed && c.busyMessage == null }
            settle(3)
        }
        Fx.assertNoErrorMessage(c, "the swatch's name")
        rs.assertNoErrors("the swatch's name")
    }
}
