package com.brushwork.paint.tools.vector

import com.brushwork.paint.compat17.ShapeCodecV17
import com.brushwork.paint.compat17.ShapeTypeV17
import com.brushwork.paint.vector.CopyMaps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 F1 (D23, §4.3): a cog is written with its outline as fallback points and `cogFallback`
 * set, so v1.7 (which reads the type as RECTANGLE) draws its teeth; v1.8 drops the points and the
 * flag when it reads the cog back. Every other shape is written exactly as v1.7 writes it. JVM.
 */
class CogFallbackWriteTest {
    private val cog = ShapeObject(type = ShapeType.COG, cx = 100f, cy = 80f, w = 120f, h = 110f, rotation = 15f, cogTeeth = 16, cogDepth = 0.25f)
    private val holed = cog.copy(cogHole = 0.4f, cogTaper = 0.6f, cogToothWidth = 0.35f)

    private fun shapeJson(text: String) = Json.parseToJsonElement(text).jsonObject.getValue("shape").jsonObject

    @Test
    fun aCogIsWrittenWithItsOutlineAsFallbackPoints() {
        for (c in listOf(cog, holed, ShapeObject(type = ShapeType.COG, w = 50f, h = 50f))) {
            val text = ShapeCodec.encode(c)
            val shape = shapeJson(text)
            assertEquals("COG", shape.getValue("type").jsonPrimitive.content)
            assertEquals(JsonPrimitive(true), shape["cogFallback"])
            val expected = ShapeGeometry.vertices(ShapeType.COG, 1f, 1f, c.outlineParams)
            assertTrue("a point per outline vertex: ${expected.size}", expected.size >= c.cogTeeth)
            val pts = shape.getValue("points").jsonArray
            assertEquals(expected.size, pts.size)
            // v1.8 reads the cog: no points, no flag, its own fields.
            val back = ShapeCodec.decode(text)!!
            assertNull(back.points)
            assertFalse(back.cogFallback)
            assertEquals(c, back)
        }
    }

    @Test
    fun v17DrawsTheFallbackOutlineAsARectangleWithPoints() {
        for (c in listOf(cog, holed)) {
            val text = ShapeCodec.encode(c)
            val v17 = ShapeCodecV17.decode(text)
            assertNotNull(v17)
            assertEquals(ShapeTypeV17.RECTANGLE, v17!!.type)
            val expected = ShapeGeometry.vertices(ShapeType.COG, 1f, 1f, c.outlineParams)
            val pts = v17.points!!
            assertEquals(expected.size, pts.size)
            for (i in expected.indices) {
                assertEquals("x $i", expected[i].x, pts[i].x, 0f)
                assertEquals("y $i", expected[i].y, pts[i].y, 0f)
            }
            assertEquals(c.cx, v17.cx, 0f)
            assertEquals(c.cy, v17.cy, 0f)
            assertEquals(c.w, v17.w, 0f)
            assertEquals(c.h, v17.h, 0f)
            assertEquals(c.rotation, v17.rotation, 0f)
        }
    }

    @Test
    fun aCogWithItsOwnPointsIsWrittenAsItIs() {
        val own = cog.copy(points = listOf(ShapePoint(-0.5f, -0.5f), ShapePoint(0.5f, -0.5f), ShapePoint(0f, 0.5f)))
        assertSame(own, own.forWriting())
        val text = ShapeCodec.encode(own)
        assertNull(shapeJson(text)["cogFallback"])
        assertEquals(own, ShapeCodec.decode(text))
    }

    @Test
    fun everyOtherShapeIsWrittenExactlyAsV17WritesIt() {
        val shapes = ShapeType.entries.filter { it != ShapeType.COG }.map { t -> ShapeObject(type = t, cx = 30f, cy = 40f, w = 80f, h = 60f) } +
            listOf(
                ShapeObject(type = ShapeType.STAR, w = 50f, h = 50f, starPoints = 7, innerRatio = 0.3f, points = listOf(ShapePoint(0f, -0.5f), ShapePoint(0.5f, 0.5f), ShapePoint(-0.5f, 0.5f))),
                ShapeObject(type = ShapeType.POLYGON, sides = 9, corner = CornerStyle.INVERTED, cornerRadius = 12f),
                ShapeObject(type = ShapeType.ARROW, w = 90f, h = 0f, rotation = -45f, arrowHeads = ArrowHeads.BOTH),
            )
        for (s in shapes) {
            assertSame(s.type.name, s, s.forWriting())
            val text = ShapeCodec.encode(s)
            assertFalse(text, text.contains("cog"))
            assertFalse(text, text.contains("copies"))
            val v17 = ShapeCodecV17.decode(text)
            assertNotNull(text, v17)
            assertEquals("${s.type}: the same JSON as v1.7", text, ShapeCodecV17.encode(v17!!))
        }
    }

    @Test
    fun aFlagWithoutPointsReadsAsTheCog() {
        // A file with the flag but no points (damaged, or the outline stripped by hand) is still the cog.
        val text = ShapeCodec.encode(holed).let { t ->
            val shape = shapeJson(t)
            val stripped = kotlinx.serialization.json.JsonObject(shape - "points")
            """{"version":2,"shape":$stripped}"""
        }
        val back = ShapeCodec.decode(text)!!
        assertNull(back.points)
        assertFalse(back.cogFallback)
        assertEquals(holed, back)
        // Copies of a cog are kept as well.
        val mirrored = holed.copy(copies = CopyMaps.of(listOf(floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f))))
        assertEquals(mirrored, ShapeCodec.decode(ShapeCodec.encode(mirrored)))
    }
}
