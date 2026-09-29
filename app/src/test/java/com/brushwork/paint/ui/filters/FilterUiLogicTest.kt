package com.brushwork.paint.ui.filters

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterUiLogicTest {

    private val identity = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

    // ------------------------------------------------------------------ curve

    @Test
    fun curveAddInsertsInOrderAndRejectsCrowdedSpots() {
        val (list, i) = CurveEditing.add(identity, 0.5f, 0.7f)!!
        assertEquals(1, i)
        assertEquals(CurvePoint(0.5f, 0.7f), list[1])
        val (list2, j) = CurveEditing.add(list, 0.25f, 0.1f)!!
        assertEquals(1, j)
        assertEquals(listOf(0f, 0.25f, 0.5f, 1f), list2.map { it.x })
        assertNull(CurveEditing.add(list2, 0.51f, 0.3f)) // too close to 0.5
        assertNull(CurveEditing.add(list2, 0.005f, 0.3f)) // on top of the endpoint
        assertEquals(0f, CurveEditing.add(identity, 0.5f, -3f)!!.first[1].y, 0f)
    }

    @Test
    fun curveMoveKeepsOrderAndLocksEndpointX() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.3f), CurvePoint(0.6f, 0.6f), CurvePoint(1f, 1f))
        val moved = CurveEditing.move(pts, 1, 0.9f, 0.8f)
        assertEquals(0.6f - CurveEditing.MIN_GAP, moved[1].x, 1e-6f)
        assertEquals(0.8f, moved[1].y, 0f)
        val end = CurveEditing.move(pts, 3, 0.2f, 0.4f)
        assertEquals(1f, end[3].x, 0f)
        assertEquals(0.4f, end[3].y, 0f)
        val start = CurveEditing.move(pts, 0, 0.5f, 2f)
        assertEquals(CurvePoint(0f, 1f), start[0])
        assertSame(pts, CurveEditing.move(pts, 9, 0f, 0f))
    }

    @Test
    fun curveRemoveOnlyInteriorPoints() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.2f), CurvePoint(1f, 1f))
        assertFalse(CurveEditing.canRemove(pts, 0))
        assertFalse(CurveEditing.canRemove(pts, 2))
        assertTrue(CurveEditing.canRemove(pts, 1))
        assertEquals(identity, CurveEditing.remove(pts, 1))
        assertSame(pts, CurveEditing.remove(pts, 0))
    }

    @Test
    fun curveHitTestFindsTheNearestPointInRadius() {
        val pts = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(0.55f, 0.52f), CurvePoint(1f, 1f))
        assertEquals(2, CurveEditing.hitTest(pts, 0.56f, 0.52f, 0.05f, 0.05f))
        assertEquals(1, CurveEditing.hitTest(pts, 0.49f, 0.5f, 0.05f, 0.05f))
        assertEquals(-1, CurveEditing.hitTest(pts, 0.3f, 0.8f, 0.05f, 0.05f))
        assertEquals(listOf(CurvePoint(0f, 0f), CurvePoint(0.4f, 1f)), CurveEditing.normalized(listOf(CurvePoint(0.4f, 1.5f), CurvePoint(-1f, 0f))))
    }

    @Test
    fun histogramHeightsAreNormalized() {
        val h = CurveEditing.histogramHeights(intArrayOf(0, 25, 100))!!
        assertEquals(0f, h[0], 0f)
        assertEquals(0.5f, h[1], 1e-6f)
        assertEquals(1f, h[2], 0f)
        assertNull(CurveEditing.histogramHeights(IntArray(256)))
    }

    // ------------------------------------------------------------------ gradient

    private val bw = listOf(GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, 0xFFFFFFFF.toInt()))

    @Test
    fun gradientSampleInterpolatesAndClampsAtEnds() {
        assertEquals(0xFF000000.toInt(), GradientEditing.sample(bw, -1f))
        assertEquals(0xFFFFFFFF.toInt(), GradientEditing.sample(bw, 2f))
        assertEquals(0xFF808080.toInt(), GradientEditing.sample(bw, 0.5f))
        val unsorted = listOf(GradientStop(1f, 0xFFFF0000.toInt()), GradientStop(0f, 0xFF0000FF.toInt()))
        assertEquals(0xFF0000FF.toInt(), GradientEditing.sample(unsorted, 0f))
        val inner = listOf(GradientStop(0.2f, 0xFF112233.toInt()), GradientStop(0.8f, 0xFF445566.toInt()))
        assertEquals(0xFF112233.toInt(), GradientEditing.sample(inner, 0.1f))
    }

    @Test
    fun gradientAddUsesTheColorAtThatPosition() {
        val (list, i) = GradientEditing.add(bw, 0.25f)
        assertEquals(1, i)
        assertEquals(3, list.size)
        assertEquals(0.25f, list[1].position, 0f)
        assertEquals(GradientEditing.sample(bw, 0.25f), list[1].color)
    }

    @Test
    fun gradientMoveResortsAndTracksTheStop() {
        val stops = listOf(GradientStop(0f, 1), GradientStop(0.5f, 2), GradientStop(1f, 3))
        val (list, i) = GradientEditing.move(stops, 0, 0.75f)
        assertEquals(listOf(2, 1, 3), list.map { it.color })
        assertEquals(1, i)
        val (list2, j) = GradientEditing.move(list, i, 5f)
        assertEquals(2, j)
        assertEquals(1f, list2[j].position, 0f)
        assertEquals(1, list2[j].color)
    }

    @Test
    fun gradientRemoveRecolorReverse() {
        val stops = listOf(GradientStop(0f, 1), GradientStop(0.3f, 2), GradientStop(1f, 3))
        assertEquals(listOf(1, 3), GradientEditing.remove(stops, 1).map { it.color })
        assertSame(bw, GradientEditing.remove(bw, 0)) // at least two stops remain
        assertEquals(9, GradientEditing.recolor(stops, 2, 9)[2].color)
        val rev = GradientEditing.reverse(stops)
        assertEquals(listOf(3, 2, 1), rev.map { it.color })
        assertEquals(0.7f, rev[1].position, 1e-6f)
        assertEquals(1, GradientEditing.hitTest(stops, 0.32f, 0.05f))
        assertEquals(-1, GradientEditing.hitTest(stops, 0.6f, 0.05f))
        assertTrue(GradientEditing.presets.all { (_, p) -> p.size >= 2 && p == GradientEditing.sorted(p) })
    }

    // ------------------------------------------------------------------ slider text

    @Test
    fun sliderSnapAndFormat() {
        val px = FilterParam.Slider("r", "Radius", 0f, 200f, 10f, step = 1f, pixels = true)
        assertEquals(13f, SliderFormat.snap(px, 12.6f), 0f)
        assertEquals(200f, SliderFormat.snap(px, 900f), 0f)
        assertEquals("13 px", SliderFormat.format(px, 12.6f))
        assertEquals(14f, SliderFormat.nudge(px, 13f, 1), 0f)

        val pct = FilterParam.Slider("a", "Amount", -100f, 100f, 0f, step = 1f, suffix = "%")
        assertEquals("-40%", SliderFormat.format(pct, -40f))

        val fine = FilterParam.Slider("g", "Gamma", 0.1f, 3f, 1f, step = 0.05f)
        assertEquals(2, SliderFormat.decimals(fine))
        assertEquals(1.15f, SliderFormat.snap(fine, 1.137f), 1e-5f)
        assertEquals("1.15", SliderFormat.format(fine, 1.15f))
        assertEquals("1", SliderFormat.format(fine, 1f))

        val cont = FilterParam.Slider("s", "Strength", 0f, 1f, 0.5f)
        assertEquals(0.01f, SliderFormat.increment(cont), 1e-7f)
        assertEquals("0.33", SliderFormat.format(cont, 0.3333f))
        assertEquals(1f, SliderFormat.nudge(cont, 0.999f, 1), 0f)

        val angle = FilterParam.Slider("t", "Angle", 0f, 360f, 0f, suffix = "°")
        assertEquals("45°", SliderFormat.format(angle, 45.2f))
        assertEquals(5f, SliderFormat.increment(angle), 0f)
        val size = FilterParam.Slider("z", "Size", 0f, 50f, 1f, suffix = "mm")
        assertEquals("12.5 mm", SliderFormat.format(size, 12.5f))
    }

    // ------------------------------------------------------------------ search

    private class F(id: String, name: String, cat: FilterCategory) : Filter(id, name, cat) {
        override val params = emptyList<FilterParam>()
        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext) = src.copy()
    }

    @Test
    fun searchMatchesWordsInNameOrCategoryAndRanksPrefixFirst() {
        val filters = listOf(
            F("gb", "Gaussian Blur", FilterCategory.BLUR),
            F("mb", "Motion Blur", FilterCategory.BLUR),
            F("bl", "Bloom", FilterCategory.STYLE),
            F("ts", "Tilt Shift", FilterCategory.BLUR),
            F("hs", "Hue / Saturation", FilterCategory.ADJUST),
        )
        assertEquals(listOf("bl", "gb", "mb", "ts"), FilterSearch.search(filters, "bl").map { it.id })
        assertEquals(listOf("mb"), FilterSearch.search(filters, "  motion  BLUR ").map { it.id })
        assertEquals(listOf("hs"), FilterSearch.search(filters, "color sat").map { it.id })
        assertEquals(filters, FilterSearch.search(filters, "   "))
        assertTrue(FilterSearch.search(filters, "zzz").isEmpty())
    }
}
