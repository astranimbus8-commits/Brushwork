package com.brushwork.paint.tools.text.frames

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.textframes.LINK_HINT
import com.brushwork.paint.ui.textframes.TextFrameToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Text frames options strip on the narrowest phone the design names (360 dp; §3.6a, I10): it is
 * hosted like the real strip (6 dp from each screen edge, 8 dp inner padding, scrolling sideways),
 * and what a finger needs first is on screen without scrolling: the frame's own buttons when a
 * frame is selected, and "Cancel link" in link mode (the long hint comes after it and may be cut).
 * Every one of them is at least 40 dp in both directions.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.frames.narrowsandbox"])
class TextFramesStripNarrowUiTest {

    private fun drag(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..4) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / 4f, y0 + (y1 - y0) * i / 4f))
        c.pointerUp(ToolPoint(x1, y1))
        SmokeUi.settle()
    }

    /** [label] is clickable, at least 40 dp both ways, and fully inside the [screenPx]-wide window. */
    private fun assertOnScreen(label: String, density: Float, screenPx: Int) {
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"")
        var n: androidx.compose.ui.semantics.SemanticsNode? = e.node
        while (n != null && !n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)) n = n.parent
        val node = requireNotNull(n) { "\"$label\" is not clickable" }
        val b = node.boundsInRoot
        assertTrue("\"$label\" is ${b.height / density} dp tall", b.height >= 40f * density - 1f)
        assertTrue("\"$label\" is ${b.width / density} dp wide", b.width >= 40f * density - 1f)
        assertTrue("\"$label\" ends at ${b.right / density} dp, past the ${screenPx / density} dp screen", b.right <= screenPx + 0.5f)
        assertTrue("\"$label\" starts on screen", b.left >= -0.5f)
    }

    @Test
    fun theFirstButtonsAreOnScreenAt360Dp() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1, whiteBottom = true))
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT_FRAMES)
        val tool = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
        activity.setContent {
            BrushworkTheme {
                // As the options strip sits on the phone: 6 dp from each edge, 8 dp inside.
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    Row(
                        Modifier.heightIn(min = 44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { TextFrameToolOptions(tool) }
                }
            }
        }
        SmokeUi.settle()
        val screen = metrics.widthPixels
        assertTrue("the phone is 360 dp wide", kotlin.math.abs(screen / density - 360f) < 1f)
        assertOnScreen("Threads", density, screen)

        // A frame with more text than fits: its buttons.
        drag(c, 40f, 40f, 300f, 200f)
        tool.story.setText(FrameFixtures.STORY)
        tool.story.setSizePx(18f)
        tool.story.confirmEditor()
        SmokeUi.settle()
        val f1 = c.activeLayer
        assertSame(f1, tool.selected)
        for (label in listOf("Edit story", "Link…")) assertOnScreen(label, density, screen)

        // Link mode: "Cancel link" first, the hint after it.
        SmokeUi.click("Link…", exact = true)
        assertSame(f1, tool.linkFrom)
        assertTrue(SmokeUi.has(LINK_HINT, exact = true))
        assertOnScreen("Cancel link", density, screen)
        SmokeUi.click("Cancel link", exact = true)
        assertTrue(tool.linkFrom == null)
        Smoke.assertQuiet(c, "narrow strip")
    }
}
