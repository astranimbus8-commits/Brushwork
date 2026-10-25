package com.brushwork.paint.v110.a2

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.SheetHost
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.rememberSheetHostState
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.vector.ObjectColorsSheet
import com.brushwork.paint.ui.vector.PaintModeTags
import com.brushwork.paint.ui.vector.ShapeStripItems
import com.brushwork.paint.ui.vector.ShapeToolOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.10 (R3 (e); design 4.4 A2 step 2): no paint-mode selector shows a word. On the editor at the
 * user's phone width the Shape tool's strip, its style menu and its settings sheet, and the Curve
 * tool's strip, its Stroke kind menu and its settings sheet; then the `ShapeToolOptions` strip
 * (still rendered by tests) with its menu, and the Object colors sheet: none shows "Fill",
 * "Stroke", "Both", "Stroke only", "Fill only" or "Stroke and fill" as the visible text of a
 * control that acts on a tap. Each place shows the icon segments instead (found by their tags),
 * and a tap on one sets the mode.
 *
 * (The pill's COLOR cell is a stub until step 6; it renders nothing yet.)
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.r3textsandbox"])
class R3NoTextModeSelectorsTest {

    private val modeTexts = setOf("Fill", "Stroke", "Both", CurveLabels17.STROKE_ONLY, CurveLabels17.FILL_ONLY, CurveLabels17.BOTH)

    /** Whether [n] acts on a tap or reads as a choice. */
    private fun acts(n: SemanticsNode): Boolean =
        n.config.contains(SemanticsActions.OnClick) || n.config.contains(SemanticsProperties.Selected) || n.config.contains(SemanticsProperties.ToggleableState)

    /**
     * The mode texts on screen (windows shown since the baseline) that a control shows: the text's
     * node, or the nearest node that merges it into one control (a chip, a menu item), acts.
     */
    private fun selectorTexts(): List<String> = RobolectricUi.elements()
        .filter { RobolectricUi.windowRoots().indexOf(it.window) >= SmokeUi.baseline && it.node.layoutInfo.isPlaced }
        .flatMap { e ->
            val texts = e.node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty().filter { it in modeTexts }
            if (texts.isEmpty()) return@flatMap emptyList()
            var n: SemanticsNode? = e.node
            while (n != null && !n.config.isMergingSemanticsOfDescendants && !acts(n)) n = n.parent
            if (n != null && acts(n)) texts else emptyList()
        }

    private fun assertNoWords(where: String) {
        val found = selectorTexts()
        assertTrue("$where shows paint modes as words: $found", found.isEmpty())
    }

    /** Invokes the click of the (last) placed node tagged [tag]. */
    private fun clickTag(tag: String) {
        val n = StripTestKit.tagged(tag) ?: throw AssertionError("no $tag; shown: ${SmokeUi.shown().take(60)}")
        requireNotNull(n.config.getOrNull(SemanticsActions.OnClick)?.action) { "$tag is not clickable" }.invoke()
        SmokeUi.settle()
    }

    private fun assertSegments(prefix: String, where: String) {
        for (m in PaintMode.entries) assertNotNull("$where: the ${m.name} segment", StripTestKit.tagged("$prefix.${m.name}"))
    }

    @Test
    fun noPaintModeSelectorShowsAWord() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        val s = h.editor()

        // The Shape tool: its strip, the style menu, the settings sheet.
        QaCurves.tool(s, ToolId.SHAPE.label)
        val shape = s.c.currentTool as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE) }
        SmokeUi.settle()
        assertNoWords("the Shape strip")
        SmokeUi.click(ShapeStripItems.STYLE_LABEL, exact = true)
        assertSegments(PaintModeTags.SHAPE_STRIP, "the Shape style menu")
        assertNoWords("the Shape style menu")
        clickTag("${PaintModeTags.SHAPE_STRIP}.${PaintMode.FILL.name}")
        assertEquals(ShapeStyle.FILL, shape.settings.style)
        assertNull("a pick closes the menu", StripTestKit.tagged("${PaintModeTags.SHAPE_STRIP}.${PaintMode.FILL.name}"))
        SmokeUi.click("Shape settings", exact = true)
        assertSegments(PaintModeTags.SHAPE, "the Shape settings sheet")
        assertNoWords("the Shape settings sheet")
        clickTag("${PaintModeTags.SHAPE}.${PaintMode.BOTH.name}")
        assertEquals(ShapeStyle.STROKE_FILL, shape.settings.style)
        s.activity.onBackPressedDispatcher.onBackPressed()
        SmokeUi.settle()
        assertNull("the sheet closed", StripTestKit.tagged("${PaintModeTags.SHAPE}.${PaintMode.BOTH.name}"))

        // The Curve tool: its strip, the Stroke kind menu, the settings sheet.
        QaCurves.tool(s, ToolId.CURVE.label)
        val curve = s.c.currentTool as CurveTool
        assertNoWords("the Curve strip")
        SmokeUi.click(CurveLabels17.STROKE_KIND, exact = true)
        assertSegments(PaintModeTags.CURVE_STRIP, "the Stroke kind menu")
        assertNoWords("the Stroke kind menu")
        clickTag("${PaintModeTags.CURVE_STRIP}.${PaintMode.FILL.name}")
        assertEquals(CurvePaint.FILL, curve.paintMode)
        assertNull("a pick closes the menu", StripTestKit.tagged("${PaintModeTags.CURVE_STRIP}.${PaintMode.FILL.name}"))
        SmokeUi.click("Curve settings", exact = true)
        assertSegments(PaintModeTags.CURVE, "the Curve settings sheet")
        assertNoWords("the Curve settings sheet")
        clickTag("${PaintModeTags.CURVE}.${PaintMode.BOTH.name}")
        assertEquals(CurvePaint.BOTH, curve.paintMode)
        s.activity.onBackPressedDispatcher.onBackPressed()
        SmokeUi.settle()

        // ShapeToolOptions (the strip function tests still render) and the Object colors sheet.
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 1))
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE) }
        val editor = A2FakeColorEditor(fill = 0xFF2266AA.toInt(), stroke = 0xFF000000.toInt())
        activity.setContent {
            BrushworkTheme {
                val host = rememberSheetHostState()
                CompositionLocalProvider(LocalSheetHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxWidth()) { FlowRow(Modifier.fillMaxWidth()) { ShapeToolOptions(tool) } }
                        SheetHost(host, bottomInset = 0.dp)
                    }
                    ObjectColorsSheet(editor, onClose = {})
                }
            }
        }
        SmokeUi.settle()
        for (m in PaintMode.entries) assertNotNull("the Object colors sheet: ${m.name}", StripTestKit.tagged("${V110Tags.COLORS_SHEET_MODE}.${m.name}"))
        assertNoWords("ShapeToolOptions and the Object colors sheet")
        SmokeUi.click(ShapeStripItems.STYLE_LABEL, exact = true)
        assertSegments(PaintModeTags.SHAPE_STRIP, "ShapeToolOptions' style menu")
        assertNoWords("ShapeToolOptions' style menu")
        clickTag("${PaintModeTags.SHAPE_STRIP}.${PaintMode.BOTH.name}")
        assertEquals(ShapeStyle.STROKE_FILL, tool.settings.style)
        dog.interrupt()
    }
}
