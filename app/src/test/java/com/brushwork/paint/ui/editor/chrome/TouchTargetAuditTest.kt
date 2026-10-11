package com.brushwork.paint.ui.editor.chrome

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.V18Tags
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 §3.7.11: every clickable of the main screen's chrome is at least 40 × 40 dp (fingers on a
 * 392 dp phone): the top row, the slider rows, the bottom bar, the ✓ / ✕, the tool menu, the More
 * menu, a minimized panel's pill — at the user's phone size and on a 360 dp phone
 * ([TouchTargetAuditNarrowTest]). The layer window's own targets are area F's
 * (LayerWindowIbisLayoutTest); here only its host's. The options strip's content is each tool's
 * own (see [TouchTargets]); v1.8 adds the strip's More-row toggle (44 dp) and the More row,
 * which hangs under the strip panel (design §3.9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.touchtargetsandbox"])
class TouchTargetAuditTest {
    @Test
    fun everyClickableIsFingerSized() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("main screen, tool menu, More menu, ✓ / ✕, pill") { TouchTargets.audit(h) }
        dog.interrupt()
        h.finish()
    }
}

/** The same audit on a 360 × 760 dp phone (pitch 44, circles 36, bottom slots 51 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.touchtargetnarrowsandbox"])
class TouchTargetAuditNarrowTest {
    @Test
    fun everyClickableIsFingerSizedAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("main screen, tool menu, More menu, ✓ / ✕, pill") { TouchTargets.audit(h) }
        dog.interrupt()
        h.finish()
    }
}

internal object TouchTargets {
    private const val MIN = 40f

    private lateinit var s: ChromeScreen

    /**
     * Every clickable's own size (a tool-menu cell half scrolled out is still its full cell),
     * except the tools' own controls inside the options strip: they belong to each tool's area
     * (Material chips 32 dp tall, whose touch area Compose extends to its 48 dp minimum touch
     * target); the strip panel around them has no target of its own.
     */
    private fun check(where: String, items: List<Clickables.Item>) {
        val strip = s.tagged(ChromeTags.OPTIONS_STRIP)
        val small = items
            .filter { strip == null || !strip.contains(it.bounds.center) }
            .filter { it.width < MIN - 0.5f || it.height < MIN - 0.5f }
        assertTrue("$where: clickables under $MIN dp: ${small.map { "${it.labels} ${it.width} × ${it.height}" }}", small.isEmpty())
    }

    /** v1.8 (design §3.9): the strip's More-row toggle is 44 dp by choice, above the 40 dp minimum. */
    private const val TOGGLE_MIN = 44f

    /**
     * v1.8 (design §3.9, §4.9): the active tool's More row, when its strip has one. The toggle sits
     * on row 1, inside the strip panel that [check] leaves to the tools, so it is checked here at
     * [TOGGLE_MIN]; the toggle opens the More row, whose node (tag `V18Tags.STRIP_MORE_ROW`) hangs
     * under the panel, outside it, and every clickable on it is checked at [MIN]; the row is then
     * closed again (an open row swallows the next canvas touch).
     */
    private fun moreRow(where: String) {
        val toggle = StripTestKit.toggle() ?: return
        val t = toggle.size
        assertTrue(
            "$where: the More-row toggle is ${t.width / s.density} × ${t.height / s.density} dp, under $TOGGLE_MIN",
            t.width / s.density >= TOGGLE_MIN - 0.5f && t.height / s.density >= TOGGLE_MIN - 0.5f,
        )
        val opened = StripTestKit.openMoreRow()
        val row = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("$where: the toggle opened no More row")
        val small = Clickables.onScreen(s, inside = row).filter { it.width < MIN - 0.5f || it.height < MIN - 0.5f }
        assertTrue("$where, the More row: clickables under $MIN dp: ${small.map { "${it.labels} ${it.width} × ${it.height}" }}", small.isEmpty())
        if (opened) StripTestKit.closeMoreRow()
    }

    fun audit(h: ChromeHarness) {
        s = h.editor()
        val main = Clickables.onScreen(s)
        assertTrue(main.size >= 20)
        check("main screen", main)
        moreRow("main screen")
        // The tool menu (all 30 cells, scrolled or not).
        click("Tools (current: Brush)")
        check("tool menu", Clickables.onScreen(s))
        click("Tools (current: Brush)")
        // The More menu.
        click("More options")
        check("More menu", Clickables.onScreen(s).filter { it.window !== s.activity.window.decorView })
        click("Fit to screen", exact = true)
        // ✓ / ✕ of a pending shape, and a minimized panel's pill.
        s.c.selectTool(ToolId.SHAPE)
        Smoke.pump(60)
        assertTrue((s.c.currentTool as ShapeTool).ensurePending())
        settle()
        moreRow("Shape")
        click("Open color picker")
        click("Minimize", exact = true)
        check("✓ / ✕ and the pill", Clickables.onScreen(s))
        click("Close Color", exact = true)
        click("Discard shape edit")
        // The layer window's host: the bottom bar's open state.
        click("Open layers")
        val lw = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        check("chrome around the layer window", Clickables.onScreen(s, outside = listOf(lw)))
        click("Close layers", exact = true)
        s.c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(s.c, "touch targets")
    }
}
