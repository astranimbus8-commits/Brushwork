package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.LetterScaleFixtures.BLACK
import com.brushwork.paint.tools.text.LetterScaleFixtures.alpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scaled letters and ligatures (v1.6 review fix): the built-in fonts join "fi", "fl", "ffi" into
 * one glyph, whose whole width a shaped measurement gives to the f (the i gets none). Scaled
 * letters are drawn one by one, so they must be measured one by one too, without ligatures:
 * otherwise "office" draws as "of fice", its i over the c. Unscaled text keeps its ligatures.
 */
@RunWith(RobolectricTestRunner::class)
class ScaledLigatureTest {

    private val w = 900
    private val h = 200

    /** Barely scaled (the last letter at 99.9 %): drawn letter by letter, sized as plain text. */
    private val barely = LetterScaleSpec(smallestPercent = 99.9f)

    private fun spec(font: TextFont, scale: LetterScaleSpec = barely) = TextSpec(font = font, sizePx = 60f, color = BLACK, letterScale = scale)

    private fun paint(font: TextFont) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 60f
        typeface = TextRenderer.typeface(spec(font))
        isSubpixelText = true
    }

    @Test
    fun theFontsDoJoinFiSoTheTestMeansSomething() {
        for (font in listOf(TextFont.SANS, TextFont.SERIF)) {
            val widths = FloatArray(2)
            paint(font).getTextWidths("fi", 0, 2, widths)
            assertEquals("$font: a shaped \"fi\" gives the i no width (a ligature)", 0f, widths[1], 0f)
        }
    }

    @Test
    fun eachLetterIsMeasuredAsItIsDrawn() {
        for (font in listOf(TextFont.SANS, TextFont.SERIF)) {
            val m = TextRenderer.frameLayout(TextItem("fifl", spec(font))).measured
            val p = paint(font)
            for ((i, c) in "fifl".withIndex()) {
                assertEquals("$font letter $i ('$c') measures as drawn alone", p.measureText(c.toString()), m.width(i, i + 1), 0.6f)
            }
        }
    }

    /** Records where every letter is drawn (ScaledLetters draws one `drawText` per letter). */
    private class Recorder(b: Bitmap) : Canvas(b) {
        /** start index, x, text size of each letter drawn. */
        val letters = ArrayList<Triple<Int, Float, Float>>()
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            letters += Triple(start, x, paint.textSize)
            super.drawText(text, start, end, x, y, paint)
        }
    }

    @Test
    fun aWordWithLigaturesDrawsItsLettersInPlace() {
        for (font in listOf(TextFont.SANS, TextFont.SERIF)) {
            val text = "fifi office fluffy affine fifi"
            val item = TextItem(text, spec(font), w / 2f, h / 2f)
            val prep = TextRenderer.prepare(item)
            val bmp = BitmapUtils.createLayerBitmap(w, h)
            val rec = Recorder(bmp)
            TextRenderer.drawItem(rec, item, prep, null)
            assertEquals("one draw per letter", text.count { it != ' ' }, rec.letters.size)
            // Each letter starts where the one before it ends, drawn alone at its size (a
            // ligature's width given to the f put the i over the next letter).
            val p = paint(font)
            for (k in 0 until rec.letters.size - 1) {
                val (s, x, size) = rec.letters[k]
                val (s2, x2, _) = rec.letters[k + 1]
                if (s2 != s + 1) continue // a space between them
                p.textSize = size
                val alone = p.measureText(text, s, s + 1)
                assertEquals("$font: '${text[s]}' at $s is followed at its own width", alone, x2 - x, 2f)
            }
            // The last i's ink ends inside the line's measured width (it was drawn one i further right).
            val block = prep.block!!
            val line = block.wrapLines!!.single()
            val a = alpha(bmp)
            var right = -1
            for (x in 0 until w) for (y in 0 until h) if (a[y * w + x] >= 128) right = x
            val lineRight = item.cx - block.width / 2f + block.inset + line.x + line.width
            assertTrue("$font: ink ends at $right, the line at $lineRight", right <= lineRight + 1f)
        }
    }

    @Test
    fun textOnAPathMeasuresItsLettersAlone() {
        val item = TextItem(
            "fifi", spec(TextFont.SERIF, LetterScaleSpec(smallestPercent = 50f)),
            path = TextPathSpec(type = TextPathType.LINE, x1 = 50f, y1 = 150f, x2 = 850f, y2 = 150f),
        )
        val fill = TextRenderer.pathPaints(item.spec).fill
        val layout = TextOnPathEngine.layout(item.text, fill, item.spec.letterScale)!!
        val p = paint(TextFont.SERIF)
        for ((k, c) in layout.clusters.withIndex()) {
            val alone = p.measureText(item.text, c.start, c.end) * c.scale
            assertEquals("cluster $k advance", alone, c.advance, 0.6f)
        }
        // The one-line width the path is sized with agrees.
        assertEquals(layout.width, TextRenderer.lineWidth(item.text, item.spec), 0.6f)
    }

    @Test
    fun unscaledTextKeepsItsLigatures() {
        val text = "office"
        val p = paint(TextFont.SERIF)
        val shaped = p.measureText(text)
        val apart = TextPaint(p).apply { fontFeatureSettings = TextRenderer.SCALED_LETTER_FEATURES }.measureText(text)
        assertTrue("the ffi ligature is narrower than its letters: $shaped vs $apart", shaped < apart - 1f)
        // v1.5's StaticLayout, shaped with the ligature.
        val layout = TextRenderer.prepare(TextItem(text, spec(TextFont.SERIF, LetterScaleSpec()), w / 2f, h / 2f)).block!!.staticLayout!!
        assertEquals(shaped, layout.getLineWidth(0), 0.5f)
        // Scaled (barely), the letters' own widths.
        val scaled = TextRenderer.prepare(TextItem(text, spec(TextFont.SERIF), w / 2f, h / 2f)).block!!.wrapLines!!.single()
        assertEquals(apart, scaled.width, 0.5f)
    }
}
