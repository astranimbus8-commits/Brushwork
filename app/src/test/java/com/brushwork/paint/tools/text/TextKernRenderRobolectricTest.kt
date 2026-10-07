package com.brushwork.paint.tools.text

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * v1.7 §3.17c/d (item 17, area D): manual kerns move letters by exactly their value (the
 * per-cluster WrapLayout route), unkerned text keeps the v1.6 pixels (R16: the StaticLayout and
 * scaled-letter routes untouched), "Font kerning" off lays "AV" out wider on every route, v1.6
 * (format 4) text reads unkerned with the font's kerning on, and the relayout budgets hold.
 */
@RunWith(RobolectricTestRunner::class)
class TextKernRenderRobolectricTest {

    private val w = 500
    private val h = 400

    private fun render(item: TextItem): Bitmap {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        TextRenderer.drawItem(Canvas(bmp), item, TextRenderer.prepare(item), null)
        return bmp
    }

    private fun crc(item: TextItem): Long {
        val bmp = render(item)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val buf = ByteBuffer.allocate(px.size * 4)
        buf.asIntBuffer().put(px)
        return CRC32().apply { update(buf.array()) }.value
    }

    /** Leftmost and rightmost columns with ink (alpha over half). */
    private fun inkSpan(item: TextItem): IntArray {
        val bmp = render(item)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        var l = w
        var r = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 128) { l = minOf(l, x); r = maxOf(r, x) }
        assertTrue("something is drawn", r >= l)
        return intArrayOf(l, r)
    }

    private fun inkWidth(item: TextItem): Int = inkSpan(item).let { it[1] - it[0] }

    private val av = TextItem("AV", TextSpec(sizePx = 100f), 250f, 200f)

    @Test
    fun avAtMinus200IsAFifthOfAnEmNarrower() {
        val kerned = av.copy(kerns = listOf(TextKern(0, -200)))
        assertNear(inkWidth(av) - 20, inkWidth(kerned), 1) // 0.2 em = 20 px
        // Unkerned text keeps StaticLayout; kerned text takes the per-cluster route.
        assertNotNull(TextRenderer.prepare(av).block!!.staticLayout)
        assertNull(TextRenderer.prepare(kerned).block!!.staticLayout)
        // A positive kern widens by its value too.
        assertNear(inkWidth(av) + 30, inkWidth(av.copy(kerns = listOf(TextKern(0, 300)))), 1)
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ±$tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)

    @Test
    fun kernsWorkOnEveryHorizontalRoute() {
        val kern = listOf(TextKern(0, -200))
        // Letter-scaled (the kern scales with the A), in a box, around a picture, centred.
        val scaled = av.copy(spec = av.spec.copy(letterScale = LetterScaleSpec(smallestPercent = 50f)))
        assertTrue(inkWidth(scaled.copy(kerns = kern)) < inkWidth(scaled) - 10)
        val boxed = av.copy(spec = av.spec.copy(align = TextAlign.CENTER, box = TextBoxSpec(width = 300f, minHeight = 120f)))
        assertNear(inkWidth(boxed) - 20, inkWidth(boxed.copy(kerns = kern)), 1)
        val square = WrapPolygon(listOf(400f, 480f, 480f, 400f), listOf(300f, 300f, 380f, 380f))
        val wrapped = av.copy(wrap = TextWrapSpec(sourceLayerId = 3, polygons = listOf(square), gapPx = 4f))
        assertTrue(wrapped.wrapActive)
        assertNear(inkWidth(wrapped) - 20, inkWidth(wrapped.copy(kerns = kern)), 1)
        // A frame of a linked story: the kern indexes the story (here "AV" is all of it).
        val frame = TextItem(
            "AV", TextSpec(sizePx = 100f, box = TextBoxSpec(width = 300f, minHeight = 150f)), 250f, 200f,
            thread = TextThreadSpec(storyId = 5, story = "AV", start = 0, end = 2),
        ).sanitized()
        assertNear(inkWidth(frame) - 20, inkWidth(frame.copy(kerns = kern)), 1)
        // Vertical text ignores kerns.
        val vertical = TextItem("AV", TextSpec(sizePx = 60f, vertical = true), 250f, 200f)
        assertEquals(crc(vertical), crc(vertical.copy(kerns = kern)))
    }

    @Test
    fun aKernMovesTheLettersAfterItOnly() {
        // "AVA": a kern after the V moves the last A, not the first two letters.
        val ava = TextItem("AVA", TextSpec(sizePx = 80f), 250f, 200f)
        val left = inkSpan(ava)[0]
        val kerned = ava.copy(kerns = listOf(TextKern(1, 250)))
        assertNear(left - 10, inkSpan(kerned)[0], 1) // the block grew by 20 px around its centre
        assertNear(inkWidth(ava) + 20, inkWidth(kerned), 1)
        // The glyph outline (export) matches the drawing.
        val block = TextRenderer.prepare(kerned).block!!
        val bounds = android.graphics.RectF()
        block.glyphOutline()!!.computeBounds(bounds, true)
        assertNear(inkWidth(kerned), bounds.width().toInt(), 2)
    }

    @Test
    fun unkernedTextKeepsTheV16Pixels() {
        assumeTrue("the goldens are Windows hashes", System.getProperty("os.name").orEmpty().startsWith("Windows"))
        // CRC32s of the v1.6 renderer (v17d 1/8, TextRenderer as on main 3cd93ff); the v1.5 kinds
        // are UnscaledParityGoldenTest's goldens, which still pass.
        val golden = mapOf(
            "scaledPlain" to 1899230039L,
            "scaledBoxed" to 1878685498L,
            "scaledWrapped" to 4102189181L,
            "scaledFrame" to 2336990624L,
            "scaledPath" to 944824745L,
            "scaledVertical" to 3718189097L,
        )
        for ((name, item) in TextKernRenderCases.cases()) assertEquals("\"$name\" is not v1.6's pixels", golden[name], crc(item))
    }

    @Test
    fun kernsThatCantApplyChangeNoPixel() {
        // At a line break (either side) a kern has nothing to move: the text keeps StaticLayout.
        val text = "Boxed words that wrap\nin a centred caption"
        val item = TextItem(text, TextSpec(sizePx = 28f, align = TextAlign.CENTER, box = TextBoxSpec(width = 300f)), 250f, 200f, 12f)
        val useless = item.copy(kerns = listOf(TextKern(20, -300), TextKern(21, 400)))
        assertEquals(crc(item), crc(useless))
        assertNotNull(TextRenderer.prepare(useless).block!!.staticLayout)
        // Right-to-left text keeps its shaping (kerns are ignored there).
        val arabic = TextItem("مرحبا بالعالم", TextSpec(sizePx = 40f), 250f, 200f)
        assertEquals(crc(arabic), crc(arabic.copy(kerns = listOf(TextKern(2, 500)))))
        // A usable kern does change them.
        assertNotEquals(crc(item), crc(item.copy(kerns = listOf(TextKern(2, 300)))))
    }

    @Test
    fun fontKerningOffWidensAV() {
        val off = av.copy(spec = av.spec.copy(fontKerning = false))
        assertTrue("off: ${inkWidth(off)} > on: ${inkWidth(av)}", inkWidth(off) > inkWidth(av))
        // The same on the kerned route (the font's own pair plus the manual kern)...
        val manual = listOf(TextKern(0, -100))
        assertTrue(inkWidth(off.copy(kerns = manual)) > inkWidth(av.copy(kerns = manual)))
        // ...with scaled letters...
        val scaled = LetterScaleSpec(smallestPercent = 80f)
        assertTrue(inkWidth(off.copy(spec = off.spec.copy(letterScale = scaled))) > inkWidth(av.copy(spec = av.spec.copy(letterScale = scaled))))
        // ...and on a path (laid out as one run).
        val path = TextPathSpec(type = TextPathType.LINE, x1 = 50f, y1 = 250f, x2 = 450f, y2 = 250f)
        val onPath = av.copy(path = path)
        assertTrue(inkWidth(onPath.copy(spec = off.spec)) > inkWidth(onPath))
        // Turning it off and on again gives the v1.6 pixels back.
        assertEquals(crc(av), crc(off.copy(spec = off.spec.copy(fontKerning = true))))
    }

    @Test
    fun v4JsonDecodesUnkernedWithTheFontsKerning() {
        val v4 = """{"version":4,"item":{"text":"AV","spec":{"sizePx":100.0},"cx":250.0,"cy":200.0}}"""
        val item = TextCodec.decode(v4)!!
        assertTrue(item.kerns.isEmpty())
        assertTrue(item.spec.fontKerning)
        assertEquals(crc(av), crc(item))
        // Written back as version 5 without either new field (I13).
        val json = TextCodec.encode(item)
        assertTrue(json, json.contains("\"version\":5"))
        assertFalse(json, json.contains("kerns") || json.contains("fontKerning"))
        // Kerned text round-trips.
        val kerned = av.copy(kerns = listOf(TextKern(0, -200)), spec = av.spec.copy(fontKerning = false))
        assertEquals(kerned, TextCodec.decode(TextCodec.encode(kerned)))
    }

    @Test
    fun kernedTextRelaysOutWithinItsBudgets() {
        val words = WrapFixtures.LOREM
        fun textOf(n: Int) = buildString { while (length < n) append(words).append(' ') }.take(n)
        // Every fifth gap kerned (a kerned headline is far sparser).
        fun kernsOf(n: Int) = (0 until n - 1 step 5).map { TextKern(it, if (it % 2 == 0) -60 else 40) }
        fun median(runs: Int, block: () -> Unit): Double {
            val t = DoubleArray(runs) { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }
            t.sort()
            return t[runs / 2]
        }
        var k = 0
        val five = TextItem(textOf(500), TextSpec(sizePx = 18f, box = TextBoxSpec(width = 400f)), 250f, 200f, kerns = kernsOf(500))
        repeat(3) { TextRenderer.prepare(five) }
        // A kern stepped: every relayout measures and breaks the text again.
        val relayout = median(15) { k++; TextRenderer.prepare(five.copy(kerns = kernsOf(500).map { it.copy(value = it.value + k % 7 + 1) })) }
        val two = textOf(2000)
        val box = TextSpec(sizePx = 14f, box = TextBoxSpec(width = 900f))
        val bmp = BitmapUtils.createLayerBitmap(1000, 1000)
        val canvas = Canvas(bmp)
        fun layOutAndDraw(item: TextItem) {
            bmp.eraseColor(0)
            val p = TextRenderer.prepare(item)
            TextRenderer.drawItem(canvas, item, p, null)
        }
        val kernedItem = TextItem(two, box, 500f, 500f, kerns = kernsOf(2000))
        val scaledItem = TextItem(two, box.copy(letterScale = LetterScaleSpec(smallestPercent = 60f)), 500f, 500f)
        repeat(3) { layOutAndDraw(kernedItem); layOutAndDraw(scaledItem) }
        val kerned = median(15) { k++; layOutAndDraw(kernedItem.copy(spec = box.copy(sizePx = 14f + (k % 5) * 0.01f))) }
        val scaled = median(15) { k++; layOutAndDraw(scaledItem.copy(spec = scaledItem.spec.copy(sizePx = 14f + (k % 5) * 0.01f))) }
        bmp.recycle()
        println("[perf] kerned 500 chars relayout ${"%.2f".format(relayout)} ms; 2000 chars kerned ${"%.2f".format(kerned)} ms vs scaled ${"%.2f".format(scaled)} ms")
        assertTrue("relayout $relayout ms", relayout <= PerfBudget.ms(30.0))
        assertTrue("kerned $kerned ms > 1.5 × scaled $scaled ms", kerned <= 1.5 * scaled + PerfBudget.ms(2.0))
    }
}
