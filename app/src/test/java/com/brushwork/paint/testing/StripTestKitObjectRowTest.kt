package com.brushwork.paint.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 integration: the More-row helpers reach the OPTIONS STRIP's row, never the vector object
 * bar's. The bar's toggle and More row reuse the strip's tags ([V18Tags.STRIP_TOGGLE],
 * [V18Tags.STRIP_MORE_ROW]), so a helper that took "the last toggle" opened the bar's row whenever
 * vector objects were selected on Lasso, Select shape, Magic wand or Object select.
 *
 * With objects selected on a vector layer and Lasso, then Object select, on the user's phone (392 dp):
 * - [StripTestKit.openMoreRow] and [SmokeUi.openMoreRow] open the strip's More row (4 dp under the
 *   strip's panel), not the bar's; [StripTestKit.moreRowShown] and [StripTestKit.inConvertedStrip]
 *   count the strip's row only; [StripTestKit.reach]'s and [SmokeUi.find]'s fallbacks open only the
 *   strip's row; [StripTestKit.closeMoreRow] leaves the bar's open row alone;
 * - [StripTestKit.openObjectRow] opens the bar's row (under the bar, with Bring forward);
 * - one row shows at a time (area H's rule): each opener closes the other's row, and there is never
 *   more than one More row in the tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.testing.objectrowsandbox"])
class StripTestKitObjectRowTest {

    @Test
    fun theStripHelpersOpenTheStripsMoreRowAndOpenObjectRowTheObjectBars() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        for (tool in listOf(ToolId.LASSO, ToolId.OBJECT_SELECT)) h.section(tool.label) { bothRows(h, tool) }
        dog.interrupt()
        h.finish()
    }

    private fun description(n: SemanticsNode?): String? = n?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun state(n: SemanticsNode?): String? = n?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** Every placed node tagged [V18Tags.STRIP_MORE_ROW] (the strip's and the bar's share the tag). */
    private fun moreRows(): List<SemanticsNode> = RobolectricUi.elements().map { it.node }.filter { n ->
        n.layoutInfo.isPlaced && n.config.getOrNull(SemanticsProperties.TestTag) == V18Tags.STRIP_MORE_ROW
    }

    /** The clickable labelled [label] (its own description or click label, or a child's description). */
    private fun clickable(label: String): SemanticsNode? = RobolectricUi.elements().map { it.node }.lastOrNull { n ->
        n.layoutInfo.isPlaced && n.config.getOrNull(SemanticsActions.OnClick) != null &&
            (n.config.getOrNull(SemanticsActions.OnClick)?.label == label || description(n) == label)
    }

    private fun assertOneRow(what: String, strip: Boolean) {
        val rows = moreRows()
        assertEquals("$what: one More row at a time: ${rows.map { it.boundsInWindow }}", 1, rows.size)
        assertEquals("$what: the strip's row", strip, StripTestKit.isStripMoreRow(rows[0]))
        assertEquals("$what: the object bar's row", !strip, StripTestKit.isObjectMoreRow(rows[0]))
        assertEquals("$what: moreRowShown", strip, StripTestKit.moreRowShown())
        assertEquals("$what: objectRowShown", !strip, StripTestKit.objectRowShown())
        assertEquals("$what: the strip's toggle", if (strip) StripLabels18.EXPANDED else StripLabels18.COLLAPSED, state(StripTestKit.toggle()))
        assertEquals("$what: the bar's toggle", if (strip) StripLabels18.COLLAPSED else StripLabels18.EXPANDED, state(StripTestKit.objectToggle()))
    }

    private fun assertNoRow(what: String) {
        assertTrue("$what: no More row: ${moreRows().map { it.boundsInWindow }}", moreRows().isEmpty())
        assertFalse(StripTestKit.moreRowShown())
        assertFalse(StripTestKit.objectRowShown())
        assertEquals("$what: the strip's toggle", StripLabels18.COLLAPSED, state(StripTestKit.toggle()))
        assertEquals("$what: the bar's toggle", StripLabels18.COLLAPSED, state(StripTestKit.objectToggle()))
    }

    private fun bothRows(h: ChromeHarness, tool: ToolId) {
        val kit = ObjectTestKit(400, 300)
        val doc = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 1
        }
        try {
            val s = h.editor(doc) { c ->
                c.vectors.addObjects(c.doc.layers[1], listOf(kit.box(40f, 40f, 120f, 110f), kit.stroke(60f, 200f, 200f, 230f)), "Add")
            }
            val c = s.c
            // The tool first (a tool change closes every More row), then the objects selected.
            c.selectTool(tool)
            SmokeUi.settle()
            assertEquals(tool, c.activeToolId)
            c.vectors.setSelection(c.doc.layers[1], setOf(1L))
            Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
            SmokeUi.settle()
            assertTrue("${tool.label} on a vector layer", c.isVectorMode)
            assertEquals(setOf(1L), c.vectors.selectedIds)

            // Two toggles: the strip's at the end of its row 1, the bar's at the end of the bar.
            val stripToggle = StripTestKit.toggle()
            assertNotNull("${tool.label}'s strip has a More toggle at 392 dp on a vector layer", stripToggle)
            assertEquals(StripLabels18.MORE_TOGGLE, description(stripToggle))
            assertEquals(StripLabels18.MORE_OBJECT, description(StripTestKit.objectToggle()))
            assertNoRow("before")
            assertFalse("nothing to close", StripTestKit.closeMoreRow())
            assertFalse("nothing to close", StripTestKit.closeObjectRow())

            // openMoreRow: the strip's row, 4 dp under the strip's panel.
            assertTrue("openMoreRow clicks the strip's toggle", StripTestKit.openMoreRow())
            assertOneRow("openMoreRow", strip = true)
            assertFalse("already open", StripTestKit.openMoreRow())
            assertFalse("already open (SmokeUi)", SmokeUi.openMoreRow())
            val panel = s.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("no strip panel")
            val more = s.dp(StripTestKit.stripMoreRow()!!.boundsInWindow)
            assertEquals("the strip's row hangs 4 dp under its panel", panel.bottom + StripLayout.GAP_DP, more.top, 1f)
            val inMore = RobolectricUi.elements().map { it.node }.filter { n ->
                n.layoutInfo.isPlaced && n.config.getOrNull(SemanticsActions.OnClick) != null && StripTestKit.under(n, V18Tags.STRIP_MORE_ROW)
            }
            assertTrue("the strip's More row has controls", inMore.isNotEmpty())
            assertTrue("inConvertedStrip counts the strip's More row", inMore.all { StripTestKit.inConvertedStrip(it) && !StripTestKit.inObjectRow(it) })
            assertNull("the bar's More row is closed", clickable("Bring forward"))

            // openObjectRow: the bar's row, under the bar; the strip's row closes (one at a time).
            assertTrue("openObjectRow clicks the bar's toggle", StripTestKit.openObjectRow())
            assertOneRow("openObjectRow", strip = false)
            assertFalse("already open", StripTestKit.openObjectRow())
            // The strip's closer leaves the bar's row alone (the strip's row is the closed one).
            assertFalse("closeMoreRow: the strip's row is closed", StripTestKit.closeMoreRow())
            assertOneRow("closeMoreRow with the bar's row open", strip = false)
            val forward = clickable("Bring forward") ?: throw AssertionError("no Bring forward on the bar's More row")
            assertTrue("Bring forward sits on the bar's More row", StripTestKit.inObjectRow(forward))
            assertFalse("…which is not the strip's", StripTestKit.inConvertedStrip(forward))
            val bar = s.dp(StripTestKit.objectToggle()!!.boundsInWindow)
            assertTrue("the bar's row hangs under the bar", s.dp(StripTestKit.objectMoreRow()!!.boundsInWindow).top >= bar.bottom - 0.5f)

            // openMoreRow again: the strip's row opens and the bar's closes.
            assertTrue(StripTestKit.openMoreRow())
            assertOneRow("openMoreRow after openObjectRow", strip = true)
            assertFalse("closeObjectRow: the bar's row is closed", StripTestKit.closeObjectRow())
            assertTrue(StripTestKit.closeMoreRow())
            assertNoRow("closeMoreRow")

            // StripTestKit.reach's fallback opens the strip's row only.
            assertNull("reach never opens the bar's row", StripTestKit.reach { clickable("Bring forward") })
            assertOneRow("StripTestKit.reach's fallback", strip = true)
            assertTrue(StripTestKit.closeMoreRow())
            assertNoRow("closeMoreRow after reach")

            // SmokeUi: its opener and find's fallback reach the strip's row only.
            assertTrue("SmokeUi.openMoreRow clicks the strip's toggle", SmokeUi.openMoreRow())
            assertOneRow("SmokeUi.openMoreRow", strip = true)
            assertTrue(StripTestKit.closeMoreRow())
            assertNull("find never opens the bar's row", SmokeUi.find("Bring forward", exact = true))
            assertOneRow("SmokeUi.find's fallback", strip = true)
            assertTrue(StripTestKit.closeMoreRow())

            // closeObjectRow closes the bar's row.
            assertTrue(StripTestKit.openObjectRow())
            assertNotNull("find reaches the open bar's row", SmokeUi.find("Bring forward", exact = true))
            assertOneRow("find with the bar's row open", strip = false)
            assertTrue(StripTestKit.closeObjectRow())
            assertNoRow("closeObjectRow")
            Smoke.assertQuiet(c, "${tool.label}: the strip's and the bar's More rows")
        } finally {
            kit.close()
        }
    }
}
