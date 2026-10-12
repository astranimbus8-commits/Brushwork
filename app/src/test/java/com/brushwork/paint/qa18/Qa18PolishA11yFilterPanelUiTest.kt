package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.fxsmoke.RecordingScope
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.color.RobolectricUi.byDescription
import com.brushwork.paint.ui.color.RobolectricUi.elements
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.filters.GradientEditing
import com.brushwork.paint.ui.filters.colorStopDescription
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 polish (accessibility, item 2) on a filter panel at 392 dp: Gradation Map's gradient bar
 * and its Opacity slider, as a screen reader reads them.
 *
 * 1. The bar's stops: one node per stop over its marker, named "Color stop k of n, #RRGGBB"
 *    (k from the left), none Selected at first. A finger tap on a stop's marker selects it (that
 *    node alone Selected) and opens its color picker, as before; "Cancel" changes nothing. A
 *    screen reader's click on another stop's node does the same for that stop; a color typed in
 *    the picker recolors that stop and its name says the new color. A finger tap on the bar
 *    between stops still adds a stop (the stops' nodes leave the touches to the bar).
 * 2. The shared slider (`LabeledSlider`): the slider's node, the one TalkBack focuses (its merged
 *    node), is named "Opacity slider", says the value shown next to the label as its state, and
 *    keeps its progress (range and value). "Decrease Opacity" tapped: the state follows.
 *    No text the panel shows is renamed.
 *
 * One UI test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polisha11yfilterpanelsandbox"])
class Qa18PolishA11yFilterPanelUiTest {

    private val rs = RecordingScope()

    @After
    fun tearDown() = rs.close()

    /** [find]'s element after scrolling the panel's column until it is wholly inside (as a user would). */
    private fun visible(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(12) {
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
            // The column slides there (an animation): let it land before looking again.
            settle(6, 100)
        }
        return find()
    }

    private fun description(e: RobolectricUi.Element): String? = e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    private fun isSelected(e: RobolectricUi.Element): Boolean? = e.node.config.getOrNull(SemanticsProperties.Selected)

    /** The bar's stop nodes, left to right. */
    private fun stops(): List<RobolectricUi.Element> =
        elements().filter { e -> description(e)?.startsWith("Color stop ") == true }.sortedBy { it.bounds.left }

    private fun hasExactText(text: String): Boolean =
        elements().any { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

    /** The compose view of [v] (the activity's content). */
    private fun composeRoot(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { composeRoot(v.getChildAt(it)) }
        else -> null
    }

    /** Every node of the MERGED tree (what a screen reader focuses) of [root]. */
    private fun merged(root: ViewRootForTest): List<SemanticsNode> {
        root.measureAndLayoutForTest()
        val out = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(root.semanticsOwner.rootSemanticsNode)
        return out
    }

    @Test
    fun gradientStopsAndTheSliderAreNamedForAScreenReader() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
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
        val density = activity.resources.displayMetrics.density
        assertEquals("the phone is 392 dp wide", 392f, activity.window.decorView.width / density, 1f)
        val root = requireNotNull(composeRoot(activity.window.decorView)) { "no compose view" }

        val f = FilterRegistry.byId("adjust.gradation_map") ?: throw AssertionError("Gradation Map is registered")
        val param = f.params.filterIsInstance<FilterParam.Gradient>().single()
        c.startFilter(f)
        val s = c.filterSession ?: throw AssertionError("no session (${c.message})")
        Fx.awaitPreview(s, f.id)
        settle(3)
        fun gradient(): List<GradientStop> = GradientEditing.sorted(s.values.gradient(param.key))
        fun names(g: List<GradientStop>) = g.mapIndexed { i, st -> colorStopDescription(i + 1, g.size, ColorUtils.toHex(st.color)) }

        // ---- 1. The stops.
        val bar = visible { byDescription("Gradient editor") }
        val g0 = gradient()
        assertEquals("four stops", 4, g0.size)
        var nodes = stops()
        assertEquals("one node per stop, named from the left", names(g0), nodes.map { description(it) })
        assertEquals("Color stop 1 of 4, ${ColorUtils.toHex(g0[0].color)}", description(nodes[0]))
        assertTrue("none selected at first", nodes.all { isSelected(it) == false })
        val pad = 14f * density
        val span = bar.bounds.width - 2 * pad
        for ((i, n) in nodes.withIndex()) {
            assertEquals("stop ${i + 1} over its marker", bar.bounds.left + pad + g0[i].position * span, n.bounds.center.x, 1.5f * density)
            assertTrue("stop ${i + 1} under the bar, inside the editor", n.bounds.top >= bar.bounds.top + 34f * density && n.bounds.bottom <= bar.bounds.bottom + 0.5f)
            assertNotNull("stop ${i + 1} is clickable for a screen reader", n.node.config.getOrNull(SemanticsActions.OnClick))
        }

        // A finger on stop 2's marker: selected (that node alone), its picker open; Cancel.
        nodes[1].tap()
        settle(3)
        nodes = stops()
        assertEquals("a finger selects stop 2", listOf(false, true, false, false), nodes.map { isSelected(it) })
        assertTrue("its color picker is open", hasExactText("Stop color") && hasExactText("Cancel"))
        assertEquals("the stops untouched by a tap", g0, gradient())
        val v = activity.window.decorView
        val shot = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        RobolectricUi.byText("Cancel").tap()
        settle(3)
        assertFalse("the picker closed", hasExactText("Stop color"))
        v.draw(Canvas(shot))
        Qa18CurvesShots.save(shot, "polish-a11y-gradation-stop-selected.png")

        // A screen reader's click on stop 3: selected, its picker; a color typed recolors it.
        requireNotNull(stops()[2].node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        settle(3)
        assertEquals("the click selects stop 3", listOf(false, false, true, false), stops().map { isSelected(it) })
        assertTrue("its color picker is open", hasExactText("Stop color"))
        SmokeUi.field("Hex").type("20C040")
        settle(2)
        RobolectricUi.byText("OK").tap()
        settle(3)
        val g1 = gradient()
        assertEquals("stop 3 recolored", 0xFF20C040.toInt(), g1[2].color)
        assertEquals("the others kept", listOf(g0[0], g0[1], g0[3]), listOf(g1[0], g1[1], g1[3]))
        assertEquals("its name says the new color", "Color stop 3 of 4, #20C040", description(stops()[2]))

        // A finger between stops 1 and 2 still adds a stop there.
        val bar2 = visible { byDescription("Gradient editor") }
        val x = (stops()[0].bounds.center.x + stops()[1].bounds.center.x) / 2f
        RobolectricUi.tap(bar2.window, x, bar2.bounds.top + 17f * density)
        settle(3)
        val g2 = gradient()
        assertEquals("a stop added", 5, g2.size)
        assertEquals("five named stops, the new one selected", names(g2), stops().map { description(it) })
        assertEquals(listOf(false, true, false, false, false), stops().map { isSelected(it) })

        // ---- 2. The Opacity slider, as a screen reader focuses it.
        fun slider(): SemanticsNode = merged(root).singleOrNull { n ->
            n.config.getOrNull(SemanticsActions.SetProgress) != null && n.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Opacity slider")
        } ?: throw AssertionError("no merged slider node named \"Opacity slider\"")
        visible { RobolectricUi.byText("Opacity") }
        val o = slider()
        val state = o.config.getOrNull(SemanticsProperties.StateDescription)
        assertNotNull("the slider says its value", state)
        assertTrue("the state is the value shown by the label (\"$state\")", hasExactText(state!!))
        assertTrue("its label's text is unchanged", hasExactText("Opacity"))
        val range = o.config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals("the progress is the value", s.values.float("opacity"), range.current, 1e-3f)
        assertEquals(0f, range.range.start, 0f)
        assertEquals(100f, range.range.endInclusive, 0f)
        visible { byDescription("Decrease Opacity") }.tap()
        settle(3)
        val after = slider().config.getOrNull(SemanticsProperties.StateDescription)
        assertNotEquals("the state follows the value", state, after)
        assertTrue("the new state is the value shown (\"$after\")", hasExactText(after!!))
        assertTrue("the progress follows", abs(slider().config[SemanticsProperties.ProgressBarRangeInfo].current - s.values.float("opacity")) < 1e-3f)

        byDescription("Cancel filter").tap()
        Fx.waitUntil("cancel") { s.isClosed && c.busyMessage == null }
        settle(3)
        Fx.assertNoErrorMessage(c, "the stops' and the slider's names")
        rs.assertNoErrors("the stops' and the slider's names")
    }
}
