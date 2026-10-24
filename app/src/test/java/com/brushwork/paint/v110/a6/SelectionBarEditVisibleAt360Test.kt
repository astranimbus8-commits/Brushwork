package com.brushwork.paint.v110.a6

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.editor.SelectionActionBar
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 A6 (R10, finding 27): while the selection bar offers Edit it is the bar's first item,
 * before Copy, wholly inside the bar at scroll 0 on a 360 dp and a 392 dp phone; a tap opens the
 * text. "Behind" (R4, while the screen passes it) comes directly after "Array", and the pair
 * before Save. (Behind sits after More, past the bar's edge at scroll 0: see the A6 lead request
 * on the design's "Edit and Behind" wording.)
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.v110.a6.seleditsandbox"])
class SelectionBarEditVisibleAt360Test {

    @Test
    fun editIsTheFirstItemAndOnScreenAtScrollZero() {
        try {
            Qa19Ui.uiTest { h ->
                for (width in listOf(360, 392)) {
                    if (width == 392) RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
                    h.section("$width dp") { bar(h, width) }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
        }
    }

    private fun bar(h: Qa19Harness, width: Int) {
        val kit = ObjectTestKit(400, 300)
        try {
            val c = kit.controller()
            val item = TextItem("Title", spec = TextSpec(sizePx = 40f), cx = 200f, cy = 150f)
            val layer = c.addLayerWithContent("Text", "Add text", textData = TextCodec.encodeAll(listOf(item))) { cv ->
                TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
            }!!
            c.setSelection(kit.rectSelection(120, 100, 280, 200), recordUndo = false)
            val behind = mutableStateOf(true)
            h.show {
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    SelectionActionBar(
                        controller = c,
                        onMore = {},
                        onHide = null,
                        modifier = Modifier.testTag(ChromeTags.SELECTION_BAR),
                        onArrayBehind = if (behind.value) ({}) else null,
                    )
                }
            }
            val bar = rect(tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("no selection bar"))
            val editNode = tagged(V110Tags.SEL_EDIT) ?: throw AssertionError("no Edit at $width dp")
            val edit = rect(editNode)
            assertTrue("Edit wholly inside the bar at scroll 0 at $width dp: $edit in $bar", edit.left >= bar.left - 0.5f && edit.right <= bar.right + 0.5f)
            val copy = rect(clickable("Copy selection")!!)
            assertTrue("Edit before Copy: $edit, $copy", edit.right <= copy.left + 1f)
            val firsts = RobolectricUi.elements().filter { it.node.config.getOrNull(SemanticsActions.OnClick) != null }.map { rect(it.node).left }
            assertEquals("the first item", firsts.min(), edit.left, 1f)

            // The Array pair, then Save.
            val array = rect(clickable(ArrayLabels.FROM_SELECTION)!!)
            val behindAt = rect(tagged(V110Tags.SEL_ARRAY_BEHIND) ?: throw AssertionError("no Behind"))
            val save = rect(clickable(SavedSelectionLabels.SAVE)!!)
            assertEquals("Behind directly after Array", array.right, behindAt.left, 1f)
            assertTrue("the pair before Save", behindAt.right <= save.left + 1f)
            behind.value = false
            Qa19Ui.settle()
            assertNull("no Behind without its callback", tagged(V110Tags.SEL_ARRAY_BEHIND))
            assertEquals("Save after Array then", rect(clickable(ArrayLabels.FROM_SELECTION)!!).right, rect(clickable(SavedSelectionLabels.SAVE)!!).left, 1f)

            // A tap opens the text in the Text tool, its editor open.
            val tap = editNode.config[SemanticsActions.OnClick].action ?: throw AssertionError("Edit has no click")
            tap()
            Qa19Ui.settle()
            assertEquals(ToolId.TEXT, c.activeToolId)
            val tool = c.tools.getValue(ToolId.TEXT) as TextTool
            assertTrue("the text is open in its editor", tool.editingLayer === layer && tool.editorOpen)
        } finally {
            kit.close()
        }
    }

    /** Unclipped bounds (window px) of [n]: a node scrolled past the bar's edge keeps its full size. */
    private fun rect(n: SemanticsNode): Rect = Rect(n.positionInWindow, n.size.toSize())

    /** The placed node tagged [tag], or null. */
    private fun tagged(tag: String): SemanticsNode? = RobolectricUi.elements()
        .lastOrNull { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }?.node

    /** The clickable whose click label is [clickLabel], or null. */
    private fun clickable(clickLabel: String): SemanticsNode? = RobolectricUi.elements()
        .lastOrNull { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == clickLabel }?.node
}
