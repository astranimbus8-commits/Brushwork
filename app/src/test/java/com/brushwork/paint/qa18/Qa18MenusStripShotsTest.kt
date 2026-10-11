package com.brushwork.paint.qa18

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.tools.strip.StripLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (items 8 and 9) on the user's phone (392 × 873 dp): every tool of the tool menu,
 * picked through the tool menu by a finger, on a raster and on a vector layer, its More row opened
 * by a finger on the toggle. For each strip: the toggle (where there is one) says what it does and
 * its state; the opened row is opaque under row 1, at most `StripLayout.MAX_MORE_LINES` lines, and
 * every control of row 1 and of the More row shows whole on screen; a second tap closes it. The
 * whole top of the screen is rendered (`menus-392-<nn>-<tool>[-vector].png`) for a human to read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.menusstripshotssandbox"])
class Qa18MenusStripShotsTest {

    @Test
    fun everyToolsStripOpensItsMoreRowWholeOnScreen() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        for (vector in listOf(false, true)) {
            h.section(if (vector) "every strip on a vector layer" else "every strip on a raster layer") { walk(h, vector) }
        }
        dog.interrupt()
        h.finish()
    }

    private fun onScreen(s: ChromeScreen, what: String, r: Rect) {
        assertTrue("$what on screen: $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f && r.top >= -0.5f && r.bottom <= s.heightDp + 0.5f)
    }

    private fun names(items: List<Clickables.Item>): String =
        items.sortedWith(compareBy({ (it.bounds.top / 8f).toInt() }, { it.bounds.left })).joinToString(" | ") { it.own.firstOrNull() ?: "?" }

    private fun walk(h: ChromeHarness, vector: Boolean) {
        val s = h.editor(Qa18MenusShots.doc(vector))
        assertEquals("392 dp wide", 392f, s.widthDp, 1f)
        var withRow = 0
        for ((i, id) in ToolMenu.tools.withIndex()) {
            Qa18MenusShots.pick(s, id)
            val what = "${id.label}${if (vector) " (vector)" else ""}"
            val row1 = s.tagged(ChromeTags.OPTIONS_STRIP)?.takeIf { it.width > 0f && it.height > 0f }
            val row1Items = row1?.let { Clickables.onScreen(s, inside = it) }.orEmpty()
            row1Items.forEach { onScreen(s, "$what: \"${it.own.firstOrNull()}\" on row 1", it.bounds) }
            val toggle = s.tagged(V18Tags.STRIP_TOGGLE)?.takeIf { row1 != null && row1.contains(it.center) }
            var moreItems = emptyList<Clickables.Item>()
            var lines = 0
            if (toggle != null) {
                assertEquals("$what: the toggle is collapsed", StripLabels18.COLLAPSED, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
                Finger.tap(s, StripLabels18.MORE_TOGGLE)
                Smoke.pump(300); SmokeUi.settle()
                assertEquals("$what: the toggle says the row is open", StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
                val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("$what: the toggle opens no More row")
                onScreen(s, "$what: the More row", more)
                assertEquals("$what: 4 dp under row 1", row1!!.bottom + StripLayout.GAP_DP, more.top, 1f)
                lines = Math.round((more.height + StripLayout.GAP_DP) / (StripLayout.LINE_DP + StripLayout.GAP_DP))
                assertTrue("$what: $lines lines", lines in 1..StripLayout.MAX_MORE_LINES)
                moreItems = Clickables.onScreen(s, inside = more)
                moreItems.forEach {
                    onScreen(s, "$what: \"${it.own.firstOrNull()}\" on the More row", it.bounds)
                    assertEquals("$what: \"${it.own.firstOrNull()}\" whole", it.width, it.bounds.width, 0.5f)
                }
                withRow++
            }
            val shot = Qa18MenusShots.capture()
            Qa18MenusShots.save(shot, s.density, "392-%02d-%s%s".format(i, id.name.lowercase(), if (vector) "-vector" else ""))
            System.err.println("[menus] $what | row 1: ${names(row1Items)} || More ($lines lines): ${names(moreItems)}")
            if (toggle != null) {
                Finger.tap(s, StripLabels18.MORE_TOGGLE)
                Smoke.pump(300); SmokeUi.settle()
                assertEquals("$what: a second tap closes it", null, s.tagged(V18Tags.STRIP_MORE_ROW))
            }
        }
        System.err.println("[menus] ${if (vector) "vector" else "raster"}: $withRow strips with a More row")
        assertTrue("strips with a More row: $withRow", withRow >= 12)
        Smoke.assertQuiet(s.c, "every strip at 392 dp")
    }
}
