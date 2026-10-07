package com.brushwork.paint.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArrayTransforms
import com.brushwork.paint.assist.SymmetryGuides
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.symmetry.SymmetryTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTransforms
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeTransforms
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 F5 (design §4.6, §4.9): the three tools registered before their areas fill them (Array,
 * Symmetry, Pathfinder) open, take a gesture, draw their overlays and close on every layer kind
 * (raster, text, shape, vector, adjustment, folder) without an exception, a step or a change,
 * and their option strips compose; every other stub keeps v1.6 behaviour (`ArrayOps` refuses
 * with no effect, the transform mappers decline, `SymmetryGuides` draws nothing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.brushwork.paint.tools.stubtoolssandbox"])
class StubToolsRobolectricTest {
    private val stubs = listOf(ToolId.ARRAY, ToolId.SYMMETRY, ToolId.PATHFINDER)

    /** Raster (with a red square), text, shape, vector, adjustment and folder layers; returns them by kind. */
    private fun everyKind(c: EditorController): Map<String, Layer> {
        Canvas(c.doc.layers[1].bitmap).drawRect(Rect(40, 40, 120, 100), Paint().apply { color = 0xFFDD2211.toInt() })
        val raster = c.doc.layers[1]
        c.selectLayer(c.doc.layers.last())
        val item = TextItem("Hi", spec = TextSpec(sizePx = 40f), cx = 150f, cy = 90f)
        val text = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item)) { canvas ->
            TextRenderer.drawItem(canvas, item, TextRenderer.prepare(item), null)
        }!!
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 200f, cy = 150f, w = 60f, h = 40f)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { canvas ->
            canvas.drawRect(170f, 130f, 230f, 170f, Paint().apply { color = 0xFF2244CC.toInt() })
        }!!
        val vector = c.addVectorLayer()!!
        val adjustment = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), null)!!
        val folder = c.addFolder()!!
        assertNull(LayerTree.check(c.doc.layers))
        return linkedMapOf(
            "raster" to raster, "text" to text, "shape" to shape, "vector" to vector,
            "adjustment" to adjustment, "folder" to folder,
        )
    }

    private fun pixels(c: EditorController): List<Int> = c.doc.layers.map { l ->
        if (l.isFolder) 0 else IntArray(l.bitmap.width * l.bitmap.height).also { l.bitmap.getPixels(it, 0, l.bitmap.width, 0, 0, l.bitmap.width, l.bitmap.height) }.contentHashCode()
    }

    @Test
    fun theStubToolsOpenAndCloseOnEveryLayerKind() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity)
        val kinds = everyKind(c)

        // Registered as the stubs, each its own class.
        assertTrue(c.tools.getValue(ToolId.ARRAY) is ArrayTool)
        assertTrue(c.tools.getValue(ToolId.SYMMETRY) is SymmetryTool)
        assertTrue(c.tools.getValue(ToolId.PATHFINDER) is PathfinderTool)
        for (id in stubs) assertEquals(id, c.tools.getValue(id).id)

        var shown by mutableIntStateOf(0)
        activity.setContent { BrushworkTheme { shown; ToolOptionsBar(c) } }
        SmokeUi.settle()
        val overlay = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
        for ((kind, layer) in kinds) {
            for (id in stubs) {
                val where = "$id on the $kind layer"
                c.selectLayer(layer)
                c.selectTool(id)
                shown++
                SmokeUi.settle()
                assertEquals(where, id, c.activeToolId)
                val steps = c.undoManager.undoCount
                val before = pixels(c)
                val layers = c.doc.layers.map { it.id }
                c.pointerDown(ToolPoint(60f, 60f))
                c.pointerMove(ToolPoint(90f, 80f))
                c.pointerUp(ToolPoint(90f, 80f))
                c.drawOverlays(Canvas(overlay), 0f)
                SmokeUi.settle()
                assertEquals("$where: no step", steps, c.undoManager.undoCount)
                assertEquals("$where: no pixel change", before, pixels(c))
                assertEquals("$where: no structure change", layers, c.doc.layers.map { it.id })
                assertFalse("$where: nothing pending", c.currentTool.hasPendingWork)
                Smoke.assertQuiet(c, where)
                c.selectTool(ToolId.BRUSH)
                shown++
                SmokeUi.settle()
                Smoke.assertQuiet(c, "$where, put away")
            }
        }
        // SymmetryGuides draws nothing on main, editing or not.
        val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        SymmetryGuides.draw(Canvas(blank), c.viewTransform, c.doc, editing = true)
        SymmetryGuides.draw(Canvas(blank), c.viewTransform, c.doc, editing = false)
        assertTrue(blank.sameAs(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)))
        assertTrue(Smoke.errorLogs().isEmpty())
    }

    @Test
    fun theOtherStubsKeepV16Behaviour() {
        val c = Smoke.controller(org.robolectric.RuntimeEnvironment.getApplication())
        val layer = c.activeLayer
        val steps = c.undoManager.undoCount
        // ArrayOps: every entry refuses with no effect; the controller's entry points pass it on.
        assertFalse(ArrayOps.fromSelection(c))
        assertFalse(ArrayOps.fromObjects(c, setOf(1L)))
        assertFalse(ArrayOps.fromLayer(c, layer))
        assertFalse(ArrayOps.edit(c, layer, ArraySpec()))
        assertFalse(ArrayOps.apply(c, layer))
        assertFalse(ArrayOps.remove(c, layer))
        assertFalse(ArrayOps.editSource(c, layer))
        assertFalse(ArrayOps.finishSource(c, layer))
        c.setSelection(Selection.all(c.doc.width, c.doc.height), recordUndo = false)
        assertFalse(c.arrayFromSelection())
        assertFalse(c.arrayFromObjects(setOf(1L)))
        assertFalse(c.arrayWholeLayer(layer))
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertNull(layer.array)
        // The transform mappers decline (Transform keeps the v1.6 pixel lift; arrays are refused).
        val m = floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f)
        assertNull(ArrayTransforms.mapped(layer, m))
        assertFalse(TextTransforms.canMap(m))
        assertNull(TextTransforms.mapped("{}", m))
        assertNull(ShapeTransforms.mapped("{}", m))
        assertNotNull(c.tools[ToolId.ARRAY])
    }
}
