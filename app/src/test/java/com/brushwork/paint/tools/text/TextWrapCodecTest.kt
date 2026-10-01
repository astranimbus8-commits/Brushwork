package com.brushwork.paint.tools.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text wrap data in text layers (v1.5 §4.1b, JVM): format 3, older data unwrapped, damaged data repaired. */
class TextWrapCodecTest {

    private val outline = WrapPolygon(listOf(10.25f, 200.125f, 150f, 0.1f), listOf(5f, 7.5f, 300.75f, 290f))

    @Test
    fun wrapRoundTripsExactlyInFormat3() {
        val item = TextItem(
            "Wrapped words", TextSpec(box = TextBoxSpec(width = 400f)), 120f, 80f, 15f,
            wrap = TextWrapSpec(sourceLayerId = 7, contour = WrapContour.BOX, gapPx = 12.5f, sides = WrapSides.BOTH, polygons = listOf(outline), minRunEm = 2f),
        )
        val json = TextCodec.encode(item)
        assertEquals(3, TextCodec.VERSION)
        assertTrue(json, json.contains("\"version\":3"))
        assertTrue(json.contains("\"wrap\""))
        val back = TextCodec.decode(json)!!
        assertEquals(item, back)
        assertEquals("float coordinates are exact", outline, back.wrap.polygons.single())
        assertTrue(back.wrapActive)
    }

    @Test
    fun version2TextReadsAsUnwrapped() {
        val v2 = """{"version":2,"item":{"text":"Old","spec":{"sizePx":30,"box":{"width":120}},"cx":5,"cy":6}}"""
        val item = TextCodec.decode(v2)!!
        assertEquals("Old", item.text)
        assertEquals(TextWrapSpec(), item.wrap)
        assertFalse(item.wrap.isOn)
        assertFalse(item.wrapActive)
        // Written again it is format 3, still unwrapped.
        assertEquals(item, TextCodec.decode(TextCodec.encode(item)))
    }

    @Test
    fun wrapAppliesOnlyToHorizontalStraightText() {
        val on = TextItem("x", wrap = TextWrapSpec(sourceLayerId = 3))
        assertTrue(on.wrapActive)
        assertFalse(on.copy(spec = on.spec.copy(vertical = true)).wrapActive)
        assertFalse(on.copy(path = TextPathSpec(type = TextPathType.CIRCLE)).wrapActive)
        assertTrue(on.copy(spec = on.spec.copy(vertical = true)).wrap.isOn)
    }

    @Test
    fun damagedWrapDataIsRepairedNotFatal() {
        val json = """{"version":3,"item":{"text":"x","wrap":{"sourceLayerId":4,"gapPx":-5,"minRunEm":999,"sides":"DIAGONAL",
            "polygons":[{"xs":[0,10,10],"ys":[0,0,10]},{"xs":[0,10],"ys":[0,0]},{"xs":[0,10,5],"ys":[0,0]},{"xs":[0,1e30,5],"ys":[0,1,2]}]}}}"""
        val item = TextCodec.decode(json)
        assertNotNull("bad wrap data never makes the text unreadable", item)
        val w = item!!.wrap
        assertEquals(4L, w.sourceLayerId)
        assertEquals(0f, w.gapPx, 0f)
        assertEquals(TextWrapSpec.MAX_RUN_EM, w.minRunEm, 0f)
        assertEquals("an unknown value falls back to the default", WrapSides.LARGEST, w.sides)
        assertEquals("only the usable outline is kept", 1, w.polygons.size)
        assertEquals(3, w.pointCount)
        // A sanitized spec is left as it is.
        assertTrue(w.sanitized() === w)
    }

    @Test
    fun storedPointsAreCapped() {
        val big = WrapPolygon(List(3000) { it.toFloat() }, List(3000) { (it % 7).toFloat() })
        val w = TextWrapSpec(sourceLayerId = 1, polygons = listOf(big, big)).sanitized()
        assertEquals(1, w.polygons.size)
        assertTrue(w.pointCount <= TextWrapSpec.MAX_STORED_POINTS)
    }
}
