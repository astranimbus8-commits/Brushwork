package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTransforms
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.lift.LiftGeometry
import com.brushwork.paint.vector.render.VectorLayerRenderer
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
import kotlin.math.roundToInt

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

    /** A shape layer of [o] whose pixels are a fresh render of it (as the Shape tool leaves it), active. */
    private fun shapeSetup(o: ShapeObject): Pair<EditorController, Layer> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 200, 150)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(200, 150))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val area = Rect(0, 0, 200, 150)
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { cv: Canvas ->
            VectorLayerRenderer.render(cv, VectorContent(objects = listOf(VShape(0L, shape = o))), area, tips = TipCache(), document = area)
        }!!
        return c to layer
    }

    /** The corners of [o]'s outline (a sharp rectangle: a polygon), document px, each once. */
    private fun corners(o: ShapeObject): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (p in ShapeOutlines.outline(o).flatten(0.01f).flatMap { it.points }) if (out.none { (it - p).length < 1e-3f }) out += p
        return out
    }

    private fun map(m: FloatArray, p: Vec2) = Vec2(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])

    /** [o]'s outline is [expected] within 0.01 px (I1: exact, not resampled). */
    private fun assertOutline(expected: List<Vec2>, o: ShapeObject) {
        val actual = corners(o)
        assertEquals("corners $actual", expected.size, actual.size)
        for (p in expected) assertTrue("$p on the outline $actual", actual.any { (it - p).length <= 0.01f })
    }

    /** The tool's map (document px to document px) of its pending transform, from the box it started with [start]. */
    private fun mapOf(tool: TransformTool, start: TransformState): FloatArray {
        val left = (start.cx - start.srcW / 2f).roundToInt()
        val top = (start.cy - start.srcH / 2f).roundToInt()
        return LiftGeometry.matrix(tool.transformState!!, left, top)!!
    }

    @Test
    fun aFlippedTurnedShapeStaysAShapeWithItsOutlineMirrored() {
        val o = RECT.copy(rotation = 30f)
        val (c, layer) = shapeSetup(o)
        val before = layer.shapeData
        val tool = transform(c)
        val start = tool.transformState!!
        tool.flip(horizontal = true)
        val m = mapOf(tool, start)
        val expected = corners(o).map { map(m, it) }
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertOutline(expected, shapeOf(layer))
        c.undo()
        assertEquals(before, layer.shapeData)
    }

    @Test
    fun aSkewGivesCustomPointsWithTheOutlineExact() {
        // A turned rectangle stretched along the document's x: a skew in its own axes.
        val o = RECT.copy(rotation = 30f)
        val (c, layer) = shapeSetup(o)
        val tool = transform(c)
        val start = tool.transformState!!
        tool.objectScale!!.setScale(150f, null)
        val m = mapOf(tool, start)
        val expected = corners(o).map { map(m, it) }
        val steps = c.undoManager.undoCount
        tool.commit()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val after = shapeOf(layer)
        assertTrue("custom points", ShapeOutlines.isCustom(after))
        assertOutline(expected, after)
    }

    private companion object {
        val RECT = ShapeObject(ShapeType.RECTANGLE, cx = 60f, cy = 50f, w = 60f, h = 40f, style = ShapeStyle.FILL, fillColor = 0xFF2244CC.toInt())
    }
}
