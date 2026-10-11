package com.brushwork.paint.qa18

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 final QA (items 6 and 7) on a narrow phone (360 dp), by finger: the Curve strip's
 * "Curves" quick start, its chip and the curve sheet's sliders; the Shape strip's "Cog", every cog
 * slider in "Shape settings" and the More row's "Turn into path" all reach the finger whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.curvesnarrowsandbox"])
class Qa18CurvesNarrow360UiTest {

    /** The slider under [label] lies wholly across the phone's width (a sheet must not run off it). */
    private fun across(q: Qa18CurvesUi, label: String) {
        val e = q.visible { q.sliderUnder(label) }
        val l = e.node.positionInWindow.x
        val r = l + e.node.size.width
        assertTrue("\"$label\": its slider across the phone ($l..$r of ${e.window.width} px)", l >= 0f && r <= e.window.width + 0.5f)
    }

    private fun curve(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Curve")
        val tool = q.c.currentTool as CurveTool
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.ROSE)
        val start = tool.generator ?: throw AssertionError("no rose placed")
        assertEquals(CurveGenKind.ROSE, start.kind)
        StripTestKit.closeMoreRow()
        q.tap(CurveGenLabels18.CHANGE)
        for (label in listOf(CurveGenLabels18.PETALS, CurveGenLabels18.PETAL_DIVISOR, CurveGenLabels18.POINTS_PER_TURN)) across(q, label)
        q.dragSlider(CurveGenLabels18.PETALS, 0.2f, downPast = 0.4f)
        val changed = tool.generator ?: throw AssertionError("the rose lost its settings")
        assertNotEquals("Petals changed the rose", start, changed)
        Finger.back()
        assertFalse("the sheet closed", has(CurveGenLabels18.PETALS, exact = true))
        q.applyEdit("Apply curve edit")
        val rose = q.c.activeLayer.vector!!.objects.single() as VPath
        assertEquals("✓ keeps the rose's settings", changed, rose.generator)
        Smoke.assertQuiet(q.c, "curve at 360 dp")
    }

    private fun cog(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Shape")
        val tool = q.c.currentTool as ShapeTool
        q.tap("Shape type")
        q.tap(ShapeType.COG.label)
        assertEquals(ShapeType.COG, tool.settings.type)
        q.tap("Shape settings")
        for (label in listOf(CogLabels18.TEETH, CogLabels18.TOOTH_DEPTH, CogLabels18.TOOTH_WIDTH, CogLabels18.TOOTH_TAPER, CogLabels18.HOLE_SIZE)) across(q, label)
        q.dragSlider(CogLabels18.HOLE_SIZE, 0.3f)
        assertTrue("a hole: ${tool.settings.cogHole}", tool.settings.cogHole > 0.1f)
        Finger.back()
        q.strokeDoc(Vec2(110f, 60f), Vec2(155f, 105f), Vec2(200f, 150f), Vec2(245f, 195f), Vec2(290f, 240f))
        assertTrue("an open cog", tool.ensurePending())
        val steps = q.steps()
        q.tap(PointLabels.TO_PATH)
        q.settleRenders("Turn into path")
        assertEquals("the cog, then Turn into path", steps + 2, q.steps())
        val p = q.c.activeLayer.vector!!.objects.single() as? VPath ?: throw AssertionError("not a path: ${q.c.activeLayer.vector!!.objects}")
        assertEquals("one compound path", 2, p.subpaths.size)
        assertEquals(ToolId.PATH, q.c.activeToolId)
        Smoke.assertQuiet(q.c, "cog at 360 dp")
    }

    @Test
    fun curvesAndCogReachTheFingerOnA360DpPhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 360f)
        h.section("Curves ▸ Rose, its chip and sheet at 360 dp") { curve(q) }
        h.section("Cog, its sliders and Turn into path at 360 dp") { cog(q) }
        dog.interrupt()
        h.finish()
    }
}
