package com.brushwork.paint.v110.a2

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
 * v1.10 (R2, R7, J21; design 4.4 A2 step 1): the Stroke tab and a paint-mode glyph act on the
 * object live; a minimize (the host's, as an outside touch does) keeps them; the object's session
 * then ends with ✕: the sheet goes without a call of its own and the object is back as it was.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.minimizedcrosssandbox"])
class ObjectColorSheetMinimizedThenCrossTest {

    private fun tapTag(tag: String) {
        val e = RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }
        requireNotNull(e.node.config.getOrNull(SemanticsActions.OnClick)?.action) { "$tag is not clickable" }.invoke()
    }

    @Test
    fun minimizeKeepsThePreviewAndCrossRestoresTheObject() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        val blue = 0xFF2266AA.toInt()
        val black = 0xFF000000.toInt()
        val editor = A2FakeColorEditor(fill = blue, stroke = black, mode = PaintMode.FILL)
        var sheetOpen by mutableStateOf(true)
        var minimize: () -> Unit = {}
        activity.setContent {
            BrushworkTheme {
                val h = rememberSheetHostState()
                minimize = h::minimize
                CompositionLocalProvider(LocalSheetHost provides h) {
                    Box(Modifier.fillMaxSize()) {
                        SheetHost(h, bottomInset = 0.dp)
                        SheetPill(h, Modifier.align(Alignment.BottomCenter))
                    }
                    if (sheetOpen && editor.sessionOpen) ObjectColorsSheet(editor, onClose = { sheetOpen = false })
                }
            }
        }
        SmokeUi.settle()

        // The Stroke tab, then a color: the stroke shows it live, and a Fill-only object paints both.
        tapTag("${V110Tags.COLORS_SHEET_TAB}.${ObjectColorTarget.STROKE.name}")
        SmokeUi.settle()
        RobolectricUi.textFields().first().type("00FF00")
        SmokeUi.settle()
        assertEquals("live stroke", 0xFF00FF00.toInt(), editor.stroke)
        assertEquals("a stroke color on a Fill-only object paints both", PaintMode.BOTH, editor.mode)
        // A paint-mode glyph applies at once too.
        tapTag("${V110Tags.COLORS_SHEET_MODE}.${PaintMode.STROKE.name}")
        SmokeUi.settle()
        assertEquals(PaintMode.STROKE, editor.mode)
        assertEquals(listOf("begin", "preview STROKE FF00FF00", "mode STROKE"), editor.calls)

        // Minimized (as by a touch outside the sheet): everything previewed stays.
        minimize()
        SmokeUi.settle()
        assertEquals(listOf(ObjectColorLabels110.CELL), SmokeUi.pillTitles())
        assertEquals(0xFF00FF00.toInt(), editor.stroke)
        assertEquals(PaintMode.STROKE, editor.mode)

        // The session's ✕: the sheet goes, the object is as it was.
        editor.cross()
        SmokeUi.settle()
        assertTrue("closed: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
        assertEquals(black, editor.stroke)
        assertEquals(blue, editor.fill)
        assertEquals(PaintMode.FILL, editor.mode)
        assertEquals("no Cancel and no OK of the sheet", listOf("begin", "preview STROKE FF00FF00", "mode STROKE"), editor.calls)
        assertEquals("no step", 0, editor.steps)
    }
}
