package com.brushwork.paint.vector

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.PackedPointsSerializer
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.CurvePoint
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.GradientStop
import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.FilterValuesCodec
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskCodec
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskStroke
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.random.Random

/** v1.5 foundation (§5.10 items 2, 5): the pure data models and their codecs (JVM). */
class V15ModelCodecTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun randomPoints(n: Int, seed: Int): PackedPoints {
        val r = Random(seed)
        // Any finite float, including tiny / huge magnitudes and negative zero.
        fun f(): Float = when (r.nextInt(6)) {
            0 -> r.nextFloat() * 4000f - 1000f
            1 -> Float.fromBits(r.nextInt()).takeIf { it.isFinite() } ?: 1.5f
            2 -> -0f
            3 -> Float.MIN_VALUE
            else -> r.nextFloat()
        }
        return PackedPoints(FloatArray(n) { f() }, FloatArray(n) { f() }, FloatArray(n) { r.nextFloat() })
    }

    // ------------------------------------------------------------------ PackedPoints

    @Test
    fun packedPointsJsonRoundTripIsExact() {
        for (seed in 1..20) {
            val p = randomPoints(seed * 37, seed)
            val text = json.encodeToString(PackedPointsSerializer, p)
            val back = json.decodeFromString(PackedPointsSerializer, text)
            assertEquals(p, back)
            assertEquals(p.hashCode(), back.hashCode())
            for (i in 0 until p.size) {
                assertEquals(p.x[i].toRawBits(), back.x[i].toRawBits())
                assertEquals(p.y[i].toRawBits(), back.y[i].toRawBits())
                assertEquals(p.p[i].toRawBits(), back.p[i].toRawBits())
            }
        }
        val empty = json.decodeFromString(PackedPointsSerializer, json.encodeToString(PackedPointsSerializer, PackedPoints.EMPTY))
        assertEquals(0, empty.size)
    }

    @Test
    fun packedPointsJsonIsCountAndLittleEndianFloat32Base64() {
        val p = PackedPoints(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), floatArrayOf(0.5f, 1f))
        val obj = json.parseToJsonElement(json.encodeToString(PackedPointsSerializer, p)).jsonObject
        assertEquals(2, obj.getValue("n").jsonPrimitive.int)
        val bytes = Base64.getDecoder().decode(obj.getValue("d").jsonPrimitive.content)
        assertEquals(24, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 0.5f, 1f), FloatArray(6) { buf.float }, 0f)
    }

    @Test
    fun packedPointsRejectsDamagedData() {
        for (bad in listOf("""{"n":3,"d":"AAAA"}""", """{"n":-1,"d":""}""", """{"n":1,"d":"%%%"}""")) {
            try {
                json.decodeFromString(PackedPointsSerializer, bad)
                fail("accepted $bad")
            } catch (e: Exception) {
                // expected: a damaged stroke never becomes a wrong one
            }
        }
        try {
            PackedPoints(FloatArray(2), FloatArray(3), FloatArray(2))
            fail("arrays of different sizes accepted")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun packedPointsMapAndSlice() {
        val p = PackedPoints(floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 5f, 10f), floatArrayOf(0.1f, 0.2f, 0.3f))
        val moved = p.mapped(floatArrayOf(2f, 0f, 1f, 0f, 3f, -1f, 0f, 0f, 1f))
        assertArrayEquals(floatArrayOf(1f, 21f, 41f), moved.x, 0f)
        assertArrayEquals(floatArrayOf(-1f, 14f, 29f), moved.y, 0f)
        assertArrayEquals(p.p, moved.p, 0f)
        // A homography divides by the third row.
        val persp = p.mapped(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.1f, 0f, 1f))
        assertEquals(10f / 2f, persp.x[1], 1e-5f)
        assertEquals(5f / 2f, persp.y[1], 1e-5f)
        val s = p.slice(1, 3)
        assertArrayEquals(floatArrayOf(10f, 20f), s.x, 0f)
        assertEquals(0, p.slice(5, 9).size)
        assertNotEquals(p, s)
    }

    // ------------------------------------------------------------------ vector content

    private fun sampleContent(): VectorContent {
        val pen = BrushLibrary.defaultBrush
        val stroke = VStroke(0, 0.8f, pen, 0xFF112233.toInt(), 1234L, stylus = true, points = randomPoints(50, 7), sizeScale = 1.5f, taperOut = false)
        val path = VPath(
            0,
            subpaths = listOf(
                VSubpath(listOf(VAnchor(0f, 0f), VAnchor(10f, 0f, sharp = true, outX = 3f, outY = 4f, width = 2.5f), VAnchor(10f, 10f)), closed = true),
                VSubpath(listOf(VAnchor(20f, 20f), VAnchor(30f, 25f))),
            ),
            tension = 0.25f,
            fillRule = VFillRule.EVENODD,
            fill = VPaint.Radial(5f, 5f, 8f, listOf(VStop(0f, -1), VStop(1f, 0xFF000000.toInt())), matrix = listOf(1f, 0f, 0f, 2f, 0f, 0f)),
            stroke = VStrokeStyle(VStrokeKind.BRUSH, 0xFFFF0000.toInt(), 6f, LineCapStyle.SQUARE, JoinStyle.MITER, 7f, ToolId.BRUSH, pen, 99L, 20f),
        )
        val linear = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(1f, 1f), VAnchor(2f, 2f)))), fill = VPaint.Linear(0f, 0f, 1f, 1f, listOf(VStop(0.5f, 7))))
        val solid = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(1f, 1f), VAnchor(2f, 2f)))), fill = VPaint.Solid(0x8000FF00.toInt()), polyline = true)
        val shape = VShape(0, 0.5f, ShapeObject(type = ShapeType.STAR, cx = 50f, cy = 40f, w = 30f, h = 20f, rotation = 15f), seed = 5L)
        return VectorContent.EMPTY.plus(listOf(stroke, path, linear, solid, shape)).first
    }

    @Test
    fun vectorCodecRoundTripsEveryObjectKind() {
        val c = sampleContent()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), c.objects.map { it.id })
        assertEquals(6L, c.nextId)
        val bytes = VectorCodec.encode(c)
        val back = VectorCodec.decode(bytes)
        assertEquals(c, back)
        assertTrue(back.objects[0] is VStroke && back.objects[1] is VPath && back.objects[4] is VShape)
        assertTrue("Deflate keeps it smaller than the JSON", bytes.size < Json.encodeToString(VectorContent.serializer(), c).length)
        assertEquals(VectorContent.EMPTY, VectorCodec.decode(VectorCodec.encode(VectorContent.EMPTY)))
    }

    @Test
    fun vectorCodecRejectsGarbageAndTruncation() {
        val bytes = VectorCodec.encode(sampleContent())
        val cases = listOf(ByteArray(0), byteArrayOf(1, 2, 3, 4), bytes.copyOf(bytes.size / 2), Random(3).nextBytes(500))
        for (b in cases) {
            try {
                VectorCodec.decode(b)
                fail("decoded garbage of ${b.size} bytes")
            } catch (e: CorruptVectorException) {
                // expected
            }
        }
    }

    @Test
    fun vectorContentEditsKeepIdsStable() {
        val c = sampleContent()
        assertSame(c.objects[2], c.byId(3))
        assertEquals(2, c.indexOf(3))
        assertEquals(-1, c.indexOf(42))
        val without = c.without(setOf(2L, 4L))
        assertEquals(listOf(1L, 3L, 5L), without.objects.map { it.id })
        assertSame(c, c.without(setOf(77L)))
        // Replaced in place by 0..n pieces: the first keeps the id, the others get new ones.
        val piece = c.byId(1)!!
        val r = c.replaced(mapOf(1L to listOf(piece, piece), 3L to emptyList()))
        assertEquals(listOf(1L, 6L, 2L, 4L, 5L), r.objects.map { it.id })
        assertEquals(7L, r.nextId)
        val (more, ids) = r.plus(listOf(piece))
        assertEquals(listOf(7L), ids)
        assertEquals(7L, more.objects.last().id)
        assertTrue(c.approxBytes() > 50 * 12)
    }

    // ------------------------------------------------------------------ masks / adjustments

    @Test
    fun maskAndAdjustmentCodecsRoundTrip() {
        val spec = MaskSpec(
            startFull = true,
            components = listOf(
                LinearMask(1, MaskMode.SUBTRACT, invert = true, amount = 0.5f, x0 = 1f, y0 = 2f, x1 = 30f, y1 = 40f),
                RadialMask(2, MaskMode.INTERSECT, cx = 10f, cy = 10f, rx = 5f, ry = 8f, rotationDeg = 30f, feather = 0.2f, visible = false),
                BrushMask(3, strokes = listOf(MaskStroke(erase = true, size = 40f, hardness = 0.3f, flow = 0.7f, points = randomPoints(10, 2)))),
            ),
            invert = true,
            density = 0.75f,
            nextId = 4,
        )
        assertEquals(spec, MaskCodec.decode(MaskCodec.encode(spec)))
        val adj = AdjustmentSpec(filterId = "adjust.levels", values = JsonObject(mapOf("gamma" to JsonPrimitive(1.5f))))
        assertEquals(adj, AdjustmentCodec.decode(AdjustmentCodec.encode(adj)))
        assertEquals("adjust.tone", AdjustmentSpec().filterId)
        assertNull(MaskCodec.decode("not json"))
        assertNull(MaskCodec.decode("""{"components":[{"type":"spiral","id":1}]}"""))
        assertNull(AdjustmentCodec.decode("[]"))
        assertEquals("missing fields take their defaults", MaskSpec(), MaskCodec.decode("{}"))
    }

    /** A filter with one parameter of every type. */
    private class AllParams : Filter("test.all", "All", FilterCategory.ADJUST) {
        override val params: List<FilterParam> = listOf(
            FilterParam.Slider("s", "Slider", -5f, 5f, 0.25f, 0.01f),
            FilterParam.Toggle("t", "Toggle", false),
            FilterParam.Choice("c", "Choice", listOf("a", "b", "c"), 1),
            FilterParam.Color("col", "Color", 0xFF102030.toInt()),
            FilterParam.Point("p", "Point", 0.25f, 0.75f),
            FilterParam.Curve("cv", "Curve"),
            FilterParam.Gradient("g", "Gradient", listOf(GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, -1))),
            FilterParam.Text("tx", "Text", "hello"),
            FilterParam.Seed(),
        )

        override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext) = src.copy()
    }

    private fun assertSameValues(expected: FilterValues, actual: FilterValues, f: Filter) {
        for (p in f.params) {
            val a = expected.raw(p.key)
            val b = actual.raw(p.key)
            if (a is FloatArray) assertArrayEquals(p.key, a, b as FloatArray, 0f) else assertEquals(p.key, a, b)
        }
    }

    @Test
    fun filterValuesCodecHandlesEveryParamType() {
        val f = AllParams()
        val v = f.defaultValues()
            .set("s", -3.75f).set("t", true).set("c", 2).set("col", 0x80FF8800.toInt())
            .set("p", floatArrayOf(0.1f, 0.9f))
            .set("cv", listOf(CurvePoint(0f, 0.1f), CurvePoint(0.4f, 0.7f), CurvePoint(1f, 0.9f)))
            .set("g", listOf(GradientStop(0f, 0xFFFF0000.toInt()), GradientStop(0.3f, 0xFF00FF00.toInt()), GradientStop(1f, 0xFF0000FF.toInt())))
            .set("tx", "Brushwork").set("seed", 42)
        val j = FilterValuesCodec.toJson(f, v)
        // Through a string too, as stored in project.json.
        val text = Json.encodeToString(JsonObject.serializer(), j)
        val back = FilterValuesCodec.fromJson(f, Json.decodeFromString(JsonObject.serializer(), text))
        assertSameValues(v, back, f)
        // Defaults survive a round trip; unknown keys are ignored, missing or unreadable ones default.
        assertSameValues(f.defaultValues(), FilterValuesCodec.fromJson(f, FilterValuesCodec.toJson(f, f.defaultValues())), f)
        val partial = JsonObject(mapOf("s" to JsonPrimitive(1.5f), "nope" to JsonPrimitive(3), "t" to JsonPrimitive("yes"), "cv" to JsonPrimitive(1)))
        val p = FilterValuesCodec.fromJson(f, partial)
        assertEquals(1.5f, p.float("s"), 0f)
        assertFalse(p.bool("t"))
        assertEquals(f.defaultValues().curve("cv"), p.curve("cv"))
        assertEquals(1, p.choice("c"))
    }

    // ------------------------------------------------------------------ LayerData

    @Test
    fun layerDataRasterizeRules() {
        val d = LayerData(text = "t", shape = "s", vector = VectorContent.EMPTY, maskSpec = MaskSpec(), adjustment = AdjustmentSpec())
        assertFalse(d.isEmpty)
        assertTrue(LayerData.NONE.isEmpty)
        val content = d.rasterizedContent()
        assertNull(content.text); assertNull(content.shape); assertNull(content.vector)
        assertEquals("a CONTENT edit keeps the mask spec", d.maskSpec, content.maskSpec)
        assertEquals("an adjustment is never cleared", d.adjustment, content.adjustment)
        val mask = d.rasterizedMask()
        assertNull(mask.maskSpec)
        assertEquals(d.copy(maskSpec = null), mask)
        assertTrue(d.approxBytes() > LayerData.NONE.approxBytes())
    }
}
