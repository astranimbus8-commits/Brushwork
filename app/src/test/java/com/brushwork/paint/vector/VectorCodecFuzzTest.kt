package com.brushwork.paint.vector

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random

/**
 * v1.5 A1: [VectorCodec] round trips every object kind exactly and turns any damaged input —
 * truncation at every length, bit flips, garbage, wrong JSON, zip bombs, mismatched point data —
 * into a [CorruptVectorException], never another exception or a crash. Readable files get unique
 * ids. (JVM.)
 */
class VectorCodecFuzzTest {

    private fun content(): VectorContent {
        val r = Random(5)
        val pts = PackedPoints(FloatArray(120) { r.nextFloat() * 900f }, FloatArray(120) { r.nextFloat() * 700f }, FloatArray(120) { r.nextFloat() })
        return VectorContent.EMPTY.plus(
            listOf(
                VStroke(0, preset = BrushLibrary.byId("chalk")!!, color = 0xFF102030.toInt(), seed = 77, stylus = true, points = pts, sizeScale = 1.75f, taperIn = false),
                VPath(
                    0, opacity = 0.5f,
                    subpaths = listOf(
                        VSubpath(listOf(VAnchor(1f, 2f), VAnchor(30f, 40f, sharp = true, inX = -3f, inY = 1f, outX = 4f, outY = 0.5f, width = 2.5f), VAnchor(90f, 10f)), closed = true),
                        VSubpath(listOf(VAnchor(5f, 5f), VAnchor(6f, 7f))),
                    ),
                    tension = 0.3f, fillRule = VFillRule.EVENODD,
                    fill = VPaint.Radial(10f, 20f, 30f, listOf(VStop(0f, -1), VStop(1f, 0xFF00FF00.toInt())), listOf(1f, 0.1f, 0f, 1f, 3f, 4f)),
                    stroke = VStrokeStyle(VStrokeKind.BRUSH, 0xFF0000FF.toInt(), 12f, brushTool = ToolId.BRUSH, brush = BrushLibrary.defaultBrush, seed = 9, taperPercent = 20f),
                ),
                VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(10f, 0f)))), fill = VPaint.Linear(0f, 0f, 1f, 1f, listOf(VStop(0.5f, 7)))),
                VShape(0, shape = ShapeObject(ShapeType.STAR, cx = 50f, cy = 60f, w = 40f, h = 30f, rotation = 12f), seed = 3),
            ),
        ).first
    }

    @Test
    fun everyObjectKindRoundTripsExactly() {
        val c = content()
        val back = VectorCodec.decode(VectorCodec.encode(c))
        assertEquals(c, back)
        // The empty content too.
        assertEquals(VectorContent.EMPTY, VectorCodec.decode(VectorCodec.encode(VectorContent.EMPTY)))
    }

    /** [block] either decodes or throws CorruptVectorException; anything else fails the test. */
    private fun decodesOrCorrupt(b: ByteArray, what: String) {
        try {
            VectorCodec.decode(b)
        } catch (e: CorruptVectorException) {
            // fine
        } catch (t: Throwable) {
            fail("$what: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun corrupt(b: ByteArray, what: String, max: Long = 128L shl 20) {
        try {
            VectorCodec.decode(b, max)
            fail("$what was read")
        } catch (e: CorruptVectorException) {
            // expected
        } catch (t: Throwable) {
            fail("$what: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun deflate(text: String): ByteArray = deflate(text.toByteArray())

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(5)
        d.setInput(bytes)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    @Test
    fun everyTruncationIsCorrupt() {
        val full = VectorCodec.encode(content())
        for (n in 0 until full.size) corrupt(full.copyOf(n), "truncated to $n of ${full.size} bytes")
    }

    @Test
    fun bitFlipsAndGarbageNeverCrash() {
        val full = VectorCodec.encode(content())
        val r = Random(11)
        repeat(400) { k ->
            val b = full.copyOf()
            repeat(1 + r.nextInt(4)) { b[r.nextInt(b.size)] = (b[r.nextInt(b.size)].toInt() xor (1 shl r.nextInt(8))).toByte() }
            decodesOrCorrupt(b, "flip $k")
        }
        repeat(100) { k -> corrupt(ByteArray(1 + r.nextInt(400)) { r.nextInt(256).toByte() }.also { it[0] = 0x13 }, "garbage $k") }
        // Valid Deflate around garbage text.
        repeat(50) { k -> corrupt(deflate(String(CharArray(r.nextInt(300)) { (32 + r.nextInt(90)).toChar() })), "garbage text $k") }
    }

    @Test
    fun wrongJsonIsCorrupt() {
        val cases = listOf(
            "" to "empty text",
            "[]" to "an array",
            "{\"objects\": 3}" to "a number for the objects",
            "{\"objects\": [{\"type\": \"comet\", \"id\": 1}]}" to "an unknown object kind",
            "{\"objects\": [{\"type\": \"stroke\", \"id\": 1}]}" to "a stroke without its fields",
            "{\"objects\": [{\"type\": \"stroke\", \"id\": 1, \"preset\": {}, \"color\": 0, \"seed\": 0, \"stylus\": false, \"points\": {\"n\": 2, \"d\": \"AAAA\"}}]}" to "point data of the wrong size",
            "{\"objects\": [{\"type\": \"stroke\", \"id\": 1, \"preset\": {}, \"color\": 0, \"seed\": 0, \"stylus\": false, \"points\": {\"n\": 2000000000, \"d\": \"\"}}]}" to "a huge point count",
            "{\"objects\": [{\"type\": \"stroke\", \"id\": 1, \"preset\": {}, \"color\": 0, \"seed\": 0, \"stylus\": false, \"points\": {\"n\": 1, \"d\": \"%%%%\"}}]}" to "broken base64",
            "{\"objects\": [" + "[".repeat(5000) to "deep nesting",
            "{\"nextId\": \"x\"}" to "a string for nextId",
        )
        for ((json, what) in cases) corrupt(deflate(json), what)
    }

    @Test
    fun aZipBombStopsAtTheLimit() {
        // 2 MB of spaces inflate past a 1 MB limit: rejected without reading it all.
        val bomb = deflate(ByteArray(2 shl 20) { ' '.code.toByte() })
        assertTrue(bomb.size < 20_000)
        corrupt(bomb, "a zip bomb", max = 1L shl 20)
    }

    @Test
    fun unknownFieldsAndNewerEnumValuesStillRead() {
        val json = "{\"version\": 1, \"future\": {\"a\": [1, 2]}, \"objects\": [{\"type\": \"path\", \"id\": 4, \"subpaths\": [], \"fillRule\": \"SPIRAL\", \"newField\": 1}], \"nextId\": 5}"
        val c = VectorCodec.decode(deflate(json))
        assertEquals(1, c.objects.size)
        assertEquals(VFillRule.NONZERO, (c.objects[0] as VPath).fillRule)
    }

    @Test
    fun duplicateIdsAreRepairedAndNextIdMovedPastThem() {
        val a = VPath(3, subpaths = emptyList())
        val bad = VectorContent(objects = listOf(a, a.copy(opacity = 0.5f), a.copy(id = 9)), nextId = 2)
        val fixed = VectorCodec.decode(VectorCodec.encode(bad))
        assertEquals(listOf(3L, 10L, 9L), fixed.objects.map { it.id })
        assertEquals(11L, fixed.nextId)
        assertEquals(0.5f, fixed.objects[1].opacity)
        // A sound content stays the same instance.
        val good = content()
        assertSame(good, VectorCodec.sanitized(good))
        // nextId behind the ids only.
        assertEquals(10L, VectorCodec.sanitized(VectorContent(objects = listOf(a.copy(id = 9)), nextId = 1)).nextId)
    }
}
