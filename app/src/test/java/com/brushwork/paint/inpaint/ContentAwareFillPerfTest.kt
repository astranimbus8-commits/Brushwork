package com.brushwork.paint.inpaint

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.inpaint.InpaintTestImages.channelDiff
import com.brushwork.paint.inpaint.InpaintTestImages.dump
import com.brushwork.paint.inpaint.InpaintTestImages.rectMask
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Time budgets on the JVM (generous: other builds may share the machine) and the downscaled
 * big-hole path with its full-resolution refinement.
 */
@Category(Timing::class)
class ContentAwareFillPerfTest {

    private val magenta = 0xFFFF00FF.toInt()

    @Test
    fun typicalObjectHoleIsFast() {
        val w = 1000; val h = 1000
        val src = InpaintTestImages.texture(w, h, seed = 11)
        val hole = IRect(425, 425, 575, 575)
        val img = InpaintTestImages.paint(src, hole, magenta)
        val mask = rectMask(w, h, hole)
        // Warm-up (JIT) on a smaller copy.
        ContentAwareFill.fill(ContentAwareFill.crop(img, IRect(300, 300, 700, 700)), rectMask(400, 400, IRect(125, 125, 275, 275)))
        val (result, ms) = PerfBudget.timedWithin(8_000.0, what = "1000x1000, 150x150 hole") { ContentAwareFill.fill(img, mask)!! }
        println("[caf] 1000x1000, 150x150 hole: $ms ms")
        val out = ContentAwareFill.apply(img, result)
        dump("perf", ContentAwareFill.crop(out, IRect(300, 300, 700, 700)))
        for (y in hole.top until hole.bottom) for (x in hole.left until hole.right) {
            assertTrue("magenta at $x,$y", channelDiff(out[x, y], magenta) > 60)
        }
    }

    @Test
    fun bigHoleRunsDownscaledThenRefinesAtFullSize() {
        val w = 900; val h = 900
        val src = InpaintTestImages.stripes(w, h, period = 10)
        val hole = IRect(180, 180, 720, 720) // 0.29 MP: searched at half size
        val img = InpaintTestImages.paint(src, hole, magenta)
        val mask = rectMask(w, h, hole)
        val (a, ms) = PerfBudget.timedWithin(20_000.0, what = "900x900, 540x540 hole") { ContentAwareFill.fill(img, mask, InpaintParams(seed = 3))!! }
        println("[caf] 900x900, 540x540 hole: $ms ms")
        val out = ContentAwareFill.apply(img, a)
        dump("bighole", out)
        var ok = 0; var total = 0
        for (y in hole.top until hole.bottom step 3) for (x in hole.left until hole.right) {
            total++
            if (channelDiff(out[x, y], InpaintTestImages.stripeColor(x, 10)) <= 40) ok++
        }
        // Full-resolution copying keeps the stripes crisp (a blurred upscale would be gray).
        assertTrue("stripes through a big hole: $ok / $total", ok >= total * 0.85)
        val b = ContentAwareFill.fill(img, mask, InpaintParams(seed = 3))!!
        assertArrayEquals("deterministic on the big-hole path", a.fill, b.fill)
    }

    @Test
    fun thinScratchIsFilledQuickly() {
        val w = 600; val h = 400
        val src = InpaintTestImages.texture(w, h, seed = 2)
        // A 3 px wide diagonal scratch.
        val mask = ByteArray(w * h)
        for (x in 20 until 580) {
            val y = 40 + x * 300 / 600
            for (dy in -1..1) mask[(y + dy) * w + x] = -1
        }
        val img = PixelBuffer(w, h, src.pixels.copyOf())
        for (i in mask.indices) if (mask[i].toInt() != 0) img.pixels[i] = magenta
        val (result, ms) = PerfBudget.timedWithin(8_000.0, what = "600x400 thin scratch") { ContentAwareFill.fill(img, mask)!! }
        println("[caf] 600x400 thin scratch: $ms ms")
        val out = ContentAwareFill.apply(img, result)
        dump("scratch", out)
        for (i in mask.indices) if (mask[i].toInt() != 0) assertTrue(channelDiff(out.pixels[i], magenta) > 60)
    }
}
