package com.brushwork.paint.core

import com.brushwork.paint.filters.FilterMath
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreTest {
    @Test
    fun unitConversions() {
        assertEquals(350.0, LengthUnit.IN.toPx(1.0, 350.0), 1e-9)
        assertEquals(2.54, LengthUnit.CM.fromPx(350.0, 350.0), 1e-9)
        assertEquals(2480.0, LengthUnit.MM.toPx(210.0, 300.0), 0.5) // A4 width @300dpi
        assertEquals(3.0, Units.convert(3.0, LengthUnit.PX, LengthUnit.PX, 72.0), 0.0)
        assertEquals("12.5", Units.formatNumber(12.5, 2))
        assertEquals("3", Units.formatNumber(3.0, 2))
        assertEquals(12.5, Units.parse("12,5")!!, 1e-9)
    }

    @Test
    fun hsvRoundTrip() {
        val hsv = FloatArray(3)
        for (c in listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF123456.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())) {
            ColorUtils.colorToHsv(c, hsv)
            assertEquals(c, ColorUtils.hsvToColor(hsv[0], hsv[1], hsv[2]))
        }
    }

    @Test
    fun hexParsing() {
        assertEquals(0xFFFF8800.toInt(), ColorUtils.parseHex("#ff8800"))
        assertEquals(0xFFFF8800.toInt(), ColorUtils.parseHex("F80"))
        assertEquals(0x80FF8800.toInt(), ColorUtils.parseHex("80FF8800"))
        assertEquals("#FF8800", ColorUtils.toHex(0xFFFF8800.toInt()))
    }

    @Test
    fun distanceTransform() {
        val w = 9; val h = 1
        val plane = FloatArray(w).also { it[4] = 1f }
        val d = FilterMath.distanceToCoverage(plane, w, h)
        assertEquals(0f, d[4], 1e-4f)
        assertEquals(3f, d[1], 1e-4f)
        assertEquals(4f, d[8], 1e-4f)
    }

    @Test
    fun ellipseProjectionLandsOnEllipse() {
        val c = Vec2(100f, 50f)
        val p = Geometry.projectOnEllipse(Vec2(400f, 300f), c, 80f, 40f, 0.3f)
        val local = (p - c).rotated(-0.3f)
        val v = (local.x / 80f) * (local.x / 80f) + (local.y / 40f) * (local.y / 40f)
        assertEquals(1f, v, 1e-3f)
    }

    @Test
    fun parallelCoversRange() {
        val hits = IntArray(1000)
        Parallel.forRange(1000) { s, e -> for (i in s until e) hits[i]++ }
        assert(hits.all { it == 1 })
    }
}
