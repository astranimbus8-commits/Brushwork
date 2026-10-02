package com.brushwork.paint.qa

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.layers.LayerOps
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA (§7 checklist 1 "undo ×N / redo ×N back and forth: the content and pixels must match exactly
 * the states they had"), found by [VectorFuzzQaTest]: a canvas operation, then edits, then Flip
 * layer — undone all the way and redone: every state exactly as it was. (Flip layer used to put a
 * NEW bitmap into the layer on every flip, undo and redo; the canvas operation's step keeps the
 * layer's bitmap it made, so the edits undone in the new bitmap were still in the kept one, and
 * came back on redo.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorHistoryQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private val ink = 0xFF1A2A6C.toInt()

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    private fun waitCanvasOp(label: String) {
        assertTrue("$label finished", Smoke.pumpUntil(30_000) { c.busyMessage == null && c.undoManager.undoLabel == label })
    }

    private fun flow(vector: Boolean, mask: Boolean, canvasOp: () -> String) {
        r = VectorQaRig(480, 320)
        if (vector) {
            c.toggleVectorMode()
            r.checkpoint("Vector on")
        }
        val layer = c.activeLayer
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        r.stroke(40f to 60f, 240f to 80f, 440f to 60f); r.checkpoint("s1")
        if (mask) {
            LayerOps.addMask(c, layer, fromSelection = false)
            r.checkpoint("add mask")
            c.color = 0xFF000000.toInt()
            r.stroke(100f to 30f, 100f to 300f); r.checkpoint("paint the mask")
            LayerOps.editTarget(c, layer, mask = false)
            c.color = ink
        }
        val label = canvasOp()
        waitCanvasOp(label)
        r.checkpoint("canvas op", tolerance = 2, maxOffPermille = 5)
        r.stroke(40f to 160f, 240f to 180f, 440f to 160f); r.checkpoint("s2 after the canvas op", tolerance = 2, maxOffPermille = 5)
        if (mask) {
            LayerOps.editTarget(c, layer, mask = true)
            c.color = 0xFF000000.toInt()
            r.stroke(300f to 30f, 300f to 300f); r.checkpoint("paint the mask again", tolerance = 2, maxOffPermille = 5)
            LayerOps.editTarget(c, layer, mask = false)
            c.color = ink
        }
        c.flipLayer(layer, horizontal = false)
        r.checkpoint("flip layer", tolerance = 2, maxOffPermille = 5)
        r.stroke(60f to 250f, 200f to 290f, 300f to 240f); r.checkpoint("s3 after the flip", tolerance = 2, maxOffPermille = 5)
        // All the way back, then forward, twice: every state exactly as it was.
        repeat(2) { round ->
            while (c.undoManager.canUndo) r.undoAndCheck("round $round undo")
            while (c.undoManager.canRedo) r.redoAndCheck("round $round redo")
        }
    }

    @Test
    fun canvasFlipThenEditsThenFlipLayerUndoAndRedoExactlyOnAVectorLayer() =
        flow(vector = true, mask = false) { assertTrue(CanvasOps.applyFlip(c, horizontal = true)); "Flip canvas horizontally" }

    @Test
    fun canvasRotateThenEditsThenFlipLayerUndoAndRedoExactlyOnARasterLayer() =
        flow(vector = false, mask = false) { assertTrue(CanvasOps.applyRotate(c, CanvasRotation.R_180)); CanvasRotation.R_180.label }

    @Test
    fun canvasFlipThenMaskEditsThenFlipLayerUndoAndRedoExactlyWithAMask() =
        flow(vector = true, mask = true) { assertTrue(CanvasOps.applyFlip(c, horizontal = true)); "Flip canvas horizontally" }
}
