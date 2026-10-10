package com.brushwork.paint.vector

import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F1 (D23, design §4.3, finding 22): a vector-layer cog with a hole keeps its parameters
 * through a `.vec` file (`VectorCodec`) and through a Brushwork SVG / PDF payload
 * (`Payload` + `PayloadImport.sound`): written with its fallback outline and `cogFallback`, read
 * back with `points == null`, `cogFallback == false` and the hole kept. Content without a cog is
 * the same instance through `sanitizedShapes` and `forWriting`, so its bytes never change.
 * (Robolectric: the import's bounds check uses RectF.)
 */
@RunWith(RobolectricTestRunner::class)
class CogVectorRoundTripTest {
    private val cog = ShapeObject(
        type = ShapeType.COG, cx = 200f, cy = 150f, w = 160f, h = 160f, rotation = 12f, style = ShapeStyle.STROKE_FILL,
        cogTeeth = 24, cogDepth = 0.15f, cogToothWidth = 0.45f, cogTaper = 0.4f, cogHole = 0.55f,
    )
    private val path = VPath(
        2,
        subpaths = listOf(VSubpath(listOf(VAnchor(10f, 10f), VAnchor(90f, 40f), VAnchor(30f, 80f)))),
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 3f),
    )
    private val content = VectorContent(objects = listOf(VShape(1, 0.8f, cog, seed = 9L), path), nextId = 3)

    private fun payload(c: VectorContent) = BrushworkPayload(
        width = 400, height = 300,
        layers = listOf(
            PayloadLayer(
                1, LayerProps("Vector 1", 1f, LayerBlendMode.NORMAL, true, false, false, false, true),
                kind = PayloadKind.VECTOR, vector = c,
            ),
        ),
    )

    private fun assertTheCog(where: String, c: VectorContent) {
        val shape = (c.objects.first { it is VShape } as VShape).shape
        assertEquals(where, ShapeType.COG, shape.type)
        assertNull("$where: no fallback points", shape.points)
        assertFalse("$where: no flag", shape.cogFallback)
        assertEquals("$where: the hole is kept", 0.55f, shape.cogHole, 0f)
        assertEquals(where, cog, shape)
    }

    @Test
    fun throughAVecFile() {
        val written = VectorCodec.forWriting(content)
        val fallback = (written.objects[0] as VShape).shape
        assertTrue(fallback.cogFallback)
        assertNotNull(fallback.points)
        assertSame("the path is written as it is", path, written.objects[1])
        val back = VectorCodec.decode(VectorCodec.encode(content))
        assertTheCog(".vec", back)
        assertEquals(content, back)
    }

    @Test
    fun throughAnSvgOrPdfPayload() {
        val bytes = Payload.toJson(payload(content))
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("the payload carries the fallback outline", text.contains("\"cogFallback\":true"))
        val read = Payload.fromJson(bytes)
        val raw = read.layers.single().vector!!
        assertTrue("as written, before the import's checks", (raw.objects[0] as VShape).shape.cogFallback)
        val sound = PayloadImport.sound(raw)
        assertTheCog("payload", sound)
        assertEquals(content, sound)
        // The payload object itself is not changed by writing it.
        assertNull((content.objects[0] as VShape).shape.points)
    }

    @Test
    fun contentWithoutACogIsTheSameInstance() {
        val plain = VectorContent(objects = listOf(path, VShape(3, shape = ShapeObject(type = ShapeType.STAR, w = 40f, h = 40f))), nextId = 4)
        assertSame(plain, VectorCodec.sanitizedShapes(plain))
        assertSame(plain, VectorCodec.forWriting(plain))
        val decoded = VectorCodec.decode(VectorCodec.encode(plain))
        assertSame(decoded, VectorCodec.sanitizedShapes(decoded))
        assertEquals(plain, decoded)
        // A cog read back (no flag) is the same instance too.
        val back = VectorCodec.decode(VectorCodec.encode(content))
        assertSame(back, VectorCodec.sanitizedShapes(back))
        // A payload without a cog is written exactly as before.
        val p = payload(plain)
        assertArrayEquals(Payload.toJson(p), Payload.toJson(Payload.fromJson(Payload.toJson(p))))
        assertFalse(String(Payload.toJson(p), Charsets.UTF_8).contains("cog"))
    }
}
