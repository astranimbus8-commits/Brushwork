package com.brushwork.paint.filters.adjust

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterValues

internal fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int = ColorUtils.argb(a, r, g, b)

internal fun gray(v: Int, a: Int = 255): Int = ColorUtils.gray(v, a)

/** One-row image of [colors]. */
internal fun row(vararg colors: Int): PixelBuffer = PixelBuffer(colors.size, 1, colors.copyOf())

/** Runs [filter] with its defaults overridden by [overrides]. */
internal fun Filter.run(src: PixelBuffer, vararg overrides: Pair<String, Any>, ctx: FilterContext = FilterContext()): PixelBuffer {
    val v: FilterValues = defaultValues()
    for ((k, value) in overrides) v.set(k, value)
    return apply(src, v, ctx)
}

/** A colorful test image with gradients, varying alpha and some fully transparent pixels. */
internal fun sampleImage(w: Int = 32, h: Int = 24): PixelBuffer {
    val b = PixelBuffer(w, h)
    for (y in 0 until h) for (x in 0 until w) {
        val a = when {
            (x + y) % 11 == 0 -> 0
            x % 5 == 0 -> 90
            else -> 255
        }
        b[x, y] = ColorUtils.argb(a, (x * 255) / (w - 1), (y * 255) / (h - 1), ((x * 7 + y * 13) * 5) % 256)
    }
    return b
}

internal fun r(c: Int) = ColorUtils.red(c)
internal fun g(c: Int) = ColorUtils.green(c)
internal fun b(c: Int) = ColorUtils.blue(c)
internal fun a(c: Int) = ColorUtils.alpha(c)

internal inline fun <reified T : Filter> adjust(): T = adjustFilters.filterIsInstance<T>().single()
