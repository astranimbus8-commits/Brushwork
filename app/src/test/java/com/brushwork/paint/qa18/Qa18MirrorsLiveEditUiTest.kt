package com.brushwork.paint.qa18

import android.graphics.Bitmap
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa18.Qa18MirrorsKit.divisions
import com.brushwork.paint.qa18.Qa18MirrorsKit.map
import com.brushwork.paint.qa18.Qa18MirrorsKit.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v1.8 final QA, item 1 (design §3.1): the copies of a curve and a shape on a vector layer are
 * live, on the user's phone (392 dp), every step by a finger. Under a Rotation 4 ruler:
 * - a Curve ("Plain line") of three taps, ✓: ONE path keeping the four maps. A tap on a copy's
 *   line reopens the path with its copies; its middle point dragged, ✓: one step, and every copy
 *   shows the point where it went (none where it was). Reopened again, "Line width" typed 14 in
 *   the settings sheet, ✓: one step, every copy 14 px thick. Undo twice takes the two edits back
 *   one by one (6 px, then the point where it was); Redo twice brings them back;
 * - a Shape (a rectangle) on a second vector layer: reopened by a tap on a copy's edge, its corner
 *   dragged, ✓; then "Stroke width" 14 in "Shape settings", ✓: each one step, every copy follows;
 * - saved and loaded: the same objects with the same copies; opened again in an editor, the same
 *   pixels; exported to SVG: one element per copy of each object, in the object's color;
 * - "Select shape" around the path, the object bar's "Show more object actions" then "Detach
 *   mirror copies": ONE step, four plain paths, the same pixels; one Undo brings the live copies back.
 * Renders: `mirrors-live-edited.png` and `mirrors-live-detached.png` (with `-screen`).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.mirrorsliveeditsandbox"])
class Qa18MirrorsLiveEditUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    /** What section 1 saved and loaded back. */
    private var loaded: Document? = null

    private fun document(): Document = Smoke.document(W, H, layers = 1, whiteBottom = true).also { d ->
        for (name in listOf(CURVE_LAYER, SHAPE_LAYER)) {
            d.layers += Layer(d.newLayerId(), name, BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        }
        d.activeLayerIndex = 1
    }

    private fun attach(screen: ChromeScreen) {
        s = screen
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
    }

    private fun layer(name: String): Layer = c.doc.layers.single { it.name == name }

    private fun steps() = c.undoManager.undoCount

    private fun settleRenders(what: String) {
        assertTrue(
            "$what: done",
            Smoke.pumpUntil(10_000) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering },
        )
        settle()
    }

    /** The floating ✓, one step, everything landed. */
    private fun apply(label: String, what: String) {
        val n0 = steps()
        click(label, exact = true)
        settleRenders(what)
        assertEquals("$what: ONE step", n0 + 1, steps())
    }

    /** [c] (ARGB) with its color channels multiplied by its alpha. */
    private fun premultiplied(c: Int): Int {
        val a = c ushr 24
        fun ch(shift: Int) = (((c shr shift) and 0xFF) * a + 127) / 255
        return (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun alpha(b: Bitmap, x: Int, y: Int): Int = if (x in 0 until b.width && y in 0 until b.height) b.getPixel(x, y) ushr 24 else 0

    /** The most opaque pixel within [r] px of [p]. */
    private fun inkNear(b: Bitmap, p: Vec2, r: Int): Int {
        var best = 0
        for (y in p.y.toInt() - r..p.y.toInt() + r) for (x in p.x.toInt() - r..p.x.toInt() + r) best = maxOf(best, alpha(b, x, y))
        return best
    }

    /**
     * The thickness (px) of the line through [p] across [n] (a unit normal): the longest run of
     * ink (alpha > 100) sampled every half pixel along the normal that comes within 8 px of [p].
     */
    private fun thickness(b: Bitmap, p: Vec2, n: Vec2): Float {
        var best = 0f
        var start = Float.NaN
        var t = -20f
        while (t <= 20.01f) {
            val inked = alpha(b, Math.round(p.x + n.x * t), Math.round(p.y + n.y * t)) > 100
            if (inked && start.isNaN()) start = t
            if ((!inked || t >= 20f) && !start.isNaN()) {
                val end = if (inked) t else t - 0.5f
                if (start <= 8f && end >= -8f) best = maxOf(best, end - start + 0.5f)
                start = Float.NaN
            }
            t += 0.5f
        }
        return best
    }

    /** [n] (a direction at [p]) through [m]'s linear part, unit length. */
    private fun mappedNormal(m: FloatArray, p: Vec2, n: Vec2): Vec2 {
        val a = map(m, p)!!
        val b = map(m, Vec2(p.x + n.x, p.y + n.y))!!
        val l = hypot(b.x - a.x, b.y - a.y)
        return Vec2((b.x - a.x) / l, (b.y - a.y) / l)
    }

    /** Every copy of [p] (normal [n]) is a line [px] thick (± 2.5). */
    private fun assertThick(what: String, b: Bitmap, maps: List<FloatArray>, p: Vec2, n: Vec2, px: Float) {
        val runs = maps.map { m -> thickness(b, map(m, p)!!, mappedNormal(m, p, n)) }
        println("Qa18MirrorsLiveEdit $what: thickness per copy $runs (expected $px)")
        for ((k, r) in runs.withIndex()) assertEquals("$what: copy $k is $px px thick (runs $runs)", px, r, 2.5f)
    }

    /** Ink at every copy of [now], none within 2 px of every copy of [was]. */
    private fun assertFollowed(what: String, b: Bitmap, maps: List<FloatArray>, now: Vec2, was: Vec2) {
        for ((k, m) in maps.withIndex()) {
            assertTrue("$what: copy $k shows the point at ${map(m, now)}", inkNear(b, map(m, now)!!, 3) >= 200)
            assertEquals("$what: copy $k no longer at ${map(m, was)}", 0, inkNear(b, map(m, was)!!, 2))
        }
    }

    // ------------------------------------------------------------------ 1. live edits

    private fun liveEdits(h: ChromeHarness) {
        attach(h.editor(document()) { it.snapping.enabled = false })
        c.brush = c.brush.copy(size = 6f, opacity = 1f)
        Qa18MirrorsKit.rulerOn(ui, SymmetryType.ROTATION, listOf(divisions("4")))
        val maps = ObjectCopies.mapsAt(c, TAPS[0].x, TAPS[0].y)
        assertEquals("Rotation 4: four maps", 4, maps.size)

        // The Curve, a plain line, on the first vector layer.
        val curves = layer(CURVE_LAYER)
        c.selectLayer(curves)
        c.color = CURVE_COLOR
        ui.tool("Curve")
        val tool = c.currentTool as CurveTool
        click(CurveLabels17.STROKE_KIND, exact = true)
        click(CurveStroke.PLAIN.label, exact = true)
        settle()
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        assertEquals("a plain line", CurveStroke.PLAIN, tool.settings.stroke)
        for (p in TAPS) ui.tap(p.x, p.y)
        assertEquals(3, tool.pointCount)
        apply("Apply curve edit", "the curve")
        val drawn = curves.vector!!.objects.single() as VPath
        assertEquals("the path keeps the ruler's maps", CopyMaps.of(maps), drawn.copies)
        assertEquals("6 px (the brush size)", 6f, drawn.stroke!!.width, 0.01f)
        assertThick("drawn", curves.bitmap, maps, TAPS[1], Vec2(0f, 1f), 6f)

        // A tap on a copy's line reopens the path, with its copies.
        val onCopy = map(maps[2], TAPS[1])!!
        ui.tap(onCopy.x, onCopy.y)
        assertTrue("a tap on copy 2 at $onCopy reopens the path", tool.isReopened)
        assertTrue("with its copies", tool.hasCopies)
        assertEquals("the source's points", 3, tool.pointCount)
        assertTrue("the middle point is the source's: ${tool.pointAt(1)}", hypot(tool.pointAt(1).x - TAPS[1].x, tool.pointAt(1).y - TAPS[1].y) < 1.5f)
        QaCurves.drag(s, TAPS[1], Vec2(0f, MOVE))
        val moved = tool.pointAt(1)
        assertTrue("the point went down by $MOVE: $moved", abs(moved.x - TAPS[1].x) < 1.5f && abs(moved.y - (TAPS[1].y + MOVE)) < 1.5f)
        apply("Apply curve edit", "the point moved")
        val afterMove = curves.vector!!.objects.single() as VPath
        assertEquals("the same path", drawn.id, afterMove.id)
        assertEquals("the same copies", drawn.copies, afterMove.copies)
        val bentPixels = pixels(curves.bitmap)
        assertFollowed("the point moved", curves.bitmap, maps, moved, TAPS[1])

        // Reopened again; "Line width" 14 in "Curve settings" (row 1's button to the sheet).
        val onCopy3 = map(maps[3], moved)!!
        ui.tap(onCopy3.x, onCopy3.y)
        assertTrue("a tap on copy 3 at $onCopy3 reopens the path", tool.isReopened && tool.hasCopies)
        ui.reach("Curve settings", 32f)
        click("Curve settings", exact = true)
        settle()
        SmokeUi.typeAndDone("Line width", "14")
        assertEquals("typed", 14f, tool.lineWidth, 1e-3f)
        Finger.back()
        settle()
        assertTrue("the sheet closed, the path still open", tool.isReopened)
        apply("Apply curve edit", "the line width")
        val thick = curves.vector!!.objects.single() as VPath
        assertEquals("the same path", drawn.id, thick.id)
        assertEquals("14 px", 14f, thick.stroke!!.width, 0.01f)
        assertEquals("the same copies", drawn.copies, thick.copies)
        assertThick("14 px", curves.bitmap, maps, moved, Vec2(0f, 1f), 14f)
        val thickPixels = pixels(curves.bitmap)

        // The top row's Undo: one edit at a time.
        Finger.tap(s, "Undo")
        settleRenders("Undo the width")
        assertEquals("Undo: the point moved, 6 px", afterMove, curves.vector!!.objects.single())
        assertArrayEquals("Undo: the bent pixels", bentPixels, pixels(curves.bitmap))
        Finger.tap(s, "Undo")
        settleRenders("Undo the move")
        assertEquals("Undo again: as drawn", drawn, curves.vector!!.objects.single())
        assertFollowed("undone", curves.bitmap, maps, TAPS[1], moved)
        Finger.tap(s, "Redo")
        Finger.tap(s, "Redo")
        settleRenders("Redo twice")
        assertEquals("Redo twice: 14 px, bent", thick, curves.vector!!.objects.single())
        assertArrayEquals("Redo twice: the same pixels", thickPixels, pixels(curves.bitmap))

        // The Shape on the second vector layer.
        val shapes = layer(SHAPE_LAYER)
        c.selectLayer(shapes)
        settle()
        c.color = SHAPE_COLOR
        ui.tool("Shape")
        val st = c.currentTool as ShapeTool
        val shapeMaps = ObjectCopies.mapsAt(c, BOX_A.x, BOX_A.y)
        ui.stroke(BOX_A.x to BOX_A.y, (BOX_A.x + BOX_B.x) / 2f to (BOX_A.y + BOX_B.y) / 2f, BOX_B.x to BOX_B.y)
        assertNotNull("a pending rectangle", st.box)
        apply("Apply shape edit", "the shape")
        val box = shapes.vector!!.objects.single() as VShape
        assertEquals("the shape keeps the ruler's maps", CopyMaps.of(shapeMaps), box.shape.copies)
        val corner = Vec2(box.shape.cx + box.shape.w / 2f, box.shape.cy + box.shape.h / 2f)
        assertTrue("its corner where the finger let go: $corner", hypot(corner.x - BOX_B.x, corner.y - BOX_B.y) < 2f)

        // Reopened by a tap on copy 2's top edge; the bottom-right corner dragged.
        val top = Vec2(box.shape.cx, box.shape.cy - box.shape.h / 2f)
        val onShapeCopy = map(shapeMaps[2], top)!!
        ui.tap(onShapeCopy.x, onShapeCopy.y)
        assertNotNull("a tap on copy 2's edge at $onShapeCopy reopens the shape", st.box)
        assertTrue("with its copies", st.hasCopies)
        QaCurves.drag(s, corner, CORNER_BY)
        apply("Apply shape edit", "the corner dragged")
        val bigger = shapes.vector!!.objects.single() as VShape
        assertEquals("the same shape", box.id, bigger.id)
        assertEquals("the same copies", box.shape.copies, bigger.shape.copies)
        val corner2 = Vec2(bigger.shape.cx + bigger.shape.w / 2f, bigger.shape.cy + bigger.shape.h / 2f)
        assertTrue("the corner went by $CORNER_BY: $corner2", hypot(corner2.x - corner.x - CORNER_BY.x, corner2.y - corner.y - CORNER_BY.y) < 2.5f)
        assertFollowed("the corner dragged", shapes.bitmap, shapeMaps, corner2, corner)

        // Reopened again; "Stroke width" 14 in "Shape settings".
        val top2 = Vec2(bigger.shape.cx, bigger.shape.cy - bigger.shape.h / 2f)
        val onShapeCopy1 = map(shapeMaps[1], top2)!!
        ui.tap(onShapeCopy1.x, onShapeCopy1.y)
        assertTrue("a tap on copy 1's edge reopens the shape", st.box != null && st.hasCopies)
        ui.reach("Shape settings", 32f)
        click("Shape settings", exact = true)
        settle()
        SmokeUi.typeAndDone("Stroke width", "14")
        Finger.back()
        settle()
        assertNotNull("the sheet closed, the shape still open", st.box)
        apply("Apply shape edit", "the stroke width")
        val wide = shapes.vector!!.objects.single() as VShape
        assertEquals("the same shape", box.id, wide.id)
        assertEquals("14 px", 14f, wide.shape.strokeWidth, 0.01f)
        assertEquals("the same copies", box.shape.copies, wide.shape.copies)
        assertThick("the shape 14 px", shapes.bitmap, shapeMaps, top2, Vec2(0f, 1f), 14f)

        ui.tool("Curve")
        Qa18MirrorsKit.shoot(s, "live-edited")

        // Saved, then loaded: the same objects with the same copies.
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        runBlocking { repo.save(c.doc, null) }
        val back = runBlocking { repo.load(c.doc.id) }
        assertEquals("no load warnings", emptyList<String>(), back.loadWarnings.toList())
        for (name in listOf(CURVE_LAYER, SHAPE_LAYER)) {
            val was = layer(name)
            val now = back.layers.single { it.name == name }
            assertEquals("$name: the same objects, copies and all", was.vector, now.vector)
            assertArrayEquals("$name: the same pixels", pixels(was.bitmap), pixels(now.bitmap))
        }
        assertEquals("the ruler kept", c.symmetry, back.symmetry)
        loaded = back
        Smoke.assertQuiet(c, "live edits")
    }

    // ------------------------------------------------------------------ 2. reopened, SVG, detached

    private fun reopenedExportedDetached(h: ChromeHarness) {
        val doc = requireNotNull(loaded) { "section 1 did not save" }
        val expectCurve = pixels(doc.layers.single { it.name == CURVE_LAYER }.bitmap)
        attach(h.editor(doc) { it.snapping.enabled = false })
        val curves = layer(CURVE_LAYER)
        val path = curves.vector!!.objects.single() as VPath
        assertEquals("reopened: four copies", 4, ObjectCopies.count(path))
        assertArrayEquals("reopened: the same pixels", expectCurve, pixels(curves.bitmap))

        // The SVG: one element per copy of each object, in its color.
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val svg = out.toString("UTF-8")
        val elements = Regex("<(path|rect|polygon|polyline|ellipse|circle|line)\\b[^>]*>").findAll(svg).map { it.value }.toList()
        val byStroke = elements.groupingBy { Regex("\\sstroke=\"(#[0-9a-fA-F]{6})\"").find(it)?.groupValues?.get(1)?.lowercase() ?: "none" }.eachCount()
        println("Qa18MirrorsLiveEdit SVG: ${elements.size} elements, by stroke $byStroke")
        assertEquals("the curve: one element per copy", 4, byStroke[SvgWriter.color(CURVE_COLOR).lowercase()])
        assertEquals("the shape: one element per copy", 4, byStroke[SvgWriter.color(SHAPE_COLOR).lowercase()])

        // "Select shape" around everything on the curve layer; the object bar's More row; Detach.
        c.selectLayer(curves)
        settle()
        ui.tool("Select shape")
        ui.stroke(4f to 4f, 200f to 150f, (W - 4f) to (H - 4f))
        assertTrue("the path selected", Smoke.pumpUntil(10_000) { settle(1); c.vectors.selectedIds == setOf(path.id) })
        settle()
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        val n0 = steps()
        Finger.tap(s, MirrorLabels18.DETACH)
        settleRenders("detach")
        assertEquals("Detach: ONE step", n0 + 1, steps())
        assertEquals(HistoryLabels.DETACH_MIRROR_COPIES, c.undoManager.undoLabel)
        val pieces = curves.vector!!.objects
        assertEquals("four plain paths", 4, pieces.size)
        assertTrue("no copies left", pieces.all { it is VPath && !ObjectCopies.hasCopies(it) })
        assertEquals("the source keeps its id", path.id, pieces[0].id)
        // Compared premultiplied, within 12: a detached copy is the path transformed, drawn in place
        // (not the source through a matrix), so an edge pixel's coverage rounds a few levels apart
        // (seen: alpha 8 to 0; 14 to 15, which unpremultiplies to quite different colors).
        val detachedPixels = pixels(curves.bitmap)
        val off = detachedPixels.indices.filter { !Qa18MirrorsKit.channelsClose(premultiplied(detachedPixels[it]), premultiplied(expectCurve[it]), 12) }
        println(
            "Qa18MirrorsLiveEdit detach: ${off.size} pixels off by more than 12 (premultiplied): " +
                off.take(12).joinToString { "(${it % W},${it / W}) ${Integer.toHexString(expectCurve[it])} -> ${Integer.toHexString(detachedPixels[it])}" },
        )
        assertEquals("the same pixels (within 12 per premultiplied channel)", 0, off.size)
        Qa18MirrorsKit.shoot(s, "live-detached")
        Finger.tap(s, "Undo")
        settleRenders("Undo the detach")
        assertEquals("Undo: the one path with its copies", path, curves.vector!!.objects.single())
        assertArrayEquals("Undo: the same pixels", expectCurve, pixels(curves.bitmap))
        c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(c, "detach")
    }

    @Test
    fun curveAndShapeCopiesFollowEditsSaveExportAndDetach() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("1. live copies follow the edits; saved and loaded") { liveEdits(h) }
        h.section("2. reopened, SVG, detached") { reopenedExportedDetached(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300
        const val CURVE_LAYER = "Vector curve"
        const val SHAPE_LAYER = "Vector shape"
        val CURVE_COLOR = 0xFF101010.toInt()
        val SHAPE_COLOR = 0xFF1E5AD2.toInt()

        /** The curve's three taps (50 px apart), its middle point dragged down by [MOVE]. */
        val TAPS = listOf(Vec2(60f, 60f), Vec2(110f, 40f), Vec2(160f, 60f))
        const val MOVE = 40f

        /** The rectangle's drag, then its corner dragged by [CORNER_BY] (every copy on the canvas, before and after). */
        val BOX_A = Vec2(60f, 100f)
        val BOX_B = Vec2(110f, 140f)
        val CORNER_BY = Vec2(30f, 20f)
    }
}
