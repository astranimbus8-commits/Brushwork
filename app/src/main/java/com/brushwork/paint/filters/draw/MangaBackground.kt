package com.brushwork.paint.filters.draw

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Photo -> black-and-white manga background: bright areas become paper white, dark areas solid
 * black, mid-tones a few levels of screen tone (dots, lines or sand), and ink lines are traced
 * along edges (XDoG). Tone pitch is in pixels; smoothing and line sizes are relative to the image.
 */
class MangaBackgroundFilter : Filter("draw.manga_background", "Manga Background", FilterCategory.DRAW) {

    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("smoothing", "Smoothing", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("brightness", "Brightness", -100f, 100f, 0f, 1f),
        FilterParam.Slider("contrast", "Contrast", 0f, 100f, 30f, 1f, "%"),
        FilterParam.Slider("white", "White level", 50f, 100f, 72f, 1f, "%"),
        FilterParam.Slider("black", "Black level", 0f, 50f, 22f, 1f, "%"),
        FilterParam.Choice("tone", "Screen tone", TONES, TONE_DOTS),
        FilterParam.Slider("tone_steps", "Tone steps", 1f, 8f, 3f, 1f),
        FilterParam.Slider("tone_size", "Screen tone size", 2f, 40f, 8f, 0.5f, pixels = true),
        FilterParam.Slider("tone_angle", "Screen tone angle", 0f, 90f, 45f, 1f, "°"),
        FilterParam.Slider("edges", "Line darkness", 0f, 100f, 75f, 1f, "%"),
        FilterParam.Slider("detail", "Line detail", 0f, 100f, 50f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val size = Stylize.workSize(src.width, src.height, WORK_LONG)
        val work = Stylize.downsampleLab(src, size[0], size[1], ctx, chroma = false)
        val ww = work.w; val wh = work.h
        val u = max(ww, wh) / 800f
        Stylize.fillTransparent(work, 1, 1, 4f * u, ctx)
        val l = work[0]
        for (i in l.indices) l[i] *= 0.01f // lightness 0..1
        val smooth = values.float("smoothing").coerceIn(0f, 100f) / 100f
        val edges = values.float("edges").coerceIn(0f, 100f) / 100f
        // Lines are traced on the lightly smoothed image, tones read from the smoothed one.
        val dog = if (edges > 0f) {
            val pre = Planes(ww, wh, 2).also { System.arraycopy(l, 0, it[0], 0, l.size); System.arraycopy(work[1], 0, it[1], 0, l.size) }
            Stylize.bilateral(pre, 1, 1, u, 0.08f, 1, ctx)
            Stylize.dog(pre[0], ww, wh, 0.8f * u, ctx)
        } else null
        ctx.progress(0.4f)
        val iterations = if (smooth <= 0f) 0 else 1 + (smooth * 2.99f).toInt()
        Stylize.bilateral(work, 1, 1, u * (1f + 2f * smooth), 0.07f + 0.1f * smooth, iterations, ctx)
        // A light blur so tone boundaries follow smooth contours instead of photo grain.
        val tones = FilterMath.gaussianBlurPlane(work[0], ww, wh, 0.4f * u * (1f + smooth), ctx)
        ctx.progress(0.7f)

        val detail = values.float("detail").coerceIn(0f, 100f) / 100f
        val eps = 0.014f - 0.011f * detail
        val inkGain = 1f / max(0.004f, eps)
        val contrast = 1f + 3f * values.float("contrast").coerceIn(0f, 100f) / 100f
        val bright = 0.5f * values.float("brightness").coerceIn(-100f, 100f) / 100f
        val white = values.float("white").coerceIn(0f, 100f) / 100f
        val black = minOf(values.float("black").coerceIn(0f, 100f) / 100f, white - 0.01f)
        val steps = values.int("tone_steps").coerceIn(1, 16)
        val tone = values.choice("tone").coerceIn(0, TONES.lastIndex)
        val pitch = max(1.5f, ctx.px(values.float("tone_size")))
        val ang = values.float("tone_angle") * (PI.toFloat() / 180f)
        val ca = cos(ang) / pitch; val sa = sin(ang) / pitch
        val grain = max(1f, pitch / 4f)
        return Stylize.render(src, work, ctx) { x, y, wx, wy, c ->
            val alpha = c ushr 24
            if (alpha == 0) return@render 0
            val v = ((Planes.sample(tones, ww, wh, wx, wy) - 0.5f) * contrast + 0.5f + bright)
            val ink = when {
                v >= white -> 0f
                v <= black -> 1f
                else -> {
                    val d = 1f - (v - black) / (white - black)
                    val dq = ((floor(d * steps) + 0.5f) / steps).coerceIn(0f, 1f)
                    screen(tone, dq, x + 0.5f, y + 0.5f, ca, sa, pitch, grain)
                }
            }
            var value = 1f - ink
            if (dog != null) {
                val dd = Planes.sample(dog, ww, wh, wx, wy)
                val line = ((-dd - eps) * inkGain).coerceIn(0f, 1f) * edges
                value *= 1f - line
            }
            val g = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
            (alpha shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /** Ink coverage (0..1, antialiased) of a screen tone of density [d] at pixel center (fx, fy). */
    private fun screen(tone: Int, d: Float, fx: Float, fy: Float, ca: Float, sa: Float, pitch: Float, grain: Float): Float {
        val ru = fx * ca + fy * sa
        val rv = -fx * sa + fy * ca
        return when (tone) {
            TONE_LINES -> {
                val gv = rv - round(rv)
                (d * pitch * 0.5f - kotlin.math.abs(gv) * pitch + 0.5f).coerceIn(0f, 1f)
            }
            TONE_SAND -> {
                val gx = floor(fx / grain).toInt(); val gy = floor(fy / grain).toInt()
                if (FilterMath.hash01(gx, gy, 9173) < d) 1f else 0f
            }
            else -> if (d <= 0.5f) {
                // Black dots centred in each cell; area = d.
                val fu = ru - floor(ru) - 0.5f; val fv = rv - floor(rv) - 0.5f
                val r = pitch * sqrt(d / PI.toFloat())
                (r - pitch * sqrt(fu * fu + fv * fv) + 0.5f).coerceIn(0f, 1f)
            } else {
                // White dots at the cell corners on black; white area = 1 - d.
                val gu = ru - round(ru); val gv = rv - round(rv)
                val r = pitch * sqrt((1f - d) / PI.toFloat())
                1f - (r - pitch * sqrt(gu * gu + gv * gv) + 0.5f).coerceIn(0f, 1f)
            }
        }
    }

    private companion object {
        const val WORK_LONG = 1600
        const val TONE_DOTS = 0
        const val TONE_LINES = 1
        const val TONE_SAND = 2
        val TONES = listOf("Dots", "Lines", "Sand")
    }
}
