package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.tan

/**
 * ibisPaint "Expansion" (膨張): magnifies the circular area around a point with a smooth falloff to
 * its edge (e.g. to enlarge eyes). Negative values shrink (pinch) the area instead.
 */
class ExpansionFilter : Filter("distort.expansion", "Expansion", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center"),
        FilterParam.Slider("amount", "Expansion", -100f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("radius", "Radius", 1f, 3000f, 300f, 1f, pixels = true),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val amount = values.float("amount").coerceIn(-100f, 100f) / 100f
        val radius = ctx.px(values.float("radius"))
        if (abs(amount) < 1e-3f || radius < 0.5f) return src.copy()
        val s = abs(amount) * MAX_STRENGTH
        // r_src = r * (1 - s * (1 - t^2)): monotonic for s < 1, magnifies the center by 1 / (1 - s).
        val bulge = { t: Float -> t * (1f - s + s * t * t) }
        val profile = if (amount > 0f) RadialProfile.of(bulge) else RadialProfile.inverseOf(bulge)
        val c = values.point("center")
        return DistortMath.radialWarp(src, ctx, c[0] * src.width, c[1] * src.height, radius, profile)
    }

    private companion object {
        const val MAX_STRENGTH = 0.95f
    }
}

/**
 * Fisheye lens: strong barrel distortion inside a circle. The center is magnified and the rim
 * compressed; negative values give the inverse (pincushion / "defish") mapping.
 */
class FishLensFilter : Filter("distort.fish_lens", "Fish Lens", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center"),
        FilterParam.Slider("distortion", "Distortion", -100f, 100f, 60f, 1f, "%"),
        FilterParam.Slider("radius", "Radius", 10f, 200f, 100f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val d = values.float("distortion").coerceIn(-100f, 100f) / 100f
        val fov = abs(d) * MAX_HALF_FOV
        if (fov < 1e-3f) return src.copy()
        val tanFov = tan(fov)
        // Equidistant fisheye over a rectilinear source: r_src = R * tan(t * fov) / tan(fov).
        val profile = if (d > 0f) {
            RadialProfile.of { t -> tan(t * fov) / tanFov }
        } else {
            RadialProfile.of { t -> atan(t * tanFov) / fov }
        }
        val c = values.point("center")
        val radius = DistortMath.percentRadius(values.float("radius"), src.width, src.height)
        return DistortMath.radialWarp(src, ctx, c[0] * src.width, c[1] * src.height, radius, profile)
    }

    private companion object {
        /** About 85 degrees: tan() stays well conditioned. */
        const val MAX_HALF_FOV = 1.48f
    }
}

/**
 * Sphere lens (spherize): the circle looks like a glass ball, magnified in the middle and
 * compressed at the rim. Negative strength makes it concave (the middle shrinks).
 */
class SphereLensFilter : Filter("distort.sphere_lens", "Sphere Lens", FilterCategory.DISTORT) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("center", "Center"),
        FilterParam.Slider("strength", "Strength", -100f, 100f, 100f, 1f, "%"),
        FilterParam.Slider("radius", "Radius", 5f, 200f, 70f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val k = values.float("strength").coerceIn(-100f, 100f) / 100f
        if (abs(k) < 1e-3f) return src.copy()
        val ak = abs(k)
        // Inverse spherical projection r_src = R * asin(a * t) / asin(a), blended with identity by
        // strength. a slightly below 1 keeps the slope at the rim finite (pure asin(t) squeezes tens
        // of source pixels into the outermost pixel ring, which shows up as a hard aliased line).
        val sphere = { t: Float -> t + ak * (asin(RIM * t.coerceIn(0f, 1f)) * INV_ASIN_RIM - t) }
        val profile = if (k > 0f) RadialProfile.of(sphere) else RadialProfile.inverseOf(sphere)
        val c = values.point("center")
        val radius = DistortMath.percentRadius(values.float("radius"), src.width, src.height)
        return DistortMath.radialWarp(src, ctx, c[0] * src.width, c[1] * src.height, radius, profile)
    }

    private companion object {
        const val RIM = 0.99f
        val INV_ASIN_RIM = (1.0 / kotlin.math.asin(RIM.toDouble())).toFloat()
    }
}
