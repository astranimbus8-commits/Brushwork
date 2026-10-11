package com.brushwork.paint.qa18

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.placement.FrameStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (request items 8 and 9, QA: the Frame divider's two-line status left a lone "it" on
 * its second line). The status is v1.7's text ([FrameStripItems.THEN_DIVIDE], which v1.8 never
 * rewords), so its layout changes instead: two even lines. Frame divider picked by a finger with
 * no frame layer, on a raster and on a vector layer (the VECTOR cell), at 392 and 360 dp: the
 * status shows whole on two lines, the second at least two words and not under half the first.
 * Rendered: `polish-chrome-frame-hint-<width>[-vector].png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishchromeframehintsandbox"])
class Qa18PolishChromeFrameHintTest {

    @Test
    fun theFrameDividersStatusReadsInTwoEvenLines() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        Qa18PolishChromeKit.atEachWidth(h, "Frame divider without a frame layer") { dp ->
            for (vector in listOf(false, true)) status(h, dp, vector)
        }
        dog.interrupt()
        h.finish()
    }

    private fun status(h: ChromeHarness, dp: Int, vector: Boolean) {
        val s = h.editor(Qa18MenusShots.doc(vector))
        assertEquals("$dp dp wide", dp.toFloat(), s.widthDp, 1f)
        Qa18MenusShots.pick(s, ToolId.FRAME_DIVIDER)
        val what = "the Frame divider's status at $dp dp${if (vector) " (vector)" else ""}"
        val strip = s.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("$what: no strip")
        val n = Qa18PolishChromeKit.textNode(s, FrameStripItems.THEN_DIVIDE, inside = strip) ?: throw AssertionError("$what: no status on the strip")
        val r = Qa18PolishChromeKit.layout(n) ?: throw AssertionError("$what: no text layout")
        val lines = Qa18PolishChromeKit.lines(r)
        System.err.println("[polish-chrome] $what: $lines")
        assertNull("$what: whole", Qa18PolishChromeKit.cut(n))
        assertEquals("$what: two lines: $lines", 2, lines.size)
        assertTrue("$what: no lone word on the second line: $lines", lines[1].split(' ').filter { it.isNotBlank() }.size >= 2)
        val w0 = r.getLineRight(0) - r.getLineLeft(0)
        val w1 = r.getLineRight(1) - r.getLineLeft(1)
        assertTrue("$what: even lines ($w0 px, $w1 px): $lines", minOf(w0, w1) >= maxOf(w0, w1) / 2f)
        Qa18PolishChromeKit.shot(s, "frame-hint-$dp${if (vector) "-vector" else ""}", heightDp = 160f)
        Smoke.assertQuiet(s.c, what)
    }
}
