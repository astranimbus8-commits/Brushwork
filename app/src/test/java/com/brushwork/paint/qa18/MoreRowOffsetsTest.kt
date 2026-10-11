package com.brushwork.paint.qa18

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.tools.FakePillTool
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (design §3.9): the open More row of the options strip hangs 4 dp under row 1 and moves the
 * X / Y pill and the selection bar (the object bar with vector objects selected) down by its
 * height plus 4 dp, for 1, 2 and 3 lines (more items still make 3); closed, they are back in
 * their v1.7 places, and the canvas never moves. A fake strip ([FakeStrips]) on the curve tool's
 * place (a [FakePillTool], so the pill shows), at 392 and 360 dp, without and with the VECTOR cell
 * on row 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.morerowoffsetssandbox"])
class MoreRowOffsetsTest {

    @Test
    fun theOpenMoreRowMovesThePillAndTheBarDown() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        try {
            for (width in listOf(392, 360)) {
                if (width == 360) RuntimeEnvironment.setQualifiers("w360dp-h640dp-xxhdpi")
                for (vector in listOf(false, true)) {
                    h.section("$width dp, ${if (vector) "with" else "without"} the VECTOR cell") { offsets(width, vector) }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers("w392dp-h873dp-xxhdpi")
        }
        dog.interrupt()
        h.finish()
    }

    private fun doc(vector: Boolean): Document = Smoke.document(400, 300, layers = 2, whiteBottom = true).also { d ->
        if (vector) {
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 2
        }
    }

    private class Places(val row1: Rect, val pill: Rect, val bar: Rect, val more: Rect?, val canvas: Pair<Float, Float>, val zoom: Float)

    private fun places(s: ChromeScreen) = Places(
        s.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("no options strip"),
        s.tagged(ChromeTags.PILL_SLOT) ?: throw AssertionError("no pill"),
        s.tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("no selection bar"),
        s.tagged(V18Tags.STRIP_MORE_ROW),
        s.screen(0f, 0f),
        s.c.viewTransform.zoom,
    )

    private fun offsets(width: Int, vector: Boolean) {
        val kit = ObjectTestKit(400, 300)
        FakeStripEditor.open(doc(vector), FakeStrips.spec(1)) { c ->
            val fake = FakePillTool(c, listOf(Vec2(40f, 40f), Vec2(120f, 40f), Vec2(120f, 100f), Vec2(40f, 100f)))
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = fake
            c.selectTool(ToolId.CURVE)
            c.selectAll()
            if (vector) {
                val layer = c.doc.layers[2]
                c.vectors.addObjects(layer, listOf(kit.box(200f, 150f, 300f, 250f)), "Add")
                c.vectors.setSelection(layer, setOf(1L))
            }
        }.use { e ->
            val s = e.screen
            val c = s.c
            Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
            SmokeUi.settle()
            assertEquals("$width dp wide", width.toFloat(), s.widthDp, 1f)
            assertEquals("vector mode", vector, c.isVectorMode)
            if (vector) assertTrue("the object bar shows", c.vectors.selectedIds.isNotEmpty())
            val closed = places(s)
            assertTrue("the pill shows: ${closed.pill}", closed.pill.height > 0f)
            assertNull("closed: no More row", closed.more)
            assertEquals("the pill 6 dp under row 1", closed.row1.bottom + 6f, closed.pill.top, 1f)
            assertEquals("the bar under the pill", ChromeLayout.selectionBarTop(0f, closed.pill.height) - ChromeLayout.pillTop(0f), closed.bar.top - closed.pill.top, 1f)

            for ((items, lines) in listOf(1 to 1, 2 to 2, 3 to 3, 5 to 3)) {
                val name = "$items More items"
                e.spec.value = FakeStrips.spec(items)
                SmokeUi.settle()
                Finger.tap(s, StripLabels18.MORE_TOGGLE)
                Smoke.pump(300); SmokeUi.settle()
                assertEquals("$name: open", StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
                val open = places(s)
                val more = open.more ?: throw AssertionError("$name: no More row")
                assertEquals("$name: row 1 stays", closed.row1, open.row1)
                assertEquals("$name: 4 dp under row 1", closed.row1.bottom + StripLayout.GAP_DP, more.top, 1f)
                assertEquals("$name: $lines lines tall", StripLayout.moreRowDp(lines), more.height, 1f)
                val shift = StripLayout.moreRowDp(lines) + ChromeLayout.MORE_ROW_GAP
                assertEquals("$name: the pill moves by the row plus 4 dp", closed.pill.top + shift, open.pill.top, 1f)
                assertEquals("$name: so does the bar", closed.bar.top + shift, open.bar.top, 1f)
                assertTrue("$name: the pill under the More row", open.pill.top >= more.bottom - 0.5f)
                assertTrue("$name: the bar under the pill", open.bar.top >= open.pill.bottom - 0.5f)
                assertTrue("$name: the More row on screen", more.bottom <= s.heightDp + 0.5f && more.right <= s.widthDp + 0.5f)
                assertEquals("$name: the canvas does not move", closed.canvas, open.canvas)
                assertEquals("$name: nor zoom", closed.zoom, open.zoom, 0f)
                for (k in 1..items) assertNotNull("$name: \"${FakeStrips.more(k)}\" on the More row", Fingers18.described(s, FakeStrips.more(k)))

                Finger.tap(s, StripLabels18.MORE_TOGGLE)
                Smoke.pump(300); SmokeUi.settle()
                val back = places(s)
                assertNull("$name: closed again", back.more)
                assertEquals("$name: the pill back", closed.pill.top, back.pill.top, 0.5f)
                assertEquals("$name: the bar back", closed.bar.top, back.bar.top, 0.5f)
            }
            Smoke.assertQuiet(c, "More row offsets")
        }
        kit.close()
    }
}
