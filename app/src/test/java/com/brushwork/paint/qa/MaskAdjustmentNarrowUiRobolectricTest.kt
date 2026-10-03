package com.brushwork.paint.qa

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_LABEL
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6 area A on the narrowest phone the app targets (360 dp): the "Fast adjustment preview"
 * switch of the Masks tool's Components sheet and the Adjust sheet's −/+ buttons (which follow the
 * increments) lie inside the window and are finger-sized (≥ 40 dp), and a −/+ press moves the
 * slider by its step. (One test: this harness runs one Compose screen per test class.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa.maskadjustnarrowsandbox"])
class MaskAdjustmentNarrowUiRobolectricTest {

    @Test
    fun theFastPreviewSwitchAndTheAdjustSteppersFitAt360dp() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.settings.prefs.edit().clear().commit()
        c.selectTool(ToolId.MASK)
        activity.setContent { BrushworkTheme { CompositionLocalProvider(LocalIncrements provides c.increments) { ToolOptionsBar(c) } } }
        SmokeUi.settle()
        val density = activity.resources.displayMetrics.density
        val width = activity.window.decorView.width
        assertEquals("a 360 dp window", 360f, width / density, 1f)
        val minTarget = 40f * density - 1f

        /** [e]'s whole extent (not clipped by a scroller) in window px. */
        fun extent(e: RobolectricUi.Element): androidx.compose.ui.geometry.Rect {
            val p = e.node.positionInWindow
            return androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + e.node.size.width, p.y + e.node.size.height)
        }
        fun assertFits(label: String, e: RobolectricUi.Element) {
            val b = extent(e)
            assertTrue("\"$label\" inside the 360 dp window: $b (width $width)", b.left >= -1f && b.right <= width + 1f)
            assertTrue("\"$label\" is finger-sized: $b", b.height >= minTarget)
        }

        // A radial makes a Tone layer.
        SmokeUi.click("+ Radial", exact = true)
        c.pointerDown(ToolPoint(200f, 150f)); c.pointerMove(ToolPoint(230f, 150f)); c.pointerMove(ToolPoint(260f, 150f)); c.pointerUp(ToolPoint(260f, 150f))
        SmokeUi.settle()
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)

        // Components: the switch's row (label, description and switch) fits the width.
        SmokeUi.click("Components (1)", exact = true)
        val label = SmokeUi.find(FAST_ADJUST_PREVIEW_LABEL, exact = true) ?: throw AssertionError("no switch; shown: ${SmokeUi.shown()}")
        // The toggleable row around the label (the whole row is the touch target).
        var row: SemanticsNode? = label.node
        while (row != null && row.config.getOrNull(SemanticsProperties.ToggleableState) == null) row = row.parent
        val toggle = RobolectricUi.Element(requireNotNull(row) { "the label is in no switch row" }, label.window)
        assertEquals(ToggleableState.On, toggle.node.config.getOrNull(SemanticsProperties.ToggleableState))
        assertFits(FAST_ADJUST_PREVIEW_LABEL, toggle)
        assertTrue("the switch is the whole row's width", extent(toggle).width >= width * 0.6f)
        SmokeUi.click("Close", exact = true)

        // Adjust: Exposure's −/+ beside its slider. Their semantics are the icons, centred in the
        // 40 dp buttons (RepeatIconButton): the whole button must lie inside the window.
        SmokeUi.click("Adjust…", exact = true)
        val plus = RobolectricUi.byDescription("Increase Exposure")
        val minus = RobolectricUi.byDescription("Decrease Exposure")
        val half = minTarget / 2f
        for ((name, e) in listOf("Increase Exposure" to plus, "Decrease Exposure" to minus)) {
            val c0 = extent(e).center
            assertTrue("\"$name\"'s 40 dp button inside the 360 dp window: $c0 (width $width)", c0.x - half >= -1f && c0.x + half <= width + 1f)
        }
        assertTrue("− and + a button apart, the slider between", extent(plus).center.x - extent(minus).center.x >= width * 0.5f)

        // Increments on, Exposure in 0.25 EV: one press of + goes to 0.25 EV.
        c.increments.update { it.copy(enabled = true).withCustom("Exposure|EV", 0.25f) }
        SmokeUi.settle()
        val before = c.undoManager.undoCount
        val b = extent(RobolectricUi.byDescription("Increase Exposure"))
        RobolectricUi.tap(plus.window, b.center.x, b.center.y)
        SmokeUi.settle()
        assertTrue("the slider shows +0.25 EV: ${SmokeUi.shown()}", SmokeUi.shown().any { "0.25" in it && "EV" in it })
        SmokeUi.click("Close", exact = true)
        assertEquals("I2: one step for the press", before + 1, c.undoManager.undoCount)
        assertEquals("Edit adjustment", c.undoManager.undoLabel)
        c.liveAdjust.release()
        Smoke.assertQuiet(c, "mask adjustment at 360 dp")
    }
}
