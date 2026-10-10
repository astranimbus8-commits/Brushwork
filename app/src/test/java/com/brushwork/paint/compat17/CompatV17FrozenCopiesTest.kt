package com.brushwork.paint.compat17

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ArrowHeadStyle
import com.brushwork.paint.tools.vector.ArrowHeads
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeHandle
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapePoint
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * v1.8 F0: the frozen v1.7 codecs (`compat17`) are v1.7's. Content of every v1.7 kind, written
 * by the app's codecs (still v1.7's at 885793b4, and bound to keep v1.7 content byte for byte by
 * I13 / I13b), reads into the frozen copies and writes back byte for byte, both ways; so do the
 * v1.6 goldens. The frozen readers ignore what they do not know, as v1.7 does: the mechanism the
 * v1.8 compatibility rows (§4.3) rely on.
 */
class CompatV17FrozenCopiesTest {
    private val preset = BrushPreset(
        "calli", "Calligraphy", BrushTip.CALLIGRAPHY, size = 17.5f, minSizeRatio = 0.3f, opacity = 0.8f, flow = 0.6f,
        hardness = 0.7f, spacing = 0.1f, pressureSize = false, pressureOpacity = true, angle = 30f, roundness = 0.4f,
        scatter = 0.05f, taperStart = 12f, taperEnd = 8f, antiAlias = false, mixing = 0.25f, grain = 0.2f,
    )

    private val points = PackedPoints(floatArrayOf(1f, 2.5f, 7f, 30f), floatArrayOf(3f, 4f, 9.25f, -2f), floatArrayOf(0.5f, 0.75f, 1f, 0.1f))

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val perspective = floatArrayOf(1.1f, 0.05f, 3f, 0.02f, 0.9f, -4f, 0.0004f, 0.0002f, 1f)

    private val starWithPoints = ShapeObject(
        type = ShapeType.STAR, cx = 120f, cy = 80.5f, w = 60f, h = 44f, rotation = 33f, style = ShapeStyle.STROKE_FILL,
        strokeWidth = 6f, strokeWith = ShapeStroke.BRUSH, strokeColor = 0xFF102030.toInt(), fillColor = 0x80FF8000.toInt(),
        fillFollowsColor = false, lineCap = LineCapStyle.SQUARE, corner = CornerStyle.ROUND, cornerRadius = 3f, sides = 7,
        starPoints = 6, innerRatio = 0.3f, brushTool = ToolId.BRUSH.name, brushPreset = preset,
        points = listOf(
            ShapePoint(-0.5f, -0.5f, radius = 4f),
            ShapePoint(0.5f, -0.5f, smooth = true, handleIn = ShapeHandle(-0.1f, 0f), handleOut = ShapeHandle(0.1f, 0.02f)),
            ShapePoint(0.5f, 0.5f),
            ShapePoint(-0.5f, 0.5f, radius = 0f),
        ),
    )

    private val arrow = ShapeObject(
        type = ShapeType.ARROW, cx = 10f, cy = 20f, w = 90f, h = 0f, rotation = -45f, arrowHeads = ArrowHeads.BOTH,
        arrowHeadStyle = ArrowHeadStyle.OPEN, arrowHeadScale = 6f,
    )

    /** One of every v1.7 vector object kind and option, in the app's (v1.7) model. */
    private fun everyKind(): VectorContent = VectorContent(
        objects = listOf(
            VStroke(1, 1f, preset, 0xFF112233.toInt(), 42L, true, points, sizeScale = 1.5f, taperIn = false, copies = listOf(identity, mirror, perspective)),
            VStroke(2, 0.5f, preset, 0x7F00FF00, -7L, false, points, taperOut = false),
            VPath(
                3, 0.9f,
                subpaths = listOf(
                    VSubpath(listOf(VAnchor(0f, 0f, sharp = true), VAnchor(50f, 10f, inX = -5f, inY = 0f, outX = 5f, outY = 1f, width = 2f), VAnchor(20f, 40f, width = 0.5f)), closed = true),
                    VSubpath(listOf(VAnchor(100f, 100f), VAnchor(140f, 90f))),
                ),
                tension = 0.3f, fillRule = VFillRule.EVENODD,
                fill = VPaint.Linear(0f, 0f, 100f, 50f, listOf(VStop(0f, -1), VStop(0.4f, 0x80123456.toInt()), VStop(1f, 0xFF000000.toInt()))),
                stroke = VStrokeStyle(VStrokeKind.BRUSH, 0xFF00FF00.toInt(), 9f, LineCapStyle.SQUARE, JoinStyle.BEVEL, 6f, ToolId.SMUDGE, preset, 9L, 20f),
            ),
            VPath(
                4,
                subpaths = listOf(VSubpath(listOf(VAnchor(1f, 2f), VAnchor(3f, 4f), VAnchor(5f, 1f)))),
                fill = VPaint.Radial(50f, 50f, 25f, listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0)), matrix = listOf(1f, 0.2f, 0f, 1f, 5f, 6f)),
                stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 2f),
                spline = VSpline(
                    listOf(VSplinePoint(1f, 2f), VSplinePoint(3f, 4f, weight = 2.5f, width = 0.25f, sharp = true), VSplinePoint(5f, 1f, width = 3f)),
                    order = 5, endpoint = false,
                ),
            ),
            VPath(5, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(10f, 0f), VAnchor(10f, 10f)), closed = true)), polyline = true, fill = VPaint.Solid(0xFF336699.toInt())),
            VPath(6, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(9f, 9f)))), spline = VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(9f, 9f)), cyclic = true)),
            VShape(7, 0.75f, starWithPoints, seed = 11L),
            VShape(8, shape = arrow),
            VShape(9, shape = ShapeObject(type = ShapeType.ELLIPSE, style = ShapeStyle.FILL)),
        ),
        nextId = 10,
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

    private fun resource(path: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$path"), File("app/src/test/resources/$path")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $path")
        return file.readBytes()
    }

    @Test
    fun everyV17VectorKindRoundTripsThroughTheFrozenCodecByteForByte() {
        val app = VectorCodec.encode(everyKind())
        val frozen = VectorCodecV17.decode(app)
        assertEquals(9, frozen.objects.size)
        assertEquals(listOf("VStrokeV17", "VStrokeV17", "VPathV17", "VPathV17", "VPathV17", "VPathV17", "VShapeV17", "VShapeV17", "VShapeV17"), frozen.objects.map { it.javaClass.simpleName })
        assertEquals(3, (frozen.byId(1) as VStrokeV17).copies.size)
        assertTrue((frozen.byId(4) as VPathV17).spline!!.points[1].sharp)
        assertEquals(4f, (frozen.byId(7) as VShapeV17).shape.points!![0].radius!!, 0f)
        val again = VectorCodecV17.encode(frozen)
        assertEquals("the same JSON", inflate(app), inflate(again))
        assertArrayEquals("the same bytes", app, again)
        // And back: the app reads what the frozen codec writes, unchanged.
        assertArrayEquals("the app reads the frozen codec's file", app, VectorCodec.encode(VectorCodec.decode(again)))
        assertEquals("equal content after a frozen round trip", frozen, VectorCodecV17.decode(again))
    }

    @Test
    fun theV16GoldenVectorFilesReadTheSameInTheFrozenCodec() {
        var n = 0
        for (f in listOf("v16/plain/vector_6_r1.vec", "v16/plain/vector_19_r2.vec", "v16/adjust/vector_6_r1.vec", "v16/adjust/vector_22_r2.vec")) {
            val bytes = resource(f)
            val app = VectorCodec.encode(VectorCodec.decode(bytes))
            val frozen = VectorCodecV17.encode(VectorCodecV17.decode(bytes))
            assertEquals("$f: the same JSON", inflate(app), inflate(frozen))
            assertArrayEquals("$f: the same bytes", app, frozen)
            n++
        }
        assertEquals(4, n)
    }

    @Test
    fun shapesRoundTripThroughTheFrozenShapeCodecByteForByte() {
        val shapes = mutableListOf(starWithPoints, arrow, ShapeObject(), ShapeObject(type = ShapeType.POLYGON, sides = 9, corner = CornerStyle.INVERTED, cornerRadius = 12f))
        // The goldens' shapes too (two v1.5 QA projects, re-encoded by v1.6).
        for (name in listOf("plain", "adjust")) {
            val project = Json.parseToJsonElement(String(resource("v16/project-$name.json"), Charsets.UTF_8)).jsonObject
            for (layer in project.getValue("layers").jsonArray) {
                val data = ((layer as JsonObject)["shapeData"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                shapes += ShapeCodec.decode(data)!!
            }
        }
        assertTrue("the goldens' shapes were read (${shapes.size})", shapes.size >= 6)
        for (s in shapes) {
            val app = ShapeCodec.encode(s)
            val frozen = ShapeCodecV17.decode(app)
            assertNotNull(app, frozen)
            assertEquals("the frozen codec writes it again", app, ShapeCodecV17.encode(frozen!!))
            assertEquals("the app reads it the same", app, ShapeCodec.encode(ShapeCodec.decode(ShapeCodecV17.encode(frozen))!!))
        }
        assertEquals(2, ShapeCodecV17.VERSION)
    }

    @Test
    fun arraySpecsRoundTripThroughTheFrozenArrayCodecByteForByte() {
        val guide = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(100f, 50f, inX = -20f, inY = 0f, outX = 20f, outY = 0f), VAnchor(200f, 0f)))
        val specs = listOf(
            ArraySpec(),
            ArraySpec(mode = ArrayMode.LINE, count = 7, relativeX = 0.5f, relativeY = 1.25f, constantX = 3f, constantY = -4f),
            ArraySpec(mode = ArrayMode.CIRCLE, count = 12, centerX = 300f, centerY = 200f, sweepDeg = 270f, rotateCopies = false),
            ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = guide, spacing = 40f, alignToCurve = false),
            ArraySpec(mode = ArrayMode.TRANSFORM, count = 30, moveX = 12f, moveY = -3f, turnDeg = 15f, scale = 0.9f, pivotX = 10f, pivotY = 20f, editingSource = true),
        )
        val flip = floatArrayOf(-1f, 0f, 500f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (s in specs) {
            val app = ArrayCodec.encodeSpec(s)
            val frozen = ArrayCodecV17.decodeSpec(app)
            assertNotNull(app, frozen)
            assertEquals("the frozen codec writes it again", app, ArrayCodecV17.encodeSpec(frozen!!))
            assertEquals("the app reads it the same", s, ArrayCodec.decodeSpec(ArrayCodecV17.encodeSpec(frozen)))
            assertEquals("mapped as v1.7 maps it", ArrayCodec.encodeSpec(s.mapped(flip)), ArrayCodecV17.encodeSpec(frozen.mapped(flip)))
        }
    }

    @Test
    fun theFrozenReadersIgnoreWhatV17DoesNotKnow() {
        // An unknown shape type reads as v1.7's default (RECTANGLE); unknown fields are dropped.
        val shape = ShapeCodecV17.decode("""{"version":3,"shape":{"type":"SOMETHING_NEW","cx":5.0,"w":40.0,"h":30.0,"newField":{"a":1},"copies":[[1,0,0,0,1,0,0,0,1]]}}""")
        assertNotNull(shape)
        assertEquals(ShapeTypeV17.RECTANGLE, shape!!.type)
        assertEquals(40f, shape.w, 0f)
        // A path with fields v1.7 does not have reads as the plain path.
        val json = """{"version":1,"objects":[{"type":"path","id":3,"subpaths":[{"anchors":[{"x":1.0,"y":2.0},{"x":3.0,"y":4.0}]}],""" +
            """"copies":[[1,0,0,0,1,0,0,0,1]],"generator":{"kind":"spiral","turns":3}}],"nextId":4}"""
        val content = VectorCodecV17.decode(deflate(json))
        val path = content.byId(3) as VPathV17
        assertEquals(2, path.subpaths.single().anchors.size)
        assertNull(path.spline)
        // An array spec with a field v1.7 does not have reads as the plain spec.
        val spec = ArrayCodecV17.decodeSpec("""{"mode":"CIRCLE","count":5,"look":{"tint":-1,"strength":0.5}}""")
        assertEquals(ArrayModeV17.CIRCLE, spec!!.mode)
        assertEquals(5, spec.count)
        // Damaged data fails as v1.7 fails.
        assertNull(ShapeCodecV17.decode("{not json"))
        assertNull(ArrayCodecV17.decodeSpec("[]"))
        try {
            VectorCodecV17.decode(byteArrayOf(1, 2, 3))
            throw AssertionError("garbage was read")
        } catch (e: CorruptVectorExceptionV17) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }
}
