package com.brushwork.paint.qa3

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Final QA (v1.5 §4.9 Curve row, §4.11): a path with a GRADIENT fill (from an imported SVG)
 * opened again in the Curve tool. Its settings said "Same as the main color" for the fill, while
 * the path keeps its gradient: a user reading that expects ✓ to paint it in the main color. The
 * sheet now says the gradient is kept (and picking a color replaces it); ✓ keeps the gradient.
 *
 * Own sandbox; all UI work in ONE test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.curvegradientsandbox"])
class Qa3CurveGradientFillUiRobolectricTest {

    @Test
    fun aReopenedGradientFillSaysItIsKept() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
            c.snapping.enabled = false
            activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
            settle()
            c.tools
            settle()
            val layer = c.addVectorLayer()!!
            val gradient = VPaint.Linear(100f, 0f, 300f, 0f, listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0xFF0000FF.toInt())))
            val blob = VPath(
                0,
                subpaths = listOf(VSubpath(listOf(VAnchor(100f, 100f), VAnchor(300f, 100f), VAnchor(300f, 220f), VAnchor(100f, 220f)), closed = true)),
                fill = gradient,
            )
            c.vectors.addObjects(layer, listOf(blob), "Import")
            c.settleVectorWork()
            settle()
            c.selectTool(ToolId.CURVE)
            val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
            settle()
            c.pointerDown(ToolPoint(200f, 160f))
            c.pointerUp(ToolPoint(200f, 160f))
            Smoke.pumpUntil { tool.isReopened }
            settle()
            assertTrue("reopened", tool.isReopened)
            SmokeUi.click("Settings", exact = true)
            SmokeUi.assertPanelShown("Curve")
            assertFalse("the fill is not \"the main color\"", SmokeUi.has("Same as the main color", exact = true))
            assertTrue("the sheet says the gradient is kept; shown: ${SmokeUi.shown()}", SmokeUi.has("Gradient (kept)", exact = true))
            SmokeUi.click("Close", exact = true)
            val steps = c.undoManager.undoCount
            tool.commit()
            c.settleVectorWork()
            settle()
            assertEquals("unchanged: nothing recorded", steps, c.undoManager.undoCount)
            assertEquals("the gradient stays", gradient, (layer.vector!!.objects.single() as VPath).fill)
        } finally {
            runCatching { ctl.pause().stop().destroy() }
        }
    }
}
