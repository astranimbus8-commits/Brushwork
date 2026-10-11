package com.brushwork.paint.qa18

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.IbisDocs
import com.brushwork.paint.qa16.MoreWalk
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.MenuLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.MenuEntry
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.editor.chrome.TopButton
import com.brushwork.paint.ui.editor.chrome.TopRow
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisDims
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (design §3.9 item 9, D28): the More menu's five sections at 360 × 640 dp. Each run of
 * entries is headed by its section's name ("Clipboard", "Bring in", "Send out", "View", "This
 * artwork"): a 24 dp heading that takes no tap (the menu stays open, nothing acts), tagged
 * [V18Tags.MENU_SECTION_PREFIX] + its name, where v1.7 drew a divider. The entries keep their
 * labels and their order within a section; "Canvas…" heads "This artwork". "Settings", the last
 * entry, is reached with one scroll: the menu scrolls by at most its own visible height. On a
 * screen narrow enough to fold top-row circles into the menu, they come first, under no header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.menusectionssandbox"])
class MoreMenuSectionsTest {

    private val sections = listOf(
        MenuLabels18.CLIPBOARD to listOf("Copy layer", "Paste"),
        MenuLabels18.BRING_IN to listOf("Import picture", "Import SVG or PDF…"),
        MenuLabels18.SEND_OUT to listOf("Export PNG", "Export JPG", "Export SVG…", "Export PDF…", "Share"),
        MenuLabels18.VIEW to listOf("Flip view", "Fit to screen", "100% (actual pixels)", "Reset rotation"),
        MenuLabels18.THIS_ARTWORK to listOf("Canvas…", "Increments…", "Save now", "Settings"),
    )

    private fun inWindow(w: View) = RobolectricUi.elements().filter { it.window === w }

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    private fun SemanticsNode.subtreeText(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.subtreeText() }

    /** The header rows of the menu in [w], top to bottom (laid-out y, scrolled out or not). */
    private fun headers(w: View): List<SemanticsNode> =
        inWindow(w).map { it.node }.filter { it.tag()?.startsWith(V18Tags.MENU_SECTION_PREFIX) == true }.sortedBy { it.positionInWindow.y }

    /** Laid-out middle y (window px) of the entry row whose text is [label]. */
    private fun entryY(w: View, label: String): Float {
        val text = inWindow(w).firstOrNull { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
            ?: throw AssertionError("no \"$label\" in the More menu")
        var n: SemanticsNode? = text.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        val row = n ?: throw AssertionError("\"$label\" is not a menu entry")
        return row.positionInWindow.y + row.size.height / 2f
    }

    @Test
    fun theMoreMenuIsInFiveSectionsAndSettingsIsOneScrollAway() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("the editor's menu at 360 × 640 dp") { editorMenu(h) }
        h.section("a top row folded to 320 dp") { foldedFirst() }
        dog.interrupt()
        h.finish()
    }

    private fun editorMenu(h: ChromeHarness) {
        val s = h.editor(IbisDocs.page())
        MoreWalk.open(s)
        val w = SmokeUi.windows().last()
        val found = headers(w)
        assertEquals("the section headers, top to bottom", sections.map { it.first }, found.map { it.subtreeText().joinToString("") })
        for (n in found) {
            val name = n.subtreeText().joinToString("")
            assertEquals("\"$name\" is tagged by its name", V18Tags.MENU_SECTION_PREFIX + name, n.tag())
            assertEquals("\"$name\" is 24 dp tall", IbisDims.MoreMenuSection.value, n.size.height / s.density, 0.5f)
            assertTrue("\"$name\" is a heading", n.config.contains(SemanticsProperties.Heading))
            assertNull("\"$name\" takes no tap", n.config.getOrNull(SemanticsActions.OnClick))
        }
        // Every entry sits under its section's header, in the v1.7 order within the section.
        val tops = found.map { it.positionInWindow.y }
        var last = Float.NEGATIVE_INFINITY
        sections.forEachIndexed { i, (name, labels) ->
            for (label in labels) {
                val y = entryY(w, label)
                assertTrue("\"$label\" is under \"$name\"", y > tops[i] && (i == tops.lastIndex || y < tops[i + 1]))
                assertTrue("\"$label\" keeps its order", y > last)
                last = y
            }
        }
        // One scroll: the menu scrolls by at most its own visible height, and one page shows "Settings" whole.
        val body = inWindow(w).first { it.node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        val range = body.node.config[SemanticsProperties.VerticalScrollAxisRange]
        val page = body.bounds.height
        assertTrue("the menu scrolls (${range.maxValue()} px)", range.maxValue() > 0f)
        assertTrue("one scroll reaches the end: ${range.maxValue()} px to scroll, ${page} px shown", range.maxValue() <= page + 0.5f)
        requireNotNull(body.node.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, page)
        SmokeUi.settle()
        val settings = Finger.control(s, "Settings") ?: throw AssertionError("no Settings")
        assertTrue("\"Settings\" shows whole after one scroll: $settings", settings.height >= 40f - 0.5f)
        // A header takes no tap: the menu stays open and nothing acts.
        val artwork = headers(w).first { it.subtreeText().joinToString("") == MenuLabels18.THIS_ARTWORK }
        val b = artwork.boundsInWindow
        assertTrue("\"${MenuLabels18.THIS_ARTWORK}\" shows at the end: $b", b.height >= IbisDims.MoreMenuSection.value * s.density - 1f)
        RobolectricUi.tap(w, b.center.x, b.center.y)
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("a tap on a header leaves the menu open", SmokeUi.windows().contains(w))
        assertEquals("…and opens nothing", 2, SmokeUi.windows().size)
        Finger.back()
        assertEquals("Back closed the menu", 1, SmokeUi.windows().size)
        Smoke.assertQuiet(s.c, "More menu sections")
    }

    /** A top row folded to 320 dp: its folded circles come first in the menu, under no header. */
    private fun foldedFirst() {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = ctl.get()
        val spec = ChromeLayout.topRow(320f)
        assertTrue("320 dp folds circles", spec.folded.isNotEmpty())
        val buttons = ChromeLayout.TopSlot.entries.map { slot -> TopButton(slot, "Circle ${slot.name}", Icons.Filled.Star) {} }
        val entries = listOf(
            MenuEntry("Entry A", Icons.Filled.Star, section = MenuLabels18.CLIPBOARD) {},
            MenuEntry("Entry B", Icons.Filled.Star, section = MenuLabels18.VIEW) {},
        )
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.width(320.dp)) {
                    TopRow(spec, buttons, moreOpen = true, onMoreOpenChange = {}, moreHeader = "Doc", moreEntries = entries)
                }
            }
        }
        SmokeUi.settle()
        val menu = SmokeUi.windows().last()
        val folded = headers(menu)
        assertEquals("one header per section", listOf(MenuLabels18.CLIPBOARD, MenuLabels18.VIEW), folded.map { it.subtreeText().joinToString("") })
        for (slot in spec.folded) {
            assertTrue("folded ${slot.name} comes before the first header", entryY(menu, "Circle ${slot.name}") < folded.first().positionInWindow.y)
        }
        assertTrue("Entry A under Clipboard", entryY(menu, "Entry A") in folded[0].positionInWindow.y..folded[1].positionInWindow.y)
        assertTrue("Entry B under View", entryY(menu, "Entry B") > folded[1].positionInWindow.y)
        ctl.pause().stop().destroy()
    }
}
