package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Wrap chip and the "Wrap around picture" sheet in a real activity at the user's phone size
 * (392 dp): the chip turns wrap on around the picture, every choice applies to the pending text,
 * a deleted picture is shown as such, vertical text is told it can't wrap.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.tools.text.wrapsheetsandbox"])
class TextWrapSheetUiRobolectricTest {

    @Test
    fun theWrapChipAndSheet() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val doc = Smoke.document(600, 800, layers = 2, whiteBottom = true)
        val picture = doc.layers[1]
        Canvas(picture.bitmap).drawCircle(220f, 400f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3366AA.toInt() })
        picture.markChanged()
        val c = Smoke.controller(activity, doc)
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        activity.setContent { BrushworkTheme { TextToolOptions(tool) } }
        tool.startTextAt(300f, 400f)
        tool.setText(WrapFixtures.LOREM)
        tool.updateSpec { it.copy(sizePx = 24f, box = it.box.copy(width = 500f)) }
        tool.confirmEditor()
        SmokeUi.settle()

        SmokeUi.click("Wrap around picture", exact = true)
        assertTrue(tool.wrapSheetOpen)
        SmokeUi.assertWindowsLaidOut(2)
        assertEquals("on around the picture", picture.id, tool.item!!.wrap.sourceLayerId)
        assertTrue(SmokeUi.has("Layer 2", exact = true))
        assertTrue("the background is listed too", SmokeUi.has("Layer 1", exact = true))
        assertTrue(SmokeUi.has("Distance"))

        SmokeUi.click("Both sides", exact = true)
        assertEquals(WrapSides.BOTH, tool.item!!.wrap.sides)
        SmokeUi.click("Box", exact = true)
        assertEquals(WrapContour.BOX, tool.item!!.wrap.contour)
        SmokeUi.click("Shape", exact = true)
        assertEquals(WrapContour.SHAPE, tool.item!!.wrap.contour)
        SmokeUi.click("Show outline", exact = true)
        assertFalse(tool.showWrapOutline)
        SmokeUi.click("Off", exact = true)
        assertFalse(tool.item!!.wrap.isOn)
        SmokeUi.click("Layer 2", exact = true)
        assertTrue(tool.item!!.wrapActive)
        assertEquals("nothing of this is history", 0, c.undoManager.undoCount)

        // Applied; the picture deleted; the text opened again: the sheet names the deleted layer.
        tool.commit()
        SmokeUi.settle()
        assertFalse(tool.wrapSheetOpen)
        val text = c.doc.layers.first { it.isTextLayer }
        c.deleteLayer(picture)
        assertTrue(tool.editLayer(text))
        tool.openWrapSheet()
        SmokeUi.settle()
        assertTrue(SmokeUi.has("(deleted layer)", exact = true))
        assertTrue(SmokeUi.has("keeps its last outline"))
        SmokeUi.click("Off", exact = true)
        assertFalse(tool.item!!.wrap.isOn)
        assertFalse(SmokeUi.has("(deleted layer)", exact = true))

        // Vertical text can't wrap: the chip explains it.
        tool.toggleVertical()
        SmokeUi.settle()
        tool.wrapSheetOpen = false
        SmokeUi.settle()
        SmokeUi.click("Wrap around picture (works with horizontal text)", exact = true)
        assertEquals(TextTool.WRAP_HORIZONTAL_ONLY, c.message)
        assertFalse(tool.wrapSheetOpen)
    }
}
