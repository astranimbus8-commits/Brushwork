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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish, verifier (accessibility, items 1 and 2) on a narrow phone (360 dp), the flows the
 * fixer's 392 dp tests leave out:
 *
 * 1. Glitch: the shuffle button is described exactly "New random seed", lies wholly on the
 *    phone, and a finger tap on it gives a new seed.
 * 2. Gradation Map's gradient bar at 360 dp: each stop's node sits over its marker; the stops'
 *    names follow every whole-gradient change a finger makes: "Reverse gradient" (the first stop
 *    now says the last color, none Selected), a stop picked by a screen reader's click, its picker
 *    cancelled, then "Delete color stop" ("k of 3"), and the "Default" preset (its stops' names).
 * 3. The Opacity slider: ONE node named "Opacity slider" in the merged tree (what TalkBack
 *    focuses), its state the value shown, wholly on the phone.
 * A render of the bar after the delete.
 *
 * One UI test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polisha11yverifyfiltersandbox"])
class Qa18PolishA11yVerifyFilterNarrowUiTest {

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
            settle(6, 100)
        }
        return find()
    }

    private fun description(e: RobolectricUi.Element): String? = e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    private fun stops(): List<RobolectricUi.Element> =
        elements().filter { e -> description(e)?.startsWith("Color stop ") == true }.sortedBy { it.bounds.left }

    private fun selection(): List<Boolean?> = stops().map { it.node.config.getOrNull(SemanticsProperties.Selected) }

    private fun hasExactText(text: String): Boolean =
        elements().any { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

    private fun describedExactly(name: String): List<RobolectricUi.Element> =
        elements().filter { it.node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(name) }

    private fun composeRoot(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { composeRoot(v.getChildAt(it)) }
        else -> null
    }

    private fun merged(root: ViewRootForTest): List<SemanticsNode> {
        root.measureAndLayoutForTest()
        val out = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        walk(root.semanticsOwner.rootSemanticsNode)
        return out
    }

    @Test
    fun seedGradientStopsAndSliderAt360Dp() {
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
        val window = activity.window.decorView
        assertEquals("the phone is 360 dp wide", 360f, window.width / density, 1f)
        val root = requireNotNull(composeRoot(window)) { "no compose view" }

        // ---- 1. Glitch's seed button.
        val glitch = FilterRegistry.byId("art.glitch") ?: throw AssertionError("Glitch is registered")
        c.startFilter(glitch)
        val gs = c.filterSession ?: throw AssertionError("no Glitch session (${c.message})")
        Fx.awaitPreview(gs, glitch.id)
        settle(3)
        val seedButton = visible { describedExactly("New random seed").singleOrNull() ?: throw AssertionError("one \"New random seed\"") }
        assertTrue("the button on the phone: ${seedButton.bounds}", seedButton.bounds.left >= 0f && seedButton.bounds.right <= window.width + 0.5f)
        assertTrue("\"Random seed\" still shown", hasExactText("Random seed"))
        val seed0 = gs.values.seed()
        seedButton.tap()
        settle(3)
        assertNotEquals("a new seed", seed0, gs.values.seed())
        byDescription("Cancel filter").tap()
        Fx.waitUntil("cancel Glitch") { gs.isClosed && c.busyMessage == null }
        settle(3)

        // ---- 2. Gradation Map's stops at 360 dp.
        val f = FilterRegistry.byId("adjust.gradation_map") ?: throw AssertionError("Gradation Map is registered")
        val param = f.params.filterIsInstance<FilterParam.Gradient>().single()
        c.startFilter(f)
        val s = c.filterSession ?: throw AssertionError("no session (${c.message})")
        Fx.awaitPreview(s, f.id)
        settle(3)
        fun gradient(): List<GradientStop> = GradientEditing.sorted(s.values.gradient(param.key))
        fun names(g: List<GradientStop>) = g.mapIndexed { i, st -> colorStopDescription(i + 1, g.size, ColorUtils.toHex(st.color)) }
        val bar = visible { byDescription("Gradient editor") }
        val g0 = gradient()
        assertEquals("one node per stop", names(g0), stops().map { description(it) })
        val pad = 14f * density
        val span = bar.bounds.width - 2 * pad
        for ((i, n) in stops().withIndex()) {
            assertEquals("stop ${i + 1} over its marker at 360 dp", bar.bounds.left + pad + g0[i].position * span, n.bounds.center.x, 1.5f * density)
            assertTrue("stop ${i + 1} on the phone", n.bounds.left >= 0f && n.bounds.right <= window.width + 0.5f)
        }

        // "Reverse gradient": the names follow (the first stop says the old last color).
        visible { byDescription("Reverse gradient") }.tap()
        settle(3)
        val g1 = gradient()
        assertEquals("reversed", GradientEditing.reverse(g0), g1)
        assertEquals("the names follow the reverse", names(g1), stops().map { description(it) })
        assertTrue("stop 1 now says the old last color", description(stops()[0])!!.endsWith(ColorUtils.toHex(g0.last().color)))
        assertTrue("none selected after the reverse", selection().all { it == false })

        // A screen reader picks stop 2 (its picker opens; Cancel), then "Delete color stop".
        requireNotNull(stops()[1].node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        settle(3)
        assertEquals(listOf(false, true, false, false), selection())
        assertTrue("its picker", hasExactText("Stop color"))
        RobolectricUi.byText("Cancel").tap()
        settle(3)
        assertEquals("still selected after Cancel", listOf(false, true, false, false), selection())
        visible { byDescription("Delete color stop") }.tap()
        settle(3)
        val g2 = gradient()
        assertEquals("three stops", 3, g2.size)
        assertEquals("the deleted one gone", g1.filterIndexed { i, _ -> i != 1 }, g2)
        assertEquals("names k of 3", names(g2), stops().map { description(it) })
        assertTrue("\"Color stop 3 of 3\"", description(stops()[2])!!.startsWith("Color stop 3 of 3, #"))
        assertTrue("none selected after the delete", selection().all { it == false })
        val shot = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        window.draw(Canvas(shot))
        Qa18CurvesShots.save(shot, "polish-a11y-verify-gradation-360.png")

        // The "Default" preset: four stops again, named.
        visible { RobolectricUi.byText("Default") }.tap()
        settle(3)
        val g3 = gradient()
        assertEquals("the default", GradientEditing.sorted(param.default), g3)
        assertEquals("the default's names", names(g3), stops().map { description(it) })

        // ---- 3. The Opacity slider, as TalkBack focuses it.
        visible { RobolectricUi.byText("Opacity") }
        val sliders = merged(root).filter { n -> n.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Opacity slider") }
        assertEquals("one node named \"Opacity slider\"", 1, sliders.size)
        val o = sliders.single()
        assertNotNull("it sets a progress", o.config.getOrNull(SemanticsActions.SetProgress))
        val state = o.config.getOrNull(SemanticsProperties.StateDescription)
        assertTrue("its state is the value shown (\"$state\")", state != null && hasExactText(state))
        assertTrue("the slider on the phone: ${o.boundsInWindow}", o.boundsInWindow.left >= 0f && o.boundsInWindow.right <= window.width + 0.5f)

        byDescription("Cancel filter").tap()
        Fx.waitUntil("cancel") { s.isClosed && c.busyMessage == null }
        settle(3)
        Fx.assertNoErrorMessage(c, "the narrow filter panel's names")
        rs.assertNoErrors("the narrow filter panel's names")
    }
}
