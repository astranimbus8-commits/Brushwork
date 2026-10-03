package com.brushwork.paint.qa16

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.editor.ToolMenuEntry
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Every tool-menu cell's label shows whole (§3.7.6: an 11 sp label of up to 2 lines under the
 * 28 dp glyph; ibisPaint never cuts a tool's name): no line ends in an ellipsis and nothing is
 * clipped. "Frame divider" used to read "Frame divid…" — its two lines didn't fit the cell's
 * 52 dp, so Compose ellipsized the first.
 */
internal object ToolMenuLabels {
    val labels: List<String> = ToolMenu.entries.map { e ->
        when (e) {
            is ToolMenuEntry.Tool -> e.id.label
            ToolMenuEntry.Filters -> "Filters"
            ToolMenuEntry.Canvas -> "Canvas"
            ToolMenuEntry.Settings -> "Settings"
        }
    }

    fun check() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("tool menu labels") {
            val s = h.editor(Smoke.document(300, 430, layers = 2, whiteBottom = true))
            click("Tools (current: Brush)")
            val menu = s.tagged(ChromeTags.TOOL_MENU) ?: throw AssertionError("no tool menu")
            val texts = s.placed().filter { e ->
                val t = e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                // Where it is laid out (cells below the menu's fold are clipped to nothing until scrolled to).
                val x = e.node.positionInWindow.x / s.density - s.dp(s.root).left
                t != null && t in labels && x >= menu.left - 1 && x <= menu.right + 1
            }
            val bad = mutableListOf<String>()
            for (label in labels) {
                val e = texts.firstOrNull { it.node.config[SemanticsProperties.Text].joinToString("") { t -> t.text } == label }
                if (e == null) { bad += "$label: not in the menu"; continue }
                val results = mutableListOf<TextLayoutResult>()
                e.node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                val r = results.firstOrNull() ?: run { bad += "$label: no layout"; null } ?: continue
                val ellipsized = (0 until r.lineCount).any { r.isLineEllipsized(it) }
                val c = r.layoutInput.constraints
                val wide = (0 until r.lineCount).any { r.getLineRight(it) - r.getLineLeft(it) > c.maxWidth + 0.5f }
                val tall = r.multiParagraph.height > c.maxHeight + 0.5f
                val cut = ellipsized || r.multiParagraph.didExceedMaxLines || wide || tall
                val shown = (0 until r.lineCount).joinToString(" / ") { i -> label.substring(r.getLineStart(i), r.getLineEnd(i, visibleEnd = true)) }
                if (cut || r.lineCount > 2) {
                    bad += "$label: lines=${r.lineCount} ellipsized=$ellipsized wide=$wide tall=$tall shows \"$shown\" in ${r.size} c=$c ph=${r.multiParagraph.height} exceed=${r.multiParagraph.didExceedMaxLines}"
                }
            }
            assertEquals("every cell, once", labels.size, labels.toSet().size)
            assertTrue("tool-menu labels cut at ${s.widthDp} dp:\n" + bad.joinToString("\n"), bad.isEmpty())
        }
        dog.interrupt()
        h.finish()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.toolmenulabelssandbox"])
class ToolMenuLabelsFitTest {
    @Test
    fun everyLabelShowsWhole() = ToolMenuLabels.check()
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.toolmenulabelsnarrowsandbox"])
class ToolMenuLabelsFitNarrowTest {
    @Test
    fun everyLabelShowsWhole() = ToolMenuLabels.check()
}
