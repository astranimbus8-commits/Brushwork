package com.brushwork.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7 F1 (item 18): [SymmetrySettings.sanitized] keeps usable values and repairs damaged ones. */
class SymmetrySettingsTest {
    private val square = listOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)

    @Test
    fun usableSettingsAreKept() {
        val s = SymmetrySettings(SymmetryType.PERSPECTIVE_ARRAY, 10f, 20f, 45f, 8, 50f, 60f, square)
        assertSame(s, s.sanitized())
        val d = SymmetrySettings()
        assertSame(d, d.sanitized())
        assertEquals(listOf("Off", "Mirror ruler", "Kaleidoscope ruler", "Rotation ruler", "Array ruler", "Perspective array ruler"), SymmetryType.entries.map { it.label })
    }

    @Test
    fun damagedSettingsAreRepaired() {
        val s = SymmetrySettings(
            SymmetryType.KALEIDOSCOPE, Float.NaN, 5e9f, Float.POSITIVE_INFINITY, 99, Float.NaN, 0f,
            listOf(0f, 0f, 100f, 100f, 100f, 0f, 0f, 100f),
        ).sanitized()
        assertEquals(-1f, s.centerX, 0f)
        assertEquals(-1f, s.centerY, 0f)
        assertEquals(90f, s.angleDeg, 0f)
        assertEquals(SymmetrySettings.MAX_DIVISIONS, s.divisions)
        assertEquals(300f, s.spacingX, 0f)
        assertEquals(SymmetrySettings.MIN_SPACING, s.spacingY, 0f)
        assertTrue("a self-crossing quad goes", s.quad.isEmpty())
        assertEquals(SymmetrySettings.MIN_DIVISIONS, SymmetrySettings(divisions = 0).sanitized().divisions)
    }

    @Test
    fun convexQuads() {
        assertTrue(SymmetrySettings.isConvexQuad(square))
        assertTrue("either winding", SymmetrySettings.isConvexQuad(listOf(0f, 0f, 0f, 100f, 100f, 100f, 100f, 0f)))
        assertTrue("a trapezoid", SymmetrySettings.isConvexQuad(listOf(30f, 0f, 70f, 0f, 100f, 100f, 0f, 100f)))
        assertFalse("seven numbers", SymmetrySettings.isConvexQuad(square.dropLast(1)))
        assertFalse("a dart", SymmetrySettings.isConvexQuad(listOf(0f, 0f, 100f, 0f, 30f, 30f, 0f, 100f)))
        assertFalse("three in a line", SymmetrySettings.isConvexQuad(listOf(0f, 0f, 50f, 0f, 100f, 0f, 0f, 100f)))
        assertFalse("NaN", SymmetrySettings.isConvexQuad(listOf(Float.NaN) + square.drop(1)))
    }
}
