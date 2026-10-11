package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.fxsmoke.RecordingScope
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.color.RobolectricUi.byDescription
import com.brushwork.paint.ui.color.RobolectricUi.elements
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.filters.newSeedDescription
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
 * v1.8 polish (accessibility, item 1): a filter's seed row keeps v1.7's label "Random seed", and
 * its shuffle button is named "New random seed" for a screen reader (it read "New random random
 * seed": "New random " was put before the label, which already says "random").
 *
 * 1. Every registered filter with a seed names its button "New random seed".
 * 2. On the phone (392 dp), on the panels of Glitch, Noise and Clouds: the button is described
 *    exactly "New random seed", no text or description on screen says "random random", the row's
 *    label is still "Random seed", and a finger tap on the button found by that name gives a new
 *    seed, shown on the row. A render of Glitch's seed row for the lead.
 *
 * One UI test, own sandbox (Compose frames stall in later tests of a sandbox).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polisha11yseedsandbox"])
class Qa18PolishA11ySeedUiTest {

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

    /** Every text and description on screen, one entry per place it shows. */
    private fun shown(): List<String> = elements().flatMap { e ->
        e.node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            e.node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.isNotBlank() }

    /** The elements described exactly [name]. */
    private fun describedExactly(name: String): List<RobolectricUi.Element> =
        elements().filter { it.node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(name) }

    private fun hasExactText(text: String): Boolean =
        elements().any { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }

    @Test
    fun theSeedButtonSaysNewRandomSeedOnEveryPanel() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()

        // 1. Every filter with a seed (all of them keep v1.7's "Random seed").
        val seeded = FilterRegistry.all.filter { f -> f.params.any { it is FilterParam.Seed } }
        assertTrue("filters with a seed: ${seeded.map { it.id }}", seeded.size >= 10)
        for (f in seeded) for (p in f.params.filterIsInstance<FilterParam.Seed>()) {
            assertEquals("${f.id}: v1.7's label", "Random seed", p.label)
            assertEquals("${f.id}: the button's name", "New random seed", newSeedDescription(p.label))
        }
        // A seed labelled without "random" still says it once.
        assertEquals("New random seed", newSeedDescription("Seed"))

        // 2. The panels on the phone.
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

        for (id in listOf("art.glitch", "art.noise", "draw.clouds")) {
            val f = FilterRegistry.byId(id) ?: throw AssertionError("$id is registered")
            c.startFilter(f)
            val s = c.filterSession ?: throw AssertionError("no session for $id (${c.message})")
            Fx.awaitPreview(s, id)
            settle(3)

            val name = "New random seed"
            val button = visible { describedExactly(name).singleOrNull() ?: throw AssertionError("$id: one button described \"$name\"; shown: ${shown()}") }
            assertTrue("$id: nothing says \"random random\": ${shown().filter { it.contains("random random", ignoreCase = true) }}", shown().none { it.contains("random random", ignoreCase = true) })
            assertTrue("$id: the row's label is still v1.7's \"Random seed\"", hasExactText("Random seed"))
            var n: androidx.compose.ui.semantics.SemanticsNode? = button.node
            while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            assertNotNull("$id: \"$name\" is a button", n)

            if (id == "art.glitch") {
                val v = activity.window.decorView
                val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(b))
                Qa18CurvesShots.save(b, "polish-a11y-seed-glitch.png")
            }

            // Found by its name, a finger tap gives a new seed, shown on the row.
            val seed0 = s.values.seed()
            visible { describedExactly(name).single() }.tap()
            settle(3)
            assertNotEquals("$id: a new seed", seed0, s.values.seed())
            assertTrue("$id: the new seed is shown", hasExactText(s.values.seed().toString()))
            assertEquals("$id: the button keeps its name", 1, describedExactly(name).size)

            byDescription("Cancel filter").tap()
            Fx.waitUntil("cancel of $id") { s.isClosed && c.busyMessage == null }
            settle(3)
        }
        Fx.assertNoErrorMessage(c, "the seed button's name")
        rs.assertNoErrors("the seed button's name")
    }
}
