package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.RectF
import com.brushwork.paint.tools.text.LetterScaleFixtures.BLACK
import com.brushwork.paint.tools.text.LetterScaleFixtures.alpha
import com.brushwork.paint.tools.text.LetterScaleFixtures.lettersLeftToRight
import com.brushwork.paint.tools.text.LetterScaleFixtures.render
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 17, area D): manual kerns on text along a path. A kern moves every letter after its
 * gap along the path by `value / 1000` em (scaled with the letter before the gap), bent or
 * rotated, on lines and shapes; the bounds, the exported outlines and a fresh render follow; and
 * a path text whose kerns don't apply (none, at a line break, in right-to-left text) keeps the
 * plain layout and its pixels bit for bit (R16).
 */
@RunWith(RobolectricTestRunner::class)
class TextKernPathRobolectricTest {

    private val w = 512
    private val h = 300

    private fun onLine(text: String, mode: TextPathMode, kerns: List<TextKern> = emptyList(), spec: TextSpec = TextSpec(sizePx = 60f, color = BLACK)) = TextItem(
        text, spec,
        path = TextPathSpec(type = TextPathType.LINE, mode = mode, x1 = 20f, y1 = 200f, x2 = 490f, y2 = 200f),
        kerns = kerns,
    )

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Left edge of each letter's ink, relative to the first letter's. */
    private fun lefts(b: Bitmap): List<Int> = lettersLeftToRight(b).let { r -> r.map { it.left - r.first().left } }

    @Test
    fun aKernMovesTheLettersAfterItsGapAlongThePath() {
        val text = "HIHIH"
        val kern = listOf(TextKern(1, 500))
        val px = 0.5f * 60f
        val paints = TextRenderer.pathPaints(TextSpec(sizePx = 60f, color = BLACK))
        val plain = requireNotNull(TextOnPathEngine.layout(text, paints.fill))
        val kerned = requireNotNull(TextOnPathEngine.layout(text, paints.fill, null, kern))
        assertFalse(plain.kerned)
        assertTrue(kerned.kerned)
        assertFalse("no letter scaling", kerned.lettersScaled)
        assertEquals(plain.width + px, kerned.width, 0.01f)
        // Letters up to the gap stay; every one after it moves by half an em.
        for (i in kerned.clusters.indices) {
            val by = if (i <= 1) 0f else px
            assertEquals("cluster $i", plain.clusters[i].x0 + by, kerned.clusters[i].x0, 0.01f)
            assertEquals(plain.clusters[i].advance, kerned.clusters[i].advance, 0.01f)
        }
        // Drawn, bent or rotated: the same shift between the letters' ink.
        for (mode in TextPathMode.entries) {
            val a = lefts(render(onLine(text, mode), w, h))
            val b = lefts(render(onLine(text, mode, kern), w, h))
            assertEquals("$mode: five letters $a / $b", 5, a.size)
            assertEquals(5, b.size)
            for (i in a.indices) {
                val by = if (i <= 1) 0f else px
                assertEquals("$mode letter $i: $a / $b", a[i] + by, b[i].toFloat(), 1.5f)
            }
        }
        // A negative kern pulls them back.
        val tight = requireNotNull(TextOnPathEngine.layout(text, paints.fill, null, listOf(TextKern(1, -200))))
        assertEquals(plain.width - 12f, tight.width, 0.01f)
    }

    @Test
    fun kernsWithoutEffectKeepThePlainLayoutBitForBit() {
        for (mode in TextPathMode.entries) {
            val cases = linkedMapOf(
                // At a line break (the path joins the lines with a space): no kern there.
                "line break" to (onLine("Hi\nthere", mode) to listOf(TextKern(1, 400), TextKern(2, -300))),
                // Right-to-left text keeps its shaping and ignores kerns.
                "right to left" to (onLine("שלום world", mode) to listOf(TextKern(1, 400), TextKern(6, 300))),
                // Letter scaling with a kern that applies nowhere: the scaled layout as it was.
                "scaled" to (onLine("Hi\nthere", mode, spec = TextSpec(sizePx = 50f, color = BLACK, letterScale = LetterScaleSpec(smallestPercent = 50f))) to listOf(TextKern(1, 400))),
            )
            for ((what, case) in cases) {
                val (item, kerns) = case
                val plain = render(item, w, h)
                val withKerns = render(item.copy(kerns = kerns), w, h)
                assertTrue("$mode $what: ink", alpha(plain).any { it > 0 })
                assertTrue("$mode $what: the same pixels", pixels(plain).contentEquals(pixels(withKerns)))
                val paints = TextRenderer.pathPaints(item.spec)
                val layout = requireNotNull(TextOnPathEngine.layout(item.text, paints.fill, TextRenderer.pathLetters(item), kerns))
                assertFalse("$mode $what: not kerned", layout.kerned)
            }
        }
    }

    @Test
    fun aKernScalesWithTheLetterBeforeItsGap() {
        val spec = TextSpec(sizePx = 60f, color = BLACK, letterScale = LetterScaleSpec(smallestPercent = 40f))
        val item = onLine("HHHHHH", TextPathMode.ROTATE, spec = spec)
        val paints = TextRenderer.pathPaints(spec)
        val scaled = requireNotNull(TextOnPathEngine.layout(item.text, paints.fill, spec.letterScale))
        val kerned = requireNotNull(TextOnPathEngine.layout(item.text, paints.fill, spec.letterScale, listOf(TextKern(3, 500))))
        assertTrue(kerned.lettersScaled && kerned.kerned)
        val f = scaled.clusters[3].scale
        assertTrue("a smaller letter: $f", f < 1f)
        val px = 0.5f * 60f * f
        assertEquals(scaled.width + px, kerned.width, 0.01f)
        for (i in kerned.clusters.indices) {
            assertEquals(scaled.clusters[i].scale, kerned.clusters[i].scale, 0f)
            assertEquals(scaled.clusters[i].dy, kerned.clusters[i].dy, 0f)
            assertEquals("cluster $i", scaled.clusters[i].x0 + if (i <= 3) 0f else px, kerned.clusters[i].x0, 0.01f)
        }
    }

    @Test
    fun boundsOutlinesAndAFreshRenderFollowTheKerns() {
        val circle = TextPathSpec(type = TextPathType.CIRCLE, cx = 256f, cy = 150f, radius = 100f)
        for (mode in TextPathMode.entries) {
            val plain = TextItem("Kerned around", TextSpec(sizePx = 30f, color = BLACK, strokeWidthPx = 2f), path = circle.copy(mode = mode))
            val item = plain.copy(kerns = listOf(TextKern(0, 600), TextKern(5, 800), TextKern(9, -150)))
            val bmp = render(item, w, h)
            // The pixels differ from the unkerned text's, and a fresh render of the stored item gives them again (I1).
            assertFalse("$mode: kerned", pixels(bmp).contentEquals(pixels(render(plain, w, h))))
            val stored = requireNotNull(TextCodec.decode(TextCodec.encode(item)))
            assertEquals(item.kerns, stored.kerns)
            assertTrue("$mode: a fresh render", pixels(bmp).contentEquals(pixels(render(stored, w, h))))
            // Every inked pixel lies inside the bounds.
            val bounds = TextRenderer.prepare(item).docBounds(item)
            val ink = inkBounds(bmp)
            assertNotNull(ink)
            assertTrue("$mode: $ink inside $bounds", bounds.contains(RectF(ink!!).apply { inset(0.5f, 0.5f) }))
            // The exported outlines (letters and their outline stroke) are where the ink is.
            val parts = requireNotNull(TextExport.outlineParts(item))
            val out = RectF()
            for (part in parts) out.union(RectF().also { part.path.computeBounds(it, true) })
            assertEquals("$mode left", ink.left, out.left, 1.5f)
            assertEquals("$mode right", ink.right, out.right, 1.5f)
            assertEquals("$mode top", ink.top, out.top, 1.5f)
            assertEquals("$mode bottom", ink.bottom, out.bottom, 1.5f)
        }
    }

    /** Bounds of the pixels with any ink, null for none. */
    private fun inkBounds(b: Bitmap): RectF? {
        val a = alpha(b)
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bt = -1
        for (y in 0 until b.height) for (x in 0 until b.width) if (a[y * b.width + x] > 40) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); bt = maxOf(bt, y)
        }
        return if (r < 0) null else RectF(l.toFloat(), t.toFloat(), r + 1f, bt + 1f)
    }
}
