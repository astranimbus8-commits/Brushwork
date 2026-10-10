package com.brushwork.paint.vector

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * v1.8 F1 (I15, §4.3): [CopyMaps] is written exactly as v1.7 writes `VStroke.copies` (a list of
 * 9-float lists, nothing when empty), on paths and on shapes, and damaged or non-affine maps are
 * dropped when read. JVM.
 */
class CopyMapsCodecTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val halfTurn = floatArrayOf(-1f, 0f, 200f, 0f, -1f, 100f, 0f, 0f, 1f)
    private val perspective = floatArrayOf(1.1f, 0.05f, 3f, 0.02f, 0.9f, -4f, 0.0004f, 0.0002f, 1f)

    private val preset = BrushPreset("round", "Round", BrushTip.ROUND_HARD, size = 12f)
    private val points = PackedPoints(floatArrayOf(1f, 20f, 40f), floatArrayOf(3f, 4f, 9f), floatArrayOf(0.5f, 0.75f, 1f))

    private val path = VPath(
        1,
        subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(50f, 10f, inX = -5f, inY = 0f, outX = 5f, outY = 1f), VAnchor(20f, 40f)))),
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
    )

    private fun inflate(b: ByteArray): String {
        val inf = Inflater()
        try {
            inf.setInput(b)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw AssertionError("truncated vector file")
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            inf.end()
        }
    }

    private fun deflate(json: String): ByteArray {
        val d = Deflater(5)
        try {
            d.setInput(json.toByteArray(Charsets.UTF_8))
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    /** The JSON objects of [c], as VectorCodec writes them. */
    private fun objects(c: VectorContent): List<JsonObject> =
        Json.parseToJsonElement(inflate(VectorCodec.encode(c))).jsonObject.getValue("objects").jsonArray.map { it.jsonObject }

    @Test
    fun copiesAreWrittenAsAStrokesCopies() {
        val maps = listOf(identity, mirror, halfTurn)
        val stroke = VStroke(1, 1f, preset, 0xFF112233.toInt(), 42L, true, points, copies = maps)
        val withCopies = path.copy(id = 2, copies = CopyMaps.of(maps))
        val written = objects(VectorContent(objects = listOf(stroke, withCopies), nextId = 3))
        val strokeCopies = written[0].getValue("copies")
        val pathCopies = written[1].getValue("copies")
        assertEquals("the same JSON shape as VStroke.copies", strokeCopies, pathCopies)
        assertEquals(3, (pathCopies as JsonArray).size)
        assertTrue(pathCopies.all { (it as JsonArray).size == 9 })
        // And back.
        val back = VectorCodec.decode(VectorCodec.encode(VectorContent(objects = listOf(withCopies), nextId = 3)))
        assertEquals(withCopies, back.objects.single())
    }

    @Test
    fun noCopiesAreNeverWritten() {
        val o = objects(VectorContent(objects = listOf(path), nextId = 2)).single()
        assertNull(o["copies"])
        assertNull(o["generator"])
        val shape = ShapeCodec.encode(ShapeObject(type = ShapeType.STAR, w = 40f, h = 40f))
        assertFalse(shape, shape.contains("copies"))
    }

    @Test
    fun damagedAndNonAffineMapsAreDroppedWhenRead() {
        fun list(vararg m: FloatArray) = m.joinToString(",", "[", "]") { a -> a.joinToString(",", "[", "]") }
        fun read(copies: String): CopyMaps {
            val json = """{"version":1,"objects":[{"type":"path","id":1,"subpaths":[{"anchors":[{"x":0.0,"y":0.0},{"x":10.0,"y":5.0}]}],""" +
                """"copies":$copies}],"nextId":2}"""
            return (VectorCodec.decode(deflate(json)).objects.single() as VPath).copies
        }
        // A homography, a singular map, a short one and a NaN are dropped; the mirror stays, after the identity.
        val damaged = list(perspective, mirror, floatArrayOf(1f, 2f, 0f, 2f, 4f, 0f, 0f, 0f, 1f), floatArrayOf(1f, 0f, 3f)).dropLast(1) +
            ",[NaN,0,0,0,1,0,0,0,1]]"
        val c = read(damaged)
        assertEquals(CopyMaps.of(listOf(mirror)), c)
        assertEquals(2, c.maps.size)
        // Without the identity in front, it is put there.
        assertEquals(CopyMaps.of(listOf(identity, halfTurn)), read(list(halfTurn)))
        // Nothing usable: no copies.
        assertSame(CopyMaps.NONE, read(list(perspective)))
        assertSame(CopyMaps.NONE, read("[]"))
        assertSame(CopyMaps.NONE, read(list(identity)))
    }

    @Test
    fun shapeCopiesRoundTripThroughTheShapeCodec() {
        val shape = ShapeObject(type = ShapeType.STAR, cx = 50f, cy = 60f, w = 40f, h = 40f, copies = CopyMaps.of(listOf(mirror, halfTurn)))
        val text = ShapeCodec.encode(shape)
        val written = Json.parseToJsonElement(text).jsonObject.getValue("shape").jsonObject.getValue("copies")
        assertEquals(3, (written as JsonArray).size)
        assertEquals(shape, ShapeCodec.decode(text))
        // A perspective map written by hand is dropped when read.
        val crafted = text.replace(Regex("\"copies\":\\[.*?\\]\\]"), "\"copies\":[[1.1,0.05,3,0.02,0.9,-4,0.0004,0.0002,1]]")
        assertTrue(crafted, crafted.contains("0.0004"))
        assertSame(CopyMaps.NONE, ShapeCodec.decode(crafted)!!.copies)
    }
}
