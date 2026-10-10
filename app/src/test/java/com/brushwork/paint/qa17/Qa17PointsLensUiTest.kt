package com.brushwork.paint.qa17

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.qa17.PointsQa.Companion.composite
import com.brushwork.paint.qa17.PointsQa.Companion.save
import com.brushwork.paint.qa17.PointsQa.Companion.steps
import com.brushwork.paint.qa17.PointsQa.Companion.thickness
import com.brushwork.paint.qa17.PointsQa.Companion.window
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.7 final QA, item 5 exactly as the user put it: "a path with 3 points and change the end and
 * beginning points of a path to 0% thickness" showed nothing until the path had 5 points. On
 * the user's phone (392 dp), with fingers: three taps, each end tapped and "0" typed as its
 * thickness. The canvas shows the lens AT ONCE (the pending path, before ✓), thin at the ends and
 * as thick in the middle as the spline is there (50 % at 3 points, 75 % at 4, 100 % at 5 or with
 * a sharp middle point), with "Current brush" and with "Plain line"; ✓ keeps what was shown, and
 * each typed thickness is one in-tool step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.pointslenssandbox"])
class Qa17PointsLensUiTest {

    @Test
    fun aPathWithBothEndsAtZeroShowsItsLensAtOnce() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        val qa = PointsQa(h)
        h.section("3 points, Current brush") { qa.lens(3, CurveStroke.BRUSH, sharpMiddle = false, shot = "lens-3-brush") }
        h.section("3 points, Plain line") { qa.lens(3, CurveStroke.PLAIN, sharpMiddle = false, shot = "lens-3-plain") }
        h.section("4 points, Current brush") { qa.lens(4, CurveStroke.BRUSH, sharpMiddle = false, shot = "lens-4-brush") }
        h.section("5 points, Plain line") { qa.lens(5, CurveStroke.PLAIN, sharpMiddle = false, shot = "lens-5-plain") }
        h.section("3 points, a sharp middle, Current brush") { qa.lens(3, CurveStroke.BRUSH, sharpMiddle = true, shot = "lens-3-sharp") }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val Y = 150f
        const val SIZE = 24f
        const val INK = 0xFF2244CC.toInt()
    }

    /**
     * The thickness factor the spline has at x (the control points evenly spaced on y = [Y]
     * from 80 to 320, so x runs evenly with the parameter): clamped B-splines of order
     * min(4, n), the ends at 0 and the inner points at 1.
     */
    private fun expected(n: Int, sharpMiddle: Boolean, x: Float): Float {
        val t = (x - 80f) / 240f
        return when {
            sharpMiddle -> 1f - abs(x - 200f) / 120f
            n == 3 -> 2f * t * (1f - t)
            n == 4 -> 3f * t * (1f - t)
            else -> Float.NaN
        }
    }

    private fun PointsQa.lens(n: Int, kind: CurveStroke, sharpMiddle: Boolean, shot: String) {
        editor()
        c.color = INK
        c.brush = BrushLibrary.defaultBrush.copy(size = SIZE, opacity = 1f)
        val tool = tool("Path")
        if (kind == CurveStroke.PLAIN) {
            press(CurveLabels17.STROKE_KIND)
            click(CurveStroke.PLAIN.label, exact = true)
        }
        assertEquals("the stroke kind", kind, tool.settings.stroke)
        val xs = List(n) { 80f + 240f * it / (n - 1) }
        for (x in xs) tap(x, Y)
        assertEquals("$n points by $n taps", n, tool.pointCount)
        val width = if (kind == CurveStroke.PLAIN) tool.lineWidth else SIZE
        assertEquals("the line is the brush size", SIZE, width, 0.01f)

        // The end, then the beginning: tapped, "0" typed as the point's thickness.
        val before = steps(tool)
        for (i in listOf(n - 1, 0)) {
            tap(xs[i], Y)
            assertEquals("point $i tapped", i, tool.selectedPoint)
            press("Type the point thickness")
            SmokeUi.typeAndDone("Thickness", "0")
        }
        assertEquals("each typed thickness is one in-tool step", before + 2, steps(tool))
        assertEquals(List(n) { if (it == 0 || it == n - 1) 0f else 1f }, tool.spline!!.points.map { it.width })
        if (sharpMiddle) {
            tap(xs[n / 2], Y)
            assertEquals(n / 2, tool.selectedPoint)
            press("Sharp corner")
            assertTrue("the middle point is sharp", tool.spline!!.points[n / 2].sharp)
        }
        idle()
        assertTrue("still pending: the lens shows before ✓", tool.hasPendingWork)

        val live = composite(c)
        save(live, "$shot-doc")
        if (n == 3 && !sharpMiddle) save(window(s), "$shot-screen")
        val probes = listOf(84f, 110f, 140f, 170f, 200f, 230f, 260f, 290f, 316f)
        val got = probes.map { thickness(live, it.toInt(), INK, (Y - 40).toInt(), (Y + 40).toInt()) }
        println("QA17 lens n=$n $kind sharp=$sharpMiddle order=${tool.spline!!.order}: " + probes.zip(got).joinToString { (x, t) -> "x=${x.toInt()}: %.1f".format(t) })
        val mid = got[probes.indexOf(200f)]
        val peak = when {
            sharpMiddle -> 1f
            n == 3 -> 0.5f
            n == 4 -> 0.75f
            else -> 1f
        }
        assertTrue("$n points: a visible lens at once, ${peak * 100} % of $SIZE px in the middle, got $mid", abs(mid - SIZE * peak) <= max(2f, SIZE * peak * 0.15f))
        assertTrue("thin at the start (${got.first()})", got.first() <= 3f)
        assertTrue("thin at the end (${got.last()})", got.last() <= 3f)
        for ((i, x) in probes.withIndex()) {
            val e = expected(n, sharpMiddle, x)
            if (e.isNaN()) continue
            assertTrue("x=$x: ${e * SIZE} px expected, got ${got[i]}", abs(got[i] - e * SIZE) <= max(2.5f, e * SIZE * 0.2f))
        }
        // Thicker towards the middle from both sides.
        for (i in 1..probes.indexOf(200f)) assertTrue("rises towards the middle at ${probes[i]}: $got", got[i] >= got[i - 1] - 0.75f)
        for (i in probes.indexOf(200f) until probes.lastIndex) assertTrue("falls after the middle at ${probes[i]}: $got", got[i + 1] <= got[i] + 0.75f)

        // The in-tool undo takes back the beginning's 0 % (the start is full again); redo brings it back.
        if (!sharpMiddle) {
            press("Undo last point", 44f)
            idle()
            assertEquals(1f, tool.spline!!.points[0].width, 0f)
            val undone = composite(c)
            assertTrue("undone: the start is drawn again", thickness(undone, 84, INK, (Y - 40).toInt(), (Y + 40).toInt()) > SIZE * 0.5f)
            press("Redo point", 44f)
            idle()
            assertEquals(0f, tool.spline!!.points[0].width, 0f)
        }

        // ✓ keeps the lens that was shown.
        apply("Apply path edit")
        val done = composite(c)
        val after = probes.map { thickness(done, it.toInt(), INK, (Y - 40).toInt(), (Y + 40).toInt()) }
        for (i in probes.indices) assertTrue("applied as shown at x=${probes[i]}: ${got[i]} -> ${after[i]}", abs(after[i] - got[i]) <= 1.5f)
        Smoke.assertQuiet(c, "lens $n $kind")
        (c.currentTool as? CurveTool)?.discard()
    }
}
