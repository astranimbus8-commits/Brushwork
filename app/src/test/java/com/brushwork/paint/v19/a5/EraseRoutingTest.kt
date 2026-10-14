package com.brushwork.paint.v19.a5

import android.graphics.Bitmap
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeInfo
import com.brushwork.paint.brush.StrokeKind
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.EraseRoute
import com.brushwork.paint.vector.draw.EraseRouting
import com.brushwork.paint.vector.draw.EraseSource
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.draw.VectorEraserRecorder
import com.brushwork.paint.vector.draw.VectorStrokeCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.9 (R11 b, I20; A5 step 1): the route of every erase ([EraseRouting]) over every kind of
 * layer, target, source and stroke; an erase never turns a vector or a shape layer into pixels on
 * its own (only the Eraser's path stroke, v1.8's rasterize with its toast); the stroke seam
 * ([VectorStrokeCapture.hookFor]) lets only the Eraser convert a shape layer, and a shape that
 * doesn't decode is refused, not rasterized.
 */
@RunWith(RobolectricTestRunner::class)
class EraseRoutingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val kinds = listOf("raster", "text", "shape", "vector", "adjustment", "folder")

    private fun layer(kind: String): Layer = Layer(1L, kind, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)).also { l ->
        when (kind) {
            "text" -> l.textData = "{}"
            "shape" -> l.shapeData = "{}"
            "vector" -> l.vector = VectorContent.EMPTY
            "adjustment" -> l.adjustment = AdjustmentSpec(filterId = "adjust.invert")
            "folder" -> l.folder = FolderSpec()
        }
    }

    /** R11 (b), written out on its own. */
    private fun expected(kind: String, source: EraseSource, mask: Boolean, path: Boolean): EraseRoute {
        val eraserPath = path && source == EraseSource.ERASER_BRUSH
        return when {
            kind == "folder" -> EraseRoute.REFUSE
            mask -> EraseRoute.RASTER
            kind == "adjustment" -> EraseRoute.REFUSE
            kind == "vector" -> if (eraserPath) EraseRoute.RASTERIZE_DATA else EraseRoute.OBJECTS
            kind == "shape" -> if (eraserPath) EraseRoute.RASTERIZE_DATA else EraseRoute.CONVERT_THEN_OBJECTS
            kind == "text" -> EraseRoute.RASTERIZE_DATA
            else -> EraseRoute.RASTER
        }
    }

    @Test
    fun theTableOfR11b() {
        for (source in EraseSource.entries) for (kind in kinds) for (mask in listOf(false, true)) for (path in listOf(false, true)) {
            val route = EraseRouting.route(layer(kind), source, mask, path)
            assertEquals("$source on $kind, mask $mask, path $path", expected(kind, source, mask, path), route)
        }
    }

    @Test
    fun noEraseRasterizesAVectorOrAShapeLayerOnItsOwn() {
        for (source in EraseSource.entries) for (kind in listOf("vector", "shape")) {
            val route = EraseRouting.route(layer(kind), source, maskTarget = false, isPath = false)
            assertTrue("$source on $kind: $route", route == EraseRoute.OBJECTS || route == EraseRoute.CONVERT_THEN_OBJECTS)
        }
        // The four tools have no path stroke: the flag changes nothing for them.
        for (source in EraseSource.entries.filter { it != EraseSource.ERASER_BRUSH }) for (kind in kinds) {
            val l = layer(kind)
            assertEquals("$source on $kind", EraseRouting.route(l, source, false, false), EraseRouting.route(l, source, false, true))
        }
    }

    private val app get() = RuntimeEnvironment.getApplication()

    private fun controller(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 200, 160)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(200, 160))
        doc.activeLayerIndex = 0
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    private val shapeObject = ShapeObject(ShapeType.ELLIPSE, cx = 100f, cy = 80f, w = 80f, h = 60f, style = ShapeStyle.STROKE, strokeWidth = 4f)

    private fun info(id: ToolId, layer: Layer, convertFirst: Boolean, target: EditTarget = EditTarget.CONTENT): StrokeInfo {
        val preset = if (id == ToolId.ERASER) BrushLibrary.defaultEraser else BrushLibrary.defaultBrush
        return StrokeInfo(id, StrokeKind.of(id, preset), layer, target, preset, 1L, false, 0xFF000000.toInt(), isPath = false, convertFirst = convertFirst)
    }

    @Test
    fun onlyTheEraserConvertsAShapeLayerAndAnUnreadableShapeIsRefused() {
        val c = controller()
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(shapeObject), draw = ArraySources.shapeDraw(shapeObject, ColorMode.RGB, 200, 160))!!
        // The Eraser: its shape becomes the object the eraser works on.
        val eraser = VectorStrokeCapture.hookFor(c, info(ToolId.ERASER, shape, convertFirst = true))
        assertTrue("$eraser", eraser is StrokeHook.Record && eraser.recorder is VectorEraserRecorder)
        (eraser as StrokeHook.Record).recorder.cancel()
        // Every other tool on a shape layer, and the Eraser in its mask, keep v1.8's raster stroke.
        for (id in listOf(ToolId.BRUSH, ToolId.SMUDGE, ToolId.BLUR)) {
            assertSame("$id", StrokeHook.None, VectorStrokeCapture.hookFor(c, info(id, shape, convertFirst = true)))
        }
        assertSame(StrokeHook.None, VectorStrokeCapture.hookFor(c, info(ToolId.ERASER, shape, convertFirst = false, target = EditTarget.MASK)))
        // A shape that doesn't decode: refused (a message), never rasterized.
        shape.shapeData = "not a shape"
        val refused = VectorStrokeCapture.hookFor(c, info(ToolId.ERASER, shape, convertFirst = true))
        assertTrue("$refused", refused is StrokeHook.Refuse)
    }

    @Test
    fun theEraserOnAShapeLayerLeavesItsDataAndNeverJustItsPixels() {
        val c = controller()
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(shapeObject), draw = ArraySources.shapeDraw(shapeObject, ColorMode.RGB, 200, 160))!!
        c.selectLayer(shape)
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(60f, 70f))
        c.pointerMove(ToolPoint(60f, 90f))
        c.pointerUp(ToolPoint(60f, 90f))
        // Whatever the mode makes of it, the layer still holds objects (or its shape), as data.
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(shape.shapeData)
        assertNotNull(shape.vector)
        c.undo()
        assertEquals(ShapeCodec.encode(shapeObject), shape.shapeData)
        assertNull(shape.vector)
    }
}
