package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.WrapFixtures.LOREM
import com.brushwork.paint.tools.text.WrapFixtures.pixels
import com.brushwork.paint.tools.text.WrapFixtures.render
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Text for SVG / PDF export (v1.5 §4.10, A7's API for A8): laid-out lines land exactly on the
 * rendered text, and outlines cover the same pixels (IoU ≥ 0.97) for every kind of text.
 */
@RunWith(RobolectricTestRunner::class)
class TextExportRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val w = 500
    private val h = 400

    private fun paintFor(spec: TextSpec) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = spec.antiAlias
        isAntiAlias = spec.antiAlias
        textSize = spec.sizePx
        typeface = TextRenderer.typeface(spec)
        letterSpacing = spec.letterSpacing
        color = spec.color
    }

    /** The runs of [TextExport.lines] drawn as an exporter would (matrix, then text at x / baseline). */
    private fun drawRuns(item: TextItem): Bitmap {
        val runs = TextExport.lines(item)!!
        val b = BitmapUtils.createLayerBitmap(w, h)
        val c = Canvas(b)
        for (r in runs) {
            val v = r.paintSpec.matrix
            val m = Matrix().apply { setValues(floatArrayOf(v[0], v[2], v[4], v[1], v[3], v[5], 0f, 0f, 1f)) }
            c.save()
            c.concat(m)
            c.drawText(r.text, r.x, r.baseline, paintFor(r.paintSpec.spec))
            c.restore()
        }
        return b
    }

    /** Share of pixels whose alpha differs by more than 8 between [a] and [b]. */
    private fun mismatch(a: Bitmap, b: Bitmap): Float {
        val pa = pixels(a)
        val pb = pixels(b)
        var bad = 0
        var ink = 0
        for (i in pa.indices) {
            val x = pa[i] ushr 24
            val y = pb[i] ushr 24
            if (x > 0 || y > 0) ink++
            if (kotlin.math.abs(x - y) > 8) bad++
        }
        assertTrue("something is drawn", ink > 0)
        return bad.toFloat() / ink
    }

    @Test
    fun linesLandOnTheRenderedText() {
        val plain = TextItem(LOREM, TextSpec(sizePx = 22f, align = TextAlign.CENTER, lineSpacing = 1.3f, box = TextBoxSpec(width = 380f)), 250f, 200f, 12f)
        val runs = TextExport.lines(plain)!!
        val block = TextRenderer.prepare(plain).block!!
        val layout = block.staticLayout!!
        assertEquals(layout.lineCount, runs.size)
        for (i in runs.indices) assertEquals(layout.getLineBaseline(i) + block.inset, runs[i].baseline, 0.5f)
        assertTrue("unwrapped lines draw like the layer: ${mismatch(render(plain, w, h), drawRuns(plain))}", mismatch(render(plain, w, h), drawRuns(plain)) < 0.01f)

        // Wrapped around a picture: the runs are the wrapped lines.
        val square = WrapPolygon(listOf(150f, 260f, 260f, 150f), listOf(120f, 120f, 260f, 260f))
        val wrapped = plain.copy(rotationDeg = 0f, wrap = TextWrapSpec(sourceLayerId = 9, polygons = listOf(square), gapPx = 5f, sides = WrapSides.BOTH))
        val wruns = TextExport.lines(wrapped)!!
        val lines = TextRenderer.prepare(wrapped).block!!.wrapLines!!
        assertEquals(lines.count { it.end > it.start }, wruns.size)
        assertTrue(wruns.size > runs.size)
        assertTrue(mismatch(render(wrapped, w, h), drawRuns(wrapped)) < 0.01f)
        // Text and matrix.
        assertTrue(wruns.joinToString(" ") { it.text }.startsWith("Lorem ipsum"))
        assertEquals(6, wruns[0].paintSpec.matrix.size)
    }

    @Test
    fun linesAreNullWhereTextNeedsOutlines() {
        assertNull(TextExport.lines(TextItem("縦", TextSpec(vertical = true))))
        assertNull(TextExport.lines(TextItem("On a path", path = TextPathSpec(type = TextPathType.CIRCLE, cx = 100f, cy = 100f, radius = 60f))))
        assertEquals(emptyList<TextLineRun>(), TextExport.lines(TextItem("")))
        assertNotNull(TextExport.lines(TextItem("Plain")))
    }

    @Test
    fun boxedTextHasLinesAndItsBoxAsParts() {
        // Horizontal text in a caption box: its letters as lines (the frozen contract), its box
        // from outlineParts, drawn under them.
        val boxed = TextItem("Boxed words", TextSpec(sizePx = 48f, color = WrapFixtures.BLACK, box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(), 48f)), 250f, 200f, 8f)
        val runs = TextExport.lines(boxed)!!
        assertEquals(1, runs.size)
        val block = TextRenderer.prepare(boxed).block!!
        assertEquals(block.staticLayout!!.getLineBaseline(0) + block.inset, runs[0].baseline, 0.5f)
        assertTrue("the inset is in x", runs[0].x >= block.inset - 0.5f)
        val parts = TextExport.outlineParts(boxed)!!
        assertEquals(listOf(TextOutlinePart.Kind.BOX_FILL, TextOutlinePart.Kind.BOX_BORDER, TextOutlinePart.Kind.TEXT), parts.map { it.kind })

        // Box parts, then the runs: the layer's look.
        val b = BitmapUtils.createLayerBitmap(w, h)
        val c = Canvas(b)
        for (p in parts.filter { it.kind != TextOutlinePart.Kind.TEXT }) c.drawPath(p.path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color })
        for (r in runs) {
            val v = r.paintSpec.matrix
            c.save()
            c.concat(Matrix().apply { setValues(floatArrayOf(v[0], v[2], v[4], v[1], v[3], v[5], 0f, 0f, 1f)) })
            c.drawText(r.text, r.x, r.baseline, paintFor(r.paintSpec.spec))
            c.restore()
        }
        val ref = render(boxed, w, h)
        val pa = pixels(ref)
        val pb = pixels(b)
        var bad = 0
        var ink = 0
        for (i in pa.indices) {
            if ((pa[i] ushr 24) > 0 || (pb[i] ushr 24) > 0) ink++
            val d = maxOf(kotlin.math.abs(((pa[i] shr 16) and 0xFF) - ((pb[i] shr 16) and 0xFF)), kotlin.math.abs((pa[i] ushr 24) - (pb[i] ushr 24)))
            if (d > 24) bad++
        }
        assertTrue("box + lines draw like the layer: $bad of $ink", ink > 0 && bad.toFloat() / ink < 0.02f)
    }

    @Test
    fun outlinesAreTheLettersOnly() {
        // A white caption box with a black border and red letters outlined in blue: outlines()
        // is only the letters (filled with the text color it never paints the box over them).
        val item = TextItem(
            "Letters", TextSpec(sizePx = 80f, color = 0xFFFF0000.toInt(), strokeWidthPx = 3f, strokeColor = 0xFF0000FF.toInt(), box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(), 80f)),
            250f, 200f,
        )
        val doc = Document("x", "x", w, h)
        val layer = Layer(doc.newLayerId(), "Text", render(item, w, h)).also { it.textData = TextCodec.encode(item) }
        val letters = TextExport.outlines(doc, layer)!!
        val parts = TextExport.outlineParts(item)!!
        val text = parts.single { it.kind == TextOutlinePart.Kind.TEXT }.path
        val lb = RectF().also { letters.computeBounds(it, true) }
        val tb = RectF().also { text.computeBounds(it, true) }
        assertEquals(tb, lb)
        // The box is bigger than the letters; the coverage of everything includes it.
        val box = RectF().also { parts.first { it.kind == TextOutlinePart.Kind.BOX_FILL }.path.computeBounds(it, true) }
        assertTrue(box.width() > lb.width() && box.height() > lb.height())
        val all = RectF().also { TextExport.coverage(item)!!.computeBounds(it, true) }
        assertTrue(all.contains(box))
        // A pixel of the box between the letters is not in the letters.
        val filled = BitmapUtils.createLayerBitmap(w, h)
        Canvas(filled).drawPath(letters, Paint().apply { color = WrapFixtures.BLACK })
        assertEquals(0, filled.getPixel(box.left.toInt() + 3, box.centerY().toInt()) ushr 24)
    }

    /**
     * How well the filled outline of [item]'s text layer covers the layer's pixels (alpha ≥ 50 %):
     * (strict IoU, IoU within 1 px). Skia draws glyphs from its glyph cache, snapped to whole
     * pixels vertically and hinted, while the outline is exact: even a plain `drawText` and its
     * own `getTextPath` agree only to IoU 0.88–0.98 (thin CJK strokes worst), all along the edges
     * and never by a shift (the unshifted outline always matches best). The 1-px tolerant IoU
     * counts an ink pixel of either image as matched when the other has ink within one pixel.
     */
    private fun iou(item: TextItem, size: Int = 1000): Pair<Float, Float> {
        val doc = Document("x", "x", size, size)
        val layer = Layer(doc.newLayerId(), "Text", render(item, size, size)).also { it.textData = TextCodec.encode(item) }
        // The letters alone are all a plain text paints; a box or an outline stroke adds parts.
        val decorated = item.spec.box.hasFrame || item.spec.strokeWidthPx > 0f
        val path = if (decorated) TextExport.coverage(item)!! else TextExport.outlines(doc, layer)!!
        val filled = BitmapUtils.createLayerBitmap(size, size)
        Canvas(filled).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WrapFixtures.BLACK })
        val a = pixels(layer.bitmap).let { p -> BooleanArray(p.size) { (p[it] ushr 24) >= 128 } }
        val b = pixels(filled).let { p -> BooleanArray(p.size) { (p[it] ushr 24) >= 128 } }
        fun near(m: BooleanArray, i: Int): Boolean {
            val x = i % size
            val y = i / size
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size && m[yy * size + xx]) return true
            }
            return false
        }
        var inter = 0
        var union = 0
        var na = 0
        var nb = 0
        var aNearB = 0
        var bNearA = 0
        for (i in a.indices) {
            if (a[i] && b[i]) inter++
            if (a[i] || b[i]) union++
            if (a[i]) { na++; if (near(b, i)) aNearB++ }
            if (b[i]) { nb++; if (near(a, i)) bNearA++ }
        }
        assertTrue(union > 0)
        return inter.toFloat() / union to (aNearB + bNearA).toFloat() / (na + nb)
    }

    @Test
    fun outlinesCoverTheLayerPixels() {
        val cases = listOf(
            "horizontal, outlined, in a caption box, turned" to TextItem(
                "Hello outlines\nsecond line", TextSpec(sizePx = 60f, strokeWidthPx = 3f, strokeColor = 0xFFFF0000.toInt(), box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(), 60f)),
                500f, 500f, 20f,
            ),
            "horizontal, plain" to TextItem("Plain\nwords", TextSpec(sizePx = 180f, align = TextAlign.CENTER), 500f, 500f),
            "horizontal, small, spaced" to TextItem("Small text of a caption\nat 36 px", TextSpec(sizePx = 36f, letterSpacing = 0.1f, italic = true), 500.3f, 500.6f, 7f),
            "vertical" to TextItem("縦書AB", TextSpec(sizePx = 180f, vertical = true), 500f, 500f),
            "vertical manga" to TextItem("ラーAB12", TextSpec(sizePx = 170f, vertical = true, verticalStyle = VerticalStyle.MIXED), 500f, 500f),
            "on a circle, bent" to TextItem("Around we go", TextSpec(sizePx = 120f), 500f, 500f, path = TextPathSpec(type = TextPathType.CIRCLE, cx = 500f, cy = 500f, radius = 330f)),
            "on a curve, letters turned, outlined" to TextItem(
                "Turned", TextSpec(sizePx = 170f, strokeWidthPx = 5f),
                500f, 500f,
                path = TextPathSpec(type = TextPathType.CURVE, x1 = 60f, y1 = 700f, cx1 = 330f, cy1 = 200f, cx2 = 670f, cy2 = 800f, x2 = 940f, y2 = 300f, mode = TextPathMode.ROTATE),
            ),
            "wrapped" to TextItem(
                "Words wrap around the square", TextSpec(sizePx = 160f, box = TextBoxSpec(width = 960f)), 500f, 500f,
                wrap = TextWrapSpec(sourceLayerId = 3, polygons = listOf(WrapPolygon(listOf(420f, 580f, 580f, 420f), listOf(420f, 420f, 580f, 580f)))),
            ),
        )
        val results = cases.map { (name, item) -> name to iou(item) }
        assertTrue("IoU (strict, within 1 px): $results", results.all { (_, v) -> v.first >= 0.85f && v.second >= 0.97f })
    }

    @Test
    fun outlinePartsKeepTheirColorsInPaintingOrder() {
        val item = TextItem(
            "Parts", TextSpec(sizePx = 40f, color = 0xFF112233.toInt(), strokeWidthPx = 2f, strokeColor = 0xFF445566.toInt(),
                box = TextBoxPreset.CAPTION.applyTo(TextBoxSpec(), 40f).copy(fillColor = 0xFFFFEE00.toInt(), borderColor = 0xFF0000FF.toInt())),
            250f, 200f,
        )
        val parts = TextExport.outlineParts(item)!!
        assertEquals(
            listOf(TextOutlinePart.Kind.BOX_FILL, TextOutlinePart.Kind.BOX_BORDER, TextOutlinePart.Kind.TEXT_OUTLINE, TextOutlinePart.Kind.TEXT),
            parts.map { it.kind },
        )
        assertEquals(listOf(0xFFFFEE00.toInt(), 0xFF0000FF.toInt(), 0xFF445566.toInt(), 0xFF112233.toInt()), parts.map { it.color })
        assertTrue(parts.none { it.path.isEmpty })
        assertNull(TextExport.outlineParts(TextItem("")))
        // Not a text layer: nothing.
        val doc = Document("x", "x", 10, 10)
        assertNull(TextExport.outlines(doc, Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(10, 10))))
    }

    @Test
    fun pathUnionWorksOnThisSkia() {
        val a = Path().apply { addRect(0f, 0f, 10f, 10f, Path.Direction.CW) }
        val b = Path().apply { addRect(5f, 5f, 15f, 15f, Path.Direction.CCW) }
        val u = Path()
        assertTrue(u.op(a, b, Path.Op.UNION))
        val bmp = BitmapUtils.createLayerBitmap(20, 20)
        Canvas(bmp).drawPath(u, Paint().apply { color = WrapFixtures.BLACK })
        assertEquals(0xFF, bmp.getPixel(7, 7) ushr 24)
        assertEquals(0xFF, bmp.getPixel(12, 12) ushr 24)
        assertEquals(0, bmp.getPixel(12, 2) ushr 24)
    }
}
