package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Wrap around picture" sheet on a narrow phone (360 dp): every control fits the width (no
 * horizontal scrolling of the page), the layer rows are finger-sized, and the Wrap chip with a
 * text layer active (nothing pending) opens that text and the sheet.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.wrapsheetnarrowsandbox"])
class TextWrapSheetNarrowUiRobolectricTest {

    @Test
    fun theSheetFitsA360dpPhone() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val doc = Smoke.document(600, 800, layers = 3, whiteBottom = true)
        val picture = doc.layers[1]
        doc.layers[1].name = "A picture layer with a rather long name that must be cut"
        Canvas(picture.bitmap).drawCircle(220f, 400f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3366AA.toInt() })
        picture.markChanged()
        val c = Smoke.controller(activity, doc)
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        activity.setContent { BrushworkTheme { TextToolOptions(tool) } }

        // A text placed and committed: its layer is active, nothing is pending.
        tool.startTextAt(300f, 400f)
        tool.setText(WrapFixtures.LOREM)
        tool.updateSpec { it.copy(sizePx = 24f, box = it.box.copy(width = 500f)) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val text = c.activeLayer
        assertTrue(text.isTextLayer)
        SmokeUi.settle()

        // The chip opens that text and wraps it around the picture.
        SmokeUi.click("Wrap around picture", exact = true)
        assertSame(text, tool.editingLayer)
        assertTrue(tool.wrapSheetOpen)
        assertEquals(picture.id, tool.item!!.wrap.sourceLayerId)

        val widthPx = activity.window.decorView.width
        assertTrue(widthPx > 0)
        val density = activity.resources.displayMetrics.density
        // Where the sheet's controls are laid out (unclipped: the sheet scrolls vertically).
        for (label in listOf("Off", "Shape", "Box", "Largest side", "Both sides", "Left only", "Right only", "Show outline")) {
            val e = SmokeUi.find(label, exact = true)
            assertNotNull("\"$label\" is shown", e)
            val (left, right) = extent(e!!.node)
            assertTrue("\"$label\" fits the width: $left..$right in $widthPx px", left >= 0f && right <= widthPx + 0.5f)
        }
        // The long layer name is cut, not pushed off the screen.
        val (_, nameRight) = extent(SmokeUi.find("A picture layer", exact = false)!!.node)
        assertTrue("$nameRight", nameRight <= widthPx + 0.5f)
        // Finger-sized targets: the layer rows are 48 dp, the switch row at least 40 dp; the chips
        // are Material 3 chips (32 dp drawn, their touch area grown to 48 dp by Material).
        for (label in listOf("Layer 3", "Off")) assertTrue("\"$label\" row", clickable(label).size.height >= 48f * density - 1f)
        assertTrue("switch row", clickable("Show outline").size.height >= 40f * density - 1f)
        for (label in listOf("Right only", "Box")) {
            assertTrue("\"$label\" ${clickable(label).size}", clickable(label).size.height >= 32f * density - 1f)
        }
        SmokeUi.click("Right only", exact = true)
        assertEquals(WrapSides.RIGHT, tool.item!!.wrap.sides)
        SmokeUi.click("Layer 3", exact = true)
        assertEquals(doc.layers[2].id, tool.item!!.wrap.sourceLayerId)
        assertEquals("nothing of this is history yet", 1, c.undoManager.undoCount)
    }

    /** The clickable element labelled [label] (or its clickable ancestor). */
    private fun clickable(label: String): SemanticsNode {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)!!.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return requireNotNull(n) { "\"$label\" is not clickable" }
    }

    /** Left and right edge of [n] in the window, not clipped by a scrolling parent. */
    private fun extent(n: SemanticsNode): Pair<Float, Float> {
        val x = n.positionInWindow.x
        return x to x + n.size.width
    }
}
