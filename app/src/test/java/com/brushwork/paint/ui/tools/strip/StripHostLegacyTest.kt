package com.brushwork.paint.ui.tools.strip

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * v1.8 (F3, design §4.6): a legacy spec draws as v1.7 did. The "VECTOR" chip (described "Vector
 * mode is on") first while in vector mode, then the tool's composable, in one row that scrolls
 * sideways; no row-1 tag, no toggle, no More row, no More-row height; the spec's overlays are
 * composed beside it. Then the same through the editor's panel, with the Brush's strip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.hostlegacysandbox"])
class StripHostLegacyTest {

    private fun described(d: String): SemanticsNode? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true
    }?.node

    private fun texts(t: String): List<SemanticsNode> = RobolectricUi.elements().filter { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == t } == true
    }.map { it.node }

    private fun noV18Nodes(where: String) {
        for (tag in listOf(V18Tags.STRIP_ROW1, V18Tags.STRIP_TOGGLE, V18Tags.STRIP_MORE_ROW)) {
            assertNull("$where: no $tag", StripTestKit.tagged(tag))
        }
    }

    @Test
    fun legacySpecsDrawAsV17() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = ctl.get()
        val state = StripState()
        var vectorMode by mutableStateOf(true)
        var reported = -1
        var clicks = 0
        val spec = StripSpec(
            items = emptyList(),
            legacyContent = {
                for (i in 1..12) {
                    Box(Modifier.size(60.dp, 40.dp).clickable { clicks++ }.semantics { contentDescription = "Probe chip $i" })
                }
            },
            overlays = { Text("Probe overlay") },
        )
        activity.setContent {
            BrushworkTheme {
                Surface {
                    StripHost(spec, ToolId.BRUSH, vectorMode, state, onContentWidth = { reported = it })
                }
            }
        }
        RobolectricUi.settle(10, 50)

        // The VECTOR chip first, with its v1.7 text and description.
        val chip = described("Vector mode is on") ?: throw AssertionError("no VECTOR chip")
        assertEquals(listOf("VECTOR"), chip.config.getOrNull(SemanticsProperties.Text)?.map { it.text })
        val first = described("Probe chip 1") ?: throw AssertionError("no chip 1")
        assertTrue("the VECTOR chip comes first", chip.positionInRoot.x < first.positionInRoot.x)
        // The chips in the spec's order, in one row that scrolls sideways.
        var lastX = Float.NEGATIVE_INFINITY
        for (i in 1..12) {
            val n = described("Probe chip $i") ?: throw AssertionError("no chip $i")
            assertTrue("chip $i after chip ${i - 1}", n.positionInRoot.x > lastX)
            lastX = n.positionInRoot.x
        }
        val last = described("Probe chip 12")!!
        val scroller = StripTestKit.horizontalScroller(last) ?: throw AssertionError("a legacy strip scrolls sideways")
        val range = scroller.config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("12 × 60 dp overflow 392 dp: ${range.maxValue()}", range.maxValue() > 0f)
        assertNotNull(scroller.config.getOrNull(SemanticsActions.ScrollBy))
        // No v1.8 strip parts; no More-row height; the content width is reported.
        noV18Nodes("legacy spec")
        assertEquals(0f, state.moreRowDp, 0f)
        assertTrue("content width reported: $reported", reported > 0)
        // The overlays are composed beside the strip.
        assertEquals(1, texts("Probe overlay").size)
        // The chips still work.
        requireNotNull(first.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        RobolectricUi.settle(4, 50)
        assertEquals(1, clicks)

        // Out of vector mode: no chip.
        vectorMode = false
        RobolectricUi.settle(10, 50)
        assertNull("no VECTOR chip out of vector mode", described("Vector mode is on"))
        assertTrue(texts("VECTOR").isEmpty())
        assertFalse(state.anyOpen)
        ctl.pause().stop().destroy()
        RobolectricUi.settle(4, 50)

        // Through the editor's panel: the Brush's v1.7 strip in the floating panel, one 44 dp row.
        val rig = StripRig().show()
        try {
            rig.select(ToolId.BRUSH)
            assertNotNull("the Brush strip is legacy at F3 or converted later", rig.spec)
            if (rig.spec.legacyContent != null) {
                val panel = StripTestKit.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("no options strip panel")
                assertEquals(44f, panel.size.height / rig.activity.resources.displayMetrics.density, 0.5f)
                noV18Nodes("the Brush's legacy strip")
                assertEquals(0f, rig.state.moreRowDp, 0f)
                // On a vector layer the VECTOR chip comes first in the panel.
                rig.select(ToolId.BRUSH, vector = true)
                assertNotNull("the VECTOR chip in the panel", described("Vector mode is on"))
                assertEquals(1, texts("VECTOR").size)
            }
        } finally {
            rig.close()
        }
    }
}
