package com.brushwork.paint.storage

import com.brushwork.paint.compat17.ArrayCodecV17
import com.brushwork.paint.compat17.ShapeCodecV17
import com.brushwork.paint.compat17.VectorCodecV17
import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.MaskCodec
import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapePoint
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * v1.8 F1 (I13 / I13b, §4.3): content without v1.8 data is written exactly as before. Every v1.6
 * golden (`v16/`) and v1.7 compatibility fixture (`qa17/compat/`) re-encodes byte for byte through
 * the v1.8 codecs: `project.json`, the shapes, masks and adjustments stored in it, and the vector
 * files (the same JSON, and the same bytes as the frozen v1.7 codec writes). v1.7 content written
 * by the frozen v1.7 codecs reads and writes again identically, and v1.8 data at its "none" value
 * (no copies, no generator, no look, an identity look) adds nothing. JVM.
 */
class CodecV18GoldenTest {
    private fun resource(path: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$path"), File("app/src/test/resources/$path")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $path")
        return file.readBytes()
    }

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

    /** [data] with the codec version at its start replaced by 0 (the one part a v1.7 codec may change). */
    private fun versionless(data: String): String = data.replaceFirst(Regex("^\\{\"version\":\\d+,"), "{\"version\":0,")

    private val projects = listOf("v16/project-plain.json", "v16/project-adjust.json", "qa17/compat/v16-plain/project.json", "qa17/compat/v16-adjust/project.json")

    private val vectorFiles = listOf(
        "v16/plain/vector_6_r1.vec", "v16/plain/vector_19_r2.vec", "v16/adjust/vector_6_r1.vec", "v16/adjust/vector_22_r2.vec",
        "qa17/compat/v16-plain/vector_19_r2.vec", "qa17/compat/v16-adjust/vector_22_r2.vec",
    )

    @Test
    fun everyProjectFileReencodesByteForByte() {
        for (p in projects) {
            val json = String(resource(p), Charsets.UTF_8)
            val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), json)
            assertEquals("$p byte for byte", json, ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto))
        }
    }

    @Test
    fun theStoredLayerDataReencodesByteForByte() {
        var shapes = 0
        var others = 0
        for (p in projects) {
            val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), String(resource(p), Charsets.UTF_8))
            for (e in dto.layers) {
                e.shapeData?.let { s ->
                    val shape = ShapeCodec.decode(s)
                    assertNotNull("$p/${e.name}: the shape decodes", shape)
                    val again = ShapeCodec.encode(shape!!)
                    assertEquals("$p/${e.name}: the shape byte for byte", versionless(s), versionless(again))
                    assertEquals("$p/${e.name}: as the frozen v1.7 codec writes it", ShapeCodecV17.encode(ShapeCodecV17.decode(s)!!), again)
                    shapes++
                }
                e.maskSpec?.let { s -> assertEquals("$p/${e.name}: the mask spec", s, MaskCodec.encode(MaskCodec.decode(s)!!)); others++ }
                e.adjustment?.let { s -> assertEquals("$p/${e.name}: the adjustment", s, AdjustmentCodec.encode(AdjustmentCodec.decode(s)!!)); others++ }
            }
        }
        assertTrue("shapes and specs were checked ($shapes, $others)", shapes >= 4 && others >= 2)
    }

    @Test
    fun everyVectorFileReencodesToTheSameJsonAndTheFrozenCodecsBytes() {
        for (f in vectorFiles) {
            val bytes = resource(f)
            val app = VectorCodec.encode(VectorCodec.decode(bytes))
            assertEquals("$f: the same JSON", inflate(bytes), inflate(app))
            assertArrayEquals("$f: the frozen v1.7 codec's bytes", VectorCodecV17.encode(VectorCodecV17.decode(bytes)), app)
        }
    }

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** v1.7 content of every kind the v1.8 changes touch: paths, shapes of every v1.7 type. */
    private fun v17Content(): VectorContent {
        val shapes = ShapeType.entries.filter { it != ShapeType.COG }.mapIndexed { i, t ->
            VShape(10L + i, 0.9f, ShapeObject(type = t, cx = 20f * i, cy = 30f, w = 60f, h = if (t.isLineLike) 0f else 40f, rotation = 10f * i), seed = i.toLong())
        }
        return VectorContent(
            objects = listOf(
                VPath(
                    1, 0.9f,
                    subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f, sharp = true), VAnchor(50f, 10f, inX = -5f, inY = 0f, outX = 5f, outY = 1f, width = 2f), VAnchor(20f, 40f)), closed = true)),
                    fill = VPaint.Linear(0f, 0f, 100f, 50f, listOf(VStop(0f, -1), VStop(1f, 0xFF000000.toInt()))),
                    stroke = VStrokeStyle(color = 0xFF00FF00.toInt(), width = 9f),
                ),
                VPath(
                    2,
                    subpaths = listOf(VSubpath(listOf(VAnchor(1f, 2f), VAnchor(3f, 4f), VAnchor(5f, 1f)))),
                    stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 2f),
                    spline = VSpline(listOf(VSplinePoint(1f, 2f), VSplinePoint(3f, 4f, weight = 2.5f), VSplinePoint(5f, 1f)), order = 3),
                ),
                VShape(3, shape = ShapeObject(type = ShapeType.STAR, w = 50f, h = 50f, points = listOf(ShapePoint(0f, -0.5f, radius = 3f), ShapePoint(0.5f, 0.5f), ShapePoint(-0.5f, 0.5f)))),
                VShape(4, shape = ShapeObject(type = ShapeType.POLYGON, sides = 9, corner = CornerStyle.INVERTED, cornerRadius = 12f)),
            ) + shapes,
            nextId = 10L + shapes.size,
        )
    }

    @Test
    fun v17ContentWrittenByTheFrozenCodecsReadsAndWritesAgainIdentically() {
        val content = v17Content()
        val v17Bytes = VectorCodecV17.encode(VectorCodecV17.decode(VectorCodec.encode(content)))
        assertArrayEquals("the app writes what v1.7 wrote", v17Bytes, VectorCodec.encode(VectorCodec.decode(v17Bytes)))
        assertEquals(content, VectorCodec.decode(v17Bytes))
        val json = inflate(v17Bytes)
        for (key in listOf("copies", "generator", "cog", "look")) assertFalse("$key is not written", json.contains("\"$key"))
        // The shape layers' data, every v1.7 type.
        for (o in content.objects.filterIsInstance<VShape>()) {
            val v17 = ShapeCodecV17.encode(ShapeCodecV17.decode(ShapeCodec.encode(o.shape))!!)
            assertEquals(o.shape.type.name, v17, ShapeCodec.encode(ShapeCodec.decode(v17)!!))
        }
    }

    @Test
    fun v18DataAtItsNoneValueAddsNothing() {
        val content = v17Content()
        val none = content.copy(objects = content.objects.map { o ->
            when (o) {
                is VPath -> o.copy(copies = CopyMaps.of(listOf(identity)), generator = null)
                is VShape -> o.copy(shape = o.shape.copy(copies = CopyMaps.NONE))
                else -> o
            }
        })
        assertArrayEquals(VectorCodec.encode(content), VectorCodec.encode(none))
        val guide = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(100f, 50f), VAnchor(200f, 0f)))
        for (spec in listOf(
            ArraySpec(),
            ArraySpec(mode = ArrayMode.CIRCLE, count = 12, centerX = 300f, centerY = 200f, sweepDeg = 270f, rotateCopies = false),
            ArraySpec(mode = ArrayMode.CURVE, count = 5, guide = guide, spacing = 40f, alignToCurve = false),
            ArraySpec(mode = ArrayMode.TRANSFORM, count = 30, moveX = 12f, moveY = -3f, turnDeg = 15f, scale = 0.9f, pivotX = 10f, pivotY = 20f, editingSource = true),
        )) {
            val v17 = ArrayCodecV17.encodeSpec(ArrayCodecV17.decodeSpec(ArrayCodec.encodeSpec(spec))!!)
            assertEquals("${spec.mode}: as v1.7 writes it", v17, ArrayCodec.encodeSpec(spec))
            assertEquals(v17, ArrayCodec.encodeSpec(spec.copy(look = ArrayLook.IDENTITY)))
            assertEquals(v17, ArrayCodec.encodeSpec(spec.copy(look = ArrayLook(easing = ArrayEasing.EASE_IN_OUT))))
            assertEquals(spec, ArrayCodec.decodeSpec(v17))
        }
    }
}
