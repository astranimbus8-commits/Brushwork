package com.brushwork.paint.ui.common

import com.brushwork.paint.model.IncrementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6 §3.4 (c) (G, JVM): the shared number controls' increment rules. A control's kind is
 * inferred from its unit (`%` → Percent, `°` → Angle, `px` → Size; anything else has no kind and
 * a custom step under "$label|$suffix"); -/+ go to the next multiple of the step; sliders land on
 * the nearest multiple with the range ends reachable and no binary noise; popup titles.
 */
class IncrementSteppingTest {

    @Test
    fun kindsAreInferredFromTheUnit() {
        assertEquals(IncrementKind.PERCENT, IncrementStepping.kindForSuffix("%"))
        assertEquals(IncrementKind.PERCENT, IncrementStepping.kindForSuffix("% of the path"))
        assertEquals(IncrementKind.ANGLE, IncrementStepping.kindForSuffix("°"))
        assertEquals(IncrementKind.SIZE, IncrementStepping.kindForSuffix(" px "))
        assertNull(IncrementStepping.kindForSuffix("EV"))
        assertNull(IncrementStepping.kindForSuffix("mm"))
        assertNull(IncrementStepping.kindForSuffix(""))
        assertEquals("%", IncrementStepping.suffixOf("45 %"))
        assertEquals("EV", IncrementStepping.suffixOf("+0.50 EV"))
        assertEquals("°", IncrementStepping.suffixOf("15.0°"))
        assertEquals("× width", IncrementStepping.suffixOf("3.0 × width"))
        assertEquals("", IncrementStepping.suffixOf("Off"))
        assertEquals("Exposure|EV", IncrementStepping.customKey("Exposure", "EV"))
        // A 0..1 percentage slider shows its value × 100.
        assertEquals(100f, IncrementStepping.impliedScale(IncrementKind.PERCENT, 1f), 0f)
        assertEquals(1f, IncrementStepping.impliedScale(IncrementKind.PERCENT, 100f), 0f)
        assertEquals(1f, IncrementStepping.impliedScale(IncrementKind.ANGLE, 1f), 0f)
    }

    @Test
    fun anExplicitKindOrKeyOverridesTheInference() {
        // An explicit kind wins over the unit (and a key given with it is not used).
        assertEquals(IncrementKind.SCALE to null, IncrementStepping.resolve(IncrementKind.SCALE, "whatever", "Scale", "%"))
        // A key alone is a custom step under it, whatever the unit implies (A's feather shown in %).
        assertEquals(null to "mask.feather", IncrementStepping.resolve(null, "mask.feather", "Feather", "%"))
        assertEquals(null to "path.weight", IncrementStepping.resolve(null, "path.weight", "Weight", ""))
        // Neither: the unit's kind, else "$label|$suffix".
        assertEquals(IncrementKind.PERCENT to null, IncrementStepping.resolve(null, null, "Feather", "%"))
        assertEquals(IncrementKind.ANGLE to null, IncrementStepping.resolve(null, null, "Rotation", "°"))
        assertEquals(null to "Exposure|EV", IncrementStepping.resolve(null, null, "Exposure", "EV"))
    }

    @Test
    fun plusAndMinusGoToTheNextMultiple() {
        assertEquals(40.0, IncrementStepping.stepBy(37.0, 1, 10.0), 0.0)
        assertEquals(30.0, IncrementStepping.stepBy(37.0, -1, 10.0), 0.0)
        assertEquals(50.0, IncrementStepping.stepBy(40.0, 1, 10.0), 0.0)
        assertEquals(30.0, IncrementStepping.stepBy(40.0, -1, 10.0), 0.0)
        assertEquals(60.0, IncrementStepping.stepBy(37.0, 3, 10.0), 0.0)
        // Binary noise: 0.30000000000000004 counts as on 0.3.
        assertEquals(0.4, IncrementStepping.stepBy(0.1 + 0.2, 1, 0.1), 0.0)
        assertEquals(-10.0, IncrementStepping.stepBy(-3.0, -1, 10.0), 0.0)
        // No step: unchanged.
        assertEquals(37.0, IncrementStepping.stepBy(37.0, 1, 0.0), 0.0)
        assertEquals(37.0, IncrementStepping.stepBy(37.0, 1, Double.NaN), 0.0)
        assertFalse(IncrementStepping.valid(null))
        assertFalse(IncrementStepping.valid(-1.0))
        assertTrue(IncrementStepping.valid(0.25))
    }

    @Test
    fun slidersLandOnMultiplesAndKeepTheirEnds() {
        assertEquals(0.5, IncrementStepping.snapSlider(0.463, 0.1, 0.0, 1.0), 0.0)
        assertEquals(1.25, IncrementStepping.snapSlider(1.13, 0.25, -5.0, 5.0), 0.0)
        // The ends stay reachable when no multiple is nearer.
        assertEquals(1000.0, IncrementStepping.snapSlider(999.0, 30.0, 0.5, 1000.0), 0.0)
        assertEquals(0.5, IncrementStepping.snapSlider(2.0, 30.0, 0.5, 1000.0), 0.0)
        // Clamped, and without a step just clamped.
        assertEquals(1.0, IncrementStepping.snapSlider(7.0, 0.1, 0.0, 1.0), 0.0)
        assertEquals(0.437, IncrementStepping.snapSlider(0.437, null, 0.0, 1.0), 0.0)
        // No binary noise: 0.7, not 0.7000000000000001.
        assertEquals("0.7", IncrementStepping.snapSlider(0.69, 0.1, 0.0, 1.0).toString())
    }

    @Test
    fun popupTitlesAndKeyNames() {
        assertEquals("Step for angles", IncrementStepping.popupTitle(IncrementKind.ANGLE, null))
        assertEquals("Step for lengths", IncrementStepping.popupTitle(IncrementKind.LENGTH, null))
        assertEquals("Step for percentages", IncrementStepping.popupTitle(IncrementKind.PERCENT, null))
        assertEquals("Step for Exposure", IncrementStepping.popupTitle(null, "Exposure|EV"))
        assertEquals("Step for Feather", IncrementStepping.popupTitle(null, "mask.feather"))
        assertEquals("Exposure", IncrementStepping.nameOfKey("Exposure|EV"))
        assertEquals("EV", IncrementStepping.suffixOfKey("Exposure|EV"))
        assertEquals("", IncrementStepping.suffixOfKey("mask.feather"))
        assertEquals("0.25", IncrementStepping.format(0.25f))
        assertEquals("10", IncrementStepping.format(10f))
    }
}
