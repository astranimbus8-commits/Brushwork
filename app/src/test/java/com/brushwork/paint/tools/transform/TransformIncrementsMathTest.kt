package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.6 §3.4 (G, JVM): the Transform increments' math. Scales land on multiples of the Scale step
 * in percent of the ORIGINAL size (100 → 110, 120; 90, 80), whatever the size at the gesture
 * start; mirrored axes keep their sign; a bad step changes nothing. The rotation handle lands on
 * absolute multiples of the Angle step; a pinch steps its scale and angle but keeps small turns.
 * And the readout's wording.
 */
class TransformIncrementsMathTest {

    @Test
    fun anAxisLandsOnMultiplesOfTheStepInPercentOfTheOriginal() {
        // At 100 %: a raw factor of 1.07 gives 110 %, 1.16 gives 120 %, 0.93 gives 90 %.
        assertEquals(1.1f, TransformIncrements.axisFactor(1f, 1.07f, 10f), 1e-5f)
        assertEquals(1.2f, TransformIncrements.axisFactor(1f, 1.16f, 10f), 1e-5f)
        assertEquals(0.9f, TransformIncrements.axisFactor(1f, 0.93f, 10f), 1e-5f)
        // At 37 % (an imported picture shrunk to fit): 37 × 1.05 = 38.85 → 40 % of the original.
        assertEquals(40f / 37f, TransformIncrements.axisFactor(0.37f, 1.05f, 10f), 1e-5f)
        // A mirrored axis (scale −1) is stepped by its size and stays mirrored.
        assertEquals(1.1f, TransformIncrements.axisFactor(-1f, 1.08f, 10f), 1e-5f)
        // Dragged past the fixed side: −1.25 → −130 % (halves go up), the sign kept.
        assertEquals(-1.3f, TransformIncrements.axisFactor(1f, -1.25f, 10f), 1e-5f)
        // Never below one step of the original.
        assertEquals(0.1f, TransformIncrements.axisFactor(1f, 0.01f, 10f), 1e-5f)
        // No usable step: the factor as it is.
        for (bad in listOf(0f, -5f, Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(1.07f, TransformIncrements.axisFactor(1f, 1.07f, bad), 0f)
        assertEquals(Float.NaN, TransformIncrements.axisFactor(1f, Float.NaN, 10f), 0f)
    }

    @Test
    fun theOverallScaleIsSteppedFromItsGeometricMean() {
        // 120 % × 50 %: overall 77.46 %; × 1.1 = 85.2 → 90 %.
        val st = TransformState(100, 100, 50f, 50f, sx = 1.2f, sy = 0.5f)
        val k = TransformIncrements.uniformFactor(st.scalePercent, 1.1f, 10f)
        assertEquals(90f, st.scalePercent * k, 1e-3f)
        assertEquals(0.97f, TransformIncrements.uniformFactor(100f, 0.97f, 0f), 0f)
    }

    @Test
    fun relativeFactorsSnapFromTheGestureStart() {
        assertEquals(1.1f, TransformIncrements.relativeFactor(1.07f, 10f), 1e-5f)
        assertEquals(0.8f, TransformIncrements.relativeFactor(0.83f, 10f), 1e-5f)
        assertEquals(-1.2f, TransformIncrements.relativeFactor(-1.17f, 10f), 1e-5f)
        assertEquals(1.07f, TransformIncrements.relativeFactor(1.07f, Float.NaN), 0f)
    }

    @Test
    fun theRotationHandleLandsOnAbsoluteMultiplesOfTheAngleStep() {
        val st = TransformState(40, 20, 40f, 30f)
        val pivot = Vec2(40f, 30f)
        val from = Vec2(40f, -16f)
        fun handleAt(deg: Float): Vec2 {
            val r = Math.toRadians(deg.toDouble())
            return Vec2(pivot.x + (46.0 * sin(r)).toFloat(), pivot.y - (46.0 * cos(r)).toFloat())
        }
        // 20° swept: 15° with a 15° step (v1.5: 20°, the 45° detents are only 2° wide).
        assertEquals(15f, TransformHandles.rotateStepped(st, pivot, from, handleAt(20f), 15f).rotationDeg, 1e-3f)
        assertEquals(20f, TransformHandles.rotate(st, pivot, from, handleAt(20f)).rotationDeg, 1e-2f)
        assertEquals(30f, TransformHandles.rotateStepped(st, pivot, from, handleAt(23f), 15f).rotationDeg, 1e-3f)
        assertEquals(-45f, TransformHandles.rotateStepped(st, pivot, from, handleAt(-41f), 15f).rotationDeg, 1e-3f)
        // Absolute: a box already at 7° turned by 20° goes to 30°, not 7 + 15.
        val turned = st.rotatedAbout(pivot, 7f)
        assertEquals(30f, TransformHandles.rotateStepped(turned, pivot, from, handleAt(20f), 15f).rotationDeg, 1e-3f)
        // Steps of 10°: 180 stays reachable, angles stay in (−180, 180].
        assertEquals(180f, TransformHandles.rotateStepped(st, pivot, from, handleAt(178f), 10f).rotationDeg, 1e-3f)
        assertEquals(-170f, TransformHandles.rotateStepped(st, pivot, from, handleAt(-172f), 10f).rotationDeg, 1e-3f)
    }

    @Test
    fun aPinchStepsItsScaleAndItsAngleButSmallTurnsKeepTheAngle() {
        val st = TransformState(100, 50, 200f, 100f)
        val focus = Vec2(200f, 100f)
        val none = Vec2.ZERO
        // Scale 1.07 → 110 %, a 3° turn kept at 0°.
        val a = TransformHandles.pinchStepped(st, focus, none, 1.07f, 3f, 10f, 15f)
        assertEquals(110f, a.scalePercent, 1e-3f)
        assertEquals(0f, a.rotationDeg, 0f)
        // A 20° turn → 15°; the scale free without a Scale step.
        val b = TransformHandles.pinchStepped(st, focus, none, 1.07f, 20f, null, 15f)
        assertEquals(15f, b.rotationDeg, 1e-3f)
        assertEquals(107f, b.scalePercent, 1e-3f)
        // Only a Scale step: the v1.5 45° detents for the angle (44° → 45°, 30° stays).
        assertEquals(45f, TransformHandles.pinchStepped(st, focus, none, 1f, 44f, 10f, null).rotationDeg, 1e-3f)
        assertEquals(30f, TransformHandles.pinchStepped(st, focus, none, 1f, 30f, 10f, null).rotationDeg, 1e-3f)
        // The translation follows the fingers, unstepped.
        assertEquals(213.3f, TransformHandles.pinchStepped(st, focus, Vec2(13.3f, 0f), 1f, 0f, 10f, 15f).cx, 1e-3f)
        // Neither step: exactly the v1.5 pinch.
        assertEquals(TransformHandles.pinch(st, focus, Vec2(3f, 4f), 1.33f, 17f), TransformHandles.pinchStepped(st, focus, Vec2(3f, 4f), 1.33f, 17f, null, null))
    }

    @Test
    fun readouts() {
        assertEquals("+30 px, −10 px", IncrementReadout.move(30f, -10f))
        assertEquals("0 px, +2.5 px", IncrementReadout.move(0f, 2.5f))
        assertEquals("120 %", IncrementReadout.percent(120f))
        assertEquals("110 %", IncrementReadout.scale(110f, 110.01f))
        assertEquals("110 × 90 %", IncrementReadout.scale(110f, 90f))
        assertEquals("45°", IncrementReadout.angle(45f))
        assertEquals("−15°", IncrementReadout.angle(-15f))
        assertEquals("40 px", IncrementReadout.length(40f))
        assertEquals("120 × 80 px", IncrementReadout.size(120f, 80f))
    }
}
