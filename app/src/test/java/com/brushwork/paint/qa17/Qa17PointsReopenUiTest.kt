package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa17.PointsQa.Companion.composite
import com.brushwork.paint.qa17.PointsQa.Companion.inked
import com.brushwork.paint.qa17.PointsQa.Companion.save
import com.brushwork.paint.qa17.PointsQa.Companion.steps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.7 final QA (verifier), item 19 and item 1 on a path the user made EARLIER, on the user's phone
 * (392 dp), with fingers: a Path applied on a vector layer, saved and reopened from disk, then
 * tapped to edit it again. Its first point tapped, two taps on empty canvas grow it from its start
 * (the user's "when selecting the first point, it seems to put a new point after the first one").
 * Then "Select several", two more points tapped and the three dragged together by the one that
 * sits on the gizmo's corner. ✓ is ONE app step that keeps the path's id and its exact Bézier form
 * (I9); Undo gives back the very path that was saved, pixel for pixel; Redo the edited one.
 * Then item 7 in the Polyline tool: "Fill only" on an open polyline, applied, reopened by a tap
 * inside its fill (which shows "Fill only"), turned to "Stroke and fill" as one app step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.pointsreopensandbox"])
class Qa17PointsReopenUiTest {

    @Test
    fun aSavedPathGrowsFromItsStartAndItsPointsMoveTogether() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        val qa = PointsQa(h)
        h.section("a saved, reopened Path: prepend and a group drag, one app step") { qa.reopened() }
        h.section("item 7 on a Polyline: Fill only, reopened by a tap on its fill, then Both") { qa.polylineFill() }
        dog.interrupt()
        h.finish()
    }

    private fun xs(t: CurveTool) = t.spline!!.points.map { it.x.roundToInt() to it.y.roundToInt() }

    private fun differing(a: Bitmap, b: Bitmap): Int {
        var bad = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if (listOf(24, 16, 8, 0).any { abs((p shr it and 0xFF) - (q shr it and 0xFF)) > 2 }) bad++
        }
        return bad
    }

    private fun PointsQa.reopened() {
        // A Path made and applied earlier, saved, and the project opened again.
        editor(vector = true)
        c.brush = BrushLibrary.defaultBrush.copy(size = 8f, opacity = 1f)
        tool("Path")
        tap(80f, 200f); tap(200f, 100f); tap(320f, 200f)
        apply("Apply path")
        val app = s.activity.application
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        val loaded = runBlocking { repo.save(c.doc, null); repo.load(c.doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        loaded.activeLayerIndex = loaded.layers.lastIndex
        editor(opened = loaded)
        c.brush = BrushLibrary.defaultBrush.copy(size = 8f, opacity = 1f)
        assertTrue("reopened and drawn", Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); !c.vectors.isRendering })
        val layer = c.activeLayer
        val saved = layer.vector!!.objects.single() as VPath
        val savedPixels = composite(c)
        val tool = tool("Path")

        // A tap on the path opens it again; its first point tapped.
        tap(200f, 150f)
        assertTrue("the saved path is open for editing", tool.isReopened)
        assertEquals(listOf(80 to 200, 200 to 100, 320 to 200), xs(tool))
        tap(80f, 200f)
        assertEquals("the first point, tapped", 0, tool.selectedPoint)
        val n0 = steps(tool)
        // Two taps on empty canvas: each new point goes BEFORE the first one and stays selected.
        tap(40f, 260f)
        assertEquals("grown from its start", listOf(40 to 260, 80 to 200, 200 to 100, 320 to 200), xs(tool))
        assertEquals(0, tool.selectedPoint)
        tap(20f, 150f)
        assertEquals(listOf(20 to 150, 40 to 260, 80 to 200, 200 to 100, 320 to 200), xs(tool))
        assertEquals(0, tool.selectedPoint)
        assertEquals("one step per point", n0 + 2, steps(tool))
        assertTrue("the canvas shows the longer path before ✓", Smoke.pumpUntil(PointsQa.WAIT_MS) {
            settle(1); inked(composite(c), 15, 145, 26, 156) > 0
        })

        // "Select several": the new start plus two more points, dragged together by the last one,
        // which sits on the gizmo's SE corner (a point on a handle wins the touch).
        press(PointLabels.SELECT_SEVERAL, 44f)
        tap(200f, 100f)
        tap(320f, 200f)
        assertEquals(listOf(0, 3, 4), tool.pointSelection.indices)
        QaCurves.drag(s, Vec2(320f, 200f), Vec2(10f, 30f))
        assertEquals("the three moved together, the others stayed",
            listOf(30 to 180, 40 to 260, 80 to 200, 210 to 130, 330 to 230), xs(tool))
        assertEquals("the group drag is one step", n0 + 3, steps(tool))
        val edited = tool.spline!!

        // ✓: ONE app step, the same object id, its Bézier form exactly the spline's (I9).
        val before = c.undoManager.undoCount
        apply("Apply path")
        assertEquals("one app step for the whole edit", before + 1, c.undoManager.undoCount)
        val made = layer.vector!!.objects.single() as VPath
        assertEquals("the same object", saved.id, made.id)
        assertEquals(edited.points, made.spline!!.points)
        assertEquals("I9", SplineBezier.toSubpath(made.spline!!), made.subpaths.single())
        assertTrue(Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); !c.vectors.isRendering })
        val editedPixels = composite(c)
        save(editedPixels, "reopen-prepend-doc")
        assertTrue("drawn from the new start", inked(editedPixels, 25, 175, 36, 186) > 0)

        // Undo: the very path that was saved, pixel for pixel; Redo: the edited one.
        click("Undo", exact = true)
        assertTrue(Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); !c.vectors.isRendering })
        assertEquals("Undo gives the saved path back", saved, layer.vector!!.objects.single())
        assertEquals("and its pixels", 0, differing(savedPixels, composite(c)))
        click("Redo", exact = true)
        assertTrue(Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); !c.vectors.isRendering })
        assertEquals("Redo gives the edit back", made, layer.vector!!.objects.single())
        assertEquals(0, differing(editedPixels, composite(c)))
        Smoke.assertQuiet(c, "reopened path")
    }

    /** Whether the segment [label] is the chosen one. */
    private fun chosen(label: String): Boolean =
        SmokeUi.find(label, exact = true)?.node?.config?.getOrNull(SemanticsProperties.Selected) == true

    /** Inked (darker than mid-grey) window pixels over document rect [l, r) × [t, b): what the user sees there. */
    private fun PointsQa.seen(l: Int, t: Int, r: Int, b: Int): Int {
        val w = PointsQa.window(s)
        var n = 0
        for (y in t until b) for (x in l until r) {
            val (sx, sy) = s.screen(x + 0.5f, y + 0.5f)
            val p = w.getPixel(sx.toInt(), sy.toInt())
            if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 < 200) n++
        }
        return n
    }

    /**
     * Item 7 in the Polyline tool (the strip is the same in Curve, Polyline and Path): three taps,
     * "Fill only": the open polyline fills to its chord at once, nothing above its peak; ✓ makes
     * a fill-only polyline. A tap inside that fill opens it again showing "Fill"; "Both" brings
     * the line back; ✓ is one app step; Undo gives the fill-only polyline back.
     */
    private fun PointsQa.polylineFill() {
        editor(vector = true)
        c.color = 0xFF2244CC.toInt()
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f, opacity = 1f)
        val tool = tool("Polyline")
        tap(60f, 250f); tap(150f, 80f); tap(240f, 250f)
        assertEquals(3, tool.pointCount)
        press(CurveLabels17.FILL_ONLY, 40f)
        assertEquals(CurvePaint.FILL, tool.paintMode)
        // What the user sees at once: inside filled, under the chord nothing (the window; the
        // peak itself is under the point's marker there, so the line is judged after ✓).
        assertTrue("the fill shows at once", Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); seen(145, 195, 155, 205) == 100 })
        assertEquals("nothing under the chord", 0, seen(145, 254, 155, 262))
        apply("Apply polyline")
        val layer = c.activeLayer
        val fillOnly = layer.vector!!.objects.single() as VPath
        assertTrue(fillOnly.polyline)
        assertNull("a fill alone has no line", fillOnly.stroke)
        assertNotNull(fillOnly.fill)
        assertTrue(Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); !c.vectors.isRendering })
        val filled = composite(c)
        assertEquals("applied as shown", 100, inked(filled, 145, 195, 155, 205))
        assertEquals("no line over the peak", 0, inked(filled, 147, 70, 153, 77))
        assertEquals(0, inked(filled, 145, 254, 155, 262))

        // The user's Stroke back for the next line; then a tap inside the fill opens it again.
        press(CurveLabels17.STROKE_ONLY, 40f)
        tap(150f, 200f)
        assertTrue("a tap on the fill opens the polyline", tool.isReopened)
        assertTrue("it shows Fill", chosen(CurveLabels17.FILL_ONLY))
        assertFalse("no stroke kind for a fill", SmokeUi.has(CurveLabels17.STROKE_KIND, exact = true))
        press(CurveLabels17.BOTH, 40f)
        assertEquals(CurvePaint.BOTH, tool.paintMode)
        assertTrue("the line comes back with the stroke kind", SmokeUi.has(CurveLabels17.STROKE_KIND, exact = true))
        val before = c.undoManager.undoCount
        apply("Apply polyline")
        assertEquals("one app step", before + 1, c.undoManager.undoCount)
        val both = layer.vector!!.objects.single() as VPath
        assertEquals("the same object", fillOnly.id, both.id)
        assertNotNull("a line now", both.stroke)
        assertEquals(fillOnly.fill, both.fill)
        assertTrue(Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); !c.vectors.isRendering })
        val lined = composite(c)
        save(lined, "polyline-both-doc")
        assertTrue("the line over the peak", inked(lined, 147, 72, 153, 79) > 0)
        assertEquals("still filled", 100, inked(lined, 145, 195, 155, 205))
        click("Undo", exact = true)
        assertEquals("Undo: the fill-only polyline again", fillOnly, layer.vector!!.objects.single())
        Smoke.assertQuiet(c, "polyline fill")
    }
}
