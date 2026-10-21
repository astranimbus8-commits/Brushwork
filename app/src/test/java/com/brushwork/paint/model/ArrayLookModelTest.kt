package com.brushwork.paint.model

import com.brushwork.paint.vector.VStop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 F1 (item 2, I19, D6): the [ArrayLook] model. A look that draws as none is
 * [ArrayLook.isIdentity] and becomes null in a sanitized [ArraySpec], so it is never written;
 * [ArrayLook.sanitized] clamps every value and keeps a usable look as it is; a reflection reverses
 * the turns; [ArrayInstances.ease] gives the four paces. JVM.
 */
class ArrayLookModelTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private val look = ArrayLook(
        opacityStart = 0.9f, opacityEnd = 0.2f, sizeStart = 1.5f, sizeEnd = 0.5f, turnStart = 10f, turnEnd = 90f,
        easing = ArrayEasing.EASE_IN_OUT, tint = listOf(VStop(0f, red), VStop(1f, blue)), tintStrength = 0.6f,
        colorMode = ArrayColorMode.RECOLOR, // v1.10 (F0, R5): every property differs from its default
    )

    @Test
    fun theDefaultDrawsAsNone() {
        assertEquals(ArrayLook(), ArrayLook.IDENTITY)
        assertTrue(ArrayLook.IDENTITY.isIdentity)
        assertEquals(8, ArrayLook.MAX_STOPS)
        // The pace alone changes nothing; a tint without strength or a strength without a tint neither.
        assertTrue(ArrayLook(easing = ArrayEasing.EASE_OUT).isIdentity)
        assertTrue(ArrayLook(tint = listOf(VStop(0f, red), VStop(1f, blue))).isIdentity)
        assertTrue(ArrayLook(tintStrength = 1f).isIdentity)
        // Every other value does.
        for (l in listOf(
            ArrayLook(opacityStart = 0.5f), ArrayLook(opacityEnd = 0.5f), ArrayLook(sizeStart = 2f), ArrayLook(sizeEnd = 2f),
            ArrayLook(turnStart = 5f), ArrayLook(turnEnd = 5f), ArrayLook(tint = listOf(VStop(0f, red), VStop(1f, blue)), tintStrength = 0.1f),
        )) assertFalse("$l", l.isIdentity)
        assertFalse(look.isIdentity)
    }

    @Test
    fun aUsableLookIsKeptAsItIs() {
        assertSame(look, look.sanitized())
        assertSame(ArrayLook.IDENTITY, ArrayLook.IDENTITY.sanitized())
        // Every property of the fixture differs from the default, so each one is checked.
        val json = Json { encodeDefaults = true }
        val d = ArrayLook.serializer().descriptor
        val a = json.encodeToJsonElement(ArrayLook.serializer(), look).jsonObject
        val def = json.encodeToJsonElement(ArrayLook.serializer(), ArrayLook()).jsonObject
        for (i in 0 until d.elementsCount) {
            val name = d.getElementName(i)
            assertNotEquals("$name is set in the fixture", def[name], a[name])
        }
    }

    @Test
    fun sanitizedClampsEveryValue() {
        val s = ArrayLook(
            opacityStart = -1f, opacityEnd = 2f, sizeStart = 0f, sizeEnd = 100f, turnStart = -5000f, turnEnd = 5000f,
            tint = listOf(VStop(0f, red), VStop(1f, blue)), tintStrength = 3f,
        ).sanitized()
        assertEquals(0f, s.opacityStart, 0f)
        assertEquals(1f, s.opacityEnd, 0f)
        assertEquals(ArraySpec.MIN_SCALE, s.sizeStart, 0f)
        assertEquals(ArraySpec.MAX_SCALE, s.sizeEnd, 0f)
        assertEquals(-3600f, s.turnStart, 0f)
        assertEquals(3600f, s.turnEnd, 0f)
        assertEquals(1f, s.tintStrength, 0f)
        assertSame("idempotent", s, s.sanitized())
        val n = ArrayLook(
            opacityStart = Float.NaN, opacityEnd = Float.POSITIVE_INFINITY, sizeStart = Float.NaN, sizeEnd = Float.NEGATIVE_INFINITY,
            turnStart = Float.NaN, turnEnd = Float.POSITIVE_INFINITY, tintStrength = Float.NaN,
        ).sanitized()
        assertEquals("non-finite values take their defaults", ArrayLook.IDENTITY, n)
    }

    @Test
    fun theTintIsSortedClampedAndAtMostEightStops() {
        assertEquals("one stop is no tint", emptyList<VStop>(), ArrayLook(tint = listOf(VStop(0.5f, red)), tintStrength = 1f).sanitized().tint)
        val sorted = ArrayLook(tint = listOf(VStop(0.9f, blue), VStop(0.1f, red)), tintStrength = 1f).sanitized().tint
        assertEquals(listOf(VStop(0.1f, red), VStop(0.9f, blue)), sorted)
        val clamped = ArrayLook(tint = listOf(VStop(-0.5f, red), VStop(Float.NaN, 0), VStop(1.5f, blue)), tintStrength = 1f).sanitized().tint
        assertEquals("a NaN offset is dropped, the others clamped", listOf(VStop(0f, red), VStop(1f, blue)), clamped)
        val many = ArrayLook(tint = List(12) { VStop((11 - it) / 11f, it) }, tintStrength = 1f).sanitized().tint
        assertEquals(ArrayLook.MAX_STOPS, many.size)
        assertEquals("the first ones by offset are kept", List(8) { VStop(it / 11f, 11 - it) }.map { it.color }, many.map { it.color })
        assertTrue(many.zipWithNext().all { (a, b) -> a.offset <= b.offset })
    }

    @Test
    fun aSanitizedSpecDropsALookThatDrawsAsNone() {
        val spec = ArraySpec(count = 5)
        assertNull(spec.copy(look = ArrayLook.IDENTITY).sanitized().look)
        assertNull(spec.copy(look = ArrayLook(easing = ArrayEasing.EASE_IN)).sanitized().look)
        // A one-stop tint is no tint, so this look draws as none once sanitized.
        assertNull(spec.copy(look = ArrayLook(tint = listOf(VStop(0.5f, red)), tintStrength = 1f)).sanitized().look)
        val kept = spec.copy(look = look)
        assertSame(kept, kept.sanitized())
        // A damaged look is sanitized with the spec.
        val damaged = spec.copy(look = look.copy(opacityEnd = 7f)).sanitized()
        assertEquals(1f, damaged.look!!.opacityEnd, 0f)
    }

    @Test
    fun theLookIsWrittenOnlyWhenItDrawsSomething() {
        val spec = ArraySpec(mode = ArrayMode.CIRCLE, count = 6)
        val plain = ArrayCodec.encodeSpec(spec)
        assertFalse(plain, plain.contains("look"))
        assertEquals("an identity look is never written", plain, ArrayCodec.encodeSpec(spec.copy(look = ArrayLook.IDENTITY)))
        assertEquals(plain, ArrayCodec.encodeSpec(spec.copy(look = ArrayLook(easing = ArrayEasing.EASE_IN))))
        val withLook = spec.copy(look = look)
        val text = ArrayCodec.encodeSpec(withLook)
        assertTrue(text, text.contains("\"look\""))
        assertEquals(withLook, ArrayCodec.decodeSpec(text))
        // A damaged look is read sanitized.
        val damaged = text.replace("\"tintStrength\":0.6", "\"tintStrength\":9.0")
        assertTrue(damaged != text)
        assertEquals(1f, ArrayCodec.decodeSpec(damaged)!!.look!!.tintStrength, 0f)
    }

    @Test
    fun aReflectionReversesTheTurns() {
        val spec = ArraySpec(mode = ArrayMode.TRANSFORM, count = 5, look = look)
        val flipped = spec.mapped(floatArrayOf(-1f, 0f, 500f, 0f, 1f, 0f, 0f, 0f, 1f))
        assertEquals(look.copy(turnStart = -10f, turnEnd = -90f), flipped.look)
        val moved = spec.mapped(floatArrayOf(1f, 0f, 30f, 0f, 1f, -20f, 0f, 0f, 1f))
        assertEquals(look, moved.look)
        val turned = spec.mapped(floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        assertEquals("a turn keeps the look", look, turned.look)
        assertNull(ArraySpec().mapped(floatArrayOf(-1f, 0f, 500f, 0f, 1f, 0f, 0f, 0f, 1f)).look)
    }

    @Test
    fun theFourPaces() {
        for (e in ArrayEasing.entries) {
            assertEquals("$e at 0", 0f, ArrayInstances.ease(e, 0f), 0f)
            assertEquals("$e at 1", 1f, ArrayInstances.ease(e, 1f), 0f)
            assertEquals("$e clamps", 0f, ArrayInstances.ease(e, -1f), 0f)
            assertEquals("$e clamps", 1f, ArrayInstances.ease(e, 2f), 0f)
            assertEquals("$e: NaN is 0", 0f, ArrayInstances.ease(e, Float.NaN), 0f)
            val samples = (0..20).map { ArrayInstances.ease(e, it / 20f) }
            assertTrue("$e rises", samples.zipWithNext().all { (a, b) -> a <= b })
        }
        assertEquals(0.5f, ArrayInstances.ease(ArrayEasing.LINEAR, 0.5f), 1e-6f)
        assertEquals(0.25f, ArrayInstances.ease(ArrayEasing.EASE_IN, 0.5f), 1e-6f)
        assertEquals(0.75f, ArrayInstances.ease(ArrayEasing.EASE_OUT, 0.5f), 1e-6f)
        assertEquals(0.5f, ArrayInstances.ease(ArrayEasing.EASE_IN_OUT, 0.5f), 1e-6f)
        assertEquals(0.104f, ArrayInstances.ease(ArrayEasing.EASE_IN_OUT, 0.2f), 1e-5f)
    }
}
