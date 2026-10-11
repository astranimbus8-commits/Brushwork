package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * v1.8 final QA, cluster "look" (item 5, design §3.6): the fringe colors on the full editor at
 * 392 dp, opened the way the user opens a filter (the Filters tool, its search, the filter's
 * name), every chip and swatch touched with a finger.
 *
 * Chromatic aberration (Moving), on a black-and-gray drawing on white:
 * - opened at its defaults (RGB, the chip lit), the preview is v1.7's output byte for byte (the
 *   frozen v1.7 filter);
 * - each preset chip fills the three swatches (their spoken states name the colors) and lights
 *   alone; the preview is the filter's own result, and the fringes are the preset's: Red/Cyan
 *   keeps green = blue on the gray drawing, Magenta/Green red = blue, Blue/Yellow red = green;
 *   RGB again gives v1.7's bytes again;
 * - a custom set: the middle swatch picked (its dialog titled after it), then "No color" beside
 *   it, then first orange and last azure (typed hex). No chip lights; green is the mean of red
 *   and blue (both swatches hold half of it), flat areas keep their gray;
 * - "Apply filter" is ONE step named after the filter, equal to the preview; a two-finger tap
 *   undoes it, three fingers redo it.
 * Chromatic aberration (Zooming) on a rectangle selection: the defaults are v1.7's inside the
 * selection, the rest untouched; Blue/Yellow the same way; the apply equals the preview.
 * Glitch, on a colorful picture: the defaults are v1.7's bytes; small bands and full static
 * (the sliders) still are; then Magenta/Green and a red "Color of the static" (its swatch found
 * by its spoken name) change the colors only: the seed shown stays and the same rows change (the
 * seed keeps its layout). The apply equals the preview, one step, undone by two fingers. Then
 * Glitch on the rectangle selection (Magenta/Green): its result inside, the rest untouched, only
 * rows its bands change on the whole picture; the apply equals the preview, undone.
 * PNGs: `look-fringe-*.png` (each preset, the custom set, Zooming on the selection, Glitch whole
 * and on the selection, the panel on the phone).
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.lookfringesandbox"])
class Qa18LookFringeTest {

    @Test
    fun fringeColorsOnChromaticAberrationAndGlitch() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        h.section("fringe colors: presets, v1.7 defaults, a custom set, a selection, Glitch") { flow(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    private lateinit var u: Qa17ArrayUi

    // ------------------------------------------------------------------ the screen

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

    /**
     * The element [find] returns, after scrolling its scrolling ancestors (the panel, the chip
     * row) until it is wholly inside them, as a finger would (FringePanelSessionUiTest).
     */
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
                    // The slide is animated: wait for it to stop, or a finger lands where it was.
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

    /** Whether the chip [name] is lit (selected), as TalkBack reads it. */
    private fun lit(name: String): Boolean {
        val n = chip(name).node
        return n.config.getOrNull(SemanticsProperties.Selected) == true || n.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
    }

    /** A finger on the preset chip [name] (slid into view first). */
    private fun tapChip(name: String) {
        visible { chip(name) }.tap()
        settle(3)
    }

    /** What TalkBack says each fringe swatch holds. */
    private fun swatchStates(): List<String?> =
        listOf(FringeLabels18.FIRST, FringeLabels18.MIDDLE, FringeLabels18.LAST).map { RobolectricUi.byDescription(it).stateDescription }

    private fun spoken(colors: IntArray): List<String> = colors.map { if (it == FringeColors.NONE) FringeLabels18.NO_COLOR else ColorUtils.toHex(it) }

    /** A finger on the swatch described [name]; its color picker opens titled [title] (in its own window); [hex] typed, OK. */
    private fun pick(name: String, hex: String, title: String = name) {
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

    /** The preview as the canvas draws it (document size), once rendered. */
    private fun preview(s: FilterSession, what: String): IntArray {
        Fx.awaitPreview(s, what)
        settle()
        return Fx.overrideContent(u.c) ?: throw AssertionError("$what: no preview drawn")
    }

    /** What [f] gives on [src] for [values], read back through a bitmap (as the apply writes it). */
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

    /** Two fingers on the canvas: the apply is undone and the layer is [src] again. */
    private fun undoTo(src: IntArray, what: String) {
        val before = u.steps()
        u.ui.twoFingerUndo()
        settle()
        assertEquals("$what: one step undone", before - 1, u.steps())
        val now = Fx.pixels(u.c.activeLayer.bitmap)
        assertTrue("$what: undo gives the source back: ${Fx.diff(src, now, u.c.doc.width)}", src.contentEquals(now))
    }

    /** The slider under the text [label] (the next placed node after it that sets a progress), as RequestCoverageV18UiTest. */
    private fun sliderUnder(label: String): RobolectricUi.Element {
        val all = RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }
        val at = all.indexOfLast { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        assertTrue("\"$label\" shows; shown: ${SmokeUi.shown().take(60)}", at >= 0)
        return all.drop(at + 1).firstOrNull { it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
            ?: throw AssertionError("no slider under \"$label\"")
    }

    /** The slider [label] slid into view, then set to [v] by its progress action (as TalkBack sets it). */
    private fun setSlider(label: String, v: Float) {
        val e = visible { sliderUnder(label) }
        requireNotNull(e.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(v)
        settle(4)
    }

    // ------------------------------------------------------------------ pixels

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    /** Pixels where [a] and [b] channels differ, with the first one, for messages. */
    private fun channelsDiffer(px: IntArray, a: (Int) -> Int, b: (Int) -> Int): Int = px.count { a(it) != b(it) }

    /** Rows of [out] that differ from [src] ([w] wide). */
    private fun changedRows(src: IntArray, out: IntArray, w: Int): Set<Int> =
        (0 until src.size / w).filter { y -> (0 until w).any { x -> src[y * w + x] != out[y * w + x] } }.toSet()

    /**
     * Pixels of [src] whose 13 x 13 neighbourhood is one color and that lie 8 px or more inside
     * the picture: there, any shift of up to 6 px reads the same color.
     */
    private fun flat(src: IntArray, w: Int, h: Int): List<Int> {
        val out = ArrayList<Int>()
        for (y in 8 until h - 8) for (x in 8 until w - 8) {
            val v = src[y * w + x]
            var same = true
            loop@ for (dy in -6..6) for (dx in -6..6) if (src[(y + dy) * w + x + dx] != v) { same = false; break@loop }
            if (same) out += y * w + x
        }
        return out
    }

    // ------------------------------------------------------------------ the flow

    private fun flow(ui: Qa17ArrayUi) {
        u = ui
        u.editor()
        val c = u.c
        val w = c.doc.width
        val h = c.doc.height
        val layer = c.activeLayer

        // What the flow starts from: a black and gray drawing on white, opaque (every pixel gray).
        assertTrue(c.editWholeLayer(layer, "Seed") { bmp -> grayDrawing(bmp) })
        settle()
        val gray = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val grayPx = Fx.pixels(gray)
        assertTrue("the drawing is gray and opaque", grayPx.all { it ushr 24 == 0xFF && r(it) == g(it) && g(it) == b(it) })
        assertTrue("the drawing has edges", grayPx.count { it != -1 } > 5000)
        val flatPx = flat(grayPx, w, h)
        assertTrue("flat areas to check: ${flatPx.size}", flatPx.size > 20_000)

        // ================================================================ Moving: v1.7 at its defaults
        val moving = FilterRegistry.byId("art.chromatic_aberration_moving") ?: throw AssertionError("Moving is registered")
        var s = openFilter(moving, "chromatic")
        assertArrayEquals("RGB at first", FringeColors.rgb, s.values.colors(FringeColors.KEY))
        assertTrue("the RGB chip is lit", lit(FringeLabels18.RGB))
        assertEquals("the swatches say red, green, blue", spoken(FringeColors.rgb), swatchStates())
        val v17 = Fx.roundTrip(ChromaticAberrationV17.moving(BitmapUtils.toPixelBuffer(gray), s.values.copy().set(FringeColors.LEGACY_ORDER_KEY, 0), ctx()))
        assertFalse("v1.7's Moving changes the drawing", v17.contentEquals(grayPx))
        var shown = preview(s, "moving, defaults")
        assertTrue("the defaults preview v1.7's Moving byte for byte: ${Fx.diff(v17, shown, w)}", v17.contentEquals(shown))
        save(shown, w, h, "look-fringe-moving-rgb.png")
        // The panel as the phone shows it.
        IbisShots.capture().let { save(it, "look-fringe-panel-screen.png") }

        // ================================================================ Moving: each preset chip
        val presets = listOf(
            Triple(FringeLabels18.RED_CYAN, intArrayOf(FringeColors.RED, FringeColors.NONE, FringeColors.CYAN), "red-cyan"),
            Triple(FringeLabels18.MAGENTA_GREEN, intArrayOf(FringeColors.MAGENTA, FringeColors.NONE, FringeColors.GREEN), "magenta-green"),
            Triple(FringeLabels18.BLUE_YELLOW, intArrayOf(FringeColors.BLUE, FringeColors.NONE, FringeColors.YELLOW), "blue-yellow"),
        )
        for ((name, colors, file) in presets) {
            tapChip(name)
            assertArrayEquals("$name fills the three swatches", colors, s.values.colors(FringeColors.KEY))
            assertEquals("$name: the swatches say so", spoken(colors), swatchStates())
            for (other in listOf(FringeLabels18.RGB, FringeLabels18.RED_CYAN, FringeLabels18.MAGENTA_GREEN, FringeLabels18.BLUE_YELLOW)) {
                assertEquals("$name: the chip $other lit or not", other == name, lit(other))
            }
            shown = preview(s, "moving, $name")
            val expected = direct(moving, gray, s.values)
            assertTrue("$name: the preview is the filter's result: ${Fx.diff(expected, shown, w)}", expected.contentEquals(shown))
            // The fringes are the preset's: on gray, the two channels of the one color move together.
            val (same, fringe) = when (name) {
                FringeLabels18.RED_CYAN -> channelsDiffer(shown, ::g, ::b) to channelsDiffer(shown, ::r, ::g)
                FringeLabels18.MAGENTA_GREEN -> channelsDiffer(shown, ::r, ::b) to channelsDiffer(shown, ::r, ::g)
                else -> channelsDiffer(shown, ::r, ::g) to channelsDiffer(shown, ::b, ::r)
            }
            assertEquals("$name: the second color's channels stay together", 0, same)
            assertTrue("$name: fringes show ($fringe px)", fringe > 1000)
            assertTrue("$name: flat areas keep their gray", flatPx.all { shown[it] == grayPx[it] })
            println("Qa18LookFringe: Moving $name: $fringe fringe px")
            save(shown, w, h, "look-fringe-moving-$file.png")
        }
        // RGB again: v1.7's bytes again.
        tapChip(FringeLabels18.RGB)
        assertArrayEquals(FringeColors.rgb, s.values.colors(FringeColors.KEY))
        assertTrue(lit(FringeLabels18.RGB))
        shown = preview(s, "moving, RGB again")
        assertTrue("RGB again is v1.7's Moving: ${Fx.diff(v17, shown, w)}", v17.contentEquals(shown))

        // ================================================================ Moving: a custom set
        tapChip(FringeLabels18.BLUE_YELLOW)
        pick(FringeLabels18.MIDDLE, "808080")
        assertArrayEquals(intArrayOf(FringeColors.BLUE, 0xFF808080.toInt(), FringeColors.YELLOW), s.values.colors(FringeColors.KEY))
        assertFalse("a picked middle unlights Blue/Yellow", lit(FringeLabels18.BLUE_YELLOW))
        // "No color" beside the middle swatch takes it off again: Blue/Yellow lights again.
        u.fingerPress(FringeLabels18.NO_COLOR)
        assertArrayEquals(intArrayOf(FringeColors.BLUE, FringeColors.NONE, FringeColors.YELLOW), s.values.colors(FringeColors.KEY))
        assertTrue(lit(FringeLabels18.BLUE_YELLOW))
        pick(FringeLabels18.FIRST, "FF8000")
        pick(FringeLabels18.LAST, "0080FF")
        val custom = intArrayOf(0xFFFF8000.toInt(), FringeColors.NONE, 0xFF0080FF.toInt())
        assertArrayEquals("the custom set", custom, s.values.colors(FringeColors.KEY))
        assertEquals(spoken(custom), swatchStates())
        for (p in listOf(FringeLabels18.RGB, FringeLabels18.RED_CYAN, FringeLabels18.MAGENTA_GREEN, FringeLabels18.BLUE_YELLOW)) assertFalse("no chip lit for a custom set: $p", lit(p))
        shown = preview(s, "moving, custom")
        val customExpected = direct(moving, gray, s.values)
        assertTrue("custom: the preview is the filter's result: ${Fx.diff(customExpected, shown, w)}", customExpected.contentEquals(shown))
        // Red comes from the first fringe, blue from the last, green half from each.
        val offMean = shown.count { abs(2 * g(it) - r(it) - b(it)) > 2 }
        assertEquals("custom: green is the mean of red and blue", 0, offMean)
        val split = channelsDiffer(shown, ::r, ::b)
        assertTrue("custom: fringes show ($split px)", split > 1000)
        assertTrue("custom: flat areas keep their gray", flatPx.all { p -> listOf(16, 8, 0).all { sh -> abs(((shown[p] shr sh) and 0xFF) - ((grayPx[p] shr sh) and 0xFF)) <= 1 } })
        save(shown, w, h, "look-fringe-moving-custom.png")

        // Apply: one step, the preview; two fingers undo, three redo.
        applyFilter(s, shown)
        val applied = Fx.pixels(layer.bitmap)
        undoTo(grayPx, "Moving")
        u.ui.threeFingerRedo()
        settle()
        assertEquals(moving.name, c.undoManager.undoLabel)
        assertTrue("redo gives the custom result back", Fx.pixels(layer.bitmap).contentEquals(applied))
        undoTo(grayPx, "Moving again")

        // ================================================================ Zooming on a selection
        val zooming = FilterRegistry.byId("art.chromatic_aberration_zooming") ?: throw AssertionError("Zooming is registered")
        val sl = 40; val st = 30; val sr = 300; val sb = 250
        c.setSelection(u.rectSelection(sl.toFloat(), st.toFloat(), sr.toFloat(), sb.toFloat()), recordUndo = false)
        settle(4)
        s = openFilter(zooming, "chromatic")
        assertArrayEquals("RGB at first", FringeColors.rgb, s.values.colors(FringeColors.KEY))
        fun inSel(i: Int) = (i % w) in sl until sr && (i / w) in st until sb
        fun masked(full: IntArray) = IntArray(grayPx.size) { if (inSel(it)) full[it] else grayPx[it] }
        val z17 = Fx.roundTrip(ChromaticAberrationV17.zooming(BitmapUtils.toPixelBuffer(gray), s.values.copy().set(FringeColors.LEGACY_ORDER_KEY, 0), ctx()))
        shown = preview(s, "zooming, defaults")
        val zExpected = masked(z17)
        assertFalse("v1.7's Zooming changes the selection", zExpected.contentEquals(grayPx))
        assertTrue("Zooming's defaults are v1.7's inside the selection, the rest untouched: ${Fx.diff(zExpected, shown, w)}", zExpected.contentEquals(shown))
        tapChip(FringeLabels18.BLUE_YELLOW)
        assertArrayEquals(intArrayOf(FringeColors.BLUE, FringeColors.NONE, FringeColors.YELLOW), s.values.colors(FringeColors.KEY))
        shown = preview(s, "zooming, Blue/Yellow")
        val zbExpected = masked(direct(zooming, gray, s.values))
        assertTrue("Zooming Blue/Yellow on the selection: ${Fx.diff(zbExpected, shown, w)}", zbExpected.contentEquals(shown))
        assertEquals("Blue/Yellow: red and green together", 0, channelsDiffer(shown, ::r, ::g))
        assertTrue("Blue/Yellow fringes inside the selection", (0 until shown.size).count { inSel(it) && b(shown[it]) != r(shown[it]) } > 500)
        assertTrue("nothing outside the selection changes", (0 until shown.size).all { inSel(it) || shown[it] == grayPx[it] })
        save(shown, w, h, "look-fringe-zooming-blue-yellow-selection.png")
        applyFilter(s, shown)
        undoTo(grayPx, "Zooming")
        c.setSelection(null, recordUndo = false)
        settle(4)

        // ================================================================ Glitch
        assertTrue(c.editWholeLayer(layer, "Seed") { bmp -> bmp.setPixels(Fx.paintPixels(w, h), 0, w, 0, 0, w, h) })
        settle()
        val pic = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val picPx = Fx.pixels(pic)
        val glitch = FilterRegistry.byId("art.glitch") ?: throw AssertionError("Glitch is registered")
        s = openFilter(glitch, "glitch")
        assertArrayEquals("RGB at first", FringeColors.rgb, s.values.colors(FringeColors.KEY))
        assertEquals("the static is white at first", FringeColors.WHITE, s.values.color(GlitchFilter.STATIC_COLOR_KEY))
        assertEquals("its swatch says so", ColorUtils.toHex(FringeColors.WHITE), RobolectricUi.byDescription("Choose color of the static").stateDescription)
        shown = preview(s, "glitch, defaults")
        val g17 = Fx.roundTrip(GlitchV17.apply(BitmapUtils.toPixelBuffer(pic), s.values.copy(), ctx()))
        assertTrue("Glitch's defaults are v1.7's bytes: ${Fx.diff(g17, shown, w)}", g17.contentEquals(shown))
        // Small bands and full static (the sliders): every kind of band shows; still v1.7's bytes.
        setSlider("Height", 6f)
        setSlider("Static", 100f)
        assertEquals(6f, s.values.float("height"), 0f)
        assertEquals(100f, s.values.float("noise"), 0f)
        shown = preview(s, "glitch, small bands")
        val g17b = Fx.roundTrip(GlitchV17.apply(BitmapUtils.toPixelBuffer(pic), s.values.copy(), ctx()))
        assertTrue("small bands, full static: v1.7's bytes: ${Fx.diff(g17b, shown, w)}", g17b.contentEquals(shown))
        val rows17 = changedRows(picPx, g17b, w)
        assertTrue("bands and untouched rows both: ${rows17.size} of $h rows change", rows17.size in 20 until h - 20)
        val seed = s.values.seed()
        assertTrue("the seed is shown", has(seed.toString(), exact = true))
        // The colors: Magenta/Green, and a red static picked on its swatch (found by its spoken name).
        tapChip(FringeLabels18.MAGENTA_GREEN)
        assertArrayEquals(intArrayOf(FringeColors.MAGENTA, FringeColors.NONE, FringeColors.GREEN), s.values.colors(FringeColors.KEY))
        assertTrue(lit(FringeLabels18.MAGENTA_GREEN))
        pick("Choose color of the static", "FF2040", title = FringeLabels18.STATIC_COLOR)
        assertEquals("the static's color", 0xFFFF2040.toInt(), s.values.color(GlitchFilter.STATIC_COLOR_KEY))
        assertEquals(ColorUtils.toHex(0xFFFF2040.toInt()), RobolectricUi.byDescription("Choose color of the static").stateDescription)
        shown = preview(s, "glitch, colors")
        val gExpected = direct(glitch, pic, s.values)
        assertTrue("Glitch with colors: the preview is the filter's result: ${Fx.diff(gExpected, shown, w)}", gExpected.contentEquals(shown))
        assertFalse("the colors reach the result", shown.contentEquals(g17b))
        // The same seed keeps its layout: the seed shown is the same, and the same rows change.
        assertEquals("the seed stays", seed, s.values.seed())
        assertTrue("the seed is still shown", has(seed.toString(), exact = true))
        assertEquals("the same rows change (the bands are the seed's)", rows17, changedRows(picPx, shown, w))
        save(shown, w, h, "look-fringe-glitch.png")
        applyFilter(s, shown)
        undoTo(picPx, "Glitch")

        // ================================================================ Glitch on a selection
        c.setSelection(u.rectSelection(sl.toFloat(), st.toFloat(), sr.toFloat(), sb.toFloat()), recordUndo = false)
        settle(4)
        s = openFilter(glitch, "glitch")
        setSlider("Height", 6f)
        setSlider("Static", 100f)
        tapChip(FringeLabels18.MAGENTA_GREEN)
        assertArrayEquals(intArrayOf(FringeColors.MAGENTA, FringeColors.NONE, FringeColors.GREEN), s.values.colors(FringeColors.KEY))
        shown = preview(s, "glitch on a selection")
        fun maskedPic(full: IntArray) = IntArray(picPx.size) { if (inSel(it)) full[it] else picPx[it] }
        val gsExpected = maskedPic(direct(glitch, pic, s.values))
        assertTrue("Glitch on the selection: its result inside, the rest untouched: ${Fx.diff(gsExpected, shown, w)}", gsExpected.contentEquals(shown))
        assertTrue("nothing outside the selection changes", (0 until shown.size).all { inSel(it) || shown[it] == picPx[it] })
        val selRows = changedRows(picPx, shown, w)
        assertTrue("Glitch changes rows inside the selection: $selRows", selRows.isNotEmpty() && selRows.all { it in st until sb })
        // The seed keeps its layout under a selection too: the rows that change are rows its bands change on the whole picture.
        val rowsAll = changedRows(picPx, Fx.roundTrip(GlitchV17.apply(BitmapUtils.toPixelBuffer(pic), s.values.copy(), ctx())), w)
        assertTrue("rows changed in the selection are v1.7's band rows: ${selRows - rowsAll}", rowsAll.containsAll(selRows))
        save(shown, w, h, "look-fringe-glitch-selection.png")
        applyFilter(s, shown)
        undoTo(picPx, "Glitch on a selection")
        c.setSelection(null, recordUndo = false)
        settle(4)
        Fx.assertNoErrorMessage(c, "fringe colors")
        Smoke.assertQuiet(c, "fringe colors")
    }

    /** White, with a black disc holed white, a gray block and a dark bar: edges in every direction. */
    private fun grayDrawing(bmp: Bitmap) {
        val cv = Canvas(bmp)
        cv.drawColor(0xFFFFFFFF.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF000000.toInt(); cv.drawCircle(110f, 150f, 70f, p)
        p.color = 0xFFFFFFFF.toInt(); cv.drawCircle(110f, 150f, 28f, p)
        p.color = 0xFF555555.toInt(); cv.drawRect(210f, 50f, 350f, 130f, p)
        p.color = 0xFF202020.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 14f
        cv.drawLine(200f, 260f, 370f, 170f, p)
    }

    private fun save(px: IntArray, w: Int, h: Int, file: String) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        save(Bitmap.createScaledBitmap(b, w * 2, h * 2, false), file)
    }

    private companion object {
        private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

        /** Writes [b] as [file] in the v1.8 QA shots folder when this machine has it; the path, or null. */
        fun save(b: Bitmap, file: String): String? {
            if (SHOTS.parentFile?.isDirectory != true) return null
            SHOTS.mkdirs()
            val f = File(SHOTS, file)
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }
    }
}
