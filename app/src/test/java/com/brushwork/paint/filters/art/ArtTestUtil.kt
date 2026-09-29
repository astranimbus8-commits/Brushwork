package com.brushwork.paint.filters.art

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues

/** Helpers shared by the art filter tests. */
internal object ArtTestUtil {
    fun run(filter: Filter, src: PixelBuffer, vararg overrides: Pair<String, Any>, scale: Float = 1f): PixelBuffer {
        val v: FilterValues = filter.defaultValues()
        for ((k, value) in overrides) v.set(k, value)
        return filter.apply(src, v, FilterContext(scale = scale))
    }

    /** Smooth opaque test image with gradients in all three channels. */
    fun gradient(w: Int, h: Int, alpha: Int = 255): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            b[x, y] = ColorUtils.argb(alpha, 30 + x * 190 / w, 40 + y * 170 / h, 200 - (x + y) * 150 / (w + h))
        }
        return b
    }

    fun r(c: Int) = ColorUtils.red(c)
    fun g(c: Int) = ColorUtils.green(c)
    fun b(c: Int) = ColorUtils.blue(c)
    fun a(c: Int) = ColorUtils.alpha(c)

    fun countDifferent(x: PixelBuffer, y: PixelBuffer): Int = x.pixels.indices.count { x.pixels[it] != y.pixels[it] }
}
