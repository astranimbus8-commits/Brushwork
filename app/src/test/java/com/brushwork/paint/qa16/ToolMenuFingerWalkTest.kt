package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.editor.ToolMenuEntry
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A finger walks the tool menu (§3.7.6) on the user's phone: every cell, tapped with real touch
 * events at the middle of what shows of it (cells below the fold scrolled up by a finger first),
 * does what its label says and closes the menu. The menu lies over the slider rows "as ibis": a
 * tap on a cell where the size row's value, its − button or its track lie under it is the cell's,
 * never the slider's (the brush size and opacity stay, no value dialog opens).
 */
internal object ToolMenuWalk {

    private fun label(e: ToolMenuEntry) = when (e) {
        is ToolMenuEntry.Tool -> e.id.label
        ToolMenuEntry.Filters -> "Filters"
        ToolMenuEntry.Canvas -> "Canvas"
        ToolMenuEntry.Settings -> "Settings"
    }

    private fun ChromeScreen.size() = c.presetFor(ToolId.BRUSH)!!.size
    private fun ChromeScreen.opacity() = c.presetFor(ToolId.BRUSH)!!.opacity

    /** Back until no panel, dialog or menu is left, then the Brush again (not what is tested). */
    private fun reset(s: ChromeScreen) {
        repeat(4) {
            if (SmokeUi.windows().size > 1 || SmokeUi.sheetTitles().isNotEmpty() || SmokeUi.pillTitles().isNotEmpty() || Finger.toolMenu(s) != null) Finger.back()
        }
        if (s.c.activeToolId != ToolId.BRUSH) { s.c.selectTool(ToolId.BRUSH); SmokeUi.settle() }
        assertEquals("reset: one window", 1, SmokeUi.windows().size)
        assertTrue("reset: no panel ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", SmokeUi.sheetTitles().isEmpty())
    }

    private fun openMenu(s: ChromeScreen) {
        Finger.tap(s, "Tools (current: ${s.c.activeToolId.label})")
        assertNotNull("the tool menu opens", Finger.toolMenu(s))
    }

    /** What a tap on [e]'s cell must do. */
    private fun assertDid(s: ChromeScreen, e: ToolMenuEntry, where: String) {
        assertNull("$where: the menu closes", Finger.toolMenu(s))
        when (e) {
            is ToolMenuEntry.Tool -> assertEquals("$where: the tool", e.id, s.c.activeToolId)
            ToolMenuEntry.Filters -> SmokeUi.assertPanelShown("Filters")
            ToolMenuEntry.Canvas -> SmokeUi.assertPanelShown("Canvas")
            ToolMenuEntry.Settings -> assertTrue("$where: the settings dialog; shown ${SmokeUi.shown().take(40)}", SmokeUi.windows().size == 2 && SmokeUi.has("Editor settings", exact = true))
        }
    }

    fun run() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("every cell by a finger") {
            val s = h.editor(IbisDocs.page())
            val size0 = s.size()
            val opacity0 = s.opacity()
            val bad = mutableListOf<String>()
            for (e in ToolMenu.entries) {
                val l = label(e)
                try {
                    reset(s)
                    openMenu(s)
                    val menu = Finger.toolMenu(s)!!
                    var cell = Finger.control(s, l, menu) ?: throw AssertionError("no cell")
                    if (cell.height < 51f) {
                        // Below the fold: a finger pushes the menu up.
                        Finger.slowDrag(s, menu.center.x to menu.bottom - 20f, menu.center.x to menu.top + 20f)
                        assertNotNull("the menu stays open while it scrolls", Finger.toolMenu(s))
                        cell = Finger.control(s, l, menu) ?: throw AssertionError("no cell after scrolling")
                    }
                    assertTrue("the whole cell shows: $cell", cell.height >= 51f && cell.width >= 74f)
                    Finger.tapAt(s, cell.center.x, cell.center.y)
                    assertDid(s, e, l)
                    assertEquals("$l: the brush size stays", size0, s.size(), 0f)
                    assertEquals("$l: the opacity stays", opacity0, s.opacity(), 0f)
                } catch (t: Throwable) {
                    bad += "$l: ${t.message}"
                }
            }
            reset(s)
            assertTrue("cells that don't do what they say:\n" + bad.joinToString("\n"), bad.isEmpty())
        }
        h.section("cells over the slider rows are the cells'") {
            val s = h.editor(IbisDocs.page())
            val size0 = s.size()
            val opacity0 = s.opacity()
            val sizeRow = Finger.control(s, "Smaller brush") ?: throw AssertionError("no − of the size row")
            val track = Finger.element(s, "Brush size") ?: throw AssertionError("no size track")
            val value = Finger.control(s, "Type brush size") ?: throw AssertionError("no size value")
            val canvas = ToolMenu.entries.first { it == ToolMenuEntry.Canvas }
            val settings = ToolMenu.entries.first { it == ToolMenuEntry.Settings }
            // Points of the Canvas / Settings cells (row 8) that lie on the size row's controls.
            data class Spot(val name: String, val entry: ToolMenuEntry, val pick: (cell: androidx.compose.ui.geometry.Rect) -> Pair<Float, Float>?)
            val spots = listOf(
                Spot("Canvas over the − button", canvas) { c ->
                    val x = sizeRow.center.x.coerceAtMost(c.right - 3f)
                    val y = sizeRow.center.y
                    if (x in c.left..c.right && y in c.top..c.bottom && x in sizeRow.left..sizeRow.right) x to y else null
                },
                Spot("Canvas over the value", canvas) { c ->
                    val x = (value.left + 6f).coerceAtLeast(c.left + 3f)
                    val y = value.center.y
                    if (x in c.left..c.right && y in c.top..c.bottom && x in value.left..value.right) x to y else null
                },
                Spot("Settings over the track", settings) { c ->
                    val x = c.center.x
                    val y = track.center.y
                    if (x in track.left..track.right && y in c.top..c.bottom) x to y else null
                },
            )
            val bad = mutableListOf<String>()
            var tested = 0
            for (spot in spots) {
                reset(s)
                openMenu(s)
                val menu = Finger.toolMenu(s)!!
                val cell = Finger.control(s, label(spot.entry), menu) ?: throw AssertionError("no ${label(spot.entry)} cell")
                val p = spot.pick(cell) ?: continue
                tested++
                Finger.tapAt(s, p.first, p.second)
                try {
                    assertDid(s, spot.entry, spot.name)
                    assertEquals("${spot.name}: the brush size stays", size0, s.size(), 0f)
                    assertEquals("${spot.name}: the opacity stays", opacity0, s.opacity(), 0f)
                    assertTrue("${spot.name}: no value dialog", !SmokeUi.has("Type brush size", exact = false) || SmokeUi.windows().size == 1 || SmokeUi.has("Editor settings", exact = true))
                } catch (t: Throwable) {
                    bad += "${spot.name} at (${p.first}, ${p.second}) dp: ${t.message}"
                }
            }
            reset(s)
            assertTrue("no spot of the menu lies over the size row's controls (layout changed?)", tested > 0)
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
        }
        dog.interrupt()
        h.finish()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.toolmenuwalksandbox"])
class ToolMenuFingerWalkTest {
    @Test
    fun everyCellDoesWhatItSays() = ToolMenuWalk.run()
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.toolmenuwalknarrowsandbox"])
class ToolMenuFingerWalkNarrowTest {
    @Test
    fun everyCellDoesWhatItSays() = ToolMenuWalk.run()
}
