package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.tools.coordinateSourceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 (items 1 and 9, design §3.1, §3.9 and §4.6): the Transform tool as the pill's source
 * (`PillPositionTool`, `ScaledTool`). Transforming, X / Y is the Numbers reference point, as
 * v1.6's adapter was, and Scale is percent of the box as lifted, about its centre (proportional
 * only for a text). In Free deform the pill falls back from one vertex ("Point 5") to the
 * selected vertices' box centre ("Selected points") to the whole mesh ("Center"); each drag or
 * typed value is ONE in-tool step, and Scale is percent of the box the selection had when taken.
 */
@RunWith(RobolectricTestRunner::class)
class TransformPillRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val red = 0xFFFF0000.toInt()

    /** A text map that moves the centre only (area D owns the real one). */
    private object MoveOnlyMaps : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = true
        override fun text(textData: String, m: FloatArray): String? = TextCodec.decode(textData)?.let { t ->
            TextCodec.encode(t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5]))
        }
        override fun shape(shapeData: String, m: FloatArray): String? = null
        override fun array(layer: Layer, m: FloatArray): LayerData? = null
    }

    /** A 128 x 128 document whose one layer holds a red square (20, 20)-(100, 100); [more] adds before the controller is made. */
    private fun setup(more: (Document) -> Unit = {}): Pair<EditorController, Layer> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 128, 128)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(128, 128))
        Canvas(layer.bitmap).drawRect(Rect(20, 20, 100, 100), Paint().apply { color = red })
        doc.layers += layer
        more(doc)
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return c to layer
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun transform(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        return (c.tools.getValue(ToolId.TRANSFORM) as TransformTool).also { it.snapToObjects = false }
    }

    private fun meshOf(c: EditorController): TransformTool = transform(c).also { t ->
        t.mode = TransformTool.Mode.MESH
        t.setMeshCells(2, 2)
        assertEquals(9, t.pointCount)
    }

    private fun assertAt(x: Float, y: Float, p: Vec2?, what: String = "position") {
        assertNotNull(what, p)
        assertEquals("$what x", x, p!!.x, 1e-2f)
        assertEquals("$what y", y, p.y, 1e-2f)
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun transformingThePillIsTheReferencePointAndScaleIsOfTheLiftedBox() {
        val (c, layer) = setup()
        val tool = transform(c)
        val pill = tool.pillPosition
        assertSame("routed generically, before the v1.6 adapter", pill, coordinateSourceOf(tool)!!.target)
        assertEquals(tool.anchor.label, pill.label)
        assertAt(60f, 60f, pill.position, "the box centre")
        assertEquals(tool.unit, tool.pillUnit)

        val scale = tool.objectScale
        assertNotNull(scale)
        assertFalse(scale!!.uniformOnly)
        assertAt(100f, 100f, scale.scalePercent, "as lifted")
        scale.setScale(150f, null)
        scale.endScaleEdit()
        assertAt(150f, 100f, scale.scalePercent, "Scale X 150")
        assertEquals(120f, tool.transformState!!.width, 1e-3f)
        assertEquals(80f, tool.transformState!!.height, 1e-3f)
        assertAt(60f, 60f, pill.position, "about the box centre")
        scale.setScale(100f, 100f)
        scale.endScaleEdit()
        assertAt(100f, 100f, scale.scalePercent)

        // X / Y moves the pending transform; the commit is the one step.
        pill.setPosition(70f, null)
        pill.endPositionEdit()
        pill.setPosition(null, 64f)
        pill.endPositionEdit()
        assertAt(70f, 64f, pill.position)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(red, layer.bitmap.getPixel(30, 24))
        assertEquals(0, layer.bitmap.getPixel(25, 22))
        assertNull("nothing lifted: no Scale row", tool.objectScale)
        assertNull("nothing lifted: the pill hides", pill.position)
    }

    @Test
    fun aTextIsScaledProportionallyOnly() {
        val item = TextItem("Hi", spec = TextSpec(sizePx = 32f), cx = 64f, cy = 64f)
        val (c, _) = setup { doc ->
            val l = Layer(doc.newLayerId(), "Text", BitmapUtils.createLayerBitmap(128, 128))
            l.textData = TextCodec.encode(item)
            TextRenderer.drawItem(Canvas(l.bitmap), item, TextRenderer.prepare(item), null)
            doc.layers += l
            doc.activeLayerIndex = doc.layers.indexOf(l)
        }
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.dataMaps = { MoveOnlyMaps }
        tool.start()
        assertEquals(TransformTool.Lifted.TEXT, tool.lifted)
        val scale = tool.objectScale!!
        assertTrue(scale.uniformOnly)
        scale.setScale(150f, null)
        scale.endScaleEdit()
        assertAt(150f, 150f, scale.scalePercent, "one value scales both")
        scale.setScale(null, 80f)
        assertAt(80f, 80f, scale.scalePercent)
    }

    @Test
    fun onTheMeshThePillFallsBackFromOneVertexToTheSelectionToTheWholeMesh() {
        val (c, _) = setup()
        val tool = meshOf(c)
        val pill = tool.pillPosition

        // No vertex selected: the whole mesh ("Center"); a typed X moves every vertex, one in-tool step.
        assertEquals(TransformTool.PILL_CENTER, pill.label)
        assertAt(60f, 60f, pill.position)
        pill.setPosition(70f, null)
        assertAt(70f, 60f, pill.position)
        for (i in 0 until tool.pointCount) assertEquals("vertex $i", 30f + 40f * (i % 3), tool.pointAt(i).x, 1e-2f)
        assertTrue(tool.canUndoStep)
        assertTrue(tool.undoStep())
        assertAt(60f, 60f, pill.position)
        assertFalse(tool.canUndoStep)

        // One vertex: "Point 5"; a slider drag is one step however many values it sends.
        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        assertEquals("Point 5", pill.label)
        assertAt(60f, 60f, pill.position)
        pill.beginPositionEdit()
        pill.setPosition(64f, null)
        pill.setPosition(68f, null)
        pill.setPosition(null, 50f)
        pill.endPositionEdit()
        assertAt(68f, 50f, tool.pointAt(4))
        assertAt(20f, 20f, tool.pointAt(0), "the others stay")
        assertTrue(tool.undoStep())
        assertAt(60f, 60f, tool.pointAt(4))
        assertFalse("one step", tool.canUndoStep)

        // Two or more: their box centre ("Selected points").
        tool.selectPoints(PointSelection.of(tool.pointCount, 0, 4))
        assertEquals(TransformTool.PILL_SELECTED_POINTS, pill.label)
        assertAt(40f, 40f, pill.position)
        pill.setPosition(null, 50f)
        assertAt(20f, 30f, tool.pointAt(0))
        assertAt(60f, 70f, tool.pointAt(4))
        assertAt(100f, 20f, tool.pointAt(2), "unselected")
        assertEquals("the selection is kept", listOf(0, 4), tool.pointSelection.indices)
    }

    @Test
    fun onTheMeshScaleIsOfTheBoxTheSelectionHadWhenTaken() {
        val (c, layer) = setup()
        val tool = meshOf(c)
        val scale = tool.objectScale!!
        assertFalse(scale.uniformOnly)
        assertAt(100f, 100f, scale.scalePercent, "the whole mesh")

        tool.selectPoints(PointSelection.of(tool.pointCount, 4))
        assertNull("one vertex has nothing to scale", scale.scalePercent)
        scale.setScale(200f, null)
        assertAt(60f, 60f, tool.pointAt(4))

        // The four corners: 80 x 80 about (60, 60).
        tool.selectPoints(PointSelection.of(tool.pointCount, 0, 2, 6, 8))
        assertAt(100f, 100f, scale.scalePercent)
        scale.setScale(150f, null)
        assertAt(0f, 20f, tool.pointAt(0))
        assertAt(120f, 100f, tool.pointAt(8))
        assertAt(60f, 60f, tool.pointAt(4), "unselected")
        assertAt(150f, 100f, scale.scalePercent)
        // A drag on Scale Y: one step, X kept.
        scale.beginScaleEdit()
        scale.setScale(null, 75f)
        scale.setScale(null, 50f)
        scale.endScaleEdit()
        assertAt(150f, 50f, scale.scalePercent)
        assertAt(0f, 40f, tool.pointAt(0))
        assertAt(120f, 80f, tool.pointAt(8))
        assertTrue(tool.undoStep())
        assertAt(150f, 100f, scale.scalePercent, "the drag was one step")
        assertTrue(tool.undoStep())
        assertAt(100f, 100f, scale.scalePercent)
        assertFalse(tool.canUndoStep)

        // Scaled, then applied: one "Free deform" step that changed the pixels.
        val before = pixels(layer.bitmap)
        scale.setScale(50f, 50f)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.FREE_DEFORM_LABEL, c.undoManager.undoLabel)
        assertFalse(before.contentEquals(pixels(layer.bitmap)))
        assertEquals("the corners came in", 0, layer.bitmap.getPixel(22, 22))
        assertEquals(red, layer.bitmap.getPixel(60, 60))
    }
}
