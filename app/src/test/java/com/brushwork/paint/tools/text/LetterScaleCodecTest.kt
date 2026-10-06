package com.brushwork.paint.tools.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6 §3.5(b, d): the codec round trip of letter scaling (JVM). Version 4 writes
 * `spec.letterScale`; version 3 data (v1.5) reads unscaled; damaged or unknown values read as
 * usable ones (I4: never fail to open a text layer).
 */
class LetterScaleCodecTest {

    @Test
    fun v4WritesAndReadsLetterScaling() {
        val spec = LetterScaleSpec(
            smallestPercent = 62f, direction = LetterScaleDirection.END_TO_START, align = LetterScaleAlign.TOP,
            curve = LetterScaleCurve.RATIO, scope = LetterScaleScope.EACH_PARAGRAPH,
        )
        val item = TextItem("ELTON JOHN", TextSpec(sizePx = 42f, letterScale = spec), 10f, 20f)
        val json = TextCodec.encode(item)
        assertTrue(json, json.contains("\"version\":${TextCodec.VERSION}"))
        assertTrue(json, json.contains("\"letterScale\""))
        val back = TextCodec.decode(json)!!
        assertEquals(item, back)
        assertEquals(spec, back.spec.letterScale)
    }

    @Test
    fun v3DataReadsUnscaled() {
        val v3 = """{"version":3,"item":{"text":"Old text","spec":{"sizePx":30.0,"box":{"width":120.0}},"cx":5.0,"cy":6.0}}"""
        val item = TextCodec.decode(v3)!!
        assertEquals("Old text", item.text)
        assertFalse(item.spec.letterScale.isOn)
        assertEquals(LetterScaleSpec(), item.spec.letterScale)
        // Written back: version 4 with the default (off) scaling.
        val again = TextCodec.decode(TextCodec.encode(item))!!
        assertEquals(item, again)
    }

    @Test
    fun damagedValuesReadAsUsableOnes() {
        val bad = """{"version":4,"item":{"text":"x","spec":{"letterScale":{"smallestPercent":1.0,"direction":"SIDEWAYS","align":"MIDDLE","curve":"EVEN"}}}}"""
        val item = TextCodec.decode(bad)!!
        val s = item.spec.letterScale
        assertEquals(LetterScaleSpec.MIN_PERCENT, s.smallestPercent, 0f)
        assertEquals(LetterScaleDirection.START_TO_END, s.direction)
        assertEquals(LetterScaleAlign.CENTER, s.align)
        val nan = """{"version":4,"item":{"text":"x","spec":{"letterScale":{"smallestPercent":NaN}}}}"""
        assertFalse(TextCodec.decode(nan)!!.spec.letterScale.isOn)
        // A newer field this version doesn't know is ignored.
        val newer = """{"version":5,"item":{"text":"x","spec":{"letterScale":{"smallestPercent":50.0,"wobble":3}}}}"""
        assertEquals(50f, TextCodec.decode(newer)!!.spec.letterScale.smallestPercent, 0f)
    }
}
