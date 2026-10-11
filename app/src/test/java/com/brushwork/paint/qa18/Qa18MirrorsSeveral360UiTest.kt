package com.brushwork.paint.qa18

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (verifier), item 1 (design §3.2): "Select several" on a 360 dp phone, the
 * narrowest the app supports, every step by a finger. In Curve, Shape's Points mode and Free
 * deform, with a point picked (the pill's full first row: fold, X, Y, "Increments", trash):
 * the cell ends the row, finger-sized, nothing on the row clipped (X, Y and "Increments" shown
 * whole); "Fold the X / Y strip": still at the row's end; the cell works folded (on, then off);
 * "Unfold the X / Y strip" brings the full row back.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.mirrorsseveral360sandbox"])
class Qa18MirrorsSeveral360UiTest {

    private lateinit var s: ChromeScreen
    private val c get() = s.c

    private fun attach(screen: ChromeScreen) {
        s = screen
        settle()
        assertEquals("a 360 dp phone", 360f, s.widthDp, 1f)
    }

    private fun tapCell(t: PointEditor, on: Boolean, what: String) {
        val r = requireNotNull(s.tagged(V18Tags.PILL_SEVERAL)) { "$what: no \"Select several\" cell; shown: ${SmokeUi.shown().take(80)}" }
        Finger.tapAt(s, r.center.x, r.center.y)
        assertEquals("$what: Select several", on, t.selectSeveral)
        assertEquals("$what: the cell's state", if (on) "On" else "Off", PillTestKit.severalState())
    }

    private fun run(t: PointEditor, what: String) {
        Qa18MirrorsKit.severalAtRowEnd(s, "360 dp, $what, unfolded", folded = false)
        tapCell(t, on = true, what = "360 dp, $what, unfolded")
        tapCell(t, on = false, what = "360 dp, $what, unfolded")
        Finger.tap(s, "Fold the X / Y strip")
        settle()
        Qa18MirrorsKit.severalAtRowEnd(s, "360 dp, $what, folded", folded = true)
        tapCell(t, on = true, what = "360 dp, $what, folded")
        tapCell(t, on = false, what = "360 dp, $what, folded")
        Finger.tap(s, "Unfold the X / Y strip")
        settle()
        Qa18MirrorsKit.severalAtRowEnd(s, "360 dp, $what, unfolded again", folded = false)
    }

    private fun curve(h: ChromeHarness) {
        attach(h.editor())
        QaCurves.tool(s, "Curve")
        val tool = c.currentTool as CurveTool
        QaCurves.snapOff(c)
        for (p in THREE) QaCurves.tap(s, p)
        assertEquals("three points", 3, tool.pointCount)
        run(tool, "Curve")
        tool.discard()
        settle()
        Smoke.assertQuiet(c, "Curve")
    }

    private fun shapePoints(h: ChromeHarness) {
        attach(h.editor())
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        QaCurves.drag(s, Vec2(60f, 70f), Vec2(170f, 150f))
        assertNotNull("a pending rectangle", tool.box)
        Qa16Ui(s).reach("Points", 32f)
        settle()
        Finger.tap(s, "Points")
        assertTrue(tool.pointsMode)
        QaCurves.tap(s, tool.pointAt(0))
        run(tool, "Shape points")
        tool.discard()
        settle()
        Smoke.assertQuiet(c, "Shape")
    }

    private fun freeDeform(h: ChromeHarness) {
        attach(h.editor())
        c.selectLayer(0)
        settle()
        QaCurves.tool(s, "Transform")
        val tool = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); tool.transformState != null })
        settle()
        val ui = Qa16Ui(s)
        ui.reach(TransformTool.Mode.FREE.label, 32f)
        Finger.tap(s, TransformTool.Mode.FREE.label)
        settle()
        ui.reach(TransformLabels17.FREE_DEFORM, 32f)
        SmokeUi.tap(TransformLabels17.FREE_DEFORM, exact = true)
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); tool.isMeshShown })
        settle()
        QaCurves.tap(s, tool.pointAt(tool.meshColumns + 2))
        run(tool, "Free deform")
        tool.discard()
        c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(c, "Free deform")
    }

    @Test
    fun selectSeveralEndsThePillRowAt360Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("Curve") { curve(h) }
        h.section("Shape points") { shapePoints(h) }
        h.section("Free deform") { freeDeform(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        val THREE = listOf(Vec2(80f, 200f), Vec2(160f, 110f), Vec2(240f, 200f))
    }
}
