package com.brushwork.paint.qa17

import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.qa17.Qa17CompatArtwork.pixels
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.ui.common.FolderLabels
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.7 QA, compat cluster (design §4.3 "What older versions do with a v1.7 project", README's
 * compatibility note): v1.7 projects after v1.6 has been at them. The JSON edits below are what
 * the REAL v1.6.0 build (`c72ea66`, run in a scratch checkout during QA) wrote: it decodes
 * `project.json` with `ignoreUnknownKeys` and writes back only the keys it knows, keeping
 * `formatVersion`, a folder entry's `"file": ""` and the text and shape strings as they were.
 *
 * - v1.6's gallery "Rename" of a project with folders (it can't open it, but lists and renames
 *   it) drops `folder`, `parentId` and `folderOpen`. v1.7 still opens the artwork (it used to
 *   refuse it with `Layer "Folder 2" refers to an invalid file`): every layer and its pixels,
 *   the folders empty at the top level, with "The folder structure was repaired".
 * - A v1.6 save of a project without folders drops the array, saved-selection and symmetry
 *   keys: v1.7 reads plain layers showing the copies, no saved selection, symmetry Off, and
 *   keeps the kerns and roundness v1.6 did not re-encode; its next save deletes the orphans.
 */
@RunWith(RobolectricTestRunner::class)
class Qa17CompatV16ReaderRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val root get() = File(app.filesDir, "projects")

    @Before
    fun setUp() {
        root.deleteRecursively()
    }

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun file(id: String) = File(root, "$id/${ProjectFormat.PROJECT_FILE}")

    /** `project.json` of [id] as v1.6 writes it back: without the keys it doesn't know (and [name] when given). */
    private fun rewriteAsV16(id: String, name: String? = null) {
        val layerKeys = setOf("parentId", "folder", "folderOpen", "array", "arrayFile")
        val projectKeys = setOf("selections", "symmetry", "nextSelectionId")
        val project = ProjectFormat.json.parseToJsonElement(file(id).readText()).jsonObject
        val layers = project.getValue("layers").jsonArray.map { e -> JsonObject(e.jsonObject.filterKeys { it !in layerKeys }) }
        val out = project.filterKeys { it !in projectKeys }.toMutableMap()
        out["layers"] = JsonArray(layers)
        if (name != null) out["name"] = JsonPrimitive(name)
        file(id).writeText(JsonObject(out).toString())
    }

    @Test
    fun aFolderArtworkRenamedInV16StillOpensWithEveryLayer() {
        val (c, _) = Qa17CompatArtwork.build(app, "qa17-compat-v16-rename")
        val doc = c.doc
        runBlocking { ProjectRepository(app).save(doc, null) }
        rewriteAsV16(doc.id, name = "Renamed in 1.6")
        val json = file(doc.id).readText()
        assertTrue("still format 3", json.contains("\"formatVersion\":3"))
        assertTrue("the folder entries keep \"file\": \"\"", json.contains("\"file\":\"\""))

        val loaded = runBlocking { ProjectRepository(app).load(doc.id) }
        assertEquals(listOf(FolderLabels.REPAIRED), loaded.loadWarnings)
        assertEquals("Renamed in 1.6", loaded.name)
        assertEquals("every layer, in order", doc.layers.map { it.id }, loaded.layers.map { it.id })
        assertEquals(doc.layers.map { it.name }, loaded.layers.map { it.name })
        assertEquals(doc.layers.map { it.props() }, loaded.layers.map { it.props() })
        assertEquals("the folders stay folders", doc.layers.map { it.isFolder }, loaded.layers.map { it.isFolder })
        assertTrue("v1.6 dropped the tree: all at the top level", loaded.layers.all { it.parentId == Layer.ROOT_ID })
        for ((e, a) in doc.layers.zip(loaded.layers)) if (!e.isFolder) assertArrayEquals("${e.name}: pixels", pixels(e.bitmap), pixels(a.bitmap))

        // Saved by v1.7 again: a valid format-3 project that opens without a warning.
        runBlocking { ProjectRepository(app).save(loaded, null) }
        assertEquals(3, ProjectFormat.json.parseToJsonElement(file(doc.id).readText()).jsonObject.getValue("formatVersion").jsonPrimitive.int)
        val again = runBlocking { ProjectRepository(app).load(doc.id) }
        assertEquals(emptyList<String>(), again.loadWarnings)
        assertEquals(loaded.layers.map { it.name }, again.layers.map { it.name })
        // The gallery counts the layers with pixels.
        assertEquals(doc.layers.count { !it.isFolder }, runBlocking { ProjectRepository(app).list() }.single { it.id == doc.id }.layerCount)
    }

    @Test
    fun aV16SaveKeepsThePixelsAndV17DeletesTheOrphans() {
        val (c, made) = Qa17CompatArtwork.build(app, "qa17-compat-v16-save", folders = false)
        val doc = c.doc
        runBlocking { ProjectRepository(app).save(doc, null) }
        assertEquals("no folder: format 1, v1.6 opens it", 1, ProjectFormat.json.parseToJsonElement(file(doc.id).readText()).jsonObject.getValue("formatVersion").jsonPrimitive.int)
        rewriteAsV16(doc.id)
        val dir = File(root, doc.id)
        fun orphans() = dir.list()!!.filter { ProjectFormat.isArrayFile(it) || it.startsWith("sel_") }.toSet()
        assertEquals(6, orphans().size)

        val loaded = runBlocking { ProjectRepository(app).load(doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertTrue("no saved selection", loaded.savedSelections.isEmpty())
        assertEquals("symmetry Off", SymmetrySettings(), loaded.symmetry)
        for (l in loaded.layers) assertNull("${l.name}: no array", l.array)
        for ((e, a) in doc.layers.zip(loaded.layers)) assertArrayEquals("${e.name}: pixels", pixels(e.bitmap), pixels(a.bitmap))
        for (kind in listOf("arrayPixels", "arrayText", "arrayShape", "arrayVector")) {
            assertFalse("$kind: a plain raster layer showing the copies", loaded.layers.single { it.id == made.getValue(kind).id }.hasEditableData)
        }
        val kerned = TextCodec.decode(loaded.layers.single { it.id == made.getValue("kerned").id }.textData)!!
        assertEquals("the kerns v1.6 kept as text", 2, kerned.kerns.size)
        assertFalse(kerned.spec.fontKerning)
        val rounded = ShapeCodec.decode(loaded.layers.single { it.id == made.getValue("rounded").id }.shapeData)!!
        assertEquals(listOf(12f, null, 30f, null), rounded.points!!.map { it.radius })

        runBlocking { ProjectRepository(app).save(loaded, null) }
        assertEquals("the orphans are gone", emptySet<String>(), orphans())
    }
}
