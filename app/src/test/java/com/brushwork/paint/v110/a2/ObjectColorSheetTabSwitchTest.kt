package com.brushwork.paint.v110.a2

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.10 (R2, R7; design 4.4 A2 step 1, review fix): the Object colors sheet on a 360 dp phone at
 * font scale 1.3 and 2.0. Its one row (both tabs, "No fill", the three paint-mode glyphs) and OK
 * are inside the sheet. A tab shown again never sends its picker's color to the object once more:
 * "No fill" and a paint-mode glyph chosen after a color stay when the person looks at the other
 * tab and comes back, and OK then adds no preview of its own.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.tabswitchsandbox"])
class ObjectColorSheetTabSwitchTest {

    private fun byTag(tag: String): RobolectricUi.Element =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag } ?: throw AssertionError("no $tag")

    private fun tapTag(tag: String) {
        requireNotNull(byTag(tag).node.config.getOrNull(SemanticsActions.OnClick)?.action) { "$tag is not clickable" }.invoke()
        SmokeUi.settle()
    }

    private fun tab(t: ObjectColorTarget) = "${V110Tags.COLORS_SHEET_TAB}.${t.name}"
    private fun mode(m: PaintMode) = "${V110Tags.COLORS_SHEET_MODE}.${m.name}"
    private fun selected(t: ObjectColorTarget) = byTag(tab(t)).node.config.getOrNull(SemanticsProperties.Selected)

    /** The row's controls and OK lie inside the sheet's panel, none cut at its sides. */
    private fun assertFits(where: String) {
        val panel = requireNotNull(SmokeUi.sheetPanel()) { "$where: no sheet" }.bounds
        val row = listOf(tab(ObjectColorTarget.FILL), tab(ObjectColorTarget.STROKE)) + PaintMode.entries.map { mode(it) }
        for (tag in row) {
            val b = byTag(tag).bounds
            assertTrue("$where: $tag $b inside the sheet $panel", b.left >= panel.left && b.right <= panel.right && b.top >= panel.top)
        }
        val none = requireNotNull(SmokeUi.find(ObjectColorLabels110.NO_FILL, exact = true)) { "$where: no \"No fill\"" }.bounds
        assertTrue("$where: No fill $none inside the sheet $panel", none.left >= panel.left && none.right <= panel.right)
        val ok = RobolectricUi.byText("OK").bounds
        assertTrue("$where: OK $ok inside the sheet $panel", ok.top >= panel.top && ok.bottom <= panel.bottom && ok.right <= panel.right)
    }

    private fun previews(editor: A2FakeColorEditor, t: ObjectColorTarget) = editor.calls.count { it.startsWith("preview $t ") }

    @Test
    fun aTabShownAgainKeepsNoFillAndThePaintModeAt360() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        val editor = A2FakeColorEditor(fill = 0xFF2266AA.toInt(), stroke = 0xFF000000.toInt(), mode = PaintMode.BOTH)
        var fontScale by mutableFloatStateOf(1.3f)
        var sheetOpen by mutableStateOf(true)
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                BrushworkTheme {
                    val h = rememberSheetHostState()
                    CompositionLocalProvider(LocalSheetHost provides h) {
                        Box(Modifier.fillMaxSize()) {
                            SheetHost(h, bottomInset = 0.dp)
                            SheetPill(h, Modifier.align(Alignment.BottomCenter))
                        }
                        if (sheetOpen) ObjectColorsSheet(editor, onClose = { sheetOpen = false })
                    }
                }
            }
        }
        SmokeUi.settle()
        assertEquals(listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
        assertFits("360 dp, font scale 1.3")

        // A fill color, then "No fill": the paint mode is Stroke.
        assertEquals(true, selected(ObjectColorTarget.FILL))
        RobolectricUi.textFields().first().type("FF0000")
        SmokeUi.settle()
        assertEquals(0xFFFF0000.toInt(), editor.fill)
        SmokeUi.click(ObjectColorLabels110.NO_FILL, exact = true)
        assertEquals(PaintMode.STROKE, editor.mode)
        assertEquals(listOf("begin", "preview FILL FFFF0000", "none FILL"), editor.calls)

        // The Stroke tab and back: the fill's picker does not paint the fill again.
        tapTag(tab(ObjectColorTarget.STROKE))
        assertEquals(true, selected(ObjectColorTarget.STROKE))
        tapTag(tab(ObjectColorTarget.FILL))
        assertEquals(true, selected(ObjectColorTarget.FILL))
        assertEquals("No fill stays", PaintMode.STROKE, editor.mode)
        assertEquals("no fill preview again", 1, previews(editor, ObjectColorTarget.FILL))

        // A stroke color, then the Fill-only glyph; the Fill tab and back: Fill only stays.
        tapTag(tab(ObjectColorTarget.STROKE))
        RobolectricUi.textFields().first().type("00FF00")
        SmokeUi.settle()
        assertEquals(0xFF00FF00.toInt(), editor.stroke)
        tapTag(mode(PaintMode.FILL))
        assertEquals(PaintMode.FILL, editor.mode)
        tapTag(tab(ObjectColorTarget.FILL))
        tapTag(tab(ObjectColorTarget.STROKE))
        assertEquals("Fill only stays", PaintMode.FILL, editor.mode)
        assertEquals("no stroke preview again", 1, previews(editor, ObjectColorTarget.STROKE))
        assertEquals("no fill preview again", 1, previews(editor, ObjectColorTarget.FILL))
        assertEquals(listOf("begin", "preview FILL FFFF0000", "none FILL", "preview STROKE FF00FF00", "mode FILL"), editor.calls)

        // Font scale 2.0 (on the Fill tab, which shows "No fill"): the row and OK still fit.
        tapTag(tab(ObjectColorTarget.FILL))
        fontScale = 2f
        SmokeUi.settle()
        assertFits("360 dp, font scale 2.0")
        assertEquals("a font change sends nothing", 5, editor.calls.size)

        // OK keeps what the object shows: no preview of its own.
        SmokeUi.clickIn(ObjectColorLabels110.CELL, "OK")
        SmokeUi.settle()
        assertEquals(listOf("begin", "preview FILL FFFF0000", "none FILL", "preview STROKE FF00FF00", "mode FILL", "commit"), editor.calls)
        assertEquals(PaintMode.FILL, editor.mode)
        assertTrue("closed: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
    }
}
