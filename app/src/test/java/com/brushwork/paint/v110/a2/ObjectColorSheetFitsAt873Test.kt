package com.brushwork.paint.v110.a2

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.10 (R2, design 4.4 A2 step 1): the Object colors sheet on an 873 dp tall phone, hosted as in
 * the editor (half the screen), at font scale 1.0 and 2.0: its one row of tabs and paint-mode
 * glyphs shows, and the wheel's bottom (the top of the opacity row under it) and OK are inside the
 * sheet as it opens, without scrolling (`extraAbove` keeps the wheel small enough).
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.fitssandbox"])
class ObjectColorSheetFitsAt873Test {

    private fun byTag(tag: String): RobolectricUi.Element? =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }

    @Test
    fun wheelAndOkFitWithoutScrollingAtBothFontScales() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        val editor = A2FakeColorEditor(fill = 0xFF2266AA.toInt(), stroke = 0xFF000000.toInt(), mode = PaintMode.BOTH)
        var fontScale by mutableFloatStateOf(1f)
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
                        ObjectColorsSheet(editor, onClose = {})
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            fontScale = scale
            SmokeUi.settle()
            val where = "font scale $scale"
            assertEquals(where, listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
            val panel = requireNotNull(SmokeUi.sheetPanel()) { "$where: no sheet" }.bounds
            // The row: both tabs and the three paint-mode glyphs.
            for (t in listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE)) {
                assertNotNull("$where: tab $t", byTag("${V110Tags.COLORS_SHEET_TAB}.${t.name}"))
            }
            for (m in PaintMode.entries) assertNotNull("$where: mode $m", byTag("${V110Tags.COLORS_SHEET_MODE}.${m.name}"))
            val tabs = requireNotNull(byTag("${V110Tags.COLORS_SHEET_TAB}.FILL")).bounds
            val ok = RobolectricUi.byText("OK").bounds
            val opacity = requireNotNull(SmokeUi.find("Opacity", exact = true)) { "$where: no opacity row" }.bounds
            assertTrue("$where: OK $ok inside the sheet $panel", ok.top >= panel.top && ok.bottom <= panel.bottom)
            assertTrue("$where: the row $tabs is above the wheel (opacity $opacity)", tabs.bottom <= opacity.top)
            // The wheel ends above the opacity row: its top inside the sheet means the whole wheel shows.
            assertTrue("$where: the wheel's bottom (opacity row top ${opacity.top}) inside the sheet $panel", opacity.top <= panel.bottom)
            assertTrue("$where: the sheet starts at its top (no scroll): row $tabs, sheet $panel", tabs.top >= panel.top)
        }
        assertEquals("opening only begins", listOf("begin"), editor.calls)
    }
}
