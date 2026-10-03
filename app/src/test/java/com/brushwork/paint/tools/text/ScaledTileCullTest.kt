package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.LetterScaleFixtures.BLACK
import com.brushwork.paint.tools.text.WrapFixtures.LOREM
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scaled letters redrawn tile by tile (v1.6 review fix): like StaticLayout, a scaled text draws
 * only the lines near the canvas clip, so a 512 px display tile redraws the lines it shows, not
 * the whole text. The tiles together are the whole text bit for bit (I7: the screen equals a
 * full rendering), with an outline, turned, for every alignment, and for imported-font-free text.
 */
@RunWith(RobolectricTestRunner::class)
class ScaledTileCullTest {

    private val size = 1024
    private val tile = 512

    /** Counts the per-letter draws (ScaledLetters draws one `drawText` per letter). */
    private class CountingCanvas(b: Bitmap) : Canvas(b) {
        var letters = 0
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            letters++
            super.drawText(text, start, end, x, y, paint)
        }
    }

    private fun item(align: LetterScaleAlign, rotationDeg: Float = 13f) = TextItem(
        LOREM + "\n" + LOREM,
        TextSpec(
            sizePx = 34f, color = BLACK, strokeWidthPx = 3f, strokeColor = 0xFFFF0000.toInt(), italic = true,
            box = TextBoxSpec(width = 820f), letterScale = LetterScaleSpec(smallestPercent = 35f, align = align),
        ),
        cx = size / 2f, cy = size / 2f, rotationDeg = rotationDeg,
    )

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** [item] drawn whole, and tile by tile; the letters drawn in all and by the busiest tile. */
    private fun wholeAndTiled(item: TextItem): Triple<Pair<Bitmap, Bitmap>, Int, Int> {
        val prep = TextRenderer.prepare(item)
        val whole = BitmapUtils.createLayerBitmap(size, size)
        val all = CountingCanvas(whole)
        TextRenderer.drawItem(all, item, prep, null)
        val tiled = BitmapUtils.createLayerBitmap(size, size)
        val c = CountingCanvas(tiled)
        var most = 0
        for (ty in 0 until size step tile) for (tx in 0 until size step tile) {
            val before = c.letters
            val s = c.save()
            c.clipRect(Rect(tx, ty, tx + tile, ty + tile))
            TextRenderer.drawItem(c, item, prep, null)
            c.restoreToCount(s)
            most = maxOf(most, c.letters - before)
        }
        return Triple(whole to tiled, all.letters, most)
    }

    @Test
    fun theTilesTogetherAreTheWholeTextAndEachDrawsLess() {
        for (align in LetterScaleAlign.entries) {
            val turned = item(align)
            assertTrue("several lines", TextRenderer.prepare(turned).block!!.lineCount > 8)
            val (images, _, _) = wholeAndTiled(turned)
            assertArrayEquals("$align: the tiles are the whole (turned) text", pixels(images.first), pixels(images.second))
            // Upright: a tile shows about half of the lines, and draws about half of the letters.
            val (upright, all, most) = wholeAndTiled(item(align, 0f))
            assertArrayEquals("$align: the tiles are the whole text", pixels(upright.first), pixels(upright.second))
            assertTrue("$align: a tile draws fewer letters ($most of $all)", most < all * 3 / 4)
        }
    }

    @Test
    fun aSingleLineIsNeverCulledAwayAndImportedFontsDrawEverything() {
        val one = TextItem("ELTON JOHN", TextSpec(sizePx = 120f, color = BLACK, letterScale = LetterScaleSpec(smallestPercent = 60f)), cx = 500f, cy = 500f)
        val prep = TextRenderer.prepare(one)
        val whole = BitmapUtils.createLayerBitmap(size, size)
        TextRenderer.drawItem(Canvas(whole), one, prep, null)
        val tiled = BitmapUtils.createLayerBitmap(size, size)
        val c = Canvas(tiled)
        for (ty in 0 until size step tile) for (tx in 0 until size step tile) {
            val s = c.save()
            c.clipRect(Rect(tx, ty, tx + tile, ty + tile))
            TextRenderer.drawItem(c, one, prep, null)
            c.restoreToCount(s)
        }
        assertArrayEquals(pixels(whole), pixels(tiled))
        // A font that isn't built in (here: missing, drawn with the built-in one) is never culled.
        val imported = item(LetterScaleAlign.CENTER).let { it.copy(spec = it.spec.copy(fontId = "missing-font", fontName = "Gone")) }
        val ip = TextRenderer.prepare(imported)
        val all = CountingCanvas(BitmapUtils.createLayerBitmap(size, size))
        TextRenderer.drawItem(all, imported, ip, null)
        val corner = CountingCanvas(BitmapUtils.createLayerBitmap(size, size))
        corner.clipRect(Rect(0, 0, tile, tile))
        TextRenderer.drawItem(corner, imported, ip, null)
        assertTrue("every letter drawn: ${corner.letters} of ${all.letters}", corner.letters == all.letters)
    }
}
