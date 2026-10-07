package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.7 §3.11 (item 11, area D accept test): the Transform tool maps a text layer's DATA. A
 * similarity (move, turn, uniform scale) maps the centre, turns the text and multiplies every
 * length — font size, outline, box, padding, border, minimum sizes, wrap gap, path — so a wrapped
 * text in a box keeps every line break; anything else is refused (the pixel lift stays).
 */
@RunWith(RobolectricTestRunner::class)
class TextTransformsTest {

    /** Row-major 3 × 3: scale [s] and turn [deg] about ([px], [py]), then move by ([tx], [ty]). */
    private fun similarity(s: Float, deg: Float, px: Float = 0f, py: Float = 0f, tx: Float = 0f, ty: Float = 0f): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val a = (s * cos(r)).toFloat()
        val d = (s * sin(r)).toFloat()
        // x' = R s (x - p) + p + t
        val c = px - (a * px - d * py) + tx
        val f = py - (d * px + a * py) + ty
        return floatArrayOf(a, -d, c, d, a, f, 0f, 0f, 1f)
    }

    private val words = "Every length of this text doubles, and its lines break where they broke before. " +
        "Padding, border and the box grow with the type.\nA second paragraph keeps its own lines too, word for word."

    private val boxed = TextItem(
        text = words,
        spec = TextSpec(
            sizePx = 18f,
            strokeWidthPx = 1.5f,
            box = TextBoxSpec(width = 230f, padding = 12f, fill = true, borderWidth = 3f, roundness = 0.2f, minHeight = 180f),
        ),
        cx = 200f,
        cy = 150f,
        rotationDeg = 10f,
        wrap = TextWrapSpec(gapPx = 6f),
    )

    private fun lineTexts(item: TextItem): List<String> =
        requireNotNull(TextExport.lines(item)) { "no runs" }.map { it.text }

    @Test
    fun twoHundredPercentKeepsEveryLineBreakAndDoublesEveryLength() {
        val m = similarity(2f, 0f, px = 120f, py = 90f, tx = 15f, ty = -5f)
        assertTrue(TextTransforms.canMap(m))
        val out = TextCodec.decode(TextTransforms.mapped(TextCodec.encode(boxed), m))
        assertNotNull(out)
        out!!
        // The centre maps; the turn stays.
        assertEquals(2f * 200f - 120f + 15f, out.cx, 1e-3f)
        assertEquals(2f * 150f - 90f - 5f, out.cy, 1e-3f)
        assertEquals(10f, out.rotationDeg, 1e-4f)
        // Every length doubles.
        assertEquals(36f, out.spec.sizePx, 1e-4f)
        assertEquals(3f, out.spec.strokeWidthPx, 1e-4f)
        val b = out.spec.box
        assertEquals(460f, b.width, 1e-3f)
        assertEquals(24f, b.padding, 1e-4f)
        assertEquals(6f, b.borderWidth, 1e-4f)
        assertEquals(360f, b.minHeight, 1e-3f)
        assertEquals(12f, out.wrap.gapPx, 1e-4f)
        // ... and nothing measured in em, percent or letters changes.
        assertEquals(boxed.text, out.text)
        assertEquals(boxed.spec.letterSpacing, out.spec.letterSpacing, 0f)
        assertEquals(boxed.spec.lineSpacing, out.spec.lineSpacing, 0f)
        assertEquals(boxed.spec.box.roundness, b.roundness, 0f)

        // The same lines, word for word, in a box twice the size.
        val before = lineTexts(boxed)
        assertTrue("several lines: $before", before.size >= 4)
        assertEquals(before, lineTexts(out))
        val b0 = requireNotNull(TextRenderer.prepare(boxed).block)
        val b1 = requireNotNull(TextRenderer.prepare(out).block)
        assertEquals(b0.lineCount, b1.lineCount)
        assertEquals(2f * b0.width, b1.width, 1f)
        assertEquals(2f * b0.height, b1.height, 1f)
        // Vertical text: its column length and minimum width double too.
        val vertical = TextItem("縦書きの文字", TextSpec(sizePx = 20f, vertical = true, box = TextBoxSpec(height = 90f, minWidth = 70f)))
        val v = requireNotNull(TextTransforms.mapped(vertical, m))
        assertEquals(180f, v.spec.box.height, 1e-3f)
        assertEquals(140f, v.spec.box.minWidth, 1e-3f)
    }

    @Test
    fun aTurnTurnsTheTextAndItsPathAboutTheMapsCentre() {
        val m = similarity(1.5f, 30f, px = 256f, py = 256f)
        val turned = requireNotNull(TextTransforms.mapped(boxed, m))
        // The centre turns about (256, 256), 1.5 times as far from it.
        val r = Math.toRadians(30.0)
        val dx = 200.0 - 256.0
        val dy = 150.0 - 256.0
        assertEquals((256.0 + 1.5 * (dx * cos(r) - dy * sin(r))).toFloat(), turned.cx, 1e-2f)
        assertEquals((256.0 + 1.5 * (dx * sin(r) + dy * cos(r))).toFloat(), turned.cy, 1e-2f)
        assertEquals(40f, turned.rotationDeg, 1e-3f)
        assertEquals(27f, turned.spec.sizePx, 1e-3f)

        // Text on a circle: its centre maps, its radius, offset and baseline shift scale, its start turns.
        val onCircle = TextItem(
            "Round and round", TextSpec(sizePx = 24f), cx = 300f, cy = 200f,
            path = TextPathSpec(type = TextPathType.CIRCLE, cx = 300f, cy = 200f, radius = 80f, startAngleDeg = -90f, offset = 10f, baselineShift = 4f),
        )
        val p = requireNotNull(TextTransforms.mapped(onCircle, m)).path
        val pdx = 300.0 - 256.0
        val pdy = 200.0 - 256.0
        assertEquals((256.0 + 1.5 * (pdx * cos(r) - pdy * sin(r))).toFloat(), p.cx, 1e-2f)
        assertEquals((256.0 + 1.5 * (pdx * sin(r) + pdy * cos(r))).toFloat(), p.cy, 1e-2f)
        assertEquals(120f, p.radius, 1e-3f)
        assertEquals(-60f, p.startAngleDeg, 1e-3f)
        assertEquals(15f, p.offset, 1e-3f)
        assertEquals(6f, p.baselineShift, 1e-3f)
    }

    @Test
    fun onlyMovesTurnsAndUniformScalesMap() {
        val data = TextCodec.encode(boxed)
        assertTrue(TextTransforms.canMap(similarity(1f, 0f, tx = 30f, ty = -12f)))
        assertTrue(TextTransforms.canMap(similarity(0.5f, -135f, px = 40f, py = 60f)))
        val refused = listOf(
            "non-uniform" to floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            "skew" to floatArrayOf(1f, 0.4f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            "flip" to floatArrayOf(-1f, 0f, 400f, 0f, 1f, 0f, 0f, 0f, 1f),
            "collapse" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
            "perspective" to floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f),
            "not finite" to floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            "2 × 3" to floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f),
        )
        for ((what, m) in refused) {
            assertFalse(what, TextTransforms.canMap(m))
            assertNull(what, TextTransforms.mapped(data, m))
        }
        // No text, or sizes pushed out of range: refused too (the pixel lift stays).
        assertNull(TextTransforms.mapped("not text data", similarity(2f, 0f)))
        assertNull(TextTransforms.mapped(TextCodec.encode(boxed.copy(spec = boxed.spec.copy(sizePx = 3f))), similarity(0.5f, 0f)))
        // The identity changes nothing: the same data back.
        assertSame(data, TextTransforms.mapped(data, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
    }

    @Test
    fun aFrameScalesWithItsSliceAndRefusesATurn() {
        val story = "The first frame holds these words. The second frame holds the rest of the story."
        val frame = TextItem(
            spec = TextSpec(sizePx = 16f, box = TextBoxSpec(width = 160f, minHeight = 90f, padding = 4f)),
            cx = 120f, cy = 80f,
            thread = TextThreadSpec(storyId = 42L, index = 0, story = story, start = 0, end = 35, overset = false, rev = 3L),
            kerns = listOf(TextKern(4, 120), TextKern(50, -40)),
        ).sanitized()
        val out = requireNotNull(TextTransforms.mapped(TextCodec.decode(TextCodec.encode(frame))!!, similarity(2f, 0f)))
        assertEquals(frame.thread, out.thread)
        assertEquals(frame.text, out.text)
        assertEquals(frame.kerns, out.kerns)
        assertEquals(32f, out.spec.sizePx, 1e-4f)
        assertEquals(320f, out.spec.box.width, 1e-3f)
        assertEquals(180f, out.spec.box.minHeight, 1e-3f)
        assertEquals(0f, out.rotationDeg, 0f)
        // Frames are never rotated (§7).
        assertNull(TextTransforms.mapped(TextCodec.encode(frame), similarity(1f, 15f)))
    }

    @Test
    fun aMappedTextOf2000CharactersRendersWithinBudget() {
        val text = buildString { while (length < 2000) append(words).append(' ') }.take(2000)
        val big = boxed.copy(text = text, spec = boxed.spec.copy(sizePx = 12f, box = boxed.spec.box.copy(width = 700f)))
        val data = TextCodec.encode(big)
        val m = similarity(1.5f, 20f, px = 256f, py = 256f)
        fun commit(): Long {
            val t0 = System.nanoTime()
            val item = requireNotNull(TextCodec.decode(TextTransforms.mapped(data, m)))
            val prep = TextRenderer.prepare(item)
            val block = requireNotNull(prep.block)
            val bmp = Bitmap.createBitmap(block.width.toInt() + 1, block.height.toInt() + 1, Bitmap.Config.ARGB_8888)
            TextRenderer.drawItem(Canvas(bmp), item.copy(cx = block.width / 2f, cy = block.height / 2f, rotationDeg = 0f), prep, null)
            bmp.recycle()
            return System.nanoTime() - t0
        }
        repeat(2) { commit() }
        val times = List(5) { commit() / 1e6 }.sorted()
        val ms = times[2]
        println("[perf] TextTransforms 2000 chars: map + layout + draw ${"%.2f".format(ms)} ms")
        assertTrue("map + render $ms ms", ms < PerfBudget.ms(150.0))
    }
}
