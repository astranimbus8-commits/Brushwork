package com.brushwork.paint.v19.a2

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.CaBlend
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.filters.art.ChromaticAberrationZoomingFilter
import com.brushwork.paint.filters.draw.DrawBlend
import com.brushwork.paint.model.LayerBlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v1.9 (R10, A2 accept): the chromatic-aberration filters' "Blend mode" and "Opacity".
 * [CaBlend.composite] against the W3C Compositing Level 1 formulas written here independently in
 * double precision (each of the 17 modes of the layers panel, within 1/255 per channel); the 12
 * modes [DrawBlend] also has give [DrawBlend.composite]'s results exactly; at Normal and 100 %
 * the filters' output is v1.8's (the pass is skipped); the two new rows come after the v1.8
 * controls with the layers panel's names and defaults. Pure Kotlin.
 */
class ChromaticAberrationBlendTest {

    // ------------------------------------------------------------------ W3C reference (doubles)

    private class C(val r: Double, val g: Double, val b: Double)

    private fun lum(c: C) = 0.3 * c.r + 0.59 * c.g + 0.11 * c.b

    private fun clipColor(c: C): C {
        val l = lum(c)
        val n = min(c.r, min(c.g, c.b)); val x = max(c.r, max(c.g, c.b))
        var r = c.r; var g = c.g; var b = c.b
        if (n < 0) { r = l + (r - l) * l / (l - n); g = l + (g - l) * l / (l - n); b = l + (b - l) * l / (l - n) }
        if (x > 1) { r = l + (r - l) * (1 - l) / (x - l); g = l + (g - l) * (1 - l) / (x - l); b = l + (b - l) * (1 - l) / (x - l) }
        return C(r, g, b)
    }

    private fun setLum(c: C, l: Double): C { val d = l - lum(c); return clipColor(C(c.r + d, c.g + d, c.b + d)) }

    private fun sat(c: C) = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b))

    /** SetSat as the W3C writes it: Cmid, Cmax and Cmin by sorting. */
    private fun setSat(c: C, s: Double): C {
        val ch = doubleArrayOf(c.r, c.g, c.b)
        val idx = (0..2).sortedBy { ch[it] }
        val (iMin, iMid, iMax) = idx
        val out = DoubleArray(3)
        if (ch[iMax] > ch[iMin]) {
            out[iMid] = (ch[iMid] - ch[iMin]) * s / (ch[iMax] - ch[iMin])
            out[iMax] = s
        } else {
            out[iMid] = 0.0; out[iMax] = 0.0
        }
        out[iMin] = 0.0
        return C(out[0], out[1], out[2])
    }

    private fun separable(mode: LayerBlendMode, cb: Double, cs: Double): Double = when (mode) {
        LayerBlendMode.NORMAL -> cs
        LayerBlendMode.MULTIPLY -> cb * cs
        LayerBlendMode.SCREEN -> cb + cs - cb * cs
        LayerBlendMode.OVERLAY -> hardLight(cs, cb) // HardLight with the layers swapped
        LayerBlendMode.DARKEN -> min(cb, cs)
        LayerBlendMode.LIGHTEN -> max(cb, cs)
        LayerBlendMode.COLOR_DODGE -> if (cb == 0.0) 0.0 else if (cs == 1.0) 1.0 else min(1.0, cb / (1 - cs))
        LayerBlendMode.COLOR_BURN -> if (cb == 1.0) 1.0 else if (cs == 0.0) 0.0 else 1 - min(1.0, (1 - cb) / cs)
        LayerBlendMode.HARD_LIGHT -> hardLight(cb, cs)
        LayerBlendMode.SOFT_LIGHT -> if (cs <= 0.5) {
            cb - (1 - 2 * cs) * cb * (1 - cb)
        } else {
            val d = if (cb <= 0.25) ((16 * cb - 12) * cb + 4) * cb else sqrt(cb)
            cb + (2 * cs - 1) * (d - cb)
        }
        LayerBlendMode.DIFFERENCE -> abs(cb - cs)
        LayerBlendMode.EXCLUSION -> cb + cs - 2 * cb * cs
        LayerBlendMode.ADD -> min(1.0, cb + cs) // DrawBlend's Add ("Add (Glow)")
        else -> error("not separable: $mode")
    }

    private fun hardLight(cb: Double, cs: Double) = if (cs <= 0.5) cb * 2 * cs else { val s2 = 2 * cs - 1; cb + s2 - cb * s2 }

    private fun blend(mode: LayerBlendMode, cb: C, cs: C): C = when (mode) {
        LayerBlendMode.HUE -> setLum(setSat(cs, sat(cb)), lum(cb))
        LayerBlendMode.SATURATION -> setLum(setSat(cb, sat(cs)), lum(cb))
        LayerBlendMode.COLOR -> setLum(cs, lum(cb))
        LayerBlendMode.LUMINOSITY -> setLum(cb, lum(cs))
        else -> C(separable(mode, cb.r, cs.r), separable(mode, cb.g, cs.g), separable(mode, cb.b, cs.b))
    }

    /** Source-over with blending (W3C 5.8 / 10.1), the source's alpha times [coverage]; non-premultiplied ARGB out. */
    private fun reference(dst: Int, src: Int, coverage: Double, mode: LayerBlendMode): IntArray {
        fun ch(c: Int, s: Int) = ((c shr s) and 0xFF) / 255.0
        val ab = (dst ushr 24) / 255.0
        val asrc = (src ushr 24) / 255.0 * coverage
        val cb = C(ch(dst, 16), ch(dst, 8), ch(dst, 0))
        val cs = C(ch(src, 16), ch(src, 8), ch(src, 0))
        val b = blend(mode, cb, cs)
        val ao = asrc + ab * (1 - asrc)
        if (ao <= 0.0) return intArrayOf(0, 0, 0, 0)
        fun out(csc: Double, bc: Double, cbc: Double): Int {
            val mixed = (1 - ab) * csc + ab * bc
            val co = asrc * mixed + ab * cbc * (1 - asrc)
            return (co / ao * 255).coerceIn(0.0, 255.0).roundToInt()
        }
        return intArrayOf((ao * 255).roundToInt(), out(cs.r, b.r, cb.r), out(cs.g, b.g, cb.g), out(cs.b, b.b, cb.b))
    }

    private fun argb(c: Int) = intArrayOf(c ushr 24, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    /** Colors that reach every branch: random ones, greys, the extremes, and the alphas 0, partial and full. */
    private val colors: List<Int> = run {
        val r = Random(19)
        val fixed = listOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF3F7FBF.toInt(), 0x80FF8040.toInt(), 0x01204060, 0x00000000, 0x7F7F7F7F,
        )
        fixed + List(60) { (listOf(255, 255, 255, 200, 128, 30)[r.nextInt(6)] shl 24) or r.nextInt(0x1000000) }
    }

    @Test
    fun everyModeFollowsTheW3cFormulasWithinOneLevel() {
        assertEquals("the layers panel's 17 modes", 17, CaBlend.MODES.size)
        for (mode in CaBlend.MODES) {
            var worst = 0
            for (dst in colors) for (src in colors) for (cov in listOf(1.0, 0.6, 0.25)) {
                val got = argb(CaBlend.composite(dst, src, cov.toFloat(), mode))
                val want = reference(dst, src, cov, mode)
                if (want[0] == 0) { assertEquals("$mode: fully transparent", 0, got[0]); continue }
                for (i in 0..3) {
                    val d = abs(got[i] - want[i])
                    worst = max(worst, d)
                    assertTrue(
                        "$mode dst ${Integer.toHexString(dst)} src ${Integer.toHexString(src)} cov $cov channel $i: ${got[i]} vs W3C ${want[i]}",
                        d <= 1,
                    )
                }
            }
            println("ChromaticAberrationBlendTest: $mode worst difference $worst / 255")
        }
    }

    @Test
    fun theTwelveModesDrawBlendHasAreItsResults() {
        val shared = CaBlend.MODES.filter { CaBlend.drawModeOf(it) >= 0 }
        assertEquals(12, shared.size)
        assertEquals(
            "the five DrawBlend lacks",
            setOf(LayerBlendMode.EXCLUSION, LayerBlendMode.HUE, LayerBlendMode.SATURATION, LayerBlendMode.COLOR, LayerBlendMode.LUMINOSITY),
            (CaBlend.MODES - shared.toSet()).toSet(),
        )
        for (mode in shared) {
            val dm = CaBlend.drawModeOf(mode)
            // Same meaning by name ("Add (Glow)" is DrawBlend's "Add"; "Color Dodge" its "Color dodge").
            assertEquals(mode.label.substringBefore(" (").lowercase(), DrawBlend.NAMES[dm].lowercase())
            for (dst in colors) for (src in colors) for (cov in listOf(1f, 0.6f, 0.25f)) {
                assertEquals("$mode", DrawBlend.composite(dst, src, cov, dm), CaBlend.composite(dst, src, cov, mode))
            }
        }
    }

    @Test
    fun theTwoRowsComeAfterTheV18ControlsWithTheLayersPanelsNames() {
        for (f in listOf(ChromaticAberrationMovingFilter(), ChromaticAberrationZoomingFilter())) {
            val last = f.params.takeLast(2)
            val mode = last[0] as FilterParam.Choice
            val opacity = last[1] as FilterParam.Slider
            assertEquals(DrawBlend.param().label, mode.label)
            assertEquals(LayerBlendMode.entries.map { it.label }, mode.options)
            assertEquals(LayerBlendMode.NORMAL.ordinal, mode.default)
            assertTrue("the panel shows it as a dropdown", CaBlend.isModeChoice(mode))
            assertEquals(DrawBlend.opacityParam().label, opacity.label)
            assertEquals(0f, opacity.min); assertEquals(100f, opacity.max); assertEquals(100f, opacity.default)
            assertEquals("${f.name}: keys unique", f.params.size, f.params.map { it.key }.toSet().size)
        }
        // A draw filter's own "Blend mode" (13 chips) is not the dropdown.
        assertTrue(!CaBlend.isModeChoice(DrawBlend.param()))
    }

    @Test
    fun normalAtFullOpacitySkipsThePassAndOtherModesBlendTheAberratedPicture() {
        val w = 40; val h = 28
        val r = Random(7)
        val src = PixelBuffer(w, h, IntArray(w * h) { if (it % 9 == 0) 0x80402010.toInt() else (0xFF shl 24) or r.nextInt(0x1000000) })
        val ctx = FilterContext()
        val m = ChromaticAberrationMovingFilter()
        val v18 = FilterValues(mapOf("distance" to 6f, "angle" to 30f))
        val shifted = m.apply(src, v18, ctx)
        // The defaults (Normal, 100 %) give v1.8's result byte for byte, and skip the pass.
        val defaults = m.defaultValues().set("distance", 6f).set("angle", 30f)
        assertArrayEquals(shifted.pixels, m.apply(src, defaults, ctx).pixels)
        assertTrue(CaBlend.isIdentity(defaults))
        assertTrue("values without the new keys are v1.8's", CaBlend.isIdentity(v18))
        val aberrated = shifted.copy()
        assertSame("skipped: the aberrated picture itself", aberrated, CaBlend.over(src, aberrated, defaults, ctx))

        // Multiply at 60 %: every pixel is the aberrated one composited onto the original.
        for ((mode, opacity) in listOf(LayerBlendMode.MULTIPLY to 60f, LayerBlendMode.COLOR to 100f, LayerBlendMode.NORMAL to 40f)) {
            val values = defaults.copy().set("blend", mode.ordinal).set("opacity", opacity)
            val out = m.apply(src, values, ctx).pixels
            for (i in out.indices) {
                assertEquals("$mode $opacity pixel $i", CaBlend.composite(src.pixels[i], shifted.pixels[i], opacity / 100f, mode), out[i])
            }
        }
        // Zooming takes the same pass.
        val z = ChromaticAberrationZoomingFilter()
        val zv = z.defaultValues().set("distance", 5f)
        val zShifted = z.apply(src, zv, ctx).pixels
        val zOut = z.apply(src, zv.copy().set("blend", LayerBlendMode.LUMINOSITY.ordinal).set("opacity", 70f), ctx).pixels
        for (i in zOut.indices) assertEquals(CaBlend.composite(src.pixels[i], zShifted[i], 0.7f, LayerBlendMode.LUMINOSITY), zOut[i])
    }
}
