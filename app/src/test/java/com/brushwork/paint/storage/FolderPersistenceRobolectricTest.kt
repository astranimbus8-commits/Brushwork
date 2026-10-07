package com.brushwork.paint.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.7 area A (item 8, §3.8): a folder tree made with the layer window's operations round-trips
 * through `ProjectRepository`: format 3 while it has a folder, the same tree, folder specs, open
 * states (opening and closing is no undo step, yet saved) and the same picture after loading; with
 * every folder ungrouped the file is format 1 again and carries none of the v1.7 keys. (The byte
 * equality of a no-folder `project.json` with the v1.6 goldens is `I13GoldensRobolectricTest`;
 * the format's edge cases are `ProjectFormatV17RobolectricTest`.)
 */
@RunWith(RobolectricTestRunner::class)
class FolderPersistenceRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    @After
    fun tearDown() = scope.cancel()

    private fun projectJson(id: String) =
        ProjectFormat.json.parseToJsonElement(File(context.filesDir, "projects/$id/${ProjectFormat.PROJECT_FILE}").readText()).jsonObject

    private fun version(id: String) = projectJson(id).getValue("formatVersion").jsonPrimitive.int

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun picture(doc: Document) = pixels(Compositor(doc) { null }.renderFlattened())

    private fun controller(doc: Document): EditorController {
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        return EditorController(context, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    @Test
    fun aTreeMadeInTheWindowRoundTripsAndUngroupedIsFormatOneAgain() = runBlocking {
        val doc = Document("a-folders", "Folders", w, h, 300f)
        val base = Layer(doc.newLayerId(), "Base", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0xFF336699.toInt()) })
        doc.layers += base
        val c = controller(doc)

        // Bottom first: Base; "Folder 1" (50 %) holding A and "Folder 2" (isolated, closed) holding B.
        c.selectLayer(base)
        val f1 = c.addFolder()!!
        val a = c.addLayer()!!
        val b = c.addLayer()!!
        val f2 = c.putInNewFolder(b)!!
        c.setFolderPassThrough(f2, false)
        c.setLayerProps(f1, f1.props().copy(opacity = 0.5f))
        Canvas(a.bitmap).drawCircle(20f, 20f, 12f, Paint().apply { color = 0xFFE03020.toInt() })
        Canvas(b.bitmap).drawRect(24f, 10f, 60f, 40f, Paint().apply { color = 0x8020C040.toInt() })
        val steps = c.undoManager.undoCount
        c.setFolderOpen(f2, false)
        assertEquals("closing a folder is no undo step", steps, c.undoManager.undoCount)
        assertEquals(listOf(base, a, b, f2, f1), doc.layers)
        assertEquals(listOf(Layer.ROOT_ID, f1.id, f2.id, f1.id, Layer.ROOT_ID), doc.layers.map { it.parentId })
        assertNull(LayerTree.check(doc.layers))
        val before = picture(doc)

        repo.save(doc, null)
        assertEquals("a folder: format 3", 3, version(doc.id))
        val loaded = repo.load(doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertEquals(doc.layers.map { it.id }, loaded.layers.map { it.id })
        assertEquals(doc.layers.map { it.name }, loaded.layers.map { it.name })
        assertEquals(doc.layers.map { it.parentId }, loaded.layers.map { it.parentId })
        assertEquals(doc.layers.map { it.folder }, loaded.layers.map { it.folder })
        assertEquals("the closed folder stays closed", doc.layers.map { it.folderOpen }, loaded.layers.map { it.folderOpen })
        assertFalse(loaded.layers[3].folderOpen)
        assertEquals(doc.layers.map { it.props() }, loaded.layers.map { it.props() })
        for (i in loaded.layers.indices) if (loaded.layers[i].isFolder) assertSame(Layer.FOLDER_BITMAP, loaded.layers[i].bitmap)
        assertArrayEquals("the same picture", before, picture(loaded))

        // Every folder ungrouped (the window's "Ungroup folder"): no folder, format 1, no v1.7 keys.
        val lc = controller(loaded)
        assertTrue(lc.ungroupFolder(loaded.layers.first { it.name == f2.name }))
        assertTrue(lc.ungroupFolder(loaded.layers.first { it.name == f1.name }))
        assertFalse(loaded.hasFolders)
        assertTrue(loaded.layers.all { it.parentId == Layer.ROOT_ID })
        repo.save(loaded, null)
        assertEquals("no folder: format 1", 1, version(doc.id))
        for (entry in projectJson(doc.id).getValue("layers").jsonArray.map { it.jsonObject }) {
            for (key in listOf("folder", "folderOpen", "parentId")) assertFalse("$key in $entry", key in entry)
        }
        assertEquals(listOf(base.name, a.name, b.name), repo.load(doc.id).layers.map { it.name })
    }
}
