package com.brushwork.paint.fxsmoke

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The session previews on a downscaled copy with ctx.scale < 1, so a pixel-distance parameter
 * must act through ctx.px(): the filter run with value 2v at scale 0.5 must see the same
 * distance as with value v at scale 1. For every Slider(pixels = true) of every filter this
 * checks that the parameter has an effect and that the two runs agree.
 */
class FilterScaleConsistencyTest {

    private fun image(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val transparent = (x / 10 + y / 10) % 4 == 3
            val a = if (transparent) 0 else 255
            val r = if ((x / 12 + y / 12) % 2 == 0) 210 else 50
            val g = (x * 255) / w
            val bl = if ((x + 2 * y) % 32 < 5) 20 else (y * 255) / h
            b[x, y] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return b
    }

    /**
     * Runs [f] with [key] = [v] as a preview at [scale] would: every pixel parameter (the others at
     * their defaults) is given in full-resolution pixels, i.e. divided by [scale] relative to the
     * buffer, so a filter that acts only through ctx.px() sees identical distances at any scale.
     */
    private fun run(f: Filter, src: PixelBuffer, key: String, v: Float, scale: Float): PixelBuffer {
        val values: FilterValues = f.defaultValues()
        for (p in f.params.filterIsInstance<FilterParam.Slider>().filter { it.pixels }) values.set(p.key, p.default / scale)
        values.set(key, v / scale)
        return f.apply(src, values, FilterContext(scale = scale))
    }

    /** Mean absolute premultiplied channel difference (0..255). */
    private fun diff(a: PixelBuffer, b: PixelBuffer): Double {
        var sum = 0.0
        for (i in a.pixels.indices) {
            val x = a.pixels[i]; val y = b.pixels[i]
            val ax = x ushr 24; val ay = y ushr 24
            sum += abs(ax - ay)
            for (sh in intArrayOf(16, 8, 0)) sum += abs(((x shr sh) and 0xFF) * ax - ((y shr sh) and 0xFF) * ay) / 255.0
        }
        return sum / (a.pixels.size * 4)
    }

    @Test
    fun pixelParametersScaleWithThePreview() {
        val src = image(120, 90)
        val report = mutableListOf<String>()
        val problems = mutableListOf<String>()
        var checked = 0
        for (f in FilterRegistry.all) {
            for (p in f.params.filterIsInstance<FilterParam.Slider>().filter { it.pixels }) {
                var v = if (p.default > p.min) p.default else p.min + maxOf(4f, p.step)
                v = minOf(v, 12f).coerceAtLeast(maxOf(p.min, 2f)) // small enough to act on a 120 px image
                if (2 * v > p.max) v = p.max / 2
                val a = run(f, src, p.key, v, 1f)
                val b = run(f, src, p.key, v, 0.5f)
                val c = run(f, src, p.key, 2 * v, 1f)
                val effect = diff(a, c)
                val mismatch = diff(a, b)
                report += "%-36s %-14s v=%-6s effect=%6.2f scaleMismatch=%6.2f".format(f.id, p.key, v, effect, mismatch)
                if (effect < 0.05) continue // no visible effect on this image at these values
                checked++
                if (mismatch > 0.05 && f.id !in FULL_RES_LAYOUT) problems += "${f.id}.${p.key}: effect %.2f but a scale-0.5 run differs by %.2f".format(effect, mismatch)
            }
        }
        println("fxsmoke scale consistency:\n" + report.joinToString("\n"))
        assertTrue("only $checked pixel parameters had a visible effect", checked >= 60)
        assertTrue("pixel parameters not scaled by ctx.px():\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        /**
         * Filters that also lay out patterns in full-resolution coordinates (w / ctx.scale: random
         * bands, drops, grain cells) or use fixed full-resolution sizes (bristles, emboss offsets),
         * as they should for a preview that matches the result. The identity above can't hold for
         * them because it keeps the buffer size fixed while changing the scale.
         */
        val FULL_RES_LAYOUT = setOf(
            "art.glitch", "art.oil_paint", "style.relief_hq", "frame.rain",
            "art.sheer_cross", "art.sheer_line", "art.sheer_square", "art.sheer_hex", "art.sheer_circle",
        )
    }
}
