package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.placement.TransformStripItems
import com.brushwork.paint.ui.tools.strip.StripLayout
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
 * v1.8 polish (request items 8 and 9, QA: "Choose interpolation" showed only "Smooth ▾"): Transform
 * with a lifted picture, its More row opened by a finger on the toggle, at 392 and 360 dp, in a
 * box mode and in Free deform. "Choose interpolation" now shows the caption
 * [TransformLabels18.INTERPOLATION_CAPTION] over the current interpolation and ▾, all whole in its
 * 80 dp; the More row keeps its lines; a finger on it drops the menu and a pick shows under the
 * caption. Rendered: `polish-chrome-interpolation-<box|mesh>-<width>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishchromeinterpolationsandbox"])
class Qa18PolishChromeInterpolationTest {

    @Test
    fun chooseInterpolationSaysWhatItChooses() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 600_000)
        val h = ChromeHarness()
        Qa18PolishChromeKit.atEachWidth(h, "Transform with a lifted picture") { dp -> transform(h, dp) }
        dog.interrupt()
        h.finish()
    }

    private fun transform(h: ChromeHarness, dp: Int) {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c ->
            check(c.editWholeLayer(c.doc.layers[1], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        }
        assertEquals("$dp dp wide", dp.toFloat(), s.widthDp, 1f)
        Qa18MenusShots.pick(s, ToolId.TRANSFORM)
        val t = s.c.currentTool as TransformTool
        assertTrue("the picture is lifted", Smoke.pumpUntil(10_000) { SmokeUi.settle(); t.transformState != null })

        // A box mode: the hint and the eight items, three lines as before.
        val box = "Transform (box) at $dp dp"
        val (_, lines) = Qa18MenusShots.openMore(s, box)
        assertEquals("$box: the More row keeps its lines", StripLayout.MAX_MORE_LINES, lines)
        assertButton(s, box, TransformTool.Interpolation.SMOOTH)
        Qa18PolishChromeKit.shot(s, "interpolation-box-$dp")
        // A finger on it drops the menu over the open row; a pick shows under the caption.
        val before = s.c.undoManager.undoCount
        Finger.tap(s, TransformStripItems.INTERPOLATION_LABEL)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("$box: the interpolation menu drops", 2, SmokeUi.windows().size)
        SmokeUi.tap(TransformTool.Interpolation.NEAREST.label, exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("$box: the pick is made", TransformTool.Interpolation.NEAREST, t.interpolation)
        assertNotNull("$box: the anchored menu keeps the More row open", s.tagged(V18Tags.STRIP_MORE_ROW))
        assertButton(s, "$box after the pick", TransformTool.Interpolation.NEAREST)
        assertEquals("$box: the pick is no step", before, s.c.undoManager.undoCount)
        Qa18MenusShots.closeMore(s, box)

        // Free deform: the same button on its More row.
        Finger.tap(s, TransformTool.Mode.FREE.label)
        Smoke.pump(300); SmokeUi.settle()
        SmokeUi.tap(TransformTool.Mode.MESH.label, exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals(TransformTool.Mode.MESH, t.mode)
        val mesh = "Transform (Free deform) at $dp dp"
        val (_, meshLines) = Qa18MenusShots.openMore(s, mesh)
        // Its four items (Smooth mesh, Select all points, Delete, the interpolation): one line on
        // the user's phone, two at 360 dp, as the 80 dp button always took.
        assertEquals("$mesh: the More row keeps its lines", if (dp >= 392) 1 else 2, meshLines)
        assertButton(s, mesh, TransformTool.Interpolation.NEAREST)
        Qa18PolishChromeKit.shot(s, "interpolation-mesh-$dp")
        Qa18MenusShots.closeMore(s, mesh)
        Smoke.assertQuiet(s.c, box)
    }

    /** "Choose interpolation" on the open More row: 80 dp, the caption over [now] and ▾, every text whole. */
    private fun assertButton(s: ChromeScreen, what: String, now: TransformTool.Interpolation) {
        val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("$what: no More row")
        val r = Finger.control(s, TransformStripItems.INTERPOLATION_LABEL, exact = true) ?: throw AssertionError("$what: no \"${TransformStripItems.INTERPOLATION_LABEL}\"")
        assertTrue("$what: the button is on the More row: $r in $more", more.contains(r.center))
        assertEquals("$what: the button keeps its width", TransformStripItems.INTERPOLATION_DP, r.width, 0.5f)
        val caption = Qa18PolishChromeKit.textNode(s, TransformLabels18.INTERPOLATION_CAPTION, inside = r)
            ?: throw AssertionError("$what: no caption \"${TransformLabels18.INTERPOLATION_CAPTION}\" on the button")
        val value = Qa18PolishChromeKit.textNode(s, now.label, inside = r) ?: throw AssertionError("$what: no \"${now.label}\" on the button")
        val c = s.dp(caption.boundsInWindow)
        val v = s.dp(value.boundsInWindow)
        assertTrue("$what: the caption over the value: $c, $v", c.bottom <= v.top + 1f)
        for ((name, n) in listOf("caption" to caption, "value" to value)) {
            val b = s.dp(n.boundsInWindow)
            assertTrue("$what: the $name inside the button: $b in $r", b.left >= r.left - 0.5f && b.right <= r.right + 0.5f && b.top >= r.top - 0.5f && b.bottom <= r.bottom + 0.5f)
            assertNull("$what: the $name is whole", Qa18PolishChromeKit.cut(n))
        }
        // Its handle (the screen reader's name) is unchanged.
        assertNotNull("$what: still described \"${TransformStripItems.INTERPOLATION_LABEL}\"", Fingers18.described(s, TransformStripItems.INTERPOLATION_LABEL))
    }
}
