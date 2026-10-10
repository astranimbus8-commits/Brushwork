package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.qa17.PointsQa.Companion.composite
import com.brushwork.paint.qa17.PointsQa.Companion.inked
import com.brushwork.paint.qa17.PointsQa.Companion.save
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorOps
import kotlinx.coroutines.Dispatchers
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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * v1.7 final QA, item 7 (Fill, Stroke and Both for Paths) on the user's phone (392 dp), with
 * fingers: on a Path the canvas shows "Stroke only", "Fill only" and "Stroke and fill" at once
 * with "Current brush" and with "Plain line" (the open path filled as if closed by its chord),
 * Fill and Both say "Fill needs 3 points" below 3 points, Fill then Stroke / Both brings the
 * stroke kind back, and ✓ draws what was shown. On a vector layer three paths (a fill alone, a
 * plain line of varying thickness with a sharp corner, a brush line with a fill) are saved and
 * reopened as they were (a tap on each shows its own segment and kind) and exported to SVG: the
 * fill alone has no stroke, the varying line is a filled outline through its corner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.pointspaintsandbox"])
class Qa17PointsPaintUiTest {

    @Test
    fun strokeFillAndBothShowSaveAndExport() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        val qa = PointsQa(h)
        h.section("7: Stroke, Fill and Both on a Path, brush and plain line") { qa.modes() }
        h.section("7: on a vector layer, saved, reopened and exported to SVG") { qa.savedAndExported() }
        dog.interrupt()
        h.finish()
    }

    private val blue = 0xFF2244CC.toInt()

    private fun selected(label: String): Boolean =
        SmokeUi.find(label, exact = true)?.node?.config?.getOrNull(SemanticsProperties.Selected) == true

    /** Waits (frames) until [b] holds on the composite. */
    private fun PointsQa.until(what: String, b: (Bitmap) -> Boolean) =
        assertTrue(what, Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); b(composite(c)) })

    private fun PointsQa.modes() {
        editor()
        c.color = blue
        c.brush = BrushLibrary.defaultBrush.copy(size = 16f, opacity = 1f)
        val tool = tool("Path")
        assertTrue("Stroke is the default", selected(CurveLabels17.STROKE_ONLY))

        // Two points: Fill and Both are off and say why; a tap changes nothing.
        tap(80f, 230f)
        tap(200f, 50f)
        ui.reach(CurveLabels17.FILL_ONLY, 40f)
        assertFalse(SmokeUi.isEnabled(CurveLabels17.FILL_ONLY))
        assertFalse(SmokeUi.isEnabled(CurveLabels17.BOTH))
        for (l in listOf(CurveLabels17.FILL_ONLY, CurveLabels17.BOTH)) {
            assertEquals("$l says why", CurveLabels17.FILL_NEEDS_3, SmokeUi.find(l, exact = true)?.node?.config?.getOrNull(SemanticsProperties.StateDescription))
        }
        click(CurveLabels17.FILL_ONLY, exact = true, settleAfter = false)
        assertEquals(CurveLabels17.FILL_NEEDS_3, c.message)
        settle()
        assertEquals(CurvePaint.STROKE, tool.paintMode)
        tap(320f, 230f)
        assertEquals(3, tool.pointCount)
        assertTrue(SmokeUi.isEnabled(CurveLabels17.FILL_ONLY))

        // The open path's peak is at (200, 140); its fill is closed by the chord at y = 230.
        // Above the peak (4..7 px): only the 16 px line; inside: only the fill; under the chord: nothing.
        fun line(b: Bitmap) = inked(b, 198, 133, 202, 137)
        fun inside(b: Bitmap) = inked(b, 195, 190, 205, 200)
        fun under(b: Bitmap) = inked(b, 195, 234, 205, 240)
        // What the user sees: the window at those document pixels (the brush line's fill is drawn
        // over the canvas while the path is pending, beside the live brush, not into the layer).
        fun seen(b: Bitmap, l: Int, t: Int, r: Int, bo: Int): Int {
            var n = 0
            for (y in t until bo) for (x in l until r) {
                val (sx, sy) = s.screen(x + 0.5f, y + 0.5f)
                val p = b.getPixel(sx.toInt(), sy.toInt())
                if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 < 200) n++
            }
            return n
        }
        fun shows(what: String, stroke: Boolean, fill: Boolean) {
            fun ok(w: Bitmap): Boolean {
                val ln = seen(w, 198, 133, 202, 137); val ins = seen(w, 195, 190, 205, 200)
                return (if (stroke) ln == 16 else ln == 0) && (if (fill) ins == 100 else ins == 0) && seen(w, 195, 234, 205, 240) == 0
            }
            if (!Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); ok(PointsQa.window(s)) }) {
                val w = PointsQa.window(s)
                save(w, "fill-failed")
                throw AssertionError("$what: line $stroke, fill $fill; seen: line ${seen(w, 198, 133, 202, 137)}/16, inside ${seen(w, 195, 190, 205, 200)}/100, under ${seen(w, 195, 234, 205, 240)}")
            }
        }
        shows("brush, Stroke only", stroke = true, fill = false)

        press(CurveLabels17.FILL_ONLY, 40f)
        assertEquals(CurvePaint.FILL, tool.paintMode)
        assertFalse("a fill has no stroke kind", has(CurveLabels17.STROKE_KIND, exact = true))
        shows("Fill only", stroke = false, fill = true)
        save(composite(c), "fill-only-doc")

        press(CurveLabels17.BOTH, 40f)
        assertEquals("Both brings the brush back", CurveStroke.BRUSH, tool.settings.stroke)
        shows("brush, Stroke and fill", stroke = true, fill = true)
        save(PointsQa.window(s), "fill-brush-both-pending-screen")

        // The plain line: Both, then Fill and Stroke (the plain line comes back).
        press(CurveLabels17.STROKE_KIND, 32f)
        click(CurveStroke.PLAIN.label, exact = true)
        assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
        assertEquals(CurvePaint.BOTH, tool.paintMode)
        shows("plain, Stroke and fill", stroke = true, fill = true)
        press(CurveLabels17.FILL_ONLY, 40f)
        shows("plain, Fill only", stroke = false, fill = true)
        press(CurveLabels17.STROKE_ONLY, 40f)
        assertEquals("Stroke brings the plain line back", CurveStroke.PLAIN, tool.settings.stroke)
        shows("plain, Stroke only", stroke = true, fill = false)
        save(composite(c), "fill-plain-stroke-doc")

        // ✓ with the brush and the fill: one app step, drawn as shown.
        press(CurveLabels17.BOTH, 40f)
        press(CurveLabels17.STROKE_KIND, 32f)
        click(CurveStroke.BRUSH.label, exact = true)
        shows("brush, Stroke and fill again", stroke = true, fill = true)
        save(PointsQa.window(s), "fill-brush-both-screen")
        val before = c.undoManager.undoCount
        apply("Apply path edit")
        assertEquals("one app step", before + 1, c.undoManager.undoCount)
        val b = composite(c)
        assertEquals("the line, applied", 16, line(b))
        assertEquals("the fill, applied", 100, inside(b))
        assertEquals(0, under(b))
        Smoke.assertQuiet(c, "modes")
        // (The next sections start from the user's own Stroke only.)
        tool("Path").setPaintMode(CurvePaint.STROKE)
    }

    private fun PointsQa.savedAndExported() {
        editor(vector = true)
        c.color = blue
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f, opacity = 1f)
        var tool = tool("Path")
        assertEquals(CurvePaint.STROKE, tool.paintMode)

        // A fill alone.
        tap(30f, 280f); tap(100f, 120f); tap(170f, 280f)
        press(CurveLabels17.FILL_ONLY, 40f)
        apply("Apply path")
        // A plain line, thicker at its second point, with a sharp third point.
        press(CurveLabels17.STROKE_ONLY, 40f)
        press(CurveLabels17.STROKE_KIND, 32f)
        click(CurveStroke.PLAIN.label, exact = true)
        tap(200f, 180f); tap(250f, 110f); tap(300f, 230f); tap(370f, 160f)
        tap(250f, 110f)
        press("Type the point thickness")
        SmokeUi.typeAndDone("Thickness", "200")
        tap(300f, 230f)
        press("Sharp corner")
        apply("Apply path")
        // A brush line with a fill.
        press(CurveLabels17.BOTH, 40f)
        press(CurveLabels17.STROKE_KIND, 32f)
        click(CurveStroke.BRUSH.label, exact = true)
        tap(30f, 80f); tap(110f, 20f); tap(190f, 80f)
        apply("Apply path")

        val layer = c.doc.layers.last()
        val made = layer.vector!!.objects.map { it as VPath }
        assertEquals(3, made.size)
        val (fill, plain, both) = made
        assertNull("the fill alone has no line", fill.stroke)
        assertEquals(VPaint.Solid(blue), fill.fill)
        assertEquals(VStrokeKind.PLAIN, plain.stroke?.kind)
        assertNull(plain.fill)
        assertEquals(listOf(false, false, true, false), plain.spline!!.points.map { it.sharp })
        assertEquals(listOf(1f, 2f, 1f, 1f), plain.spline!!.points.map { it.width })
        assertEquals(VStrokeKind.BRUSH, both.stroke?.kind)
        assertNotNull(both.fill)
        val shown = composite(c)
        save(shown, "fill-vector-doc")
        assertTrue("the fill alone", inked(shown, 95, 240, 105, 250) == 100)
        assertTrue("the plain line through its sharp corner", inked(shown, 297, 224, 303, 232) > 0)
        assertTrue("the brush path's fill", inked(shown, 105, 62, 115, 72) == 100)

        // Saved and reopened: the same three paths, drawn the same.
        val app = s.activity.application
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        val loaded = runBlocking { repo.save(c.doc, null); repo.load(c.doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val back = loaded.layers.last().vector!!.objects.map { it as VPath }
        assertEquals(made.map { it.fill }, back.map { it.fill })
        assertEquals(made.map { it.stroke?.kind }, back.map { it.stroke?.kind })
        assertEquals(made.map { it.spline }, back.map { it.spline })
        loaded.activeLayerIndex = loaded.layers.lastIndex
        editor(opened = loaded)
        assertTrue("reopened and drawn", Smoke.pumpUntil(PointsQa.WAIT_MS) { settle(1); !c.vectors.isRendering })
        val again = composite(c)
        var bad = 0
        for (y in 0 until again.height) for (x in 0 until again.width) {
            val p = again.getPixel(x, y); val q = shown.getPixel(x, y)
            if (listOf(16, 8, 0).any { abs((p shr it and 0xFF) - (q shr it and 0xFF)) > 2 }) bad++
        }
        assertEquals("the reopened project looks the same", 0, bad)

        // A tap on each path shows its own look in the strip.
        tool = tool("Path")
        tap(100f, 250f)
        assertTrue("the fill reopened", tool.isReopened)
        assertTrue(selected(CurveLabels17.FILL_ONLY))
        assertFalse(has(CurveLabels17.STROKE_KIND, exact = true))
        click("Discard path edit", exact = true)
        settle()
        tap(300f, 230f)
        assertTrue("the plain line reopened", tool.isReopened)
        assertEquals(CurvePaint.STROKE, tool.paintMode)
        assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
        assertEquals(listOf(false, false, true, false), tool.spline!!.points.map { it.sharp })
        click("Discard path edit", exact = true)
        settle()

        // SVG: the fill alone is a fill without a stroke; the varying line is a filled outline
        // (no stroke) that reaches its sharp corner; the brush path's fill and its line's outline.
        val scene = runBlocking {
            ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        }
        val items = scene.layers.single { it.name == c.doc.layers.last().name }.items.map { it as SceneItem.Shape }
        assertEquals("fill, plain outline, brush fill, brush outline: $items", 4, items.size)
        assertEquals(VPaint.Solid(blue), items[0].fill)
        assertNull("the fill alone has no stroke", items[0].stroke)
        val outline = items[1]
        assertNull("a varying line is a filled outline", outline.stroke)
        assertEquals(VPaint.Solid(blue), outline.fill)
        val ob = requireNotNull(outline.path.bounds())
        val gb = requireNotNull(VectorOps.toVectorPath(back[1]).bounds())
        assertTrue("the outline reaches the sharp corner (y 230): $ob", ob.bottom > 233f)
        assertTrue("thicker at the second point: $ob vs the centre line $gb", ob.top < gb.top - 6.5f)
        assertNotNull(items[2].fill)
        assertNull(items[2].stroke)
        assertNotNull("the brush line as an outline", items[3].fill)

        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val svg = out.toString("UTF-8")
        val paths = Regex("<path\\b[^>]*/>").findAll(svg).map { it.value }.toList()
        assertEquals("$paths", 4, paths.size)
        assertFalse("no stroke on the fill alone: ${paths[0]}", paths[0].contains("stroke="))
        assertFalse("no stroke on the outline: ${paths[1]}", paths[1].contains("stroke="))
        Smoke.assertQuiet(c, "saved and exported")
    }
}
