package com.brushwork.paint.compat18

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.PayloadArray
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.exchange.export.PayloadRect
import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.MaskCodec
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextKerningMode
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.TextTransforms
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.TextWrapSpec
import com.brushwork.paint.tools.text.WrapPolygon
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeSettings
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
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Inflater

/**
 * v1.9 F0: the frozen v1.8 codecs (`compat18`) are v1.8's. Every v1.8 fixture and golden of
 * `app/src/test/resources` that holds text, shape, vector, project or payload data reads into the
 * frozen copies and writes back exactly as the app's codecs (still v1.8's at 600582d9) read and
 * write it; what v1.8 itself wrote (`qa18/compat/v18-written`) writes back to its own bytes. Data
 * the app builds of every v1.8 kind (cogs, live copies, generators, paths, threads, kerns) does
 * too, and the frozen Transform lift maps a text as the app's does. After F1a the app's codecs
 * are v1.9's; they must still write v1.8 content as v1.8 did (I13), which these comparisons keep.
 * The F1a tests at the end write v1.9 data (several texts, nodes, paper, the new tool options)
 * with the app's codecs and check what v1.8 reads and writes back of it.
 */
class Compat18KitTest {
    private val textFixtures = listOf(
        "v16/project-plain.json", "v16/project-adjust.json", "qa16/v15/v15-plain/project.json", "qa16/v15/v15-adjust/project.json",
        "qa17/compat/v16-plain/project.json", "qa17/compat/v16-adjust/project.json", "qa18/compat/v17-compat/project.json",
    )

    private val projectFixtures = textFixtures + listOf(
        "qa18/compat/v17-edited/project.json", "qa18/compat/v17-untouched/project.json", "qa18/compat/v18-written/project.json",
    )

    private val written = "qa18/compat/v18-written"

    private val vecFiles = listOf("vector_9_r1.vec", "vector_11_r1.vec", "vector_12_r1.vec", "vector_13_r1.vec")

    private fun resource(path: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$path"), File("app/src/test/resources/$path")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $path")
        return file.readBytes()
    }

    private fun text(path: String) = String(resource(path), Charsets.UTF_8)

    private fun project(path: String): ProjectFileDto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), text(path))

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

    /** The app's write of what the app reads from [data] (null when it reads nothing). */
    private fun appText(data: String) = TextCodec.decode(data)?.let { TextCodec.encode(it) }

    private fun frozenText(data: String) = TextCodecV18.decode(data)?.let { TextCodecV18.encode(it) }

    private fun appShape(data: String) = ShapeCodec.decode(data)?.let { ShapeCodec.encode(it) }

    private fun frozenShape(data: String) = ShapeCodecV18.decode(data)?.let { ShapeCodecV18.encode(it) }

    private fun appVector(b: ByteArray) = inflate(VectorCodec.encode(VectorCodec.decode(b)))

    private fun frozenVector(b: ByteArray) = VectorCodecV18.encodeJson(VectorCodecV18.decode(b))

    @Test
    fun textFixturesReadAndWriteAsTheAppDoes() {
        var n = 0
        for (path in textFixtures) {
            for (l in project(path).layers) {
                val data = l.textData ?: continue
                val app = appText(data)
                assertNotNull("$path ${l.name}: the app reads it", app)
                assertEquals("$path ${l.name}: v1.8 reads and writes it as the app does", app, frozenText(data))
                n++
            }
        }
        assertTrue("text fixtures found: $n", n >= 30)
    }

    @Test
    fun everyV18TextKindReadsAndWritesAsTheAppDoes() {
        val spec = TextSpec(
            bold = true, italic = true, sizePx = 37.5f, color = 0xFF336699.toInt(), letterSpacing = 0.12f, lineSpacing = 1.6f,
            strokeWidthPx = 2.5f, antiAlias = false, columnsLeftToRight = true, fontId = "0123456789abcdef0123456789abcdef",
            fontName = "  My\u0007 Font�  ", fontKerning = false,
            box = TextBoxSpec(width = 300f, padding = 8f, fill = true, borderWidth = 3f, roundness = 0.4f, minHeight = 120f),
            letterScale = LetterScaleSpec(smallestPercent = 40f),
        )
        val items = listOf(
            TextItem("Plain", TextSpec(), 10f, 20f),
            TextItem("Kerned text", spec, 120f, 80f, 33f, kerns = listOf(TextKern(0, 50), TextKern(3, -120), TextKern(40, 9))),
            TextItem(
                "On a circle", spec.copy(box = TextBoxSpec()), 200f, 200f,
                path = TextPathSpec(type = TextPathType.CIRCLE, cx = 200f, cy = 200f, radius = 90f, startAngleDeg = 400f),
            ),
            TextItem(
                "Curve", TextSpec(vertical = true), 50f, 60f, -170f,
                path = TextPathSpec(type = TextPathType.CURVE, x1 = 0f, y1 = 0f, cx1 = 30f, cy1 = -40f, cx2 = 70f, cy2 = 40f, x2 = 100f, y2 = 0f),
            ),
            TextItem(
                "Wrapped", TextSpec(box = TextBoxSpec(width = 250f)), 300f, 100f,
                wrap = TextWrapSpec(7L, gapPx = 12f, polygons = listOf(WrapPolygon(listOf(0f, 50f, 50f), listOf(0f, 0f, 40f)))),
            ),
            TextItem(
                "frame", TextSpec(box = TextBoxSpec(width = 200f, minHeight = 100f)), 100f, 100f,
                thread = TextThreadSpec(storyId = 42L, index = 1, story = "A story in two frames", start = 2, end = 7, overset = true, rev = 3),
                kerns = listOf(TextKern(10, 30)),
            ),
            TextItem("Damaged", TextSpec(sizePx = Float.NaN, lineSpacing = 9f), Float.POSITIVE_INFINITY, 5f, Float.NaN),
        )
        for (item in items) {
            val data = TextCodec.encode(item)
            assertEquals("${item.text}: v1.8 reads and writes it as the app does", appText(data), frozenText(data))
            assertEquals("${item.text}: v1.8's write is the app's", data, frozenText(data))
        }
    }

    @Test
    fun theFrozenTransformLiftMapsTextAsTheAppDoes() {
        val matrices = listOf(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(1f, 0f, 25f, 0f, 1f, -40f, 0f, 0f, 1f),
            rotationScale(30.0, 1.5, 12f, 7f),
            rotationScale(-90.0, 0.5, -3f, 100f),
            floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        )
        var mapped = 0
        for (path in textFixtures) {
            for (l in project(path).layers) {
                val data = l.textData ?: continue
                for (m in matrices) {
                    val app = TextTransforms.mapped(data, m)
                    assertEquals("$path ${l.name}: v1.8's Transform maps it as the app does", app, TextTransformsV18.mapped(data, m))
                    if (app != null && app != data) mapped++
                }
            }
        }
        assertTrue("texts mapped: $mapped", mapped >= 60)
        assertNull("a flip is no similarity", TextTransformsV18.mapped(TextCodec.encode(TextItem("x")), matrices[4]))
    }

    private fun rotationScale(deg: Double, s: Double, tx: Float, ty: Float): FloatArray {
        val r = Math.toRadians(deg)
        val c = (Math.cos(r) * s).toFloat()
        val n = (Math.sin(r) * s).toFloat()
        return floatArrayOf(c, -n, tx, n, c, ty, 0f, 0f, 1f)
    }

    @Test
    fun shapeFixturesReadAndWriteAsTheAppDoesAndAsV18WroteThem() {
        var n = 0
        for (path in projectFixtures) {
            for (l in project(path).layers) {
                val data = l.shapeData ?: continue
                val app = appShape(data)
                assertNotNull("$path ${l.name}: the app reads it", app)
                assertEquals("$path ${l.name}: v1.8 reads and writes it as the app does", app, frozenShape(data))
                if (path.startsWith(written)) assertEquals("$path ${l.name}: v1.8 writes back its own bytes", data, frozenShape(data))
                n++
            }
        }
        assertTrue("shape fixtures found: $n", n >= 15)
        val cogs = project("$written/project.json").layers.mapNotNull { it.shapeData }.mapNotNull { ShapeCodecV18.decode(it) }
            .filter { it.type == ShapeTypeV18.COG }
        assertEquals("v1.8's fixture holds two cogs, read back as cogs (fallback points dropped)", 2, cogs.size)
        assertTrue("no fallback points after reading", cogs.all { it.points == null && !it.cogFallback })
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
            assertEquals("shape $i: v1.8 reads and writes it as the app does", appShape(data), frozenShape(data))
            assertEquals("shape $i: v1.8's write is the app's", data, frozenShape(data))
        }
    }

    @Test
    fun vectorFixturesReadAndWriteAsTheAppDoesAndAsV18WroteThem() {
        for (f in vecFiles) {
            val b = resource("$written/$f")
            assertEquals("$f: v1.8 reads and writes it as the app does", appVector(b), frozenVector(b))
            assertEquals("$f: v1.8 writes back its own JSON", inflate(b), frozenVector(b))
        }
    }

    @Test
    fun everyV18VectorKindReadsAndWritesAsTheAppDoes() {
        val preset = BrushPreset("calli", "Calligraphy", BrushTip.CALLIGRAPHY, size = 17.5f, opacity = 0.8f, antiAlias = false, grain = 0.2f)
        val points = PackedPoints(floatArrayOf(1f, 2.5f, 7f), floatArrayOf(3f, 4f, 9.25f), floatArrayOf(0.5f, 0.75f, 1f))
        val mirror = floatArrayOf(-1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val content = VectorContent(
            objects = listOf(
                VStroke(1, 0.9f, preset, 0xFF112233.toInt(), 7L, true, points, copies = listOf(identity, mirror)),
                VPath(
                    2, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(50f, 10f, outX = 60f, outY = 20f, width = 2f)), closed = true)),
                    fill = VPaint.Solid(0xFF00FF00.toInt()),
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
        assertEquals("v1.8 reads and writes every v1.8 kind as the app does", appVector(b), frozenVector(b))
        val back = VectorCodecV18.decode(b)
        assertEquals("ids made unique as v1.8 does", listOf(1L, 2L, 3L, 4L, 5L), back.objects.map { it.id })
        assertEquals("the stroke's points read bit for bit by the frozen PackedPointsV18", PackedPointsV18(points.x, points.y, points.p), (back.objects[0] as VStrokeV18).points)
        assertEquals("the path's live copies kept", 2, (back.objects[1] as VPathV18).copies.maps.size)
        assertEquals("the generator kept", CurveGenKindV18.ROSE, (back.objects[2] as VPathV18).generator?.kind)
        assertNull("the cog read back without its fallback points", (back.objects[3] as VShapeV18).shape.points)
    }

    @Test
    fun projectFixturesReadAndWriteAsTheAppDoesAndAsV18WroteThem() {
        for (path in projectFixtures) {
            val json = text(path)
            val app = ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), project(path))
            assertEquals("$path: v1.8 reads and writes project.json as the app does", app, ProjectFormatV18.encode(ProjectFormatV18.decode(json)))
        }
        val own = text("$written/project.json")
        assertEquals("v1.8 writes back its own project.json", own, ProjectFormatV18.encode(ProjectFormatV18.decode(own)))
    }

    /** A payload of every v1.8 layer kind, built by the app from the fixtures (no payload fixture exists). */
    private fun payload(): BrushworkPayload {
        val layers = ArrayList<PayloadLayer>()
        val dto = project("$written/project.json")
        for (e in dto.layers) {
            val vector = e.vectorFile?.let { VectorCodec.decode(resource("$written/$it")) }
            val array = e.array?.let { PayloadArray(it, Base64.getEncoder().encodeToString(resource("$written/${e.arrayFile}"))) }
            val kind = when {
                vector != null -> PayloadKind.VECTOR
                e.shapeData != null -> PayloadKind.SHAPE
                else -> PayloadKind.RASTER
            }
            layers += PayloadLayer(e.id, e.props, kind, imageRef = "img${e.id}", imageRect = PayloadRect(0, 0, dto.width, dto.height), vector = vector, shapeData = e.shapeData, array = array)
        }
        val adjust = project("v16/project-adjust.json")
        for (e in adjust.layers) {
            layers += PayloadLayer(
                1000 + e.id, e.props, if (e.textData != null) PayloadKind.TEXT else if (e.adjustment != null) PayloadKind.ADJUSTMENT else PayloadKind.RASTER,
                hasMask = e.hasMask, maskRef = if (e.hasMask) "mask${e.id}" else null, maskRect = if (e.hasMask) PayloadRect(5, 6, 70, 80) else null,
                maskFill = if (e.hasMask) 0xFF808080.toInt() else -1,
                textData = e.textData, maskSpec = e.maskSpec?.let { MaskCodec.decode(it) }, adjustment = e.adjustment?.let { AdjustmentCodec.decode(it) },
                parentId = 2000L,
            )
        }
        val props = LayerProps("Folder", 0.7f, LayerBlendMode.SCREEN, true, false, false, false, true)
        layers += PayloadLayer(2000L, props, PayloadKind.FOLDER, folder = FolderSpec(passThrough = false), folderOpen = false)
        return BrushworkPayload(width = dto.width, height = dto.height, dpi = 300f, activeLayer = 3, layers = layers)
    }

    @Test
    fun aPayloadOfEveryV18KindReadsAndWritesAsTheAppDoes() {
        val p = payload()
        assertTrue("the payload has masks and adjustments", p.layers.any { it.maskSpec != null } && p.layers.any { it.adjustment != null })
        val json = Payload.toJson(p)
        val app = String(Payload.toJson(Payload.fromJson(json)), Charsets.UTF_8)
        assertEquals("v1.8 reads and writes the payload as the app does", app, String(PayloadV18.toJson(PayloadV18.fromJson(json)), Charsets.UTF_8))
        assertEquals("v1.8 writes back the app's payload", String(json, Charsets.UTF_8), app)
        assertEquals("written as version 2 (folders)", 2, PayloadV18.fromJson(json).version)
        val deflated = Payload.deflated(p)
        assertEquals("v1.8 inflates the app's deflated payload", app, String(PayloadV18.toJson(PayloadV18.fromDeflated(deflated)), Charsets.UTF_8))
    }

    @Test
    fun theFrozenReadersDropWhatV18DoesNotKnow() {
        val v19Text = """{"version":6,"item":{"text":"Hello","spec":{"align":"JUSTIFY","sizePx":30.0,"kerningMode":"OPTICAL"},"id":7},""" +
            """"more":[{"text":"Second","id":8}]}"""
        val item = TextCodecV18.decode(v19Text)
        assertNotNull("v1.8 reads a v1.9 text layer", item)
        assertEquals("only the first text", "Hello", item!!.text)
        assertEquals("an unknown alignment takes the default", TextAlignV18.START, item.spec.align)
        assertTrue("written back as version 5 without the v1.9 keys", TextCodecV18.encode(item).let { it.startsWith("{\"version\":5,") && !it.contains("more") && !it.contains("kerningMode") })
        val dto = ProjectFormatV18.decode("""{"formatVersion":1,"id":"p","name":"P","width":10,"height":10,"paper":{"color":-1}}""")
        assertTrue("v1.8 drops the paper", !ProjectFormatV18.encode(dto).contains("paper"))
        val p = PayloadV18.fromJson("""{"version":2,"width":10,"height":10,"paper":{"color":-1},"layers":[{"id":1,"props":{"name":"a","opacity":1.0,"blendMode":"NORMAL","visible":true,"clipping":false,"alphaLocked":false,"locked":false,"maskEnabled":true},"kind":"PAPER"}]}""".toByteArray())
        assertEquals("an unknown layer kind reads as RASTER", PayloadKindV18.RASTER, p.layers.single().kind)
    }

    // v1.9 F1a: what the app's v1.9 codecs write, read by v1.8 (the frozen copies) and written back.

    /** A text using every v1.9 text field. */
    private val v19Text = TextItem(
        "First", TextSpec(justify = TextJustify.ALL, kerningMode = TextKerningMode.OPTICAL, list = TextListSpec(type = TextListType.NUMBERS, start = 3, gapEm = 1f)),
        10f, 20f, id = 4,
    )

    /** [item] without its v1.9 fields: what v1.8 keeps of it. */
    private fun v18Part(item: TextItem) =
        item.copy(spec = item.spec.copy(justify = TextJustify.OFF, kerningMode = null, list = TextListSpec()), path = item.path.copy(nodes = null), id = 0)

    @Test
    fun aLayerOfSeveralTextsReadsInV18AsItsFirstText() {
        val items = listOf(v19Text, TextItem("Second", TextSpec(sizePx = 50f), id = 9), TextItem("Third"))
        val data = TextCodec.encodeAll(items)
        val v18 = TextCodecV18.decode(data)
        assertNotNull("v1.8 reads a layer of several texts", v18)
        assertEquals("only the first text", "First", v18!!.text)
        val back = TextCodecV18.encode(v18)
        assertTrue(back, back.startsWith("{\"version\":5,"))
        for (key in listOf("\"more\"", "\"id\"", "\"justify\"", "\"list\"", "\"kerningMode\"")) assertFalse("v1.8 writes no $key", back.contains(key))
        assertEquals("v1.8 writes the first text as v1.9 writes it without its v1.9 fields", TextCodec.encode(v18Part(v19Text)), back)
        assertEquals("v1.9 reads v1.8's write as that one text", listOf(v18Part(v19Text).sanitized()), TextCodec.decodeAll(back))
    }

    @Test
    fun aTextAlongNodesReadsInV18AsItsFallback() {
        val guides = listOf(
            VSpline(listOf(VSplinePoint(10f, 20f), VSplinePoint(110f, 70f)), order = 2),
            VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(90f, 0f), VSplinePoint(90f, 90f)), order = 2),
            VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(0f, 100f), VSplinePoint(100f, 100f), VSplinePoint(100f, 0f), VSplinePoint(160f, 40f))),
            VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(100f, 0f), VSplinePoint(100f, 100f), VSplinePoint(0f, 100f)), order = 2, cyclic = true),
        )
        for ((i, nodes) in guides.withIndex()) {
            val item = TextItem("Along", TextSpec(), 0f, 0f, path = TextPathSpec(type = TextPathType.CURVE, nodes = nodes))
            val data = TextCodec.encode(item)
            val fallback = item.sanitized().path
            val v18 = TextCodecV18.decode(data)!!.path
            val type = if (nodes.points.size == 2) "LINE" else "CURVE"
            assertEquals("guide $i: v1.8's type", type, v18.type.name)
            assertEquals(
                "guide $i: v1.8 reads the app's fallback",
                listOf(fallback.x1, fallback.y1, fallback.cx1, fallback.cy1, fallback.cx2, fallback.cy2, fallback.x2, fallback.y2),
                listOf(v18.x1, v18.y1, v18.cx1, v18.cy1, v18.cx2, v18.cy2, v18.x2, v18.y2),
            )
            val back = TextCodecV18.encode(TextCodecV18.decode(data)!!)
            assertFalse("guide $i: v1.8 writes no nodes", back.contains("\"nodes\""))
            val again = TextCodec.decode(back)!!
            assertNull("guide $i: v1.9 reads v1.8's write without nodes", again.path.nodes)
            assertEquals("guide $i: and with the fallback as its path", fallback.copy(nodes = null), again.path)
        }
    }

    @Test
    fun aV19ProjectKeepsItsTextsInV18AndLosesItsPaper() {
        val multi = TextCodec.encodeAll(listOf(v19Text, TextItem("Second", id = 9)))
        val base = project("v16/project-plain.json")
        val dto = base.copy(layers = base.layers.map { if (it.textData != null) it.copy(textData = multi) else it }, paper = PaperSpec(0xFFF4ECD8.toInt()))
        val json = ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto)
        assertTrue("the app writes the paper", json.contains("\"paper\":{\"color\":"))
        val v18 = ProjectFormatV18.encode(ProjectFormatV18.decode(json))
        assertFalse("v1.8 drops the paper", v18.contains("\"paper\""))
        val texts = ProjectFormatV18.decode(v18).layers.mapNotNull { it.textData }
        assertTrue("text layers: ${texts.size}", texts.size >= 10)
        assertTrue("v1.8 keeps each text layer's v1.9 data unchanged", texts.all { it == multi })
        assertEquals("v1.8 writes project.json as the app does without the paper", ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto.copy(paper = null)), v18)
    }

    @Test
    fun aV19PayloadKeepsItsTextsInV18AndLosesItsPaper() {
        val multi = TextCodec.encodeAll(listOf(v19Text, TextItem("Second", id = 9)))
        val base = payload()
        val p = base.copy(layers = base.layers.map { if (it.textData != null) it.copy(textData = multi) else it }, paper = PaperSpec(0xFF102030.toInt()))
        assertTrue("the payload has text layers", p.layers.any { it.textData == multi })
        val json = Payload.toJson(p)
        assertTrue("the app writes the paper", String(json, Charsets.UTF_8).contains("\"paper\":{\"color\":"))
        val v18 = PayloadV18.fromJson(json)
        assertTrue("v1.8 keeps each text layer's v1.9 data unchanged", v18.layers.filter { it.textData != null }.all { it.textData == multi })
        val back = String(PayloadV18.toJson(v18), Charsets.UTF_8)
        assertFalse("v1.8 drops the paper", back.contains("\"paper\""))
        assertEquals("v1.8 writes the payload as the app does without the paper", String(Payload.toJson(p.copy(paper = null)), Charsets.UTF_8), back)
        assertEquals("deflated too", back, String(PayloadV18.toJson(PayloadV18.fromDeflated(Payload.deflated(p))), Charsets.UTF_8))
    }

    @Test
    fun theToolSettingsReadInV18AsV18WroteThemAndLoseTheV19Options() {
        // AppSettings.json's configuration (an AppSettings needs a Context; the row 14 test uses the real one).
        val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun app(s: CurveSettings) = settingsJson.encodeToString(CurveSettings.serializer(), s)
        fun app(s: ShapeSettings) = settingsJson.encodeToString(ShapeSettings.serializer(), s)
        assertEquals("v1.9's default curve options are v1.8's bytes", ToolSettingsV18.write(CurveSettingsV18()), app(CurveSettings()))
        assertEquals("v1.9's default shape options are v1.8's bytes", ToolSettingsV18.write(ShapeSettingsV18()), app(ShapeSettings()))

        val curve = CurveSettings(
            closed = true, tension = 0.5f, stroke = CurveStroke.PLAIN, plainWidth = 9f, fill = true, fillColor = 0xFF336699.toInt(), taper = true,
            taperPercent = 30f, unit = LengthUnit.MM, nudgeStepPx = 2f, useBrushSize = false, pathOrder = 3, pathEndpoint = false, lastStroke = CurveStroke.PLAIN,
        )
        assertEquals("v1.8 writes back the app's curve options", app(curve), ToolSettingsV18.write(ToolSettingsV18.curve(app(curve))))
        val own = app(curve.copy(ownLayer = true))
        assertTrue(own, own.contains("\"ownLayer\":true"))
        val curveBack = ToolSettingsV18.write(ToolSettingsV18.curve(own))
        assertEquals("v1.8 reads ownLayer as nothing and writes the rest as it was", app(curve), curveBack)
        assertEquals("v1.9 reads v1.8's write with ownLayer off", curve, settingsJson.decodeFromString(CurveSettings.serializer(), curveBack))

        val shape = ShapeSettings(
            type = ShapeType.COG, style = ShapeStyle.STROKE_FILL, strokeWidth = 5f, editable = false, cogTeeth = 20, cogHole = 0.4f, unit = LengthUnit.CM,
        )
        assertEquals("v1.8 writes back the app's shape options", app(shape), ToolSettingsV18.write(ToolSettingsV18.shape(app(shape))))
        val each = app(shape.copy(newLayerEachTime = true))
        assertTrue(each, each.contains("\"newLayerEachTime\":true"))
        val shapeBack = ToolSettingsV18.write(ToolSettingsV18.shape(each))
        assertEquals("v1.8 reads newLayerEachTime as nothing and writes the rest as it was", app(shape), shapeBack)
        assertEquals("v1.9 reads v1.8's write with newLayerEachTime off", shape, settingsJson.decodeFromString(ShapeSettings.serializer(), shapeBack))
    }
}
