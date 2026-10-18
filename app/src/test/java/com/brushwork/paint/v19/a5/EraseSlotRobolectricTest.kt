package com.brushwork.paint.v19.a5

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ErasableTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.BottomBar
import com.brushwork.paint.ui.editor.chrome.BottomSlotOpen
import com.brushwork.paint.v19.a1.A1Tools
import com.brushwork.paint.v19.a1.A1Tools.curveTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 (R11 a, A5 step 8): bottom-bar slot 1 is tool-aware for a tool that erases with its own
 * outline ([ErasableTool]: Curve, Polyline, Path and Shape, driven here by fakes in their place).
 * Off it reads the tool's "Erase with …" label; a tap turns erase mode on: it then reads
 * "Erasing with …: switch to painting", draws the open-state box and a tap turns it off again.
 * Every other tool keeps v1.8's slot 1 ("Switch to eraser", "Eraser on: switch to <tool>"); the
 * bar keeps its seven slots. Lead (R14, A1's request): slot 2, the tool menu, flashes its
 * open-state box once, for [CurveTool.SLOT_FLASH_MS], when a curve tool reopens a line in another.
 *
 * One test (sections): Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.v19.a5.eraseslotsandbox"])
class EraseSlotRobolectricTest {

    /** A tool that erases with its outline, in the place of [id]; its erase mode is snapshot state (as A1's and A9's). */
    private class FakeErasable(c: EditorController, override val id: ToolId) : Tool(c), ErasableTool {
        override var eraseOn by mutableStateOf(false)
            private set

        override fun setErase(on: Boolean) {
            eraseOn = on
        }

        override val eraseLabel = when (id) {
            ToolId.CURVE -> EraseLabels19.WITH_CURVE
            ToolId.POLYLINE -> EraseLabels19.WITH_POLYLINE
            ToolId.PATH -> EraseLabels19.WITH_PATH
            else -> EraseLabels19.WITH_SHAPE
        }

        override val erasingLabel = when (id) {
            ToolId.CURVE -> EraseLabels19.ERASING_CURVE
            ToolId.POLYLINE -> EraseLabels19.ERASING_POLYLINE
            ToolId.PATH -> EraseLabels19.ERASING_PATH
            else -> EraseLabels19.ERASING_SHAPE
        }
    }

    private val four = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.SHAPE)

    /** The bar's slots: its clickable nodes, left to right. */
    private fun slots(): List<SemanticsNode> = RobolectricUi.elements()
        .map { it.node }
        .filter { it.config.getOrNull(SemanticsActions.OnClick) != null && it.layoutInfo.isPlaced }
        .sortedBy { it.positionInWindow.x }

    private fun label(n: SemanticsNode): String = n.config[SemanticsProperties.ContentDescription].single()

    private fun open(n: SemanticsNode): Boolean = n.config.getOrNull(BottomSlotOpen) == true

    @Test
    fun slotOneErasesWithTheToolsOwnOutlineAndKeepsV18ForTheOthers() = Qa19Ui.uiTest { h ->
        h.section("slot 1 at 360 dp") {
            val c = Smoke.controller(RuntimeEnvironment.getApplication(), Smoke.document(400, 300, layers = 2, whiteBottom = true))
            c.tools
            h.show {
                BottomBar(
                    c, toolMenuOpen = false, onToolMenu = {}, onBrushPanel = {}, onColorPanel = {},
                    interfaceHidden = false, onToggleInterface = {}, layersOpen = false, onLayers = {}, onBack = {},
                )
            }
            assertEquals("seven slots", 7, slots().size)
            assertEquals("Switch to eraser", label(slots()[0]))
            assertNull("v1.8's slot 1 has no erase tag", StripTestKit.tagged(V19Tags.ERASE_SLOT))

            // Every other tool: v1.8's labels (the swap with the eraser).
            for (id in ToolId.entries - four.toSet() - ToolId.ERASER) {
                c.selectTool(id)
                Qa19Ui.settle()
                assertEquals("$id", "Switch to eraser", label(slots()[0]))
                assertFalse(open(slots()[0]))
            }
            c.selectTool(ToolId.BRUSH)
            c.selectTool(ToolId.ERASER)
            Qa19Ui.settle()
            assertEquals("Eraser on: switch to brush", label(slots()[0]))
            assertEquals(7, slots().size)

            // The four tools (fakes in their place): off, a tap on, open; a tap off.
            @Suppress("UNCHECKED_CAST")
            val tools = c.tools as MutableMap<ToolId, Tool>
            for (id in four) {
                val t = FakeErasable(c, id)
                tools[id] = t
                c.selectTool(id)
                Qa19Ui.settle()
                val off = slots()[0]
                assertEquals("$id", t.eraseLabel, label(off))
                assertFalse(open(off))
                assertTrue(StripTestKit.under(off, V19Tags.ERASE_SLOT) || off.config.getOrNull(SemanticsProperties.TestTag) == V19Tags.ERASE_SLOT)
                Qa19Ui.click(t.eraseLabel, exact = true)
                assertTrue("$id: erase mode on", t.eraseOn)
                assertEquals("$id: the tool stays", id, c.activeToolId)
                val on = slots()[0]
                assertEquals(t.erasingLabel, label(on))
                assertTrue("$id: the open-state box", open(on))
                assertEquals(7, slots().size)
                Qa19Ui.click(t.erasingLabel, exact = true)
                assertFalse("$id: erase mode off", t.eraseOn)
                assertEquals(t.eraseLabel, label(slots()[0]))
                assertFalse(open(slots()[0]))
                // Turned on elsewhere (the tool's "Erase instead of paint" row): the slot follows.
                t.setErase(true)
                Qa19Ui.settle()
                assertEquals(t.erasingLabel, label(slots()[0]))
                t.setErase(false)
            }
            // Back to a tool of v1.8's slot.
            c.selectTool(ToolId.BRUSH)
            Qa19Ui.settle()
            assertEquals("Switch to eraser", label(slots()[0]))
            Qa19Ui.assertNoTextOverflow("in the bottom bar")
        }
        h.section("slot 2 flashes once when a curve tool reopens a line in another (R14, lead)") {
            val c = A1Tools.controller()
            // A polyline on the active vector layer, drawn and committed with the Polyline tool.
            val poly = c.curveTool(ToolId.POLYLINE)
            for (p in listOf(Vec2(40f, 60f), Vec2(180f, 140f), Vec2(320f, 60f))) assertTrue(poly.addAnchor(p))
            poly.commit()
            val made = c.activeLayer.vector!!.objects.last() as VPath
            c.selectTool(ToolId.CURVE)
            h.show {
                BottomBar(
                    c, toolMenuOpen = false, onToolMenu = {}, onBrushPanel = {}, onColorPanel = {},
                    interfaceHidden = false, onToggleInterface = {}, layersOpen = false, onLayers = {}, onBack = {},
                )
            }
            Qa19Ui.settle()
            assertEquals("Tools (current: ${ToolId.CURVE.label})", label(slots()[1]))
            assertFalse("no flash when the bar is shown", open(slots()[1]))
            // Tool changes of their own never flash.
            for (id in listOf(ToolId.POLYLINE, ToolId.PATH, ToolId.CURVE)) c.selectTool(id)
            Qa19Ui.settle(4, 16)
            assertFalse("no flash for a tool picked by hand", open(slots()[1]))
            // The Curve tool taps the polyline: it opens in Polyline, and slot 2 flashes.
            val signal = CurveTool.slotFlashes(c.tools)
            val pts = VectorOps.toVectorPath(made).flatten(0.25f).single().points
            val on = pts[pts.size / 3]
            c.pointerDown(ToolPoint(on.x, on.y))
            c.pointerUp(ToolPoint(on.x, on.y))
            assertEquals(ToolId.POLYLINE, c.activeToolId)
            assertEquals(signal + 1, CurveTool.slotFlashes(c.tools))
            Qa19Ui.settle(4, 16)
            assertEquals("Tools (current: ${ToolId.POLYLINE.label})", label(slots()[1]))
            assertTrue("slot 2 flashes its open-state box", open(slots()[1]))
            assertEquals("the bar keeps its seven slots", 7, slots().size)
            // Once: off after CurveTool.SLOT_FLASH_MS, and it stays off.
            Qa19Ui.settle(12, 50)
            assertFalse("the flash ends after ${CurveTool.SLOT_FLASH_MS} ms", open(slots()[1]))
            Qa19Ui.settle(4, 50)
            assertFalse("one flash", open(slots()[1]))
        }
    }
}
