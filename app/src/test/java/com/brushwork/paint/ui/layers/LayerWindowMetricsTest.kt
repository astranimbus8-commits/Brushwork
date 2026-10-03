package com.brushwork.paint.ui.layers

import com.brushwork.paint.ui.theme.IbisDims
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ibisPaint layer window's part sizes (design §3.7.7) for the sizes a host can give it (the
 * window fills its modifier, v1.6 sizing contract). Pure JVM; the Robolectric layout test checks
 * that the composables really take these sizes.
 */
class LayerWindowMetricsTest {

    private fun assertDp(what: String, expected: Float, actual: Float) = assertEquals(what, expected, actual, 0.001f)

    @Test
    fun ibisPaintSizeGivesEveryMeasuredPart() {
        val m = LayerWindowMetrics.of(382f, 520f)
        assertFalse(m.sideBySide)
        assertFalse(m.compact)
        assertDp("header", 46f, m.header)
        assertDp("main block", 360f, m.main)
        assertDp("blend row", 56f, m.blendRow)
        assertDp("opacity row", 48f, m.opacityRow)
        assertDp("bottom pad", 10f, m.bottomPad)
        assertDp("the rows add up to the window", 520f, m.header + m.main + m.blendRow + m.opacityRow + m.bottomPad)
        assertDp("left pad", 8f, m.padStart)
        assertDp("gap after the left column", 6f, m.gap1)
        assertDp("gap before the strip", 2f, m.gap2)
        assertDp("right pad", 6f, m.padEnd)
        assertDp("left column", 100f, m.leftColumn)
        assertEquals(2, m.leftColumns)
        assertDp("preview", 240f, m.preview)
        assertDp("buttons pane", 120f, m.buttonsPane)
        assertDp("list", 220f, m.list)
        assertDp("strip", 40f, m.strip)
        assertDp("the columns add up to the window", 382f, m.padStart + m.leftColumn + m.gap1 + m.list + m.gap2 + m.strip + m.padEnd)
        assertDp("row", 80f, m.row)
        assertDp("thumbnail", 62f, m.thumb)
        assertDp("transparency squares row", 40f, m.transparencyRow)
        assertTrue("80 dp rows stack number, eye line and name", m.tallRows)
        assertFalse("the 9 icons fit the 360 dp strip exactly", m.stripScrolls)
        assertFalse("3 rows of 40 dp buttons fit the 120 dp pane", m.buttonsScroll)
        assertEquals(9 * 40f, IbisDims.LayerStripIcons * IbisDims.LayerStripPitch.value, 0f)
    }

    @Test
    fun aTallerWindowGivesTheRoomToThePreviewAndTheList() {
        val m = LayerWindowMetrics.of(382f, 640f)
        assertDp("main block", 480f, m.main)
        assertDp("preview", 360f, m.preview)
        assertDp("buttons pane keeps its size", 120f, m.buttonsPane)
        assertDp("list width unchanged", 220f, m.list)
        assertFalse(m.stripScrolls)
    }

    @Test
    fun aShorterWindowShrinksThePreviewThenHidesItAndScrollsTheStrip() {
        val short = LayerWindowMetrics.of(382f, 420f)
        assertDp("main block", 260f, short.main)
        assertDp("preview shrinks", 140f, short.preview)
        assertTrue("9 × 40 dp no longer fit: the strip scrolls", short.stripScrolls)
        assertFalse(short.sideBySide)

        val shorter = LayerWindowMetrics.of(382f, 320f)
        assertDp("main block", 160f, shorter.main)
        assertDp("a 40 dp preview is not worth showing", 0f, shorter.preview)
        assertDp("the buttons keep their pane", 120f, shorter.buttonsPane)

        // Never below the minimum main block (the window then clips at the bottom).
        val tiny = LayerWindowMetrics.of(350f, 250f)
        assertFalse("too narrow for side by side", tiny.sideBySide)
        assertDp("minimum main block", LayerWindowMetrics.MIN_MAIN, tiny.main)
        assertDp("no preview", 0f, tiny.preview)
        assertTrue("the buttons pane never exceeds the block", tiny.buttonsPane <= tiny.main)
        assertFalse(tiny.buttonsScroll)
    }

    @Test
    fun aNarrowWindowStacksTheLeftButtonsAndKeepsTheListWide() {
        // The v1.5 host size on the user's phone (300 dp wide) until area E sizes the window.
        val m = LayerWindowMetrics.of(300f, 436f)
        assertTrue(m.compact)
        assertFalse(m.sideBySide)
        assertEquals(1, m.leftColumns)
        assertDp("no preview", 0f, m.preview)
        assertDp("one 50 dp column of buttons", 50f, m.leftColumn)
        assertTrue("the list keeps room for its rows: ${m.list}", m.list >= 190f)
        assertDp("the columns add up to the window", 300f, m.padStart + m.leftColumn + m.gap1 + m.list + m.gap2 + m.strip + m.padEnd)
        assertDp("rows stay 80 dp", 80f, m.row)
        assertDp("a slightly smaller thumbnail keeps the values' room", m.list - LayerWindowMetrics.ROW_ROOM_BESIDE_THUMB, m.thumb)

        // Narrower still: the small thumbnail.
        val narrow = LayerWindowMetrics.of(250f, 436f)
        assertTrue(narrow.compact)
        assertDp(LayerWindowMetrics.SMALL_THUMB.toString(), LayerWindowMetrics.SMALL_THUMB, narrow.thumb)
    }

    @Test
    fun a360DpPhoneShrinksTheThumbnailNotTheValues() {
        // E sizes the window w − 10 = 350 dp wide on a 360 dp phone: the full layout, a 188 dp list.
        val m = LayerWindowMetrics.of(350f, 520f)
        assertFalse(m.compact)
        assertFalse(m.sideBySide)
        assertDp("list", 188f, m.list)
        assertDp("preview kept", 240f, m.preview)
        assertDp("thumbnail", 48f, m.thumb)
        assertTrue("room beside the thumbnail for eye, values and ≡", m.list - m.thumb >= LayerWindowMetrics.ROW_ROOM_BESIDE_THUMB - 0.001f)
        // The thumbnail never grows past ibisPaint's 62 nor shrinks under the small one.
        for (list in listOf(0f, 100f, 180f, 188f, 198f, 220f, 400f)) {
            val t = LayerWindowMetrics.thumbFor(list)
            assertTrue("$list: $t", t in LayerWindowMetrics.SMALL_THUMB..62f)
        }
        assertDp("ibisPaint's list", 62f, LayerWindowMetrics.thumbFor(220f))
    }

    @Test
    fun shortScreensAndShortWideWindowsPutTheListBesideTheControls() {
        // A phone in landscape: the v1.5 side-by-side fallback (design §3.7.7: height < 480 dp).
        val landscape = LayerWindowMetrics.of(520f, 330f, shortScreen = true)
        assertTrue(landscape.sideBySide)
        assertDp("header", 46f, landscape.header)
        assertDp("everything under the header", 284f, landscape.main)
        assertDp("controls column: half the window", 260f, landscape.controls)
        assertDp("the list takes the rest", 520f - 260f - 3 * LayerWindowMetrics.COMPACT_PAD, landscape.list)
        assertDp("a wide list keeps the short rows", LayerWindowMetrics.SIDE_ROW, landscape.row)
        assertDp(LayerWindowMetrics.SMALL_THUMB.toString(), LayerWindowMetrics.SMALL_THUMB, landscape.thumb)
        assertFalse(landscape.stripScrolls)
        assertFalse(landscape.buttonsScroll)

        assertFalse("the dropdown beside the toggles", landscape.blendWraps)
        assertDp("blend row", 56f, landscape.blendRow)
        assertEquals(5, landscape.gridColumns)

        // A window of 424 dp: the controls at their minimum, the list at its own.
        val mid = LayerWindowMetrics.of(424f, 300f, shortScreen = true)
        assertDp("controls minimum", LayerWindowMetrics.CONTROLS_WIDTH, mid.controls)
        assertDp("list minimum", LayerWindowMetrics.MIN_SIDE_LIST, mid.list)
        assertFalse(mid.blendWraps)

        // A smaller landscape phone (640 dp: E gives 416), or the user's phone in split screen (E
        // gives 380): the list keeps its minimum (80 dp rows; a masked row stacks its eye over its
        // mask square, and "100%" / "Normal" keep 44 dp); the controls narrow and the blend
        // dropdown gets a line of its own.
        for (w in listOf(416f, 380f)) {
            val small = LayerWindowMetrics.of(w, 300f, shortScreen = true)
            assertTrue(small.sideBySide)
            assertDp("$w: list minimum", LayerWindowMetrics.MIN_SIDE_LIST, small.list)
            assertDp("$w: controls", w - LayerWindowMetrics.MIN_SIDE_LIST - 3 * LayerWindowMetrics.COMPACT_PAD, small.controls)
            assertDp("$w: 80 dp rows", 80f, small.row)
            assertDp("$w: thumbnail", LayerWindowMetrics.SMALL_THUMB, small.thumb)
            assertTrue("$w: the dropdown wraps", small.blendWraps)
            assertDp("$w: both blend lines", LayerWindowMetrics.BLEND_WRAPPED, small.blendRow)
            assertTrue("$w: grid cells ≥ 40 dp", small.controls / small.gridColumns >= 40f)
        }
        // The narrowest side-by-side window: the controls stop narrowing.
        val narrowest = LayerWindowMetrics.of(LayerWindowMetrics.SIDE_BY_SIDE_MIN_WIDTH, 300f, shortScreen = true)
        assertDp("controls floor", LayerWindowMetrics.CONTROLS_NARROW_WIDTH, narrowest.controls)
        assertEquals(4, narrowest.gridColumns)
        // A very wide one: the controls stop growing.
        assertDp("controls maximum", LayerWindowMetrics.CONTROLS_MAX_WIDTH, LayerWindowMetrics.of(800f, 300f, shortScreen = true).controls)

        // The same window on a tall screen keeps the ibisPaint layout...
        assertFalse(LayerWindowMetrics.of(520f, 330f).sideBySide)
        // ...unless the window itself is very short.
        assertTrue(LayerWindowMetrics.of(520f, 260f).sideBySide)
        // Too narrow for side by side: the stacked layout even on a short screen.
        assertFalse(LayerWindowMetrics.of(340f, 330f, shortScreen = true).sideBySide)
    }

    @Test
    fun degenerateSizesNeverCrash() {
        for ((w, h) in listOf(0f to 0f, -5f to 10f, Float.NaN to 520f, 382f to Float.POSITIVE_INFINITY, 2000f to 2000f)) {
            val m = LayerWindowMetrics.of(w, h)
            assertTrue("$w × $h: finite list", m.list.isFinite() && m.list >= 0f)
            assertTrue("$w × $h: finite main", m.main.isFinite() && m.main >= LayerWindowMetrics.MIN_MAIN || m.sideBySide)
        }
    }

    @Test
    fun everyTouchTargetStaysAtLeast40Dp() {
        for (w in listOf(250f, 300f, 340f, 382f, 500f)) for (h in listOf(300f, 436f, 520f, 700f)) for (short in listOf(false, true)) {
            val m = LayerWindowMetrics.of(w, h, short)
            assertTrue("$w × $h: rows ${m.row}", m.row >= 40f)
            assertTrue("$w × $h: transparency row", m.transparencyRow >= 40f)
            if (!m.sideBySide) {
                assertTrue("$w × $h: strip ${m.strip}", m.strip >= 40f)
                assertTrue("$w × $h: left cells ${m.leftColumn / m.leftColumns}", m.leftColumn / m.leftColumns >= 40f)
            } else {
                // 15 actions, 4 or 5 per row, across the controls column.
                assertTrue("$w × $h: grid cells", m.controls / m.gridColumns >= 40f)
            }
        }
    }
}
