package com.brushwork.paint.tools.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 F1 (item 17): [TextKern]s are sanitized against the text (the story of a linked frame),
 * written only when present, and `spec.fontKerning` only when off; other text encodes as in v1.6
 * except the version number ([TextCodec.VERSION] 5). Pure JVM.
 */
class TextKernSanitizeTest {
    @Test
    fun indicesOutsideTheTextAreDropped() {
        // "Hello": a kern after character i needs a character i + 1, so 0..3 only.
        val k = TextKern.sanitized(listOf(TextKern(-1, 10), TextKern(0, 20), TextKern(3, 30), TextKern(4, 40), TextKern(99, 50)), 5)
        assertEquals(listOf(TextKern(0, 20), TextKern(3, 30)), k)
        assertTrue(TextKern.sanitized(listOf(TextKern(0, 10)), 1).isEmpty())
        assertTrue(TextKern.sanitized(listOf(TextKern(0, 10)), 0).isEmpty())
    }

    @Test
    fun valuesAreClampedAndZerosDropped() {
        val k = TextKern.sanitized(listOf(TextKern(0, -5000), TextKern(1, 0), TextKern(2, 1001), TextKern(3, -1000)), 10)
        assertEquals(listOf(TextKern(0, TextKern.MIN_VALUE), TextKern(2, TextKern.MAX_VALUE), TextKern(3, -1000)), k)
    }

    @Test
    fun oneSortedKernPerIndexAtMostTenThousand() {
        val k = TextKern.sanitized(listOf(TextKern(5, 1), TextKern(2, 2), TextKern(5, 3), TextKern(2, 0), TextKern(1, 4)), 10)
        // The first kern of an index is kept (a zero one is no kern).
        assertEquals(listOf(TextKern(1, 4), TextKern(2, 2), TextKern(5, 1)), k)
        val many = List(12_000) { TextKern(11_999 - it, 7) }
        val capped = TextKern.sanitized(many, 20_000)
        assertEquals(TextKern.MAX_COUNT, capped.size)
        assertEquals((0 until TextKern.MAX_COUNT).toList(), capped.map { it.index })
    }

    @Test
    fun usableKernsAreTheSameList() {
        val ok = listOf(TextKern(0, -50), TextKern(2, 120))
        assertSame(ok, TextKern.sanitized(ok, 4))
        val item = TextItem("Wave", kerns = ok)
        assertSame(ok, item.sanitized().kerns)
    }

    @Test
    fun aFrameKernsItsStory() {
        val thread = TextThreadSpec(storyId = 9, story = "One two three", start = 4, end = 7)
        val item = TextItem("two", TextSpec(box = TextBoxSpec(width = 100f, minHeight = 50f)), thread = thread, kerns = listOf(TextKern(1, 30), TextKern(10, 40), TextKern(12, 50)))
        val s = item.sanitized()
        assertEquals("two", s.text)
        // Index 10 lies in the story beyond this frame's slice: kept; 12 is its last character.
        assertEquals(listOf(TextKern(1, 30), TextKern(10, 40)), s.kerns)
    }

    @Test
    fun theCodecWritesKerningOnlyWhenUsed() {
        assertEquals(5, TextCodec.VERSION)
        val plain = TextCodec.encode(TextItem("Plain"))
        assertTrue(plain, plain.startsWith("{\"version\":5,"))
        assertFalse(plain, plain.contains("kerns"))
        assertFalse(plain, plain.contains("fontKerning"))

        val kerned = TextItem("AVATAR", TextSpec(fontKerning = false), kerns = listOf(TextKern(0, -80), TextKern(1, -80)))
        val json = TextCodec.encode(kerned)
        assertTrue(json, json.contains("\"kerns\":[{\"index\":0,\"value\":-80},{\"index\":1,\"value\":-80}]"))
        assertTrue(json, json.contains("\"fontKerning\":false"))
        assertEquals(kerned, TextCodec.decode(json))

        // Damaged kerns are sanitized on read; a v1.6 text (format 4) reads with none, font kerning on.
        val damaged = json.replace("\"index\":1,\"value\":-80", "\"index\":40,\"value\":-80")
        assertEquals(listOf(TextKern(0, -80)), TextCodec.decode(damaged)!!.kerns)
        val v16 = TextCodec.decode("""{"version":4,"item":{"text":"Old","spec":{"sizePx":30}}}""")!!
        assertTrue(v16.kerns.isEmpty())
        assertTrue(v16.spec.fontKerning)
    }
}
