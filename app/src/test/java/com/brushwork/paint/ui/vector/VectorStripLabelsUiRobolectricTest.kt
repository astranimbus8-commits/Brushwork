package com.brushwork.paint.ui.vector

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.Clickables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * I10 for the vector tools' options strips (v1.6 §3.7.11): with the tool menu open beside the
 * Curve, Polyline, Path or Shape strip, every visible clickable label is unique at the user's
 * phone size — with the strip at its start and scrolled to its "Settings" chip. The strips'
 * "Settings" chips still show "Settings" but are known as "Curve settings", "Polyline settings",
 * "Path settings" and "Shape settings", apart from the menu's "Settings" cell. (The top row's
 * "Ruler" circle and the menu's "Ruler" cell share their label by design, as in
 * [com.brushwork.paint.ui.editor.chrome.UniqueLabelsTest].)
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.striplabelssandbox"])
class VectorStripLabelsUiRobolectricTest {

    /** A value a control shows ("100 %", "8.0", "12 px"), not its name (as UniqueLabels). */
    private val value = Regex("""^[-+]?[\d.,\s]+(%|px|°| px| %)?$""")

    private fun assertUnique(where: String, items: List<Clickables.Item>, allowed: Set<String> = emptySet()) {
        val dups = Clickables.duplicates(items).filterKeys { it !in allowed && !value.matches(it) }
        assertTrue("$where: labels shared by several clickables: $dups", dups.isEmpty())
    }

    /** Scrolls the options strip until the clickable named [label] shows whole. */
    private fun scrollStripTo(s: ChromeScreen, label: String) {
        fun node(): SemanticsNode {
            var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node ?: throw AssertionError("no \"$label\": ${SmokeUi.shown()}")
            while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            return n ?: throw AssertionError("\"$label\" is not clickable")
        }
        fun whole(): Boolean = node().let { it.boundsInWindow.width >= it.size.width - 1f && it.boundsInWindow.height >= it.size.height - 1f }
        if (whole()) return
        var p: SemanticsNode? = node()
        while (p != null && p.config.getOrNull(SemanticsActions.ScrollBy) == null) p = p.parent
        val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is clipped and the strip does not scroll" }
        repeat(40) {
            if (whole()) return
            // (The semantics scroll animates: let each run finish before the next.)
            scroll.invoke(120f * s.density, 0f)
            settle(8)
        }
        val range = p.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        throw AssertionError("\"$label\" never scrolled into view: ${node().boundsInWindow} of ${node().size}, scrolled ${range?.value?.invoke()} of ${range?.maxValue?.invoke()}")
    }

    /** [id]'s strip with and without the tool menu: unique labels, and the two "Settings" told apart. */
    private fun audit(s: ChromeScreen, id: ToolId, where: String) {
        val chip = "${id.label} settings"
        assertUnique("$where, strip at its start", Clickables.onScreen(s))
        scrollStripTo(s, chip)
        val strip = Clickables.onScreen(s)
        assertUnique("$where, strip at its Settings chip", strip)
        assertEquals("$where: the strip's chip is \"$chip\"", 1, strip.count { chip in it.labels })
        assertTrue("$where: no control is known as the bare \"Settings\" without the menu", strip.none { "Settings" in it.labels })

        click("Tools (current: ${id.label})")
        scrollStripTo(s, chip)
        val menu = Clickables.onScreen(s)
        assertUnique("$where, tool menu open", menu, allowed = setOf("Ruler"))
        assertTrue("$where: at most the top-row circle and the tool cell share \"Ruler\"", menu.count { "Ruler" in it.labels } <= 2)
        assertEquals("$where: \"Settings\" is the menu cell alone", 1, menu.count { "Settings" in it.labels })
        assertEquals("$where: the strip's chip shows beside the menu", 1, menu.count { chip in it.labels })
        click("Tools (current: ${id.label})")
        Smoke.assertQuiet(s.c, where)
    }

    private fun curveWithSelectedPoint(s: ChromeScreen, id: ToolId): CurveTool {
        val tool = s.c.tools.getValue(id) as CurveTool
        for (p in listOf(Vec2(60f, 200f), Vec2(150f, 80f), Vec2(260f, 210f), Vec2(350f, 90f))) tool.addAnchor(p)
        tool.select(1)
        settle()
        return tool
    }

    @Test
    fun theStripsAndTheToolMenuShareNoLabel() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.SHAPE)) {
            h.section("${id.label}, nothing drawn") {
                val s = h.editor()
                s.c.selectTool(id)
                settle()
                audit(s, id, "${id.label} (empty)")
            }
        }
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            h.section("${id.label}, a point selected") {
                val s = h.editor()
                s.c.selectTool(id)
                settle()
                val tool = curveWithSelectedPoint(s, id)
                if (id == ToolId.CURVE) {
                    // The Handles group: "In and out" / "In" / "Out", named "Handles: …".
                    assertTrue("handles: ${SmokeUi.shown()}", SmokeUi.has("Handles: In and out", exact = true))
                } else {
                    assertTrue("the point's weight: ${SmokeUi.shown()}", SmokeUi.has("Type the point weight"))
                }
                audit(s, id, "${id.label} (point 2 selected)")
                tool.discard()
                settle()
            }
        }
        h.section("Shape, a pending shape") {
            val s = h.editor()
            s.c.selectTool(ToolId.SHAPE)
            settle()
            val shape = s.c.tools.getValue(ToolId.SHAPE) as ShapeTool
            assertTrue(shape.ensurePending())
            settle()
            audit(s, ToolId.SHAPE, "Shape (pending)")
            shape.discard()
            settle()
        }
        dog.interrupt()
        h.finish()
    }
}
