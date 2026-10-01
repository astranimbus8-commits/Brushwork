package com.brushwork.paint.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * v1.5 foundation (§5.7, §5.10 item 4): vector files, mask specs and adjustments in the project
 * format, the format version rule (I4), unreferenced-file cleanup, duplicate, and isolated load
 * failures.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectFormatV15RobolectricTest {
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

    private fun projectJson(id: String) = ProjectFormat.json.parseToJsonElement(File(dirOf(id), ProjectFormat.PROJECT_FILE).readText()).jsonObject

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun content(): VectorContent = VectorContent.EMPTY.plus(
        listOf(
            VStroke(0, preset = BrushLibrary.defaultBrush, color = 0xFF112233.toInt(), seed = 42, stylus = true,
                points = PackedPoints(floatArrayOf(1.25f, 30.5f, 60f), floatArrayOf(2f, 20.125f, 40f), floatArrayOf(0.1f, 0.7f, 1f))),
            VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(10f, 5f, sharp = true, outX = 2f, outY = 1f, width = 2f)), closed = false))),
            VShape(0, shape = ShapeObject(cx = 20f, cy = 20f, w = 10f, h = 8f)),
        ),
    ).first

    private fun sampleDoc(id: String = "v15-proj", adjustment: Boolean = false): Document {
        val doc = Document(id, "V15", w, h, 300f)
        val base = Layer(doc.newLayerId(), "Base", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(0xFF336699.toInt()) }
        val vec = Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawCircle(20f, 20f, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0000.toInt() })
            it.vector = content()
            it.mask = BitmapUtils.createMaskBitmap(w, h, 0xFF808080.toInt())
            it.maskSpec = MaskSpec(components = listOf(LinearMask(1, x0 = 0f, y0 = 0f, x1 = 10f, y1 = 10f)), nextId = 2)
        }
        doc.layers += base
        doc.layers += vec
        if (adjustment) {
            doc.layers += Layer(doc.newLayerId(), "Tone 1", BitmapUtils.createLayerBitmap(w, h)).also {
                it.adjustment = AdjustmentSpec(values = JsonObject(mapOf("exposure" to JsonPrimitive(0.5f))))
            }
        }
        doc.activeLayerIndex = 1
        return doc
    }

    @Test
    fun vectorMaskSpecAndAdjustmentRoundTrip() = runBlocking {
        val doc = sampleDoc(adjustment = true)
        repo.save(doc, null)
        val loaded = repo.load(doc.id)
        assertEquals(doc.layers.size, loaded.layers.size)
        for (i in doc.layers.indices) {
            assertEquals(doc.layers[i].dataSnapshot(), loaded.layers[i].dataSnapshot())
            assertArrayEquals(pixels(doc.layers[i].bitmap), pixels(loaded.layers[i].bitmap))
        }
        assertTrue(loaded.loadWarnings.isEmpty())
        val names = dirOf(doc.id).list()!!.toList()
        assertEquals("one vector file for the vector layer", 1, names.count { ProjectFormat.isVectorFile(it) })
        assertEquals("specs live in project.json", 2, projectJson(doc.id).getValue("formatVersion").jsonPrimitive.int)
    }

    @Test
    fun formatVersionIsTwoOnlyWithAnAdjustmentLayer() = runBlocking {
        val plain = sampleDoc("plain")
        repo.save(plain, null)
        assertEquals(1, projectJson("plain").getValue("formatVersion").jsonPrimitive.int)
        val adj = sampleDoc("adj", adjustment = true)
        repo.save(adj, null)
        assertEquals(2, projectJson("adj").getValue("formatVersion").jsonPrimitive.int)
        // Removing the adjustment layer writes version 1 again.
        adj.layers.removeAt(2)
        adj.touch()
        repo.save(adj, null)
        assertEquals(1, projectJson("adj").getValue("formatVersion").jsonPrimitive.int)
        // A new canvas (and a picture import) is version 1, readable by v1.4.
        val id = repo.create(NewCanvasSpec("New", 40, 30, 350f))
        assertEquals(1, projectJson(id).getValue("formatVersion").jsonPrimitive.int)
        // Newer than this version: refused with the existing message.
        val f = File(dirOf(id), ProjectFormat.PROJECT_FILE)
        f.writeText(f.readText().replace("\"formatVersion\": 1", "\"formatVersion\": 3").replace("\"formatVersion\":1", "\"formatVersion\":3"))
        try {
            repo.load(id)
            fail("a format 3 project was opened")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }

    @Test
    fun aV14ProjectLoadsUnchanged() = runBlocking {
        val id = "v14-fixture"
        val dir = dirOf(id).also { it.mkdirs() }
        // Exactly what v1.4 writes: no formatVersion field at all, no v1.5 fields.
        val row = ByteArray(w * 4) { i -> if (i % 4 == 3) 0xFF.toByte() else 0x40 }
        LayerCodec.writeRepeatedRow(File(dir, "layer_1_r1.bin"), w, h, row)
        LayerCodec.writeRepeatedRow(File(dir, "layer_2_r1.bin"), w, h, ByteArray(w * 4))
        File(dir, ProjectFormat.PROJECT_FILE).writeText(
            """{"id":"$id","name":"Old","width":$w,"height":$h,"dpi":350.0,"activeLayerIndex":1,"revision":1,"layers":[""" +
                """{"id":1,"name":"Background","props":{"name":"Background","opacity":1.0,"blendMode":"NORMAL","visible":true,"clipping":false,"alphaLocked":false,"locked":false,"maskEnabled":true},"file":"layer_1_r1.bin"},""" +
                """{"id":2,"name":"Text","props":{"name":"Text","opacity":1.0,"blendMode":"NORMAL","visible":true,"clipping":true,"alphaLocked":false,"locked":false,"maskEnabled":true},"file":"layer_2_r1.bin","textData":"{\"t\":1}"}]}""",
        )
        val doc = repo.load(id)
        assertEquals(2, doc.layers.size)
        assertEquals("{\"t\":1}", doc.layers[1].textData)
        assertTrue("a v1.4 clipping flag above a normal layer stays", doc.layers[1].clipping)
        assertTrue(doc.layers.all { it.vector == null && it.maskSpec == null && it.adjustment == null })
        assertTrue(doc.loadWarnings.isEmpty())
        assertEquals(0x40404040 or (0xFF shl 24), doc.layers[0].bitmap.getPixel(3, 3) or (0xFF shl 24))
        doc.touch()
        repo.save(doc, null)
        val json = projectJson(id)
        assertEquals(1, json.getValue("formatVersion").jsonPrimitive.int)
        assertFalse(dirOf(id).list()!!.any { ProjectFormat.isVectorFile(it) })
    }

    @Test
    fun vectorFilesAreWrittenWhenChangedAndCleanedUpWhenUnreferenced() = runBlocking {
        val doc = sampleDoc()
        repo.save(doc, null)
        val dir = dirOf(doc.id)
        val first = dir.list()!!.single { ProjectFormat.isVectorFile(it) }
        // A stray vector file (e.g. left by v1.4 saving a v1.5 project) is deleted on save.
        File(dir, "vector_99_r1.vec").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "notes.txt").writeText("keep")
        repo.save(doc, null)
        assertFalse(File(dir, "vector_99_r1.vec").exists())
        assertTrue("other files are left alone", File(dir, "notes.txt").exists())
        assertTrue("an unchanged layer keeps its file across saves", File(dir, first).exists())
        // Changed content: a new file, the old one goes.
        val vec = doc.layers[1]
        vec.vector = vec.vector!!.plus(listOf(VShape(0, shape = ShapeObject()))).first
        vec.markChanged()
        repo.save(doc, null)
        val second = dir.list()!!.single { ProjectFormat.isVectorFile(it) }
        assertFalse(first == second)
        assertEquals(vec.dataSnapshot(), repo.load(doc.id).layers[1].dataSnapshot())
        // A missing vector file is written again even when the layer didn't change.
        File(dir, second).delete()
        repo.save(doc, null)
        assertEquals(1, dir.list()!!.count { ProjectFormat.isVectorFile(it) })
        assertEquals(vec.vector, repo.load(doc.id).layers[1].vector)
        // Rasterized: the vector file is no longer referenced.
        vec.vector = null
        vec.markChanged()
        repo.save(doc, null)
        assertEquals(0, dir.list()!!.count { ProjectFormat.isVectorFile(it) })
    }

    @Test
    fun duplicateCopiesVectorFiles() = runBlocking {
        val doc = sampleDoc()
        repo.save(doc, null)
        val copyId = repo.duplicate(doc.id)
        val copy = repo.load(copyId)
        assertEquals(doc.layers[1].vector, copy.layers[1].vector)
        assertEquals(doc.layers[1].maskSpec, copy.layers[1].maskSpec)
    }

    @Test
    fun damagedDataOnlyAffectsItsOwnLayer() = runBlocking {
        val doc = sampleDoc(adjustment = true)
        doc.layers[2].adjustment = AdjustmentSpec(filterId = "adjust.from_the_future")
        doc.layers[2].clipping = true
        repo.save(doc, null)
        val dir = dirOf(doc.id)
        val vecFile = dir.list()!!.single { ProjectFormat.isVectorFile(it) }
        File(dir, vecFile).writeBytes(ByteArray(40) { it.toByte() })
        // A broken mask spec string.
        val pf = File(dir, ProjectFormat.PROJECT_FILE)
        val dto = ProjectFormat.read(dir)
        ProjectFormat.write(dir, dto.copy(layers = dto.layers.map { if (it.maskSpec != null) it.copy(maskSpec = "{broken") else it }))
        assertTrue(pf.exists())
        val loaded = repo.load(doc.id)
        val v = loaded.layers[1]
        assertNull("an unreadable vector file: a raster layer", v.vector)
        assertArrayEquals("its pixels (the cache) are intact", pixels(doc.layers[1].bitmap), pixels(v.bitmap))
        assertNull(v.maskSpec)
        assertNotNull("the mask pixels are intact", v.mask)
        val adj = loaded.layers[2]
        assertEquals("an unknown effect is kept", "adjust.from_the_future", adj.adjustment!!.filterId)
        assertFalse("adjustment layers never clip", adj.clipping)
        assertEquals(3, loaded.loadWarnings.size)
        assertArrayEquals(pixels(doc.layers[0].bitmap), pixels(loaded.layers[0].bitmap))
        // The unknown effect draws as pass-through: the same image as without the layer.
        val withLayer = Compositor(loaded) { null }.renderFlattened()
        loaded.layers.removeAt(2)
        val without = Compositor(loaded) { null }.renderFlattened()
        assertArrayEquals(pixels(without), pixels(withLayer))
    }
}
