package com.brushwork.paint.qa19

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.compat18.CopyMapsV18
import com.brushwork.paint.compat18.CurveSettingsV18
import com.brushwork.paint.compat18.PayloadV18
import com.brushwork.paint.compat18.ProjectFileDtoV18
import com.brushwork.paint.compat18.ProjectFormatV18
import com.brushwork.paint.compat18.ShapeCodecV18
import com.brushwork.paint.compat18.ShapeLayerDataV18
import com.brushwork.paint.compat18.ShapeObjectV18
import com.brushwork.paint.compat18.ShapeSettingsV18
import com.brushwork.paint.compat18.ShapeTypeV18
import com.brushwork.paint.compat18.TextAlignV18
import com.brushwork.paint.compat18.TextCodecV18
import com.brushwork.paint.compat18.TextPathTypeV18
import com.brushwork.paint.compat18.TextTransformsV18
import com.brushwork.paint.compat18.ToolSettingsV18
import com.brushwork.paint.compat18.VObjectV18
import com.brushwork.paint.compat18.VPathV18
import com.brushwork.paint.compat18.VShapeV18
import com.brushwork.paint.compat18.VSplineV18
import com.brushwork.paint.compat18.VStrokeV18
import com.brushwork.paint.compat18.VectorCodecV18
import com.brushwork.paint.compat18.VectorContentV18
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.diagnostics.ReportText
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.CanvasStart
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.select.SelectionDuplicate
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextKerningMode
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextNumbering
import com.brushwork.paint.tools.text.TextPathNodes
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.v19.a1.A1Tools
import com.brushwork.paint.v19.a1.A1Tools.curveTool
import com.brushwork.paint.v19.a4.A4Kit
import com.brushwork.paint.v19.a8.BackupKit
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorDrawState
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.math.hypot

/**
 * v1.9 (design 2.2 and 5.2, modelled on `qa18/Qa18CompatBackFromV17RobolectricTest`): what v1.8
 * does with what v1.9 writes, one test per row of the compat table of 5.2, each reading a v1.9
 * fixture of `app/src/test/resources/qa19/compat/<row>/` with the frozen v1.8 copies of
 * `compat18/` ([ProjectFormatV18], [TextCodecV18], [ShapeCodecV18], [VectorCodecV18],
 * [PayloadV18], [TextTransformsV18]), as v1.8 would.
 *
 * At F0 every row is `@Ignore`d with its row and owner: the owning area writes the row's
 * fixture generator ([generators]), runs [generateFixtures] once, un-ignores its row and adds the
 * row's own checks after [v18Reads] (the table's "v1.8 opens" and "v1.8 edits and saves"
 * columns). Rows 10 and 12 have one test per owner; row 8 (the wrap re-flow) cannot occur and
 * has none (I24 is `MultiTextLayerRobolectricTest`'s). Q18 repeats the table with the real v1.8
 * APK.
 *
 * UI harness: the sections only decode and reopen files, no row needs one. Two generators may:
 * row 10's duplicates (the selection bar's Duplicate) and row 12's R17 edits (the Shape strip),
 * where the area's code has no entry point below the UI; they then use [Qa19Harness.editor].
 *
 * The test file is in `$ExtraTestFiles` of A8, A5, A1, A9, A4 and A3 (5.4); rows owned by A7a,
 * A7b or the lead (13, 14, 15a) are un-ignored by the lead.
 */
@RunWith(RobolectricTestRunner::class)
class Qa19CompatBackFromV18RobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        File(app.filesDir, "projects").deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    /** A file of the fixture folder `qa19/compat/<path>`. */
    private fun resource(path: String): ByteArray {
        val full = "qa19/compat/$path"
        val stream = javaClass.classLoader?.getResourceAsStream(full) ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(full)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$full"), File("app/src/test/resources/$full")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $full (run generateFixtures)")
        return file.readBytes()
    }

    private fun text(path: String): String = String(resource(path), Charsets.UTF_8)

    /**
     * What v1.8 reads of the project of row [row]: `project.json` and every layer's text, shape
     * and vector data through the frozen v1.8 readers, none refused. The fixture holds each of
     * [newKeys] (so it exercises the row), and v1.8 writes none of them back.
     */
    private fun v18Reads(row: String, newKeys: List<String> = emptyList()): ProjectFileDtoV18 {
        val raw = text("$row/project.json")
        val dto = ProjectFormatV18.decode(raw)
        var written = ProjectFormatV18.encode(dto)
        val inLayers = StringBuilder(raw)
        for (l in dto.layers) {
            l.textData?.let { d ->
                val item = TextCodecV18.decode(d)
                assertNotNull("$row ${l.name}: v1.8 reads the text", item)
                written += TextCodecV18.encode(item!!)
                // A3: the text data is a string in project.json (its quotes escaped): look for the keys in it as read.
                inLayers.append(d)
            }
            l.shapeData?.let { d ->
                val s = ShapeCodecV18.decode(d)
                assertNotNull("$row ${l.name}: v1.8 reads the shape", s)
                written += ShapeCodecV18.encode(s!!)
            }
            l.vectorFile?.let { f ->
                val bytes = resource("$row/$f")
                inLayers.append(String(PayloadV18.inflate(bytes), Charsets.UTF_8))
                written += VectorCodecV18.encodeJson(VectorCodecV18.decode(bytes))
            }
        }
        for (k in newKeys) {
            assertTrue("$row: the fixture writes \"$k\"", inLayers.contains("\"$k\""))
            assertFalse("$row: v1.8 writes no \"$k\" back", written.contains("\"$k\""))
        }
        return dto
    }

    /** The texts of a layer's data: v1.8 reads the first only. */
    private fun firstText(row: String, layer: String): String =
        requireNotNull(v18Reads(row).layers.single { it.name == layer }.textData) { "$row: $layer is a text layer" }

    /**
     * The objects of vector layer [layer] of [row] as v1.8 reads them (A9's rows): only kinds and
     * fields v1.8 knows (a strict reader takes the data), v1.8's sanitizing changes nothing, and
     * what v1.8 saves after an edit reads back the same.
     */
    private fun v18Objects(row: String, layer: String, dto: ProjectFileDtoV18): List<VObjectV18> {
        val file = requireNotNull(dto.layers.single { it.name == layer }.vectorFile) { "$row: $layer is a vector layer" }
        val bytes = resource("$row/$file")
        val strict = STRICT_V18.decodeFromString(VectorContentV18.serializer(), String(PayloadV18.inflate(bytes), Charsets.UTF_8))
        val v18 = VectorCodecV18.decode(bytes)
        assertEquals("$row $layer: v1.8's sanitizing changes nothing", strict, v18)
        assertEquals("$row $layer: v1.8 saves what it read", v18, VectorCodecV18.decode(VectorCodecV18.encode(v18)))
        return v18.objects
    }

    /**
     * The shape of shape layer [layer] of [row] as v1.8 reads it (A9's rows): only fields v1.8
     * knows (a strict reader takes the data), v1.8's sanitizing changes nothing, and v1.8 saves
     * it back byte for byte.
     */
    private fun v18Shape(row: String, layer: String, dto: ProjectFileDtoV18): ShapeObjectV18 {
        val data = requireNotNull(dto.layers.single { it.name == layer }.shapeData) { "$row: $layer is a shape layer" }
        val strict = STRICT_V18.decodeFromString(ShapeLayerDataV18.serializer(), data)
        assertEquals("$row $layer: the shape data version", ShapeCodecV18.VERSION, strict.version)
        val v18 = requireNotNull(ShapeCodecV18.decode(data)) { "$row: v1.8 reads $layer's shape" }
        assertEquals("$row $layer: v1.8's sanitizing changes nothing", strict.shape, v18)
        assertEquals("$row $layer: v1.8 saves exactly what v1.9 wrote", data, ShapeCodecV18.encode(v18))
        return v18
    }

    /** v1.8 reads every field of each shape object of [file] of [row] as v1.9 does (their shape-layer JSON). */
    private fun sameShapesAsV19(row: String, file: String, v18: List<VObjectV18>) {
        val v19 = VectorCodec.decode(resource("$row/$file")).objects
        assertEquals("$row: the objects", v19.map { it.id }, v18.map { it.id })
        for ((a, b) in v19.zip(v18)) {
            if (a !is VShape) continue
            assertTrue("$row: object ${a.id} is a shape in v1.8 too", b is VShapeV18)
            b as VShapeV18
            assertEquals("$row: v1.8 reads every field of shape ${a.id}", ShapeCodec.encode(a.shape), ShapeCodecV18.encode(b.shape))
            assertEquals(a.seed, b.seed)
            assertEquals(a.opacity, b.opacity, 0f)
        }
    }

    private fun assertBox(what: String, s: ShapeObjectV18, cx: Float, cy: Float, w: Float, h: Float) {
        assertEquals("$what: cx", cx, s.cx, 1e-3f)
        assertEquals("$what: cy", cy, s.cy, 1e-3f)
        assertEquals("$what: w", w, s.w, 1e-3f)
        assertEquals("$what: h", h, s.h, 1e-3f)
    }

    // ------------------------------------------------------------------ the rows (5.2)

    @Test
    fun row01TextJustify() {
        v18Reads("text-justify", listOf("justify"))
        // A3: v1.8 shows the saved pixels; an edit lays out with `align` (ragged) and drops the field.
        val item = requireNotNull(TextCodecV18.decode(firstText("text-justify", "Justified"))) { "v1.8 reads the justified text" }
        assertEquals(TEXT_PARAGRAPH, item.text)
        assertEquals("the last line's alignment is v1.8's align", TextAlignV18.END, item.spec.align)
        assertTrue("the box width is kept", item.spec.box.width > 0f)
    }

    @Test
    fun row02TextKerningMode() {
        v18Reads("text-kerning", listOf("kerningMode"))
        // A3: v1.8 shows the saved pixels; an edit kerns as "Auto" while "Font kerning" is on in the
        // data, and not at all while it is off ("Off" keeps the mode in v1.9 and turns the switch off).
        val metrics = requireNotNull(TextCodecV18.decode(firstText("text-kerning", "Metrics"))) { "v1.8 reads Metrics" }
        assertTrue("Metrics: Font kerning on, so v1.8 kerns as Auto", metrics.spec.fontKerning)
        val off = requireNotNull(TextCodecV18.decode(firstText("text-kerning", "Off"))) { "v1.8 reads Off" }
        assertFalse("Off: Font kerning off, so v1.8 doesn't kern", off.spec.fontKerning)
    }

    @Test
    fun row03TextList() {
        v18Reads("text-list", listOf("list"))
        // A3: v1.8 shows the saved pixels; an edit drops markers and indent and keeps the text.
        for (name in listOf("Numbered", "Bullets")) {
            val item = requireNotNull(TextCodecV18.decode(firstText("text-list", name))) { "v1.8 reads $name" }
            assertEquals(name, TEXT_LIST, item.text)
            assertTrue("$name: the box width is kept", item.spec.box.width > 0f)
            // v1.9 reads its list back.
            assertTrue(name, TextCodec.decode(firstText("text-list", name))!!.spec.list.isOn)
        }
    }

    @Test
    fun row04TextPathNodes() {
        v18Reads("text-path-nodes", listOf("nodes"))
        // A3: v1.8 reads the CURVE or LINE fallback (a closed guide becomes an open curve); the nodes are dropped.
        for ((name, type) in listOf("Path nodes" to TextPathTypeV18.CURVE, "Closed" to TextPathTypeV18.CURVE, "Two points" to TextPathTypeV18.LINE)) {
            val data = firstText("text-path-nodes", name)
            val old = requireNotNull(TextCodecV18.decode(data)) { "v1.8 reads $name" }
            assertEquals(name, type, old.path.type)
            // v1.9 reads its nodes back. An open guide's fallback runs between its end nodes.
            val nodes = requireNotNull(TextPathNodes.of(TextCodec.decode(data)!!.path)) { "v1.9 reads the nodes of $name" }
            if (TextPathNodes.isClosed(nodes)) continue
            assertEquals("$name: the fallback starts at the first node", nodes.points.first().x, old.path.x1, 0.05f)
            assertEquals("$name: the fallback starts at the first node", nodes.points.first().y, old.path.y1, 0.05f)
            assertEquals("$name: the fallback ends at the last node", nodes.points.last().x, old.path.x2, 0.05f)
            assertEquals("$name: the fallback ends at the last node", nodes.points.last().y, old.path.y2, 0.05f)
        }
        // A closed guide's fallback runs once around its curve (through, not on, its nodes): it
        // starts and ends at one point of the circle.
        val closed = requireNotNull(TextCodecV18.decode(firstText("text-path-nodes", "Closed"))).path
        assertTrue("the circle's nodes are closed", TextPathNodes.isClosed(TextPathNodes.of(TextCodec.decode(firstText("text-path-nodes", "Closed"))!!.path)!!))
        assertEquals("Closed: the fallback ends where it starts", closed.x1, closed.x2, 0.05f)
        assertEquals("Closed: the fallback ends where it starts", closed.y1, closed.y2, 0.05f)
        assertEquals("Closed: it starts on the circle", 40f, hypot(closed.x1 - 120f, closed.y1 - 100f), 0.5f)
    }

    @Test
    fun row05SeveralTextsInALayer() {
        val dto = v18Reads("text-several", listOf("more"))
        // A3: v1.8 shows the saved pixels with every text; it edits only the first text; its
        // re-encode drops `more`; an untouched layer's JSON survives a v1.8 save.
        val data = firstText("text-several", "Two texts")
        assertEquals("v1.9 wrote both texts", SEVERAL.map { it.text }, TextCodec.decodeAll(data).map { it.text })
        val first = requireNotNull(TextCodecV18.decode(data)) { "v1.8 reads the layer" }
        assertEquals("v1.8 edits the first text", SEVERAL[0].text, first.text)
        val edited = TextCodecV18.encode(first.copy(text = "Edited in v1.8"))
        assertFalse("v1.8's re-encode drops the others", edited.contains("\"more\""))
        assertEquals("v1.9 reads v1.8's edit as one text", listOf("Edited in v1.8"), TextCodec.decodeAll(edited).map { it.text })
        // Untouched: the layer's text data goes through v1.8's save as it was.
        val saved = ProjectFormatV18.decode(ProjectFormatV18.encode(dto))
        assertEquals("v1.8 saves the untouched layer's data as it was", data, saved.layers.single { it.name == "Two texts" }.textData)
        // A one-text layer is v1.8's format, byte for byte.
        val one = firstText("text-several", "One text")
        assertEquals(one, TextCodecV18.encode(TextCodecV18.decode(one)!!))
    }

    @Test
    fun row06V18TransformOfASeveralTextLayer() {
        v18Reads("text-transform", listOf("more"))
        val data = firstText("text-transform", "Three texts")
        assertEquals(3, TextCodec.decodeAll(data).size)
        val moved = TextTransformsV18.mapped(data, floatArrayOf(0.8f, -0.6f, 40f, 0.6f, 0.8f, -12f, 0f, 0f, 1f))
        assertNotNull("v1.8 transforms the layer's text", moved)
        assertFalse("v1.8 keeps only the first text", moved!!.contains("\"more\""))
        // A3: v1.9 reopens the v1.8-transformed layer with one editable text, the pixels of the
        // others moved with it (I1 for the first text only).
        val reopened = TextCodec.decodeAll(moved).single()
        val src = TextCodec.decodeAll(data)[0]
        assertEquals(src.text, reopened.text)
        assertEquals("moved as v1.8 maps it", 0.8f * src.cx - 0.6f * src.cy + 40f, reopened.cx, 0.01f)
        assertEquals(0.6f * src.cx + 0.8f * src.cy - 12f, reopened.cy, 0.01f)
        assertEquals("turned with it", src.rotationDeg + 36.87f, reopened.rotationDeg, 0.01f)
    }

    @Test
    fun row07V18ArraySourceAndLiftOfASeveralTextLayer() {
        v18Reads("text-array-lift", listOf("more"))
        // A3: v1.8's copy or lift carries the first text only; the others are pixels.
        val data = firstText("text-array-lift", "Array source")
        assertEquals(SEVERAL.size, TextCodec.decodeAll(data).size)
        // v1.8's ArraySources and DataLift read a text layer with its TextCodec and write the copy with it.
        val copy = TextCodecV18.encode(requireNotNull(TextCodecV18.decode(data)) { "v1.8 reads the source" })
        assertFalse(copy.contains("\"more\""))
        assertEquals("the copy holds the first text", listOf(SEVERAL[0].text), TextCodec.decodeAll(copy).map { it.text })
    }

    // Row 8, v1.8's wrap re-flow (TextWrapReflow): not reachable, no section (a wrapping or
    // framed text is alone in its layer, I24, checked by MultiTextLayerRobolectricTest).

    /** 5.2 row 9, ProjectFileDto.paper and BrushworkPayload.paper (NEVER): owner A8, fixture by [writePaper]. */
    @Test
    fun row09Paper() {
        val raw = text("paper/project.json")
        assertTrue("the fixture has a paper", raw.contains("\"paper\""))
        assertFalse("v1.8 writes no paper back", ProjectFormatV18.encode(ProjectFormatV18.decode(raw)).contains("\"paper\""))
        val payload = resource("paper/payload.json")
        assertTrue(String(payload, Charsets.UTF_8).contains("\"paper\""))
        assertFalse("v1.8's payload drops the paper", String(PayloadV18.toJson(PayloadV18.fromJson(payload)), Charsets.UTF_8).contains("\"paper\""))
        // A8: v1.8 opens one transparent layer over the checker; its PNG is transparent, its JPEG on white.
        val dto = v18Reads("paper", listOf("paper"))
        assertEquals("one empty layer", listOf("Layer 1"), dto.layers.map { it.name })
        assertEquals("v1.8's payload keeps the size", 64, PayloadV18.fromJson(payload).width)
    }

    /** 5.2 row 10 (shapes joined into a vector layer, S1), existing kinds only: owner A9, fixture by [writeShapesJoined]. */
    @Test
    fun row10ShapesJoinedIntoAVectorLayer() {
        val dto = v18Reads("shapes-joined")
        // A9: fully read and fully editable in v1.8. The shape layer the ellipse joined is a vector
        // layer of three shape objects (the star drawn on it afterwards); its data holds only kinds
        // and fields v1.8 knows, and v1.8 reads every field of each shape as v1.9 does.
        assertEquals(listOf("Layer 1", ShapeTypeV18.RECTANGLE.label), dto.layers.map { it.name })
        val joined = dto.layers.single { it.name == ShapeTypeV18.RECTANGLE.label }
        assertEquals("no shape data left", null, joined.shapeData)
        val objects = v18Objects("shapes-joined", joined.name, dto)
        assertTrue(objects.all { it is VShapeV18 })
        val shapes = objects.map { (it as VShapeV18).shape }
        assertEquals(listOf(ShapeTypeV18.RECTANGLE, ShapeTypeV18.ELLIPSE, ShapeTypeV18.STAR), shapes.map { it.type })
        assertBox("the rectangle", shapes[0], 70f, 60f, 80f, 60f)
        assertBox("the ellipse", shapes[1], 190f, 150f, 60f, 60f)
        assertEquals("three objects, each with its own id", 3, objects.map { it.id }.toSet().size)
        sameShapesAsV19("shapes-joined", joined.vectorFile!!, objects)
    }

    @Test
    fun row10MixedCurves() {
        v18Reads("curves-mixed")
        // A1: fully read and fully editable in v1.8: the Curve, Polyline and Path lines of one
        // vector layer keep what v1.8's tools reopen them by (corners, the polyline flag, the spline).
        val paths = curvePaths("curves-mixed")
        assertEquals("one layer, three lines", 3, paths.size)
        val (curve, polyline, path) = paths
        assertFalse(curve.polyline)
        assertEquals(null, curve.spline)
        assertEquals(3, curve.subpaths.single().anchors.size)
        assertTrue(polyline.polyline)
        assertEquals(null, polyline.spline)
        assertEquals(3, polyline.subpaths.single().anchors.size)
        assertTrue("Polyline's corners", polyline.subpaths.single().anchors.all { it.sharp })
        assertFalse(path.polyline)
        assertEquals(3, requireNotNull(path.spline) { "the Path line keeps its spline" }.points.size)
    }

    /** 5.2 row 10 (merged layers), existing kinds only: owner A4, fixture by [writeMergedLayers]. */
    @Test
    fun row10MergedLayers() {
        val dto = v18Reads("layers-merged")
        // A4: fully read and fully editable in v1.8: two vector layers of the kinds v1.8 knows.
        assertEquals(listOf("Background", "Curves", "Shapes"), dto.layers.map { it.name })
        val (curves, shapes) = dto.layers[1] to dto.layers[2]
        for (l in listOf(curves, shapes)) {
            assertNull("${l.name}: no shape data (a vector layer)", l.shapeData)
            assertNull("${l.name}: no text data", l.textData)
            assertEquals("${l.name}: at 100 % (the opacity folded into the objects)", 1f, l.props.opacity, 0f)
        }
        val merged = VectorCodecV18.decode(resource("layers-merged/${requireNotNull(curves.vectorFile)}"))
        assertEquals("both layers' objects", 2, merged.objects.size)
        assertEquals("the lower one's at its own opacity", 1f, merged.objects[0].opacity, 0.001f)
        assertEquals("the upper layer's 50 % folded into its object", 0.5f, merged.objects[1].opacity, 0.001f)
        editable(merged, "Curves")
        val joined = VectorCodecV18.decode(resource("layers-merged/${requireNotNull(shapes.vectorFile)}"))
        assertEquals("both shapes", 2, joined.objects.size)
        assertTrue("shapes as v1.8's shape objects", joined.objects.all { it is VShapeV18 })
        editable(joined, "Shapes")
    }

    /** 5.2 row 10 (duplicates), existing kinds only: owner A4, fixture by [writeDuplicates]. */
    @Test
    fun row10Duplicates() {
        val dto = v18Reads("duplicates")
        // A4: fully read and fully editable in v1.8: the copies are pixels and objects of their layers.
        assertEquals("no new layer", listOf("Background", "Painted", "Objects"), dto.layers.map { it.name })
        val painted = dto.layers[1]
        assertNull(painted.vectorFile)
        val pixels = LayerCodec.readBitmap(resource("duplicates/${requireNotNull(painted.file)}").inputStream(), painted.name, dto.width, dto.height)
        assertEquals("the original", DUPLICATE_RED, pixels.getPixel(21, 21))
        assertEquals("the copy, 16 px right and down", DUPLICATE_RED, pixels.getPixel(59 + 16, 49 + 16))
        val objects = VectorCodecV18.decode(resource("duplicates/${requireNotNull(dto.layers[2].vectorFile)}"))
        assertEquals("two objects and the copy", 3, objects.objects.size)
        assertEquals("the copy is of the first's kind", objects.objects[0].javaClass, objects.objects[2].javaClass)
        editable(objects, "Objects")
    }

    /** [content] as v1.8 edits it: distinct ids, all below its next id. */
    private fun editable(content: VectorContentV18, layer: String) {
        val ids = content.objects.map { it.id }
        assertEquals("$layer: distinct ids $ids", ids.size, ids.toSet().size)
        assertTrue("$layer: the next id ${content.nextId} past $ids", ids.all { it < content.nextId })
    }

    /** 5.2 row 10 (cut pieces), existing kinds only: owner A5, fixture by [writeCutPieces]. */
    @Test
    fun row10CutPieces() {
        val dto = v18Reads("cut-pieces")
        // A5: fully read and fully editable in v1.8. Both layers are vector layers (the shape
        // layer was converted by its cut); their data holds only kinds and fields v1.8 knows (a
        // strict reader takes it), v1.8's sanitizing changes nothing, and what v1.8 saves after
        // an edit (its encode) reads back the same.
        val layers = dto.layers.filter { it.vectorFile != null }
        assertEquals("two vector layers", listOf(CUT_VECTOR_LAYER, CUT_SHAPE_LAYER), layers.map { it.name })
        assertTrue("the shape layer is a vector layer now", layers.all { it.shapeData == null })
        val read = layers.associate { l ->
            val bytes = resource("cut-pieces/${l.vectorFile}")
            val strict = STRICT_V18.decodeFromString(VectorContentV18.serializer(), String(PayloadV18.inflate(bytes), Charsets.UTF_8))
            val v18 = VectorCodecV18.decode(bytes)
            assertEquals("${l.name}: v1.8's sanitizing changes nothing", strict, v18)
            assertEquals("${l.name}: v1.8 saves what it read", v18, VectorCodecV18.decode(VectorCodecV18.encode(v18)))
            l.name to v18.objects
        }
        val pieces = read.getValue(CUT_VECTOR_LAYER)
        assertTrue(pieces.all { it is VPathV18 || it is VStrokeV18 })
        assertFalse("no shape is left whole", pieces.any { it is VShapeV18 })
        val filled = pieces.filterIsInstance<VPathV18>()
        // The box (2), the ellipse (2), the box with a copy (2, and its copy baked into one).
        assertEquals("the filled pieces", 7, filled.size)
        assertTrue("filled, stroked, no live copies", filled.all { it.fill != null && it.stroke != null && it.copies == CopyMapsV18.NONE })
        assertEquals("the stroke's two ends", 2, pieces.count { it is VStrokeV18 })
        val shapePieces = read.getValue(CUT_SHAPE_LAYER)
        assertEquals("the shape layer's rectangle in two pieces", 2, shapePieces.size)
        assertTrue(shapePieces.all { it is VPathV18 && it.fill != null })
    }

    @Test
    fun row10BevelledCurvePoints() {
        v18Reads("bevel-curves")
        // A1: fully read and fully editable in v1.8. Only Polyline bevels (R18): a bevel is
        // more sharp points of the same polyline, with thickness in v1.8's range.
        val paths = curvePaths("bevel-curves")
        assertEquals(2, paths.size)
        val (open, closed) = paths
        for (p in paths) {
            assertTrue(p.polyline)
            assertTrue(p.subpaths.single().anchors.all { it.sharp && it.width in 0f..VSplineV18.MAX_WIDTH })
        }
        assertEquals("4 points, 2 corners of 3 points each (2 segments)", 8, open.subpaths.single().anchors.size)
        assertFalse(open.subpaths.single().closed)
        assertEquals("3 points, 1 corner of 4 points (3 segments)", 6, closed.subpaths.single().anchors.size)
        assertTrue(closed.subpaths.single().closed)
    }

    /** The paths of row [row]'s one vector layer, as v1.8 reads them, in drawing order. */
    private fun curvePaths(row: String): List<VPathV18> {
        val layer = v18Reads(row).layers.single { it.vectorFile != null }
        return VectorCodecV18.decode(resource("$row/${layer.vectorFile}")).objects.map { it as VPathV18 }
    }

    /** 5.2 row 10 (bevelled points of shapes), existing kinds only: owner A9, fixture by [writeBevelShapes]. */
    @Test
    fun row10BevelledShapePoints() {
        val dto = v18Reads("bevel-shapes")
        // A9: fully read and fully editable in v1.8. The bevelled points are plain sharp points of
        // the shape's own points (v1.7's ShapePoint, radius included): on a shape layer v1.8 saves
        // the shape back byte for byte; on a vector layer it reads every field as v1.9 does.
        assertEquals(listOf("Layer 1", ShapeTypeV18.RECTANGLE.label, VECTOR_LAYER), dto.layers.map { it.name })
        val rect = v18Shape("bevel-shapes", ShapeTypeV18.RECTANGLE.label, dto)
        val points = requireNotNull(rect.points) { "the rectangle keeps its points" }
        assertEquals("one corner bevelled into two points", 5, points.size)
        assertEquals("the two new points' own roundness 0", 2, points.count { it.radius == 0f })
        assertEquals("the other corners follow the shape's radius", 3, points.count { it.radius == null })
        assertTrue(points.none { it.smooth || it.handleIn != null || it.handleOut != null })
        val vec = dto.layers.single { it.name == VECTOR_LAYER }
        val objects = v18Objects("bevel-shapes", VECTOR_LAYER, dto)
        val hexagon = (objects.single() as VShapeV18).shape
        assertEquals(ShapeTypeV18.POLYGON, hexagon.type)
        val hexPoints = requireNotNull(hexagon.points) { "the hexagon keeps its points" }
        assertEquals("two corners bevelled with three segments, four points each", 12, hexPoints.size)
        assertEquals("the eight new points' own roundness 0", 8, hexPoints.count { it.radius == 0f })
        sameShapesAsV19("bevel-shapes", vec.vectorFile!!, objects)
    }

    /** 5.2 row 11, one-layer new canvases and "Picture" alone (existing format): owner A8, fixture by [writeNewCanvases]. */
    @Test
    fun row11OneLayerNewCanvases() {
        val dto = v18Reads("new-canvas")
        assertEquals("one layer", 1, dto.layers.size)
        // A8: and the imported picture's project ("Picture" alone): fully read, fully editable.
        for ((row, layer) in listOf("new-canvas" to "Layer 1", "new-canvas/transparent" to "Layer 1", "new-canvas/picture" to "Picture")) {
            val d = v18Reads(row)
            assertEquals("$row: one layer, as v1.8 reads it", listOf(layer), d.layers.map { it.name })
            assertEquals("$row: active", 0, d.activeLayerIndex)
            assertFalse("$row: no paper", text("$row/project.json").contains("\"paper\""))
        }
    }

    @Test
    fun row12TextArrangeOrder() {
        v18Reads("text-arrange", listOf("more"))
        // A3: v1.9's Bring forward stores the new picture order in the existing fields (`item`
        // and `more`, bottom first); v1.8 shows the saved pixels, drawn in that order, and edits
        // the bottom text, which is the one the arrange put there.
        val data = firstText("text-arrange", "Arranged")
        assertEquals("arranged by Bring forward", ARRANGED_ORDER, TextCodec.decodeAll(data).map { it.text })
        val first = requireNotNull(TextCodecV18.decode(data)) { "v1.8 reads the layer" }
        assertEquals("v1.8 edits the new bottom text", ARRANGED_ORDER[0], first.text)
        assertEquals(ARRANGE[1].cx, first.cx, 0.01f)
    }

    /** 5.2 row 12 (R17 edits), existing fields: owner A9, fixture by [writeShapeR17] (the tool driven below the UI). */
    @Test
    fun row12R17ShapeEdits() {
        val dto = v18Reads("shape-r17")
        // A9: identical in v1.8. Per-edit undo leaves no trace in the file: the shapes as last shown
        // (the undone edits gone, the redone one kept), in existing fields only; v1.8 saves the
        // shape layer back byte for byte and reads every field of the shape object as v1.9 does.
        assertEquals(listOf("Layer 1", ShapeTypeV18.RECTANGLE.label, VECTOR_LAYER), dto.layers.map { it.name })
        val rect = v18Shape("shape-r17", ShapeTypeV18.RECTANGLE.label, dto)
        assertBox("the rectangle as last edited", rect, 90f, 61f, 100f, 60f)
        assertEquals(0f, rect.rotation, 1e-4f)
        assertEquals("a box shape, no points of its own", null, rect.points)
        val vec = dto.layers.single { it.name == VECTOR_LAYER }
        val objects = v18Objects("shape-r17", VECTOR_LAYER, dto)
        val ellipse = (objects.single() as VShapeV18).shape
        assertEquals(ShapeTypeV18.ELLIPSE, ellipse.type)
        assertBox("the ellipse as last edited", ellipse, 190f, 150f, 60f, 60f)
        assertEquals("its typed rotation", 30f, ellipse.rotation, 1e-4f)
        sameShapesAsV19("shape-r17", vec.vectorFile!!, objects)
    }

    @Ignore("5.2 row 13, banded save (byte-identical files): owner A7b, un-ignored by the lead")
    @Test
    fun row13BandedSave() {
        v18Reads("banded/whole")
        val whole = text("banded/whole/files.txt").lines().filter { it.isNotBlank() }
        assertEquals("the same files", whole, text("banded/banded/files.txt").lines().filter { it.isNotBlank() })
        for (f in whole) assertArrayEquals("$f: byte for byte", resource("banded/whole/$f"), resource("banded/banded/$f"))
    }

    /** 5.2 row 14, prefs and tool settings: owner lead (F1a, AppSettings.kt, UiPrefs.kt), fixture by [writePrefs]. */
    @Test
    fun row14Prefs() {
        val files = text("prefs/files.txt").lines().filter { it.isNotBlank() }
        assertTrue("written outside any project", files.none { it.startsWith("projects/") || it.startsWith("files/projects/") })
        assertEquals("only the two preference files", listOf("shared_prefs/$SETTINGS_FILE.xml", "shared_prefs/${UiPrefs.FILE}.xml"), files)
        val xml = text("prefs/shared_prefs/$SETTINGS_FILE.xml")
        for (k in V19_SETTINGS_KEYS) assertTrue("$SETTINGS_FILE holds \"$k\"", xml.contains("name=\"$k\""))
        assertFalse("UiPrefs keeps its own file", xml.contains("name=\"${UiPrefs.STRIP_OPEN_ROWS}\""))
        val ui = text("prefs/shared_prefs/${UiPrefs.FILE}.xml")
        assertTrue(ui.contains("name=\"${UiPrefs.STRIP_OPEN_ROWS}\"") && ui.contains("name=\"${UiPrefs.OBJECT_BAR_MORE_OPEN}\""))
        // v1.8 never reads the new keys; its AppSettings reads the tool options' JSON with its own
        // fields (unknown keys ignored) and writes them back without the v1.9 ones.
        for (key in listOf(ToolSettingsV18.CURVE, ToolSettingsV18.POLYLINE, ToolSettingsV18.PATH)) {
            val json = text("prefs/$key.json")
            assertTrue("$key: the fixture has ownLayer on", json.contains("\"ownLayer\":true"))
            val back = ToolSettingsV18.write(ToolSettingsV18.curve(json))
            assertEquals("$key: v1.8 reads its own values and writes no ownLayer", ToolSettingsV18.write(CurveSettingsV18()), back)
        }
        val shape = text("prefs/${ToolSettingsV18.SHAPE}.json")
        assertTrue("the fixture has newLayerEachTime on", shape.contains("\"newLayerEachTime\":true"))
        assertEquals("v1.8 reads its own shape values and writes no newLayerEachTime", ToolSettingsV18.write(ShapeSettingsV18()), ToolSettingsV18.write(ToolSettingsV18.shape(shape)))
    }

    /**
     * 5.2 row 15 (crash reports), app files outside the projects: owner A7a, un-ignored by the lead
     * at A7a's merge; fixture by [writeCrashReports]. v1.8 lists and opens only `files/projects/`,
     * so it never sees a report, and the "diagnostics" preferences are a file of their own that
     * v1.8 never reads.
     */
    @Test
    fun row15CrashReports() {
        val files = text("crash/files.txt").lines().filter { it.isNotBlank() }
        assertTrue("written outside any project: $files", files.none { it.startsWith("projects/") || it.startsWith("files/projects/") })
        assertEquals("one report and the diagnostics preferences: $files", 2, files.size)
        val report = files.singleOrNull { Regex("""files/${CrashLog.DIR}/\d{13}-crash\.txt""").matches(it) }
        assertNotNull("the report is files/${CrashLog.DIR}/<13-digit millis>-crash.txt: $files", report)
        assertEquals("the diagnostics preferences, not v1.8's", "shared_prefs/${CrashLog.PREFS}.xml", files.single { it != report })
        val lines = text("crash/$report").lines()
        assertEquals("a report starts with its title", ReportText.TITLE, lines.first())
        assertTrue("a fatal crash", lines.contains("Kind: CRASH") && lines.contains("Fatal: yes"))
        assertTrue("its place", lines.contains("Where: compat row 15"))
        assertTrue("its stack", lines.contains("java.lang.RuntimeException: compat row 15"))
        assertTrue("the gallery's card was dismissed for it", text("crash/shared_prefs/${CrashLog.PREFS}.xml").contains("name=\"${CrashLog.SEEN_AT}\""))
        // v1.9 reads its own report back, from the folder only (no index, no preference).
        val dir = File(app.filesDir, CrashLog.DIR)
        CrashLog.deleteAll(app)
        dir.mkdirs()
        File(dir, report!!.substringAfterLast('/')).writeBytes(resource("crash/$report"))
        val back = CrashLog.reports(app).single()
        assertEquals(report.substringAfterLast('/').removeSuffix(".txt"), back.id)
        assertEquals(CrashReport.Kind.CRASH, back.kind)
        assertEquals("${ReportText.kindTitle(CrashReport.Kind.CRASH)}: RuntimeException", back.summary)
        CrashLog.deleteAll(app)
    }

    /** 5.2 row 15 (backup-state.json), app files outside the projects: owner A8, fixture by [writeBackupState]. */
    @Test
    fun row15BackupState() {
        assertTrue("written outside any project", text("backup-state/files.txt").lines().none { it.startsWith("projects/") })
        // A8: what choosing a folder writes into the app's data folder: the state alone, under
        // noBackupFilesDir, which v1.8 never reads.
        val files = text("backup-state/files.txt").lines().filter { it.isNotBlank() }
        assertEquals(listOf("no_backup/backup-state.json"), files)
    }

    /** 5.2 row 16, the backup folder (outside the app): owner A8, fixture by [writeBackupFolder]. */
    @Test
    fun row16BackupFolder() {
        assertTrue("format 1", text("backup-folder/brushwork-backup.json").contains("\"format\":1"))
        // A8: v1.8 cannot restore from it and leaves it untouched (it never opens the folder).
        assertTrue("settings.json format 1", text("backup-folder/settings.json").contains("\"format\":1"))
        val files = text("backup-folder/files.txt").lines().filter { it.isNotBlank() }
        assertTrue("the index and the settings: $files", files.containsAll(listOf("brushwork-backup.json", "settings.json")))
        assertTrue("a mirrored project: $files", files.any { it.endsWith("/project.json") })
        // The mirrored project.json is the project's own: v1.8 reads it as it reads the projects folder.
        assertEquals(1, v18Reads("backup-folder").layers.size)
    }

    // ------------------------------------------------------------------ the generator

    /**
     * The fixture writers, by row folder: each gets an empty folder to fill with v1.9 code (a
     * project saved by [saveProject], a payload, a `files.txt` listing what the row writes).
     * Each area adds its rows' writers.
     */
    private val generators: Map<String, (File) -> Unit> = mapOf(
        "crash" to ::writeCrashReports,
        "prefs" to ::writePrefs,
        "paper" to ::writePaper,
        "new-canvas" to ::writeNewCanvases,
        "backup-state" to ::writeBackupState,
        "backup-folder" to ::writeBackupFolder,
        "cut-pieces" to ::writeCutPieces,
        "curves-mixed" to ::writeMixedCurves,
        "bevel-curves" to ::writeBevelledCurves,
        "shapes-joined" to ::writeShapesJoined,
        "bevel-shapes" to ::writeBevelShapes,
        "shape-r17" to ::writeShapeR17,
        "layers-merged" to ::writeMergedLayers,
        "duplicates" to ::writeDuplicates,
        "text-justify" to ::writeTextJustify,
        "text-kerning" to ::writeTextKerning,
        "text-list" to ::writeTextList,
        "text-path-nodes" to ::writeTextPathNodes,
        "text-several" to ::writeTextSeveral,
        "text-transform" to ::writeTextTransform,
        "text-array-lift" to ::writeTextArrayLift,
        "text-arrange" to ::writeTextArrange,
    )

    /** `project.json` of the gallery's project [id], copied into [dir]. */
    private fun copyProjectJson(id: String, dir: File) {
        dir.mkdirs()
        File(app.filesDir, "projects/$id/project.json").copyTo(File(dir, "project.json"), overwrite = true)
    }

    /**
     * Row 9 (A8): a new 64 x 48 canvas on paper (R15), its `project.json`, and the Brushwork
     * payload of its SVG export as `payload.json`.
     */
    private fun writePaper(dir: File) {
        val repo = ProjectRepository(app)
        val id = runBlocking { repo.create(NewCanvasSpec("Paper", 64, 48, 300f, null, CanvasStart.PAPER)) }
        copyProjectJson(id, dir)
        val doc = runBlocking { repo.load(id) }
        val c = Smoke.controller(app, doc)
        val payload = runBlocking {
            ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build().payload
        }
        File(dir, "payload.json").writeBytes(Payload.toJson(requireNotNull(payload) { "the SVG export's payload" }))
    }

    /**
     * Row 11 (A8): the `project.json` of a new white canvas (here), of a transparent one
     * (`transparent/`) and of a project made from a picture (`picture/`), one layer each.
     */
    private fun writeNewCanvases(dir: File) {
        val repo = ProjectRepository(app)
        val white = runBlocking { repo.create(NewCanvasSpec("White", 64, 48, 300f, 0xFFFFFFFF.toInt(), CanvasStart.FILL)) }
        copyProjectJson(white, dir)
        val clear = runBlocking { repo.create(NewCanvasSpec("Transparent", 64, 48, 300f, null, CanvasStart.TRANSPARENT)) }
        copyProjectJson(clear, File(dir, "transparent"))
        val png = File(app.cacheDir, "qa19-compat-picture.png")
        val b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2050C0.toInt()) }
        png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val picture = runBlocking { repo.createFromImage(Uri.fromFile(png)) }
        copyProjectJson(picture, File(dir, "picture"))
        png.delete()
    }

    /**
     * Row 15 (A8): what choosing a backup folder writes into the app's data folder, as
     * `files.txt` (paths relative to it; the stand-in storage provider's folder left out).
     */
    private fun writeBackupState(dir: File) {
        BackupKit.freshInstall()
        BackupKit.newArtwork("Backed up", BackupKit.BLUE)
        BackupKit.idle()
        val fake = BackupKit.storage.relativeTo(app.dataDir).invariantSeparatorsPath + "/"
        val before = snapshot(app.dataDir)
        BackupKit.choose()
        BackupKit.idle()
        val after = snapshot(app.dataDir)
        val changed = after.filter { (path, bytes) -> !path.startsWith(fake) && before[path]?.contentEquals(bytes) != true }.keys.sorted()
        File(dir, "files.txt").writeText(changed.joinToString("\n", postfix = "\n"))
    }

    /**
     * Row 16 (A8): a backup folder with one artwork: its index, `settings.json`, the mirrored
     * `project.json` and `files.txt` (every file of the folder, relative to it).
     */
    private fun writeBackupFolder(dir: File) {
        BackupKit.freshInstall()
        val id = BackupKit.newArtwork("Backed up", BackupKit.BLUE)
        BackupKit.choose()
        BackupKit.idle()
        val root = BackupKit.rootDir()
        for (f in listOf("brushwork-backup.json", "settings.json")) File(root, f).copyTo(File(dir, f), overwrite = true)
        File(root, "$id/project.json").copyTo(File(dir, "project.json"), overwrite = true)
        val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        File(dir, "files.txt").writeText(files.joinToString("\n", postfix = "\n"))
    }

    /**
     * Row 10, merged layers (A4, R19): v1.9's "Merge down" keeping the layers' type, over the
     * raster "Background": two vector layers merged as objects (the upper one at 50 %, folded
     * into its object's opacity), two shape layers merged into one vector layer of shapes. (A
     * text merge writes row 5's `more`, A3's row.)
     */
    private fun writeMergedLayers(dir: File) {
        val kit = A4Kit(240, 180)
        try {
            val c = kit.controller()
            kit.vectorLayer(c, listOf(kit.box(20f, 20f, 80f, 70f)), "Curves")
            val upper = kit.vectorLayer(c, listOf(kit.box(100f, 30f, 150f, 80f, 0xFF3070D0.toInt())), "Curves 2")
            c.setLayerProps(upper, upper.props().copy(opacity = 0.5f))
            kit.settle(c)
            c.mergeDown(upper)
            kit.settle(c)
            kit.shapeLayer(c, kit.rect(60f, 130f, 50f, 30f), "Shapes")
            val top = kit.shapeLayer(c, kit.rect(160f, 130f, 40f, 40f, 0xFF30A060.toInt()), "Shapes 2")
            c.mergeDown(top)
            kit.settle(c)
            assertEquals(listOf("Background", "Curves", "Shapes"), c.doc.layers.map { it.name })
            assertTrue("kept their type", c.doc.layers.drop(1).all { it.isVectorLayer })
            saveProject(c.doc, dir)
        } finally {
            kit.close()
        }
    }

    /**
     * Row 10, duplicates (A4, R14): the selection bar's Duplicate (its controller entry,
     * [SelectionDuplicate.duplicate]; no UI needed) on a raster layer (the selected pixels drawn
     * again 16 px right and down) and on a vector layer (the touched object copied).
     */
    private fun writeDuplicates(dir: File) {
        val kit = A4Kit(240, 180)
        fun select(c: EditorController, l: Float, t: Float, r: Float, b: Float) =
            c.setSelection(Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false))
        try {
            val c = kit.controller()
            c.addLayerWithContent("Painted", "Add") { cv -> cv.drawRect(20f, 20f, 60f, 50f, Paint().apply { color = DUPLICATE_RED }) }
            select(c, 10f, 10f, 70f, 60f)
            assertEquals(16, SelectionDuplicate.offset(c))
            assertTrue("pixels duplicated", SelectionDuplicate.duplicate(c))
            kit.vectorLayer(c, listOf(kit.box(100f, 100f, 140f, 140f), kit.box(180f, 40f, 220f, 80f)), "Objects")
            select(c, 90f, 90f, 150f, 150f)
            assertTrue("an object duplicated", SelectionDuplicate.duplicate(c))
            kit.settle(c)
            assertEquals(listOf("Background", "Painted", "Objects"), c.doc.layers.map { it.name })
            saveProject(c.doc, dir)
        } finally {
            kit.close()
        }
    }

    /**
     * Row 15, crash reports (A7a's `CrashLog`, written by the lead at A7a's merge): one fatal
     * report as the uncaught handler writes it (synchronously, `files/diagnostics/reports/`), and
     * the gallery's card dismissed for it (the "diagnostics" preferences). `files.txt` lists every
     * file of the app's data folder those writes added or changed, copied alongside (as [writePrefs]).
     */
    private fun writeCrashReports(dir: File) {
        CrashLog.deleteAll(app)
        CrashLog.prefs(app).edit().clear().commit()
        CrashLog.resetRepeats()
        val before = snapshot(app.dataDir)
        CrashLog.record(RuntimeException("compat row 15"), "compat row 15", fatal = true)
        val id = CrashLog.reports(app).first().id
        CrashLog.markSeen(app, id)
        assertTrue("diagnostics committed", app.getSharedPreferences(CrashLog.PREFS, Context.MODE_PRIVATE).edit().commit())
        CrashLog.drain()
        val after = snapshot(app.dataDir)
        val changed = after.filter { (path, bytes) -> before[path]?.contentEquals(bytes) != true }.keys.sorted()
        assertTrue("the report was written: $changed", "files/${CrashLog.DIR}/$id.txt" in changed)
        assertTrue("the preferences were written: $changed", "shared_prefs/${CrashLog.PREFS}.xml" in changed)
        for (path in changed) File(app.dataDir, path).copyTo(File(dir, path), overwrite = true)
        File(dir, "files.txt").writeText(changed.joinToString("\n", postfix = "\n"))
    }

    /** Row 10 (A1): a Curve, a Polyline and a Path line drawn into one vector layer, saved. */
    private fun writeMixedCurves(dir: File) {
        val c = A1Tools.controller()
        for ((id, y) in listOf(ToolId.CURVE to 40f, ToolId.POLYLINE to 120f, ToolId.PATH to 200f)) {
            val t = c.curveTool(id)
            for (p in listOf(Vec2(40f, y), Vec2(180f, y + 50f), Vec2(320f, y))) assertTrue("$id", t.addAnchor(p))
            t.commit()
        }
        assertEquals(3, c.activeLayer.vector!!.objects.size)
        File(app.filesDir, "projects").deleteRecursively()
        saveProject(c.doc, dir)
    }

    /** Row 10 (A1): an open and a closed polyline with bevelled corners (R18), saved. */
    private fun writeBevelledCurves(dir: File) {
        val c = A1Tools.controller()
        val t = c.curveTool(ToolId.POLYLINE)
        c.settings.curveBevelAmount = 0.3f
        c.settings.curveBevelSegments = 2
        for (p in listOf(Vec2(40f, 60f), Vec2(120f, 140f), Vec2(200f, 60f), Vec2(280f, 140f))) assertTrue(t.addAnchor(p))
        t.setWidths(listOf(0, 1, 2, 3), floatArrayOf(1f, 2f, 0.5f, 1f))
        t.endNumericEdit()
        t.selectPoints(PointSelection.of(t.pointCount, 1, 2))
        assertTrue(t.bevelPoints())
        t.commit()
        c.settings.curveBevelSegments = 3
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 200f), Vec2(130f, 280f))) assertTrue(t.addAnchor(p))
        t.setClosed(true)
        t.selectPoints(PointSelection.of(t.pointCount, 0))
        assertTrue(t.bevelPoints())
        t.commit()
        assertEquals(2, c.activeLayer.vector!!.objects.size)
        File(app.filesDir, "projects").deleteRecursively()
        saveProject(c.doc, dir)
    }

    /**
     * Row 14 (lead, F1a): every v1.9 preference written by v1.9's code (AppSettings' new keys,
     * [UiPrefs], the curve and shape tool options with their new fields on), both preference
     * files committed. `files.txt` lists every file of the app's data folder those writes added
     * or changed, copied alongside; the tool options are also copied as `<key>.json`.
     */
    private fun writePrefs(dir: File) {
        val before = snapshot(app.dataDir)
        val settings = AppSettings(app)
        settings.drawPreview = AppSettings.DRAW_PREVIEW_EXACT
        settings.showFrameTimes = true
        settings.newCanvasBackground = AppSettings.NEW_CANVAS_PAPER
        settings.textNewLayerEachTime = true
        settings.curveBevelAmount = 0.3f
        settings.curveBevelSegments = 3
        settings.shapeBevelAmount = 0.25f
        settings.shapeBevelSegments = 2
        for (key in listOf(ToolSettingsV18.CURVE, ToolSettingsV18.POLYLINE, ToolSettingsV18.PATH)) {
            settings.putObject(key, CurveSettings.serializer(), CurveSettings(ownLayer = true))
        }
        settings.putObject(ToolSettingsV18.SHAPE, ShapeSettings.serializer(), ShapeSettings(newLayerEachTime = true))
        val ui = UiPrefs(app)
        ui.stripOpenRows = setOf("text.more", "shape.more")
        ui.objectBarMoreOpen = true
        for (file in listOf(SETTINGS_FILE, UiPrefs.FILE)) {
            assertTrue("$file committed", app.getSharedPreferences(file, Context.MODE_PRIVATE).edit().commit())
        }
        val after = snapshot(app.dataDir)
        val changed = after.filter { (path, bytes) -> before[path]?.contentEquals(bytes) != true }.keys.sorted()
        for (path in changed) File(app.dataDir, path).copyTo(File(dir, path), overwrite = true)
        File(dir, "files.txt").writeText(changed.joinToString("\n", postfix = "\n"))
        for (key in listOf(ToolSettingsV18.CURVE, ToolSettingsV18.POLYLINE, ToolSettingsV18.PATH, ToolSettingsV18.SHAPE)) {
            File(dir, "$key.json").writeText(requireNotNull(settings.prefs.getString(key, null)) { key })
        }
    }

    /**
     * Row 10 (cut pieces, A5): v1.9's partial eraser, with gestures on a real editor, cuts a
     * filled and stroked box, a filled and stroked ellipse of the Shape tool, a stroke across it,
     * a box with a live copy (the copy becomes a piece of its own) and, on a legacy shape layer, a
     * rectangle (the layer becomes a vector layer). Saved by [saveProject].
     */
    private fun writeCutPieces(dir: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val settings = AppSettings(app)
            settings.prefs.edit().clear().commit()
            val w = 320
            val h = 240
            val doc = Document("cut-pieces", "Cut pieces", w, h)
            doc.layers += Layer(doc.newLayerId(), CUT_VECTOR_LAYER, BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
            doc.activeLayerIndex = 0
            val c = EditorController(app, doc, scope, settings).also {
                it.viewTransform.set(Matrix())
                it.tools
            }
            VectorDrawState.of(c).cutDispatcher = Dispatchers.Unconfined
            val vec = doc.layers[0]
            val blue = 0xFF3070C0.toInt()
            fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
                0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
                fill = VPaint.Solid(blue), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
            )
            val n = 31
            val line = VStroke(
                0, preset = BrushLibrary.defaultBrush.copy(size = 8f), color = 0xFF203040.toInt(), seed = 5L, stylus = false,
                points = PackedPoints(FloatArray(n) { 180f + 120f * it / (n - 1) }, FloatArray(n) { 150f }, FloatArray(n) { 1f }),
            )
            val ellipse = VShape(0, shape = ShapeObject(ShapeType.ELLIPSE, cx = 230f, cy = 65f, w = 120f, h = 70f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, fillColor = blue))
            val copied = box(20f, 130f, 60f, 180f).copy(copies = CopyMaps.of(listOf(floatArrayOf(1f, 0f, 100f, 0f, 1f, 0f, 0f, 0f, 1f))))
            c.vectors.addObjects(vec, listOf(box(20f, 30f, 140f, 100f), ellipse, line, copied), "Add")
            c.selectLayer(vec)
            c.selectTool(ToolId.ERASER)
            c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
            VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
            fun swipe(x: Float, y0: Float, y1: Float) {
                c.pointerDown(ToolPoint(x, y0))
                c.pointerMove(ToolPoint(x, (y0 + y1) / 2))
                c.pointerMove(ToolPoint(x, y1))
                c.pointerUp(ToolPoint(x, y1))
                shadowOf(Looper.getMainLooper()).idle()
            }
            swipe(80f, 10f, 115f)
            swipe(230f, 10f, 200f)
            swipe(40f, 120f, 200f)
            assertEquals("the pieces: ${vec.vector!!.objects}", 9, vec.vector!!.objects.size)

            val rect = ShapeObject(ShapeType.RECTANGLE, cx = 160f, cy = 205f, w = 120f, h = 40f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, fillColor = blue)
            val shape = requireNotNull(c.addLayerWithContent(CUT_SHAPE_LAYER, "Add shape", shapeData = ShapeCodec.encode(rect), draw = ArraySources.shapeDraw(rect, ColorMode.RGB, w, h)))
            c.selectLayer(shape)
            swipe(160f, 170f, 235f)
            assertTrue("the shape layer is a vector layer now", shape.isVectorLayer && !shape.isShapeLayer)
            assertEquals(2, shape.vector!!.objects.size)
            saveProject(doc, dir)
        } finally {
            scope.cancel()
        }
    }

    /**
     * A9's rows: the Shape tool on a 320 x 240 document [id] ("Layer 1" selected and, when
     * [vectorLayer], "Vector 1" above it), the tool options as installed, snapping off, an
     * identity view (screen and document px the same). [write] makes the row's shapes; then the
     * document is saved by [saveProject] into [dir].
     */
    private fun withShapeTool(id: String, name: String, vectorLayer: Boolean, dir: File, write: (EditorController, ShapeTool) -> Unit) {
        val w = 320
        val h = 240
        val doc = Document(id, name, w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        if (vectorLayer) doc.layers += Layer(doc.newLayerId(), VECTOR_LAYER, BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 0
        val c = Smoke.controller(app, doc)
        try {
            c.viewTransform.set(Matrix())
            c.snapping.enabled = false
            c.color = 0xFF802000.toInt()
            c.tools
            c.selectTool(ToolId.SHAPE)
            write(c, c.tools.getValue(ToolId.SHAPE) as ShapeTool)
            shadowOf(Looper.getMainLooper()).idle()
            saveProject(doc, dir)
        } finally {
            c.dispose()
        }
    }

    /** A one-finger gesture through [pts] (document px), the main looper idled after it. */
    private fun shapeGesture(c: EditorController, vararg pts: Pair<Float, Float>) {
        c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) c.pointerMove(ToolPoint(pts[i].first, pts[i].second))
        c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Row 10 (shapes joined into a vector layer, S1; A9): a filled rectangle on a layer of its own
     * ("Rectangle", a legacy shape layer), an ellipse drawn while that layer is selected (it joins
     * it: the layer becomes a vector layer of two shape objects), and a filled star drawn on it.
     */
    private fun writeShapesJoined(dir: File) = withShapeTool("shapes-joined", "Joined shapes", vectorLayer = false, dir) { c, tool ->
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = A9_BLUE) }
        shapeGesture(c, 30f to 30f, 70f to 60f, 110f to 90f)
        tool.commit()
        val layer = c.activeLayer
        assertTrue("a shape layer of its own", layer.isShapeLayer && !layer.isVectorLayer)
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE) }
        shapeGesture(c, 160f to 120f, 190f to 150f, 220f to 180f)
        tool.commit()
        assertTrue("the ellipse joined the shape layer", layer.isVectorLayer && layer.shapeData == null)
        tool.update { it.copy(type = ShapeType.STAR, style = ShapeStyle.FILL) }
        shapeGesture(c, 200f to 20f, 240f to 50f, 280f to 80f)
        tool.commit()
        assertEquals(listOf("Layer 1", ShapeType.RECTANGLE.label), c.doc.layers.map { it.name })
        assertEquals(3, layer.vector!!.objects.filterIsInstance<VShape>().size)
    }

    /**
     * Row 10 (bevelled points of shapes, R18; A9): a rectangle with round corners on a layer of
     * its own whose top-left corner was made sharp and bevelled (Segments 1: two points, each of
     * its own roundness 0), and on "Vector 1" a filled hexagon with two corners bevelled with
     * Segments 3 (four points each).
     */
    private fun writeBevelShapes(dir: File) = withShapeTool("bevel-shapes", "Bevelled shapes", vectorLayer = true, dir) { c, tool ->
        tool.update {
            it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f, corner = CornerStyle.ROUND, cornerRadius = 12f)
        }
        shapeGesture(c, 30f to 30f, 70f to 60f, 110f to 90f)
        tool.setPointEditing(true)
        tool.selectPoints(PointSelection.of(tool.pointCount, 0))
        assertTrue(tool.typePointRoundness("0"))
        assertTrue("the bevel applies", tool.bevelPoints())
        tool.closeBevelPopup()
        assertEquals("one corner became two points", 5, tool.pointCount)
        tool.commit()
        assertTrue(c.activeLayer.isShapeLayer)
        val vec = c.doc.layers.single { it.name == VECTOR_LAYER }
        c.selectLayer(vec)
        tool.update { it.copy(type = ShapeType.POLYGON, sides = 6, style = ShapeStyle.STROKE_FILL, corner = CornerStyle.SHARP, fillColor = A9_BLUE) }
        shapeGesture(c, 160f to 120f, 200f to 160f, 240f to 200f)
        tool.setPointEditing(true)
        assertEquals(6, tool.pointCount)
        tool.selectPoints(PointSelection.of(6, 0, 2))
        assertTrue("the bevel applies", tool.bevelPoints())
        tool.changeBevelSegments(3)
        tool.closeBevelPopup()
        assertEquals("two corners became four points each", 12, tool.pointCount)
        tool.commit()
        assertEquals(12, (vec.vector!!.objects.single() as VShape).shape.points!!.size)
        assertEquals(listOf("Layer 1", ShapeType.RECTANGLE.label, VECTOR_LAYER), c.doc.layers.map { it.name })
    }

    /**
     * Row 12 (R17 edits; A9): shapes edited one step at a time, some edits undone. A new filled
     * rectangle moved, a typed height undone, a nudge undone and redone, placed on a layer of its
     * own; reopened, moved, the move undone, a typed width. On "Vector 1" an ellipse placed,
     * reopened, a typed rotation, a move undone.
     */
    private fun writeShapeR17(dir: File) = withShapeTool("shape-r17", "R17 edits", vectorLayer = true, dir) { c, tool ->
        fun box(what: String, cx: Float, cy: Float, w: Float, h: Float, rotation: Float = 0f) {
            val b = requireNotNull(tool.box) { "$what: a shape open" }
            assertEquals("$what: ${b}", listOf(cx, cy, w, h, rotation), listOf(b.cx, b.cy, b.w, b.h, b.rotationDeg))
        }
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = A9_BLUE) }
        shapeGesture(c, 30f to 30f, 70f to 60f, 110f to 90f)
        box("drawn", 70f, 60f, 80f, 60f)
        shapeGesture(c, 70f to 60f, 80f to 60f, 90f to 60f)
        box("moved", 90f, 60f, 80f, 60f)
        tool.place(tool.box!!.copy(h = 40f))
        c.undo()
        box("the typed height undone", 90f, 60f, 80f, 60f)
        tool.nudge(0, 1)
        c.undo()
        c.redo()
        box("the nudge redone", 90f, 61f, 80f, 60f)
        tool.commit()
        val layer = c.activeLayer
        assertTrue(layer.isShapeLayer)
        shapeGesture(c, 50f to 61f)
        assertSame("reopened by a tap on its left side", layer, tool.editingLayer)
        shapeGesture(c, 90f to 61f, 90f to 71f, 90f to 81f)
        c.undo()
        box("the move undone", 90f, 61f, 80f, 60f)
        tool.place(tool.box!!.copy(w = 100f))
        tool.commit()
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)

        val vec = c.doc.layers.single { it.name == VECTOR_LAYER }
        c.selectLayer(vec)
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE) }
        shapeGesture(c, 160f to 120f, 190f to 150f, 220f to 180f)
        tool.commit()
        shapeGesture(c, 160f to 150f)
        assertTrue("the ellipse reopened", tool.editingObject)
        tool.place(tool.box!!.copy(rotationDeg = 30f))
        shapeGesture(c, 190f to 150f, 200f to 150f, 210f to 150f)
        c.undo()
        box("the move undone", 190f, 150f, 60f, 60f, 30f)
        tool.commit()
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        assertEquals(listOf("Layer 1", ShapeType.RECTANGLE.label, VECTOR_LAYER), c.doc.layers.map { it.name })
    }

    /** Row 1 (A3): a justified paragraph in a 200 px box, its last line on the right. */
    private fun writeTextJustify(dir: File) {
        val spec = TextSpec(sizePx = 24f, align = TextAlign.END, justify = TextJustify.ON, box = TextBoxSpec(width = 200f))
        saveTexts(dir, "Justified" to TextItem(TEXT_PARAGRAPH, spec, cx = 120f, cy = 80f))
    }

    /**
     * Row 2 (A3): "Metrics" (Font kerning on) on Latin and Japanese letters, and "Off" ("Font
     * kerning" off, keeping "Optical" for when it is turned on again).
     */
    private fun writeTextKerning(dir: File) {
        val metrics = TextSpec(sizePx = 28f, kerningMode = TextKerningMode.METRICS_LATIN)
        val off = TextSpec(sizePx = 28f, fontKerning = false, kerningMode = TextKerningMode.OPTICAL)
        saveTexts(dir, "Metrics" to TextItem("AVATAR テキスト", metrics, cx = 120f, cy = 50f), "Off" to TextItem("AVATAR", off, cx = 120f, cy = 110f))
    }

    /**
     * Row 3 (A3): "Numbered" (Roman from III, larger and thicker markers in a serif font, a wide
     * gap) and "Bullets" (stars), each three paragraphs in a 200 px box.
     */
    private fun writeTextList(dir: File) {
        val numbered = TextListSpec(
            type = TextListType.NUMBERS, numbering = TextNumbering.UPPER_ROMAN, start = 3,
            markerScale = 1.2f, markerWeight = 0.5f, gapEm = 1f, markerFont = TextFont.SERIF,
        )
        val box = TextBoxSpec(width = 200f)
        saveTexts(
            dir,
            "Numbered" to TextItem(TEXT_LIST, TextSpec(sizePx = 16f, box = box, list = numbered), cx = 120f, cy = 45f),
            "Bullets" to TextItem(TEXT_LIST, TextSpec(sizePx = 16f, box = box, list = TextListSpec(type = TextListType.BULLETS, bullet = "★")), cx = 120f, cy = 115f),
        )
    }

    /**
     * Row 4 (A3): texts along edited points (R12): "Path nodes" (5 path nodes, the middle one a
     * corner), "Closed" (a circle as 8 closed path nodes) and "Two points" (a polyline of 2).
     */
    private fun writeTextPathNodes(dir: File) {
        val open = VSpline(
            listOf(VSplinePoint(20f, 60f), VSplinePoint(70f, 20f), VSplinePoint(120f, 60f, sharp = true), VSplinePoint(170f, 20f), VSplinePoint(220f, 60f)),
            order = 4,
        )
        val closed = requireNotNull(TextPathNodes.asPath(TextPathSpec(type = TextPathType.CIRCLE, cx = 120f, cy = 100f, radius = 40f, startAngleDeg = -90f)))
        val two = VSpline(listOf(VSplinePoint(20f, 150f), VSplinePoint(220f, 140f)), order = 2)
        fun along(text: String, nodes: VSpline) =
            TextItem(text, TextSpec(sizePx = 16f), cx = 120f, cy = 80f, path = TextPathNodes.withNodes(TextPathSpec(type = TextPathType.CURVE), nodes))
        saveTexts(dir, "Path nodes" to along("Along the path nodes", open), "Closed" to along("Round and round", closed), "Two points" to along("Two points", two))
    }

    /** Row 5 (A3): "Two texts" (one layer, two texts, R14) and "One text" (v1.8's format). */
    private fun writeTextSeveral(dir: File) {
        saveTextLayers(dir, "Two texts" to SEVERAL, "One text" to listOf(TextItem("Alone", TextSpec(sizePx = 20f), cx = 120f, cy = 140f)))
    }

    /** Row 6 (A3): "Three texts", for v1.8's Transform. */
    private fun writeTextTransform(dir: File) {
        saveTextLayers(dir, "Three texts" to SEVERAL + TextItem("Third", TextSpec(sizePx = 18f), cx = 180f, cy = 130f, rotationDeg = 10f))
    }

    /** Row 7 (A3): "Array source", two texts, for v1.8's array source and data lift. */
    private fun writeTextArrayLift(dir: File) {
        saveTextLayers(dir, "Array source" to SEVERAL)
    }

    /**
     * Row 12 (A3): "Arranged", [ARRANGE] drawn and stored by v1.9, then its bottom text opened in
     * the Text tool and moved up by Bring forward (past the text it overlaps): [ARRANGED_ORDER].
     */
    private fun writeTextArrange(dir: File) {
        val doc = Document("qa19-${dir.name}", dir.name, 240, 160, dpi = 144f)
        val layer = Layer(doc.newLayerId(), "Arranged", BitmapUtils.createLayerBitmap(doc.width, doc.height))
        for (item in ARRANGE) TextRenderer.drawItem(Canvas(layer.bitmap), item, TextRenderer.prepare(item), null)
        layer.textData = TextCodec.encodeAll(ARRANGE)
        doc.layers += layer
        val c = EditorController(app, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(app))
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        assertTrue(tool.editLayer(layer, index = 0))
        assertTrue("Bring forward", tool.arrangeOpen(1))
        tool.discard()
        assertEquals(ARRANGED_ORDER, TextCodec.decodeAll(layer.textData).map { it.text })
        saveProject(doc, dir)
    }

    /** A 240 x 160 document of one text layer per [texts] (name to text, its pixels drawn by v1.9), saved into [dir]. */
    private fun saveTexts(dir: File, vararg texts: Pair<String, TextItem>) =
        saveTextLayers(dir, *texts.map { (name, item) -> name to listOf(item) }.toTypedArray())

    /** A 240 x 160 document of one text layer per [layers] (name to its texts, bottom first, drawn by v1.9), saved into [dir]. */
    private fun saveTextLayers(dir: File, vararg layers: Pair<String, List<TextItem>>) {
        val doc = Document("qa19-${dir.name}", dir.name, 240, 160, dpi = 144f)
        for ((name, items) in layers) {
            val layer = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(doc.width, doc.height))
            for (item in items) TextRenderer.drawItem(Canvas(layer.bitmap), item, TextRenderer.prepare(item), null)
            layer.textData = TextCodec.encodeAll(items)
            doc.layers += layer
        }
        saveProject(doc, dir)
    }

    /** Every file under [root] by its path relative to it ('/'-separated), with its bytes. */
    private fun snapshot(root: File): Map<String, ByteArray> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }

    /** [doc] saved by v1.9's repository and copied, every file, into [dir]. */
    private fun saveProject(doc: Document, dir: File) {
        runBlocking { ProjectRepository(app).save(doc, null) }
        val saved = File(app.filesDir, "projects/${doc.id}")
        assertTrue("saved into $saved", saved.isDirectory)
        saved.copyRecursively(dir, overwrite = true)
    }

    /** `app/src/test/resources/qa19/compat`, from the module or the repository root. */
    private fun fixtureRoot(): File {
        val user = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val module = if (File(user, "src/test").isDirectory) user else File(user, "app")
        return File(module, "src/test/resources/qa19/compat")
    }

    @Ignore("Writes app/src/test/resources/qa19/compat/ with v1.9 code: run by the lead (or an area for its rows) to regenerate the fixtures")
    @Test
    fun generateFixtures() {
        val root = fixtureRoot()
        root.mkdirs()
        for ((row, write) in generators) {
            val dir = File(root, row)
            dir.deleteRecursively()
            dir.mkdirs()
            write(dir)
            println("[qa19 compat] wrote $row: ${dir.list()?.sorted()}")
        }
    }

    private companion object {
        /** The duplicated pixels of row 10's [writeDuplicates]. */
        const val DUPLICATE_RED = 0xFFE02020.toInt()

        /** AppSettings' preference file (v1.0 on). */
        const val SETTINGS_FILE = "brushwork_settings"

        /** Row 10 (cut pieces): the layers [writeCutPieces] cuts. */
        const val CUT_VECTOR_LAYER = "Vector 1"
        const val CUT_SHAPE_LAYER = "Shape"

        /** A9's rows: the vector layer of [withShapeTool], and the shapes' fill. */
        const val VECTOR_LAYER = "Vector 1"
        const val A9_BLUE = 0xFF3070C0.toInt()

        /** v1.8's vector JSON read strictly: a key or a value v1.8 does not know fails. */
        val STRICT_V18 = Json { allowSpecialFloatingPointValues = true }

        /** Row 1 (A3): the justified paragraph. */
        const val TEXT_PARAGRAPH = "Justified lines reach both edges of the box while the last line keeps its alignment."

        /** Row 3 (A3): three paragraphs and a blank one (neither marked nor counted). */
        const val TEXT_LIST = "Brushes\n\nLayers and text\nExport"

        /** Rows 5 to 7 (A3): two texts of one layer, bottom first. */
        val SEVERAL = listOf(
            TextItem("First text", TextSpec(sizePx = 22f), cx = 90f, cy = 40f),
            TextItem("Second", TextSpec(sizePx = 26f, color = 0xFFCC2222.toInt()), cx = 140f, cy = 90f, rotationDeg = -8f),
        )

        /** Row 12 (A3): three texts, bottom first; the first two overlap, the third is apart. */
        val ARRANGE = listOf(
            TextItem("Bottom", TextSpec(sizePx = 36f), cx = 90f, cy = 60f),
            TextItem("Middle", TextSpec(sizePx = 36f, color = 0xFF2266CC.toInt()), cx = 110f, cy = 72f),
            TextItem("Top", TextSpec(sizePx = 20f), cx = 190f, cy = 135f),
        )

        /** Row 12 (A3): [ARRANGE] after Bring forward of its bottom text. */
        val ARRANGED_ORDER = listOf("Middle", "Bottom", "Top")

        /** The keys v1.9 adds to [SETTINGS_FILE] (F1a, design 2.3.4). */
        val V19_SETTINGS_KEYS = listOf(
            "drawPreview", "showFrameTimes", "newCanvas.background", "text.newLayerEachTime",
            "curve.bevel.amount", "curve.bevel.segments", "shape.bevel.amount", "shape.bevel.segments",
        )
    }
}
