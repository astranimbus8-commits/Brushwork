package com.brushwork.paint.v110.a2

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.SheetHost
import com.brushwork.paint.ui.common.SheetPill
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.rememberSheetHostState
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.vector.ObjectColorsSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.10 (R2, R7; design 4.4 A2 step 1, review fix): the Object colors sheet on an object whose fill
 * is refused (a curve with 1 or 2 points: [CurveLabels17.FILL_NEEDS_3]) opens on its Stroke tab,
 * so the wheel acts; a tap on the Fill tab or on the Fill and Both glyphs says why and changes
 * nothing. Cancel restores the colors the sheet opened with; opened again (the fill possible now,
 * so on its Fill tab), Back does too.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.refusedfillsandbox"])
class ObjectColorSheetRefusedFillTest {

    private fun byTag(tag: String): RobolectricUi.Element =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag } ?: throw AssertionError("no $tag")

    private fun tapTag(tag: String) {
        requireNotNull(byTag(tag).node.config.getOrNull(SemanticsActions.OnClick)?.action) { "$tag is not clickable" }.invoke()
        SmokeUi.settle()
    }

    private fun tab(t: ObjectColorTarget) = "${V110Tags.COLORS_SHEET_TAB}.${t.name}"
    private fun mode(m: PaintMode) = "${V110Tags.COLORS_SHEET_MODE}.${m.name}"
    private fun selected(tag: String) = byTag(tag).node.config.getOrNull(SemanticsProperties.Selected)

    @Test
    fun aRefusedFillOpensOnStrokeSaysWhyAndCancelAndBackRestore() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        val blue = 0xFF2266AA.toInt()
        val black = 0xFF000000.toInt()
        val editor = A2FakeColorEditor(fill = blue, stroke = black, mode = PaintMode.STROKE)
        editor.fillRefusal = CurveLabels17.FILL_NEEDS_3
        var sheetOpen by mutableStateOf(true)
        var opening by mutableIntStateOf(0)
        activity.setContent {
            BrushworkTheme {
                val h = rememberSheetHostState()
                // Back as the editor maps it (EditorScreen): the top sheet's dismiss.
                BackHandler(enabled = !h.isEmpty) { h.dismissTop() }
                CompositionLocalProvider(LocalSheetHost provides h) {
                    Box(Modifier.fillMaxSize()) {
                        SheetHost(h, bottomInset = 0.dp)
                        SheetPill(h, Modifier.align(Alignment.BottomCenter))
                    }
                    key(opening) { if (sheetOpen) ObjectColorsSheet(editor, onClose = { sheetOpen = false }) }
                }
            }
        }
        SmokeUi.settle()

        // The fill is refused: the sheet opens on the Stroke tab, and the wheel paints the stroke.
        assertEquals(listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
        assertEquals("the Stroke tab is selected", true, selected(tab(ObjectColorTarget.STROKE)))
        assertEquals(false, selected(tab(ObjectColorTarget.FILL)))
        RobolectricUi.textFields().first().type("00FF00")
        SmokeUi.settle()
        assertEquals("live stroke", 0xFF00FF00.toInt(), editor.stroke)
        assertEquals(listOf("begin", "preview STROKE FF00FF00"), editor.calls)

        // The Fill tab and the Fill and Both glyphs say why and change nothing.
        tapTag(tab(ObjectColorTarget.FILL))
        assertEquals(listOf(CurveLabels17.FILL_NEEDS_3), editor.toasts)
        assertEquals("still on the Stroke tab", true, selected(tab(ObjectColorTarget.STROKE)))
        for (m in listOf(PaintMode.FILL, PaintMode.BOTH)) {
            val n = byTag(mode(m)).node
            assertTrue("${m.name} refused: disabled", n.config.contains(SemanticsProperties.Disabled))
            assertEquals("${m.name} refused: says why", CurveLabels17.FILL_NEEDS_3, n.config.getOrNull(SemanticsProperties.StateDescription))
            tapTag(mode(m))
        }
        assertEquals(List(3) { CurveLabels17.FILL_NEEDS_3 }, editor.toasts)
        assertNull("Stroke is never refused", byTag(mode(PaintMode.STROKE)).node.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(PaintMode.STROKE, editor.mode)
        assertEquals("no mode was set", listOf("begin", "preview STROKE FF00FF00"), editor.calls)

        // Cancel: the colors the sheet opened with, and the sheet goes.
        SmokeUi.clickIn(ObjectColorLabels110.CELL, "Cancel")
        SmokeUi.settle()
        assertEquals("cancel", editor.calls.last())
        assertEquals(black, editor.stroke)
        assertEquals(blue, editor.fill)
        assertTrue("closed: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

        // Opened again with the fill possible: on the Fill tab; Back restores too.
        editor.fillRefusal = null
        opening++
        sheetOpen = true
        SmokeUi.settle()
        assertEquals(listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
        assertEquals("the Fill tab is selected", true, selected(tab(ObjectColorTarget.FILL)))
        assertTrue("Fill possible: enabled", !byTag(mode(PaintMode.FILL)).node.config.contains(SemanticsProperties.Disabled))
        RobolectricUi.textFields().first().type("FF0000")
        SmokeUi.settle()
        assertEquals("live fill", 0xFFFF0000.toInt(), editor.fill)
        assertEquals("a fill color on a Stroke-only object paints both", PaintMode.BOTH, editor.mode)
        activity.onBackPressedDispatcher.onBackPressed()
        SmokeUi.settle()
        assertEquals(listOf("begin", "preview FILL FFFF0000", "cancel"), editor.calls.takeLast(3))
        assertEquals(blue, editor.fill)
        assertEquals(PaintMode.STROKE, editor.mode)
        assertTrue("closed by Back", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
        assertEquals("no other toast", 3, editor.toasts.size)
    }
}
