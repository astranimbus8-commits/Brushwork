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
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.canRedoNow
import com.brushwork.paint.ui.selection.lassoKindHint
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
 * The lasso's options strip on the user's phone size (v1.8, design §3.9): the kind dropdown
 * ("<kind> lasso", its entries Freehand / Polygon / Curve), and in curve mode ✓ with the point
 * count, ✕ and the long-pressed point's sharp / smooth / delete, all on row 1 at 392 dp while
 * points are being placed; the v1.7 "how to" text is the More row's hint (v1.8, F4: undo / redo
 * of a point edit are the top row's, called here as it calls them).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.select.lassostripsandbox"])
class LassoCurveStripTest {

    /** The element labelled [label] lies fully inside the screen (no scrolling). */
    private fun assertOnFirstScreen(label: String, width: Int) {
        val e = find(label, exact = true) ?: throw AssertionError("\"$label\" is not shown; shown: ${SmokeUi.shown()}")
        assertTrue("\"$label\" at ${e.bounds} fits the ${width}px wide screen", e.bounds.left >= 0f && e.bounds.right <= width)
    }

    /** The More row's hint contains [text]. */
    private fun hintSays(text: String): Boolean {
        assertTrue(StripTestKit.openMoreRow())
        val shown = has(text)
        assertTrue(StripTestKit.closeMoreRow())
        return shown
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

        // The kind dropdown on row 1; its three entries on the screen.
        assertEquals(LassoKind.FREEHAND, tool.kind)
        assertOnFirstScreen("Freehand lasso", width)
        click("Freehand lasso", exact = true)
        for (label in listOf("Freehand", "Polygon", "Curve")) assertOnFirstScreen(label, width)
        click("Curve", exact = true)
        assertEquals(LassoKind.CURVE, tool.kind)
        assertFalse("the dropdown closed", has("Polygon", exact = true))
        assertTrue(hintSays("Tap points"))
        // The hint lies on the More row: switching says it too.
        assertEquals(lassoKindHint(LassoKind.CURVE), c.message)
        c.message = null

        fun tap(x: Float, y: Float) { c.pointerDown(ToolPoint(x, y)); c.pointerUp(ToolPoint(x, y)) }
        tap(80f, 50f); tap(320f, 50f); tap(320f, 190f)
        settle()
        assertTrue(has("3 pt", exact = true))
        assertTrue(has("Curve lasso", exact = true))
        assertFalse(has("Freehand", exact = true))
        assertOnFirstScreen("Close curve", width)
        assertOnFirstScreen("Discard curve", width)
        assertFalse("no point selected yet", has("Sharp", exact = true))

        // A long press on a point shows its actions, on row 1 too (392 dp: no AUTO item leaves).
        c.pointerDown(ToolPoint(320f, 50f))
        assertTrue(c.pointerLongPress(ToolPoint(320f, 50f)))
        c.pointerUp(ToolPoint(320f, 50f))
        settle()
        assertEquals(1, tool.curve.selected)
        assertOnFirstScreen("Sharp", width)
        assertOnFirstScreen("Delete", width)
        assertFalse("row 1 holds them", StripTestKit.moreRowShown())
        click("Sharp", exact = true)
        assertTrue(tool.curve.anchors[1].sharp)
        assertTrue("the button now offers the way back", has("Smooth", exact = true))
        // v1.8 (F4, V9): the top row's Undo / Redo take back and bring back one point edit (this
        // rig shows the strip alone, so they are called as the top row calls them).
        HistoryLabels.performUndo(c)
        settle()
        assertFalse(tool.curve.anchors[1].sharp)
        assertTrue("the top row's Redo is enabled", canRedoNow(c))
        HistoryLabels.performRedo(c)
        settle()
        assertTrue(tool.curve.anchors[1].sharp)
        click("Delete", exact = true)
        assertEquals(2, tool.curve.count)
        assertTrue(has("2 pt", exact = true))
        assertFalse("✓ needs three points", SmokeUi.isEnabled("Close curve"))
        HistoryLabels.performUndo(c)
        settle()
        assertEquals(3, tool.curve.count)

        // ✓ closes the curve into a selection; ✓ and ✕ hide, the hint comes back.
        click("Close curve")
        assertTrue(Smoke.pumpUntil { c.selection != null && !tool.busy })
        settle()
        assertFalse(tool.hasPendingWork)
        assertFalse(has("Close curve"))
        assertTrue(hintSays("Tap points"))

        // ✕ throws an unfinished curve away.
        tap(80f, 50f); tap(320f, 50f)
        settle()
        click("Discard curve")
        assertFalse(tool.hasPendingWork)

        // The other modes are two taps away.
        click("Curve lasso", exact = true)
        click("Polygon", exact = true)
        assertEquals(LassoKind.POLYGON, tool.kind)
        assertTrue(hintSays("Tap corners"))
        assertEquals(lassoKindHint(LassoKind.POLYGON), c.message)
        click("Polygon lasso", exact = true)
        click("Freehand", exact = true)
        assertEquals(LassoKind.FREEHAND, tool.kind)
        c.deselect()
        settle()
        Smoke.assertQuiet(c, "lasso strip")
    }
}
