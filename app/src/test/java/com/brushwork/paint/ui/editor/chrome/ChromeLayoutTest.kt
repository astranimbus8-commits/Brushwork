package com.brushwork.paint.ui.editor.chrome

import com.brushwork.paint.ui.editor.SliderMath
import com.brushwork.paint.ui.editor.SliderRowGeometry
import com.brushwork.paint.ui.editor.chrome.ChromeLayout.TopSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ibisPaint main-screen geometry (v1.6 §3.7.2; JVM): every band on the 392 × 873 dp reference
 * phone (status bar 33, navigation bar 42) as the design measured it (V13), the narrow-screen
 * compaction rules, and the brush slider math the rows use.
 */
class ChromeLayoutTest {
    private val s = 33f
    private val n = 42f
    private val h = 873f

    @Test
    fun bandsOfTheReferencePhone() {
        assertEquals(81f, ChromeLayout.topRowBottom(s), 0.01f)
        assertEquals(85f, ChromeLayout.optionsStripTop(s), 0.01f)
        assertEquals(135f, ChromeLayout.pillTop(s), 0.01f)
        assertEquals("the selection bar at the pill's place", 135f, ChromeLayout.selectionBarTop(s, 0f), 0.01f)
        assertEquals("the selection bar under a 32 dp pill", 173f, ChromeLayout.selectionBarTop(s, 32f), 0.01f)
        assertEquals("fit inset top", 137f, ChromeLayout.fitInsetTop(s), 0.01f)
        assertEquals("fit inset bottom = 873 − 701", 172f, ChromeLayout.fitInsetBottom(n), 0.01f)
        assertEquals("size row", 701f, ChromeLayout.sliderRowsTop(h, n), 0.01f)
        assertEquals("bottom bar", 781f, ChromeLayout.bottomBarTop(h, n), 0.01f)
        assertEquals("7 slots of 56", 56f, ChromeLayout.bottomSlotWidth(392f), 0.01f)
    }

    @Test
    fun wideScreensPutTheSlidersInOneRow() {
        // Phones (392, 360) keep size over opacity; a phone in landscape and tablets use one row.
        assertEquals(2, ChromeLayout.sliderRowCount(392f))
        assertEquals(2, ChromeLayout.sliderRowCount(360f))
        assertEquals(2, ChromeLayout.sliderRowCount(559f))
        assertEquals(1, ChromeLayout.sliderRowCount(560f))
        assertEquals(1, ChromeLayout.sliderRowCount(873f))
        assertEquals("one row: 40 dp less fit inset", 132f, ChromeLayout.fitInsetBottom(n, rows = 1), 0.01f)
        assertEquals("its row sits on the bar", 741f, ChromeLayout.sliderRowsTop(h, n, rows = 1), 0.01f)
        // Each half of the row keeps the phone geometry: value 0–58, − at 73, track from 92, + 17 from its end.
        val half = SliderRowGeometry((873f - 12f) / 2f, leftHanded = false)
        assertEquals(73f, half.minusCenter, 0.01f)
        assertEquals(92f, half.trackStart, 0.01f)
        assertEquals((873f - 12f) / 2f - 37f, half.trackEnd, 0.01f)
    }

    @Test
    fun topRowAt392And360() {
        val wide = ChromeLayout.topRow(392f)
        assertEquals(48f, wide.pitch, 0.01f)
        assertEquals(40f, wide.circle, 0.01f)
        assertEquals(TopSlot.entries.toList(), wide.slots)
        assertTrue(wide.folded.isEmpty())
        // Centres at 24, 72, then 128 + 48·(i − 2): ibisPaint's 8 dp gap after Redo (v16 polish).
        assertEquals(8f, wide.gap, 0.01f)
        for (i in 0 until 8) assertEquals(24f + 48f * i + (if (i >= 2) 8f else 0f), wide.centerX(i), 0.01f)
        val narrow = ChromeLayout.topRow(360f)
        assertEquals("pitch = (360 − 8) / 8", 44f, narrow.pitch, 0.01f)
        assertEquals(36f, narrow.circle, 0.01f)
        assertEquals(8, narrow.slots.size)
        assertTrue("touch targets ≥ 44 dp at 360 dp", narrow.pitch >= 44f)
        // Tablets and landscape: the 48 dp pitch, left-aligned.
        assertEquals(48f, ChromeLayout.topRow(800f).pitch, 0.01f)
    }

    @Test
    fun narrowScreensFoldRulerThenGridThenStabilizerIntoMore() {
        assertTrue("336 dp keeps all 8", ChromeLayout.topRow(336f).folded.isEmpty())
        val r335 = ChromeLayout.topRow(335f)
        assertEquals(listOf(TopSlot.RULER), r335.folded)
        assertFalse(TopSlot.RULER in r335.slots)
        assertEquals("More stays last", TopSlot.MORE, r335.slots.last())
        val r280 = ChromeLayout.topRow(280f)
        assertTrue("more fold as the width shrinks: ${r280.folded}", r280.folded.size >= 2)
        assertEquals(ChromeLayout.FOLD_ORDER.take(r280.folded.size), r280.folded)
        val tiny = ChromeLayout.topRow(200f)
        assertEquals("at most three fold", ChromeLayout.FOLD_ORDER, tiny.folded)
        for (w in listOf(335f, 320f, 300f, 280f, 260f, 240f)) {
            val r = ChromeLayout.topRow(w)
            assertTrue("$w: the row fits", r.pitch * r.slots.size <= w - 8f + 0.01f)
            assertTrue("$w: with the gap after Redo", r.pitch * r.slots.size + r.gap <= w + 0.01f && r.gap >= 0f)
            assertTrue("$w: targets ≥ 40 dp (${r.pitch})", r.pitch >= 40f)
            assertTrue("$w: circles 36..40", r.circle in 36f..40f)
        }
    }

    @Test
    fun layerWindowSizingContract() {
        val lw = ChromeLayout.layerWindow(392f, h, s, n)
        assertFalse(lw.short)
        assertEquals(5f, lw.x, 0.01f)
        assertEquals(382f, lw.width, 0.01f)
        assertEquals(520f, lw.height, 0.01f)
        assertEquals("its bottom on the bar's top", 781f, lw.bottom, 0.01f)
        assertEquals("y 261 on the reference phone", 261f, lw.top, 0.01f)
        // Narrower: w − 10; shorter: the room under the top row minus 8.
        assertEquals(350f, ChromeLayout.layerWindow(360f, 760f, 24f, 48f).width, 0.01f)
        val short = ChromeLayout.layerWindow(392f, 600f, 24f, 0f)
        assertEquals(600f - 50f - 24f - 48f - 8f, short.height, 0.01f)
        assertTrue("landscape phones keep the v1.5 window", ChromeLayout.layerWindow(873f, 392f, 0f, 0f).short)
    }

    @Test
    fun toolMenuHeightAndPlacement() {
        assertEquals(434f, ChromeLayout.toolMenuMaxHeight(h, s, n), 0.01f)
        // A short screen: never over the top row.
        val m = ChromeLayout.toolMenuMaxHeight(500f, 24f, 0f)
        assertTrue("$m", m <= 500f - 50f - 16f - 72f)
    }

    @Test
    fun sliderRowGeometryMatchesTheReference() {
        val g = SliderRowGeometry(392f, leftHanded = false)
        assertEquals(0f, g.valueX, 0.01f)
        assertEquals(58f, g.valueWidth, 0.01f)
        assertEquals(73f, g.minusCenter, 0.01f)
        assertEquals(92f, g.trackStart, 0.01f)
        assertEquals(355f, g.trackEnd, 0.01f)
        assertEquals(375f, g.plusCenter, 0.01f)
        assertEquals("the + box stays on the row", 352f, g.buttonX(g.plusCenter), 0.01f)
        // Left-handed: mirrored, values on the right.
        val l = SliderRowGeometry(392f, leftHanded = true)
        assertEquals(334f, l.valueX, 0.01f)
        assertTrue(l.minusCenter < l.trackStart && l.trackEnd < l.plusCenter && l.plusCenter < l.valueX + 20f)
    }

    @Test
    fun brushSliderIncrements() {
        // Off: the v1.5 steps.
        assertEquals(SliderMath.stepSize(20f, true), SliderMath.stepSizeBy(20f, true, Float.NaN))
        // Size step 5 px: next multiples, off-step values go to the nearest multiple in that direction.
        assertEquals(25f, SliderMath.stepSizeBy(20f, true, 5f))
        assertEquals(15f, SliderMath.stepSizeBy(20f, false, 5f))
        assertEquals(25f, SliderMath.stepSizeBy(22f, true, 5f))
        assertEquals(20f, SliderMath.stepSizeBy(22f, false, 5f))
        assertEquals("the range ends stay reachable", 0.5f, SliderMath.stepSizeBy(3f, false, 5f))
        assertEquals(1000f, SliderMath.stepSizeBy(998f, true, 5f))
        // Slider positions land on multiples, ends reachable.
        assertEquals(20f, SliderMath.snapSize(21.4f, 5f))
        assertEquals(0.5f, SliderMath.snapSize(0.6f, 5f))
        assertEquals(1000f, SliderMath.snapSize(999f, 7f))
        // Opacity by 5 %.
        assertEquals(0.55f, SliderMath.stepPercentBy(0.5f, true, 5f), 1e-6f)
        assertEquals(0.45f, SliderMath.stepPercentBy(0.5f, false, 5f), 1e-6f)
        assertEquals(0.55f, SliderMath.stepPercentBy(0.53f, true, 5f), 1e-6f)
        assertEquals(1f, SliderMath.stepPercentBy(0.99f, true, 5f), 1e-6f)
        assertEquals(0f, SliderMath.stepPercentBy(0.02f, false, 5f), 1e-6f)
        // The ibisPaint readout keeps one decimal.
        assertEquals("72.0", SliderMath.formatSizeFixed(72f))
        assertEquals("2.5", SliderMath.formatSizeFixed(2.5f))
        assertEquals("1000.0", SliderMath.formatSizeFixed(1000f))
    }
}
