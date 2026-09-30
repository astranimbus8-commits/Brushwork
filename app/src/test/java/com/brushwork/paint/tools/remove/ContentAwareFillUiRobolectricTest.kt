package com.brushwork.paint.tools.remove

import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.SelectionActionBar
import com.brushwork.paint.ui.remove.RemoveSizeScale
import com.brushwork.paint.ui.remove.RemoveToolOptions
import com.brushwork.paint.ui.selection.SelectionPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * The content-aware fill UI at runtime on a ~392 dp phone: the selection bar's "Content-aware
 * fill" opens the options sheet, Fill closes it and adds the fill as one step on a new layer,
 * Refill (enabled only then) replaces it; the Remove tool's options strip shows its brush size.
 *
 * Own sandbox and a single test (Compose's frame clock only runs in the first test of one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h850dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.tools.remove.cafuisandbox"])
class ContentAwareFillUiRobolectricTest {

    private val w = 160
    private val h = 120

    private fun document(): Document {
        val doc = Document("ui", "ui", w, h)
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        bmp.setPixels(IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (x in 65 until 95 && y in 45 until 75) 0xFFFF00FF.toInt() else if (x % 8 < 4) 0xFF000000.toInt() else -1
        }, 0, w, 0, 0, w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", bmp)
        return doc
    }

    @Test
    fun selectionBarFillAndRefillAndRemoveStrip() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        Smoke.scopeErrors.clear()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val c = Smoke.controller(activity, document())
            val remove = c.tools[ToolId.REMOVE] as RemoveTool
            var panelOpen by mutableStateOf(false)
            activity.setContent {
                BrushworkTheme {
                    Column(Modifier.fillMaxSize()) {
                        RemoveToolOptions(remove)
                        SelectionActionBar(c, onMore = { panelOpen = true }, onHide = null)
                    }
                    if (panelOpen) SelectionPanel(c, onDismiss = { panelOpen = false })
                }
            }
            SmokeUi.settle()

            // Remove tool strip: its own brush size, the sample source.
            assertTrue("size slider", SmokeUi.has("Remove brush size"))
            assertTrue("size readout", SmokeUi.has("${RemoveSettings.DEFAULT_SIZE.toInt()} px"))
            assertTrue(SmokeUi.has("Sample: Current layer"))
            val pos = RemoveSizeScale.toPosition(100f)
            assertTrue(abs(RemoveSizeScale.fromPosition(pos) - 100f) <= 1f)

            // The bar offers the fill only with a selection.
            assertFalse(SmokeUi.has(ContentAwareFillJob.FILL_LABEL, exact = true))
            c.setSelection(Selection.fromPath(Path().apply { addRect(63f, 43f, 97f, 77f, Path.Direction.CW) }, w, h, antiAlias = false))
            SmokeUi.settle()
            assertTrue(SmokeUi.has(ContentAwareFillJob.FILL_LABEL, exact = true))
            val steps = c.undoManager.undoCount

            SmokeUi.click(ContentAwareFillJob.FILL_LABEL, exact = true)
            assertTrue("options sheet", SmokeUi.has("Expand selection"))
            assertTrue(SmokeUi.has("Whole layer"))
            assertFalse("nothing to refill yet", SmokeUi.isEnabled("Refill"))
            SmokeUi.click("Fill", exact = true)
            assertTrue("filled", Smoke.pumpUntil(30_000) { c.busyMessage == null && !ContentAwareFillJob.isRunning(c) && c.doc.layers.size == 2 })
            SmokeUi.settle()
            assertFalse("the sheet closed", SmokeUi.has("Expand selection"))
            assertEquals(steps + 1, c.undoManager.undoCount)
            assertEquals(ContentAwareFillJob.FILL_LABEL, c.doc.layers[1].name)

            // Refill replaces it.
            SmokeUi.click(ContentAwareFillJob.FILL_LABEL, exact = true)
            assertTrue("refill offered", SmokeUi.isEnabled("Refill"))
            SmokeUi.click("Refill", exact = true)
            assertTrue(Smoke.pumpUntil(30_000) { c.busyMessage == null && !ContentAwareFillJob.isRunning(c) })
            SmokeUi.settle()
            assertEquals("still one fill layer", 2, c.doc.layers.size)
            assertEquals(steps + 1, c.undoManager.undoCount)

            // The selection menu ("More") has it too: its tile opens the same options over the
            // menu, and Fill closes both.
            SmokeUi.click("Selection menu", exact = true)
            assertTrue("selection menu", panelOpen && SmokeUi.has("Cut to new layer"))
            SmokeUi.click(ContentAwareFillJob.FILL_LABEL, exact = true)
            assertTrue("options over the menu", SmokeUi.has("Expand selection"))
            SmokeUi.click("Fill", exact = true)
            assertTrue(Smoke.pumpUntil(30_000) { c.busyMessage == null && !ContentAwareFillJob.isRunning(c) && c.doc.layers.size == 3 })
            SmokeUi.settle()
            assertFalse("the menu closed", panelOpen)
            assertFalse("the options closed", SmokeUi.has("Expand selection"))
            assertEquals(steps + 2, c.undoManager.undoCount)
            assertTrue("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.isEmpty())
            SmokeUi.assertIdle("after the fills")
        } finally {
            runCatching { ctl.pause().stop().destroy() }
            SmokeUi.settle()
        }
    }
}
