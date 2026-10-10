package com.brushwork.paint.compat17

import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeGeometry
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * v1.8 F1 (§4.3, "v1.7 reading a v1.8 file"): the frozen v1.7 decoders (`compat17`, v1.7's own
 * code) read v1.8 files as the compatibility rows say. A curve's copies and a generated path's
 * generator are dropped (the plain path is read); a cog, with or without a hole, reads as a
 * RECTANGLE drawing its fallback outline; a shape's copies are dropped; an array's look is
 * dropped (plain copies). In each case v1.7 writes back exactly what v1.8 writes for the same
 * content without the v1.8 data. JVM.
 */
class CompatV17DecodeTest {
    private val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val quarter = floatArrayOf(0f, -1f, 150f, 1f, 0f, -50f, 0f, 0f, 1f)

    private val curve = VPath(
        1,
        subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(50f, 10f, inX = -5f, inY = 0f, outX = 5f, outY = 1f), VAnchor(20f, 40f)))),
        stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
    )
    private val generator = CurveGenerator(kind = CurveGenKind.GOLDEN, cx = 100f, cy = 100f, size = 80f, turns = 2.5f, clockwise = true)

    private val cog = ShapeObject(type = ShapeType.COG, cx = 100f, cy = 80f, w = 120f, h = 120f, cogTeeth = 14, cogDepth = 0.3f, style = ShapeStyle.FILL)
    private val holed = cog.copy(cogHole = 0.45f)

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

    /** v1.7 reads [c] as v1.8 writes it, and writes back what v1.8 writes for [plain]. */
    private fun assertV17Reads(c: VectorContent, plain: VectorContent): VectorContentV17 {
        val v18 = VectorCodec.encode(c)
        val v17 = VectorCodecV17.decode(v18)
        assertEquals(inflate(VectorCodec.encode(plain)), VectorCodecV17.encodeJson(v17))
        return v17
    }

    @Test
    fun aCurveWithCopiesReadsAsThePlainCurve() {
        val withCopies = curve.copy(copies = CopyMaps.of(listOf(mirror, quarter)))
        val v17 = assertV17Reads(VectorContent(objects = listOf(withCopies), nextId = 2), VectorContent(objects = listOf(curve), nextId = 2))
        val path = v17.byId(1) as VPathV17
        assertEquals(3, path.subpaths.single().anchors.size)
        assertEquals(50f, path.subpaths.single().anchors[1].x, 0f)
    }

    @Test
    fun aGeneratedPathReadsAsThePlainPath() {
        val generated = curve.copy(generator = generator)
        assertV17Reads(VectorContent(objects = listOf(generated), nextId = 2), VectorContent(objects = listOf(curve), nextId = 2))
        // With copies too.
        val both = generated.copy(copies = CopyMaps.of(listOf(mirror)))
        assertV17Reads(VectorContent(objects = listOf(both), nextId = 2), VectorContent(objects = listOf(curve), nextId = 2))
    }

    @Test
    fun aCogOnAVectorLayerReadsAsARectangleDrawingItsOutline() {
        for (c in listOf(cog, holed)) {
            val v18 = VectorCodec.encode(VectorContent(objects = listOf(VShape(1, 0.8f, c, seed = 5L)), nextId = 2))
            val v17 = VectorCodecV17.decode(v18)
            val shape = (v17.byId(1) as VShapeV17).shape
            assertEquals(ShapeTypeV17.RECTANGLE, shape.type)
            assertFallbackOutline(c, shape)
            // v1.7 writes it back as the rectangle with those points (the cog fields are gone).
            val again = VectorCodecV17.encodeJson(v17)
            assertFalse(again, again.contains("cog"))
            val reread = VectorCodec.decode(VectorCodecV17.encode(v17)).objects.single() as VShape
            assertEquals(ShapeType.RECTANGLE, reread.shape.type)
            assertEquals(shape.points!!.size, reread.shape.points!!.size)
        }
    }

    @Test
    fun aCogOnAShapeLayerReadsAsARectangleDrawingItsOutline() {
        for (c in listOf(cog, holed)) {
            val v17 = ShapeCodecV17.decode(ShapeCodec.encode(c))
            assertNotNull(v17)
            assertEquals(ShapeTypeV17.RECTANGLE, v17!!.type)
            assertFallbackOutline(c, v17)
            assertEquals(ShapeStyleV17.FILL, v17.style)
        }
    }

    private fun assertFallbackOutline(c: ShapeObject, v17: ShapeObjectV17) {
        val expected = ShapeGeometry.vertices(ShapeType.COG, 1f, 1f, c.outlineParams)
        val pts = v17.points
        assertNotNull("v1.7 has the outline's points", pts)
        assertEquals(expected.size, pts!!.size)
        for (i in expected.indices) {
            assertEquals(expected[i].x, pts[i].x, 0f)
            assertEquals(expected[i].y, pts[i].y, 0f)
        }
        assertEquals(c.cx, v17.cx, 0f)
        assertEquals(c.cy, v17.cy, 0f)
        assertEquals(c.w, v17.w, 0f)
        assertEquals(c.h, v17.h, 0f)
    }

    @Test
    fun aShapesCopiesAreDropped() {
        val star = ShapeObject(type = ShapeType.STAR, cx = 50f, cy = 60f, w = 40f, h = 40f, starPoints = 6)
        val withCopies = star.copy(copies = CopyMaps.of(listOf(mirror, quarter)))
        val v17 = ShapeCodecV17.decode(ShapeCodec.encode(withCopies))!!
        assertEquals(ShapeTypeV17.STAR, v17.type)
        assertEquals(ShapeCodec.encode(star), ShapeCodecV17.encode(v17))
        // On a vector layer too.
        val v = VectorContent(objects = listOf(VShape(1, shape = withCopies)), nextId = 2)
        assertV17Reads(v, VectorContent(objects = listOf(VShape(1, shape = star)), nextId = 2))
    }

    @Test
    fun anArraysLookIsDropped() {
        val look = ArrayLook(
            opacityStart = 1f, opacityEnd = 0.3f, sizeStart = 1f, sizeEnd = 0.4f, turnEnd = 180f, easing = ArrayEasing.EASE_OUT,
            tint = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0xFF0000FF.toInt())), tintStrength = 0.7f,
        )
        val guide = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(100f, 50f), VAnchor(200f, 0f)))
        for (spec in listOf(
            ArraySpec(count = 5),
            ArraySpec(mode = ArrayMode.CIRCLE, count = 12, centerX = 300f, centerY = 200f),
            ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = guide),
            ArraySpec(mode = ArrayMode.TRANSFORM, count = 30, moveX = 12f, turnDeg = 15f, scale = 0.9f),
        )) {
            val withLook = spec.copy(look = look)
            val text = ArrayCodec.encodeSpec(withLook)
            assertTrue(text, text.contains("\"look\""))
            val v17 = ArrayCodecV17.decodeSpec(text)
            assertNotNull(text, v17)
            assertEquals("plain copies", ArrayCodecV17.decodeSpec(ArrayCodec.encodeSpec(spec)), v17)
            assertEquals(ArrayCodecV17.decodeSpec(ArrayCodec.encodeSpec(withLook.copy(look = null))), ArrayCodecV17.decodeSpec(ArrayCodec.encodeSpec(withLook)))
            assertEquals(ArrayCodec.encodeSpec(spec), ArrayCodecV17.encodeSpec(v17!!))
            assertNull(ArrayCodec.decodeSpec(ArrayCodecV17.encodeSpec(v17))!!.look)
        }
    }

    @Test
    fun withoutV18DataTheFrozenCodecsWriteTheSameBytes() {
        val content = VectorContent(objects = listOf(curve, VShape(2, shape = ShapeObject(type = ShapeType.STAR, w = 30f, h = 30f))), nextId = 3)
        val v18 = VectorCodec.encode(content)
        assertArrayEquals(v18, VectorCodecV17.encode(VectorCodecV17.decode(v18)))
    }
}
