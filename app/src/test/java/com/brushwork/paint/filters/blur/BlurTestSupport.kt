package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.random.Random

internal val OPAQUE_BLACK = 0xFF000000.toInt()
internal val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
internal val OPAQUE_RED = 0xFFFF0000.toInt()
internal val OPAQUE_BLUE = 0xFF0000FF.toInt()

/** Default values of [filter] with some overridden. */
internal fun Filter.values(vararg overrides: Pair<String, Any>): FilterValues =
    defaultValues().also { v -> overrides.forEach { (k, x) -> v.set(k, x) } }

internal fun Filter.render(src: PixelBuffer, vararg overrides: Pair<String, Any>, scale: Float = 1f): PixelBuffer {
    val before = src.pixels.copyOf()
    val out = apply(src, values(*overrides), FilterContext(scale = scale))
    assertTrue("$id mutated its source", before.contentEquals(src.pixels))
    assertTrue("$id returned its source buffer", out !== src && out.pixels !== src.pixels)
    return out
}

internal fun randomImage(w: Int, h: Int, seed: Int = 7, opaque: Boolean = false): PixelBuffer {
    val rnd = Random(seed)
    return PixelBuffer(w, h, IntArray(w * h) {
        ColorUtils.argb(if (opaque) 255 else rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
    })
}

/** Opaque [bg] image with a [size] x [size] square of [color] centred at ([cx], [cy]). */
internal fun dotImage(w: Int, h: Int, cx: Int, cy: Int, size: Int = 3, color: Int = OPAQUE_WHITE, bg: Int = OPAQUE_BLACK): PixelBuffer {
    val b = PixelBuffer.filled(w, h, bg)
    val half = size / 2
    for (y in cy - half until cy - half + size) for (x in cx - half until cx - half + size) if (b.inBounds(x, y)) b[x, y] = color
    return b
}

internal fun red(c: Int) = ColorUtils.red(c)
internal fun alpha(c: Int) = ColorUtils.alpha(c)

/** Largest per-channel difference between two colours. */
internal fun channelDiff(a: Int, b: Int): Int = maxOf(
    abs(ColorUtils.alpha(a) - ColorUtils.alpha(b)),
    abs(ColorUtils.red(a) - ColorUtils.red(b)),
    abs(ColorUtils.green(a) - ColorUtils.green(b)),
    abs(ColorUtils.blue(a) - ColorUtils.blue(b)),
)

internal fun assertSameImage(message: String, expected: PixelBuffer, actual: PixelBuffer, tolerance: Int = 0) {
    for (i in expected.pixels.indices) {
        val d = channelDiff(expected.pixels[i], actual.pixels[i])
        if (d > tolerance) {
            throw AssertionError(
                "$message: pixel (${i % expected.width}, ${i / expected.width}) " +
                    "expected ${ColorUtils.toHex(expected.pixels[i], true)} but was ${ColorUtils.toHex(actual.pixels[i], true)}",
            )
        }
    }
}

internal fun assertUniform(message: String, img: PixelBuffer, color: Int, tolerance: Int = 1) {
    assertSameImage(message, PixelBuffer.filled(img.width, img.height, color), img, tolerance)
}
