package com.brushwork.paint.qa18

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.ImportTarget
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.pdf.ArrayPdfBytes
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa17.Qa17CompatArtwork.PREMUL
import com.brushwork.paint.qa17.Qa17CompatArtwork.assertSameLayers
import com.brushwork.paint.qa17.Qa17CompatArtwork.assertSameSelections
import com.brushwork.paint.qa17.Qa17CompatArtwork.differing
import com.brushwork.paint.qa17.Qa17CompatArtwork.picture
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v1.8 QA, compat cluster (I13, I15–I19; design §4.3): EVERY v1.8 datum of one artwork
 * ([Qa18CompatArtwork]: mirror copies on a Bézier curve, a polyline, a Path-tool path, a star
 * and a shape layer's ellipse; detached copies; four arrays with every field of a look, each with
 * its own Pace; every curve generator on both tools, one with copies; cogs with and without a
 * hole on a vector layer and as shape layers; Chromatic aberration and Glitch applied with fringe
 * colors and a static color; saved selections from Color select and the Magnetic lasso; the
 * Mirror ruler) survives, bit for bit:
 * - save and load (the same format 3, no warning, the same picture, copies and generators kept,
 *   every generator still its path's: the Curve tool's reopen check, I16);
 * - reopening in the editor and saving again unchanged (the same `project.json` but for its
 *   revision and date, the same files);
 * - "Duplicate" in the gallery;
 * - SVG and PDF export, then the payload imported into a new artwork of that size (saved
 *   selections and the symmetry setting are not part of the payload: design §7).
 * v1.8 adds no file family and no codec version: the files are the v1.7 families.
 */
@RunWith(RobolectricTestRunner::class)
class Qa18CompatRoundTripRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val root get() = File(app.filesDir, "projects")

    @Before
    fun setUp() {
        root.deleteRecursively()
    }

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun repo() = ProjectRepository(app)

    private fun files(id: String): Set<String> = File(root, id).list()!!.toSet()

    private fun projectJson(id: String) = File(root, "$id/${ProjectFormat.PROJECT_FILE}").readText()

    /** [json] without the two values a save of unchanged content may change. */
    private fun stable(json: String) = json.replace(Regex("\"revision\":\\d+"), "\"revision\":0").replace(Regex("\"modifiedAt\":\\d+"), "\"modifiedAt\":0")

    /** The v1.8 data of [d] that the tests below expect back: copies, generators, cogs, looks. */
    private fun assertV18Data(where: String, d: Document) {
        fun layer(name: String) = d.layers.single { it.name == name }
        val mirrored = layer("Mirrored").vector!!.objects
        assertEquals("$where: copies on the curve, polyline, spline and star", with(Qa18CompatArtwork) { listOf(MIRROR.count, ROTATION_4.count, KALEIDO_3.count, MIRROR.count) }, mirrored.map { ObjectCopies.count(it) })
        assertEquals("$where: the ellipse's copies", 4, ShapeCodec.decode(layer("Mirrored shape").shapeData)!!.copies.count)
        assertTrue("$where: detached", layer("Detached").vector!!.objects.let { o -> o.size == 2 && o.none { ObjectCopies.hasCopies(it) } })
        val gen = layer("Generated").vector!!.objects.filterIsInstance<VPath>()
        assertEquals("$where: every generator kept", Qa18CompatArtwork.GENERATED.map { it.third } + gen.last().generator, gen.map { it.generator })
        for (p in gen) assertTrue("$where: ${p.generator!!.kind} is still its path's (I16, the reopen check)", CurveGenerators.matches(p))
        assertEquals("$where: the generated rose's copies", 2, ObjectCopies.count(gen.last()))
        val cogs = layer("Cogs").vector!!.objects.map { (it as VShape).shape }
        assertEquals("$where: cogs", listOf(ShapeType.COG, ShapeType.COG, ShapeType.COG), cogs.map { it.type })
        assertEquals("$where: holes", listOf(0f, 0.5f, 0.4f), cogs.map { it.cogHole })
        assertEquals("$where: the holed cog's copies", 2, cogs[2].copies.count)
        for (n in listOf("Cog", "Holed cog")) assertEquals("$where: $n", ShapeType.COG, ShapeCodec.decode(layer(n).shapeData)!!.type)
        assertEquals("$where: the shape layer's hole", 0.55f, ShapeCodec.decode(layer("Holed cog").shapeData)!!.cogHole)
        for (n in listOf("Array 1", "Text array", "Shape array", "Vector array")) {
            val look = layer(n).array?.spec?.look
            assertTrue("$where: $n keeps its look", look != null && !look.isIdentity && look.tint.size == 3)
        }
    }

    @Test
    fun everyV18DatumSurvivesSaveReopenAndDuplicate() {
        val (c, _) = Qa18CompatArtwork.build(app)
        val doc = c.doc
        assertV18Data("built", doc)
        Qa18CompatShots.save("artwork", com.brushwork.paint.engine.Compositor(doc) { null }.renderFlattened())
        val before = picture(doc)
        runBlocking { repo().save(doc, null) }
        val json = projectJson(doc.id)
        assertEquals("no folder: format 1 (I14)", 1, ProjectFormat.json.parseToJsonElement(json).jsonObject.getValue("formatVersion").jsonPrimitive.int)
        val saved = files(doc.id)
        assertEquals("one container per array: $saved", 4, saved.count { ProjectFormat.isArrayFile(it) })
        assertEquals("one file per saved selection: $saved", 2, saved.count { it.startsWith("sel_") })
        assertTrue("v1.7's file families only: $saved", saved.all { it == ProjectFormat.PROJECT_FILE || it == ProjectFormat.THUMB_FILE || ProjectFormat.isDataFile(it) })

        // Load.
        val loaded = runBlocking { repo().load(doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertSameLayers("loaded", doc, loaded)
        assertSameSelections("loaded", doc, loaded)
        assertEquals(doc.symmetry, loaded.symmetry)
        assertEquals(doc.activeLayerIndex, loaded.activeLayerIndex)
        assertArrayEquals("the same picture", before, picture(loaded))
        assertV18Data("loaded", loaded)

        // Reopened in the editor (vectors and arrays settle) and saved again with no edit.
        val c2 = Smoke.controller(app, loaded)
        Qa18CompatArtwork.settle(c2)
        assertEquals("no edit on opening", 0, c2.editCount)
        assertSameLayers("reopened", doc, loaded)
        runBlocking { repo().save(loaded, null) }
        assertEquals("saved again unchanged", stable(json), stable(projectJson(doc.id)))
        assertEquals("the same files", saved, files(doc.id))
        Smoke.assertQuiet(c2, "reopened")
        c2.dispose()

        // "Duplicate": every file copied, the copy opens as the original.
        val copyId = runBlocking { repo().duplicate(doc.id) }
        val copy = runBlocking { repo().load(copyId) }
        assertEquals(emptyList<String>(), copy.loadWarnings)
        assertEquals("Compat 18 copy", copy.name)
        assertSameLayers("duplicate", doc, copy)
        assertSameSelections("duplicate", doc, copy)
        assertEquals(doc.symmetry, copy.symmetry)
        assertArrayEquals("the copy's picture", before, picture(copy))
        assertV18Data("duplicate", copy)
        assertEquals("the copy has the same files", saved.filter { it != ProjectFormat.THUMB_FILE }.toSet(), files(copyId).filter { it != ProjectFormat.THUMB_FILE }.toSet())
        c.dispose()
    }

    private fun export(c: EditorController, format: VectorFormat): ByteArray {
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(format), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { if (format == VectorFormat.SVG) SvgWriter(scene).write(out) else PdfWriter(scene).write(out) }
        return out.toByteArray()
    }

    private class Read(val payload: BrushworkPayload, val images: PayloadImport.Images)

    private fun read(bytes: ByteArray, format: VectorFormat): Read = if (format == VectorFormat.SVG) {
        val svg = SvgParser.parse(bytes)
        Read(svg.payload()!!) { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }
    } else {
        val reader = OwnPdfReader(ArrayPdfBytes(bytes))
        assertTrue(reader.hasPayload())
        Read(reader.payload()!!) { key -> reader.payloadImage(key) }
    }

    private fun importInto(c: EditorController, r: Read, replace: List<Layer>) {
        val d = c.doc
        val target = ImportTarget(d.width, d.height, d.dpi, d.colorMode, ImportLayers.room(c) + replace.size, c.maxLayers)
        val steps = c.undoManager.undoCount
        PayloadImport.apply(c, PayloadImport.prepare(r.payload, r.images, target), replace)
        assertEquals("one undo step", steps + 1, c.undoManager.undoCount)
    }

    @Test
    fun everyV18DatumSurvivesSvgAndPdfReimport() {
        val (c, _) = Qa18CompatArtwork.build(app)
        val doc = c.doc
        val before = picture(doc)
        for (format in listOf(VectorFormat.SVG, VectorFormat.PDF)) {
            val r = read(export(c, format), format)
            assertEquals("$format: payload v2 (live arrays)", 2, r.payload.version)
            // A new artwork of the payload's size (as the gallery's "Open"): its two layers replaced.
            val fresh = Smoke.document(r.payload.width, r.payload.height, layers = 2, whiteBottom = true)
            val c2 = Smoke.controller(app, fresh)
            importInto(c2, r, fresh.layers.toList())
            Qa18CompatArtwork.settle(c2)
            assertSameLayers("$format", doc, c2.doc, pixelTol = PREMUL, vectorTol = 1)
            assertEquals("$format: the active layer", doc.activeLayerIndex, c2.doc.activeLayerIndex)
            assertEquals("$format: the same picture", 0, differing(before, picture(c2.doc), 2))
            assertV18Data("$format", c2.doc)
            Qa18CompatShots.save("artwork-${format.name.lowercase()}-import", com.brushwork.paint.engine.Compositor(c2.doc) { null }.renderFlattened())
            // Not in the payload (design §7): saved selections and the symmetry setting.
            assertTrue(c2.doc.savedSelections.isEmpty())
            Smoke.assertQuiet(c2, "$format imported")

            // The imported artwork saves and loads as it is.
            val id = "qa18-compat-import-$format"
            val imported = Document(id, "Imported", c2.doc.width, c2.doc.height, c2.doc.dpi).also { d ->
                d.layers += c2.doc.layers
                d.activeLayerIndex = c2.doc.activeLayerIndex
                for (l in d.layers) d.ensureNextLayerIdAbove(l.id)
            }
            runBlocking { repo().save(imported, null) }
            val back = runBlocking { repo().load(id) }
            assertEquals(emptyList<String>(), back.loadWarnings)
            assertSameLayers("$format imported, saved and loaded", imported, back)
            assertV18Data("$format imported, saved and loaded", back)
            c2.dispose()
        }
        c.dispose()
    }
}
