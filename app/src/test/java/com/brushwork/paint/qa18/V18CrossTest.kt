package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.select.colorrange.ColorPreset
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v1.8 integration (design §6.2 item 2, scenarios a to d): v1.8 features used together on the full
 * editor of the user's 392 dp phone, each step a user takes done through the screen (tool menu,
 * strips, object bar, sheets, finger strokes and taps); a precondition (pixels, a ruler, a tool's
 * settings, a clone source) set in code. Each scenario checks the document or the export:
 *
 * - a. a Kaleidoscope 6 Archimedean spiral (a plain line, "Spirals and waves") on a vector layer,
 *   "Array from objects" from the object bar, the Look tab's "Sunset" tint, exported to SVG: each
 *   mirror copy of each array copy is ONE `<path>`, in its copy's tinted color;
 * - b. a cog with a hole drawn under a Mirror ruler on a vector layer; "Detach mirror copies" from
 *   the object bar's More row (one step) leaves two plain cogs; each, opened by a tap with the Shape
 *   tool, becomes through the strip's "Turn into path" ONE compound plain path with its hole (D21);
 * - c. a Magnetic lasso around a blue disc, intersected by Color select "Reds" (the strip's mode
 *   dropdown and presets popup): the reds inside the disc; a Clone stamp stroke under a Rotation 4
 *   ruler, cloning a green centre and running past a red square onto the unselected blue, then
 *   changes pixels only in the reds, in the four rotated places, each green;
 * - d. Chromatic aberration with the "Red/Cyan" fringe chip applied (one step, the filter's own
 *   result), then "Array from selection" with a "Sunset" look: each tinted copy is the fringed
 *   pixels tinted.
 *
 * One test, its own sandbox (Compose's frame clock serves the first test of a sandbox only); each
 * scenario a [ChromeHarness] section on a fresh editor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.crosssandbox"])
class V18CrossTest {

    @Test
    fun v18FeaturesTogetherOnThePhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("a. a Kaleidoscope spiral with an Array look, as SVG") { spiralArraySvg(Qa17ArrayUi(h)) }
        h.section("b. a holed cog's mirror copies detached and turned into paths") { cogDetachToPaths(Qa17ArrayUi(h)) }
        h.section("c. a clone stroke with Rotation 4 inside a lasso and Reds selection") { cloneInsideTheSelection(Qa17ArrayUi(h)) }
        h.section("d. Red/Cyan fringes carried by an Array look's tinted copies") { fringedArrayCopies(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    // ------------------------------------------------------------------ shared

    /** A white raster layer and an empty vector layer above it, the vector layer active. */
    private fun vectorDoc(): Document = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun hex(c: Int) = "#" + Integer.toHexString(c).padStart(8, '0')

    /** Every channel of [actual] within [tol] of [expected]. */
    private fun near(expected: Int, actual: Int, tol: Int = 2): Boolean =
        intArrayOf(24, 16, 8, 0).all { sh -> abs(((expected ushr sh) and 0xFF) - ((actual ushr sh) and 0xFF)) <= tol }

    /** The Array sheet's Look tab, then its "Sunset" tint preset: ONE step "Array look". */
    private fun sunsetLook(u: Qa17ArrayUi, layer: Layer) {
        u.showArraySheet()
        u.press(ArrayLabels18.LOOK, 44f)
        val steps = u.steps()
        u.press(SUNSET)
        u.settleRenders("the Sunset look")
        assertEquals("the tint: one step", steps + 1, u.steps())
        assertEquals(ArrayLabels18.HISTORY_LOOK, u.c.undoManager.undoLabel)
        val look = layer.array!!.spec.look ?: throw AssertionError("no look after \"$SUNSET\"")
        assertTrue("a tint of several colors at full strength: $look", look.tint.size >= 2 && look.tintStrength == 1f)
        SmokeUi.click("Close", exact = true)
        settle()
    }

    // ------------------------------------------------------------------ a

    private fun spiralArraySvg(u: Qa17ArrayUi) {
        u.editor(vectorDoc())
        val c = u.c
        val s = u.s
        val vectorLayer = c.doc.layers[1]
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 6))
        c.color = LINE
        u.tool(ToolId.CURVE.label)
        assertEquals(ToolId.CURVE, c.activeToolId)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 4f) }
        settle()

        // "Spirals and waves" > "Archimedean spiral", then the floating ✓.
        click(CurveGenLabels18.CURVES_DESC, exact = true)
        click(CurveGenLabels18.ARCHIMEDEAN, exact = true)
        settle()
        assertEquals("a generated spiral is open", CurveGenKind.ARCHIMEDEAN, curve.generator?.kind)
        u.applyEdit("Apply curve edit")
        u.settleRenders("the spiral")
        val spiral = vectorLayer.vector!!.objects.singleOrNull() as? VPath ?: throw AssertionError("one path: ${vectorLayer.vector!!.objects}")
        assertEquals("the generator kept", CurveGenKind.ARCHIMEDEAN, spiral.generator?.kind)
        val stroke = spiral.stroke ?: throw AssertionError("a plain line has a stroke")
        assertEquals(VStrokeKind.PLAIN, stroke.kind)
        assertEquals(LINE, stroke.color)
        val first = spiral.subpaths.first().anchors.first()
        val m = ObjectCopies.mapsAt(c, first.x, first.y).size
        assertTrue("the Kaleidoscope gives copies ($m maps)", m >= 6)
        assertEquals("the spiral carries the ruler's copies", m, ObjectCopies.count(spiral))

        // The object bar: its More row's "Array from objects".
        c.vectors.setSelection(vectorLayer, setOf(spiral.id))
        settle()
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        Finger.tap(s, ArrayLabels.FROM_OBJECTS)
        u.settleRenders("Array from objects")
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val arrayLayer = c.activeLayer
        val array = arrayLayer.array ?: throw AssertionError("no array on ${arrayLayer.name}")
        assertEquals("the spiral is the array's source", listOf(spiral.id), arrayLayer.vector!!.objects.map { it.id })
        assertTrue("the source moved out", vectorLayer.vector!!.objects.isEmpty())
        assertNull("no look yet", array.spec.look)
        sunsetLook(u, arrayLayer)

        // The SVG: one <path> per mirror copy of each array copy, stroked in its copy's tint.
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val svg = out.toString("UTF-8")
        val paths = Regex("<path\\b[^>]*>").findAll(svg).map { it.value }.filter { Regex("\\sd=\"[^\"]+\"").containsMatchIn(it) }.toList()
        val spec = arrayLayer.array!!.spec
        val instances = ArrayInstances.of(spec, ArrayDraw.sourceBounds(arrayLayer.dataSnapshot())!!)
        assertEquals("the array's copies", spec.count, instances.size)
        val expected = LinkedHashMap<String, Int>()
        for (inst in instances) {
            val look = inst.look
            assertTrue("copy ${inst.k} shows (opacity ${look?.opacity})", look == null || look.opacity > 0f)
            val color = if (look == null) LINE else ArrayTint.apply(LINE, look.tintColor, look.tintStrength)
            expected.merge(SvgWriter.color(color), m, Int::plus)
        }
        assertTrue("the copies are tinted apart: $expected", expected.size >= 2)
        assertEquals("one <path> per copy ($m mirror copies × ${instances.size} array copies)", m * instances.size, paths.size)
        for (p in paths) assertEquals("one subpath per <path>: ${p.take(160)}", 1, Regex("\\sd=\"([^\"]*)\"").find(p)!!.groupValues[1].count { it == 'M' })
        val strokes = paths.map { p -> Regex("\\sstroke=\"(#[0-9a-f]{6})\"").find(p)?.groupValues?.get(1) ?: "none: ${p.take(160)}" }
        assertEquals("each copy's path in its tinted color", expected, strokes.groupingBy { it }.eachCount())
        Smoke.assertQuiet(c, "a")
    }

    // ------------------------------------------------------------------ b

    private fun cogDetachToPaths(u: Qa17ArrayUi) {
        u.editor(vectorDoc())
        val c = u.c
        val s = u.s
        val layer = c.doc.layers[1]
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        u.tool(ToolId.SHAPE.label)
        val shapes = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shapes.update {
            it.copy(
                type = ShapeType.COG, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.PLAIN, useBrushSize = false,
                strokeWidth = 3f, editable = true, cogHole = 0.4f, fromCenter = false, keepProportions = true,
            )
        }
        settle()

        // A finger drags the cog out on the left half; the mirror copy follows on the right.
        Fingers18.stroke(s, 60f, 90f, 160f, 190f)
        assertNotNull("a cog is open", shapes.box)
        assertTrue("the open cog has its mirror copy", shapes.hasCopies)
        u.applyEdit("Apply shape edit")
        u.settleRenders("the cog")
        val cog = layer.vector!!.objects.singleOrNull() as? VShape ?: throw AssertionError("one cog object: ${layer.vector!!.objects}")
        assertEquals(ShapeType.COG, cog.shape.type)
        assertEquals(0.4f, cog.shape.cogHole, 1e-4f)
        assertEquals("the cog and its mirror copy", 2, ObjectCopies.count(cog))

        // The object bar's More row: "Detach mirror copies", ONE step; two plain cogs.
        c.vectors.setSelection(layer, setOf(cog.id))
        settle()
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        val steps = c.undoManager.undoCount
        Finger.tap(s, MirrorLabels18.DETACH)
        u.settleRenders("detach")
        assertEquals("Detach: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.DETACH_MIRROR_COPIES, c.undoManager.undoLabel)
        val pieces = layer.vector!!.objects
        assertEquals("the cog and its detached copy: $pieces", 2, pieces.size)
        assertEquals("the source keeps its id", cog.id, pieces[0].id)
        for (p in pieces) {
            assertTrue("a detached cog is still a cog shape (it opens with the Shape tool): $p", p is VShape && p.shape.type == ShapeType.COG)
            assertFalse("no copies left", ObjectCopies.hasCopies(p))
            assertEquals("its hole kept", 0.4f, (p as VShape).shape.cogHole, 1e-4f)
        }
        val mirrored = (pieces[1] as VShape).shape
        assertEquals("the copy across the mirror", 400f - cog.shape.cx, mirrored.cx, 0.5f)
        Finger.tap(s, "Deselect the objects")
        settle()

        // Each cog: a tap on its ring opens it; the strip's "Turn into path" makes ONE compound path.
        for (piece in pieces.map { it as VShape }) {
            if (c.activeToolId != ToolId.SHAPE) u.tool(ToolId.SHAPE.label)
            val o = piece.shape
            val root = min(o.w, o.h) / 2f * (1f - o.cogDepth)
            val ring = root * (1f + o.cogHole) / 2f
            u.ui.tap(o.cx + ring, o.cy)
            assertNotNull("a tap on cog ${piece.id}'s ring opens it", shapes.box)
            assertEquals(ShapeType.COG, shapes.settings.type)
            assertFalse("a holed cog never enters Points mode", shapes.pointsMode)
            val before = c.undoManager.undoCount
            // (The editor moves a message into its snackbar at once: the hint is read there.)
            assertTrue("no hint before", Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { settle(1); !SmokeUi.has(CogLabels18.COMPOUND_HINT, exact = true) })
            click(PointLabels.TO_PATH, exact = true)
            assertTrue(
                "the hint \"${CogLabels18.COMPOUND_HINT}\" shows; shown: ${SmokeUi.shown().take(40)}",
                Smoke.pumpUntil(5_000) { settle(1); SmokeUi.has(CogLabels18.COMPOUND_HINT, exact = true) },
            )
            c.vectors.flushPending()
            u.settleRenders("Turn into path ${piece.id}")
            assertEquals("ONE step", before + 1, c.undoManager.undoCount)
            assertEquals(HistoryLabels.TURN_INTO_PATH, c.undoManager.undoLabel)
            assertEquals("the Path tool takes it", ToolId.PATH, c.activeToolId)
            val path = layer.vector!!.objects.firstOrNull { it.id == piece.id } as? VPath
                ?: throw AssertionError("cog ${piece.id} is no path: ${layer.vector!!.objects}")
            assertEquals("the teeth and the hole", 2, path.subpaths.size)
            assertTrue("both closed", path.subpaths.all { it.closed })
            assertNull("a plain path (no spline)", path.spline)
            assertEquals(VStrokeKind.PLAIN, path.stroke?.kind)
            assertNotNull("filled", path.fill)
            assertFalse(ObjectCopies.hasCopies(path))
            // The hole stays empty, the ring is painted.
            assertEquals("the hole of ${piece.id}", 0, layer.bitmap.getPixel(o.cx.roundToInt(), o.cy.roundToInt()) ushr 24)
            assertTrue("the ring of ${piece.id}", layer.bitmap.getPixel((o.cx + ring).roundToInt(), o.cy.roundToInt()) ushr 24 > 0)
        }
        assertEquals("two compound paths", 2, layer.vector!!.objects.count { it is VPath && it.subpaths.size == 2 })
        Smoke.assertQuiet(c, "b")
    }

    // ------------------------------------------------------------------ c

    private fun cloneInsideTheSelection(u: Qa17ArrayUi) {
        val doc = Smoke.document(400, 300, layers = 2, whiteBottom = true)
        val art = doc.layers[1]
        Canvas(art.bitmap).apply {
            drawCircle(CX, CY, R, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE })
            // The clone's source: a green disc at the centre, the same under every Rotation 4 copy.
            drawCircle(CX, CY, GREEN_R, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN })
            val red = Paint().apply { color = RED }
            for ((x, y) in INNER + OUTER) drawRect(x - HALF, y - HALF, x + HALF, y + HALF, red)
        }
        u.editor(doc)
        val c = u.c
        assertEquals(art, c.activeLayer)

        // The Magnetic lasso: four taps on the disc's rim and one on the first close it.
        u.tool(ToolId.MAGNETIC_LASSO.label)
        val lasso = c.currentTool as MagneticLassoTool
        assertTrue("the edge map", Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { lasso.edgeMapReady && lasso.idle })
        for ((x, y) in listOf(CX - R to CY, CX to CY - R, CX + R to CY, CX to CY + R, CX - R to CY)) {
            u.ui.tap(x, y)
            assertTrue("traced", Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { lasso.idle })
        }
        settle()
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        val disc = c.selection ?: throw AssertionError("the lasso selected nothing")
        var area = 0.0
        for (y in 0 until disc.height) for (x in 0 until disc.width) area += disc.alphaAt(x, y) / 255.0
        assertTrue("the disc ($area px of ${PI * R * R})", abs(area - PI * R * R) < 0.04 * PI * R * R)

        // Color select: "Intersect with selection" from the mode dropdown, "Reds" from the presets popup.
        u.tool(ToolId.COLOR_SELECT.label)
        val cs = c.currentTool as ColorSelectTool
        assertTrue("the proxy and a preview", Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { cs.proxyReady && cs.idle && cs.preview != null })
        click(MagneticLabels18.SELECTION_MODE, exact = true)
        click("Intersect with selection", exact = true)
        assertEquals(SelectionMode.INTERSECT, cs.mode)
        val version = cs.preview!!.version
        click(ColorSelectLabels18.COLOR_RANGE, exact = true)
        click(ColorSelectLabels18.REDS, exact = true)
        assertEquals(ColorPreset.REDS, cs.settings.preset)
        assertTrue("a new preview", Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { (cs.preview?.version ?: 0) > version && cs.idle })
        settle()
        assertTrue(cs.canSelect)
        click(ColorSelectLabels18.SELECT_COLORS, exact = true)
        assertTrue(Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { cs.idle && c.busyMessage == null })
        settle()
        val sel = c.selection ?: throw AssertionError("nothing selected")
        for ((x, y) in INNER) assertEquals("the reds inside the disc at ($x, $y)", 255, sel.alphaAt(x.toInt(), y.toInt()))
        for ((x, y) in OUTER) assertEquals("the reds outside the disc at ($x, $y)", 0, sel.alphaAt(x.toInt(), y.toInt()))
        assertEquals("the blue disc", 0, sel.alphaAt(250, 110))
        assertEquals("the green centre", 0, sel.alphaAt(CX.toInt(), CY.toInt()))
        assertEquals("the white", 0, sel.alphaAt(380, 150))

        // The Clone stamp under a Rotation 4 ruler: one stroke across the right red square and past
        // it on both sides, onto the blue of the disc, which the selection leaves out.
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 4))
        u.tool(ToolId.CLONE.label)
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        c.updatePreset(ToolId.CLONE, pen.copy(size = 16f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f, taperStart = 0f, taperEnd = 0f))
        val clone = c.tools.getValue(ToolId.CLONE) as CloneTool
        // The source 50 px left of the stroke's start: the green centre, for every rotated copy
        // (copy k reads its own rotated source spot, D1, and the green disc is the same under each).
        clone.setSource(Vec2(STROKE_X0 - CLONE_DX, CY))
        assertEquals("four rotated copies", 4, ObjectCopies.mapsAt(c, STROKE_X0, CY).size)
        settle()
        val before = pixels(art.bitmap)
        // Not vacuous: the stroke's end reaches unselected blue in every rotated place, whose source
        // is green, so a clone that ignored the selection would paint it green.
        val probes = rotated4(STROKE_X1, CY)
        for ((x, y) in probes) {
            assertEquals("unselected at ($x, $y)", 0, sel.alphaAt(x, y))
            assertEquals("blue at ($x, $y) before", BLUE, art.bitmap.getPixel(x, y))
        }
        for ((x, y) in rotated4(STROKE_X1 - CLONE_DX, CY)) assertEquals("the green source at ($x, $y)", GREEN, art.bitmap.getPixel(x, y))
        val steps = c.undoManager.undoCount
        Fingers18.stroke(u.s, STROKE_X0, CY, STROKE_X1, CY)
        assertTrue(Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { !c.currentTool.hasPendingWork && c.busyMessage == null })
        settle()
        assertEquals("the stroke: one step", steps + 1, c.undoManager.undoCount)
        val after = pixels(art.bitmap)
        val changed = after.indices.filter { after[it] != before[it] }
        assertTrue("the stroke changed pixels", changed.isNotEmpty())
        for (i in changed) {
            val x = i % 400
            val y = i / 400
            if (sel.alphaAt(x, y) == 0) fail("($x, $y) changed outside the selection: ${hex(before[i])} -> ${hex(after[i])}")
        }
        for ((x, y) in INNER) {
            val n = changed.count { i -> abs(i % 400 - x) <= HALF && abs(i / 400 - y) <= HALF }
            assertTrue("the rotated copy at ($x, $y) changed $n px", n >= 40)
            val got = art.bitmap.getPixel(x.toInt(), y.toInt())
            assertTrue("the rotated copy at ($x, $y) cloned the green centre: ${hex(got)}", near(GREEN, got))
        }
        for ((x, y) in probes) assertEquals("the unselected blue at ($x, $y) under the stroke", BLUE, art.bitmap.getPixel(x, y))
        for ((x, y) in OUTER) assertEquals("the red outside the disc", RED, art.bitmap.getPixel(x.toInt(), y.toInt()))
        assertEquals("the blue disc", BLUE, art.bitmap.getPixel(250, 110))
        Smoke.assertQuiet(c, "c")
    }

    // ------------------------------------------------------------------ d

    private fun fringedArrayCopies(u: Qa17ArrayUi) {
        val doc = Smoke.document(400, 300, layers = 2, whiteBottom = true)
        val art = doc.layers[1]
        art.bitmap.eraseColor(WHITE)
        Canvas(art.bitmap).drawRect(50f, 50f, 80f, 80f, Paint().apply { color = BLACK })
        u.editor(doc)
        val c = u.c
        val original = art.bitmap.copy(Bitmap.Config.ARGB_8888, false)

        // Chromatic aberration with the "Red/Cyan" chip, applied: ONE step, the filter's own result.
        val moving = FilterRegistry.byId("art.chromatic_aberration_moving") ?: throw AssertionError("no Chromatic aberration")
        c.startFilter(moving)
        val session = c.filterSession ?: throw AssertionError("no filter session (${c.message})")
        Fx.awaitPreview(session, "chromatic aberration")
        settle()
        val chip = RobolectricUi.elements().lastOrNull { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == V18Tags.FRINGE_PREFIX + FringeLabels18.RED_CYAN }
            ?: throw AssertionError("no \"${FringeLabels18.RED_CYAN}\" chip; shown: ${SmokeUi.shown().take(80)}")
        chip.tap()
        settle()
        assertArrayEquals(intArrayOf(FringeColors.RED, FringeColors.NONE, FringeColors.CYAN), session.values.colors(FringeColors.KEY))
        Fx.awaitPreview(session, "red/cyan")
        val values = session.values.copy()
        val steps = c.undoManager.undoCount
        click("Apply filter", exact = true)
        Fx.waitUntil("the apply") { session.isClosed && c.busyMessage == null }
        settle()
        assertNull(c.filterSession)
        assertEquals("the filter: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(moving.name, c.undoManager.undoLabel)
        val fringed = pixels(art.bitmap)
        val expected = Fx.roundTrip(Fx.direct(moving, BitmapUtils.toPixelBuffer(original), values, 1f, c.doc.dpi))
        assertTrue("the applied pixels are the filter's Red/Cyan result", expected.contentEquals(fringed))
        val rgb = Fx.roundTrip(Fx.direct(moving, BitmapUtils.toPixelBuffer(original), values.copy().set(FringeColors.KEY, FringeColors.rgb), 1f, c.doc.dpi))
        assertFalse("Red/Cyan is not RGB", rgb.contentEquals(fringed))
        val plain = pixels(original)
        val fringe = (SRC_L until SRC_R).sumOf { x -> (SRC_T until SRC_B).count { y -> fringed[y * 400 + x] != plain[y * 400 + x] } }
        assertTrue("fringes inside the source area ($fringe px)", fringe >= 20)

        // "Array from selection" on that area, then the Look tab's "Sunset".
        c.setSelection(u.rectSelection(SRC_L.toFloat(), SRC_T.toFloat(), SRC_R.toFloat(), SRC_B.toFloat()), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("Array from selection")
        c.setSelection(null, recordUndo = false)
        settle(4)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val layer = c.activeLayer
        assertNotNull("an array layer", layer.array?.pixels)
        sunsetLook(u, layer)

        // Each copy: the fringed source pixels, tinted by its own color.
        val spec = layer.array!!.spec
        val instances = ArrayInstances.of(spec, ArrayDraw.sourceBounds(layer.dataSnapshot())!!)
        assertEquals(spec.count, instances.size)
        var tintedFringe = 0
        for (inst in instances) {
            val m = inst.matrix
            assertTrue("copy ${inst.k}: a translation ${m.toList()}", m[0] == 1f && m[1] == 0f && m[3] == 0f && m[4] == 1f)
            val dx = m[2].roundToInt()
            val dy = m[5].roundToInt()
            assertTrue("copy ${inst.k}: whole pixels", abs(m[2] - dx) < 1e-3f && abs(m[5] - dy) < 1e-3f)
            for (y in SRC_T until SRC_B) for (x in SRC_L until SRC_R) {
                val f = fringed[y * 400 + x]
                val look = inst.look
                val want = if (look == null) f else ArrayTint.apply(f, look.tintColor, look.tintStrength)
                val got = layer.bitmap.getPixel(x + dx, y + dy)
                if (!near(want, got)) fail("copy ${inst.k} at (${x + dx}, ${y + dy}) from ($x, $y): expected ${hex(want)} (fringed ${hex(f)}), got ${hex(got)}")
                if (look != null && f != plain[y * 400 + x]) tintedFringe++
            }
        }
        assertTrue("the tinted copies carry the fringes ($tintedFringe px)", tintedFringe >= 2 * 20)
        Smoke.assertQuiet(c, "d")
    }

    private companion object {
        /** A Tint colors preset of the Look tab (four stops). */
        const val SUNSET = "Sunset"

        /** The spiral's line: a light gray, so each tint shows (black stays black under any tint). */
        const val LINE = 0xFFB0B0B0.toInt()

        const val RED = 0xFFE01010.toInt()
        const val BLUE = 0xFF1030D0.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        /** The disc of scenario c: the canvas centre, radius 100. */
        const val CX = 200f
        const val CY = 150f
        const val R = 100f

        /** Half the side of a red square. */
        const val HALF = 12f

        const val GREEN = 0xFF20A040.toInt()

        /** The green disc at the centre of scenario c (clear of the red squares, 38 px out). */
        const val GREEN_R = 34f

        /** Scenario c's clone stroke along y = CY, its source [CLONE_DX] px to the left. */
        const val STROKE_X0 = 230f
        const val STROKE_X1 = 270f
        const val CLONE_DX = 50f

        /** ([x], [y]) and its three quarter turns about the canvas centre (the Rotation 4 copies), as pixels. */
        fun rotated4(x: Float, y: Float): List<Pair<Int, Int>> {
            val dx = x - CX
            val dy = y - CY
            return listOf(dx to dy, -dy to dx, -dx to -dy, dy to -dx).map { (u, v) -> (CX + u).roundToInt() to (CY + v).roundToInt() }
        }

        /** The red squares inside the disc: the four places a Rotation 4 copy of one stroke reaches. */
        val INNER = listOf(250f to 150f, 200f to 200f, 150f to 150f, 200f to 100f)

        /** Red squares outside the disc. */
        val OUTER = listOf(40f to 40f, 360f to 260f, 40f to 260f)

        /** The area scenario d makes an array of. */
        const val SRC_L = 30
        const val SRC_T = 30
        const val SRC_R = 100
        const val SRC_B = 100
    }
}
