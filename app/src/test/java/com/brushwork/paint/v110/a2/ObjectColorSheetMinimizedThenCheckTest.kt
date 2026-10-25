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
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.SheetHost
import com.brushwork.paint.ui.common.SheetPill
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
 * v1.10 (R2, R7, J21; design 4.4 A2 step 1): a color picked in the Object colors sheet shows on the
 * object at once; a swipe down on the sheet minimizes it to its pill and keeps the preview (no
 * Cancel, no OK); the object's session then ends with ✓: the sheet goes (its caller drops it, as
 * the pill's COLOR cell does when the tool's editor goes) without a call of its own, and the
 * previewed color stays inside the session's one ✓ step.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.minimizedchecksandbox"])
class ObjectColorSheetMinimizedThenCheckTest {

    @Test
    fun swipeMinimizesKeepsThePreviewAndCheckKeepsItInOneStep() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        val blue = 0xFF2266AA.toInt()
        val editor = A2FakeColorEditor(fill = blue, stroke = 0xFF000000.toInt())
        var sheetOpen by mutableStateOf(true)
        activity.setContent {
            BrushworkTheme {
                val h = rememberSheetHostState()
                CompositionLocalProvider(LocalSheetHost provides h) {
                    Box(Modifier.fillMaxSize()) {
                        SheetHost(h, bottomInset = 0.dp)
                        SheetPill(h, Modifier.align(Alignment.BottomCenter))
                    }
                    // As the pill's COLOR cell: shown while the tool has an editor (an open object).
                    if (sheetOpen && editor.sessionOpen) ObjectColorsSheet(editor, onClose = { sheetOpen = false })
                }
            }
        }
        SmokeUi.settle()
        assertEquals(listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())

        // A typed color is a change: it goes to the object live (the Fill tab is selected first).
        RobolectricUi.textFields().first().type("FF0000")
        SmokeUi.settle()
        assertEquals("live preview", 0xFFFF0000.toInt(), editor.fill)
        assertEquals(listOf("begin", "preview FILL FFFF0000"), editor.calls)

        // A swipe down on the title minimizes the sheet: the preview stays, nothing else is called.
        val title = RobolectricUi.byText(ObjectColorLabels110.CELL)
        val x = title.bounds.center.x
        val y = title.bounds.center.y
        RobolectricUi.drag(title.window, x to y, x to y + 60f, x to y + 160f, x to y + 260f)
        SmokeUi.settle()
        assertEquals("minimized", listOf(ObjectColorLabels110.CELL), SmokeUi.pillTitles())
        assertTrue("minimized: no panel", SmokeUi.sheetTitles().isEmpty())
        assertEquals("the preview is kept", 0xFFFF0000.toInt(), editor.fill)
        assertEquals(listOf("begin", "preview FILL FFFF0000"), editor.calls)

        // The session's ✓: the sheet and its pill go; the color stays, in the one ✓ step.
        editor.check()
        SmokeUi.settle()
        assertTrue("closed: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
        assertEquals("kept after ✓", 0xFFFF0000.toInt(), editor.fill)
        assertEquals("one step", 1, editor.steps)
        assertEquals("no Cancel and no OK of the sheet", listOf("begin", "preview FILL FFFF0000"), editor.calls)
    }
}
