package com.brushwork.paint.tools.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v1.9 F1a (R14, design 2.3.2): several texts per layer. [TextCodec.encodeAll] writes one text as
 * `encode` does (v1.8's bytes) and several as `item` plus `more` with unique ids;
 * [TextCodec.decodeAll] reads every text; `wrapSourceId` and `withWrapSource` see every text and
 * never drop `more`. JVM.
 */
class TextCodecV19Test {
    private val wrap = TextWrapSpec(7L, gapPx = 12f, polygons = listOf(WrapPolygon(listOf(0f, 50f, 50f), listOf(0f, 0f, 40f))))

    @Test
    fun oneTextIsWrittenAsV18WroteIt() {
        val items = listOf(
            TextItem("Plain"),
            TextItem("Kerned", TextSpec(sizePx = 40f), 3f, 4f, 5f, kerns = listOf(TextKern(1, 30))),
            TextItem("Wrapped", TextSpec(box = TextBoxSpec(width = 250f)), 300f, 100f, wrap = wrap),
            TextItem("With an id", id = 9),
        )
        for (item in items) {
            assertEquals(item.text, TextCodec.encode(item), TextCodec.encodeAll(listOf(item)))
            assertFalse(item.text, TextCodec.encodeAll(listOf(item)).contains("\"more\""))
            assertEquals(item.text, listOf(TextCodec.decode(TextCodec.encode(item))), TextCodec.decodeAll(TextCodec.encode(item)))
        }
    }

    @Test
    fun severalTextsRoundTripWithUniqueIds() {
        val items = listOf(TextItem("A", id = 3), TextItem("B"), TextItem("C", id = 3), TextItem("D", id = 7), TextItem("E", id = -2))
        val data = TextCodec.encodeAll(items)
        assertTrue(data, data.startsWith("{\"version\":5,") && data.contains("\"more\":["))
        val back = TextCodec.decodeAll(data)
        assertEquals(listOf("A", "B", "C", "D", "E"), back.map { it.text })
        // The first holder of a positive id keeps it; the others get max + 1, ... in order.
        assertEquals(listOf(3, 8, 9, 7, 10), back.map { it.id })
        assertEquals("a second encode is stable", data, TextCodec.encodeAll(back))
        assertEquals("decode reads the first text", back[0], TextCodec.decode(data))
        val fresh = TextCodec.decodeAll(TextCodec.encodeAll(listOf(TextItem("x"), TextItem("y"), TextItem("z"))))
        assertEquals("ids from 1 when none is set", listOf(1, 2, 3), fresh.map { it.id })
    }

    @Test
    fun theTextsAreSanitizedOnTheWayInAndOut() {
        val data = TextCodec.encodeAll(listOf(TextItem("ok"), TextItem("bad", TextSpec(sizePx = Float.NaN), Float.NaN, 5f)))
        val back = TextCodec.decodeAll(data)
        assertTrue(back.all { it == it.sanitized() })
        assertEquals(TextItem("bad", TextSpec(sizePx = Float.NaN), Float.NaN, 5f).sanitized().copy(id = back[1].id), back[1])
    }

    @Test
    fun theLimitsHold() {
        try {
            TextCodec.encodeAll(emptyList())
            fail("no texts")
        } catch (_: IllegalArgumentException) {
        }
        val most = (1..TextCodec.MAX_ITEMS).map { TextItem("t$it") }
        assertEquals(TextCodec.MAX_ITEMS, TextCodec.decodeAll(TextCodec.encodeAll(most)).size)
        try {
            TextCodec.encodeAll(most + TextItem("one too many"))
            fail("65 texts")
        } catch (_: IllegalArgumentException) {
        }
        // A layer written with more than 64 texts (damaged or crafted) reads its first 64.
        val more = (2..70).joinToString(",") { "{\"text\":\"t$it\",\"id\":$it}" }
        val crafted = "{\"version\":5,\"item\":{\"text\":\"t1\",\"id\":1},\"more\":[$more]}"
        val read = TextCodec.decodeAll(crafted)
        assertEquals(TextCodec.MAX_ITEMS, read.size)
        assertEquals("t64", read.last().text)
    }

    @Test
    fun nothingReadableReadsAsNoText() {
        for (data in listOf(null, "", "   ", "not json", "{\"version\":5,\"item\":[]}")) {
            assertEquals("$data", emptyList<TextItem>(), TextCodec.decodeAll(data))
        }
        assertEquals("a v1.8 layer reads its one text", 1, TextCodec.decodeAll("{\"version\":5,\"item\":{\"text\":\"v18\"}}").size)
    }

    @Test
    fun theWrapSourceIsFoundAndMovedInEveryText() {
        val plain = TextItem("Plain")
        val wrapping = TextItem("Wrapping", TextSpec(box = TextBoxSpec(width = 200f)), wrap = wrap)
        val data = TextCodec.encodeAll(listOf(plain, wrapping))
        assertEquals("a wrapping text in more counts", 7L, TextCodec.wrapSourceId(data))
        assertEquals(0L, TextCodec.wrapSourceId(TextCodec.encodeAll(listOf(plain, TextItem("b")))))
        assertEquals(0L, TextCodec.wrapSourceId(null))

        val moved = TextCodec.withWrapSource(data, 12L)
        val back = TextCodec.decodeAll(moved)
        assertEquals("more is kept", 2, back.size)
        assertEquals(12L, back[1].wrap.sourceLayerId)
        assertEquals("only the source moves", wrapping.wrap.polygons, back[1].wrap.polygons)
        assertSame("already wrapping around that layer", moved, TextCodec.withWrapSource(moved, 12L))
        assertSame("no layer", data, TextCodec.withWrapSource(data, 0L))
        val none = TextCodec.encodeAll(listOf(plain, TextItem("b")))
        assertSame("no text wraps", none, TextCodec.withWrapSource(none, 12L))
        assertSame("unreadable", "garbage", TextCodec.withWrapSource("garbage", 12L))

        // One text: the v1.8 bytes.
        val one = TextCodec.encode(wrapping)
        assertEquals(TextCodec.encode(wrapping.copy(wrap = wrapping.wrap.copy(sourceLayerId = 12L))), TextCodec.withWrapSource(one, 12L))
    }
}
