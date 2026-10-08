package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayTransforms
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.ArrayLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 (item 11, design §3.11 a) with area E's real `ArrayTransforms`: transforming an arrayed
 * layer maps the array's spec AND its source, so the layer stays a live array, in ONE step that
 * undo takes back exactly. Distort and Free deform are refused ("Apply the array to deform"),
 * with no rasterize offer.
 *
 * GUARDED: every test is skipped while `ArrayTransforms` is the foundation stub (`mapped` null,
 * and the Transform tool refuses arrays); it runs once area E's implementation is merged.
 */
@RunWith(RobolectricTestRunner::class)
class TransformArrayRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val hi = TextItem("Hi", spec = TextSpec(sizePx = 32f), cx = 50f, cy = 80f)

    private fun draw(item: TextItem): (Canvas) -> Unit = { cv -> TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null) }

    /**
     * A 240 x 160 document with a text layer "Hi" arrayed 3 times side by side (count 3,
     * relative X 100 %), active. Skips the test while `ArrayTransforms` is the stub.
     */
    private fun arraySetup(spec: ArraySpec = ArraySpec(count = 3, relativeX = 1f)): Pair<EditorController, Layer> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 240, 160)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(240, 160))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val layer = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(hi), draw = draw(hi))!!
        assertTrue(c.updateLayerData(layer, layer.dataSnapshot().copy(array = LayerArray(spec)), "Array", null, draw = draw(hi)))
        assertNotNull(layer.array)
        assumeTrue("area E's ArrayTransforms is still the foundation stub", ArrayTransforms.mapped(layer, DataRender.IDENTITY) != null)
        return c to layer
    }

    private fun transform(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.snapToObjects = false
        tool.start()
        assertEquals("kept as an array", TransformTool.Lifted.ARRAY, tool.lifted)
        return tool
    }

    @Test
    fun aMovedArrayStaysALiveArrayInOneStep() {
        val (c, layer) = arraySetup()
        val before = layer.dataSnapshot()
        val spec = layer.array!!.spec
        val tool = transform(c)
        assertEquals(ArrayLabels.DEFORM_REFUSAL, tool.modeRefusal(TransformTool.Mode.DISTORT))
        assertEquals(ArrayLabels.DEFORM_REFUSAL, tool.modeRefusal(TransformTool.Mode.MESH))
        assertFalse("no rasterize offer for an array", tool.canRasterizeFor(TransformTool.Mode.MESH))

        tool.moveBy(20f, 10f)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertNotNull("still a live array", layer.array)
        assertEquals("a move keeps the spec", spec, layer.array!!.spec)
        val source = TextCodec.decode(layer.textData)!!
        assertEquals("the source moved", 70f, source.cx, 0.01f)
        assertEquals(90f, source.cy, 0.01f)

        c.undo()
        assertEquals("undo restores the data exactly", before, layer.dataSnapshot())
    }

    @Test
    fun aScaledArrayKeepsItsSourceAText() {
        val (c, layer) = arraySetup()
        val tool = transform(c)
        tool.setScalePercent(200.0)
        tool.endNumericEdit()
        tool.commit()
        assertNotNull(layer.array)
        val source = TextCodec.decode(layer.textData)
        assertNotNull("the source is still a text", source)
        assertEquals("its type scaled with the array", 64f, source!!.spec.sizePx, 0.1f)
    }
}
