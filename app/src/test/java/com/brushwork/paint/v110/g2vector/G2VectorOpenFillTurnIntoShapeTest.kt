package com.brushwork.paint.v110.g2vector

import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa.VectorQaRig
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.v110.a6.A6Paths
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 G2 (V1, R11, I1, I2): "Turn into shape" on an open Curve path with a fill and no line, and
 * on a "closed" path of 2 anchors with a fill, through the object bar's action in a real editor:
 * each is ONE undo step [ObjectBarLabels110.TO_SHAPE] whose note is
 * [ObjectBarLabels110.NOTE_CLOSED_FILL]; the layer's pixels are the same before and after; undo
 * brings the path back (data and pixels) and redo the shape; after save and reopen the layer holds
 * the same closed fill shapes and pixels, its cache is its objects' rendering.
 */
@RunWith(RobolectricTestRunner::class)
class G2VectorOpenFillTurnIntoShapeTest {

    @Test
    fun theFillIsKeptInOneStepUndoneRedoneSavedAndReopened() {
        val rig = VectorQaRig(400, 300)
        try {
            val doc = runBlocking { rig.repo.load(rig.projectId) }
            doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            doc.activeLayerIndex = doc.layers.lastIndex
            rig.open(doc)
            val c = rig.c
            val layer = c.doc.layers.last()
            val open = A6Paths.curve(closed = false).copy(fill = VPaint.Solid(RED), stroke = null)
            val twoAnchors = VPath(
                0,
                subpaths = listOf(VSubpath(listOf(VAnchor(200f, 280f, true, outX = 40f, outY = -90f), VAnchor(380f, 280f, true, inX = -40f, inY = -90f)), closed = true)),
                fill = VPaint.Solid(BLUE),
            )
            val ids = c.vectors.addObjects(layer, listOf(open, twoAnchors), "Add")
            rig.checkpoint("add")

            for ((i, id) in ids.withIndex()) {
                val before = rig.pixels(layer.bitmap)
                c.vectors.setSelection(layer, setOf(id))
                c.message = null
                assertTrue(ObjectActions.turnIntoShape(c))
                val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
                assertTrue("the shape opens", Smoke.pumpUntil(10_000) { tool.editingObject })
                assertEquals("the note", ObjectBarLabels110.NOTE_CLOSED_FILL, c.pendingNote?.text)
                tool.discard()
                rig.checkpoint("turn path $i into a shape")
                assertEquals(ObjectBarLabels110.TO_SHAPE, c.undoManager.undoLabel)
                val shape = layer.vector!!.byId(id) as? VShape ?: throw AssertionError("path $i: a shape")
                assertEquals("path $i: the fill alone", ShapeStyle.FILL, shape.shape.style)
                assertArrayEquals("path $i: the same pixels", before, rig.pixels(layer.bitmap))
            }
            val converted = layer.vector!!.objects

            rig.undoAndCheck("undo the second")
            rig.undoAndCheck("undo the first")
            assertTrue("the paths are back", layer.vector!!.objects.all { it is VPath })
            rig.redoAndCheck("redo the first")
            rig.redoAndCheck("redo the second")
            assertEquals("the shapes again", converted, layer.vector!!.objects)
            val saved = rig.checkpoint("after redo", steps = 0)

            rig.saveAndReopen()
            val layer2 = rig.c.doc.layers.firstOrNull { it.id == layer.id } ?: throw AssertionError("no vector layer after reopen")
            assertEquals("the same shapes", converted, layer2.vector!!.objects)
            rig.assertState("reopened", saved)
            rig.assertCacheFresh("reopened", layer2)
        } finally {
            rig.close()
        }
    }

    private companion object {
        const val RED = 0xFFE04020.toInt()
        const val BLUE = 0xFF203080.toInt()
    }
}
