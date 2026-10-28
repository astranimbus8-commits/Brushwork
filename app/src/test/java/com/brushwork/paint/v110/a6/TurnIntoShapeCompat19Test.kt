package com.brushwork.paint.v110.a6

import com.brushwork.paint.compat18.ShapeTypeV18
import com.brushwork.paint.compat18.VShapeV18
import com.brushwork.paint.compat18.VectorCodecV18
import com.brushwork.paint.compat18.VectorContentV18
import com.brushwork.paint.compat19.ShapeTypeV19
import com.brushwork.paint.compat19.VShapeV19
import com.brushwork.paint.compat19.VectorCodecV19
import com.brushwork.paint.compat19.VectorContentV19
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * v1.10 A6 (R11 (b)): a path turned into a shape is an ordinary shape object with no new field.
 * The `.vec` data v1.10 writes for it uses only keys the frozen v1.8 and v1.9 readers
 * ([VectorCodecV18], [VectorCodecV19] of the G2 `compat19` kit) know, so both read the same custom
 * outline, style, id and copies; v1.10 reads it back unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class TurnIntoShapeCompat19Test {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    private fun inflate(b: ByteArray): String {
        val inf = Inflater()
        try {
            inf.setInput(b)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            inf.end()
        }
    }

    /** The keys of the "shape" of object [i] in the vector JSON [text]. */
    private fun shapeKeys(text: String, i: Int): Set<String> =
        (Json.parseToJsonElement(text).jsonObject["objects"]!!.jsonArray[i].jsonObject["shape"] as JsonObject).keys

    @Test
    fun v18AndV19ReadTheConvertedShape() {
        val c = kit.controller()
        val copies = CopyMaps.of(listOf(floatArrayOf(-1f, 0f, 400f, 0f, 1f, 0f, 0f, 0f, 1f)))
        val ids = A6Paths.add(kit, c, A6Paths.spline(cyclic = true).copy(fill = VPaint.Solid(RED), copies = copies), A6Paths.polyline())
        A6Paths.select(c, ids[0])
        assertTrue(ObjectActions.turnIntoShape(c))
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        val content = c.vec.vector!!
        val shape = content.objects[0] as VShape

        val bytes = VectorCodec.encode(content)
        // v1.10 reads it back unchanged.
        assertEquals(content.objects, VectorCodec.decode(bytes).objects)

        // v1.8 reads the same outline, style, id and copies.
        val old = VectorCodecV18.decode(bytes)
        val o = old.objects[0] as? VShapeV18 ?: throw AssertionError("v1.8 reads a shape: ${old.objects[0]}")
        assertEquals(shape.id, o.id)
        assertEquals(ShapeTypeV18.RECTANGLE, o.shape.type)
        assertEquals(shape.shape.points!!.size, o.shape.points!!.size)
        assertEquals(shape.shape.points!!.map { it.x to it.y }, o.shape.points!!.map { it.x to it.y })
        assertEquals(shape.shape.points!!.map { it.smooth }, o.shape.points!!.map { it.smooth })
        assertEquals(shape.shape.style.name, o.shape.style.name)
        assertEquals(shape.shape.strokeColor, o.shape.strokeColor)
        assertEquals(shape.shape.fillColor, o.shape.fillColor)
        assertEquals(shape.shape.strokeWidth, o.shape.strokeWidth)
        assertEquals(shape.shape.corner.name, o.shape.corner.name)
        assertEquals("the copies", copies.maps.size, o.shape.copies.maps.size)

        // No new field: every key written for it is one v1.8 writes for the same shape.
        val written = shapeKeys(inflate(bytes), 0)
        val v18 = shapeKeys(VectorCodecV18.encodeJson(VectorContentV18(objects = listOf(o), nextId = old.nextId)), 0)
        assertTrue("keys v1.8 doesn't know: ${written - v18}", v18.containsAll(written))

        // v1.9 (the frozen compat19 kit) reads the same outline, style, id and copies, and writes no key fewer.
        val v19Read = VectorCodecV19.decode(bytes)
        val n = v19Read.objects[0] as? VShapeV19 ?: throw AssertionError("v1.9 reads a shape: ${v19Read.objects[0]}")
        assertEquals(shape.id, n.id)
        assertEquals(ShapeTypeV19.RECTANGLE, n.shape.type)
        assertEquals(shape.shape.points.map { it.x to it.y }, n.shape.points!!.map { it.x to it.y })
        assertEquals(shape.shape.points.map { it.smooth }, n.shape.points.map { it.smooth })
        assertEquals(shape.shape.style.name, n.shape.style.name)
        assertEquals(shape.shape.strokeColor, n.shape.strokeColor)
        assertEquals(shape.shape.fillColor, n.shape.fillColor)
        assertEquals(shape.shape.strokeWidth, n.shape.strokeWidth)
        assertEquals(shape.shape.corner.name, n.shape.corner.name)
        assertEquals("the copies (v1.9)", copies.maps.size, n.shape.copies.maps.size)
        val v19 = shapeKeys(VectorCodecV19.encodeJson(VectorContentV19(objects = listOf(n), nextId = v19Read.nextId)), 0)
        assertTrue("keys v1.9 doesn't know: ${written - v19}", v19.containsAll(written))
    }

    private companion object {
        const val RED = 0xFFE04020.toInt()
    }
}
