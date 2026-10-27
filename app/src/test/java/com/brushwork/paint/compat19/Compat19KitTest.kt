package com.brushwork.paint.compat19

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.VAnchor
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.Inflater

/**
 * v1.10 G2 (C1): the frozen v1.9 kit (`compat19`) is v1.9's. Every v1.9 fixture of
 * `app/src/test/resources/qa19/compat` (written by v1.9's own repository) and every older project
 * fixture reads into the frozen copies and writes back exactly as the app's codecs read and write
 * it; what v1.9 wrote writes back to its own bytes: `project.json`, the `.vec` JSON, the shape
 * data and the pixel files (BWLY v1, which the kit reads as v1.9 does and encodes as the app
 * encodes them). Data the app builds of every v1.9 kind (vector objects, cogs, copies, array
 * specs) writes as the app writes it. The loader refuses format 4 with v1.9's words and opens
 * formats 1 to 3; the cap is v1.9's at 512 MiB and agrees with the app's
 * `ProjectFormat.v19Cap`; the frozen readers drop what v1.9 does not know. Robolectric for the
 * app's pixel-file codec only.
 */
@RunWith(RobolectricTestRunner::class)
class Compat19KitTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val v19Written = "qa19/compat"

    /** The v1.9 fixtures that hold a `project.json` (folders written by v1.9's repository). */
    private val v19Projects = listOf(
        "backup-folder", "banded/banded", "banded/whole", "bevel-curves", "bevel-shapes", "curves-mixed", "cut-pieces",
        "duplicates", "layers-merged", "new-canvas", "new-canvas/picture", "new-canvas/transparent", "paper", "shape-r17",
        "shapes-joined", "text-arrange", "text-array-lift", "text-justify", "text-kerning", "text-list", "text-path-nodes",
        "text-several", "text-transform",
    ).map { "$v19Written/$it" }

    /** Older projects v1.9 opens (compat18's fixtures). */
    private val olderProjects = listOf(
        "v16/project-plain.json", "v16/project-adjust.json", "qa16/v15/v15-plain/project.json", "qa16/v15/v15-adjust/project.json",
        "qa17/compat/v16-plain/project.json", "qa17/compat/v16-adjust/project.json", "qa18/compat/v17-compat/project.json",
        "qa18/compat/v17-edited/project.json", "qa18/compat/v17-untouched/project.json", "qa18/compat/v18-written/project.json",
    )

    private val v19Vectors = listOf(
        "bevel-curves/vector_2_r1.vec", "bevel-shapes/vector_2_r1.vec", "curves-mixed/vector_2_r1.vec", "cut-pieces/vector_1_r1.vec",
        "cut-pieces/vector_2_r1.vec", "duplicates/vector_3_r2.vec", "layers-merged/vector_2_r1.vec", "layers-merged/vector_4_r1.vec",
        "shape-r17/vector_2_r1.vec", "shapes-joined/vector_2_r1.vec",
    ).map { "$v19Written/$it" }

    private val olderVectors = listOf("vector_9_r1.vec", "vector_11_r1.vec", "vector_12_r1.vec", "vector_13_r1.vec").map { "qa18/compat/v18-written/$it" }

    private val isWindows: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun resource(path: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$path"), File("app/src/test/resources/$path")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $path")
        return file.readBytes()
    }

    private fun text(path: String) = String(resource(path), Charsets.UTF_8)

    private fun projectPath(p: String) = if (p.endsWith(".json")) p else "$p/project.json"

    private fun project(path: String): ProjectFileDto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), text(projectPath(path)))

    private fun inflate(b: ByteArray): String {
        val inf = Inflater()
        try {
            inf.setInput(b)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) throw AssertionError("truncated deflate data")
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            inf.end()
        }
    }

    private fun appProject(json: String) = ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), json))

    private fun frozenProject(json: String) = ProjectFormatV19.encode(ProjectFormatV19.decode(json))

    private fun appShape(data: String) = ShapeCodec.decode(data)?.let { ShapeCodec.encode(it) }

    private fun frozenShape(data: String) = ShapeCodecV19.decode(data)?.let { ShapeCodecV19.encode(it) }

    private fun appVector(b: ByteArray) = inflate(VectorCodec.encode(VectorCodec.decode(b)))

    private fun frozenVector(b: ByteArray) = VectorCodecV19.encodeJson(VectorCodecV19.decode(b))

    @Test
    fun projectFixturesReadAndWriteAsTheAppDoesAndAsV19WroteThem() {
        for (path in olderProjects + v19Projects) {
            val json = text(projectPath(path))
            assertEquals("$path: v1.9 reads and writes project.json as the app does", appProject(json), frozenProject(json))
        }
        for (path in v19Projects) {
            val own = text(projectPath(path))
            assertEquals("$path: v1.9 writes back its own project.json", own, frozenProject(own))
            val dto = ProjectFormatV19.decode(own)
            assertEquals("$path: written as the version v1.9 writes for its layers", ProjectFormatV19.writtenVersion(dto.layers), dto.formatVersion)
        }
        assertNotNull("the paper fixture keeps its paper in v1.9", ProjectFormatV19.decode(text("$v19Written/paper/project.json")).paper)
    }

    @Test
    fun shapeFixturesReadAndWriteAsTheAppDoesAndAsV19WroteThem() {
        var n = 0
        for (path in olderProjects + v19Projects) {
            for (l in project(path).layers) {
                val data = l.shapeData ?: continue
                val app = appShape(data)
                assertNotNull("$path ${l.name}: the app reads it", app)
                assertEquals("$path ${l.name}: v1.9 reads and writes it as the app does", app, frozenShape(data))
                if (path.startsWith(v19Written)) assertEquals("$path ${l.name}: v1.9 writes back its own bytes", data, frozenShape(data))
                n++
            }
        }
        assertTrue("shape fixtures found: $n", n >= 15)
    }

    @Test
    fun vectorFixturesReadAndWriteAsTheAppDoesAndAsV19WroteThem() {
        for (f in olderVectors + v19Vectors) {
            val b = resource(f)
            assertEquals("$f: v1.9 reads and writes it as the app does", appVector(b), frozenVector(b))
        }
        for (f in v19Vectors) {
            val b = resource(f)
            assertEquals("$f: v1.9 writes back its own JSON", inflate(b), frozenVector(b))
            assertEquals("$f: v1.9's deflate of it inflates to the same JSON", inflate(b), inflate(VectorCodecV19.encode(VectorCodecV19.decode(b))))
        }
    }

    @Test
    fun everyV19VectorKindReadsAndWritesAsTheAppDoes() {
        val preset = BrushPreset("calli", "Calligraphy", BrushTip.CALLIGRAPHY, size = 17.5f, opacity = 0.8f, antiAlias = false, grain = 0.2f)
        val points = PackedPoints(floatArrayOf(1f, 2.5f, 7f), floatArrayOf(3f, 4f, 9.25f), floatArrayOf(0.5f, 0.75f, 1f))
        val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val content = VectorContent(
            objects = listOf(
                VStroke(1, 0.9f, preset, 0xFF112233.toInt(), 7L, true, points, copies = listOf(identity, mirror)),
                VPath(
                    2, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(50f, 10f, outX = 60f, outY = 20f, width = 2f)), closed = true)),
                    fill = VPaint.Linear(0f, 0f, 50f, 10f, listOf(VStop(0f, 0xFF00FF00.toInt()), VStop(1f, 0xFF0000FF.toInt()))),
                    stroke = VStrokeStyle(VStrokeKind.BRUSH, 0xFF000000.toInt(), 4f, brushTool = ToolId.MAGNETIC_LASSO, brush = preset),
                    copies = CopyMaps.of(listOf(mirror)),
                ),
                VPath(
                    3, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(10f, 10f), VAnchor(20f, 0f)))),
                    spline = VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(10f, 10f, weight = 2f, sharp = true), VSplinePoint(20f, 0f, sharp = true)), order = 3),
                    generator = CurveGenerator(CurveGenKind.ROSE, cx = 10f, cy = 20f, petalsN = 7, petalsD = 3, density = 12),
                ),
                VShape(4, 0.5f, ShapeObject(type = ShapeType.COG, w = 90f, h = 90f, cogHole = 0.3f, copies = CopyMaps.of(listOf(mirror))), seed = 9L),
                VShape(4, shape = ShapeObject(type = ShapeType.POLYGON, sides = 7, w = 30f, h = 30f)),
            ),
            nextId = 3,
        )
        val b = VectorCodec.encode(content)
        assertEquals("v1.9 reads and writes every v1.9 kind as the app does", appVector(b), frozenVector(b))
        val back = VectorCodecV19.decode(b)
        assertEquals("ids made unique as v1.9 does", listOf(1L, 2L, 3L, 4L, 5L), back.objects.map { it.id })
        assertEquals("the stroke's points read bit for bit by the frozen PackedPointsV19", PackedPointsV19(points.x, points.y, points.p), (back.objects[0] as VStrokeV19).points)
        assertEquals("the path's live copies kept", 2, (back.objects[1] as VPathV19).copies.maps.size)
        assertEquals("the generator kept", CurveGenKindV19.ROSE, (back.objects[2] as VPathV19).generator?.kind)
        assertNull("the cog read back without its fallback points", (back.objects[3] as VShapeV19).shape.points)
    }

    @Test
    fun cogsAndCopiesOfEveryShapeWriteAsTheAppWritesThem() {
        val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
        val turn = floatArrayOf(0f, -1f, 300f, 1f, 0f, -10f, 0f, 0f, 1f)
        val shapes = listOf(
            ShapeObject(type = ShapeType.COG, cx = 50f, cy = 60f, w = 80f, h = 70f),
            ShapeObject(type = ShapeType.COG, w = 100f, h = 100f, cogTeeth = 3, cogDepth = 0.5f, cogToothWidth = 0.2f, cogTaper = 0f, cogHole = 0.8f),
            ShapeObject(type = ShapeType.COG, w = 100f, h = 40f, cogTeeth = 64, cogDepth = 0.05f, cogToothWidth = 0.8f, cogTaper = 0.9f, corner = CornerStyle.ROUND, cornerRadius = 4f),
            ShapeObject(type = ShapeType.COG, w = 10f, h = 10f, cogTeeth = 200, cogDepth = Float.NaN, cogToothWidth = 2f, cogTaper = -1f, cogHole = Float.POSITIVE_INFINITY),
            ShapeObject(type = ShapeType.STAR, w = 60f, h = 60f, style = ShapeStyle.STROKE_FILL, copies = CopyMaps.of(listOf(mirror, turn))),
            ShapeObject(type = ShapeType.COG, w = 60f, h = 60f, copies = CopyMaps.of(listOf(mirror))),
            ShapeObject(type = ShapeType.ARROW, w = 120f, h = 0f, rotation = 400f),
        )
        for ((i, s) in shapes.withIndex()) {
            val data = ShapeCodec.encode(s)
            assertEquals("shape $i: v1.9 reads and writes it as the app does", appShape(data), frozenShape(data))
            assertEquals("shape $i: v1.9's write is the app's", data, frozenShape(data))
        }
    }

    /** Every pixel file of the v1.9 fixtures with its size: (fixture path, width, height). */
    private fun v19PixelFiles(): List<Triple<String, Int, Int>> = v19Projects.flatMap { path ->
        val dto = ProjectFormatV19.decode(text(projectPath(path)))
        dto.layers.flatMap { e ->
            val names = ArrayList<String>()
            if (e.folderSpec(dto.formatVersion) == null && e.contentFileName.isNotEmpty()) names += e.contentFileName
            if (e.hasMask) names += e.maskFileName
            names.map { Triple("$path/$it", dto.width, dto.height) }
        }
    }.filter { (f, _, _) -> javaClass.classLoader?.getResource(f) != null || File("src/test/resources/$f").isFile || File("app/src/test/resources/$f").isFile }

    @Test
    fun pixelFilesReadAsTheAppReadsThemAndWriteAsV19AndTheAppWriteThem() {
        val files = v19PixelFiles()
        assertTrue("pixel files found: ${files.size}", files.size >= 30)
        assertTrue("masks among them", files.any { it.first.substringAfterLast('/').startsWith("mask_") })
        for ((f, w, h) in files) {
            val b = resource(f)
            val pixels = LayerCodecV19.decode(b, f, w, h)
            val file = tmp.newFile()
            file.writeBytes(b)
            val app = ByteArray(LayerCodec.byteLength(w, h))
            LayerCodec.read(file, w, h, app)
            assertArrayEquals("$f: v1.9 reads the pixels the app reads", app, pixels)
            val out = File(tmp.root, "app_${file.name}")
            LayerCodec.write(out, w, h, pixels)
            assertArrayEquals("$f: v1.9 encodes the pixels as the app writes them", out.readBytes(), LayerCodecV19.encode(w, h, pixels))
            assertEquals("$f: the header v1.9 checks", Triple(w, h, w * h * 4), LayerCodecV19.header(b, f))
            // The deflate stream depends on the zlib build; the fixtures were written on Windows.
            if (isWindows) assertArrayEquals("$f: v1.9 writes back its own bytes", b, LayerCodecV19.encode(w, h, pixels))
        }
        val whole = files.filter { it.first.startsWith("$v19Written/banded/whole/") }
        assertTrue("the banded fixture's files", whole.size >= 5)
        for ((f, w, h) in whole) {
            val banded = f.replace("/banded/whole/", "/banded/banded/")
            assertArrayEquals("$f: the banded save holds the same pixels", LayerCodecV19.decode(resource(f), f, w, h), LayerCodecV19.decode(resource(banded), banded, w, h))
        }
    }

    @Test
    fun damagedPixelFilesAreRefusedAsV19RefusesThem() {
        val pixels = ByteArray(8 * 4 * 4) { (it * 7).toByte() }
        val good = LayerCodecV19.encode(8, 4, pixels)
        assertArrayEquals("the round trip", pixels, LayerCodecV19.decode(good, "g", 8, 4))
        fun refused(b: ByteArray, w: Int, h: Int): String = try {
            LayerCodecV19.decode(b, "f.bin", w, h)
            fail("v1.9 reads it")
            ""
        } catch (e: CorruptProjectExceptionV19) {
            e.message.orEmpty()
        }
        assertEquals("f.bin is truncated", refused(good.copyOf(good.size - 3), 8, 4))
        assertEquals("f.bin is 8x4, expected 4x8", refused(good, 4, 8))
        assertEquals("f.bin is not a layer file", refused(ByteArray(40), 8, 4))
        assertEquals("f.bin has unsupported version 2", refused(good.copyOf().also { it[7] = 2 }, 8, 4))
        val file = File(tmp.root, "missing.bin")
        try {
            LayerCodecV19.read(file, 8, 4, ByteArray(128))
            fail("v1.9 reads a missing file")
        } catch (e: CorruptProjectExceptionV19) {
            assertEquals("missing file missing.bin", e.message)
        }
    }

    private fun dirWith(name: String, json: String?): File = File(tmp.root, name).also { d ->
        d.mkdirs()
        if (json != null) File(d, "project.json").writeText(json)
    }

    private fun projectJson(version: Int, w: Int = 64, h: Int = 48, layers: Boolean = true): String =
        ProjectFormatV19.encode(
            ProjectFileDtoV19(
                formatVersion = version, id = "p$version", name = "P", width = w, height = h,
                layers = if (!layers) emptyList() else listOf(
                    LayerEntryDtoV19(1, "Layer 1", LayerPropsV19("Layer 1", 1f, LayerBlendModeV19.NORMAL, true, false, false, false, true), file = "layer_1_r1.bin"),
                ),
            ),
        )

    @Test
    fun theLoaderRefusesFormatFourWithV19sWordsAndOpensOneToThree() {
        assertEquals("This artwork was saved by a newer version of Brushwork", ProjectFormatV19.NEWER_VERSION)
        for (v in 1..3) assertEquals("format $v opens", v, ProjectFormatV19.open(dirWith("ok$v", projectJson(v))).formatVersion)
        for (v in listOf(4, 5)) {
            try {
                ProjectFormatV19.open(dirWith("newer$v", projectJson(v)))
                fail("v1.9 opens format $v")
            } catch (e: CorruptProjectExceptionV19) {
                fail("format $v is not damage: ${e.message}")
            } catch (e: IOException) {
                assertEquals("format $v: v1.9's refusal", ProjectFormatV19.NEWER_VERSION, e.message)
            }
        }
        fun corrupt(dir: File): String = try {
            ProjectFormatV19.open(dir)
            fail("v1.9 opens ${dir.name}")
            ""
        } catch (e: CorruptProjectExceptionV19) {
            e.message.orEmpty()
        }
        assertEquals("The artwork's project file is missing", corrupt(dirWith("nojson", null)))
        assertEquals("The artwork's project file is damaged", corrupt(dirWith("damaged", "{\"formatVersion\":4,")))
        assertEquals("The artwork has an invalid size (10001x48)", corrupt(dirWith("big", projectJson(1, w = 10_001))))
        assertEquals("The artwork has no layers", corrupt(dirWith("empty", projectJson(1, layers = false))))
        try {
            ProjectFormatV19.open(File(tmp.root, "gone"))
            fail("v1.9 opens a missing folder")
        } catch (e: FileNotFoundException) {
            assertEquals("This artwork no longer exists", e.message)
        }
        assertTrue("v1.10 reads what v1.9 refuses", ProjectFormat.VERSION == ProjectFormatV19.VERSION + 1)
    }

    @Test
    fun theCapIsV19sAt512MiBAndTheAppComputesTheSame() {
        assertEquals("phone (1080 x 2400)", 25, ProjectFormatV19.v19Cap(1080, 2400))
        assertEquals("2048 x 2048", 14, ProjectFormatV19.v19Cap(2048, 2048))
        assertEquals("4000 x 5000", 2, ProjectFormatV19.v19Cap(4000, 5000))
        assertEquals("512 x 512", 100, ProjectFormatV19.v19Cap(512, 512))
        assertEquals("1 x 1", 100, ProjectFormatV19.v19Cap(1, 1))
        assertEquals("10000 x 10000", 2, ProjectFormatV19.v19Cap(10_000, 10_000))
        assertEquals("the kit's heap is the app's", ProjectFormat.V19_HEAP, ProjectFormatV19.HEAP)
        val sides = listOf(1, 64, 256, 300, 512, 777, 1000, 1080, 1200, 1500, 1920, 2048, 2400, 2500, 3000, 4000, 5000, 8000, 10_000)
        for (w in sides) for (h in sides) {
            assertEquals("${w}x$h: the app's v19Cap is v1.9's", ProjectFormatV19.v19Cap(w, h), ProjectFormat.v19Cap(w, h))
        }
        // v1.9 counts a mask like a layer and a folder as none.
        val dto = ProjectFormatV19.decode(text("$v19Written/banded/whole/project.json"))
        assertEquals("pixel layers plus masks", dto.layers.size + dto.layers.count { it.hasMask }, ProjectFormatV19.effectiveLayerCount(dto))
        val layerBytes = dto.width.toLong() * dto.height * 4
        assertEquals("array bytes in layer units, rounded up", ProjectFormatV19.effectiveLayerCount(dto) + 2, ProjectFormatV19.effectiveLayerCount(dto, layerBytes + 1))
    }

    @Test
    fun arraySpecsReadAndWriteAsTheAppDoes() {
        var n = 0
        for (path in olderProjects + v19Projects) {
            for (l in project(path).layers) {
                val data = l.array ?: continue
                val app = ArrayCodec.decodeSpec(data)?.let { ArrayCodec.encodeSpec(it) }
                assertNotNull("$path ${l.name}: the app reads it", app)
                assertEquals("$path ${l.name}: v1.9 reads and writes it as the app does", app, ArrayCodecV19.decodeSpec(data)?.let { ArrayCodecV19.encodeSpec(it) })
                n++
            }
        }
        assertTrue("array fixtures found: $n", n >= 4)
        val guide = VSubpath(listOf(VAnchor(0f, 0f), VAnchor(100f, 40f, inX = 80f, inY = 30f), VAnchor(200f, 0f, sharp = true)))
        val specs = listOf(
            ArraySpec(),
            ArraySpec(mode = ArrayMode.CIRCLE, count = 12, centerX = 100f, centerY = 80f, sweepDeg = 180f, rotateCopies = false),
            ArraySpec(mode = ArrayMode.CURVE, count = 9, guide = guide, spacing = 12f, alignToCurve = false),
            ArraySpec(mode = ArrayMode.TRANSFORM, count = 30, moveX = 5f, moveY = -3f, turnDeg = 12f, scale = 0.9f, pivotX = 10f, pivotY = 20f),
            ArraySpec(
                count = 5,
                look = ArrayLook(
                    opacityStart = 0.2f, sizeEnd = 2f, turnEnd = 90f, easing = ArrayEasing.EASE_IN_OUT,
                    tint = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0xFF0000FF.toInt())), tintStrength = 0.6f,
                ),
            ),
            ArraySpec(count = 4, look = ArrayLook()),
        )
        for ((i, s) in specs.withIndex()) {
            val app = ArrayCodec.encodeSpec(s)
            assertEquals("spec $i: v1.9's bytes for the same values", ArrayCodecV19.encodeSpec(v19Twin(s)), app)
            assertEquals("spec $i: v1.9 reads and writes it as the app does", ArrayCodec.encodeSpec(ArrayCodec.decodeSpec(app)!!), ArrayCodecV19.encodeSpec(ArrayCodecV19.decodeSpec(app)!!))
        }
    }

    @Test
    fun theFrozenReadersDropWhatV19DoesNotKnow() {
        val dto = ProjectFormatV19.decode(
            """{"formatVersion":1,"id":"p","name":"P","width":10,"height":10,"colorMode":"CMYK","future":{"a":1},""" +
                """"layers":[{"id":1,"name":"a","props":{"name":"a","opacity":1.0,"blendMode":"NORMAL","visible":true,"clipping":false,"alphaLocked":false,"locked":false,"maskEnabled":true,"soon":1},"later":true}]}""",
        )
        assertEquals("an unknown color mode takes the default", ColorModeV19.RGB, dto.colorMode)
        val written = ProjectFormatV19.encode(dto)
        assertTrue("unknown keys dropped: $written", !written.contains("future") && !written.contains("later") && !written.contains("soon"))
        assertTrue("no paper written when there is none", !written.contains("paper"))
        // A blend mode has no default: v1.9 cannot read an unknown one (its loader calls the file damaged).
        val unknownBlend = File(tmp.root, "blend").also { it.mkdirs() }
        File(unknownBlend, "project.json").writeText(written.replace("\"NORMAL\"", "\"FUTURE\""))
        try {
            ProjectFormatV19.open(unknownBlend)
            fail("v1.9 reads an unknown blend mode")
        } catch (e: CorruptProjectExceptionV19) {
            assertEquals("The artwork's project file is damaged", e.message)
        }
        val hidden = ArrayCodec.encodeSpec(ArraySpec(count = 3).copy(hideSource = true))
        val v19 = ArrayCodecV19.decodeSpec(hidden)
        assertNotNull("v1.9 reads a copies-only array", v19)
        assertTrue("v1.9 drops hideSource", !ArrayCodecV19.encodeSpec(v19!!).contains("hideSource"))
        val recolored = ArrayCodec.encodeSpec(
            ArraySpec(count = 3, look = ArrayLook(colorMode = ArrayColorMode.RECOLOR, tint = listOf(VStop(0f, -1), VStop(1f, 0xFF000000.toInt())), tintStrength = 1f)),
        )
        assertTrue("v1.9 drops the color mode", !ArrayCodecV19.encodeSpec(ArrayCodecV19.decodeSpec(recolored)!!).contains("colorMode"))
        val vec = VectorCodecV19.decode(VectorCodecV19.encode(VectorContentV19(objects = emptyList(), nextId = 1)))
        assertEquals("an empty layer reads as empty", 0, vec.objects.size)
    }
}
