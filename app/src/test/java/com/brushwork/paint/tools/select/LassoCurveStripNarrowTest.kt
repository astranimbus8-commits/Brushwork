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
 * The lasso's options strip on a narrow (360 dp) phone: the three mode chips, and while curve
 * points are placed the ✓ / ✕, undo / redo and the selected point's sharp / delete actions stay
 * on the strip's first screen. Switching modes explains the mode in a message, because the
 * strip's own hint lies past the labelled chips.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.select.lassonarrowsandbox"])
class LassoCurveStripNarrowTest {

    /** The element labelled [label] (an icon or a chip's text) is fully inside the first screen. */
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
        activity.setContent { BrushworkTheme { Surface { ToolOptionsBar(c) } } }
        settle()
        val width = activity.window.decorView.width
        assertTrue(width > 0)

        for (label in listOf("Freehand", "Polygon", "Curve")) assertOnFirstScreen(label, width)
        click("Curve", exact = true)
        assertEquals(LassoKind.CURVE, tool.kind)
        assertTrue("switching explains the mode: ${c.message}", c.message?.contains("tap the first one to close") == true)
        c.message = null
        // Picking the current mode again says nothing.
        click("Curve", exact = true)
        assertEquals(null, c.message)

        fun tap(x: Float, y: Float) { c.pointerDown(ToolPoint(x, y)); c.pointerUp(ToolPoint(x, y)) }
        tap(80f, 50f); tap(320f, 50f); tap(320f, 190f)
        settle()
        for (label in listOf("Curve lasso", "Undo last point", "Redo point", "Discard curve", "Close curve")) assertOnFirstScreen(label, width)

        c.pointerDown(ToolPoint(320f, 50f))
        assertTrue(c.pointerLongPress(ToolPoint(320f, 50f)))
        c.pointerUp(ToolPoint(320f, 50f))
        settle()
        assertOnFirstScreen("Sharp", width)
        assertOnFirstScreen("Delete", width)

        // Deleting points down to none throws the curve away: nothing is left pending.
        click("Delete", exact = true)
        tool.curve.deleteAnchor(0)
        tool.curve.deleteAnchor(0)
        settle()
        assertFalse(tool.hasPendingWork)
        assertEquals(0, tool.curve.undoCount)
        assertTrue("the labelled chips are back", has("Freehand", exact = true))

        click("Polygon", exact = true)
        assertEquals(LassoKind.POLYGON, tool.kind)
        assertTrue("${c.message}", c.message?.contains("corners") == true)
        c.message = null
        click("Freehand", exact = true)
        assertEquals("freehand needs no explanation", null, c.message)
        Smoke.assertQuiet(c, "narrow lasso strip")
    }
}
