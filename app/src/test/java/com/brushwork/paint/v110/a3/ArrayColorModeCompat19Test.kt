package com.brushwork.paint.v110.a3

import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.vector.VStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 (R5 (b), (e); I4): v1.9's own spec decoder ([ArrayCodecV19], the frozen copy of v1.9.0
 * standing in for the lead's `compat19` kit) reads a Recolored array's `array` JSON as its tint:
 * the same stops, strength and the rest of the look, so a v1.9 re-render draws Tint, and its save
 * drops the mode (what v1.10 writes for the Tinted look). v1.10 reads that save, and an unknown
 * future mode, as Tinted. A Tinted look never writes the mode and is byte for byte v1.9's JSON,
 * and a Recolored look that draws as none (no tint) is not written at all, as in v1.9. JVM.
 */
class ArrayColorModeCompat19Test {

    @Test
    fun v19ReadsARecoloredLookAsItsTintAndTintedIsWrittenAsV19WritesIt() {
        val stops = listOf(VStop(0f, 0xFFE02020.toInt()), VStop(0.5f, 0xFF20C040.toInt()), VStop(1f, 0xFF2040E0.toInt()))
        val tintLook = ArrayLook(opacityEnd = 0.5f, sizeEnd = 0.8f, turnEnd = 30f, easing = ArrayEasing.EASE_IN, tint = stops, tintStrength = 0.6f)
        for (hide in listOf(false, true)) {
            val what = if (hide) "copies only" else "with its source"
            val tinted = ArraySpec(count = 6, constantX = 4f, look = tintLook, hideSource = hide)
            val recolored = tinted.copy(look = tintLook.copy(colorMode = ArrayColorMode.RECOLOR))
            val saved = ArrayCodec.encodeSpec(recolored)
            assertTrue("$what: v1.10 writes the mode when Recolored: $saved", saved.contains("\"colorMode\":\"RECOLOR\""))

            // v1.9 opens it as its tint: the same stops and strength, the rest of the look kept.
            val read = ArrayCodecV19.decodeSpec(saved)
            assertNotNull("$what: v1.9 reads the spec: $saved", read)
            val look = requireNotNull(read!!.look) { "$what: v1.9 keeps the look" }
            assertEquals("$what: the same stops", stops.map { it.offset to it.color }, look.tint.map { it.offset to it.color })
            assertEquals("$what: the same strength", 0.6f, look.tintStrength)
            assertEquals("$what: v1.9 reads every other field", v19Twin(tinted), read)

            // v1.9's save drops the mode (I4): what v1.10 writes for the Tinted look; v1.10 reads it as Tinted.
            val v19Saved = ArrayCodecV19.encodeSpec(read)
            assertEquals("$what: v1.9's save", ArrayCodec.encodeSpec(tinted.copy(hideSource = false)), v19Saved)
            assertEquals("$what: v1.10 reads v1.9's save as Tinted", tinted.copy(hideSource = false), ArrayCodec.decodeSpec(v19Saved))

            // An unknown future mode reads as Tinted (coerceInputValues).
            assertEquals("$what: an unknown mode", tinted, ArrayCodec.decodeSpec(saved.replace("\"RECOLOR\"", "\"SPARKLE\"")))
        }

        // Tinted: the mode is never written; byte for byte v1.9's JSON for the same values.
        val tinted = ArraySpec(count = 6, constantX = 4f, look = tintLook)
        val tintJson = ArrayCodec.encodeSpec(tinted)
        assertFalse("TINT is never written: $tintJson", tintJson.contains("colorMode"))
        assertEquals("v1.9's bytes", ArrayCodecV19.encodeSpec(v19Twin(tinted)), tintJson)

        // Recolored without a tint draws as no look: none is written, as v1.9 writes plain copies.
        val bare = ArraySpec(count = 4, look = ArrayLook(colorMode = ArrayColorMode.RECOLOR))
        assertEquals("a Recolored look without a tint", ArrayCodecV19.encodeSpec(ArraySpecV19(count = 4)), ArrayCodec.encodeSpec(bare))
    }
}
