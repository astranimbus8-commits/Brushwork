package com.brushwork.paint.ui.mask

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5 A5 review: the Adjust sheet records its pending change when the app leaves the screen (so
 * the autosave that follows has an edit to save), and closes for good when a non-adjustment
 * layer becomes active.
 */
@RunWith(RobolectricTestRunner::class)
// A sandbox of its own (a unique instrumentedPackages value): Compose's global state stays per class.
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.mask.adjustsandbox"])
class AdjustmentSheetLifecycleUiRobolectricTest {

    @Test
    fun aPendingAdjustChangeIsRecordedWhenTheAppStopsAndTheSheetClosesOnAPixelLayer() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val ac = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = ac.get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        activity.setContent { BrushworkTheme { ToolOptionsBar(c) } }
        SmokeUi.settle()

        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), null)!!
        tool.openAdjust()
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Adjust: ${adj.name}", exact = true))
        val steps = c.undoManager.undoCount
        val edits = c.editCount
        tool.adjustmentEdit(adj).preview(AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert")), 0.7f)
        assertEquals("live: no step yet", steps, c.undoManager.undoCount)
        assertEquals("live: autosave sees no edit yet", edits, c.editCount)

        // Home button: the step is recorded before the activity's onStop (and its autosave) runs.
        ac.pause().stop()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)
        assertTrue(c.editCount > edits)
        assertEquals("adjust.invert", adj.adjustment!!.filterId)
        assertEquals(0.7f, adj.opacity, 0f)
        ac.start().resume()
        SmokeUi.settle()
        assertTrue("the sheet is still open", SmokeUi.has("Adjust: ${adj.name}", exact = true))

        // A pixel layer becomes active: the sheet goes, and doesn't come back by itself.
        c.selectLayer(c.doc.layers[1])
        SmokeUi.settle()
        assertFalse(tool.adjustOpen)
        assertFalse(SmokeUi.has("Adjust: ${adj.name}", exact = true))
        c.selectLayer(adj)
        SmokeUi.settle()
        assertFalse(tool.adjustOpen)
        assertFalse(SmokeUi.has("Adjust: ${adj.name}", exact = true))
        // The undo after the recorded step goes back to Tone at full amount.
        c.undo()
        assertEquals("adjust.tone", adj.adjustment!!.filterId)
        assertEquals(1f, adj.opacity, 0f)
        Smoke.assertQuiet(c, "adjust sheet lifecycle")
        ac.pause().stop().destroy()
        c.dispose()
    }
}
