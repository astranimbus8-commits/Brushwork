package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapePoints
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * v1.7 QA (compat cluster): ONE artwork holding every v1.7 datum, made through the editor's own
 * operations, and the comparisons the round-trip tests make. 400 × 300 (≤ 512).
 *
 * Bottom first: Background; the four arrays (raster in "Edit source pixels", text, shape, vector)
 * with their sources; a kerned text ("Font kerning" off); a shape with per-point roundness; a
 * vector layer of Path-tool curves with sharp points and per-point widths, one "Fill", one
 * "Both", one "Stroke"; a text turned 30° and scaled 150 % and a skewed shape (Transform tool,
 * kept as data); a Rotation × 6 vector stroke; and on top an isolated folder "Outer" (80 %,
 * Multiply) holding "Under" and a closed pass-through folder "Inner" CLIPPED onto it, which
 * holds "Painted" and a clipped "Clip". Two saved selections; symmetry Rotation × 6 stays on.
 */
internal object Qa17CompatArtwork {
    const val W = 400
    const val H = 300

    val symmetry = SymmetrySettings(SymmetryType.ROTATION, divisions = 6)

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    fun picture(doc: Document): IntArray {
        val flat = Compositor(doc) { null }.renderFlattened()
        try { return pixels(flat) } finally { flat.recycle() }
    }

    fun settle(c: EditorController) {
        Smoke.pumpUntil { !c.vectors.isRendering && c.busyMessage == null && c.pendingSavedSelections.isEmpty() }
        Smoke.pump(40)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun spline(points: List<VSplinePoint>, fill: VPaint?, stroke: VStrokeStyle?): VPath {
        val s = VSpline(points, order = 4)
        return VPath(0, subpaths = listOf(SplineBezier.toSubpath(s)), fill = fill, stroke = stroke, spline = s)
    }

    private fun transform(c: EditorController, layer: Layer, lifted: TransformTool.Lifted, edit: (TransformTool) -> Unit) {
        c.selectLayer(layer)
        c.selectTool(ToolId.TRANSFORM)
        idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.snapToObjects = false
        tool.start()
        assertEquals("${layer.name}: kept as data", lifted, tool.lifted)
        edit(tool)
        tool.commit()
        c.selectTool(ToolId.BRUSH)
        idle()
    }

    /**
     * Builds the artwork in a new controller; returns it with its layers by role. Without
     * [folders] it has every other datum (a project v1.6 still opens: format 1).
     */
    fun build(context: android.content.Context, id: String = "qa17-compat-rt", folders: Boolean = true): Pair<EditorController, Map<String, Layer>> {
        val doc = Document(id, "Compat", W, H, 300f)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(W, H).also { it.eraseColor(0xFFF4EEE0.toInt()) })
        doc.activeLayerIndex = 0
        val c = Smoke.controller(context, doc)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        val bg = doc.layers[0]
        val made = LinkedHashMap<String, Layer>()
        if (folders) made += folders(c, bg)
        others(c, bg, made)
        return c to made
    }

    /** Outer (isolated, 80 %, Multiply) > Under, Inner (clipped onto Under, closed) > Painted, Clip (clipped). */
    private fun folders(c: EditorController, bg: Layer): Map<String, Layer> {
        val doc = c.doc
        c.selectLayer(bg)
        val under = c.addLayer("Under")!!
        Canvas(under.bitmap).drawCircle(320f, 210f, 60f, Paint().apply { color = 0xFF3366AA.toInt() })
        val outer = c.putInNewFolder(under)!!
        c.selectLayer(under)
        val painted = c.addLayer("Painted")!!
        Canvas(painted.bitmap).drawRect(280f, 170f, 390f, 250f, Paint().apply { color = 0xFF22AA44.toInt() })
        val inner = c.putInNewFolder(painted)!!
        c.selectLayer(painted)
        val clip = c.addLayer("Clip")!!
        Canvas(clip.bitmap).drawRect(250f, 200f, 360f, 290f, Paint().apply { color = 0xC0E0A020.toInt() })
        c.setLayerProps(clip, clip.props().copy(clipping = true))
        c.setLayerProps(inner, inner.props().copy(clipping = true, opacity = 0.9f))
        c.setFolderOpen(inner, false)
        c.setFolderPassThrough(outer, false)
        c.setLayerProps(outer, outer.props().copy(opacity = 0.8f, blendMode = LayerBlendMode.MULTIPLY))
        assertEquals(listOf(bg, under, painted, clip, inner, outer), doc.layers)
        assertEquals(listOf(Layer.ROOT_ID, outer.id, inner.id, inner.id, outer.id, Layer.ROOT_ID), doc.layers.map { it.parentId })
        assertNull(LayerTree.check(doc.layers))
        return mapOf("outer" to outer, "inner" to inner, "under" to under, "painted" to painted, "clip" to clip)
    }

    private fun others(c: EditorController, bg: Layer, made: MutableMap<String, Layer>) {
        val doc = c.doc
        val red = Paint().apply { color = 0xFFDD2211.toInt() }
        // --- Arrays, one per kind (new layers go right above Background, at the top level).
        c.selectLayer(bg)
        val src = c.addLayer("Source")!!
        Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, red)
        c.selectLayer(src)
        c.setSelection(Selection.fromPath(Path().apply { addRect(10f, 20f, 70f, 80f, Path.Direction.CW) }, W, H, antiAlias = false), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        c.setSelection(null, recordUndo = false)
        val rasterArray = c.activeLayer
        assertTrue(ArrayOps.edit(c, rasterArray, ArraySpec(mode = ArrayMode.CIRCLE, count = 5, sweepDeg = 180f)))
        assertTrue(ArrayOps.editSource(c, rasterArray))
        made["arrayPixels"] = rasterArray

        val ab = TextItem("Ab", spec = TextSpec(sizePx = 28f, color = 0xFF000000.toInt()), cx = 150f, cy = 40f)
        val textArray = c.addLayerWithContent("Text array", "Add text", textData = TextCodec.encode(ab)) { cv -> TextRenderer.drawItem(cv, ab, TextRenderer.prepare(ab), null) }!!
        assertTrue(c.arrayWholeLayer(textArray))
        assertTrue(ArrayOps.edit(c, textArray, ArraySpec(count = 4, relativeX = 0.2f, relativeY = 1f)))
        made["arrayText"] = textArray

        val sq = ShapeObject(ShapeType.RECTANGLE, cx = 230f, cy = 60f, w = 30f, h = 24f, style = ShapeStyle.FILL)
        val shapeArray = c.addLayerWithContent("Shape array", "Add shape", shapeData = ShapeCodec.encode(sq), draw = ArraySources.shapeDraw(sq, ColorMode.RGB, W, H))!!
        assertTrue(c.arrayWholeLayer(shapeArray))
        assertTrue(ArrayOps.edit(c, shapeArray, ArraySpec(mode = ArrayMode.TRANSFORM, count = 4, moveX = 20f, moveY = 0f, turnDeg = 20f, scale = 0.9f)))
        made["arrayShape"] = shapeArray

        val boxes = c.addVectorLayer("Vector source")!!
        fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
            fill = VPaint.Solid(0xFF2244CC.toInt()),
        )
        c.vectors.update(boxes, VectorContent.EMPTY.plus(listOf(box(30f, 120f, 50f, 140f), box(120f, 120f, 140f, 140f))).first, "Add")
        settle(c)
        assertTrue(c.arrayFromObjects(setOf(boxes.vector!!.objects.first().id)))
        settle(c)
        val vectorArray = c.activeLayer
        val guide = VSubpath(listOf(VAnchor(40f, 130f), VAnchor(100f, 170f), VAnchor(160f, 130f)))
        assertTrue(ArrayOps.edit(c, vectorArray, ArraySpec(mode = ArrayMode.CURVE, count = 4, guide = guide)))
        settle(c)
        made["arrayVector"] = vectorArray
        made["vectorSource"] = boxes

        // --- Kerning: two kerns, the font's own kerning off.
        val kerned = TextItem(
            "AVATAR WAVE",
            spec = TextSpec(sizePx = 30f, color = 0xFF202020.toInt(), fontKerning = false),
            cx = 300f, cy = 30f,
            kerns = listOf(TextKern(0, -150), TextKern(4, 220)),
        )
        made["kerned"] = c.addLayerWithContent("Kerned", "Add text", textData = TextCodec.encode(kerned)) { cv -> TextRenderer.drawItem(cv, kerned, TextRenderer.prepare(kerned), null) }!!

        // --- Per-point roundness: a point rectangle, two corners with their own radius.
        val base = ShapeObject(ShapeType.RECTANGLE, cx = 70f, cy = 230f, w = 90f, h = 60f, style = ShapeStyle.FILL, fillColor = 0xFF8844CC.toInt())
        val pts = ShapePoints.fromRegular(base.type, base.outlineParams).mapIndexed { i, p ->
            when (i) { 0 -> p.copy(radius = 12f); 2 -> p.copy(radius = 30f); else -> p }
        }
        val rounded = base.copy(points = pts)
        made["rounded"] = c.addLayerWithContent("Rounded", "Add shape", shapeData = ShapeCodec.encode(rounded), draw = ArraySources.shapeDraw(rounded, ColorMode.RGB, W, H))!!

        // --- Path-tool curves: sharp middle points, per-point widths; Fill, Both and Stroke.
        val paths = c.addVectorLayer("Paths")!!
        val fillOnly = spline(
            listOf(VSplinePoint(180f, 200f), VSplinePoint(220f, 160f), VSplinePoint(260f, 200f, sharp = true), VSplinePoint(240f, 250f), VSplinePoint(190f, 250f)),
            fill = VPaint.Solid(0xFF33AACC.toInt()), stroke = null,
        )
        val both = spline(
            listOf(VSplinePoint(170f, 100f), VSplinePoint(200f, 80f, sharp = true), VSplinePoint(240f, 110f), VSplinePoint(210f, 130f)),
            fill = VPaint.Solid(0xFFEECC33.toInt()), stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 4f),
        )
        val lens = spline(
            listOf(VSplinePoint(100f, 280f, width = 0f), VSplinePoint(160f, 260f, width = 2.5f, sharp = true), VSplinePoint(220f, 285f, width = 0f)),
            fill = null, stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 8f),
        )
        c.vectors.update(paths, VectorContent.EMPTY.plus(listOf(fillOnly, both, lens)).first, "Add")
        settle(c)
        made["paths"] = paths

        // --- Transformed text (30°, 150 %) and a skewed shape (custom points), kept as data.
        val turn = TextItem("Turn", spec = TextSpec(sizePx = 24f, color = 0xFF702020.toInt()), cx = 110f, cy = 190f)
        val turned = c.addLayerWithContent("Turned", "Add text", textData = TextCodec.encode(turn)) { cv -> TextRenderer.drawItem(cv, turn, TextRenderer.prepare(turn), null) }!!
        transform(c, turned, TransformTool.Lifted.TEXT) { it.setRotation(30.0); it.setScalePercent(150.0); it.endNumericEdit() }
        assertEquals(30f, TextCodec.decode(turned.textData)!!.rotationDeg, 0.05f)
        made["turned"] = turned
        val tilt = ShapeObject(ShapeType.RECTANGLE, cx = 330f, cy = 110f, w = 50f, h = 30f, rotation = 30f, style = ShapeStyle.FILL, fillColor = 0xFF2244CC.toInt())
        val area = Rect(0, 0, W, H)
        val skewed = c.addLayerWithContent("Skewed", "Add shape", shapeData = ShapeCodec.encode(tilt)) { cv: Canvas ->
            VectorLayerRenderer.render(cv, VectorContent(objects = listOf(VShape(0L, shape = tilt))), area, tips = TipCache(), document = area)
        }!!
        transform(c, skewed, TransformTool.Lifted.SHAPE) { it.objectScale!!.setScale(150f, null) }
        assertTrue("a skew gives custom points", ShapeCodec.decode(skewed.shapeData)!!.points != null)
        made["skewed"] = skewed

        // --- A Rotation × 6 stroke on a vector layer (the Pen: one VStroke with six maps).
        val sym = c.addVectorLayer("Symmetric")!!
        c.selectTool(ToolId.BRUSH)
        c.brush = BrushLibrary.defaultBrush
        c.color = 0xFF2060C0.toInt()
        c.updateSymmetry(symmetry)
        val stroke = List(20) { k -> val t = k / 19f; ToolPoint(230f + 40f * t, 120f + 10f * sin(t * 6f), 1f, k.toLong()) }
        c.pointerDown(stroke.first())
        for (p in stroke.subList(1, stroke.size - 1)) c.pointerMove(p)
        c.pointerUp(stroke.last())
        settle(c)
        assertEquals(6, (sym.vector!!.objects.single() as VStroke).copies.size)
        made["symmetric"] = sym

        // --- Two saved selections.
        c.setSelection(Selection.fromPath(Path().apply { addRect(40f, 40f, 200f, 160f, Path.Direction.CW) }, W, H, antiAlias = false), recordUndo = false)
        assertTrue(c.saveSelection())
        c.setSelection(Selection.fromPath(Path().apply { addOval(150f, 100f, 330f, 260f, Path.Direction.CW) }, W, H, antiAlias = true), recordUndo = false)
        assertTrue(c.saveSelection())
        settle(c)
        c.setSelection(null, recordUndo = false)
        assertEquals(2, doc.savedSelections.size)
        c.vectors.flushPending()
        settle(c)
        c.selectLayer(bg)
    }

    /** Each layer's parent as an index into the same list (-1 = top level). */
    fun tree(d: Document): List<Int> = d.layers.map { l -> d.layers.indexOfFirst { it.id == l.parentId } }

    fun maxChannelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    /**
     * Pixels that differ by more than [tol] in some channel. [tol] = [PREMUL]: the same alpha and
     * one level of premultiplied rounding where alpha is partial (a picture stored unpremultiplied).
     */
    fun differing(e: IntArray, a: IntArray, tol: Int): Int {
        assertEquals(e.size, a.size)
        var n = 0
        for (i in e.indices) {
            if (e[i] == a[i]) continue
            if (tol == PREMUL) {
                val ea = e[i] ushr 24
                if (ea != a[i] ushr 24 || ea == 0 || ea == 255) { n++; continue }
                for (s in intArrayOf(16, 8, 0)) if (abs(((e[i] shr s) and 0xFF) - ((a[i] shr s) and 0xFF)) > 255 / ea + 1) { n++; break }
            } else if (maxChannelDiff(e[i], a[i]) > tol) {
                n++
            }
        }
        return n
    }

    /** [differing]'s tolerance for pictures stored unpremultiplied (PNG in SVG and PDF). */
    const val PREMUL = -1

    /**
     * [actual] has [expected]'s layers: names (or, with [renamed], any), properties, tree, folder
     * specs and open states, editable data, arrays and pixels. [pixelTol] 0 = bit-identical.
     */
    fun assertSameLayers(where: String, expected: Document, actual: Document, pixelTol: Int = 0, vectorTol: Int = pixelTol) {
        assertEquals("$where: names", expected.layers.map { it.name }, actual.layers.map { it.name })
        assertEquals("$where: props", expected.layers.map { it.props() }, actual.layers.map { it.props() })
        assertEquals("$where: tree", tree(expected), tree(actual))
        assertEquals("$where: folders", expected.layers.map { it.folder }, actual.layers.map { it.folder })
        assertEquals("$where: open states", expected.layers.map { it.folderOpen }, actual.layers.map { it.folderOpen })
        assertEquals("$where: data", expected.layers.map { it.dataSnapshot().copy(array = null) }, actual.layers.map { it.dataSnapshot().copy(array = null) })
        for ((e, a) in expected.layers.zip(actual.layers)) {
            val what = "$where/${e.name}"
            assertEquals("$what: array spec", e.array?.spec, a.array?.spec)
            assertEquals("$what: array source data", e.array?.copy(pixels = null), a.array?.copy(pixels = null))
            val ep = e.array?.pixels
            val ap = a.array?.pixels
            assertEquals("$what: source pixels present", ep != null, ap != null)
            if (ep != null && ap != null) {
                assertEquals("$what: source left", ep.left, ap.left)
                assertEquals("$what: source top", ep.top, ap.top)
                assertEquals("$what: source pixels", 0, differing(pixels(ep.bitmap), pixels(ap.bitmap), pixelTol))
            }
            if (e.isFolder) {
                assertTrue("$what: a folder", a.isFolder)
                assertTrue("$what: the folder bitmap", a.bitmap === Layer.FOLDER_BITMAP)
                continue
            }
            val tol = if (e.isVectorLayer && e.array == null) vectorTol else pixelTol
            if (tol == 0) assertArrayEquals("$what: pixels", pixels(e.bitmap), pixels(a.bitmap))
            assertEquals("$what: pixels off by more than $tol", 0, differing(pixels(e.bitmap), pixels(a.bitmap), tol))
        }
    }

    fun assertSameSelections(where: String, expected: Document, actual: Document) {
        assertEquals("$where: saved selections", expected.savedSelections.map { it.id to it.name }, actual.savedSelections.map { it.id to it.name })
        for ((e, a) in expected.savedSelections.zip(actual.savedSelections)) {
            assertEquals("$where/${e.name}: bounds", e.bounds, a.bounds)
            assertEquals("$where/${e.name}: revision", e.revision, a.revision)
            assertArrayEquals("$where/${e.name}: rows", e.packed, a.packed)
        }
        assertEquals("$where: next selection id", expected.nextSelectionId, actual.nextSelectionId)
    }

    fun assertNoV17Pending(c: EditorController) {
        assertFalse(c.isInteracting)
        assertTrue(c.pendingSavedSelections.isEmpty())
    }
}
