package com.brushwork.paint.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.7 (item 3, §3.3 b and d; area E, I14, V11, V12): arrays made the way the user makes them
 * (from a selection, from objects, a whole text or shape layer; one in "Edit source pixels")
 * survive save and load in every mode, count toward the memory rule again, and stay live: the
 * same edit after loading gives the same pixels. v1.6, which knows none of the array keys, reads
 * each arrayed layer as a plain raster layer showing its copies; when it drops the keys, v1.7
 * reads plain layers and its next save deletes the orphan containers. A damaged container loads
 * as pixels with the "could not be read" warning.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayPersistenceRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val root get() = File(app.filesDir, "projects")

    @Before
    fun setUp() {
        root.deleteRecursively()
    }

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val red = 0xFFDD2211.toInt()

    /** How v1.6 reads `project.json`: the keys it doesn't know are skipped. */
    private val v16Json = Json { ignoreUnknownKeys = true }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun settle(c: EditorController) {
        Smoke.pumpUntil { !c.vectors.isRendering && c.busyMessage == null }
        Smoke.pump(40)
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFF2244CC.toInt()),
    )

    /** The four arrays, one per source kind and mode; returns them by name. The raster one is in "Edit source pixels". */
    private fun arrays(c: EditorController): Map<String, Layer> {
        val doc = c.doc
        // Pixels: a selection on layer 2, in a circle (half a turn).
        val src = doc.layers[1]
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = red })
        c.selectLayer(src)
        c.setSelection(Selection.fromPath(Path().apply { addRect(10f, 20f, 70f, 80f, Path.Direction.CW) }, doc.width, doc.height, antiAlias = false), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        c.setSelection(null, recordUndo = false)
        val raster = c.activeLayer
        assertTrue(ArrayOps.edit(c, raster, ArraySpec(mode = ArrayMode.CIRCLE, count = 5, sweepDeg = 180f)))
        assertTrue(ArrayOps.editSource(c, raster))

        // Text: the whole layer, in a slanted line.
        val item = TextItem("Ab", spec = TextSpec(sizePx = 32f, color = 0xFF000000.toInt()), cx = 250f, cy = 50f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { cv ->
            TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
        }!!
        assertTrue(c.arrayWholeLayer(text))
        assertTrue(ArrayOps.edit(c, text, ArraySpec(count = 4, relativeX = 0.2f, relativeY = 1f)))

        // Shape: the whole layer, by transform (a fan).
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 300f, cy = 200f, w = 40f, h = 30f, style = ShapeStyle.FILL)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, doc.width, doc.height))!!
        assertTrue(c.arrayWholeLayer(shape))
        assertTrue(ArrayOps.edit(c, shape, ArraySpec(mode = ArrayMode.TRANSFORM, count = 4, moveX = 20f, moveY = 0f, turnDeg = 20f, scale = 0.9f)))

        // Vector: one of two objects, along a curve.
        val v = c.addVectorLayer()!!
        c.vectors.update(v, VectorContent.EMPTY.plus(listOf(box(40f, 200f, 70f, 230f), box(150f, 120f, 170f, 140f))).first, "Add")
        settle(c)
        assertTrue(c.arrayFromObjects(setOf(v.vector!!.objects.first().id)))
        settle(c)
        val vector = c.activeLayer
        val guide = VSubpath(listOf(VAnchor(55f, 215f), VAnchor(150f, 270f), VAnchor(250f, 215f)))
        assertTrue(ArrayOps.edit(c, vector, ArraySpec(mode = ArrayMode.CURVE, count = 4, guide = guide)))
        settle(c)
        c.vectors.flushPending()
        return mapOf("pixels" to raster, "text" to text, "shape" to shape, "vector" to vector)
    }

    private fun save(doc: Document) = runBlocking { ProjectRepository(app).save(doc, null) }

    private fun load(id: String): Document = runBlocking { ProjectRepository(app).load(id) }

    private fun dir(doc: Document) = File(root, doc.id)

    private fun arrayFiles(doc: Document) = dir(doc).list()!!.filter { ProjectFormat.isArrayFile(it) }.toSet()

    @Test
    fun everyKindRoundTripsAndStaysLive() {
        val c = Smoke.controller(app)
        val made = arrays(c)
        save(c.doc)
        assertEquals(4, arrayFiles(c.doc).size)
        val loaded = load(c.doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertEquals(c.doc.layers.map { it.name }, loaded.layers.map { it.name })
        val c2 = Smoke.controller(app, loaded)
        fun twin(l: Layer) = loaded.layers[c.doc.indexOf(l)]

        for ((kind, l) in made) {
            val back = twin(l)
            val a = l.array!!
            val b = back.array!!
            assertEquals(kind, a.spec, b.spec)
            assertEquals(kind, l.textData, back.textData)
            assertEquals(kind, l.shapeData, back.shapeData)
            assertEquals(kind, l.vector, back.vector)
            assertArrayEquals("$kind: the cache", pixels(l.bitmap), pixels(back.bitmap))
        }
        val px = made.getValue("pixels").array!!.pixels!!
        val px2 = twin(made.getValue("pixels")).array!!.pixels!!
        assertEquals(px.left, px2.left)
        assertEquals(px.top, px2.top)
        assertArrayEquals("the source pixels", pixels(px.bitmap), pixels(px2.bitmap))
        assertTrue("reopens in Edit source pixels", twin(made.getValue("pixels")).array!!.spec.editingSource)
        assertEquals("the source pixels count toward the memory rule", loaded.pixelLayerCount + 1, c2.effectiveLayerCount)
        assertEquals(c.effectiveLayerCount, c2.effectiveLayerCount)

        // Live after loading: the same edits give the same pixels.
        for (ctrl in listOf(c, c2)) {
            val layers = if (ctrl === c) made else made.mapValues { twin(it.value) }
            assertTrue(ArrayOps.finishSource(ctrl, layers.getValue("pixels")))
            for (l in layers.values) {
                val s = l.array!!.spec
                assertTrue(ArrayOps.edit(ctrl, l, s.copy(count = s.count + 1)))
            }
            settle(ctrl)
        }
        for ((kind, l) in made) {
            if (kind != "pixels") assertEquals(kind, l.array, twin(l).array)
            assertEquals(kind, l.array!!.spec, twin(l).array!!.spec)
            assertArrayEquals("$kind: edited after loading", pixels(l.bitmap), pixels(twin(l).bitmap))
        }
        val f1 = made.getValue("pixels").array!!.pixels!!
        val f2 = twin(made.getValue("pixels")).array!!.pixels!!
        assertEquals(f1.left, f2.left)
        assertArrayEquals("the finished source", pixels(f1.bitmap), pixels(f2.bitmap))
        Smoke.assertQuiet(c2, "loaded")
    }

    @Test
    fun aV16ReaderSeesPlainRasterLayersShowingTheCopies() {
        val c = Smoke.controller(app)
        val made = arrays(c)
        save(c.doc)
        val file = File(dir(c.doc), ProjectFormat.PROJECT_FILE)
        val v16 = v16Json.decodeFromString(V16Project.serializer(), file.readText())
        assertEquals("v1.6 opens it", 1, v16.formatVersion)
        val scratch = ByteArray(LayerCodec.byteLength(v16.width, v16.height))
        for ((kind, l) in made) {
            val e = v16.layers.single { it.id == l.id }
            assertNull("$kind: no text data", e.textData)
            assertNull("$kind: no shape data", e.shapeData)
            assertNull("$kind: no vector file", e.vectorFile)
            val cache = BitmapUtils.createLayerBitmap(v16.width, v16.height)
            LayerCodec.readInto(File(dir(c.doc), e.file ?: "layer_${e.id}.bin"), cache, scratch)
            assertArrayEquals("$kind: the pixel file is the cache", pixels(l.bitmap), pixels(cache))
        }

        // v1.6 edits and saves: the unknown keys are dropped. v1.7 then reads plain layers, and
        // its next save deletes the containers nobody lists any more.
        val stripped = stripArrayKeys(ProjectFormat.json.parseToJsonElement(file.readText()).jsonObject)
        file.writeText(stripped.toString())
        val loaded = load(c.doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        for ((kind, l) in made) {
            val back = loaded.layers.single { it.id == l.id }
            assertNull(kind, back.array)
            assertFalse("$kind: a plain raster layer", back.hasEditableData)
            assertArrayEquals("$kind: the copies are kept", pixels(l.bitmap), pixels(back.bitmap))
        }
        assertEquals(4, arrayFiles(c.doc).size)
        save(loaded)
        assertEquals("the orphans are gone", emptySet<String>(), arrayFiles(c.doc))
    }

    @Test
    fun aDamagedContainerKeepsTheCopiesAsPixelsWithAWarning() {
        val c = Smoke.controller(app)
        val made = arrays(c)
        save(c.doc)
        val raster = made.getValue("pixels")
        val entry = ProjectFormat.json.parseToJsonElement(File(dir(c.doc), ProjectFormat.PROJECT_FILE).readText()).jsonObject
            .getValue("layers").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.long == raster.id }
        val container = File(dir(c.doc), entry.getValue("arrayFile").jsonPrimitive.content)
        assertTrue(container.isFile)
        container.writeBytes(container.readBytes().copyOf((container.length() / 2).toInt()))

        val loaded = load(c.doc.id)
        assertEquals(listOf(ArrayLabels.damaged(raster.name)), loaded.loadWarnings)
        val back = loaded.layers.single { it.id == raster.id }
        assertNull(back.array)
        assertFalse(back.hasEditableData)
        assertArrayEquals("the copies are kept", pixels(raster.bitmap), pixels(back.bitmap))
        // The other arrays are unharmed; the damaged one has nothing left to edit.
        for (kind in listOf("text", "shape", "vector")) assertNotNull(kind, loaded.layers.single { it.id == made.getValue(kind).id }.array)
        val c2 = Smoke.controller(app, loaded)
        assertFalse(ArrayOps.edit(c2, back, ArraySpec(count = 2)))
        assertEquals(loaded.pixelLayerCount, c2.effectiveLayerCount)
        Smoke.assertQuiet(c2, "damaged")
    }

    /** [project] as v1.6 writes it back: every layer entry without the keys it doesn't know. */
    private fun stripArrayKeys(project: JsonObject): JsonObject {
        val layers = project.getValue("layers").jsonArray.map { e -> JsonObject(e.jsonObject.filterKeys { it != "array" && it != "arrayFile" }) }
        return JsonObject(project.toMutableMap().apply { put("layers", JsonArray(layers)) })
    }

    /** What v1.6 reads of `project.json` (the fields its loader needs for these layers). */
    @Serializable
    private data class V16Project(
        val formatVersion: Int = 1,
        val id: String,
        val width: Int,
        val height: Int,
        val layers: List<V16Entry> = emptyList(),
    )

    @Serializable
    private data class V16Entry(
        val id: Long,
        val name: String,
        val file: String? = null,
        val textData: String? = null,
        val shapeData: String? = null,
        val vectorFile: String? = null,
    )
}
