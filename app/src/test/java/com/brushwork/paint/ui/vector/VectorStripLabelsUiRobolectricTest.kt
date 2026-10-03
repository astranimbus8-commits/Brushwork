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
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.editor.chrome.PIXEL_ONLY_STATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * I10 for the vector tools' options strips (v1.6 §3.7.11): with the tool menu open beside the
 * Curve, Polyline, Path or Shape strip, every visible clickable label is unique at the user's
 * phone size — at every screen of the strip, swept from its start to its end. The strips'
 * "Settings" chips still show "Settings" but are known as "Curve settings", "Polyline settings",
 * "Path settings" and "Shape settings", apart from the menu's "Settings" cell. The strips are
 * audited empty, with a point selected (the Curve tool's Handles group, the Path tool's Weight),
 * on a vector layer, with a pending shape and in the Shape tool's Points mode (its Handles
 * group). (The top row's "Ruler" circle and the menu's "Ruler" cell share their label by design,
 * as in [com.brushwork.paint.ui.editor.chrome.UniqueLabelsTest]. On a vector layer the menu's
 * pixel-only cells show a "px" badge that is decoration: they are read as
 * [PIXEL_ONLY_STATE], and "px" is no clickable's label.)
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.striplabelssandbox"])
class VectorStripLabelsUiRobolectricTest {

    /**
     * No clickable is known as "px"; the cells marked [PIXEL_ONLY_STATE] are pixel-only tools,
     * one per such tool at most.
     */
    private fun assertBadgesOnPixelCells(where: String, items: List<Clickables.Item>) {
        val pixelNames = LayerToolRules.PIXEL_ONLY.map { it.label }.toSet()
        val marked = items.filter { it.node.config.getOrNull(SemanticsProperties.StateDescription) == PIXEL_ONLY_STATE }
        assertTrue("$where: no control is labelled \"px\"", items.none { "px" in it.labels })
        for (item in marked) assertTrue("$where: a pixel-only mark on a control that is no pixel-only tool cell: ${item.labels}", item.labels.any { it in pixelNames })
        assertTrue("$where: ${marked.size} pixel-only marks for ${pixelNames.size} pixel-only tools", marked.size <= pixelNames.size)
    }

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

    /** The options strip's scrolling row (it carries the scroll action and range), or null when it can't scroll. */
    private fun stripRow(): SemanticsNode? {
        val strip = RobolectricUi.elements().lastOrNull {
            it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == ChromeTags.OPTIONS_STRIP
        }?.node ?: throw AssertionError("no options strip")
        fun find(n: SemanticsNode): SemanticsNode? {
            if (n.config.getOrNull(SemanticsActions.ScrollBy) != null && n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null) return n
            for (child in n.children) find(child)?.let { return it }
            return null
        }
        return find(strip)
    }

    /**
     * Every screen of the options strip, from its start to its end (120 dp at a time, so each
     * control passes through the view whole): [check] at each with where the strip is. Returns
     * the number of screens checked.
     */
    private fun sweepStrip(s: ChromeScreen, check: (String) -> Unit): Int {
        val row = stripRow() ?: run { check("the strip (it doesn't scroll)"); return 1 }
        val range = row.config[SemanticsProperties.HorizontalScrollAxisRange]
        val scroll = requireNotNull(row.config[SemanticsActions.ScrollBy].action)
        var guard = 0
        while (range.value() > 0f && guard++ < 40) {
            scroll.invoke(-600f * s.density, 0f)
            settle(8)
        }
        assertEquals("the strip is back at its start", 0f, range.value(), 0.5f)
        var screens = 0
        while (true) {
            check("strip at ${range.value().toInt()} of ${range.maxValue().toInt()} px")
            screens++
            if (range.value() >= range.maxValue() - 0.5f) break
            val before = range.value()
            scroll.invoke(120f * s.density, 0f)
            settle(8)
            assertTrue("the strip scrolls on (${range.value()} after $before)", range.value() > before)
            assertTrue("a strip of at most 60 screens", screens < 60)
        }
        if (range.maxValue() > 0f) assertTrue("more than one screen checked: $screens", screens >= 2)
        return screens
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
        // Only the top-row circle and the menu cell share "Ruler"; the pixel-only cells are marked by state.
        val shared = setOf("Ruler")
        assertUnique("$where, tool menu open", menu, allowed = shared)
        assertBadgesOnPixelCells("$where, tool menu open", menu)
        if (s.c.isVectorMode) assertTrue("$where: the menu marks its pixel-only cells", menu.any { it.node.config.getOrNull(SemanticsProperties.StateDescription) == PIXEL_ONLY_STATE })
        assertTrue("$where: at most the top-row circle and the tool cell share \"Ruler\"", menu.count { "Ruler" in it.labels } <= 2)
        assertEquals("$where: \"Settings\" is the menu cell alone", 1, menu.count { "Settings" in it.labels })
        assertEquals("$where: the strip's chip shows beside the menu", 1, menu.count { chip in it.labels })
        // Every screen of the strip beside the open menu.
        var chipSeen = false
        val screens = sweepStrip(s) { at ->
            val items = Clickables.onScreen(s)
            assertUnique("$where, tool menu open, $at", items, allowed = shared)
            assertBadgesOnPixelCells("$where, tool menu open, $at", items)
            assertTrue("$where, $at: at most the top-row circle and the tool cell share \"Ruler\"", items.count { "Ruler" in it.labels } <= 2)
            assertEquals("$where, $at: \"Settings\" is the menu cell alone", 1, items.count { "Settings" in it.labels })
            if (items.any { chip in it.labels }) chipSeen = true
        }
        assertTrue("$where: the sweep passed the strip's \"$chip\"", chipSeen)
        println("[strip] $where: $screens screens of the strip checked beside the tool menu")
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
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            h.section("${id.label}, a point selected") {
                val s = h.editor()
                s.c.selectTool(id)
                settle()
                val tool = curveWithSelectedPoint(s, id)
                when (id) {
                    // The Handles group: "In and out" / "In" / "Out", named "Handles: …".
                    ToolId.CURVE -> assertTrue("handles: ${SmokeUi.shown()}", SmokeUi.has("Handles: In and out", exact = true))
                    ToolId.PATH -> assertTrue("the point's weight: ${SmokeUi.shown()}", SmokeUi.has("Type the point weight"))
                    else -> assertTrue("the point's actions: ${SmokeUi.shown()}", SmokeUi.has("Delete point"))
                }
                audit(s, id, "${id.label} (point 2 selected)")
                tool.discard()
                settle()
            }
        }
        h.section("Curve on a vector layer, a point selected") {
            val s = h.editor()
            assertNotNull(s.c.addVectorLayer())
            s.c.selectTool(ToolId.CURVE)
            settle()
            assertTrue("vector mode", s.c.isVectorMode)
            val tool = curveWithSelectedPoint(s, ToolId.CURVE)
            assertTrue("handles: ${SmokeUi.shown()}", SmokeUi.has("Handles: In and out", exact = true))
            audit(s, ToolId.CURVE, "Curve (vector layer, point 2 selected)")
            tool.discard()
            settle()
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
        h.section("Shape, Points mode with its Handles group") {
            val s = h.editor()
            s.c.selectTool(ToolId.SHAPE)
            settle()
            val shape = s.c.tools.getValue(ToolId.SHAPE) as ShapeTool
            shape.update { it.copy(type = ShapeType.ELLIPSE) }
            assertTrue(shape.ensurePending())
            shape.setPointEditing(true)
            settle()
            assertTrue("points mode", shape.pointsMode)
            // The Shape tool's handle sides read as the Curve tool's.
            for (label in listOf("In and out", "In", "Out", "All points")) assertTrue("\"$label\": ${SmokeUi.shown()}", SmokeUi.has(label, exact = true))
            audit(s, ToolId.SHAPE, "Shape (points mode)")
            shape.discard()
            settle()
        }
        dog.interrupt()
        h.finish()
    }
}
