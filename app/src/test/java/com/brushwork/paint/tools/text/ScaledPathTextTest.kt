package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.LetterScaleFixtures.BLACK
import com.brushwork.paint.tools.text.LetterScaleFixtures.lettersLeftToRight
import com.brushwork.paint.tools.text.LetterScaleFixtures.render
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.6 §3.5(a, d) "ScaledPathTextTest": text on a path with scaled letters — each cluster takes
 * its own size and advance, Center and Top move the smaller letters along the path's normal
 * (bent outlines and rotated letters alike), the outlines export where the pixels are, and a
 * right-to-left text on a path stays unscaled.
 */
@RunWith(RobolectricTestRunner::class)
class ScaledPathTextTest {

    private val w = 900
    private val h = 300

    private fun onLine(mode: TextPathMode, align: LetterScaleAlign) = TextItem(
        "HHHHHH",
        TextSpec(sizePx = 80f, color = BLACK, letterSpacing = 0.25f, letterScale = LetterScaleSpec(smallestPercent = 40f, align = align)),
        path = TextPathSpec(type = TextPathType.LINE, mode = mode, x1 = 50f, y1 = 200f, x2 = 850f, y2 = 200f),
    )

    private fun spread(values: List<Float>) = values.max() - values.min()

    @Test
    fun eachLetterHasItsSizeAndAdvance() {
        val item = onLine(TextPathMode.BEND, LetterScaleAlign.CENTER)
        val paints = TextRenderer.pathPaints(item.spec)
        val scaled = TextOnPathEngine.layout(item.text, paints.fill, item.spec.letterScale)!!
        val plain = TextOnPathEngine.layout(item.text, paints.fill)!!
        assertTrue(scaled.lettersScaled)
        assertFalse(plain.lettersScaled)
        assertTrue("narrower: ${scaled.width} vs ${plain.width}", scaled.width < plain.width)
        val scales = scaled.clusters.map { it.scale }
        assertEquals(1f, scales.first(), 1e-6f)
        assertEquals(0.4f, scales.last(), 1e-6f)
        for (i in 1 until scales.size) assertTrue(scales[i] < scales[i - 1])
        // Each advance is the plain advance times its factor.
        for (i in scaled.clusters.indices) assertEquals(plain.clusters[i].advance * scales[i], scaled.clusters[i].advance, 0.01f)
        // Center moves smaller letters up (negative layout y): half of the cap height they lose.
        val c = scaled.clusters.last()
        assertEquals(-scaled.capHeight * 0.6f / 2f, c.dy, 0.01f)
    }

    @Test
    fun centerAndTopFollowThePathNormalBentOrRotated() {
        for (mode in TextPathMode.entries) {
            val center = lettersLeftToRight(render(onLine(mode, LetterScaleAlign.CENTER), w, h))
            assertEquals("$mode: six letters $center", 6, center.size)
            assertTrue("$mode center: ${center.map { (it.top + it.bottom) / 2f }}", spread(center.map { (it.top + it.bottom) / 2f }) <= 1f)
            val top = lettersLeftToRight(render(onLine(mode, LetterScaleAlign.TOP), w, h))
            assertTrue("$mode top: ${top.map { it.top }}", spread(top.map { it.top.toFloat() }) <= 1f)
            val base = lettersLeftToRight(render(onLine(mode, LetterScaleAlign.BASELINE), w, h))
            assertTrue("$mode baseline: ${base.map { it.bottom }}", spread(base.map { it.bottom.toFloat() }) <= 1f)
            val heights = center.map { it.height() }
            for (i in 1 until heights.size) assertTrue("$mode shrinking: $heights", heights[i] < heights[i - 1])
        }
    }

    @Test
    fun outlinesAndBoundsMatchTheDrawnLetters() {
        val circle = TextPathSpec(type = TextPathType.CIRCLE, cx = 450f, cy = 150f, radius = 110f)
        for (mode in TextPathMode.entries) for (align in LetterScaleAlign.entries) {
            val item = TextItem(
                "Around a circle", TextSpec(sizePx = 34f, color = BLACK, strokeWidthPx = 2f, letterScale = LetterScaleSpec(smallestPercent = 30f, align = align)),
                path = circle.copy(mode = mode),
            )
            val drawn = render(item, w, h)
            val prep = TextRenderer.prepare(item)
            val bounds = prep.docBounds(item)
            // Everything drawn is inside the bounds the layer is redrawn and committed in.
            val a = LetterScaleFixtures.alpha(drawn)
            for (y in 0 until h) for (x in 0 until w) if (a[y * w + x] > 0) assertTrue("$mode $align: ($x, $y) outside $bounds", bounds.contains(x + 0.5f, y + 0.5f))
            val parts = TextExport.outlineParts(item)
            assertNotNull(parts)
            val shape = BitmapUtils.createLayerBitmap(w, h)
            val cv = Canvas(shape)
            for (p in parts!!) cv.drawPath(p.path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLACK })
            val iou = LetterScaleFixtures.iou(drawn, shape)
            assertTrue("$mode $align: outlines cover the letters (IoU $iou)", iou >= 0.85f)
            // Unscaled, the same text is drawn differently.
            val plain = render(item.copy(spec = item.spec.copy(letterScale = LetterScaleSpec())), w, h)
            assertTrue(LetterScaleFixtures.iou(drawn, plain) < 0.9f)
        }
    }

    @Test
    fun theLayoutCacheTellsScaledFromPlain() {
        val item = onLine(TextPathMode.ROTATE, LetterScaleAlign.BASELINE)
        val fill = TextRenderer.pathPaints(item.spec).fill
        val a = TextOnPathEngine.layout(item.text, fill, item.spec.letterScale)!!
        assertTrue("cached", a === TextOnPathEngine.layout(item.text, fill, item.spec.letterScale))
        val b = TextOnPathEngine.layout(item.text, fill)!!
        assertTrue("plain is another layout", a !== b && !b.lettersScaled)
        val c = TextOnPathEngine.layout(item.text, fill, item.spec.letterScale.copy(align = LetterScaleAlign.TOP))!!
        assertTrue("another alignment is another layout", a !== c && c.clusters.last().dy != a.clusters.last().dy)
        // Drawing restores the paints it was given.
        val size = fill.textSize
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        TextOnPath.draw(Canvas(bmp), item.text, fill, null, item.path, item.spec.letterScale)
        assertEquals(size, fill.textSize, 0f)
        val r: RectF = TextOnPath.bounds(item.text, fill, null, item.path, item.spec.letterScale)
        assertFalse(r.isEmpty)
    }

    @Test
    fun rightToLeftTextOnAPathStaysUnscaled() {
        val path = TextPathSpec(type = TextPathType.CIRCLE, cx = 450f, cy = 150f, radius = 110f)
        val arabic = TextItem("مرحبا بالعالم", TextSpec(sizePx = 30f, color = BLACK), path = path)
        val scaled = arabic.copy(spec = arabic.spec.copy(letterScale = LetterScaleSpec(smallestPercent = 40f)))
        assertEquals(null, TextRenderer.pathLetters(scaled))
        assertEquals(1f, LetterScaleFixtures.iou(render(arabic, w, h), render(scaled, w, h)), 0f)
        assertTrue(abs(TextRenderer.lineWidth(scaled.text, scaled.spec) - TextRenderer.lineWidth(arabic.text, arabic.spec)) < 1e-3f)
    }
}
