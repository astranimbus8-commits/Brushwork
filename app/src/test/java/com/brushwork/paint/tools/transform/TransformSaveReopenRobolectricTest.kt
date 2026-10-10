package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * v1.7 (item 11 and 16, design §3.11 b, §3.16) review: what the Transform tool commits saves and
 * reopens as it was, with no format change. A folder transformed as one (a pixel layer, a vector
 * layer in a folder inside it, a text layer kept as a text) reopens with the same tree, the same
 * layer data (the moved text and objects) and the same pixels; a Free deformed layer reopens with
 * its deformed pixels.
 */
@RunWith(RobolectricTestRunner::class)
class TransformSaveReopenRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 160
    private val h = 120

    @Before
    fun clean() {
        File(app.filesDir, "projects").deleteRecursively()
    }

    private val hi = TextItem("Hi", spec = TextSpec(sizePx = 24f), cx = 40f, cy = 95f)

    /** Text maps that move the centre (area D owns the real ones). */
    private object Maps : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = true
        override fun text(textData: String, m: FloatArray): String? = TextCodec.decode(textData)?.let { t ->
            TextCodec.encode(t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5]))
        }
        override fun shape(shapeData: String, m: FloatArray): String? = null
        override fun array(layer: Layer, m: FloatArray): LayerData? = null
    }

    private fun controller(doc: Document): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun tool(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        // Before the looper runs: the tool lifts the active layer when it opens.
        tool.dataMaps = { Maps }
        tool.snapToObjects = false
        idle()
        return tool
    }

    /** Saves [doc], reopens it and checks every layer: place in the tree, data and pixels. */
    private fun assertReopensAsSaved(doc: Document) {
        val repo = ProjectRepository(app)
        val loaded = runBlocking {
            repo.save(doc, null)
            repo.load(doc.id)
        }
        assertTrue(loaded.loadWarnings.toString(), loaded.loadWarnings.isEmpty())
        assertNull(LayerTree.check(loaded.layers))
        assertEquals(doc.layers.map { it.id to it.parentId }, loaded.layers.map { it.id to it.parentId })
        for ((a, b) in doc.layers.zip(loaded.layers)) {
            if (a.isFolder) {
                assertEquals(a.folder, b.folder)
                continue
            }
            assertEquals("${a.name}: data", a.dataSnapshot(), b.dataSnapshot())
            assertArrayEquals("${a.name}: pixels", pixels(a.bitmap), pixels(b.bitmap))
        }
    }

    @Test
    fun aTransformedFolderReopensAsSaved() {
        val doc = Document("transform-folder", "Folder", w, h)
        fun pixel(name: String, parent: Long) = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { it.parentId = parent }
        val bg = pixel("Background", Layer.ROOT_ID).also { it.bitmap.eraseColor(-1) }
        val folder = Layer.newFolder(doc.newLayerId(), "Folder")
        val inner = Layer.newFolder(doc.newLayerId(), "Inner").also { it.parentId = folder.id }
        val raster = pixel("Raster", folder.id).also { Canvas(it.bitmap).drawRect(Rect(20, 20, 60, 50), Paint().apply { color = 0xFFDD2211.toInt() }) }
        val vector = pixel("Vector", inner.id).also { it.vector = VectorContent.EMPTY }
        val text = pixel("Text", folder.id).also { l ->
            l.textData = TextCodec.encode(hi)
            TextRenderer.drawItem(Canvas(l.bitmap), hi, TextRenderer.prepare(hi), null)
        }
        doc.layers += listOf(bg, raster, vector, inner, text, folder)
        doc.activeLayerIndex = doc.layers.indexOf(folder)
        val c = controller(doc)
        val box = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(70f, 30f, true), VAnchor(100f, 30f, true), VAnchor(100f, 60f, true), VAnchor(70f, 60f, true)), closed = true)),
            fill = VPaint.Solid(0xFF2060C0.toInt()),
        )
        c.vectors.addObjects(vector, listOf(box), "Add")
        idle()

        val tool = tool(c)
        tool.start()
        assertEquals(TransformTool.Lifted.FOLDER, tool.lifted)
        tool.moveBy(14f, 9f)
        val steps = c.undoManager.undoCount
        tool.commit()
        idle()
        c.settleVectorWork()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals("the text stays a text, moved", TextCodec.encode(hi.copy(cx = hi.cx + 14f, cy = hi.cy + 9f)), text.textData)
        assertEquals(0xFFDD2211.toInt(), raster.bitmap.getPixel(40, 45))

        assertReopensAsSaved(c.doc)
    }

    @Test
    fun aFreeDeformedLayerReopensAsSaved() {
        val doc = Document("transform-mesh", "Mesh", 128, 128)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(128, 128)).also {
            Canvas(it.bitmap).apply {
                drawRect(Rect(20, 20, 60, 100), Paint().apply { color = 0xFFFF0000.toInt() })
                drawRect(Rect(60, 20, 100, 100), Paint().apply { color = 0xFF0000FF.toInt() })
            }
        }
        doc.layers += layer
        val c = controller(doc)
        val before = pixels(layer.bitmap)
        val tool = tool(c)
        tool.mode = TransformTool.Mode.MESH
        tool.setMeshCells(2, 2)
        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        tool.beginGroupEdit("Move points")
        tool.setGroupTransform(Affine2.translate(20f, 0f))
        tool.endGroupEdit()
        tool.commit()
        assertEquals(TransformTool.FREE_DEFORM_LABEL, c.undoManager.undoLabel)
        assertTrue("deformed", !before.contentEquals(pixels(layer.bitmap)))

        assertReopensAsSaved(c.doc)
    }
}
