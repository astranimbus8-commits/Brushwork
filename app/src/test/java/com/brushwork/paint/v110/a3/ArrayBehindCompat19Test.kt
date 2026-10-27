package com.brushwork.paint.v110.a3

import com.brushwork.paint.compat19.ArrayCodecV19
import com.brushwork.paint.compat19.v19Twin
import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 (R4 (b), (e); I4): v1.9's own spec decoder ([ArrayCodecV19] of the frozen `compat19`
 * kit, v1.9.0's copy) reads the `array` JSON v1.10 saves for a copies-only
 * layer ("Array behind", `hideSource = true`) in every mode: every other field as written, and
 * `hideSource` dropped, so v1.9 opens the saved cache (copies only) and its save writes exactly
 * what v1.10 writes for the same array without the field. v1.10 reads that save back as
 * `hideSource = false`, and an array that does not hide its source is written byte for byte as
 * v1.9 writes it (the field is never written when false). JVM.
 */
class ArrayBehindCompat19Test {
    private val stops = listOf(VStop(0f, 0xFFE02020.toInt()), VStop(1f, 0xFF2040E0.toInt()))

    /** Copies-only specs of each mode, some with a look and odd values. */
    private val behind = listOf(
        ArraySpec(count = 5, relativeX = 0.5f, relativeY = -0.25f, constantY = 12f, hideSource = true),
        ArraySpec(
            mode = ArrayMode.CIRCLE, count = 8, centerX = 100f, centerY = 120.5f, sweepDeg = 270f, rotateCopies = false,
            look = ArrayLook(opacityEnd = 0.4f, sizeEnd = 0.75f, tint = stops, tintStrength = 0.5f), hideSource = true,
        ),
        ArraySpec(
            mode = ArrayMode.CURVE, count = 4, spacing = 20f, alignToCurve = false, hideSource = true,
            guide = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(50f, 10f, inX = -5f, inY = 0f, outX = 5f, outY = 1f), VAnchor(20f, 40f, sharp = true))),
        ),
        ArraySpec(mode = ArrayMode.TRANSFORM, count = 6, moveX = 40f, moveY = -8f, turnDeg = 15f, scale = 0.9f, pivotX = 10f, pivotY = 20f, hideSource = true),
    )

    @Test
    fun v19ReadsACopiesOnlyArrayAndDropsOnlyHideSource() {
        for (spec in behind) {
            val what = "${spec.mode}, ${spec.count} copies"
            val plain = spec.copy(hideSource = false)
            val saved = ArrayCodec.encodeSpec(spec)
            assertTrue("$what: v1.10 writes the field when true: $saved", saved.contains("\"hideSource\":true"))

            // v1.9 opens it: every other field as v1.10 holds it.
            val read = ArrayCodecV19.decodeSpec(saved)
            assertNotNull("$what: v1.9 reads the spec: $saved", read)
            assertEquals("$what: v1.9 reads every other field", v19Twin(plain), read)

            // v1.9's save drops the field (I4): what v1.10 writes for the array without it.
            val v19Saved = ArrayCodecV19.encodeSpec(read!!)
            assertEquals("$what: v1.9's save", ArrayCodec.encodeSpec(plain), v19Saved)

            // v1.10 opens v1.9's save: missing means false.
            assertEquals("$what: v1.10 reads v1.9's save", plain, ArrayCodec.decodeSpec(v19Saved))

            // Not hiding its source: no key, byte for byte v1.9's JSON for the same values.
            val plainJson = ArrayCodec.encodeSpec(plain)
            assertFalse("$what: false is never written: $plainJson", plainJson.contains("hideSource"))
            assertEquals("$what: v1.9's bytes", ArrayCodecV19.encodeSpec(v19Twin(plain)), plainJson)
        }
    }
}
