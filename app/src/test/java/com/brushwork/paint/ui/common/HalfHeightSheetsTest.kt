package com.brushwork.paint.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.assist.GridPanel
import com.brushwork.paint.ui.assist.RulerPanel
import com.brushwork.paint.ui.assist.StabilizerPanel
import com.brushwork.paint.ui.brush.BrushPanel
import com.brushwork.paint.ui.canvas.CanvasAdjustDialog
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.color.ColorPickerPanel
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.filters.FilterBrowser
import com.brushwork.paint.ui.placement.FrameSettingsSheet
import com.brushwork.paint.ui.placement.TextEditorDialog
import com.brushwork.paint.ui.placement.TextNumbersSheet
import com.brushwork.paint.ui.placement.TransformNumbersSheet
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Every sheet of the canvas / brush / color / assist / filter / placement modules on a
 * 360 x 760dp phone: it opens without crashing, takes at most half of the screen, and its title
 * and primary controls (Apply buttons, the color wheel, OK) are on screen without scrolling.
 *
 * One test for all sheets: Compose's frame clock (sheet animations) only serves the first test
 * of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.halfsheetsandbox"])
class HalfHeightSheetsTest {

    private val failures = mutableListOf<Throwable>()

    private fun check(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            System.err.println("=== SHEET FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        }
    }

    /** Top edge and bottom edge (window px, unclipped) of a node. */
    private fun SemanticsNode.top() = positionInWindow.y
    private fun SemanticsNode.bottom() = positionInWindow.y + size.height

    /** The sheet window: the newest window showing [title]. */
    private fun sheetWith(title: String): RobolectricUi.Element =
        SmokeUi.find(title, exact = true) ?: throw AssertionError("no sheet titled \"$title\"; shown: ${SmokeUi.shown().take(80)}")

    /**
     * [title] is on screen, the sheet (from its top to the window bottom) is at most half of
     * the screen high, and every one of [visible] is fully on screen without scrolling.
     */
    private fun assertHalfHeight(activity: ComponentActivity, title: String, vararg visible: String) {
        val t = sheetWith(title)
        val window = t.window
        val h = window.height.toFloat()
        val density = activity.resources.displayMetrics.density
        val half = activity.resources.configuration.screenHeightDp * density / 2f
        // The sheet surface is the tallest node of the window that contains the title row.
        val sheet = RobolectricUi.elements()
            .filter { it.window === window && it.node.config.getOrNull(SemanticsProperties.PaneTitle) != null }
            .maxByOrNull { it.node.size.height }
        val sheetTop = sheet?.node?.top() ?: (t.node.top() - 16f * density - (48f * density - t.node.size.height) / 2f)
        assertTrue("\"$title\" is on screen (${t.node.top()}..${t.node.bottom()} of $h)", t.node.top() >= 0f && t.node.bottom() <= h + 0.5f)
        val sheetHeight = h - sheetTop
        assertTrue("\"$title\" sheet is ${sheetHeight / density}dp high, more than half of ${half * 2f / density}dp", sheetHeight <= half + 2f * density)
        for (label in visible) {
            val e = RobolectricUi.elements().lastOrNull { e ->
                e.window === window && (
                    e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                        e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
                    )
            } ?: throw AssertionError("\"$label\" missing in \"$title\"; shown: ${SmokeUi.shown().take(80)}")
            if (!(e.node.top() >= sheetTop - 0.5f && e.node.bottom() <= h + 0.5f)) {
                System.err.println("  focused: " + RobolectricUi.elements().filter { it.window === window && it.focused }.map { "${it.node.id} ${it.node.config}" })
                RobolectricUi.elements().filter { it.window === window }.take(40).forEach { n ->
                    val scroll = n.node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    System.err.println("  node ${n.node.id} ${n.node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }} ${n.node.config.getOrNull(SemanticsProperties.ContentDescription)} pos=${n.node.positionInWindow} size=${n.node.size} bounds=${n.bounds} scroll=${scroll?.let { "${it.value()}/${it.maxValue()}" }} pane=${n.node.config.getOrNull(SemanticsProperties.PaneTitle)}")
                }
            }
            assertTrue(
                "\"$label\" in \"$title\" must be on screen without scrolling: ${e.node.top()}..${e.node.bottom()} (sheet from $sheetTop, window $h)",
                e.node.top() >= sheetTop - 0.5f && e.node.bottom() <= h + 0.5f,
            )
        }
    }

    @Test
    fun everySheetFitsInHalfTheScreen() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(1200, 900, layers = 2, whiteBottom = true))
        // Something on the active layer for the transform tool to pick up.
        c.editWholeLayer(c.doc.activeLayer, "Seed") { b ->
            android.graphics.Canvas(b).drawRect(300f, 200f, 700f, 600f, android.graphics.Paint().apply { color = 0xFF2266CC.toInt() })
        }
        var which by mutableStateOf<String?>(null)
        val close = { which = null }
        activity.setContent {
            BrushworkTheme {
                when (which) {
                    "brush" -> BrushPanel(c, close)
                    "color" -> ColorPickerPanel(c, close)
                    "dialog" -> ColorPickerDialog(0xFF3366CC.toInt(), onPick = {}, onDismiss = close)
                    "dialogAlpha" -> ColorPickerDialog(0x803366CC.toInt(), onPick = {}, onDismiss = close, title = "Outline color", showAlpha = true)
                    "canvas" -> CanvasAdjustDialog(c, close)
                    "ruler" -> RulerPanel(c, close)
                    "grid" -> GridPanel(c, close)
                    "stabilizer" -> StabilizerPanel(c, close)
                    "filters" -> FilterBrowser(c, close)
                    "transform" -> TransformNumbersSheet(c.tools.getValue(ToolId.TRANSFORM) as TransformTool)
                    "textNumbers" -> TextNumbersSheet(c.tools.getValue(ToolId.TEXT) as TextTool)
                    "textEditor" -> TextEditorDialog(c.tools.getValue(ToolId.TEXT) as TextTool)
                    "frame" -> {
                        val tool = c.tools.getValue(ToolId.FRAME_DIVIDER) as FrameDividerTool
                        FrameSettingsSheet(tool, tool.status())
                    }
                }
            }
        }
        SmokeUi.settle()

        fun open(key: String) {
            which = key
            SmokeUi.settle(20, 50)
            SmokeUi.assertWindowsLaidOut(2)
        }
        fun shut(name: String) {
            which = null
            SmokeUi.settle(20, 50)
            assertEquals("$name closed", 1, SmokeUi.windows().size)
        }

        check("brush panel") { open("brush"); assertHalfHeight(activity, "Brush", "Size", "Opacity (stroke)"); shut("brush") }
        check("color panel") { open("color"); assertHalfHeight(activity, "Color", "Color wheel", "Hex"); shut("color") }
        check("color dialog") { open("dialog"); assertHalfHeight(activity, "Pick a color", "OK", "Cancel", "Color wheel"); shut("dialog") }
        check("color dialog with opacity") {
            open("dialogAlpha")
            assertHalfHeight(activity, "Outline color", "OK", "Color wheel", "Opacity")
            shut("dialogAlpha")
        }
        check("canvas dialog") {
            open("canvas")
            assertHalfHeight(activity, "Canvas", "Resize image", "Width")
            val tabs = listOf(
                "Canvas size" to "Change canvas size",
                "Trim & crop" to "Trim",
                "Rotate & flip" to "Rotate 90° clockwise",
                "Resolution" to "Set ${com.brushwork.paint.ui.canvas.formatDpi(c.doc.dpi.toDouble())} dpi",
                "Color mode" to "Already ${c.doc.colorMode.label}",
            )
            for ((tab, action) in tabs) {
                SmokeUi.clickTab(tab)
                SmokeUi.settle(10, 50)
                assertHalfHeight(activity, "Canvas", action)
            }
            shut("canvas")
        }
        check("ruler") { open("ruler"); assertHalfHeight(activity, "Ruler", "Use ruler"); shut("ruler") }
        check("grid") {
            c.updateGrid(c.grid.copy(enabled = true))
            open("grid")
            assertHalfHeight(activity, "Grid", "Show grid")
            shut("grid")
        }
        check("stabilizer") { open("stabilizer"); assertHalfHeight(activity, "Stabilizer", "Off"); shut("stabilizer") }
        check("filter browser") { open("filters"); assertHalfHeight(activity, "Filters", "Search filters"); shut("filters") }
        check("transform numbers") {
            c.selectTool(ToolId.TRANSFORM)
            Smoke.pump(100)
            open("transform")
            assertHalfHeight(activity, "Numbers", "X", "Y")
            shut("transform")
            (c.tools.getValue(ToolId.TRANSFORM) as TransformTool).commit()
        }
        check("text editor and numbers") {
            c.selectTool(ToolId.TEXT)
            val text = c.tools.getValue(ToolId.TEXT) as TextTool
            text.startTextAt(300f, 300f)
            text.setText("Hello")
            open("textEditor")
            assertHalfHeight(activity, "Add text", "OK", "Cancel", "Text")
            text.confirmEditor()
            shut("textEditor")
            open("textNumbers")
            assertHalfHeight(activity, "Position & size", "Center X")
            shut("textNumbers")
            text.discard()
        }
        check("frame settings") {
            c.selectTool(ToolId.FRAME_DIVIDER)
            open("frame")
            assertHalfHeight(activity, "Frame layer", "Border width")
            shut("frame")
        }
        Smoke.assertQuiet(c, "sheets")

        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }
}
