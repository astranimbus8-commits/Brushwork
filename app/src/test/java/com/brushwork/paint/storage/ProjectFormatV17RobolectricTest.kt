package com.brushwork.paint.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.7 F1 (§4.3): format 3. Folders, arrays (every source kind), saved selections, the symmetry
 * ruler and the next selection id round-trip; `writtenVersion` is 1, 2 or 3 and only a folder
 * raises it to 3; a damaged array container or selection file loads with a warning and loses only
 * that data; a selection file another version left behind is written again; repeated ids in a file
 * with folders get fresh ones; content without v1.7 data writes none of the new keys (the I13
 * goldens check the bytes: `I13GoldensRobolectricTest`).
 */
@RunWith(RobolectricTestRunner::class)
class ProjectFormatV17RobolectricTest {
    private lateinit var context: Context
    private lateinit var repo: ProjectRepository
    private val w = 64
    private val h = 48

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "projects").deleteRecursively()
        repo = ProjectRepository(context)
    }

    private fun dirOf(id: String) = File(context.filesDir, "projects/$id")

    private fun projectText(id: String) = File(dirOf(id), ProjectFormat.PROJECT_FILE).readText()

    private fun projectJson(id: String) = ProjectFormat.json.parseToJsonElement(projectText(id)).jsonObject

    private fun entries(id: String) = projectJson(id).getValue("layers").jsonArray.map { it.jsonObject }

    private fun version(id: String) = projectJson(id).getValue("formatVersion").jsonPrimitive.int

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun files(id: String) = dirOf(id).list()!!.toSet()

    private fun raster(doc: Document, name: String, color: Int = 0): Layer =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { if (color != 0) it.bitmap.eraseColor(color) }

    private fun stroke(): VectorContent = VectorContent.EMPTY.plus(
        listOf(
            VStroke(0, preset = BrushLibrary.defaultBrush, color = 0xFF112233.toInt(), seed = 7, stylus = false,
                points = PackedPoints(floatArrayOf(2f, 30f), floatArrayOf(3f, 20f), floatArrayOf(1f, 1f))),
        ),
    ).first

    /**
     * Bottom first: Base; folder "Outer" (pass-through off, closed) owning "Inner" (default spec,
     * open) with "B" inside, and "A"; "Top".
     */
    private fun folderDoc(id: String = "v17-folders"): Document {
        val doc = Document(id, "Folders", w, h, 300f)
        val base = raster(doc, "Base", 0xFF336699.toInt())
        val outer = Layer.newFolder(doc.newLayerId(), "Outer", FolderSpec(passThrough = false)).also { it.folderOpen = false; it.opacity = 0.5f }
        val inner = Layer.newFolder(doc.newLayerId(), "Inner")
        val b = raster(doc, "B").also { Canvas(it.bitmap).drawCircle(20f, 20f, 9f, Paint().apply { color = 0xFFFF0000.toInt() }) }
        val a = raster(doc, "A", 0x8000FF00.toInt())
        val top = raster(doc, "Top")
        inner.parentId = outer.id
        b.parentId = inner.id
        a.parentId = outer.id
        doc.layers += listOf(base, b, inner, a, outer, top)
        doc.activeLayerIndex = 3
        return doc
    }

    @Test
    fun foldersRoundTripInFormatThree() = runBlocking {
        val doc = folderDoc()
        repo.save(doc, null)
        assertEquals(3, version(doc.id))
        val json = entries(doc.id)
        val outer = json[4]
        assertEquals("", outer.getValue("file").jsonPrimitive.content)
        assertFalse(outer.getValue("folder").jsonObject.getValue("passThrough").jsonPrimitive.boolean)
        assertFalse(outer.getValue("folderOpen").jsonPrimitive.boolean)
        assertFalse("a top-level entry has no parentId", "parentId" in outer)
        val inner = json[2]
        assertTrue("passThrough is written at its default", inner.getValue("folder").jsonObject.getValue("passThrough").jsonPrimitive.boolean)
        assertFalse("an open folder writes no folderOpen", "folderOpen" in inner)
        assertEquals(doc.layers[4].id, inner.getValue("parentId").jsonPrimitive.long)
        assertEquals(doc.layers[2].id, json[1].getValue("parentId").jsonPrimitive.long)
        for (k in listOf("folder", "folderOpen", "parentId", "array", "arrayFile")) assertFalse(k, k in json[0] || k in json[5])
        assertEquals("folders have no pixel files", 4, files(doc.id).count { ProjectFormat.isPixelFile(it) })

        val loaded = repo.load(doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertEquals(doc.layers.map { it.id }, loaded.layers.map { it.id })
        assertEquals(doc.layers.map { it.parentId }, loaded.layers.map { it.parentId })
        assertEquals(doc.layers.map { it.folder }, loaded.layers.map { it.folder })
        assertEquals(doc.layers.map { it.folderOpen }, loaded.layers.map { it.folderOpen })
        assertEquals(doc.layers.map { it.props() }, loaded.layers.map { it.props() })
        assertEquals(3, loaded.activeLayerIndex)
        for (i in doc.layers.indices) {
            if (doc.layers[i].isFolder) assertSame(Layer.FOLDER_BITMAP, loaded.layers[i].bitmap)
            else assertArrayEquals(pixels(doc.layers[i].bitmap), pixels(loaded.layers[i].bitmap))
        }
        assertEquals("the gallery counts layers, not folders", 4, repo.list().single().layerCount)

        // An unchanged resave writes the same entries; without folders the file is format 1 again.
        val before = entries(doc.id)
        repo.save(loaded, null)
        assertEquals(before, entries(doc.id))
        loaded.layers.removeAll { it.isFolder }
        loaded.layers.forEach { it.parentId = Layer.ROOT_ID }
        loaded.activeLayerIndex = 0
        repo.save(loaded, null)
        assertEquals(1, version(doc.id))
        assertFalse(projectText(doc.id).contains("parentId"))
        // The copy carries the folders' entries (no files of their own).
        val dupId = repo.duplicate(folderDoc("v17-dup-src").also { repo.save(it, null) }.id)
        assertEquals(6, repo.load(dupId).layers.size)
    }

    @Test
    fun writtenVersionIsOneTwoOrThree() = runBlocking {
        val doc = Document("v17-version", "V", w, h, 300f)
        doc.layers += raster(doc, "Base")
        assertEquals(1, ProjectFormat.writtenVersion(doc.layers))
        // Arrays, saved selections, symmetry and the next selection id never raise it.
        doc.layers[0].array = LayerArray(ArraySpec(), ArrayPixels(BitmapUtils.createLayerBitmap(4, 4), 0, 0))
        doc.savedSelections = listOf(SavedSelection.of(1, "Selection 1", Selection.all(w, h), 0)!!)
        doc.ensureNextSelectionIdAbove(1)
        doc.symmetry = SymmetrySettings(SymmetryType.MIRROR)
        repo.save(doc, null)
        assertEquals(1, version(doc.id))
        val adj = raster(doc, "Tone").also { it.adjustment = AdjustmentSpec(filterId = "adjust.tone") }
        doc.layers += adj
        repo.save(doc, null)
        assertEquals(2, version(doc.id))
        val folder = Layer.newFolder(doc.newLayerId(), "Folder 1")
        doc.layers += folder
        repo.save(doc, null)
        assertEquals(3, version(doc.id))
        doc.layers.remove(adj)
        repo.save(doc, null)
        assertEquals("a folder alone", 3, version(doc.id))
        doc.layers.remove(folder)
        repo.save(doc, null)
        assertEquals(1, version(doc.id))
    }

    /** Text, shape, vector and raster sources, bottom first; every layer shows its copies as pixels. */
    private fun arrayDoc(id: String = "v17-arrays"): Document {
        val doc = Document(id, "Arrays", w, h, 300f)
        val spec = ArraySpec(mode = ArrayMode.CIRCLE, count = 5, centerX = 30f, centerY = 20f, sweepDeg = 180f)
        doc.layers += raster(doc, "Text", 0x40112233).also {
            it.textData = "{\"t\":1}"
            it.array = LayerArray(spec.copy(editingSource = true))
        }
        doc.layers += raster(doc, "Shape", 0x40223344).also {
            it.shapeData = "{\"s\":2}"
            it.array = LayerArray(ArraySpec(count = 2))
        }
        doc.layers += raster(doc, "Vector", 0x40334455).also {
            it.vector = stroke()
            it.array = LayerArray(ArraySpec(mode = ArrayMode.TRANSFORM, moveX = 12f, turnDeg = 15f))
        }
        val source = BitmapUtils.createLayerBitmap(10, 8).also { it.eraseColor(0xFF00AA55.toInt()); it.setPixel(3, 2, 0x80FF0000.toInt()) }
        doc.layers += raster(doc, "Pixels", 0x40445566).also {
            it.array = LayerArray(ArraySpec(count = 4, editingSource = true), ArrayPixels(source, 5, -3))
        }
        return doc
    }

    @Test
    fun arraysRoundTripForEverySourceKind() = runBlocking {
        val doc = arrayDoc()
        repo.save(doc, null)
        assertEquals("arrays don't raise the format version", 1, version(doc.id))
        val json = entries(doc.id)
        for (e in json) {
            assertNotNull(e["array"])
            assertTrue(ProjectFormat.isArrayFile(e.getValue("arrayFile").jsonPrimitive.content))
            for (k in listOf("textData", "shapeData", "vectorFile")) assertNull("${e["name"]}: $k lives in the container", e[k]?.takeIf { it !is JsonNull })
        }
        assertEquals(4, files(doc.id).count { ProjectFormat.isArrayFile(it) })
        assertEquals("a vector source has no .vec file", 0, files(doc.id).count { ProjectFormat.isVectorFile(it) })

        val loaded = repo.load(doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val (text, shape, vector, px) = loaded.layers
        assertEquals("{\"t\":1}", text.textData)
        assertEquals("a non-raster source is never in Edit source pixels", doc.layers[0].array!!.spec.copy(editingSource = false), text.array!!.spec)
        assertEquals("{\"s\":2}", shape.shapeData)
        assertEquals(doc.layers[1].array, shape.array)
        assertEquals(doc.layers[2].vector, vector.vector)
        assertEquals(doc.layers[2].array, vector.array)
        val p = px.array!!.pixels!!
        assertEquals(doc.layers[3].array!!.spec, px.array!!.spec)
        assertEquals(5, p.left)
        assertEquals(-3, p.top)
        assertArrayEquals(pixels(doc.layers[3].array!!.pixels!!.bitmap), pixels(p.bitmap))
        for (i in doc.layers.indices) assertArrayEquals("the cache", pixels(doc.layers[i].bitmap), pixels(loaded.layers[i].bitmap))

        // Unchanged: the containers are reused; a changed layer writes a new one and the old goes.
        val first = files(doc.id).filter { ProjectFormat.isArrayFile(it) }.toSet()
        repo.save(loaded, null)
        assertEquals(first, files(doc.id).filter { ProjectFormat.isArrayFile(it) }.toSet())
        px.array = LayerArray(ArraySpec(count = 6), p)
        px.markChanged()
        repo.save(loaded, null)
        val second = files(doc.id).filter { ProjectFormat.isArrayFile(it) }.toSet()
        assertEquals(4, second.size)
        assertEquals(3, (first intersect second).size)
        assertEquals(6, repo.load(doc.id).layers[3].array!!.spec.count)
        // A missing container is written again even when the layer didn't change.
        File(dirOf(doc.id), entries(doc.id)[0].getValue("arrayFile").jsonPrimitive.content).delete()
        repo.save(loaded, null)
        assertEquals("{\"t\":1}", repo.load(doc.id).layers[0].textData)
        // A rasterized array: the container is no longer referenced.
        loaded.layers.forEach { it.array = null; it.textData = null; it.shapeData = null; it.vector = null; it.markChanged() }
        repo.save(loaded, null)
        assertEquals(0, files(doc.id).count { ProjectFormat.isArrayFile(it) })
        assertFalse(projectText(doc.id).contains("\"array"))
    }

    @Test
    fun aDamagedArrayKeepsItsCopiesAsPixels() = runBlocking {
        val doc = arrayDoc()
        repo.save(doc, null)
        val dir = dirOf(doc.id)
        val json = entries(doc.id)
        File(dir, json[0].getValue("arrayFile").jsonPrimitive.content).writeBytes(byteArrayOf(1, 2, 3))
        val pixelFile = File(dir, json[3].getValue("arrayFile").jsonPrimitive.content)
        pixelFile.writeBytes(pixelFile.readBytes().copyOf(pixelFile.length().toInt() - 9))
        File(dir, json[2].getValue("arrayFile").jsonPrimitive.content).delete()
        val f = File(dir, ProjectFormat.PROJECT_FILE)
        // An unreadable spec (the shape layer's).
        val shapeSpec = json[1].getValue("array").jsonPrimitive.content
        f.writeText(f.readText().replace(ProjectFormat.json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(shapeSpec)), "\"{broken\""))
        val loaded = repo.load(doc.id)
        assertEquals(listOf("Text", "Shape", "Vector", "Pixels").map { LayerEntries.arrayDamaged(it) }, loaded.loadWarnings)
        for (i in loaded.layers.indices) {
            val l = loaded.layers[i]
            assertNull(l.array)
            assertFalse("${l.name}: a plain raster layer", l.hasEditableData)
            assertArrayEquals("${l.name}: the copies stay", pixels(doc.layers[i].bitmap), pixels(l.bitmap))
        }
        // An adjustment layer never has an array.
        val adj = Document("v17-adj-array", "A", w, h, 300f)
        adj.layers += raster(adj, "Tone").also { it.adjustment = AdjustmentSpec(filterId = "adjust.tone") }
        repo.save(adj, null)
        val name = ProjectFormat.arrayFile(1, 9)
        File(dirOf(adj.id), name).writeBytes(byteArrayOf(0))
        val af = File(dirOf(adj.id), ProjectFormat.PROJECT_FILE)
        af.writeText(af.readText().replace("\"adjustment\":", "\"array\":\"{}\",\"arrayFile\":\"$name\",\"adjustment\":"))
        val back = repo.load(adj.id)
        assertEquals(listOf(LayerEntries.arrayDamaged("Tone")), back.loadWarnings)
        assertNotNull(back.layers[0].adjustment)
        assertNull(back.layers[0].array)
    }

    private fun selection(id: Long, name: String, left: Int, top: Int, revision: Long = 0): SavedSelection {
        val b = ByteArray(w * h)
        for (y in top until top + 10) for (x in left until left + 12) b[y * w + x] = ((x * 5 + y * 3) % 250 + 1).toByte()
        return SavedSelection.of(id, name, Selection.fromBytes(b, w, h), revision)!!
    }

    @Test
    fun savedSelectionsSymmetryAndTheNextIdRoundTrip() = runBlocking {
        val doc = Document("v17-sel", "S", w, h, 300f)
        doc.layers += raster(doc, "Base")
        val s2 = selection(2, "Selection 2", 20, 30, revision = 3)
        val s1 = selection(1, "Selection 1", 0, 0)
        doc.savedSelections = listOf(s2, s1)
        doc.ensureNextSelectionIdAbove(4)
        val quad = listOf(0f, 0f, 30f, 0f, 30f, 30f, 0f, 30f)
        doc.symmetry = SymmetrySettings(SymmetryType.PERSPECTIVE_ARRAY, 10f, 12f, 30f, 4, 40f, 50f, quad)
        repo.save(doc, null)
        assertEquals(setOf("sel_2_r3.bin", "sel_1_r0.bin"), files(doc.id).filter { ProjectFormat.isSelectionFile(it) }.toSet())
        assertArrayEquals(s2.packed, File(dirOf(doc.id), "sel_2_r3.bin").readBytes())
        val loaded = repo.load(doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertEquals(listOf(2L, 1L), loaded.savedSelections.map { it.id })
        for ((a, b) in doc.savedSelections.zip(loaded.savedSelections)) {
            assertEquals(a.name, b.name)
            assertEquals(a.bounds, b.bounds)
            assertEquals(a.revision, b.revision)
            assertArrayEquals(a.packed, b.packed)
        }
        assertEquals(5L, loaded.nextSelectionId)
        assertEquals(doc.symmetry, loaded.symmetry)
        // Removing one deletes its file at the next save; an unchanged one is not rewritten.
        val kept = File(dirOf(doc.id), "sel_2_r3.bin").also { it.setLastModified(1000L) }
        loaded.savedSelections = listOf(loaded.savedSelections[0])
        repo.save(loaded, null)
        assertEquals(setOf("sel_2_r3.bin"), files(doc.id).filter { ProjectFormat.isSelectionFile(it) }.toSet())
        assertEquals(1000L, kept.lastModified())
        // Damaged symmetry values are repaired on load.
        val f = File(dirOf(doc.id), ProjectFormat.PROJECT_FILE)
        f.writeText(f.readText().replace("\"divisions\":4", "\"divisions\":99"))
        assertEquals(SymmetrySettings.MAX_DIVISIONS, repo.load(doc.id).symmetry.divisions)
    }

    @Test
    fun aDamagedSelectionIsDroppedWithAWarning() = runBlocking {
        val doc = Document("v17-sel-bad", "S", w, h, 300f)
        doc.layers += raster(doc, "Base")
        doc.savedSelections = listOf(selection(9, "Selection 9", 5, 5), selection(4, "Selection 4", 30, 20), selection(3, "Selection 3", 1, 1))
        doc.ensureNextSelectionIdAbove(9)
        repo.save(doc, null)
        val dir = dirOf(doc.id)
        File(dir, "sel_4_r0.bin").writeBytes(File(dir, "sel_4_r0.bin").readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        File(dir, "sel_3_r0.bin").delete()
        val f = File(dir, ProjectFormat.PROJECT_FILE)
        f.writeText(f.readText().replace("\"nextSelectionId\":10", "\"nextSelectionId\":2"))
        val loaded = repo.load(doc.id)
        assertEquals(listOf(LayerEntries.selectionDropped("Selection 4"), LayerEntries.selectionDropped("Selection 3")), loaded.loadWarnings)
        assertEquals(listOf(9L), loaded.savedSelections.map { it.id })
        assertEquals("ids are never reused, dropped ones included", 10L, loaded.nextSelectionId)
        // Bounds that don't fit the document, a repeated id and a bad file name are dropped too.
        f.writeText(
            f.readText().replace("\"width\":12", "\"width\":999").replace("\"sel_9_r0.bin\"", "\"../sel_9_r0.bin\""),
        )
        assertTrue(repo.load(doc.id).savedSelections.isEmpty())
    }

    @Test
    fun aSelectionFileThatAnotherVersionLeftBehindIsWrittenAgain() = runBlocking {
        val doc = Document("v17-orphan", "O", w, h, 300f)
        doc.layers += raster(doc, "Base")
        doc.savedSelections = listOf(selection(1, "Selection 1", 0, 0))
        repo.save(doc, null)
        // v1.6 opens and saves it: the selections and the next id are gone, the file stays.
        val dir = dirOf(doc.id)
        val f = File(dir, ProjectFormat.PROJECT_FILE)
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), f.readText())
        f.writeText(ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto.copy(selections = emptyList(), nextSelectionId = 1)))
        File(dir, "sel_1_r0.bin").writeBytes(byteArrayOf(9, 9, 9))
        val reopened = repo.load(doc.id)
        assertEquals(1L, reopened.nextSelectionId)
        val fresh = selection(1, "Selection 1", 40, 30)
        reopened.savedSelections = listOf(fresh)
        repo.save(reopened, null)
        assertArrayEquals(fresh.packed, File(dir, "sel_1_r0.bin").readBytes())
        val back = repo.load(doc.id)
        assertEquals(emptyList<String>(), back.loadWarnings)
        assertEquals(fresh.bounds, back.savedSelections.single().bounds)
    }

    @Test
    fun repeatedIdsGetFreshOnesOnlyInAFileWithFolders() = runBlocking {
        val doc = Document("v17-dupids", "D", w, h, 300f)
        val a = raster(doc, "A", 0xFF0000FF.toInt())
        val b = Layer(a.id, "B", BitmapUtils.createLayerBitmap(w, h))
        val folder = Layer.newFolder(doc.newLayerId(), "Folder 1")
        doc.layers += listOf(a, b, folder)
        repo.save(doc, null)
        val loaded = repo.load(doc.id)
        val ids = loaded.layers.map { it.id }
        assertEquals(3, ids.toSet().size)
        assertEquals("the first keeps its id", a.id, ids[0])
        assertTrue("the repeat gets a new one", ids[1] > folder.id)
        assertEquals("B", loaded.layers[1].name)
        assertTrue(loaded.loadWarnings.isEmpty())
        // Without folders a repeated id loads as in v1.6.
        doc.layers.remove(folder)
        doc.touch()
        repo.save(doc, null)
        assertEquals(listOf(a.id, a.id), repo.load(doc.id).layers.map { it.id })
    }

    @Test
    fun aBrokenTreeIsRepairedWithAWarning() = runBlocking {
        val doc = folderDoc("v17-broken")
        repo.save(doc, null)
        val f = File(dirOf(doc.id), ProjectFormat.PROJECT_FILE)
        // "Top" claims to be in "Inner", which is not directly below it.
        val inner = doc.layers[2].id
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), f.readText())
        f.writeText(ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto.copy(layers = dto.layers.mapIndexed { i, e -> if (i == 5) e.copy(parentId = inner) else e })))
        val loaded = repo.load(doc.id)
        assertEquals(listOf("The folder structure was repaired"), loaded.loadWarnings)
        assertEquals(6, loaded.layers.size)
        assertNotEquals(inner, loaded.layers.single { it.name == "Top" }.parentId)
        // A folder entry's pixel, mask and data fields are ignored.
        val g = dto.layers[4].copy(file = "layer_99_r1.bin", hasMask = true, textData = "{}")
        f.writeText(ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto.copy(layers = dto.layers.mapIndexed { i, e -> if (i == 4) g else e })))
        val again = repo.load(doc.id)
        assertTrue(again.loadWarnings.isEmpty())
        assertSame(Layer.FOLDER_BITMAP, again.layers[4].bitmap)
        assertNull(again.layers[4].mask)
        assertNull(again.layers[4].textData)
    }

    @Test
    fun contentWithoutV17DataWritesNoneOfTheNewKeys() = runBlocking {
        val doc = Document("v17-plain", "P", w, h, 300f)
        doc.layers += raster(doc, "Base", 0xFFFFFFFF.toInt())
        doc.layers += raster(doc, "Text").also { it.textData = "{\"t\":1}" }
        doc.layers += raster(doc, "Vector").also { it.vector = stroke() }
        repo.save(doc, null)
        val text = projectText(doc.id)
        for (k in listOf("parentId", "folder", "folderOpen", "\"array\"", "arrayFile", "selections", "symmetry", "nextSelectionId")) {
            assertFalse("$k is not written", text.contains(k))
        }
        assertEquals(1, version(doc.id))
        // A cleared selection list and a symmetry set back to its defaults are not written either.
        doc.savedSelections = listOf(selection(1, "Selection 1", 0, 0))
        doc.ensureNextSelectionIdAbove(1)
        doc.symmetry = SymmetrySettings(SymmetryType.MIRROR)
        repo.save(doc, null)
        assertTrue(projectText(doc.id).contains("selections"))
        doc.savedSelections = emptyList()
        doc.symmetry = SymmetrySettings()
        repo.save(doc, null)
        val after = projectText(doc.id)
        assertFalse(after.contains("selections"))
        assertFalse(after.contains("symmetry"))
        assertTrue("the next id stays above every id given", after.contains("\"nextSelectionId\":2"))
        assertEquals(0, files(doc.id).count { ProjectFormat.isSelectionFile(it) })
    }
}
