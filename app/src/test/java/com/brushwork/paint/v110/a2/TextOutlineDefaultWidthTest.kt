package com.brushwork.paint.v110.a2

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextColorsDelegate
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.placement.TextEditorDialog
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.10 (R2, R7; design 4.4 A2 step 5): picking an outline color for a text without an outline
 * (width 0) gives it the default outline width, so the color shows: in the Object colors editor
 * and in the text editor's Outline color picker alike. A width the text has stays; the
 * no-outline action sets the width to 0; Cancel puts back the width and the color. The text
 * editor's color pickers preview live on the open text, and their Cancel restores it.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.outlinewidthsandbox"])
class TextOutlineDefaultWidthTest {

    private val white = 0xFFFFFFFF.toInt()

    /** The clickable swatch on the row of the text [label] (left of it), found in layout order (it may be scrolled off). */
    private fun swatchBeside(label: String): RobolectricUi.Element {
        val text = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(80)}")
        val tTop = text.node.positionInRoot.y
        val tBottom = tTop + text.node.size.height
        val tLeft = text.node.positionInRoot.x
        return RobolectricUi.elements().filter { e ->
            val n = e.node
            e.window === text.window && n.config.getOrNull(SemanticsActions.OnClick) != null &&
                n.positionInRoot.x + n.size.width <= tLeft + 1f &&
                n.positionInRoot.y < tBottom && n.positionInRoot.y + n.size.height > tTop
        }.maxByOrNull { it.node.positionInRoot.x } ?: throw AssertionError("no swatch beside \"$label\"")
    }

    private fun RobolectricUi.Element.click() {
        requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        SmokeUi.settle()
    }

    private fun typeHex(argb: Int) {
        SmokeUi.field("Hex").type(hex(argb))
        SmokeUi.settle()
    }

    @Test
    fun anOutlineColorAtWidthZeroBringsTheDefaultWidth() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        val c: EditorController = Smoke.controller(activity, Smoke.document(512, 384))
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        val drawing = c.color
        tool.startTextAt(256f, 192f)
        tool.setText("Hi")
        tool.updateSpec { it.copy(sizePx = 50f, color = BLACK, strokeWidthPx = 0f, strokeColor = white) }
        val spec: () -> TextSpec = { tool.item!!.spec }
        val defaultWidth = TextColorsDelegate.defaultOutlineWidth(50f)
        assertEquals("6 % of the font size", 3f, defaultWidth, 1e-4f)
        assertEquals("never under 1 px", 1f, TextColorsDelegate.defaultOutlineWidth(8f), 0f)

        // The Object colors editor (the pill's sheet).
        val ed = tool.objectColors ?: throw AssertionError("an open text has colors")
        ed.begin()
        assertEquals(ObjectColorFace.None, ed.face(ObjectColorTarget.OUTLINE))
        ed.preview(ObjectColorTarget.OUTLINE, GREEN)
        assertEquals("width 0: the default width", defaultWidth, spec().strokeWidthPx, 1e-4f)
        assertEquals(GREEN, spec().strokeColor)
        assertEquals(ObjectColorFace.Solid(GREEN), ed.face(ObjectColorTarget.OUTLINE))
        tool.updateSpec { it.copy(strokeWidthPx = 5f) }
        ed.preview(ObjectColorTarget.OUTLINE, BLUE)
        assertEquals("a width the text has stays", 5f, spec().strokeWidthPx, 0f)
        ed.setNone(ObjectColorTarget.OUTLINE)
        assertEquals("no outline: width 0", 0f, spec().strokeWidthPx, 0f)
        assertEquals(ObjectColorFace.None, ed.face(ObjectColorTarget.OUTLINE))
        assertEquals("the outline color is kept", BLUE, spec().strokeColor)
        ed.preview(ObjectColorTarget.TEXT, RED)
        ed.cancel()
        assertEquals("Cancel: width back", 0f, spec().strokeWidthPx, 0f)
        assertEquals("Cancel: outline color back", white, spec().strokeColor)
        assertEquals("Cancel: text color back", BLACK, spec().color)

        // The text editor's Outline color picker: live on the open text, the default width at 0.
        activity.setContent { BrushworkTheme { TextEditorDialog(tool) } }
        SmokeUi.settle(20, 50)
        val outlineRow = "Outline color (drawn behind the text)"
        swatchBeside(outlineRow).click()
        assertTrue("the picker is open: ${SmokeUi.shown().take(60)}", SmokeUi.has(ObjectColorLabels110.OUTLINE, exact = true))
        typeHex(GREEN)
        assertEquals("live", hex(GREEN), hex(spec().strokeColor))
        assertEquals("live, with the default width", defaultWidth, spec().strokeWidthPx, 1e-4f)
        SmokeUi.clickIn(ObjectColorLabels110.OUTLINE, "Cancel")
        assertEquals("the picker's Cancel: color back", hex(white), hex(spec().strokeColor))
        assertEquals("the picker's Cancel: width back", 0f, spec().strokeWidthPx, 0f)
        assertTrue("the text editor stays open", tool.editorOpen)

        swatchBeside(outlineRow).click()
        typeHex(BLUE)
        SmokeUi.clickIn(ObjectColorLabels110.OUTLINE, "OK")
        assertEquals("OK keeps it", hex(BLUE), hex(spec().strokeColor))
        assertEquals(defaultWidth, spec().strokeWidthPx, 1e-4f)

        // The Text color picker previews live too, and its Cancel restores.
        swatchBeside(ObjectColorLabels110.TEXT).click()
        typeHex(RED)
        assertEquals("live", hex(RED), hex(spec().color))
        SmokeUi.clickIn(ObjectColorLabels110.TEXT, "Cancel")
        assertEquals("the picker's Cancel", hex(BLACK), hex(spec().color))
        assertEquals("the outline OK'd before stays", hex(BLUE), hex(spec().strokeColor))
        assertEquals("the drawing color is not touched", hex(drawing), hex(c.color))
    }
}
