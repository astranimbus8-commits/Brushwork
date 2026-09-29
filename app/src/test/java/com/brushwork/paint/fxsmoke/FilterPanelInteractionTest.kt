package com.brushwork.paint.fxsmoke

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.color.RobolectricUi.byDescription
import com.brushwork.paint.ui.color.RobolectricUi.byText
import com.brushwork.paint.ui.color.RobolectricUi.elements
import com.brushwork.paint.ui.color.RobolectricUi.hasText
import com.brushwork.paint.ui.color.RobolectricUi.settle
import com.brushwork.paint.ui.color.RobolectricUi.tap
import com.brushwork.paint.ui.filters.FilterBrowser
import com.brushwork.paint.ui.filters.FilterSearch
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.filters.GradientEditing
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The filter UI driven like a user: the real FilterBrowser sheet (categories, search, picking a
 * result or a chip) opens a session, and every kind of control in the real FilterSessionPanel
 * (curve editor taps/drags, gradient editor, chips, slider, +/- buttons, switch, text field,
 * color swatch and its dialog, seed shuffle, point reset, compare, reset, apply, back) changes
 * the session as expected. Hosted like EditorScreen hosts them.
 *
 * Own sandbox (see ColorPickerUiSmokeTest): Compose frames stall in later tests of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.fxsmoke.panelsandbox"])
class FilterPanelInteractionTest {

    private val rs = RecordingScope()

    @After
    fun tearDown() = rs.close()

    private fun touch(el: RobolectricUi.Element, action: Int, downTime: Long) {
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, el.bounds.center.x, el.bounds.center.y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        el.window.dispatchTouchEvent(e)
        e.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    }

    /**
     * The element [find] returns, after scrolling its scrolling ancestors (the panel's column, a
     * chip row) until it is fully inside them, like a user would. Uses the scroll semantics action.
     */
    private fun visible(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(6) {
            val el = find()
            val pos = el.node.positionInWindow
            val l = pos.x; val t = pos.y; val r = l + el.node.size.width; val b = t + el.node.size.height
            // Scrolling containers from the outermost in; their viewport is their own (unclipped) box.
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

    private fun sliders() = elements().filter { it.node.config.getOrNull(SemanticsActions.SetProgress) != null }.sortedBy { it.bounds.top }

    private fun switches() = elements().filter { it.node.config.getOrNull(SemanticsProperties.ToggleableState) != null }.sortedBy { it.bounds.top }

    /** The clickable element on the same row as [label], to its right (e.g. a color swatch). */
    private fun clickableRightOf(label: RobolectricUi.Element): RobolectricUi.Element {
        val row = label.bounds
        return elements().filter { e ->
            e.node.config.getOrNull(SemanticsActions.OnClick) != null &&
                e.bounds.left > row.right && e.bounds.center.y > row.top - 40 && e.bounds.center.y < row.bottom + 40
        }.minByOrNull { it.bounds.left } ?: throw AssertionError("nothing clickable right of '${label.node.config.getOrNull(SemanticsProperties.Text)}'")
    }

    private fun session(c: com.brushwork.paint.EditorController, id: String): FilterSession {
        val s = c.filterSession ?: throw AssertionError("no session for $id (${c.message})")
        assertEquals(id, s.filter.id)
        return s
    }

    @Test
    fun browserPicksFiltersAndEveryControlDrivesTheSession() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val d = Fx.newDoc(activity.applicationContext, rs.scope, 96, 72)
        val c = d.controller
        val all = FilterRegistry.all
        var browser by mutableStateOf(true)
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    val s = c.filterSession
                    if (s != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { FilterSessionPanel(s, Modifier.fillMaxWidth()) }
                    if (browser) FilterBrowser(c) { browser = false }
                }
            }
        }
        settle()

        // ---------------------------------------------------------------- browser + search
        for (cat in FilterRegistry.byCategory().keys) assertTrue("category ${cat.title} listed", hasText(cat.title.uppercase()))
        for (f in all) assertTrue("filter ${f.name} listed", hasText(f.name))
        byText("Tone Curve").tap() // chip of the first category
        assertFalse("picking closes the browser", browser)
        var s = session(c, "adjust.tone_curve")
        Fx.awaitPreview(s, "tone curve")
        settle(3)
        assertTrue("panel shows the filter", hasText("Tone Curve"))

        // ---------------------------------------------------------------- tone curve editor
        // The panel is at most 45 % of the screen: controls below the fold are scrolled to first.
        visible { byText("Red") }.tap()
        assertEquals("channel chip", 1, s.values.choice("channel"))
        var editor = visible { byDescription("Tone curve editor") }
        var eb = editor.bounds
        tap(editor.window, eb.left + eb.width * 0.5f, eb.top + eb.height * 0.25f)
        val curve = s.values.curve("curve")
        assertEquals("a tap adds a point: $curve", 3, curve.size)
        assertTrue("the new point is above the diagonal: $curve", curve[1].y > curve[1].x + 0.1f)
        RobolectricUi.drag(editor.window, eb.left + eb.width * 0.5f to eb.top + eb.height * 0.25f, eb.left + eb.width * 0.5f to eb.top + eb.height * 0.7f)
        val moved = s.values.curve("curve")
        assertEquals(3, moved.size)
        assertTrue("dragging moves the point down: $moved", moved[1].y < curve[1].y - 0.2f)
        Fx.awaitPreview(s, "curve edited")
        assertNotNull(c.renderOverride)
        visible { byText("Reset") }.tap() // the curve editor's own reset
        assertEquals("curve reset", 2, s.values.curve("curve").size)
        assertEquals("the channel is kept by the curve reset", 1, s.values.choice("channel"))
        byDescription("Reset to defaults").tap()
        assertEquals(0, s.values.choice("channel"))
        editor = visible { byDescription("Tone curve editor") }
        eb = editor.bounds
        tap(editor.window, eb.left + eb.width * 0.5f, eb.top + eb.height * 0.25f)
        assertEquals(3, s.values.curve("curve").size)
        Fx.awaitPreview(s, "curve re-edited")

        // Compare: press and hold shows the original, release shows the preview again.
        val compare = byDescription("Compare with the original")
        val t0 = SystemClock.uptimeMillis()
        touch(compare, MotionEvent.ACTION_DOWN, t0)
        assertTrue("comparing while held", s.isComparing)
        assertNull(c.renderOverride)
        touch(compare, MotionEvent.ACTION_UP, t0)
        assertFalse(s.isComparing)
        assertNotNull(c.renderOverride)

        // Apply through the button: one undo step, the panel goes away.
        val before = Fx.pixels(d.paint.bitmap)
        byDescription("Apply filter").tap()
        Fx.waitUntil("apply") { s.isClosed && c.busyMessage == null }
        settle(3)
        assertNull(c.filterSession)
        assertFalse("panel gone", hasText("Tone Curve"))
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Tone Curve", c.undoManager.undoLabel)
        assertFalse(Fx.pixels(d.paint.bitmap).contentEquals(before))
        rs.assertNoErrors("tone curve")

        // ---------------------------------------------------------------- QR code (search result)
        browser = true
        settle()
        assertTrue("recently used section", hasText("RECENTLY USED"))
        val search = RobolectricUi.textFields().first()
        search.type("zzzz nothing")
        settle()
        assertTrue(hasText("No filter matches"))
        search.type("qr")
        settle()
        val n = FilterSearch.search(all, "qr").size
        assertTrue("$n result header", hasText(("$n result" + if (n == 1) "" else "s").uppercase()))
        byText("QR Code").tap()
        assertFalse("picking closes the browser", browser)
        s = session(c, "draw.qr_code")
        Fx.awaitPreview(s, "qr")
        settle(3)
        val text = RobolectricUi.textFields().first { it.text == "https://example.com" }
        text.type("hello brushwork")
        assertEquals("hello brushwork", s.values.text("text"))
        val size = sliders().singleOrNull { it.node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == 5f..100f }
            ?: throw AssertionError("Size slider not found: ${sliders().map { it.node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }}")
        val accepted = size.node.config[SemanticsActions.SetProgress].action!!.invoke(60f)
        assertEquals("slider accepted the value (${size.node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)})", true, accepted)
        assertEquals(60f, s.values.float("size"), 0f)
        visible { byDescription("Increase Size") }.tap()
        assertEquals(61f, s.values.float("size"), 0f)
        visible { byDescription("Decrease Size") }.tap()
        visible { byDescription("Decrease Size") }.tap()
        assertEquals(59f, s.values.float("size"), 0f)
        val high = visible { byText("High (30%)") }
        val highInfo = "bounds ${high.bounds} pos ${high.node.positionInWindow} size ${high.node.size} window ${high.window.width}x${high.window.height}"
        high.tap()
        assertEquals("ecc chip tap ($highInfo)", 3, s.values.choice("ecc"))
        visible { switches().last() }.tap()
        assertTrue("switch toggles the parameter", s.values.bool("transparent"))
        // Color swatch opens the picker dialog; Cancel keeps the color.
        visible { clickableRightOf(byText("Code color")) }.tap()
        assertTrue("color dialog open", hasText("OK"))
        byText("Cancel").tap()
        assertFalse("color dialog closed", hasText("OK"))
        assertEquals(0xFF000000.toInt(), s.values.color("color"))
        // Point: dragged on the canvas, reset from the panel.
        c.pointerDown(ToolPoint(48f, 36f))
        c.pointerMove(ToolPoint(20f, 20f))
        c.pointerUp(ToolPoint(20f, 20f))
        assertArrayEquals(floatArrayOf(20f / 96f, 20f / 72f), s.values.point("position"), 1e-4f)
        settle(3)
        visible { byDescription("Reset Position") }.tap()
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), s.values.point("position"), 0f)
        Fx.awaitPreview(s, "qr edited")
        Fx.assertNoErrorMessage(c, "qr")
        // Back cancels the session.
        activity.onBackPressedDispatcher.onBackPressed()
        settle(3)
        assertTrue(s.isClosed)
        assertNull(c.filterSession)
        assertNull(c.renderOverride)
        assertEquals("cancel adds nothing", 1, c.undoManager.undoCount)

        // ---------------------------------------------------------------- gradation map (gradient editor)
        browser = true
        settle()
        RobolectricUi.textFields().first().type("gradation map")
        settle()
        byText("Gradation Map").tap()
        s = session(c, "adjust.gradation_map")
        Fx.awaitPreview(s, "gradation map")
        settle(3)
        val stops0 = s.values.gradient("gradient")
        val bar = visible { byDescription("Gradient editor") }
        val bb = bar.bounds
        val pad = 14f * activity.resources.displayMetrics.density
        tap(bar.window, bb.left + pad + (bb.width - 2 * pad) * 0.25f, bb.top + 20f)
        val stops1 = s.values.gradient("gradient")
        assertEquals("a tap on the bar adds a stop: $stops1", stops0.size + 1, stops1.size)
        visible { byDescription("Reverse gradient") }.tap()
        assertEquals(GradientEditing.reverse(stops1), s.values.gradient("gradient"))
        val (presetName, preset) = GradientEditing.presets.first()
        visible { byText(presetName) }.tap()
        assertEquals(GradientEditing.sorted(preset), s.values.gradient("gradient"))
        visible { byText("Default") }.tap()
        val restored: List<GradientStop> = s.values.gradient("gradient")
        assertEquals(GradientEditing.sorted(stops0), restored)
        visible { switches().last() }.tap()
        assertTrue(s.values.bool("reverse"))
        Fx.awaitPreview(s, "gradient edited")
        byDescription("Apply filter").tap()
        Fx.waitUntil("apply") { s.isClosed && c.busyMessage == null }
        assertEquals(2, c.undoManager.undoCount)
        assertEquals("Gradation Map", c.undoManager.undoLabel)

        // ---------------------------------------------------------------- clouds (seed shuffle)
        browser = true
        settle()
        RobolectricUi.textFields().first().type("clouds")
        settle()
        byText("Clouds").tap()
        s = session(c, "draw.clouds")
        Fx.awaitPreview(s, "clouds")
        settle(3)
        val seed0 = s.values.seed()
        visible { byDescription("New random") }.tap()
        assertNotEquals("shuffle changes the seed", seed0, s.values.seed())
        Fx.awaitPreview(s, "clouds shuffled")
        byDescription("Cancel filter").tap()
        assertNull(c.filterSession)
        assertNull(c.renderOverride)

        // ---------------------------------------------------------------- the panel while applying
        // Real filters finish too fast on a test canvas to see the busy state; this one blocks.
        val slow = BlockingFilter()
        c.startFilter(slow)
        s = session(c, "test.blocking")
        Fx.awaitPreview(s, "blocking")
        settle(3)
        slow.armed = true
        byDescription("Apply filter").tap()
        Fx.waitUntil("progress reported") { s.isApplying && s.applyProgress >= 0.5f }
        settle(3)
        assertTrue("apply progress shown", hasText("Applying at full size"))
        // Controls are disabled while applying.
        val amount0 = s.values.float("amount")
        visible { byDescription("Increase Amount") }.tap()
        assertEquals("disabled while applying", amount0, s.values.float("amount"), 0f)
        visible { byText("Stop") }.tap()
        Fx.waitUntil("apply stopped") { !s.isApplying && c.busyMessage == null }
        settle(3)
        assertFalse(s.isClosed)
        assertFalse("progress hidden", hasText("Applying at full size"))
        assertEquals("nothing applied", 2, c.undoManager.undoCount)
        visible { byDescription("Increase Amount") }.tap()
        assertEquals("enabled again", amount0 + 1f, s.values.float("amount"), 0f)
        // Back while applying stops the apply first, then a second Back cancels the session.
        byDescription("Apply filter").tap()
        Fx.waitUntil("applying again") { s.isApplying }
        activity.onBackPressedDispatcher.onBackPressed()
        Fx.waitUntil("apply stopped by Back") { !s.isApplying && c.busyMessage == null }
        assertFalse("Back during apply only stops the apply", s.isClosed)
        settle(3)
        activity.onBackPressedDispatcher.onBackPressed()
        settle(3)
        assertTrue(s.isClosed)
        assertNull(c.filterSession)
        slow.armed = false
        rs.assertNoErrors("panel interactions")
        c.dispose()
    }

    /** Blocks (reporting half progress) until cancelled while [armed]. */
    private class BlockingFilter : com.brushwork.paint.filters.Filter("test.blocking", "Blocking", com.brushwork.paint.filters.FilterCategory.ART) {
        @Volatile var armed = false
        override val params = listOf(com.brushwork.paint.filters.FilterParam.Slider("amount", "Amount", 0f, 100f, 50f, step = 1f))
        override fun apply(src: com.brushwork.paint.core.PixelBuffer, values: com.brushwork.paint.filters.FilterValues, ctx: com.brushwork.paint.filters.FilterContext): com.brushwork.paint.core.PixelBuffer {
            val end = System.currentTimeMillis() + 20_000
            while (armed && System.currentTimeMillis() < end) { ctx.progress(0.6f); ctx.checkCancelled(); Thread.sleep(2) }
            return com.brushwork.paint.core.PixelBuffer.filled(src.width, src.height, 0xFF00FF00.toInt())
        }
    }
}
