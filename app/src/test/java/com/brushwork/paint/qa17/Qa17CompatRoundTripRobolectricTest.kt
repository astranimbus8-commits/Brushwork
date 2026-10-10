package com.brushwork.paint.qa17

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
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.qa17.Qa17CompatArtwork.PREMUL
import com.brushwork.paint.qa17.Qa17CompatArtwork.assertSameLayers
import com.brushwork.paint.qa17.Qa17CompatArtwork.assertSameSelections
import com.brushwork.paint.qa17.Qa17CompatArtwork.differing
import com.brushwork.paint.qa17.Qa17CompatArtwork.picture
import com.brushwork.paint.qa17.Qa17CompatArtwork.pixels
import com.brushwork.paint.qa17.Qa17CompatArtwork.settle
import com.brushwork.paint.qa17.Qa17CompatArtwork.tree
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.vector.VStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v1.7 QA, compat cluster (I11, I13, I14; design §4.3): EVERY v1.7 datum of one artwork
 * ([Qa17CompatArtwork]: nested, isolated and clipped folders; the four kinds of live array, one
 * in "Edit source pixels"; saved selections; per-point roundness; sharp Path points with their
 * widths; Fill / Both / Stroke paths; kerns with "Font kerning" off; a Rotation × 6 vector
 * stroke; a turned and scaled text and a skewed shape kept as data; the symmetry setting)
 * survives, bit for bit:
 * - save and load (format 3, no warning, the same picture);
 * - reopening in the editor and saving again unchanged (the same `project.json` but for its
 *   revision and date, the same files);
 * - "Duplicate" in the gallery (every container and selection file copied);
 * - SVG and PDF export, then the payload imported into a new artwork of that size (saved
 *   selections and the symmetry setting are not part of the payload: design §7), twice into the
 *   same artwork (the second copy's tree points at its own folders).
 */
@RunWith(RobolectricTestRunner::class)
class Qa17CompatRoundTripRobolectricTest {
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

    @Test
    fun everyV17DatumSurvivesSaveReopenAndDuplicate() {
        val started = System.nanoTime()
        val (c, made) = Qa17CompatArtwork.build(app)
        val doc = c.doc
        val before = picture(doc)
        runBlocking { repo().save(doc, null) }
        val json = projectJson(doc.id)
        assertEquals("folders: format 3", 3, ProjectFormat.json.parseToJsonElement(json).jsonObject.getValue("formatVersion").jsonPrimitive.int)
        val saved = files(doc.id)
        assertEquals("one container per array: $saved", 4, saved.count { ProjectFormat.isArrayFile(it) })
        assertEquals("one file per saved selection: $saved", 2, saved.count { it.startsWith("sel_") })

        // Load.
        val loaded = runBlocking { repo().load(doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertSameLayers("loaded", doc, loaded)
        assertSameSelections("loaded", doc, loaded)
        assertEquals(doc.symmetry, loaded.symmetry)
        assertEquals(doc.activeLayerIndex, loaded.activeLayerIndex)
        assertArrayEquals("the same picture", before, picture(loaded))
        val stroke = loaded.layers.single { it.name == made.getValue("symmetric").name }.vector!!.objects.single() as VStroke
        assertEquals("the stroke keeps its six maps", 6, stroke.copies.size)
        assertTrue("reopens in Edit source pixels", loaded.layers.single { it.id == made.getValue("arrayPixels").id }.array!!.spec.editingSource)

        // Reopened in the editor (vectors and arrays settle) and saved again with no edit: the
        // same project.json but for its revision and date, the same files, the same pixels.
        val c2 = Smoke.controller(app, loaded)
        settle(c2)
        assertSameLayers("reopened", doc, loaded)
        runBlocking { repo().save(loaded, null) }
        assertEquals("saved again unchanged", stable(json), stable(projectJson(doc.id)))
        assertEquals("the same files", saved, files(doc.id))
        Smoke.assertQuiet(c2, "reopened")

        // "Duplicate": every file copied, the copy opens as the original.
        val copyId = runBlocking { repo().duplicate(doc.id) }
        val copy = runBlocking { repo().load(copyId) }
        assertEquals(emptyList<String>(), copy.loadWarnings)
        assertEquals("Compat copy", copy.name)
        assertSameLayers("duplicate", doc, copy)
        assertSameSelections("duplicate", doc, copy)
        assertEquals(doc.symmetry, copy.symmetry)
        assertArrayEquals("the copy's picture", before, picture(copy))
        assertEquals("the copy has the same files", saved.filter { it != ProjectFormat.THUMB_FILE }.toSet(), files(copyId).filter { it != ProjectFormat.THUMB_FILE }.toSet())
        println("[qa17-compat] save/reopen/duplicate round trip: ${(System.nanoTime() - started) / 1_000_000} ms")
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
    fun everyV17DatumSurvivesSvgAndPdfReimport() {
        val started = System.nanoTime()
        val (c, _) = Qa17CompatArtwork.build(app)
        val doc = c.doc
        val before = picture(doc)
        for (format in listOf(VectorFormat.SVG, VectorFormat.PDF)) {
            val r = read(export(c, format), format)
            assertEquals("$format: payload v2", 2, r.payload.version)
            // A new artwork of the payload's size (as the gallery's "Open"): its two layers replaced.
            val fresh = Smoke.document(r.payload.width, r.payload.height, layers = 2, whiteBottom = true)
            val c2 = Smoke.controller(app, fresh)
            importInto(c2, r, fresh.layers.toList())
            settle(c2)
            assertSameLayers("$format", doc, c2.doc, pixelTol = PREMUL, vectorTol = 1)
            assertEquals("$format: the active layer", doc.activeLayerIndex, c2.doc.activeLayerIndex)
            assertEquals("$format: the same picture", 0, differing(before, picture(c2.doc), 2))
            // Not in the payload (design §7): saved selections and the symmetry setting.
            assertTrue(c2.doc.savedSelections.isEmpty())

            // Imported AGAIN into the same artwork (the layers stay): the second copy is a tree of
            // its own, its layers in ITS folders, the first copy untouched.
            val first = c2.doc.layers.toList()
            val firstTree = tree(c2.doc)
            val ids = first.map { it.id }.toSet()
            c2.selectLayer(first.first())
            importInto(c2, r, emptyList())
            settle(c2)
            assertNull("$format: a valid tree", LayerTree.check(c2.doc.layers))
            val second = c2.doc.layers.filter { it.id !in ids }
            assertEquals("$format: every layer again", first.size, second.size)
            val sub = Document("sub", "sub", doc.width, doc.height).also { it.layers += second }
            assertEquals("$format: the second copy's tree", firstTree, tree(sub))
            val kept = Document("kept", "kept", doc.width, doc.height).also { it.layers += c2.doc.layers.filter { l -> l.id in ids } }
            assertEquals("$format: the first copy's tree", firstTree, tree(kept))
            for (l in second) assertNotNull("$format/${l.name}: data", l.dataSnapshot())
            Smoke.assertQuiet(c2, "$format imported twice")
            // The imported artwork saves and loads as it is.
            val id = "qa17-compat-import-$format"
            val imported = Document(id, "Imported", c2.doc.width, c2.doc.height, c2.doc.dpi).also { d ->
                d.layers += c2.doc.layers
                d.activeLayerIndex = c2.doc.activeLayerIndex
                for (l in d.layers) d.ensureNextLayerIdAbove(l.id)
            }
            runBlocking { repo().save(imported, null) }
            val back = runBlocking { repo().load(id) }
            assertEquals(emptyList<String>(), back.loadWarnings)
            assertSameLayers("$format imported, saved and loaded", imported, back)
        }
        println("[qa17-compat] SVG + PDF payload round trip: ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
