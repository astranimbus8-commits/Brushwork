package com.brushwork.paint.tools.select

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.find
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
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
 * The lasso's options strip on a narrow (360 dp) phone (v1.8, design §3.9): the kind dropdown and
 * its entries, and while curve points are placed ✓ / ✕ on row 1; the selected point's sharp /
 * delete stay one tap away (AUTO: "Sharp" fronts the More row here, "Delete" keeps row 1).
 * Switching kinds explains the kind in a message.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.select.lassonarrowsandbox"])
class LassoCurveStripNarrowTest {

    /** The element labelled [label] (an icon or an entry's text) is fully on the screen. */
    private fun assertOnFirstScreen(label: String, width: Int) {
        val e = find(label, exact = true) ?: throw AssertionError("\"$label\" is not shown; shown: ${SmokeUi.shown()}")
        val b = e.bounds
        assertTrue("\"$label\" at $b fits the ${width}px wide screen", b.width > 0f && b.left >= 0f && b.right <= width)
    }

    @Test
    fun modeChipsAndCurvePointActionsFitA360dpPhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300))
        c.selectTool(ToolId.LASSO)
        val tool = c.tools.getValue(ToolId.LASSO) as LassoTool
        // The editor's panel keeps 6 dp off each screen edge (the strip's width there).
        activity.setContent { BrushworkTheme { Surface(Modifier.padding(horizontal = 6.dp)) { ToolOptionsBar(c) } } }
        settle()
        val width = activity.window.decorView.width
        assertTrue(width > 0)

        click("Freehand lasso", exact = true)
        for (label in listOf("Freehand", "Polygon", "Curve")) assertOnFirstScreen(label, width)
        click("Curve", exact = true)
        assertEquals(LassoKind.CURVE, tool.kind)
        assertTrue("switching explains the kind: ${c.message}", c.message?.contains("tap the first one to close") == true)
        c.message = null
        // Picking the current kind again says nothing.
        click("Curve lasso", exact = true)
        click("Curve", exact = true)
        assertEquals(null, c.message)

        fun tap(x: Float, y: Float) { c.pointerDown(ToolPoint(x, y)); c.pointerUp(ToolPoint(x, y)) }
        tap(80f, 50f); tap(320f, 50f); tap(320f, 190f)
        settle()
        // (v1.8, F4: the point Undo / Redo are the top row's, no longer on the strip.)
        for (label in listOf("Curve lasso", "Discard curve", "Close curve")) assertOnFirstScreen(label, width)
        assertFalse(StripTestKit.moreRowShown())

        c.pointerDown(ToolPoint(320f, 50f))
        assertTrue(c.pointerLongPress(ToolPoint(320f, 50f)))
        c.pointerUp(ToolPoint(320f, 50f))
        settle()
        assertTrue("\"Delete\" on row 1", has("Delete", exact = true))
        assertFalse("\"Sharp\" left row 1", has("Sharp", exact = true))
        assertOnFirstScreen("Sharp", width) // opens the More row
        assertOnFirstScreen("Delete", width)
        assertTrue(StripTestKit.closeMoreRow())

        // Deleting points down to none throws the curve away: nothing is left pending.
        click("Delete", exact = true)
        tool.curve.deleteAnchor(0)
        tool.curve.deleteAnchor(0)
        settle()
        assertFalse(tool.hasPendingWork)
        assertEquals(0, tool.curve.undoCount)
        assertFalse("✓ and ✕ hide", has("Close curve") || has("Discard curve"))

        click("Curve lasso", exact = true)
        click("Polygon", exact = true)
        assertEquals(LassoKind.POLYGON, tool.kind)
        assertTrue("${c.message}", c.message?.contains("corners") == true)
        c.message = null
        click("Polygon lasso", exact = true)
        click("Freehand", exact = true)
        assertEquals("freehand needs no explanation", null, c.message)
        Smoke.assertQuiet(c, "narrow lasso strip")
    }
}
