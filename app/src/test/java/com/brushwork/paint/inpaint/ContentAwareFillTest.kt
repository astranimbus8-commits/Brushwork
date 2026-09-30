package com.brushwork.paint.inpaint

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.inpaint.InpaintTestImages.channelDiff
import com.brushwork.paint.inpaint.InpaintTestImages.dump
import com.brushwork.paint.inpaint.InpaintTestImages.rectMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** Content-aware fill engine on synthetic images (pure JVM). */
class ContentAwareFillTest {

    private val magenta = 0xFFFF00FF.toInt()

    private fun fillAndApply(image: PixelBuffer, hole: IRect, params: InpaintParams = InpaintParams()): Pair<PixelBuffer, InpaintResult> {
        val mask = rectMask(image.width, image.height, hole)
        val result = ContentAwareFill.fill(image, mask, params) ?: throw AssertionError("nothing filled")
        return ContentAwareFill.apply(image, result) to result
    }

    // ------------------------------------------------------------------ quality

    @Test
    fun stripesContinueThroughTheHole() {
        val w = 160; val h = 160
        val hole = IRect(60, 58, 100, 98)
        // The hole holds an object that must not come back.
        val image = InpaintTestImages.paint(InpaintTestImages.stripes(w, h), hole, magenta)
        val (out, _) = fillAndApply(image, hole)
        dump("stripes", out)
        var ok = 0; var total = 0
        for (y in hole.top until hole.bottom) for (x in hole.left until hole.right) {
            total++
            val c = out[x, y]
            assertTrue("magenta came back at $x,$y", channelDiff(c, magenta) > 60)
            if (channelDiff(c, InpaintTestImages.stripeColor(x)) <= 40) ok++
        }
        assertTrue("stripes continue: $ok / $total", ok >= total * 0.95)
    }

    @Test
    fun gradientIsFilledWithAGradient() {
        val w = 200; val h = 120
        val hole = IRect(80, 40, 124, 84)
        val image = InpaintTestImages.paint(InpaintTestImages.gradient(w, h), hole, magenta)
        val (out, _) = fillAndApply(image, hole)
        dump("gradient", out)
        var sum = 0L; var worst = 0; var n = 0
        for (y in hole.top until hole.bottom) for (x in hole.left until hole.right) {
            val d = channelDiff(out[x, y], InpaintTestImages.gradientColor(x, w))
            sum += d; n++
            worst = maxOf(worst, d)
        }
        val mean = sum.toDouble() / n
        assertTrue("mean error $mean", mean <= 4.0)
        assertTrue("worst error $worst", worst <= 24)
    }

    @Test
    fun horizonContinuesThroughAnObjectOnIt() {
        // A round object sitting on the horizon: sky must fill its top, grass its bottom.
        val w = 240; val h = 180
        val horizon = 90
        val src = InpaintTestImages.landscape(w, h, horizon)
        val cx = 120; val cy = 92; val rad = 34
        val mask = ByteArray(w * h)
        val img = src.copy()
        for (y in 0 until h) for (x in 0 until w) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) { mask[y * w + x] = -1; img[x, y] = magenta }
        }
        val result = ContentAwareFill.fill(img, mask)!!
        val out = ContentAwareFill.apply(img, result)
        dump("horizon", out)
        var sky = 0; var grass = 0; var nSky = 0; var nGrass = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (mask[y * w + x].toInt() == 0) continue
            val c = out[x, y]
            assertTrue("magenta came back at $x,$y", channelDiff(c, magenta) > 60)
            // Two rows of slack around the horizon.
            if (y < horizon - 2) { nSky++; if (channelDiff(c, InpaintTestImages.skyColor(y, horizon)) <= 30) sky++ }
            if (y > horizon + 1) { nGrass++; if (InpaintTestImages.isGrass(c)) grass++ }
        }
        assertTrue("sky above the horizon: $sky / $nSky", sky >= nSky * 0.9)
        assertTrue("grass below the horizon: $grass / $nGrass", grass >= nGrass * 0.9)
    }

    @Test
    fun colorAdaptationRepairsAGradientThatCanNotBeCopied() {
        // A diagonal ramp: the exact colors inside the hole exist nowhere else near it.
        val w = 160; val h = 160
        val img = PixelBuffer(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            InpaintTestImages.argb(255, (x + y) * 255 / (w + h - 2), 128, 255 - (x + y) * 255 / (w + h - 2))
        })
        val hole = IRect(55, 55, 105, 105)
        val expected = img.copy()
        val (out, _) = fillAndApply(InpaintTestImages.paint(img, hole, magenta), hole, InpaintParams(colorAdaptation = true))
        dump("diagonal", out)
        var sum = 0L; var n = 0
        for (y in hole.top until hole.bottom) for (x in hole.left until hole.right) { sum += channelDiff(out[x, y], expected[x, y]); n++ }
        val mean = sum.toDouble() / n
        assertTrue("mean error with color adaptation $mean", mean <= 8.0)
    }

    @Test
    fun textureIsFilledWithTextureNotBlur() {
        val w = 180; val h = 180
        val src = InpaintTestImages.texture(w, h)
        val hole = IRect(65, 65, 115, 115)
        val (out, result) = fillAndApply(InpaintTestImages.paint(src, hole, magenta), hole)
        dump("texture", out)
        // Local contrast inside the fill is comparable to the texture's (the final best-patch
        // step copies pixels instead of averaging them into a blur).
        fun contrast(img: PixelBuffer, r: IRect): Double {
            var s = 0L; var n = 0
            for (y in r.top until r.bottom) for (x in r.left until r.right - 1) { s += channelDiff(img[x, y], img[x + 1, y]); n++ }
            return s.toDouble() / n
        }
        val inside = contrast(out, IRect(hole.left + 4, hole.top + 4, hole.right - 4, hole.bottom - 4))
        val reference = contrast(src, IRect(10, 10, 60, 60))
        assertTrue("fill contrast $inside vs texture $reference", inside >= reference * 0.6)
        assertFalse(result.isEmpty)
    }

    // ------------------------------------------------------------------ correctness

    @Test
    fun holePixelsAreNeverUsedAsSources() {
        val w = 150; val h = 130
        val src = InpaintTestImages.texture(w, h, seed = 9)
        val hole = IRect(50, 40, 90, 85)
        val mask = rectMask(w, h, hole)
        val params = InpaintParams(recordSources = true, expand = 2)
        val result = ContentAwareFill.fill(InpaintTestImages.paint(src, hole, magenta), mask, params)!!
        val sources = result.sources!!
        // Hole as planned (expanded) and its 2 px halo.
        val planned = ByteArray(w * h)
        for (y in 0 until result.rect.height) for (x in 0 until result.rect.width) {
            if (result.weight[y * result.rect.width + x].toInt() != 0) planned[(result.rect.top + y) * w + result.rect.left + x] = 1
        }
        val halo = HoleOps.dilateSquare(planned, w, h, 2)
        var filled = 0
        for (j in sources.indices) {
            if (result.weight[j].toInt() == 0) continue
            val s = sources[j]
            assertTrue("pixel $j has no source", s >= 0)
            assertEquals("source $s is in the hole or its halo", 0, halo[s].toInt())
            filled++
        }
        assertTrue(filled >= hole.area)
        for (c in result.fill) if (c != 0) assertTrue("hole color leaked", channelDiff(c, magenta) > 60)
    }

    @Test
    fun sameSeedSameResultOtherSeedOtherResult() {
        val w = 140; val h = 120
        val src = InpaintTestImages.texture(w, h, seed = 5)
        val mask = rectMask(w, h, IRect(45, 35, 95, 85))
        val a = ContentAwareFill.fill(src, mask, InpaintParams(seed = 7))!!
        val b = ContentAwareFill.fill(src, mask, InpaintParams(seed = 7))!!
        assertArrayEquals("deterministic for a seed", a.fill, b.fill)
        val c = ContentAwareFill.fill(src, mask, InpaintParams(seed = 8))!!
        assertFalse("another seed gives another fill", a.fill.contentEquals(c.fill))
    }

    @Test
    fun cancellationStopsTheFill() {
        val w = 300; val h = 300
        val src = InpaintTestImages.texture(w, h)
        val mask = rectMask(w, h, IRect(100, 100, 200, 200))
        val polls = AtomicInteger()
        val monitor = InpaintMonitor(cancelled = { polls.incrementAndGet() > 40 })
        try {
            ContentAwareFill.fill(src, mask, InpaintParams(), monitor)
            fail("expected a cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        // A cancelled-from-the-start fill does nothing at all.
        try {
            ContentAwareFill.fill(src, mask, InpaintParams(), InpaintMonitor(cancelled = { true }))
            fail("expected a cancellation")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun progressGoesUpToOne() {
        val w = 160; val h = 160
        val values = mutableListOf<Float>()
        ContentAwareFill.fill(InpaintTestImages.texture(w, h), rectMask(w, h, IRect(60, 60, 100, 100)), InpaintParams(),
            InpaintMonitor(onProgress = { synchronized(values) { values += it } }))
        assertTrue(values.isNotEmpty())
        assertEquals(1f, values.last(), 1e-6f)
        for (i in 1 until values.size) assertTrue("progress went back: $values", values[i] >= values[i - 1] - 1e-6f)
    }

    @Test
    fun tinyHolesAreFilledFromTheirSurroundings() {
        val w = 40; val h = 30
        val blue = 0xFF2040C0.toInt()
        val img = PixelBuffer.filled(w, h, blue)
        // One pixel, no expansion.
        val one = ByteArray(w * h).also { it[15 * w + 20] = -1 }
        val r1 = ContentAwareFill.fill(InpaintTestImages.paint(img, IRect(20, 15, 21, 16), magenta), one, InpaintParams(expand = 0))!!
        assertEquals(IRect(20, 15, 21, 16), r1.rect)
        assertEquals(blue, r1.fill[0])
        assertEquals(255, r1.weight[0].toInt() and 0xFF)
        // A 3x3 hole with the default expansion.
        val out = fillAndApply(InpaintTestImages.paint(img, IRect(10, 10, 13, 13), magenta), IRect(10, 10, 13, 13)).first
        for (y in 0 until h) for (x in 0 until w) assertEquals("pixel $x,$y", blue, out[x, y])
    }

    @Test
    fun holeTouchingTheImageBorder() {
        val w = 120; val h = 100
        val hole = IRect(0, 30, 28, 70)
        val (out, result) = fillAndApply(InpaintTestImages.paint(InpaintTestImages.stripes(w, h), hole, magenta), hole)
        dump("border", out)
        assertEquals(0, result.rect.left)
        var ok = 0; var total = 0
        for (y in hole.top until hole.bottom) for (x in hole.left until hole.right) {
            total++
            if (channelDiff(out[x, y], InpaintTestImages.stripeColor(x)) <= 40) ok++
        }
        assertTrue("stripes at the border: $ok / $total", ok >= total * 0.85)
        // A hole in a corner.
        val corner = IRect(80, 0, 120, 25)
        val out2 = fillAndApply(InpaintTestImages.paint(InpaintTestImages.stripes(w, h), corner, magenta), corner).first
        for (y in corner.top until corner.bottom) for (x in corner.left until corner.right) {
            assertTrue("magenta at $x,$y", channelDiff(out2[x, y], magenta) > 60)
            assertEquals("opaque at $x,$y", 255, out2[x, y] ushr 24)
        }
    }

    @Test
    fun transparentSurroundingsGiveATransparentFill() {
        val w = 120; val h = 100
        val hole = IRect(40, 30, 80, 70)
        val img = InpaintTestImages.paint(PixelBuffer(w, h), IRect(45, 35, 75, 65), 0xFFFF0000.toInt())
        val (out, result) = fillAndApply(img, hole)
        for (y in 0 until h) for (x in 0 until w) assertEquals("pixel $x,$y", 0, out[x, y] ushr 24)
        assertTrue(result.fill.all { it ushr 24 == 0 })

        // Half transparent, half opaque green: the fill follows the edge.
        val green = 0xFF30A040.toInt()
        val half = PixelBuffer(w, h, IntArray(w * h) { i -> if (i % w < 60) 0 else green })
        val hole2 = IRect(40, 30, 80, 70)
        val out2 = fillAndApply(InpaintTestImages.paint(half, hole2, magenta), hole2).first
        dump("half", out2)
        var right = 0; var left = 0; var n = 0
        for (y in hole2.top until hole2.bottom) {
            n++
            if (out2[46, y] ushr 24 == 0) left++
            if (channelDiff(out2[74, y], green) <= 10) right++
        }
        assertTrue("transparent side $left / $n", left >= n * 0.9)
        assertTrue("green side $right / $n", right >= n * 0.9)
    }

    // ------------------------------------------------------------------ plan

    @Test
    fun planExpandsTheSolidPartAndKeepsSoftEdges() {
        val w = 60; val h = 60
        val mask = rectMask(w, h, IRect(20, 20, 40, 40))
        val plan = ContentAwareFill.plan(HoleMask.full(w, h, mask), InpaintParams(expand = 3))!!
        // Grown by ~3 px (plus the fading pixel).
        assertTrue(plan.changed.left in 16..17 && plan.changed.right in 43..44)
        fun weightAt(x: Int, y: Int) = plan.weight[(y - plan.changed.top) * plan.changed.width + x - plan.changed.left].toInt() and 0xFF
        assertEquals(255, weightAt(30, 30))
        assertEquals(255, weightAt(18, 30))
        assertTrue("fading edge", weightAt(plan.changed.left, 30) in 1..254)
        // expand 0: exactly the soft mask.
        val soft = ByteArray(w * h).also { it[30 * w + 30] = 100 }
        val p0 = ContentAwareFill.plan(HoleMask.full(w, h, soft), InpaintParams(expand = 0))!!
        assertEquals(IRect(30, 30, 31, 31), p0.changed)
        assertEquals(100, p0.weight[0].toInt() and 0xFF)
        assertEquals(1, p0.holePixels)
        // Nothing selected.
        assertNull(ContentAwareFill.plan(HoleMask.full(w, h, ByteArray(w * h)), InpaintParams()))
    }

    @Test
    fun samplingAreas() {
        val w = 1000; val h = 800
        val mask = HoleMask(w, h, IRect(480, 380, 520, 420), ByteArray(40 * 40) { -1 })
        val auto = ContentAwareFill.plan(mask, InpaintParams(sampling = SamplingArea.AUTO))!!
        val rect = ContentAwareFill.plan(mask, InpaintParams(sampling = SamplingArea.RECTANGLE))!!
        val whole = ContentAwareFill.plan(mask, InpaintParams(sampling = SamplingArea.WHOLE))!!
        assertEquals(IRect(0, 0, w, h), whole.roi)
        assertNull(whole.excluded)
        assertTrue(rect.roi.width in 150..230)
        assertNull(rect.excluded)
        // 44 px hole (2 px expansion): band 3 * 22 + 4 * 7 = 94 px (+ a patch) on each side.
        assertTrue("auto roi ${auto.roi}", auto.roi.width in 236..256)
        assertNotNull("the automatic band is round", auto.excluded)
        assertEquals(1, auto.excluded!![0].toInt()) // the ROI corner is farther than the band
        // Budget: WHOLE is cropped around the hole.
        val capped = ContentAwareFill.plan(mask, InpaintParams(sampling = SamplingArea.WHOLE, maxRoiPixels = 200_000))!!
        assertTrue(capped.roi.area <= 200_000)
        assertTrue(capped.roi.contains(capped.changed))
        // A hole beyond the budget is refused with a message.
        try {
            ContentAwareFill.plan(HoleMask.full(w, h, ByteArray(w * h) { -1 }), InpaintParams())
            fail("expected a refusal")
        } catch (e: InpaintException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun hasSourceLooksOnlyAtTheSamplingAreaOutsideTheHole() {
        val w = 600; val h = 400
        val hole = IRect(280, 180, 320, 220)
        val plan = ContentAwareFill.plan(HoleMask.full(w, h, rectMask(w, h, hole)), InpaintParams())!!
        fun pixelsWith(vararg opaque: Pair<Int, Int>): PixelBuffer {
            val img = PixelBuffer(w, h)
            for ((x, y) in opaque) img[x, y] = 0xFF336699.toInt()
            return ContentAwareFill.crop(img, plan.roi)
        }
        // Only the thing being removed is painted: nothing to fill from.
        assertFalse(ContentAwareFill.hasSource(plan, InpaintTestImages.paint(PixelBuffer(w, h), hole, magenta).let { ContentAwareFill.crop(it, plan.roi) }))
        assertFalse(ContentAwareFill.hasSource(plan, pixelsWith()))
        // A pixel next to the hole is a source.
        assertTrue(ContentAwareFill.hasSource(plan, pixelsWith(260 to 200)))
        // One outside the automatic band (the ROI's corner) is not.
        val corner = plan.roi.left to plan.roi.top
        assertTrue("corner outside the band", plan.excluded!![0].toInt() != 0)
        assertFalse(ContentAwareFill.hasSource(plan, pixelsWith(corner)))
    }

    @Test
    fun wholeImageHoleIsRefused() {
        val w = 50; val h = 40
        try {
            ContentAwareFill.fill(PixelBuffer.filled(w, h, -1), ByteArray(w * h) { -1 }, InpaintParams())
            fail("expected a refusal")
        } catch (e: InpaintException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    // ------------------------------------------------------------------ compositing

    @Test
    fun compositeAndLayerPixels() {
        val rect = IRect(0, 0, 3, 1)
        val fill = intArrayOf(0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0)
        val weight = byteArrayOf(-1, 128.toByte(), 0)
        val res = InpaintResult(rect, fill, weight, 1, null)
        val orig = intArrayOf(0xFFFF0000.toInt(), 0x00000000, 0x80102030.toInt())
        val out = res.composite(orig)
        assertEquals(0xFF00FF00.toInt(), out[0])
        // Half over transparent: half-transparent green.
        assertEquals(128, out[1] ushr 24)
        assertEquals(0xFF, (out[1] shr 8) and 0xFF)
        assertEquals(orig[2], out[2])
        // Alpha lock keeps transparency.
        val locked = res.composite(orig, alphaLocked = true)
        assertEquals(0xFF00FF00.toInt(), locked[0])
        assertEquals(0, locked[1])
        val layer = res.layerPixels()
        assertEquals(0xFF00FF00.toInt(), layer[0])
        assertEquals(128, layer[1] ushr 24)
        assertEquals(0, layer[2])
    }

    @Test
    fun premultiplyRoundTrip() {
        for (a in intArrayOf(0, 1, 17, 128, 254, 255)) for (v in intArrayOf(0, 1, 100, 200, 255)) {
            val c = InpaintTestImages.argb(a, v, 255 - v, v / 2)
            val back = InpaintEngine.unpremul(InpaintEngine.premul(c))
            if (a == 0) { assertEquals(0, back); continue }
            assertEquals(a, back ushr 24)
            // Low alphas lose precision, as in any premultiplied pipeline.
            assertTrue("$a/$v: ${Integer.toHexString(c)} -> ${Integer.toHexString(back)}", channelDiff(c, back) <= 255 / a + 1)
        }
    }

    @Test
    fun holeRadiusCountsErosions() {
        val m = rectMask(20, 20, IRect(5, 5, 12, 12)) // 7x7
        val crop = HoleOps.crop(m, IRect(0, 0, 20, 20), IRect(5, 5, 12, 12))
        for (i in crop.indices) crop[i] = if (crop[i].toInt() != 0) 1 else 0
        assertEquals(4, HoleOps.holeRadius(crop, IRect(5, 5, 12, 12), 20, 20))
        // A full-height band along the image edge is only reachable from one side.
        assertEquals(7, HoleOps.holeRadius(ByteArray(7 * 20) { 1 }, IRect(0, 0, 7, 20), 20, 20))
        assertEquals(4, HoleOps.holeRadius(ByteArray(49) { 1 }, IRect(0, 5, 7, 12), 20, 20))
        // Chamfer distance is close to euclidean.
        val seed = ByteArray(21 * 21).also { it[10 * 21 + 10] = 1 }
        val d = HoleOps.chamferDistance(seed, 21, 21)
        assertEquals(30, d[10 * 21 + 20])
        assertTrue(abs(d[20 * 21 + 20] / 3.0 - 14.14) < 1.5)
    }
}
