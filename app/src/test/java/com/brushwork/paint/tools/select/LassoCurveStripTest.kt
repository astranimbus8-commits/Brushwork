package com.brushwork.paint.tools.select

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.find
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
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
import org.robolectric.shadows.ShadowLog

/**
 * The lasso's options strip on the user's phone size: Freehand / Polygon / Curve chips, and in
 * curve mode the point count, ✓ / ✕, undo / redo and the long-pressed point's sharp / smooth /
 * delete actions, all reachable on the strip's first screen while points are being placed.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.select.lassostripsandbox"])
class LassoCurveStripTest {

    /** The element labelled [label] lies fully inside the strip's first screen (no scrolling). */
    private fun assertOnFirstScreen(label: String, width: Int) {
        val e = find(label, exact = true) ?: throw AssertionError("\"$label\" is not shown; shown: ${SmokeUi.shown()}")
        assertTrue("\"$label\" at ${e.bounds} fits the ${width}px wide screen", e.bounds.left >= 0f && e.bounds.right <= width)
    }

    @Test
    fun modeChipsAndCurvePointActions() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300))
        c.selectTool(ToolId.LASSO)
        val tool = c.tools.getValue(ToolId.LASSO) as LassoTool
        activity.setContent { BrushworkTheme { Surface { ToolOptionsBar(c) } } }
        settle()
        val width = activity.window.decorView.width
        assertTrue(width > 0)

        // Three labelled chips, all on the first screen of the strip.
        assertEquals(LassoKind.FREEHAND, tool.kind)
        for (label in listOf("Freehand", "Polygon", "Curve")) assertOnFirstScreen(label, width)
        click("Curve", exact = true)
        assertEquals(LassoKind.CURVE, tool.kind)
        assertTrue(has("Tap points"))

        fun tap(x: Float, y: Float) { c.pointerDown(ToolPoint(x, y)); c.pointerUp(ToolPoint(x, y)) }
        tap(80f, 50f); tap(320f, 50f); tap(320f, 190f)
        settle()
        assertTrue(has("3 pt", exact = true))
        // While points are placed the picker is compact, so ✓ is on the first screen.
        assertTrue(has("Curve lasso", exact = true))
        assertFalse(has("Freehand", exact = true))
        assertOnFirstScreen("Close curve", width)
        assertFalse("no point selected yet", has("Sharp", exact = true))

        // A long press on a point shows its actions, on the first screen too.
        c.pointerDown(ToolPoint(320f, 50f))
        assertTrue(c.pointerLongPress(ToolPoint(320f, 50f)))
        c.pointerUp(ToolPoint(320f, 50f))
        settle()
        assertEquals(1, tool.curve.selected)
        assertOnFirstScreen("Sharp", width)
        assertOnFirstScreen("Delete", width)
        click("Sharp", exact = true)
        assertTrue(tool.curve.anchors[1].sharp)
        assertTrue("the chip now offers the way back", has("Smooth", exact = true))
        click("Undo last point")
        assertFalse(tool.curve.anchors[1].sharp)
        assertTrue(SmokeUi.isEnabled("Redo point"))
        click("Redo point")
        assertTrue(tool.curve.anchors[1].sharp)
        click("Delete", exact = true)
        assertEquals(2, tool.curve.count)
        assertFalse("✓ needs three points", SmokeUi.isEnabled("Close curve"))
        click("Undo last point")
        assertEquals(3, tool.curve.count)

        // ✓ closes the curve into a selection; the chips come back.
        click("Close curve")
        assertTrue(Smoke.pumpUntil { c.selection != null && !tool.busy })
        settle()
        assertFalse(tool.hasPendingWork)
        assertTrue(has("Freehand", exact = true))

        // ✕ throws an unfinished curve away.
        tap(80f, 50f); tap(320f, 50f)
        settle()
        click("Discard curve")
        assertFalse(tool.hasPendingWork)

        // The other modes are still one tap away.
        click("Polygon", exact = true)
        assertEquals(LassoKind.POLYGON, tool.kind)
        assertTrue(has("Tap corners"))
        click("Freehand", exact = true)
        assertEquals(LassoKind.FREEHAND, tool.kind)
        c.deselect()
        settle()
        Smoke.assertQuiet(c, "lasso strip")
    }
}
