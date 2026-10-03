package com.brushwork.paint.ui.mask

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
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
 * v1.6 §3.1a: "Fast adjustment preview" (default on) in the Masks tool's sheet, next to "Safe
 * compositing", at the user's phone size: it turns live sessions off and on, is saved, and ends a
 * running session. The Settings copy ([FastAdjustPreviewToggle] on the preferences, for the
 * editor's Settings dialog) saves the same setting, which the editor takes over at the next drag.
 * (One test: this harness runs one Compose screen per test class.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.mask.fasttogglesandbox"])
class FastAdjustPreviewToggleUiRobolectricTest {

    @Test
    fun theSwitchTurnsLiveSessionsOffAndOnInTheMasksSheetAndInSettings() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        activity.setContent { BrushworkTheme { ToolOptionsBar(c) } }
        SmokeUi.settle()
        assertTrue("default on", c.settings.fastAdjustPreview)
        assertTrue(c.liveAdjust.fastPreview)

        // A radial makes a Tone layer; its Components sheet has the switch.
        SmokeUi.click("+ Radial", exact = true)
        c.pointerDown(ToolPoint(200f, 150f)); c.pointerMove(ToolPoint(230f, 150f)); c.pointerMove(ToolPoint(260f, 150f)); c.pointerUp(ToolPoint(260f, 150f))
        SmokeUi.settle()
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)
        SmokeUi.click("Components (1)", exact = true)
        assertTrue("shown: ${SmokeUi.shown()}", SmokeUi.has(FAST_ADJUST_PREVIEW_LABEL, exact = true))
        assertTrue(SmokeUi.has("Safe compositing", exact = true))

        // A running session ends when the switch goes off.
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        val edit = tool.adjustmentEdit(adj)
        edit.preview(adj.adjustment, 0.5f, adj.name)
        assertTrue(c.liveAdjust.isActive)
        SmokeUi.click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
        assertFalse(c.liveAdjust.fastPreview)
        assertFalse("saved", c.settings.fastAdjustPreview)
        assertFalse("the session ended", c.liveAdjust.isActive)
        edit.preview(adj.adjustment, 0.6f, adj.name)
        assertFalse("off: no session", c.liveAdjust.isActive)
        edit.flush()

        // On again.
        SmokeUi.click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
        assertTrue(c.liveAdjust.fastPreview)
        assertTrue(c.settings.fastAdjustPreview)
        edit.preview(adj.adjustment, 0.7f, adj.name)
        assertTrue(c.liveAdjust.isActive)
        edit.flush()
        c.liveAdjust.release()
        assertEquals("Edit adjustment", c.undoManager.undoLabel)
        SmokeUi.click("Close", exact = true)

        // The Settings copy: it saves the setting, and the editor takes it over at the next drag.
        activity.setContent { BrushworkTheme { FastAdjustPreviewToggle(c.settings) } }
        SmokeUi.settle()
        assertTrue(SmokeUi.has(FAST_ADJUST_PREVIEW_DESCRIPTION, exact = true))
        SmokeUi.click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
        assertFalse(c.settings.fastAdjustPreview)
        edit.preview(adj.adjustment, 0.4f, adj.name)
        assertFalse("taken over at the next drag", c.liveAdjust.fastPreview)
        assertFalse(c.liveAdjust.isActive)
        edit.flush()
        SmokeUi.click(FAST_ADJUST_PREVIEW_LABEL, exact = true)
        assertTrue(c.settings.fastAdjustPreview)
        edit.preview(adj.adjustment, 0.3f, adj.name)
        assertTrue(c.liveAdjust.fastPreview)
        assertTrue(c.liveAdjust.isActive)
        edit.flush()
        c.liveAdjust.release()
        Smoke.assertQuiet(c, "fast preview switch")
    }
}
