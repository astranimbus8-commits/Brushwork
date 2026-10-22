package com.brushwork.paint.v110.a3

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/**
 * v1.10 (R4, A3 step 2): shared fixtures of the "array behind" tests: a painted square selected on
 * layer 1, a vector layer with two boxes, a text layer, and the one-step and refusal checks of
 * v1.9's `ArrayOpsRobolectricTest`.
 */
internal object BehindKit {
    /** The square [l, t, l + size, t + size] painted [color] on layer 1, which becomes active and selected there. */
    fun selectedSquare(c: EditorController, l: Int = 20, t: Int = 30, size: Int = 40, color: Int = ArrayLookKit.GRAY): Layer {
        val src = c.doc.layers[1]
        Canvas(src.bitmap).drawRect(l.toFloat(), t.toFloat(), (l + size).toFloat(), (t + size).toFloat(), Paint().apply { this.color = color })
        c.selectLayer(src)
        c.setSelection(ArrayLookKit.rectSelection(c, l.toFloat(), t.toFloat(), (l + size).toFloat(), (t + size).toFloat()), recordUndo = false)
        return src
    }

    /** A new active vector layer holding two boxes: [20, 30, 60, 70] and [200, 30, 240, 70]. */
    fun twoBoxes(c: EditorController): Layer {
        val v = c.addVectorLayer()!!
        val objects = listOf(ArrayLookKit.box(20f, 30f, 60f, 70f), ArrayLookKit.box(200f, 30f, 240f, 70f, fill = ArrayLookKit.RED))
        c.vectors.update(v, VectorContent.EMPTY.plus(objects).first, "Add")
        ArrayLookKit.settle(c)
        return v
    }

    /** A new active text layer showing "Hi". */
    fun textLayer(c: EditorController): Layer {
        val item = TextItem("Hi", spec = TextSpec(sizePx = 40f), cx = 60f, cy = 60f)
        return c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(item), draw = ArrayLookKit.textDraw(item))!!
    }

    /** One step named [label] was recorded by [block]. */
    fun <T> oneStep(c: EditorController, label: String, block: () -> T): T {
        val steps = c.undoManager.undoCount
        val out = block()
        ArrayLookKit.settle(c)
        assertEquals("$label: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(label, c.undoManager.undoLabel)
        return out
    }

    /** [block] records nothing, adds no layer and shows [message]. */
    fun refused(c: EditorController, message: String, block: () -> Boolean) {
        val steps = c.undoManager.undoCount
        val layers = c.doc.layers.size
        c.message = null
        assertFalse(message, block())
        ArrayLookKit.settle(c)
        assertEquals("$message: no step", steps, c.undoManager.undoCount)
        assertEquals("$message: no layer", layers, c.doc.layers.size)
        assertEquals(message, c.message)
    }

    fun undo(c: EditorController) { c.undo(); ArrayLookKit.settle(c) }
    fun redo(c: EditorController) { c.redo(); ArrayLookKit.settle(c) }
}
