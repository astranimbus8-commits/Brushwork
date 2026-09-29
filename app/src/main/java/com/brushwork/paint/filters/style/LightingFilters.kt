package com.brushwork.paint.filters.style

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.Parallel
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterMath
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Filters that light a height field: Bevel (Outer), Relief, Relief HQ and Waterdrop (Rounded).
 * Height fields are in pixels so slopes (and therefore shading) are independent of the preview
 * scale. Light vectors are in image coordinates: x right, y down, z towards the viewer.
 */

/** Height fields shared by the relief-style filters. */
internal object Relief {
    /** Height in full-resolution px of pure white at Height 50 in "Brightness" mode. */
    private const val BRIGHT_DEPTH = 4f

    fun sourceParam() = FilterParam.Choice("source", "Height from", listOf("Layer shape", "Brightness"), 0)
    fun flatnessParam(default: Float) = FilterParam.Slider("flatness", "Flatness", 0f, 95f, default, 1f, "%")
    fun heightParam(default: Float) = FilterParam.Slider("height", "Height", 0f, 100f, default, 1f)
    fun smoothnessParam(default: Float) = FilterParam.Slider("smoothness", "Smoothness", 0f, 50f, default, 0f, pixels = true)

    /**
     * Height field in px. "Layer shape" inflates every connected shape into a rounded dome (thin
     * lines become tubes, blobs become pillows); "Brightness" uses the (alpha-weighted) luminance,
     * a classic emboss. [heightScale] 1 = hemispherical domes; [smooth] is a blur radius in px.
     */
    fun heightField(img: PixelBuffer, brightness: Boolean, flatness: Float, heightScale: Float, smooth: Float, ctx: FilterContext): FloatArray {
        val w = img.width; val h = img.height
        val z: FloatArray
        if (brightness) {
            z = FloatArray(w * h)
            val depth = ctx.px(BRIGHT_DEPTH) * heightScale
            val flat = 1f / (1f - flatness.coerceIn(0f, 0.95f))
            val p = img.pixels
            Parallel.forRows(h) { y0, y1 ->
                ctx.checkCancelled()
                for (i in y0 * w until y1 * w) {
                    val c = p[i]
                    val v = ColorUtils.luminance(c) / 255f * ((c ushr 24) / 255f)
                    z[i] = min(1f, v * flat) * depth
                }
            }
        } else {
            z = StyleMath.signedDistance(img, ctx)
            StyleMath.domeHeights(z, w, h, flatness, heightScale, ctx)
        }
        StyleMath.gaussianInPlace(z, w, h, StyleMath.sigmaForRadius(smooth), ctx)
        return z
    }

    /** Linear color -> tone mapped (ACES filmic fit) or clamped, as an sRGB byte. */
    fun encode(l: Float, realistic: Boolean): Int {
        if (!realistic) return StyleMath.linearToSrgb(l)
        val x = max(0f, l)
        return StyleMath.linearToSrgb((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f))
    }
}

/**
 * Sloped, lit rim OUTSIDE the shapes (ibisPaint "Bevel (Outer)"), placed behind the layer: a 3D
 * look for titles and metallic letters. The light source handle sets the light direction relative
 * to the canvas centre (farther from the centre = lower, more raking light).
 */
class BevelOuterFilter : Filter("style.bevel_outer", "Bevel (Outer)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("light", "Light source", 0.2f, 0.2f),
        FilterParam.Slider("size", "Size", 1f, 200f, 12f, 1f, pixels = true),
        FilterParam.Slider("depth", "Depth", 10f, 500f, 100f, 1f, "%"),
        Relief.smoothnessParam(2f),
        FilterParam.Choice("profile", "Profile", listOf("Smooth", "Chisel", "Round"), 0),
        FilterParam.Color("color", "Bevel color", 0xFFB0B0B0.toInt()),
        FilterParam.Slider("highlight", "Highlight", 0f, 100f, 70f, 1f, "%"),
        FilterParam.Slider("shadow", "Shadow", 0f, 100f, 60f, 1f, "%"),
        StyleMath.outputParam("Bevel behind layer", "Bevel only"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val size = max(0.5f, ctx.px(values.float("size").coerceAtLeast(0f)))
        val depth = StyleMath.percent(values.float("depth")).coerceIn(0.01f, 10f)
        val smooth = ctx.px(values.float("smoothness").coerceAtLeast(0f))
        val profile = values.choice("profile")
        val color = values.color("color")
        val highlight = StyleMath.percent(values.float("highlight")).coerceIn(0f, 1f)
        val shadow = StyleMath.percent(values.float("shadow")).coerceIn(0f, 1f)
        val only = values.choice("output") == 1
        val lp = values.point("light")
        val light = StyleMath.normalize3(
            floatArrayOf(
                lp[0] * src.width - src.width * 0.5f,
                lp[1] * src.height - src.height * 0.5f,
                0.5f * max(src.width, src.height),
            ),
        )
        val half = StyleMath.normalize3(floatArrayOf(light[0], light[1], light[2] + 1f))
        val flat = max(light[2], 1e-3f)
        val brightRange = max(1f / flat - 1f, 1e-3f)
        val specFlat = StyleMath.ppow(half[2], 32f)
        val cr = (color shr 16) and 0xFF; val cg = (color shr 8) and 0xFF; val cb = color and 0xFF
        val colorA = (color ushr 24) / 255f

        return StyleMath.aroundContent(src, StyleMath.margin(size + smooth * 1.6f)) { img, _, _ ->
            val w = img.width; val h = img.height
            val z = StyleMath.signedDistance(img, ctx)
            val cover = ByteArray(w * h)
            val zTop = size * depth
            for (i in z.indices) {
                val d = z[i]
                cover[i] = (StyleMath.coverageWithin(d, size, 1f) * 255f + 0.5f).toInt().toByte()
                val t = (max(0f, d) / size).coerceIn(0f, 1f)
                val u = 1f - t
                val prof = when (profile) {
                    1 -> u
                    2 -> sqrt(max(0f, 1f - t * t))
                    else -> u * u * (3f - 2f * u)
                }
                z[i] = prof * zTop
            }
            StyleMath.gaussianInPlace(z, w, h, StyleMath.sigmaForRadius(smooth), ctx)
            FilterMath.mapXY(img, ctx) { x, y, c ->
                val i = y * w + x
                val cov = (cover[i].toInt() and 0xFF) / 255f
                if (cov <= 0f) {
                    if (only) 0 else c
                } else {
                    StyleMath.withNormal(z, w, h, x, y) { nx, ny, nz ->
                        val ratio = max(0f, nx * light[0] + ny * light[1] + nz * light[2]) / flat
                        var r: Float; var g: Float; var b: Float
                        if (ratio >= 1f) {
                            val k = ((ratio - 1f) / brightRange).coerceIn(0f, 1f) * highlight
                            r = cr + (255f - cr) * k; g = cg + (255f - cg) * k; b = cb + (255f - cb) * k
                        } else {
                            val k = 1f - (1f - ratio) * shadow
                            r = cr * k; g = cg * k; b = cb * k
                        }
                        val sp = StyleMath.ppow(nx * half[0] + ny * half[1] + nz * half[2], 32f)
                        val spec = (max(0f, sp - specFlat) / (1f - specFlat + 1e-6f)) * highlight
                        r += (255f - r) * spec; g += (255f - g) * spec; b += (255f - b) * spec
                        val rim = StyleMath.pack(1f, r, g, b)
                        if (only) StyleMath.solid(rim, cov * colorA) else StyleMath.behind(c, rim, cov * colorA)
                    }
                }
            }
        }
    }
}

/**
 * Puffy raised relief (ibisPaint "Relief"): shapes (or brightness) become a height field lit from
 * a fixed-elevation light at the chosen angle, shading the layer's own colors. Alpha is kept.
 */
class ReliefFilter : Filter("style.relief", "Relief", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        Relief.sourceParam(),
        Relief.flatnessParam(30f),
        Relief.heightParam(50f),
        Relief.smoothnessParam(2f),
        FilterParam.Slider("light_angle", "Light angle", 0f, 360f, 135f, 1f, "°"),
        FilterParam.Slider("highlight", "Highlight", 0f, 100f, 40f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val brightness = values.choice("source") == 1
        val flatness = StyleMath.percent(values.float("flatness"))
        val heightScale = values.float("height").coerceIn(0f, 1000f) / 50f
        val smooth = ctx.px(values.float("smoothness").coerceAtLeast(0f))
        val highlight = StyleMath.percent(values.float("highlight")).coerceIn(0f, 1f)
        val light = StyleMath.lightFromAngle(values.float("light_angle"), ELEVATION)
        val half = StyleMath.normalize3(floatArrayOf(light[0], light[1], light[2] + 1f))
        val flat = light[2]
        val specFlat = StyleMath.ppow(half[2], SHININESS)
        return StyleMath.aroundContent(src, StyleMath.margin(smooth * 1.6f)) { img, _, _ ->
            val w = img.width; val h = img.height
            val z = Relief.heightField(img, brightness, flatness, heightScale, smooth, ctx)
            FilterMath.mapXY(img, ctx) { x, y, c ->
                if (c ushr 24 == 0) c else StyleMath.withNormal(z, w, h, x, y) { nx, ny, nz ->
                    val ratio = max(0f, nx * light[0] + ny * light[1] + nz * light[2]) / flat
                    val factor = AMBIENT + (1f - AMBIENT) * ratio
                    val sp = StyleMath.ppow(nx * half[0] + ny * half[1] + nz * half[2], SHININESS)
                    val spec = (max(0f, sp - specFlat) / (1f - specFlat)) * highlight
                    val r = ((c shr 16) and 0xFF) * factor
                    val g = ((c shr 8) and 0xFF) * factor
                    val b = (c and 0xFF) * factor
                    (c and 0xFF000000.toInt()) or
                        (ColorUtils.clamp255(r + (255f - r) * spec) shl 16) or
                        (ColorUtils.clamp255(g + (255f - g) * spec) shl 8) or
                        ColorUtils.clamp255(b + (255f - b) * spec)
                }
            }
        }
    }

    private companion object {
        const val ELEVATION = 40f
        const val AMBIENT = 0.3f
        const val SHININESS = 24f
    }
}

/**
 * Physically based relief (ibisPaint "Relief HQ"): the relief height field is rendered with a
 * Cook-Torrance GGX material (metallic / roughness / reflectance), an environment light with
 * ambient occlusion, optional cast self-shadows, and optional filmic ("realistic") tone mapping
 * so bright highlights roll off naturally toward white. Alpha is kept.
 */
class ReliefHQFilter : Filter("style.relief_hq", "Relief HQ", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        Relief.sourceParam(),
        Relief.flatnessParam(30f),
        Relief.heightParam(50f),
        Relief.smoothnessParam(2f),
        FilterParam.Slider("light_angle", "Light direction", 0f, 360f, 135f, 1f, "°"),
        FilterParam.Slider("elevation", "Light elevation", 10f, 90f, 40f, 1f, "°"),
        FilterParam.Slider("metallic", "Metallic", 0f, 100f, 0f, 1f, "%"),
        FilterParam.Slider("roughness", "Roughness", 0f, 100f, 35f, 1f, "%"),
        FilterParam.Slider("reflectance", "Reflectance", 0f, 100f, 50f, 1f, "%"),
        FilterParam.Slider("highlight", "Highlight", 0f, 300f, 100f, 1f, "%"),
        FilterParam.Color("env_color", "Environment color", 0xFF707888.toInt()),
        FilterParam.Toggle("shadows", "Cast shadows", true),
        FilterParam.Toggle("realistic", "Realistic tone", true),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val brightness = values.choice("source") == 1
        val flatness = StyleMath.percent(values.float("flatness"))
        val heightScale = values.float("height").coerceIn(0f, 1000f) / 50f
        val smooth = ctx.px(values.float("smoothness").coerceAtLeast(0f))
        val elevation = values.float("elevation").coerceIn(1f, 90f)
        val light = StyleMath.lightFromAngle(values.float("light_angle"), elevation)
        val metallic = StyleMath.percent(values.float("metallic")).coerceIn(0f, 1f)
        val rough = StyleMath.percent(values.float("roughness")).coerceIn(0.04f, 1f)
        val refl = StyleMath.percent(values.float("reflectance")).coerceIn(0f, 1f)
        val highlight = StyleMath.percent(values.float("highlight")).coerceIn(0f, 10f)
        val env = values.color("env_color")
        val shadows = values.bool("shadows")
        val realistic = values.bool("realistic")

        val lin = StyleMath.srgbToLinear
        val envR = lin[(env shr 16) and 0xFF]; val envG = lin[(env shr 8) and 0xFF]; val envB = lin[env and 0xFF]
        val envMean = (envR + envG + envB) / 3f
        val flatNL = max(light[2], 0.05f)
        // Key light intensity chosen so that a flat, unshadowed dielectric keeps its own color.
        val key = highlight * (1f - 0.8f * envMean).coerceAtLeast(0.2f) / flatNL
        val half = StyleMath.normalize3(floatArrayOf(light[0], light[1], light[2] + 1f))
        val vh = half[2]
        val fresnelBase = ppow5(1f - vh)
        val alpha = rough * rough
        val alpha2 = alpha * alpha
        val kG = (rough + 1f) * (rough + 1f) / 8f
        val f0Dielectric = 0.16f * refl * refl
        val aoRadius = ctx.px(12f)

        return StyleMath.aroundContent(src, StyleMath.margin(smooth * 1.6f + aoRadius * 1.6f)) { img, _, _ ->
            val w = img.width; val h = img.height
            val z = Relief.heightField(img, brightness, flatness, heightScale, smooth, ctx)
            val shade = if (shadows) {
                val horiz = sqrt(light[0] * light[0] + light[1] * light[1])
                val tanE = if (horiz < 1e-4f) Float.POSITIVE_INFINITY else light[2] / horiz
                StyleMath.shadowSweep(z, w, h, light[0], light[1], tanE, max(0.5f, ctx.px(2f)), ctx).also {
                    StyleMath.gaussianInPlace(it, w, h, max(0.5f, ctx.px(1f)), ctx)
                }
            } else null
            val blurred = z.copyOf()
            StyleMath.gaussianInPlace(blurred, w, h, aoRadius, ctx)
            val aoScale = 1f / max(0.5f, aoRadius * 1.5f)
            FilterMath.mapXY(img, ctx) { x, y, c ->
                if (c ushr 24 == 0) c else StyleMath.withNormal(z, w, h, x, y) { nx, ny, nz ->
                    val i = y * w + x
                    val br = lin[(c shr 16) and 0xFF]; val bg = lin[(c shr 8) and 0xFF]; val bb = lin[c and 0xFF]
                    val nl = max(0f, nx * light[0] + ny * light[1] + nz * light[2])
                    val nv = max(nz, 1e-3f)
                    val nh = max(0f, nx * half[0] + ny * half[1] + nz * half[2])
                    val dd = nh * nh * (alpha2 - 1f) + 1f
                    val distrib = alpha2 / (PI.toFloat() * dd * dd)
                    val geo = (nl / (nl * (1f - kG) + kG)) * (nv / (nv * (1f - kG) + kG))
                    val specCommon = distrib * geo / (4f * nl * nv + 1e-4f)
                    val f0r = f0Dielectric + (br - f0Dielectric) * metallic
                    val f0g = f0Dielectric + (bg - f0Dielectric) * metallic
                    val f0b = f0Dielectric + (bb - f0Dielectric) * metallic
                    val fr = f0r + (1f - f0r) * fresnelBase
                    val fg = f0g + (1f - f0g) * fresnelBase
                    val fb = f0b + (1f - f0b) * fresnelBase
                    val lit = nl * key * (1f - (shade?.get(i) ?: 0f))
                    val diff = 1f - metallic
                    val ao = (1f / (1f + max(0f, blurred[i] - z[i]) * aoScale))
                    val ambR = envR * (br * diff + f0r * metallic) * ao
                    val ambG = envG * (bg * diff + f0g * metallic) * ao
                    val ambB = envB * (bb * diff + f0b * metallic) * ao
                    val outR = ((1f - fr) * diff * br + specCommon * fr * PI.toFloat()) * lit + ambR
                    val outG = ((1f - fg) * diff * bg + specCommon * fg * PI.toFloat()) * lit + ambG
                    val outB = ((1f - fb) * diff * bb + specCommon * fb * PI.toFloat()) * lit + ambB
                    (c and 0xFF000000.toInt()) or
                        (Relief.encode(outR, realistic) shl 16) or
                        (Relief.encode(outG, realistic) shl 8) or
                        Relief.encode(outB, realistic)
                }
            }
        }
    }

    private fun ppow5(x: Float): Float { val c = x.coerceIn(0f, 1f); val c2 = c * c; return c2 * c2 * c }
}

/**
 * Glossy water droplets (ibisPaint "Waterdrop (Rounded)"): every painted blob becomes a rounded,
 * translucent drop with a dark refraction rim on the side facing the light, a bright caustic on the
 * opposite side and a sharp specular highlight. The light is a point at the handle; Distance moves
 * it away from the canvas (farther = more uniform lighting across drops).
 */
class WaterdropFilter : Filter("style.waterdrop", "Waterdrop (Rounded)", FilterCategory.STYLE) {
    override val params: List<FilterParam> = listOf(
        FilterParam.Point("light", "Light source", 0.3f, 0.2f),
        FilterParam.Slider("distance", "Distance", 0f, 100f, 50f, 1f),
        Relief.flatnessParam(10f),
        Relief.heightParam(60f),
        FilterParam.Slider("transparency", "Transparency", 0f, 100f, 70f, 1f, "%"),
        FilterParam.Slider("highlight", "Highlight", 0f, 100f, 90f, 1f, "%"),
    )

    override fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer {
        val lp = values.point("light")
        val distance = StyleMath.percent(values.float("distance")).coerceIn(0f, 1f)
        val flatness = StyleMath.percent(values.float("flatness"))
        val heightScale = values.float("height").coerceIn(0f, 1000f) / 50f
        val body = 1f - StyleMath.percent(values.float("transparency")).coerceIn(0f, 1f)
        val highlight = StyleMath.percent(values.float("highlight")).coerceIn(0f, 1f)
        val lightZ = max(src.width, src.height) * (0.08f + 1.9f * distance * distance)
        val lxFull = lp[0] * src.width - 0.5f
        val lyFull = lp[1] * src.height - 0.5f
        return StyleMath.aroundContent(src, 3) { img, offX, offY ->
            val w = img.width; val h = img.height
            val z = StyleMath.signedDistance(img, ctx)
            StyleMath.domeHeights(z, w, h, flatness, heightScale, ctx)
            StyleMath.gaussianInPlace(z, w, h, max(0.6f, ctx.px(1f)), ctx)
            val lx = lxFull - offX; val ly = lyFull - offY
            FilterMath.mapXY(img, ctx) { x, y, c ->
                val a = (c ushr 24) / 255f
                if (a <= 0f) c else StyleMath.withNormal(z, w, h, x, y) { nx, ny, nz ->
                    var vx = lx - x; var vy = ly - y; var vz = lightZ
                    val vl = sqrt(vx * vx + vy * vy + vz * vz)
                    vx /= vl; vy /= vl; vz /= vl
                    val hlx = vx; val hly = vy; val hlz = vz + 1f
                    val hl = sqrt(hlx * hlx + hly * hly + hlz * hlz)
                    val nh = (nx * hlx + ny * hly + nz * hlz) / hl
                    val steep = (1f - nz).coerceIn(0f, 1f)
                    val nxy = sqrt(nx * nx + ny * ny)
                    val lxy = sqrt(vx * vx + vy * vy)
                    val facing = if (nxy > 1e-4f && lxy > 1e-4f) (nx * vx + ny * vy) / (nxy * lxy) else 0f
                    val rimDark = (steep * 2.2f).coerceIn(0f, 1f) * (0.45f + 0.55f * max(0f, facing))
                    val band = smoothstep(0.04f, 0.25f, steep) * (1f - smoothstep(0.45f, 0.85f, steep))
                    val caustic = max(0f, -facing) * band * highlight
                    val spec = (StyleMath.ppow(nh, 70f) + 0.25f * StyleMath.ppow(nh, 8f)) * highlight
                    val light = (spec + caustic * 0.6f).coerceIn(0f, 1f)
                    val dark = rimDark * 0.65f
                    val pr = ((c shr 16) and 0xFF) * 0.85f
                    val pg = ((c shr 8) and 0xFF) * 0.85f
                    val pb = (c and 0xFF) * 0.85f
                    var r = pr * (1f - dark); var g = pg * (1f - dark); var b = pb * (1f - dark)
                    r += (255f - r) * light; g += (255f - g) * light; b += (255f - b) * light
                    val outA = a * (body + rimDark * 0.85f + spec + caustic * 0.5f).coerceIn(0f, 1f)
                    StyleMath.pack(outA, r, g, b)
                }
            }
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
