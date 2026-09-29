package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wave: displaces the image back and forth perpendicular to the wave direction, so straight lines
 * across it become wavy. Amplitude 0 leaves the image unchanged.
 */
class WaveFilter : Filter("distort.wave", "Wave", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Slider("amplitude", "Amplitude", 0f, 500f, 20f, 1f, pixels = true),
        FilterParam.Slider("wavelength", "Wave length", 2f, 2000f, 120f, 1f, pixels = true),
        FilterParam.Slider("angle", "Angle", 0f, 360f, 0f, 1f, "°"),
        FilterParam.Slider("phase", "Phase", 0f, 360f, 0f, 1f, "°"),
        FilterParam.Choice("shape", "Waveform", listOf("Sine", "Triangle", "Square")),
        FilterParam.Choice("edges", "Edges", DistortMath.EDGE_OPTIONS, DistortMath.EDGE_CLAMP),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val amp = ctx.px(values.float("amplitude"))
        if (abs(amp) < 0.01f) return src.copy()
        val lambda = max(0.5f, ctx.px(values.float("wavelength")))
        val a = values.float("angle") * DistortMath.DEG
        val dirX = cos(a); val dirY = sin(a)
        // Displacement axis: perpendicular to the direction of travel.
        val nx = -dirY; val ny = dirX
        val phaseTurns = values.float("phase") / 360f
        val shape = values.choice("shape")
        val wave = when (shape) {
            1 -> TRIANGLE
            2 -> SQUARE
            else -> PeriodicLut.SINE
        }
        val slope = when (shape) {
            1 -> TRIANGLE_SLOPE
            2 -> SQUARE_SLOPE
            else -> SINE_SLOPE
        }
        val edge = values.choice("edges").coerceIn(0, DistortMath.EDGE_OPTIONS.lastIndex)
        val cx = src.width * 0.5f; val cy = src.height * 0.5f
        val invLambda = 1f / lambda
        val shearScale = amp * invLambda
        return DistortMath.warp(
            src, ctx, 0, 0, src.width, src.height, edge,
            samples = { px, py ->
                // The displacement shears the image by s = amplitude * waveform' / wavelength.
                val u = (px - cx) * dirX + (py - cy) * dirY
                val s = abs(shearScale * slope.at(u * invLambda + phaseTurns))
                DistortMath.samplesFor((s + sqrt(s * s + 4f)) * 0.5f)
            },
        ) { px, py, q ->
            val u = (px - cx) * dirX + (py - cy) * dirY
            val off = amp * wave.at(u * invLambda + phaseTurns)
            q[0] = px - nx * off
            q[1] = py - ny * off
            true
        }
    }

    private companion object {
        val TRIANGLE = PeriodicLut { t -> 1f - 4f * abs(((t + 0.25f) - floor(t + 0.25f)) - 0.5f) }

        /** Square wave with short linear ramps so the tearing edges stay antialiased. */
        val SQUARE = PeriodicLut { t -> (sin(t * DistortMath.TWO_PI) * 6f).coerceIn(-1f, 1f) }

        // Derivatives (per period) of the waveforms, for choosing the supersampling.
        val SINE_SLOPE = PeriodicLut { t -> DistortMath.TWO_PI * cos(t * DistortMath.TWO_PI) }
        val TRIANGLE_SLOPE = PeriodicLut { 4f }
        val SQUARE_SLOPE = PeriodicLut { t ->
            if (abs(sin(t * DistortMath.TWO_PI) * 6f) < 1f) 6f * DistortMath.TWO_PI * cos(t * DistortMath.TWO_PI) else 0f
        }
    }
}

/**
 * Ripple: concentric water rings spreading from a point. Radial displacement follows a sine along
 * the distance from the center and fades out toward the edge of an (optionally elliptical) area.
 */
class RippleFilter : Filter("distort.ripple", "Ripple", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center"),
        FilterParam.Slider("amplitude", "Distortion", 0f, 200f, 12f, 1f, pixels = true),
        FilterParam.Slider("wavelength", "Wave length", 2f, 1000f, 60f, 1f, pixels = true),
        FilterParam.Slider("phase", "Phase", 0f, 360f, 0f, 1f, "°"),
        FilterParam.Slider("decay", "Decay", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("radius", "Radius", 1f, 300f, 100f, 1f, "%"),
        FilterParam.Slider("aspect", "Aspect ratio", -100f, 100f, 0f, 1f),
        FilterParam.Slider("rotation", "Rotation", 0f, 180f, 0f, 1f, "°"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val amp = ctx.px(values.float("amplitude"))
        if (abs(amp) < 0.01f) return src.copy()
        val lambda = max(0.5f, ctx.px(values.float("wavelength")))
        val radius = DistortMath.percentRadius(values.float("radius"), src.width, src.height)
        val gamma = 0.25f + 3f * values.float("decay").coerceIn(0f, 100f) / 100f
        val envelope = FloatArray(ENV_N + 1) { (1f - it.toFloat() / ENV_N).pow(gamma) }
        val phaseTurns = values.float("phase") / 360f
        val c = values.point("center")
        val frame = DistortMath.EllipseFrame(
            c[0] * src.width, c[1] * src.height,
            values.float("rotation") * DistortMath.DEG,
            DistortMath.aspectFactor(values.float("aspect")),
        )
        val r2max = radius * radius
        val invR = 1f / radius
        val invLambda = 1f / lambda
        val fadeIn = lambda * 0.5f
        val hw = frame.halfWidth(radius); val hh = frame.halfHeight(radius)
        val sine = PeriodicLut.SINE
        // The elliptical frame can concentrate the displacement gradient by up to its axis ratio.
        val squash = max(frame.aspect, 1f / frame.aspect)
        val slope = DistortMath.TWO_PI * invLambda
        return DistortMath.warp(
            src, ctx,
            floor(frame.cx - hw).toInt(), floor(frame.cy - hh).toInt(),
            ceil(frame.cx + hw).toInt() + 1, ceil(frame.cy + hh).toInt() + 1,
            DistortMath.EDGE_CLAMP,
            samples = { px, py ->
                val lx = frame.toLocalX(px, py)
                val ly = frame.toLocalY(px, py)
                val r2 = lx * lx + ly * ly
                if (r2 >= r2max || r2 < 1e-8f) {
                    1
                } else {
                    // Local stretch of r -> r + dr: radially 1 + dr'(r), tangentially 1 + dr / r.
                    val r = sqrt(r2)
                    val e = r * invR * ENV_N
                    val ei = e.toInt().coerceAtMost(ENV_N - 1)
                    val a = amp * (envelope[ei] + (envelope[ei + 1] - envelope[ei]) * (e - ei)) *
                        DistortMath.smoothstep(0f, fadeIn, r)
                    val ph = r * invLambda - phaseTurns
                    val radial = abs(a * slope * sine.at(ph + 0.25f))
                    val tangential = abs(a * sine.at(ph)) / r
                    DistortMath.samplesFor(1f + squash * max(radial, tangential))
                }
            },
        ) { px, py, q ->
            val lx = frame.toLocalX(px, py)
            val ly = frame.toLocalY(px, py)
            val r2 = lx * lx + ly * ly
            if (r2 >= r2max || r2 < 1e-8f) {
                false
            } else {
                val r = sqrt(r2)
                val e = r * invR * ENV_N
                val ei = e.toInt().coerceAtMost(ENV_N - 1)
                val env = envelope[ei] + (envelope[ei + 1] - envelope[ei]) * (e - ei)
                // Fade in over the first half wave so the center does not tear.
                val dr = amp * env * DistortMath.smoothstep(0f, fadeIn, r) * sine.at(r * invLambda - phaseTurns)
                val k = (r + dr) / r
                q[0] = frame.toImageX(lx * k, ly * k)
                q[1] = frame.toImageY(lx * k, ly * k)
                true
            }
        }
    }

    private companion object {
        const val ENV_N = 1024
    }
}

/**
 * Twirl: rotates the content inside an (optionally elliptical) area, most at the center and fading
 * to zero at the edge. Tension concentrates the rotation toward the center.
 */
class TwirlFilter : Filter("distort.twirl", "Twirl", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center"),
        FilterParam.Slider("twist", "Twist", -MAX_TWIST, MAX_TWIST, 180f, 1f, "°"),
        FilterParam.Slider("radius", "Radius", 1f, 300f, 100f, 1f, "%"),
        FilterParam.Slider("tension", "Tension", 0f, 100f, 30f, 1f, "%"),
        FilterParam.Slider("aspect", "Aspect ratio", -100f, 100f, 0f, 1f),
        FilterParam.Slider("rotation", "Rotation", 0f, 180f, 0f, 1f, "°"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val twist = values.float("twist").coerceIn(-MAX_TWIST, MAX_TWIST) * DistortMath.DEG
        if (abs(twist) < 1e-4f) return src.copy()
        val radius = DistortMath.percentRadius(values.float("radius"), src.width, src.height)
        val g = 0.5f + 4f * values.float("tension").coerceIn(0f, 100f) / 100f
        // Rotation (radians) per normalized radius; the inverse mapping rotates by -angle. Its
        // cosine and sine are tabulated too (interpolated and renormalized per pixel).
        val angle = FloatArray(ENV_N + 1) { -twist * (1f - it.toFloat() / ENV_N).pow(g) }
        val cosT = FloatArray(ENV_N + 1) { cos(angle[it]) }
        val sinT = FloatArray(ENV_N + 1) { sin(angle[it]) }
        val c = values.point("center")
        val frame = DistortMath.EllipseFrame(
            c[0] * src.width, c[1] * src.height,
            values.float("rotation") * DistortMath.DEG,
            DistortMath.aspectFactor(values.float("aspect")),
        )
        val r2max = radius * radius
        val scale = ENV_N / radius
        val hw = frame.halfWidth(radius); val hh = frame.halfHeight(radius)
        // Local minification: rotating by a(r) shears the neighborhood by s = r * a'(r), and in an
        // elliptical frame the rotation itself squeezes by up to the axis ratio (largest singular
        // values of A^-1 * Rot(a) * A and of the conjugated shear).
        val squash = max(frame.aspect, 1f / frame.aspect)
        val squash2 = squash * squash + 1f / (squash * squash)
        val samples = ByteArray(ENV_N + 1) { i ->
            val lo = max(0, i - 1); val hi = min(ENV_N, i + 1)
            val s = squash * abs(i.toFloat() / ENV_N * (angle[hi] - angle[lo]) * ENV_N / (hi - lo))
            val ca = cos(angle[i]); val sa = sin(angle[i])
            val tr = 2f * ca * ca + sa * sa * squash2
            val rot = sqrt((tr + sqrt(max(0f, tr * tr - 4f))) * 0.5f)
            DistortMath.samplesFor(rot * (s + sqrt(s * s + 4f)) * 0.5f).toByte()
        }
        return DistortMath.warp(
            src, ctx,
            floor(frame.cx - hw).toInt(), floor(frame.cy - hh).toInt(),
            ceil(frame.cx + hw).toInt() + 1, ceil(frame.cy + hh).toInt() + 1,
            DistortMath.EDGE_CLAMP,
            samples = { px, py ->
                val lx = frame.toLocalX(px, py)
                val ly = frame.toLocalY(px, py)
                val r2 = lx * lx + ly * ly
                if (r2 >= r2max) 1 else samples[(sqrt(r2) * scale).toInt().coerceIn(0, ENV_N)].toInt()
            },
        ) { px, py, q ->
            val lx = frame.toLocalX(px, py)
            val ly = frame.toLocalY(px, py)
            val r2 = lx * lx + ly * ly
            if (r2 >= r2max) {
                false
            } else {
                val e = sqrt(r2) * scale
                val ei = e.toInt().coerceAtMost(ENV_N - 1)
                val fr = e - ei
                // Adjacent table angles differ by < 0.5 rad, so the interpolated vector is not short.
                var ca = cosT[ei] + (cosT[ei + 1] - cosT[ei]) * fr
                var sa = sinT[ei] + (sinT[ei + 1] - sinT[ei]) * fr
                val norm = 1f / sqrt(ca * ca + sa * sa)
                ca *= norm; sa *= norm
                val sx = lx * ca - ly * sa
                val sy = lx * sa + ly * ca
                q[0] = frame.toImageX(sx, sy)
                q[1] = frame.toImageY(sx, sy)
                true
            }
        }
    }

    private companion object {
        const val ENV_N = 2048

        /** Degrees; keeps adjacent table angles close enough for interpolating cos / sin. */
        const val MAX_TWIST = 1080f
    }
}
