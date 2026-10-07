package com.brushwork.paint.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

/**
 * v1.7 F5 (gate 1 review): the shared fixture of the stub-tool tests. [StubToolsRobolectricTest]
 * (the lead's) checks only what must hold with the real tools too; each owner's stub test
 * (`array/ArrayStubsRobolectricTest`, `tools/symmetry/SymmetryStubsRobolectricTest`,
 * `tools/pathfinder/PathfinderStubRobolectricTest`) pins its stub's v1.6 behaviour with
 * [assertLeavesNoTrace], in a folder the owner may change (design §5.3), and is deleted or
 * rewritten by the owner when it fills the stub.
 */
internal object StubToolFixtures {
    /** Raster (with a red square), text, shape, vector, adjustment and folder layers added to [c]'s document, by kind. */
    fun everyKind(c: EditorController): Map<String, Layer> {
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

    /** A hash of every layer's pixels (0 for a folder), bottom first. */
    fun pixels(c: EditorController): List<Int> = c.doc.layers.map { l ->
        if (l.isFolder) 0 else IntArray(l.bitmap.width * l.bitmap.height).also { l.bitmap.getPixels(it, 0, l.bitmap.width, 0, 0, l.bitmap.width, l.bitmap.height) }.contentHashCode()
    }

    /**
     * The stub behaviour of a tool registered before its area fills it: chosen on every layer of
     * [kinds], one drag and its overlays leave no step, no pixel change, no structure change and
     * nothing pending, and it is put away quietly. [settle] runs the main thread between steps.
     */
    fun assertLeavesNoTrace(c: EditorController, id: ToolId, kinds: Map<String, Layer>, settle: () -> Unit = { Smoke.pump(60) }) {
        val overlay = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
        for ((kind, layer) in kinds) {
            val where = "$id on the $kind layer"
            c.selectLayer(layer)
            c.selectTool(id)
            settle()
            assertEquals(where, id, c.activeToolId)
            val steps = c.undoManager.undoCount
            val before = pixels(c)
            val layers = c.doc.layers.map { it.id }
            c.pointerDown(ToolPoint(60f, 60f))
            c.pointerMove(ToolPoint(90f, 80f))
            c.pointerUp(ToolPoint(90f, 80f))
            c.drawOverlays(Canvas(overlay), 0f)
            settle()
            assertEquals("$where: no step", steps, c.undoManager.undoCount)
            assertEquals("$where: no pixel change", before, pixels(c))
            assertEquals("$where: no structure change", layers, c.doc.layers.map { it.id })
            assertFalse("$where: nothing pending", c.currentTool.hasPendingWork)
            Smoke.assertQuiet(c, where)
            c.selectTool(ToolId.BRUSH)
            settle()
            Smoke.assertQuiet(c, "$where, put away")
        }
    }
}
