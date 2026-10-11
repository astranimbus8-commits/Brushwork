package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa17.Qa17CompatArtwork
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.CurveGenerator
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.select.ObjectActions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * v1.8 QA (compat cluster): ONE artwork holding every v1.8 datum, made through the editor's own
 * operations where the editor has one, and the checks the round-trip tests share. 400 × 300.
 *
 * Bottom first:
 * - "Background": cream, a black disc (the Magnetic lasso's edge) and a blue block (Color select).
 * - "Fringes": a square under Chromatic aberration (Moving) with Magenta / none / Yellow fringes;
 *   "Static": a band under Glitch with Red / Green / none fringes and green static. Both are
 *   pixels: the colors live only in the filter session (§4.3).
 * - Four live arrays with a look (raster from a selection, text, shape, vector), every field of
 *   the look set, each with another Pace (Even, Speeds up, Slows down, S-curve).
 * - "Mirrored": a Bézier curve (Mirror ruler), a polyline (Rotation × 4), a Path-tool path
 *   (Kaleidoscope × 3) and a star (Mirror) with live copies; "Mirrored shape": a shape layer's
 *   ellipse with Rotation × 4 copies.
 * - "Detached": a box mirrored, its copies detached (the Object bar's Detach).
 * - "Generated": every generator kind, Curve and Path tool, one with copies too.
 * - "Cogs": a cog, a holed cog and a holed cog with copies; "Cog" and "Holed cog": shape layers.
 * - Saved selections "Color select" (the blue) and "Magnetic lasso" (the disc). The symmetry
 *   ruler stays on (Mirror).
 */
internal object Qa18CompatArtwork {
    const val W = 400
    const val H = 300
    const val CREAM = 0xFFF4EEE0.toInt()
    const val BLUE = 0xFF1030D0.toInt()

    val symmetry = SymmetrySettings(SymmetryType.MIRROR)
    val MIRROR: CopyMaps = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(SymmetryType.MIRROR), W, H))
    val ROTATION_4: CopyMaps = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(SymmetryType.ROTATION, divisions = 4), W, H))
    val KALEIDO_3: CopyMaps = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(SymmetryType.KALEIDOSCOPE, divisions = 3), W, H))

    /** Every part of a look: a fade, a size and a turn gradient, a Pace, a three-stop tint. */
    fun look(easing: ArrayEasing) = ArrayLook(
        opacityStart = 0.9f, opacityEnd = 0.4f, sizeStart = 0.9f, sizeEnd = 1.3f, turnStart = -10f, turnEnd = 25f,
        easing = easing,
        tint = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(0.5f, 0xFF00C040.toInt()), VStop(1f, 0xFF0000FF.toInt())),
        tintStrength = 0.75f,
    )

    val COG = ShapeObject(
        ShapeType.COG, cx = 0f, cy = 0f, w = 70f, h = 70f, rotation = -15f, style = ShapeStyle.STROKE_FILL,
        strokeWidth = 3f, fillColor = 0xFFC08020.toInt(), fillFollowsColor = false,
        cogTeeth = 14, cogDepth = 0.3f, cogToothWidth = 0.45f, cogTaper = 0.25f,
    )

    /** The generated curves: kind, tool, generator. Every [CurveGenKind] once, both tools. */
    val GENERATED: List<Triple<CurveGenKind, CurveKind, CurveGenerator>> = listOf(
        Triple(CurveGenKind.ARCHIMEDEAN, CurveKind.CURVE, CurveGenerator(CurveGenKind.ARCHIMEDEAN, cx = 60f, cy = 60f, size = 40f, turns = 2.5f, inner = 0.2f)),
        Triple(CurveGenKind.LOGARITHMIC, CurveKind.PATH, CurveGenerator(CurveGenKind.LOGARITHMIC, cx = 160f, cy = 60f, size = 40f, turns = 2f, growth = 1.8f, clockwise = true)),
        Triple(CurveGenKind.GOLDEN, CurveKind.CURVE, CurveGenerator(CurveGenKind.GOLDEN, cx = 260f, cy = 60f, size = 40f, turns = 1.5f, angleDeg = 30f)),
        Triple(CurveGenKind.FERMAT, CurveKind.PATH, CurveGenerator(CurveGenKind.FERMAT, cx = 340f, cy = 60f, size = 40f, turns = 2f, density = 10)),
        Triple(CurveGenKind.SINE, CurveKind.CURVE, CurveGenerator(CurveGenKind.SINE, cx = 120f, cy = 170f, size = 180f, amplitude = 20f, waves = 3.5f, angleDeg = 10f)),
        Triple(CurveGenKind.ROSE, CurveKind.PATH, CurveGenerator(CurveGenKind.ROSE, cx = 300f, cy = 180f, size = 50f, petalsN = 5, petalsD = 3, density = 12)),
    )

    /** A generated path as the Curve tool ([CurveKind.CURVE]) or the Path tool stores it. */
    fun generated(g: CurveGenerator, kind: CurveKind, color: Int, copies: CopyMaps = CopyMaps.NONE): VPath {
        val b = CurveGenerators.build(g, kind)
        val stroke = VStrokeStyle(color = color, width = 3f)
        val p = if (kind == CurveKind.PATH) {
            val sp = b.spline!!.sanitized()
            VPath(0, subpaths = listOf(SplineBezier.toSubpath(sp)), stroke = stroke, spline = sp, generator = g, copies = copies)
        } else {
            VPath(0, subpaths = listOf(b.subpath), stroke = stroke, generator = g, copies = copies)
        }
        assertTrue("${g.kind} ($kind): the geometry is the generator's (I16)", CurveGenerators.matches(p))
        return p
    }

    private fun idle() = Smoke.pump(20)

    fun settle(c: EditorController) {
        c.vectors.flushPending()
        Qa17CompatArtwork.settle(c)
        Smoke.pumpUntil { c.doc.layers.none { c.arrayRenders.isPendingOn(it) } }
        Smoke.pump(20)
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, W, H, antiAlias = false)

    private fun vectorLayer(c: EditorController, name: String, objects: List<VObject>): Layer {
        val l = c.addVectorLayer(name)!!
        c.vectors.update(l, VectorContent.EMPTY.plus(objects).first, "Add")
        settle(c)
        return l
    }

    private fun shapeLayer(c: EditorController, name: String, o: ShapeObject): Layer =
        c.addLayerWithContent(name, "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, W, H))!!

    /** Builds the artwork in a new controller; returns it with its layers by role. */
    fun build(context: android.content.Context, id: String = "qa18-compat-rt"): Pair<EditorController, Map<String, Layer>> {
        val doc = Document(id, "Compat 18", W, H, 300f)
        val bgBitmap = BitmapUtils.createLayerBitmap(W, H).also { it.eraseColor(CREAM) }
        Canvas(bgBitmap).apply {
            drawCircle(90f, 210f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
            drawRect(300f, 200f, 380f, 280f, Paint().apply { color = BLUE })
        }
        doc.layers += Layer(doc.newLayerId(), "Background", bgBitmap)
        doc.activeLayerIndex = 0
        val c = Smoke.controller(context, doc)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        val bg = doc.layers[0]
        val made = LinkedHashMap<String, Layer>()
        made["background"] = bg

        fringes(c, made)
        arrays(c, made)
        copies(c, made)
        generatedAndCogs(c, made)
        selections(c, bg)

        c.updateSymmetry(symmetry)
        c.selectTool(ToolId.BRUSH)
        c.selectLayer(bg)
        settle(c)
        return c to made
    }

    /** Chromatic aberration and Glitch applied with fringe colors and a static color: pixels. */
    private fun fringes(c: EditorController, made: MutableMap<String, Layer>) {
        val fr = c.addLayer("Fringes")!!
        Canvas(fr.bitmap).drawRect(30f, 30f, 90f, 90f, Paint().apply { color = 0xFF303030.toInt() })
        c.selectLayer(fr)
        val moving = FilterRegistry.byId("art.chromatic_aberration_moving")!!
        assertTrue("Chromatic aberration is no adjustment layer", !moving.isAdjustmentCapable)
        c.startFilter(moving)
        val s1 = c.filterSession!!
        s1.update("distance", 6f)
        s1.update(FringeColors.KEY, intArrayOf(FringeColors.MAGENTA, FringeColors.NONE, FringeColors.YELLOW))
        Fx.applyAndWait(s1)
        assertNull("applied", c.filterSession)
        made["fringes"] = fr

        val st = c.addLayer("Static")!!
        Canvas(st.bitmap).drawRect(110f, 20f, 290f, 100f, Paint().apply { color = 0xFF6060A0.toInt() })
        c.selectLayer(st)
        val glitch = FilterRegistry.byId("art.glitch")!!
        assertTrue("Glitch is no adjustment layer", !glitch.isAdjustmentCapable)
        c.startFilter(glitch)
        val s2 = c.filterSession!!
        s2.update("noise", 60f)
        s2.update(FringeColors.KEY, intArrayOf(FringeColors.RED, FringeColors.GREEN, FringeColors.NONE))
        s2.update(GlitchFilter.STATIC_COLOR_KEY, 0xFF20C040.toInt())
        Fx.applyAndWait(s2)
        assertNull("applied", c.filterSession)
        made["static"] = st
        // The colors were the session's only: the next session starts at the defaults.
        c.startFilter(glitch)
        val again = c.filterSession!!
        assertEquals(FringeColors.WHITE, again.values.raw(GlitchFilter.STATIC_COLOR_KEY))
        assertArrayEquals(glitch.defaultValues().colors(FringeColors.KEY), again.values.colors(FringeColors.KEY))
        again.cancel()
        idle()
    }

    /** The four kinds of array, each with a full look and its own Pace. */
    private fun arrays(c: EditorController, made: MutableMap<String, Layer>) {
        val src = c.addLayer("Source")!!
        Canvas(src.bitmap).drawRect(20f, 120f, 50f, 150f, Paint().apply { color = 0xFFDD2211.toInt() })
        c.selectLayer(src)
        c.setSelection(rect(15f, 115f, 55f, 155f), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        c.setSelection(null, recordUndo = false)
        settle(c)
        val px = c.activeLayer
        assertTrue(ArrayOps.edit(c, px, px.array!!.spec.copy(mode = ArrayMode.CIRCLE, count = 5, sweepDeg = 180f, look = look(ArrayEasing.LINEAR))))
        made["arrayPixels"] = px

        val ab = TextItem("Ab", spec = TextSpec(sizePx = 26f, color = 0xFF000000.toInt()), cx = 150f, cy = 130f)
        val text = c.addLayerWithContent("Text array", "Add text", textData = TextCodec.encode(ab)) { cv -> TextRenderer.drawItem(cv, ab, TextRenderer.prepare(ab), null) }!!
        assertTrue(c.arrayWholeLayer(text))
        settle(c)
        assertTrue(ArrayOps.edit(c, text, text.array!!.spec.copy(count = 4, relativeX = 0.2f, relativeY = 1f, look = look(ArrayEasing.EASE_IN))))
        made["arrayText"] = text

        val sq = ShapeObject(ShapeType.RECTANGLE, cx = 230f, cy = 130f, w = 30f, h = 24f, style = ShapeStyle.FILL)
        val shape = shapeLayer(c, "Shape array", sq)
        assertTrue(c.arrayWholeLayer(shape))
        settle(c)
        assertTrue(ArrayOps.edit(c, shape, shape.array!!.spec.copy(mode = ArrayMode.TRANSFORM, count = 4, moveX = 20f, moveY = 0f, turnDeg = 20f, scale = 0.9f, look = look(ArrayEasing.EASE_OUT))))
        made["arrayShape"] = shape

        val box = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(300f, 120f, true), VAnchor(325f, 120f, true), VAnchor(325f, 145f, true), VAnchor(300f, 145f, true)), closed = true)),
            fill = VPaint.Solid(0xFF2244CC.toInt()),
            stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 2f),
        )
        val vec = vectorLayer(c, "Vector array", listOf(box))
        assertTrue(c.arrayWholeLayer(vec))
        settle(c)
        val guide = VSubpath(listOf(VAnchor(310f, 130f), VAnchor(350f, 170f), VAnchor(390f, 130f)))
        assertTrue(ArrayOps.edit(c, vec, vec.array!!.spec.copy(mode = ArrayMode.CURVE, count = 4, guide = guide, look = look(ArrayEasing.EASE_IN_OUT))))
        made["arrayVector"] = vec
        settle(c)
        for (k in listOf("arrayPixels", "arrayText", "arrayShape", "arrayVector")) {
            val l = made.getValue(k)
            assertNotNull("$k: a look", l.array!!.spec.look)
            assertTrue("$k: shows", Qa17CompatArtwork.pixels(l.bitmap).any { it != 0 })
        }
    }

    /** Live copies on paths, a polyline, a spline, a star and a shape layer; copies detached. */
    private fun copies(c: EditorController, made: MutableMap<String, Layer>) {
        val black = VStrokeStyle(color = 0xFF000000.toInt(), width = 3f)
        val curve = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(30f, 30f, outX = 20f, outY = -10f), VAnchor(90f, 60f, inX = -15f, inY = -10f)))), stroke = black, copies = MIRROR)
        val poly = VPath(
            0, subpaths = listOf(VSubpath(listOf(VAnchor(140f, 40f, true), VAnchor(170f, 70f, true), VAnchor(150f, 95f, true)))),
            tension = 1f, polyline = true, stroke = black.copy(color = 0xFF883300.toInt()), copies = ROTATION_4,
        )
        val sp = VSpline(listOf(VSplinePoint(220f, 30f), VSplinePoint(250f, 60f), VSplinePoint(230f, 90f, sharp = true), VSplinePoint(260f, 100f)), order = 4)
        val spline = VPath(0, subpaths = listOf(SplineBezier.toSubpath(sp)), stroke = black.copy(color = 0xFF006060.toInt()), spline = sp, copies = KALEIDO_3)
        val star = VShape(0, shape = ShapeObject(ShapeType.STAR, cx = 60f, cy = 250f, w = 40f, h = 40f, style = ShapeStyle.FILL, fillColor = 0xFFAA2288.toInt(), fillFollowsColor = false, copies = MIRROR))
        val mirrored = vectorLayer(c, "Mirrored", listOf(curve, poly, spline, star))
        assertEquals(4, mirrored.vector!!.objects.count { ObjectCopies.hasCopies(it) })
        made["mirrored"] = mirrored

        val ellipse = ShapeObject(ShapeType.ELLIPSE, cx = 150f, cy = 200f, w = 40f, h = 20f, rotation = 20f, style = ShapeStyle.STROKE, strokeWidth = 4f, strokeColor = 0xFF2266AA.toInt(), copies = ROTATION_4)
        made["mirroredShape"] = shapeLayer(c, "Mirrored shape", ellipse)
        settle(c)

        val box = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(120f, 230f, true), VAnchor(160f, 230f, true), VAnchor(160f, 270f, true), VAnchor(120f, 270f, true)), closed = true)),
            fill = VPaint.Solid(0xFF44AA66.toInt()),
            copies = MIRROR,
        )
        val detached = vectorLayer(c, "Detached", listOf(box))
        c.selectLayer(detached)
        c.vectors.setSelection(detached, setOf(detached.vector!!.objects.single().id))
        assertTrue(ObjectActions.detachCopies(c))
        settle(c)
        c.vectors.setSelection(detached, emptySet())
        assertEquals("the box and its mirror image", 2, detached.vector!!.objects.size)
        assertTrue(detached.vector!!.objects.none { ObjectCopies.hasCopies(it) })
        made["detached"] = detached
    }

    private fun generatedAndCogs(c: EditorController, made: MutableMap<String, Layer>) {
        val colors = listOf(0xFFCC2222, 0xFF22AA22, 0xFF2222CC, 0xFFAA8800, 0xFF8800AA, 0xFF008888).map { it.toInt() }
        val paths = GENERATED.mapIndexed { i, (_, kind, g) -> generated(g, kind, colors[i]) }
        val withCopies = generated(CurveGenerator(CurveGenKind.ROSE, cx = 120f, cy = 250f, size = 30f, petalsN = 3, petalsD = 1), CurveKind.CURVE, 0xFF000000.toInt(), copies = MIRROR)
        made["generated"] = vectorLayer(c, "Generated", paths + withCopies)

        val cogs = listOf(
            VShape(0, shape = COG.copy(cx = 60f, cy = 60f)),
            VShape(0, shape = COG.copy(cx = 170f, cy = 60f, cogHole = 0.5f)),
            VShape(0, shape = COG.copy(cx = 80f, cy = 160f, w = 50f, h = 50f, cogHole = 0.4f, copies = MIRROR)),
        )
        made["cogs"] = vectorLayer(c, "Cogs", cogs)
        made["cogShape"] = shapeLayer(c, "Cog", COG.copy(cx = 250f, cy = 230f))
        made["holedCogShape"] = shapeLayer(c, "Holed cog", COG.copy(cx = 340f, cy = 230f, cogHole = 0.55f, style = ShapeStyle.FILL))
        settle(c)
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    /** "Color select" (the blue block) and "Magnetic lasso" (the disc), both saved. */
    private fun selections(c: EditorController, bg: Layer) {
        c.selectLayer(bg)
        val cs = c.tools.getValue(ToolId.COLOR_SELECT) as ColorSelectTool
        c.selectTool(ToolId.COLOR_SELECT)
        assertTrue("the color proxy", Smoke.pumpUntil { cs.proxyReady && cs.idle })
        cs.addSample(BLUE)
        cs.selectColors()
        assertTrue("color select", Smoke.pumpUntil { c.selection != null && cs.idle })
        val blue = c.selection!!
        assertEquals(255, blue.alphaAt(340, 240))
        assertEquals(0, blue.alphaAt(200, 150))
        assertTrue(c.saveSelection())
        settle(c)
        c.setSelection(null, recordUndo = false)

        val ml = c.tools.getValue(ToolId.MAGNETIC_LASSO) as MagneticLassoTool
        c.selectTool(ToolId.MAGNETIC_LASSO)
        assertTrue("the edge map", Smoke.pumpUntil { ml.edgeMapReady && ml.idle })
        val (cx, cy, r) = Triple(90f, 210f, 50f)
        c.tap(cx - r, cy); c.tap(cx, cy - r); c.tap(cx + r, cy); c.tap(cx, cy + r)
        assertTrue(Smoke.pumpUntil { ml.idle })
        c.tap(cx - r, cy)
        assertTrue(Smoke.pumpUntil { ml.idle && c.selection != null })
        val disc = c.selection!!
        assertEquals(255, disc.alphaAt(90, 210))
        assertEquals(0, disc.alphaAt(200, 150))
        assertTrue(c.saveSelection())
        settle(c)
        c.setSelection(null, recordUndo = false)
        assertEquals(2, c.doc.savedSelections.size)
    }
}
