package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.filters.art.v17.ChromaticAberrationV17
import com.brushwork.paint.filters.art.v17.GlitchV17
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import kotlin.math.abs

/**
 * v1.8 final QA, cluster "look" (item 5, design §3.6), verifier's flow: the fringe colors on a
 * TRANSPARENT layer (a gray finger stroke over the white bottom layer), on the 360 dp phone,
 * opened the way the user opens a filter. The tester's flows ran on opaque pictures; here the
 * alpha rule shows ("the alpha is the largest alpha among the samples whose swatch is colored").
 *
 * Chromatic aberration (Moving):
 * - its defaults (RGB) are v1.7's bytes on the transparent layer too; ink appears beside the
 *   stroke (fringes on transparent pixels), far from it nothing;
 * - Red/Cyan (the fast path): the preview is the filter's result; green and blue move together;
 *   beside the stroke the ink is red only where the forward sample is covered, cyan only where
 *   the backward one is (the middle sample, with no color, adds no ink);
 * - the first swatch typed #FE0000 (the general path, same weights): the same picture, so a
 *   color one step off red never jumps; no chip lit; Red/Cyan again;
 * - "Apply filter": ONE step, the preview; two fingers undo it exactly.
 * Glitch:
 * - its defaults are v1.7's bytes on the transparent layer;
 * - Height 6, Static 100, Magenta/Green, "Color of the static" at half opacity (#80FF2040 typed
 *   in its picker, which has an opacity): the swatch says "#80FF2040"; the static is about half
 *   of the opaque one's, never touches the alpha, never paints the transparent pixels;
 * - "New random random seed": the colors kept, other rows change;
 * - "Compare with the original (press and hold)" held by a finger: the original while held, the
 *   preview again on release, no step;
 * - the static's swatch tapped, then "Cancel": nothing changes;
 * - "Reset to defaults": RGB lit, the static white, v1.7's bytes again;
 * - "Cancel filter": no step, the layer as it was.
 * PNGs: `look-fringe-transparent-moving.png`, `look-fringe-transparent-glitch.png` (over white,
 * 2x) and `look-fringe-transparent-screen.png` (the Glitch panel at 360 dp). One test, own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.lookfringetransparentsandbox"])
class Qa18LookFringeTransparentTest {

    @Test
    fun fringeColorsOnATransparentLayer() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        h.section("fringe colors on a transparent layer at 360 dp") { flow(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    private lateinit var u: Qa17ArrayUi

    // ------------------------------------------------------------------ the screen (as Qa18LookFringeTest)

    /** Opens [f] like the user: the Filters tool, "Search filters" typed, a finger on its name. */
    private fun openFilter(f: Filter, search: String): FilterSession {
        u.tool("Filters")
        SmokeUi.assertPanelShown("Filters")
        SmokeUi.field("Search filters").let { it.focus(); settle(2); it.type(search) }
        settle(4)
        u.fingerPress(f.name)
        val s = u.c.filterSession ?: throw AssertionError("no session for ${f.name} (${u.c.message})")
        assertEquals(f.id, s.filter.id)
        Fx.awaitPreview(s, f.name)
        settle()
        assertTrue("${f.name}: the Fringe colors control", has(FringeLabels18.FRINGE_COLORS, exact = true))
        return s
    }

    /** The element [find] returns, slid wholly into its scrolling ancestors first (as a finger would). */
    private fun visible(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(12) {
            val e = find()
            val pos = e.node.positionInWindow
            val l = pos.x; val t = pos.y; val r = l + e.node.size.width; val b = t + e.node.size.height
            val scrollers = generateSequence(e.node.parent) { it.parent }.filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.toList().asReversed()
            for (n in scrollers) {
                val p = n.positionInWindow
                val vl = p.x; val vt = p.y; val vr = vl + n.size.width; val vb = vt + n.size.height
                val across = n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                val delta = if (across) {
                    when { r > vr -> r - vr + 8f; l < vl -> l - vl - 8f; else -> 0f }
                } else {
                    when { b > vb -> b - vb + 8f; t < vt -> t - vt - 8f; else -> 0f }
                }
                if (delta != 0f) {
                    val scroll = n.config[SemanticsActions.ScrollBy].action!!
                    if (across) scroll(delta, 0f) else scroll(0f, delta)
                    Smoke.pump(300)
                    settle(2)
                    return@repeat
                }
            }
            return e
        }
        return find()
    }

    private fun chip(name: String): RobolectricUi.Element =
        RobolectricUi.elements().lastOrNull { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == V18Tags.FRINGE_PREFIX + name }
            ?: throw AssertionError("no chip \"$name\"")

    private fun lit(name: String): Boolean {
        val n = chip(name).node
        return n.config.getOrNull(SemanticsProperties.Selected) == true || n.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
    }

    private fun tapChip(name: String) {
        visible { chip(name) }.tap()
        settle(3)
    }

    private val chips = listOf(FringeLabels18.RGB, FringeLabels18.RED_CYAN, FringeLabels18.MAGENTA_GREEN, FringeLabels18.BLUE_YELLOW)

    /** A finger on the swatch described [name]; its picker opens titled [title]; [hex] typed, OK. */
    private fun pick(name: String, hex: String, title: String) {
        visible { RobolectricUi.byDescription(name) }.tap()
        settle(3)
        assertTrue("the color picker is open for \"$name\"; shown: ${SmokeUi.shown().take(60)}", has("OK", exact = true))
        val top = SmokeUi.windows().last()
        assertTrue("the picker is titled \"$title\"", RobolectricUi.elements().any { e -> e.window === top && e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true })
        SmokeUi.field("Hex").type(hex)
        settle(2)
        SmokeUi.tap("OK", exact = true)
        settle(3)
        assertFalse("the color picker closed", has("OK", exact = true))
    }

    private fun preview(s: FilterSession, what: String): IntArray {
        Fx.awaitPreview(s, what)
        settle()
        return Fx.overrideContent(u.c) ?: throw AssertionError("$what: no preview drawn")
    }

    private fun direct(f: Filter, src: Bitmap, values: FilterValues): IntArray =
        Fx.roundTrip(Fx.direct(f, BitmapUtils.toPixelBuffer(src), values, 1f, u.c.doc.dpi))

    private fun ctx() = FilterContext(scale = 1f, dpi = u.c.doc.dpi)

    /** "Apply filter" with a finger: ONE step named after the filter; the layer is [expected]. */
    private fun applyFilter(s: FilterSession, expected: IntArray) {
        val c = u.c
        val before = u.steps()
        visible { RobolectricUi.byDescription("Apply filter") }.tap()
        Fx.waitUntil("apply of ${s.filter.name}") { settle(1); s.isClosed && c.busyMessage == null }
        settle()
        assertNull(c.filterSession)
        assertEquals("${s.filter.name}: one step", before + 1, u.steps())
        assertEquals(s.filter.name, c.undoManager.undoLabel)
        val after = Fx.pixels(c.activeLayer.bitmap)
        assertTrue("${s.filter.name}: the layer is the preview: ${Fx.diff(expected, after, c.doc.width)}", expected.contentEquals(after))
    }

    private fun undoTo(src: IntArray, what: String) {
        val before = u.steps()
        u.ui.twoFingerUndo()
        settle()
        assertEquals("$what: one step undone", before - 1, u.steps())
        val now = Fx.pixels(u.c.activeLayer.bitmap)
        assertTrue("$what: undo gives the stroke back: ${Fx.diff(src, now, u.c.doc.width)}", src.contentEquals(now))
    }

    private fun sliderUnder(label: String): RobolectricUi.Element {
        val all = RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }
        val at = all.indexOfLast { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        assertTrue("\"$label\" shows; shown: ${SmokeUi.shown().take(60)}", at >= 0)
        return all.drop(at + 1).firstOrNull { it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
            ?: throw AssertionError("no slider under \"$label\"")
    }

    private fun setSlider(label: String, v: Float) {
        val e = visible { sliderUnder(label) }
        requireNotNull(e.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(v)
        settle(4)
    }

    /** One finger event [action] at the centre of [e], in its own window (a press held between DOWN and UP). */
    private fun finger(e: RobolectricUi.Element, action: Int, downTime: Long): Long {
        val now = SystemClock.uptimeMillis()
        val down = if (action == MotionEvent.ACTION_DOWN) now else downTime
        val ev = MotionEvent.obtain(down, now, action, e.bounds.center.x, e.bounds.center.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        e.window.dispatchTouchEvent(ev)
        ev.recycle()
        return down
    }

    private fun holdFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // ------------------------------------------------------------------ pixels

    private fun a(p: Int) = p ushr 24
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    /** The largest difference between [x] and [y] per PREMULTIPLIED channel. */
    private fun premultipliedOff(x: IntArray, y: IntArray): Int {
        var worst = 0
        for (i in x.indices) {
            val p = x[i]; val q = y[i]
            if (p == q) continue
            worst = maxOf(worst, abs(a(p) - a(q)))
            for (s in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((p shr s) and 0xFF) * a(p) / 255 - ((q shr s) and 0xFF) * a(q) / 255))
        }
        return worst
    }

    /** Pixels of [src] transparent in their whole 13 x 13 neighbourhood, 8 px or more inside: no shift of up to 6 px reaches ink. */
    private fun clear(src: IntArray, w: Int, h: Int): List<Int> {
        val out = ArrayList<Int>()
        for (y in 8 until h - 8) for (x in 8 until w - 8) {
            var none = true
            loop@ for (dy in -6..6) for (dx in -6..6) if (a(src[(y + dy) * w + x + dx]) != 0) { none = false; break@loop }
            if (none) out += y * w + x
        }
        return out
    }

    private fun changedRows(src: IntArray, out: IntArray, w: Int): Set<Int> =
        (0 until src.size / w).filter { y -> (0 until w).any { x -> src[y * w + x] != out[y * w + x] } }.toSet()

    /** How far the straight colors of [x] are from [base] (summed over R, G, B where either has ink). */
    private fun deviation(x: IntArray, base: IntArray): Long {
        var sum = 0L
        for (i in x.indices) {
            if (a(x[i]) == 0 && a(base[i]) == 0) continue
            sum += abs(r(x[i]) - r(base[i])) + abs(g(x[i]) - g(base[i])) + abs(b(x[i]) - b(base[i]))
        }
        return sum
    }

    // ------------------------------------------------------------------ the flow

    private fun flow(ui: Qa17ArrayUi) {
        u = ui
        u.editor(widthDp = 360f)
        val c = u.c
        val w = c.doc.width
        val h = c.doc.height
        val layer = c.activeLayer
        assertTrue("the layer starts transparent", Fx.pixels(layer.bitmap).all { a(it) == 0 })

        // A gray stroke by finger on the transparent layer (the white bottom layer under it).
        u.tool("Brush")
        c.color = INK
        c.brush = c.brush.copy(size = 18f, opacity = 1f)
        val path = listOf(60f to 200f, 120f to 120f, 200f to 90f, 280f to 130f, 340f to 210f)
        for ((x, y) in path) assertTrue("($x, $y) on the canvas", u.onCanvas(x, y))
        u.ui.stroke(*path.toTypedArray())
        u.settleRenders("the stroke")
        val srcBmp = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val src = Fx.pixels(srcBmp)
        val inked = src.count { a(it) == 255 }
        assertTrue("the stroke has a solid core ($inked px) and the layer stays mostly transparent", inked > 1500 && src.count { a(it) == 0 } > w * h / 2)
        val clearPx = clear(src, w, h)
        assertTrue("transparent areas to check: ${clearPx.size}", clearPx.size > 40_000)

        // ================================================================ Moving: v1.7 at its defaults
        val moving = FilterRegistry.byId("art.chromatic_aberration_moving") ?: throw AssertionError("Moving is registered")
        var s = openFilter(moving, "chromatic")
        assertArrayEquals("RGB at first", FringeColors.rgb, s.values.colors(FringeColors.KEY))
        assertTrue(lit(FringeLabels18.RGB))
        val v17 = Fx.roundTrip(ChromaticAberrationV17.moving(BitmapUtils.toPixelBuffer(srcBmp), s.values.copy().set(FringeColors.LEGACY_ORDER_KEY, 0), ctx()))
        var shown = preview(s, "moving, defaults")
        assertTrue("on a transparent layer the defaults are v1.7's Moving byte for byte: ${Fx.diff(v17, shown, w)}", v17.contentEquals(shown))
        val beside = src.indices.count { a(src[it]) == 0 && a(shown[it]) > 0 }
        assertTrue("fringes on the transparent pixels beside the stroke ($beside px)", beside > 200)
        assertTrue("far from the stroke nothing", clearPx.all { shown[it] == 0 })

        // ================================================================ Moving: Red/Cyan, the alpha rule
        tapChip(FringeLabels18.RED_CYAN)
        assertArrayEquals(intArrayOf(FringeColors.RED, FringeColors.NONE, FringeColors.CYAN), s.values.colors(FringeColors.KEY))
        assertTrue(lit(FringeLabels18.RED_CYAN))
        val redCyan = preview(s, "moving, Red/Cyan")
        val rcExpected = direct(moving, srcBmp, s.values)
        assertTrue("Red/Cyan: the preview is the filter's result: ${Fx.diff(rcExpected, redCyan, w)}", rcExpected.contentEquals(redCyan))
        assertEquals("Red/Cyan: green and blue move together", 0, redCyan.count { g(it) != b(it) })
        assertTrue("far from the stroke nothing", clearPx.all { redCyan[it] == 0 })
        // Beside the stroke (transparent there): red only where the forward sample has ink, cyan only where the backward one has.
        var redOnly = 0; var cyanOnly = 0; var other = 0
        for (i in src.indices) {
            if (a(src[i]) != 0 || a(redCyan[i]) == 0) continue
            val p = redCyan[i]
            when {
                r(p) > 0 && g(p) == 0 -> redOnly++
                r(p) == 0 && g(p) > 0 -> cyanOnly++
                else -> other++
            }
        }
        println("Qa18LookFringeTransparent: Red/Cyan beside the stroke: $redOnly red, $cyanOnly cyan, $other both")
        assertTrue("red fringes ($redOnly px) and cyan fringes ($cyanOnly px) beside the stroke", redOnly > 100 && cyanOnly > 100)
        assertTrue("beside the stroke, mostly one color or the other ($other px both)", other * 4 < redOnly + cyanOnly)
        saveOverWhite(redCyan, w, h, "look-fringe-transparent-moving.png")

        // The first swatch one step off red (#FE0000): the general path, the same weights: the same picture.
        pick(FringeLabels18.FIRST, "FE0000", FringeLabels18.FIRST)
        assertArrayEquals(intArrayOf(0xFFFE0000.toInt(), FringeColors.NONE, FringeColors.CYAN), s.values.colors(FringeColors.KEY))
        for (p in chips) assertFalse("#FE0000: no chip lit ($p)", lit(p))
        assertNull("#FE0000 runs the general path", FringeColors.oneHotFactors(s.values.colors(FringeColors.KEY)))
        val nearRed = preview(s, "moving, #FE0000/none/cyan")
        assertTrue("the general path: the preview is the filter's result", direct(moving, srcBmp, s.values).contentEquals(nearRed))
        val off = premultipliedOff(redCyan, nearRed)
        println("Qa18LookFringeTransparent: #FE0000 vs Red/Cyan: off by $off, ${redCyan.indices.count { redCyan[it] != nearRed[it] }} px differ")
        assertTrue("a first color one step off red gives the Red/Cyan picture on a transparent layer (off by $off)", off <= 1)
        tapChip(FringeLabels18.RED_CYAN)
        assertTrue(lit(FringeLabels18.RED_CYAN))
        shown = preview(s, "moving, Red/Cyan again")
        assertTrue("Red/Cyan again", shown.contentEquals(redCyan))
        applyFilter(s, redCyan)
        undoTo(src, "Moving Red/Cyan")

        // ================================================================ Glitch: v1.7 at its defaults
        val glitch = FilterRegistry.byId("art.glitch") ?: throw AssertionError("Glitch is registered")
        s = openFilter(glitch, "glitch")
        val defaults = s.values.copy()
        val g17 = Fx.roundTrip(GlitchV17.apply(BitmapUtils.toPixelBuffer(srcBmp), defaults.copy(), ctx()))
        shown = preview(s, "glitch, defaults")
        assertTrue("on a transparent layer Glitch's defaults are v1.7's bytes: ${Fx.diff(g17, shown, w)}", g17.contentEquals(shown))

        // Small bands, full static, Magenta/Green, the static at half opacity.
        setSlider("Height", 6f)
        setSlider("Static", 100f)
        tapChip(FringeLabels18.MAGENTA_GREEN)
        assertArrayEquals(intArrayOf(FringeColors.MAGENTA, FringeColors.NONE, FringeColors.GREEN), s.values.colors(FringeColors.KEY))
        pick("Choose color of the static", "80FF2040", FringeLabels18.STATIC_COLOR)
        assertEquals("the static's color, half opaque", 0x80FF2040.toInt(), s.values.color(GlitchFilter.STATIC_COLOR_KEY))
        assertEquals("its swatch says so", ColorUtils.toHex(0x80FF2040.toInt()), RobolectricUi.byDescription("Choose color of the static").stateDescription)
        assertEquals("#80FF2040", ColorUtils.toHex(0x80FF2040.toInt()))
        val half = preview(s, "glitch, half static")
        assertTrue("half static: the preview is the filter's result", direct(glitch, srcBmp, s.values).contentEquals(half))
        IbisShots.capture().let { save(it, "look-fringe-transparent-screen.png") }
        val base = direct(glitch, srcBmp, s.values.copy().set(GlitchFilter.STATIC_COLOR_KEY, 0x00FF2040))
        val full = direct(glitch, srcBmp, s.values.copy().set(GlitchFilter.STATIC_COLOR_KEY, 0xFFFF2040.toInt()))
        val dHalf = deviation(half, base)
        val dFull = deviation(full, base)
        println("Qa18LookFringeTransparent: static deviation half $dHalf, opaque $dFull")
        assertTrue("the opaque static shows on the stroke ($dFull)", dFull > 5_000)
        assertTrue("the static at half opacity is about half as strong: $dHalf of $dFull", dHalf.toDouble() / dFull in 0.35..0.65)
        assertTrue("the static never changes the alpha", half.indices.all { a(half[it]) == a(base[it]) })
        assertTrue("nor paints the transparent pixels", half.indices.all { a(base[it]) != 0 || half[it] == 0 })
        saveOverWhite(half, w, h, "look-fringe-transparent-glitch.png")

        // "New random random seed": the colors kept, the bands elsewhere.
        val seed0 = s.values.seed()
        val rows0 = changedRows(src, half, w)
        visible { RobolectricUi.byDescription("New random") }.tap()
        settle(3)
        assertNotEquals("a new seed", seed0, s.values.seed())
        assertTrue("the new seed is shown", has(s.values.seed().toString(), exact = true))
        assertArrayEquals("the fringe colors kept", intArrayOf(FringeColors.MAGENTA, FringeColors.NONE, FringeColors.GREEN), s.values.colors(FringeColors.KEY))
        assertTrue(lit(FringeLabels18.MAGENTA_GREEN))
        assertEquals("the static kept", 0x80FF2040.toInt(), s.values.color(GlitchFilter.STATIC_COLOR_KEY))
        val reseeded = preview(s, "glitch, new seed")
        assertTrue("new seed: the preview is the filter's result", direct(glitch, srcBmp, s.values).contentEquals(reseeded))
        assertNotEquals("the bands follow the seed", rows0, changedRows(src, reseeded, w))

        // "Compare with the original (press and hold)": a finger held on it.
        val steps = u.steps()
        val cmp = visible { RobolectricUi.byDescription("Compare with the original (press and hold)") }
        val down = finger(cmp, MotionEvent.ACTION_DOWN, 0L)
        holdFor(400)
        settle(2)
        assertTrue("held: comparing", s.isComparing)
        val held = Fx.overrideContent(c)
        assertTrue("held: the canvas shows the original", held == null || held.contentEquals(src))
        finger(cmp, MotionEvent.ACTION_UP, down)
        holdFor(50)
        settle(3)
        assertFalse("released: not comparing", s.isComparing)
        assertTrue("released: the preview again", preview(s, "after compare").contentEquals(reseeded))
        assertEquals("compare records no step", steps, u.steps())

        // The static's swatch tapped, then "Cancel": nothing changes.
        visible { RobolectricUi.byDescription("Choose color of the static") }.tap()
        settle(3)
        assertTrue("the static's picker is open", has("OK", exact = true))
        SmokeUi.tap("Cancel", exact = true)
        settle(3)
        assertFalse("the picker closed", has("OK", exact = true))
        assertEquals("Cancel keeps the static", 0x80FF2040.toInt(), s.values.color(GlitchFilter.STATIC_COLOR_KEY))
        assertTrue("Cancel keeps the preview", preview(s, "after the picker's Cancel").contentEquals(reseeded))
        assertEquals(steps, u.steps())

        // "Reset to defaults": RGB, the static white, v1.7's bytes.
        visible { RobolectricUi.byDescription("Reset to defaults") }.tap()
        settle(3)
        assertArrayEquals("reset: RGB", FringeColors.rgb, s.values.colors(FringeColors.KEY))
        assertTrue("reset: the RGB chip lit", lit(FringeLabels18.RGB))
        assertEquals("reset: the static white", FringeColors.WHITE, s.values.color(GlitchFilter.STATIC_COLOR_KEY))
        assertEquals("reset: its swatch says so", ColorUtils.toHex(FringeColors.WHITE), RobolectricUi.byDescription("Choose color of the static").stateDescription)
        assertEquals("reset: the seed", defaults.seed(), s.values.seed())
        shown = preview(s, "glitch, reset")
        assertTrue("reset: v1.7's bytes again: ${Fx.diff(g17, shown, w)}", g17.contentEquals(shown))

        // "Cancel filter": no step, the layer as it was.
        visible { RobolectricUi.byDescription("Cancel filter") }.tap()
        Fx.waitUntil("Glitch cancelled") { settle(1); s.isClosed }
        settle()
        assertNull(c.filterSession)
        assertEquals("Cancel filter records no step", steps, u.steps())
        assertArrayEquals("the layer as it was", src, Fx.pixels(layer.bitmap))
        Fx.assertNoErrorMessage(c, "fringe colors on a transparent layer")
        Smoke.assertQuiet(c, "fringe colors on a transparent layer")
    }

    /** [px] over white, at 2x. */
    private fun saveOverWhite(px: IntArray, w: Int, h: Int, file: String) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(0xFFFFFFFF.toInt()); drawBitmap(b, 0f, 0f, Paint()) }
        save(Bitmap.createScaledBitmap(out, w * 2, h * 2, false), file)
    }

    private companion object {
        const val INK = 0xFF808080.toInt()

        private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

        fun save(b: Bitmap, file: String): String? {
            if (SHOTS.parentFile?.isDirectory != true) return null
            SHOTS.mkdirs()
            val f = File(SHOTS, file)
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }
    }
}
