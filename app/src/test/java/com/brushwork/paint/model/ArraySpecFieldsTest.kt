package com.brushwork.paint.model

import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VSubpath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * v1.8 F1 (V6, R8): [ArraySpec.sanitized] and [ArraySpec.mapped] rebuild the spec property by
 * property, so a property they forget is silently reset to its default. Every constructor property
 * of the fixture differs from the default (checked per serial element, so a new property fails
 * here until the fixture sets it), and each one survives sanitized() and mapped(). JVM.
 *
 * v1.10 (F0, R4, R5): the fixture sets [ArraySpec.hideSource] and the look's [ArrayLook.colorMode].
 */
class ArraySpecFieldsTest {
    private val json = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }

    private fun obj(s: ArraySpec): JsonObject = json.encodeToJsonElement(ArraySpec.serializer(), s).jsonObject

    private val names: List<String> = ArraySpec.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }

    private val look = ArrayLook(
        opacityStart = 0.9f, opacityEnd = 0.2f, sizeStart = 1.5f, sizeEnd = 0.5f, turnStart = 10f, turnEnd = 90f,
        easing = ArrayEasing.EASE_IN_OUT, tint = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0xFF0000FF.toInt())), tintStrength = 0.6f,
        colorMode = ArrayColorMode.RECOLOR,
    )

    /** Every property set to a usable value other than its default. */
    private val full = ArraySpec(
        mode = ArrayMode.CURVE, count = 7, relativeX = 0.5f, relativeY = 1.25f, constantX = 3f, constantY = -4f,
        centerX = 300f, centerY = 200f, sweepDeg = 270f, rotateCopies = false,
        guide = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(100f, 50f, inX = -20f, inY = 0f, outX = 20f, outY = 0f), VAnchor(200f, 0f))),
        spacing = 40f, alignToCurve = false, moveX = 12f, moveY = -3f, turnDeg = 15f, scale = 0.9f, pivotX = 10f, pivotY = 20f,
        editingSource = true, look = look, hideSource = true,
    )

    @Test
    fun theFixtureSetsEveryProperty() {
        // The serial elements are the constructor properties (no kotlin-reflect: the backing fields).
        val fields = ArraySpec::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
        assertEquals(fields.toSet(), names.toSet())
        assertTrue("look is one of them", "look" in names)
        val def = obj(ArraySpec())
        val f = obj(full)
        for (n in names) assertNotEquals("$n is set in the fixture", def[n], f[n])
    }

    @Test
    fun sanitizedKeepsEveryProperty() {
        assertSame(full, full.sanitized())
        val f = obj(full)
        val s = obj(full.sanitized())
        for (n in names) assertEquals("$n survives sanitized()", f[n], s[n])
        // Also when sanitized() has to rebuild the spec (another value out of range).
        val damaged = obj(full.copy(count = 9999).sanitized())
        for (n in names) if (n != "count") assertEquals("$n survives a rebuild", f[n], damaged[n])
    }

    @Test
    fun mappedKeepsEveryProperty() {
        val f = obj(full)
        val def = obj(ArraySpec())
        assertEquals("the identity map changes nothing", full, full.mapped(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
        // A move changes the positions only.
        val positions = setOf("centerX", "centerY", "pivotX", "pivotY", "guide")
        val moved = obj(full.mapped(floatArrayOf(1f, 0f, 5f, 0f, 1f, -7f, 0f, 0f, 1f)))
        for (n in names) {
            if (n in positions) assertNotEquals("$n moves", f[n], moved[n]) else assertEquals("$n survives mapped()", f[n], moved[n])
        }
        // Under a flip and a quarter turn, nothing goes back to its default.
        for (m in listOf(floatArrayOf(-1f, 0f, 500f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))) {
            val mapped = obj(full.mapped(m))
            for (n in names) assertNotEquals("$n is kept by mapped(${m.toList()})", def[n], mapped[n])
        }
    }
}
