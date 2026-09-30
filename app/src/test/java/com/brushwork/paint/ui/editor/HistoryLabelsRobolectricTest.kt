package com.brushwork.paint.ui.editor

import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LambdaAction
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Undo/redo feedback and the Redo button's state against a real controller: history steps,
 * pending tool work that is discarded whole, and tools that step back one point at a time.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryLabelsRobolectricTest {

    private fun controller(): EditorController {
        val doc = Document("t", "t", 64, 48)
        repeat(2) { doc.layers += Layer(doc.newLayerId(), "Layer ${it + 1}", BitmapUtils.createLayerBitmap(64, 48)) }
        doc.activeLayerIndex = 1
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = AppSettings(context).also { it.prefs.edit().clear().commit() }
        return EditorController(context, doc, CoroutineScope(Job().apply { cancel() }), settings)
    }

    @Test
    fun historyStepsAreNamed() {
        val c = controller()
        assertEquals("Nothing to undo", HistoryLabels.undo(c))
        assertEquals("Nothing to redo", HistoryLabels.redo(c))
        c.pushUndo(LambdaAction("Fill", onUndo = {}, onRedo = {}))
        assertEquals("Undo: Fill", HistoryLabels.undo(c))
        assertFalse(canRedoNow(c))
        c.undo()
        assertEquals("Redo: Fill", HistoryLabels.redo(c))
        assertTrue(canRedoNow(c))
    }

    @Test
    fun curveUndoTakesBackTheLastPoint() {
        val c = controller()
        c.selectTool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        for (p in listOf(Vec2(5f, 5f), Vec2(30f, 40f), Vec2(60f, 5f))) curve.addAnchor(p)
        assertEquals("Undo: last point", HistoryLabels.undo(c))
        c.undo()
        assertEquals("only the last point went", 2, curve.anchors.size)
        assertTrue(curve.hasPendingWork)
        assertEquals("Undo: last point", HistoryLabels.undo(c))
        // Redo waits for the pending curve unless the tool can redo a step itself.
        assertEquals(curve.canRedoStep, canRedoNow(c))
        if (!curve.canRedoStep) assertEquals("Apply or discard the curve edit first", HistoryLabels.redo(c))
        else assertEquals("Redo: last point", HistoryLabels.redo(c))
    }

    @Test
    fun pendingWorkWithoutStepsIsDiscardedWhole() {
        val c = controller()
        c.pushUndo(LambdaAction("Brush", onUndo = {}, onRedo = {}))
        c.undo()
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(shape.ensurePending())
        val label = HistoryLabels.undo(c)
        if (HistoryLabels.undoStepName(shape) == null) assertEquals("Undo: Shape (discarded)", label)
        assertFalse("redo waits for the pending shape", canRedoNow(c) && !shape.canRedoStep)
        shape.discard()
        assertTrue("redo is back once the shape is gone", canRedoNow(c))
    }

    @Test
    fun aFilterPreviewOwnsUndo() {
        val c = controller()
        c.startFilter(com.brushwork.paint.filters.FilterRegistry.all.first())
        if (c.filterSession != null) {
            assertEquals("Filter cancelled", HistoryLabels.undo(c))
            assertEquals("Finish the filter first", HistoryLabels.redo(c))
            assertFalse(canRedoNow(c))
            c.filterSession?.cancel()
        }
    }

    /**
     * Step labels are known per tool (no reflection, which a minified release build would break):
     * polygon lasso corners step back one at a time; other tools' pending work is discarded whole.
     */
    @Test
    fun stepLabelsAreKnownPerTool() {
        val c = controller()
        val idle = object : Tool(c) { override val id = ToolId.LASSO }
        assertNull("no corners placed: nothing to step back", HistoryLabels.undoStepName(idle))
        val corners = object : Tool(c) {
            override val id = ToolId.LASSO
            override val hasPendingWork: Boolean get() = true
            override val canUndoStep: Boolean get() = true
        }
        assertEquals("last corner", HistoryLabels.undoStepName(corners))
        val shapeLike = object : Tool(c) {
            override val id = ToolId.MARQUEE
            override val hasPendingWork: Boolean get() = true
        }
        assertNull("pending work without steps is discarded whole", HistoryLabels.undoStepName(shapeLike))
        c.selectTool(ToolId.LASSO)
        assertEquals("the real lasso without corners", null, HistoryLabels.undoStepName(c.currentTool))
        assertEquals("last point", HistoryLabels.stepName(ToolId.POLYLINE))
        assertEquals("last text step", HistoryLabels.stepName(ToolId.TEXT))
    }
}
