package com.brushwork.paint.qa18

import android.graphics.Matrix
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.Document
import com.brushwork.paint.qa16.V15Fixtures
import com.brushwork.paint.qa18.Qa18CompatV17Golden.crc
import com.brushwork.paint.qa18.Qa18CompatV17Golden.resource
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.8 QA (compat), design §8.1 and ARCHITECTURE "Formats and compatibility (v1.8)", checked on
 * what the REAL v1.7.0 build did with a v1.8 project ([Qa18CompatArtwork] saved by v1.8:
 * `qa18/compat/v18-written`; opened and saved by v1.7: `v17-untouched`, and after v1.7 edits:
 * `v17-edited`, both made by `qa18/compat/V18OpenedByV17Test.kt.txt` on the v1.7.0 sources):
 * - untouched: v1.7 shows v1.8's pixels (the same composite) and its save keeps every file but
 *   `project.json`, where only the arrays' looks are gone. Back in v1.8: mirror copies,
 *   generators, cogs (holes included), saved selections and the symmetry ruler as saved; each
 *   array without its look, its cache still showing the look (I1 until it is re-rendered, and
 *   then only within the copies' bounds: pixels of the old look's larger copies outside them
 *   stay, [Qa18CompatLookBackUiTest]).
 * - edited: what v1.7 re-encoded lost its v1.8 keys (copies, generators; a cog became a
 *   Rectangle with the cog's outline as custom points, without its hole; arrays drawn plain).
 *   v1.8 opens it as v1.7 left it, pixels and data, and draws that data as v1.7 drew it.
 * In both, v1.8's editor records nothing on opening and a save keeps `project.json` but its
 * revision.
 */
@RunWith(RobolectricTestRunner::class)
class Qa18CompatBackFromV17RobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val id = "qa18-compat-v18"
    private val arrays = listOf("Array 1", "Text array", "Shape array", "Vector array")

    @Before
    fun setUp() {
        File(app.filesDir, "projects").deleteRecursively()
    }

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private class Row(val id: Long, val name: String, val stored: Long, val fresh: Long?, val data: String)

    private class Probe(val composite: Long, val files: List<String>, val rows: List<Row>)

    /** v18probe.tsv (`layer id name stored`) or a v1.7 probe.tsv (`layer id name stored fresh data`). */
    private fun probe(path: String): Probe {
        var composite = 0L
        var files = emptyList<String>()
        val rows = ArrayList<Row>()
        for (line in String(resource(path), Charsets.UTF_8).lines()) {
            val p = line.split('\t')
            when (p[0]) {
                "composite" -> composite = p[1].toLong()
                "files" -> files = p[1].split(',')
                "layer" -> rows += Row(p[1].toLong(), p[2], p[3].toLong(), p.getOrNull(4)?.takeIf { it != "-" }?.toLong(), p.getOrNull(5) ?: "")
                "warnings" -> assertEquals("$path: v1.7 opened it without a warning", "[]", p[1])
            }
        }
        return Probe(composite, files, rows)
    }

    private fun install(files: Map<String, String>): File {
        val dir = File(app.filesDir, "projects/$id")
        dir.mkdirs()
        for ((name, from) in files) File(dir, name).writeBytes(resource(from))
        return dir
    }

    private fun load(): Document = runBlocking { ProjectRepository(app).load(id) }.also {
        assertEquals("v1.8 opens it without a warning", emptyList<String>(), it.loadWarnings)
    }

    private fun Document.layer(name: String) = layers.single { it.name == name }

    /** Opened in v1.8's editor: nothing recorded; saved: `project.json` byte for byte but the revision, the same files. */
    private fun openAndSave(doc: Document, dir: File) {
        val json = File(dir, ProjectFormat.PROJECT_FILE).readText()
        val files = dir.list()!!.toSortedSet()
        val c = Smoke.controller(app, doc)
        c.viewTransform.set(Matrix())
        Qa18CompatArtwork.settle(c)
        assertEquals("no edit on opening", 0, c.editCount)
        runBlocking { ProjectRepository(app).save(doc, null) }
        val revision = Regex("\"revision\":\\d+")
        assertEquals("project.json but the revision", json.replace(revision, ""), File(dir, ProjectFormat.PROJECT_FILE).readText().replace(revision, ""))
        assertEquals("the same files", files, dir.list()!!.filter { it != ProjectFormat.THUMB_FILE }.toSortedSet())
        Smoke.assertQuiet(c, "opened in v1.8")
        c.dispose()
    }

    @Test
    fun savedUntouchedByV17OnlyTheArraysLooksAreGone() {
        val v18 = probe("v18-written/v18probe.tsv")
        val v17 = probe("v17-untouched/probe.tsv")
        assertEquals("v1.7 shows v1.8's pixels", v18.composite, v17.composite)
        assertEquals("v1.7 shows every layer's pixels", v18.rows.map { it.id to it.stored }, v17.rows.map { it.id to it.stored })
        assertEquals("v1.7 keeps v1.8's files", v18.files, v17.files)
        for (n in arrays) assertTrue("$n: v1.7 reads the spec without a look", !v17.rows.single { it.name == n }.data.contains("look"))
        // What a re-render in v1.7 would draw: the source only, a cog without its hole, plain copies.
        val redrawnAlike = v17.rows.filter { it.fresh == it.stored }.map { it.name }
        assertEquals("v1.7 draws these as v1.8 did", listOf("Detached", "Cog"), redrawnAlike)

        // Both as v1.8 wrote them, then v1.7's project.json.
        val written = install(v18.files.associateWith { "v18-written/$it" })
        val before = load()
        val original = File(written, ProjectFormat.PROJECT_FILE).readText()
        File(written, ProjectFormat.PROJECT_FILE).writeBytes(resource("v17-untouched/project.json"))
        val doc = load()
        assertEquals("the same layers", before.layers.map { it.id to it.name }, doc.layers.map { it.id to it.name })
        for ((l, r) in doc.layers.zip(v18.rows)) assertEquals("${l.name}: v1.8's pixels", r.stored, crc(l.bitmap))
        assertEquals("v1.8's picture", v18.composite, crc(Compositor(doc) { null }.renderFlattened()))
        val revision = Regex("\"revision\":\\d+")
        val look = Regex(""",\\"look\\":\{.*?\\"tintStrength\\":[-0-9.Ee]+\}""")
        assertEquals("v1.8 wrote four looks", 4, look.findAll(original).count())
        assertEquals(
            "v1.7's project.json is v1.8's without the looks, but its revision",
            original.replace(look, "").replace(revision, ""),
            File(written, ProjectFormat.PROJECT_FILE).readText().replace(revision, ""),
        )

        // Kept: copies, generators, cogs and holes, detached copies, saved selections, the ruler.
        for (n in listOf("Mirrored", "Detached", "Generated", "Cogs")) assertEquals(n, before.layer(n).vector, doc.layer(n).vector)
        for (n in listOf("Mirrored shape", "Cog", "Holed cog")) assertEquals(n, before.layer(n).shapeData, doc.layer(n).shapeData)
        assertEquals(4, ShapeCodec.decode(doc.layer("Mirrored shape").shapeData)!!.copies.count)
        assertTrue(doc.layer("Mirrored").vector!!.objects.all { ObjectCopies.hasCopies(it) })
        for (p in doc.layer("Generated").vector!!.objects.filterIsInstance<VPath>()) assertTrue("a generator kept and still its path's", p.generator != null && CurveGenerators.matches(p))
        assertEquals(listOf(0f, 0.5f, 0.4f), doc.layer("Cogs").vector!!.objects.map { (it as VShape).shape.cogHole })
        assertEquals(before.savedSelections.map { it.name to it.packed.toList() }, doc.savedSelections.map { it.name to it.packed.toList() })
        assertEquals(before.symmetry, doc.symmetry)

        // Lost: each array's look; everything else of its spec, its source and its cache stay.
        for (n in arrays) {
            val b = before.layer(n).array!!
            val a = doc.layer(n).array!!
            assertTrue("$n had a look", b.spec.look != null)
            assertNull("$n: the look is gone", a.spec.look)
            assertEquals("$n: the rest of the spec", b.spec.copy(look = null), a.spec)
            assertArrayEquals("$n: the cache still shows the look", Qa18CompatV17Golden.pixels(before.layer(n).bitmap), Qa18CompatV17Golden.pixels(doc.layer(n).bitmap))
            if (n != "Text array" || V15Fixtures.isWindows) {
                val fresh = Qa18CompatV17Golden.fresh(doc, doc.layer(n))!!
                assertNotEquals("$n: until it is re-rendered, the cache is not what the spec draws (I1)", crc(fresh), crc(doc.layer(n).bitmap))
                assertEquals("$n: the spec draws as v1.7 would", v17.rows.single { it.name == n }.fresh, crc(fresh))
            }
        }
        openAndSave(doc, written)
    }

    @Test
    fun savedAfterV17EditsTheEditedLayersLostTheirV18Keys() {
        val v18 = probe("v18-written/v18probe.tsv")
        val v17 = probe("v17-edited/probe.tsv")
        val dir = install(v17.files.associateWith { "v17-edited/$it" })
        val doc = load()
        assertEquals("v1.7's layers", v17.rows.map { it.id to it.name }, doc.layers.map { it.id to it.name })
        for ((l, r) in doc.layers.zip(v17.rows)) assertEquals("${l.name}: v1.7's pixels", r.stored, crc(l.bitmap))
        assertEquals("v1.7's picture", v17.composite, crc(Compositor(doc) { null }.renderFlattened()))
        // Layers v1.7 never edited are v1.8's.
        for (n in listOf("Background", "Fringes", "Static", "Source", "Detached")) assertEquals(n, v18.rows.single { it.name == n }.stored, crc(doc.layer(n).bitmap))

        // Re-encoded by v1.7: no copies, no generator, cogs as custom-points rectangles without a hole.
        assertTrue(doc.layer("Mirrored").vector!!.objects.none { ObjectCopies.hasCopies(it) })
        assertTrue(doc.layer("Generated").vector!!.objects.all { it is VPath && it.generator == null })
        for (o in doc.layer("Cogs").vector!!.objects.filterIsInstance<VShape>()) {
            assertEquals(ShapeType.RECTANGLE, o.shape.type)
            assertEquals(56, o.shape.points?.size)
            assertEquals(0f, o.shape.cogHole)
            assertTrue(o.shape.copies.isEmpty)
        }
        for (n in listOf("Cog", "Holed cog")) {
            val s = ShapeCodec.decode(doc.layer(n).shapeData)!!
            assertEquals(n, ShapeType.RECTANGLE, s.type)
            assertEquals(n, 56, s.points?.size)
            assertEquals(n, 0f, s.cogHole)
        }
        assertTrue(ShapeCodec.decode(doc.layer("Mirrored shape").shapeData)!!.copies.isEmpty)
        for (n in arrays) assertNull("$n: no look", doc.layer(n).array!!.spec.look)

        // v1.8 draws what v1.7 wrote as v1.7 drew it (text with Windows fonts only).
        for (r in v17.rows) {
            if (r.fresh == null || (r.data.contains("text") && !V15Fixtures.isWindows)) continue
            assertEquals("${r.name}: v1.8 draws v1.7's data as v1.7 did", r.fresh, Qa18CompatV17Golden.fresh(doc, doc.layer(r.name))?.let { crc(it) })
        }
        // v1.7 redraws only part of an edited layer: a vector layer's changed tiles, a turned shape
        // layer within the shape's own bounds, a raster array within its plain copies' bounds
        // (ArrayRenders: dirty ∪ the old and new copies' bounds, without the look). So pixels of
        // v1.8's copies, holes and looked copies stay in the cache (counts printed) until v1.8
        // redraws the layer; the text, shape and vector arrays came out whole.
        val stale = v17.rows.filter { it.fresh != null && it.fresh != it.stored }.map { it.name }
        for (n in stale) {
            val s = Qa18CompatV17Golden.pixels(doc.layer(n).bitmap)
            val f = Qa18CompatV17Golden.fresh(doc, doc.layer(n))!!
            println("[qa18 compat] after v1.7: $n keeps ${s.indices.count { s[it] != f[it] }} px v1.7 did not redraw")
        }
        assertEquals(listOf("Array 1", "Mirrored", "Mirrored shape", "Generated", "Cogs", "Cog", "Holed cog"), stale)
        openAndSave(doc, dir)
    }
}
