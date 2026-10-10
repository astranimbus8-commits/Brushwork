package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.MultiLayerRenderOverride
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 (item 11, design §3.11 a, I11) review: the Transform tool on a FOLDER respects every lock
 * of what it would change. A layer inside a locked folder inside it refuses the lift with the
 * folder's message; a layer locked (or its transparency) while the transform is pending refuses
 * the whole commit, with nothing changed; an arrayed layer whose map declines the transform
 * refuses it too (its copies would be baked). A vector layer inside is still hit where its
 * objects went.
 */
@RunWith(RobolectricTestRunner::class)
class TransformFolderGuardsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 160
    private val h = 120

    private val hi = TextItem("Hi", spec = TextSpec(sizePx = 24f), cx = 40f, cy = 95f)

    /**
     * Text and array maps that move the centre; the array map answers the identity only unless
     * [arrayMoves] (a map that declines what the user did).
     */
    private class Maps(var arrayMoves: Boolean) : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = true
        override fun text(textData: String, m: FloatArray): String? = TextCodec.decode(textData)?.let { TextCodec.encode(moved(it, m)) }
        override fun shape(shapeData: String, m: FloatArray): String? = null
        override fun array(layer: Layer, m: FloatArray): LayerData? {
            if (!arrayMoves && !m.contentEquals(DataRender.IDENTITY)) return null
            val d = layer.dataSnapshot()
            return d.copy(text = d.text?.let { text(it, m) })
        }

        fun moved(t: TextItem, m: FloatArray): TextItem = t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5])
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFF2060C0.toInt()),
    )

    /**
     * Bottom first: Background, [Raster (a red rect), [Vector (a filled box)] Inner, plus
     * [withArray] an arrayed text "Arrayed"] Folder. The folder is active.
     */
    private fun setup(withArray: Boolean = false): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        fun pixel(name: String, parent: Long) = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { it.parentId = parent }
        val bg = pixel("Background", Layer.ROOT_ID).also { it.bitmap.eraseColor(-1) }
        val folder = Layer.newFolder(doc.newLayerId(), "Folder")
        val inner = Layer.newFolder(doc.newLayerId(), "Inner").also { it.parentId = folder.id }
        val raster = pixel("Raster", folder.id).also { Canvas(it.bitmap).drawRect(Rect(20, 20, 60, 50), Paint().apply { color = 0xFFDD2211.toInt() }) }
        val vector = pixel("Vector", inner.id).also { it.vector = VectorContent.EMPTY }
        val arrayed = if (withArray) pixel("Arrayed", folder.id).also { l ->
            l.textData = TextCodec.encode(hi)
            l.array = LayerArray(ArraySpec(count = 3, relativeX = 1f))
            TextRenderer.drawItem(Canvas(l.bitmap), hi, TextRenderer.prepare(hi), null)
        } else null
        doc.layers += listOfNotNull(bg, raster, vector, inner, arrayed, folder)
        doc.activeLayerIndex = doc.layers.indexOf(folder)
        assertNull(LayerTree.check(doc.layers))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.vectors.addObjects(vector, listOf(box(70f, 30f, 100f, 60f)), "Add")
        return c
    }

    private fun EditorController.byName(name: String): Layer = doc.layers.first { it.name == name }

    private fun transform(c: EditorController, maps: DataMaps = Maps(arrayMoves = true)): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.dataMaps = { maps }
        tool.snapToObjects = false
        return tool
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Every layer's pixels and data (folders by their spec). */
    private fun picture(c: EditorController): List<Any?> = c.doc.layers.map { l ->
        if (l.isFolder) l.folder else Pair(pixels(l.bitmap).contentHashCode(), l.dataSnapshot())
    }

    /** The commit was refused with [message]: no step, nothing changed, the session over. */
    private fun assertRefused(c: EditorController, tool: TransformTool, steps: Int, before: List<Any?>, message: String) {
        assertEquals(message, c.message)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertEquals("nothing changed", before, picture(c))
        assertNull("the preview is gone", c.renderOverride)
        assertNull(tool.transformState)
        assertNull(LayerTree.check(c.doc.layers))
    }

    @Test
    fun aLayerInsideALockedFolderInsideRefusesTheLift() {
        val c = setup()
        c.byName("Inner").locked = true
        val before = picture(c)
        val steps = c.undoManager.undoCount
        val tool = transform(c)
        tool.start()
        assertNull("nothing lifted", tool.transformState)
        assertEquals("the inner folder's own message (I11)", FolderLabels.locked("Inner"), c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(before, picture(c))
    }

    @Test
    fun aLayerLockedWhileTheTransformIsPendingRefusesTheWholeCommit() {
        val c = setup()
        val tool = transform(c)
        tool.start()
        assertEquals(TransformTool.Lifted.FOLDER, tool.lifted)
        tool.moveBy(12f, 8f)
        val before = picture(c)
        val steps = c.undoManager.undoCount
        // The layer window locks a layer while the box is pending.
        c.byName("Raster").locked = true
        tool.commit()
        assertRefused(c, tool, steps, before, "Layer \"Raster\" is locked")
    }

    @Test
    fun aFolderInsideLockedWhilePendingRefusesTheWholeCommit() {
        val c = setup()
        val tool = transform(c)
        tool.start()
        tool.moveBy(12f, 8f)
        val before = picture(c)
        val steps = c.undoManager.undoCount
        c.byName("Inner").locked = true
        tool.commit()
        assertRefused(c, tool, steps, before, FolderLabels.locked("Inner"))
    }

    @Test
    fun transparencyLockedWhilePendingRefusesTheWholeCommit() {
        val c = setup()
        val tool = transform(c)
        tool.start()
        tool.moveBy(-6f, 4f)
        val before = picture(c)
        val steps = c.undoManager.undoCount
        c.byName("Vector").alphaLocked = true
        tool.commit()
        assertRefused(c, tool, steps, before, alphaLockedMessage(c.byName("Vector")))
    }

    @Test
    fun anArrayedLayerWhoseMapDeclinesRefusesTheWholeCommit() {
        val c = setup(withArray = true)
        val arrayed = c.byName("Arrayed")
        val maps = Maps(arrayMoves = false)
        val tool = transform(c, maps)
        tool.start()
        assertEquals(TransformTool.Lifted.FOLDER, tool.lifted)
        val preview = c.renderOverride as MultiLayerRenderOverride
        assertTrue("the arrayed layer is lifted with the others", arrayed in preview.layers)
        tool.moveBy(10f, -5f)
        val before = picture(c)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertRefused(c, tool, steps, before, TransformTool.ARRAY_REFUSAL)

        // The same transform with a map that takes it: ONE step, the array kept and mapped.
        maps.arrayMoves = true
        tool.start()
        tool.moveBy(10f, -5f)
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNotNull("still a live array", arrayed.array)
        assertEquals(TextCodec.encode(hi.copy(cx = hi.cx + 10f, cy = hi.cy - 5f)), arrayed.textData)
        c.undo()
        assertEquals("one undo restores everything", before, picture(c))
    }

    @Test
    fun aVectorLayerInsideIsHitWhereItsObjectsWent() {
        val c = setup()
        val vector = c.byName("Vector")
        val id = vector.vector!!.objects.single().id
        assertEquals(id, c.vectors.hitTest(vector, Vec2(85f, 45f), 1f)?.id)
        val tool = transform(c)
        tool.start()
        tool.moveBy(30f, 20f)
        tool.commit()
        assertEquals("hit at its new place", id, c.vectors.hitTest(vector, Vec2(115f, 65f), 1f)?.id)
        assertNull("no longer at its old place", c.vectors.hitTest(vector, Vec2(75f, 35f), 1f))
        c.undo()
        assertEquals("back after undo", id, c.vectors.hitTest(vector, Vec2(75f, 35f), 1f)?.id)
    }
}
