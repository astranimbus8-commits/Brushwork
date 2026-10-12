package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.placement.TransformStripItems
import com.brushwork.paint.vector.draw.VectorEraseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (request items 8 and 9, QA: icon-only strip items had no long-press bubble), on
 * the user's phone (392 dp) and on a 360 dp phone: the strips the QA named, a finger held on one
 * icon of each: Transform's More row ("Rotate 90° clockwise"), a polygon lasso's "Discard polygon"
 * (row 1) and "Deselect" (the More row, the selection bar hidden by the pending polygon), the open
 * text's direction icon, the Masks' overlay icon, the vector eraser's "Partial". The bubble says
 * the name under the icon and nothing else changes ([Qa18PolishChromeBubbles.press]). Rendered
 * with the bubble showing: `polish-chrome-bubble-<strip>-<width>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishchromelongpressshotssandbox"])
class Qa18PolishChromeLongPressShotsTest {

    @Test
    fun theNamedStripsShowTheirBubbleAt392And360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 900_000)
        val h = ChromeHarness()
        Qa18PolishChromeKit.atEachWidth(h, "the named strips") { dp ->
            transform(h, dp)
            lasso(h, dp)
            text(h, dp)
            masks(h, dp)
            eraser(h, dp)
        }
        dog.interrupt()
        h.finish()
    }

    /** The control named [name] on the strip (row 1, else its More row, opened by its toggle). */
    private fun control(s: ChromeScreen, name: String, where: String): Clickables.Item {
        fun find(): Clickables.Item? {
            val areas = listOfNotNull(s.tagged(ChromeTags.OPTIONS_STRIP), s.tagged(V18Tags.STRIP_MORE_ROW))
            return areas.firstNotNullOfOrNull { a -> Qa18PolishChromeBubbles.iconOnlyIn(s, a).firstOrNull { Qa18PolishChromeBubbles.name(it) == name } }
        }
        find()?.let { return it }
        if (Fingers18.state(s, StripLabels18.MORE_TOGGLE) == StripLabels18.COLLAPSED) {
            Finger.tap(s, StripLabels18.MORE_TOGGLE)
            Smoke.pump(300); SmokeUi.settle()
        }
        return find() ?: throw AssertionError("$where: no icon-only \"$name\" on the strip")
    }

    /** Holds a finger on [name]: its bubble, nothing else; rendered as `bubble-[shot]`. */
    private fun hold(s: ChromeScreen, name: String, where: String, shot: String) {
        val item = control(s, name, where)
        val held = Qa18PolishChromeBubbles.press(s, item, name, where)
        assertTrue(held.problems.joinToString("\n"), held.problems.isEmpty())
        Qa18PolishChromeKit.shot(s, "bubble-$shot", heightDp = 240f)
    }

    private fun transform(h: ChromeHarness, dp: Int) {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c ->
            check(c.editWholeLayer(c.doc.layers[1], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        }
        assertEquals("$dp dp wide", dp.toFloat(), s.widthDp, 1f)
        Qa18MenusShots.pick(s, ToolId.TRANSFORM)
        val t = s.c.currentTool as TransformTool
        assertTrue("the picture is lifted", Smoke.pumpUntil(10_000) { SmokeUi.settle(); t.transformState != null })
        hold(s, TransformStripItems.ROTATE_CW_LABEL, "Transform at $dp dp", "transform-$dp")
        Smoke.assertQuiet(s.c, "Transform")
    }

    private fun lasso(h: ChromeHarness, dp: Int) {
        val s = h.editor(Qa18MenusShots.doc(vector = false)) { c -> c.selectAll() }
        Qa18MenusShots.pick(s, ToolId.LASSO)
        val t = s.c.currentTool as LassoTool
        Finger.tap(s, "Freehand lasso")
        Smoke.pump(300); SmokeUi.settle()
        SmokeUi.tap("Polygon lasso", exact = true)
        Smoke.pump(300); SmokeUi.settle()
        assertEquals(LassoKind.POLYGON, t.kind)
        Fingers18.tap(s, 120f, 90f)
        Fingers18.tap(s, 260f, 110f)
        assertEquals("two corners", 2, t.vertexCount)
        val where = "Polygon lasso, two corners, at $dp dp"
        hold(s, "Discard polygon", where, "lasso-discard-$dp")
        hold(s, "Deselect", where, "lasso-deselect-$dp")
        assertNotNull("the selection is kept", s.c.selection)
        Smoke.assertQuiet(s.c, "Lasso")
    }

    private fun text(h: ChromeHarness, dp: Int) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        Qa18MenusShots.pick(s, ToolId.TEXT)
        val t = s.c.currentTool as TextTool
        t.startTextAt(200f, 150f)
        t.setText("Strip")
        t.confirmEditor()
        Smoke.pump(300); SmokeUi.settle()
        assertNotNull("an open text", t.item)
        hold(s, "Horizontal text (tap for vertical)", "Text, a text open, at $dp dp", "text-$dp")
        Smoke.assertQuiet(s.c, "Text")
    }

    private fun masks(h: ChromeHarness, dp: Int) {
        val s = h.editor(Qa18MenusShots.doc(vector = false))
        Qa18MenusShots.pick(s, ToolId.MASK)
        val t = s.c.currentTool as MaskTool
        t.arm(MaskTool.Kind.LINEAR)
        FrameFixtures.drag(s.c, 120f, 150f, 280f, 150f)
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("an adjustment layer", s.c.activeLayer.isAdjustmentLayer)
        val name = Clickables.onScreen(s).flatMap { it.own }.firstOrNull { it.startsWith("Mask overlay") }
            ?: run {
                Finger.tap(s, StripLabels18.MORE_TOGGLE)
                Smoke.pump(300); SmokeUi.settle()
                Clickables.onScreen(s).flatMap { it.own }.firstOrNull { it.startsWith("Mask overlay") }
            }
            ?: throw AssertionError("Masks at $dp dp: no overlay icon")
        hold(s, name, "Masks, a linear part, at $dp dp", "masks-$dp")
        Smoke.assertQuiet(s.c, "Masks")
    }

    private fun eraser(h: ChromeHarness, dp: Int) {
        val s = h.editor(Qa18MenusShots.doc(vector = true))
        Qa18MenusShots.pick(s, ToolId.ERASER)
        hold(s, VectorEraseMode.PARTIAL.label, "Eraser on a vector layer at $dp dp", "eraser-$dp")
        Smoke.assertQuiet(s.c, "Eraser")
    }
}
