package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTransforms
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.TransformLabels17
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 (item 11, design §3.11 a) with area C's real `ShapeTransforms`: transforming a shape layer
 * keeps it a shape (move, every handle, flips and rotation map it exactly), in ONE step that undo
 * takes back exactly. Distort and Free deform ask to rasterize first.
 *
 * GUARDED: every test is skipped while `ShapeTransforms` is the foundation stub (`mapped` null);
 * it runs once area C's implementation is merged.
 */
@RunWith(RobolectricTestRunner::class)
class TransformShapeObjectRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    @Before
    fun guard() = assumeTrue(
        "area C's ShapeTransforms is still the foundation stub",
        ShapeTransforms.mapped(ShapeCodec.encode(RECT), DataRender.IDENTITY) != null,
    )

    private val app get() = RuntimeEnvironment.getApplication()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A 200 x 150 document with a filled 60 x 40 rectangle shape layer centred on (60, 50), active. */
    private fun shapeSetup(): Pair<EditorController, Layer> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 200, 150)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(200, 150))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(RECT)) { cv: Canvas ->
            cv.drawRect(30f, 30f, 90f, 70f, Paint().apply { color = RECT.fillColor })
        }!!
        return c to layer
    }

    private fun transform(c: EditorController): TransformTool {
        c.selectTool(ToolId.TRANSFORM)
        idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.snapToObjects = false
        tool.start()
        assertEquals("kept as a shape", TransformTool.Lifted.SHAPE, tool.lifted)
        return tool
    }

    private fun shapeOf(layer: Layer): ShapeObject {
        assertNotNull("still a shape", layer.shapeData)
        return ShapeCodec.decode(layer.shapeData)!!
    }

    @Test
    fun aMovedAndStretchedShapeStaysAShapeInOneStep() {
        val (c, layer) = shapeSetup()
        val before = layer.shapeData
        val tool = transform(c)
        assertFalse("every handle", tool.uniformOnly)
        assertTrue(tool.flipsAllowed)
        assertEquals(TransformLabels17.RASTERIZE_TO_DEFORM, tool.modeRefusal(TransformTool.Mode.DISTORT))
        assertEquals(TransformLabels17.RASTERIZE_TO_FREE_DEFORM, tool.modeRefusal(TransformTool.Mode.MESH))

        tool.moveBy(10f, 5f)
        tool.objectScale!!.setScale(150f, null)
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val o = shapeOf(layer)
        assertEquals(70f, o.cx, 0.05f)
        assertEquals(55f, o.cy, 0.05f)
        assertEquals("Scale X 150 %", 90f, o.w, 0.05f)
        assertEquals(40f, o.h, 0.05f)

        c.undo()
        assertEquals("undo restores the shape exactly", before, layer.shapeData)
    }

    @Test
    fun aRotatedShapeKeepsItsData() {
        val (c, layer) = shapeSetup()
        val tool = transform(c)
        tool.setRotation(30.0)
        tool.endNumericEdit()
        tool.commit()
        val o = shapeOf(layer)
        assertEquals(30f, o.rotation, 0.05f)
        assertEquals(60f, o.w, 0.05f)
        assertEquals(40f, o.h, 0.05f)
    }

    private companion object {
        val RECT = ShapeObject(ShapeType.RECTANGLE, cx = 60f, cy = 50f, w = 60f, h = 40f, style = ShapeStyle.FILL, fillColor = 0xFF2244CC.toInt())
    }
}
