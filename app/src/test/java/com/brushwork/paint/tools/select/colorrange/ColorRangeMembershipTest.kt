package com.brushwork.paint.tools.select.colorrange

import com.brushwork.paint.tools.select.ColorSelectSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v1.8 (C, design §3.5 c): Color select's membership. Samples by the nearest sample's ΔE
 * (full up to F / 2, none from F, falling between; F = 0 only the sample's own color), each preset
 * on its swatches, the fuzziness widening the bands, the invert option, the alpha (a transparent pixel
 * is never selected, inverted or not) and anti-alias off.
 */
class ColorRangeMembershipTest {

    private fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun m(argb: Int, p: ColorRange.Params): Int {
        val q = ColorRange.labQ(argb)
        return ColorRange.member(argb, ColorRange.qL(q), ColorRange.qA(q), ColorRange.qB(q), p)
    }

    private fun preset(preset: ColorPreset, fuzziness: Int = 40) = ColorRange.Params(preset, fuzziness, IntArray(0))

    private fun sampled(fuzziness: Int, vararg colors: Int) = ColorRange.Params.of(ColorPreset.SAMPLED, fuzziness, colors)

    @Test
    fun sampledGraysFollowTheirDistance() {
        // Grays differ in L only, so their ΔE is the L difference.
        val sample = rgb(128, 128, 128)
        val ls = ColorRange.qL(ColorRange.labQ(sample))
        for (f in listOf(10, 40, 80)) {
            val p = sampled(f, sample)
            var last = 256
            for (v in 128..255) {
                val c = rgb(v, v, v)
                val d = abs(ColorRange.qL(ColorRange.labQ(c)) - ls) / 100f
                val got = m(c, p)
                when {
                    d <= f / 2f - 0.05f -> assertEquals("F $f, gray $v (ΔE $d)", 255, got)
                    d >= f + 0.05f -> assertEquals("F $f, gray $v (ΔE $d)", 0, got)
                }
                assertTrue("F $f, gray $v: falling", got <= last)
                last = got
            }
            // The soft band holds partial values.
            assertTrue("F $f: a partial gray", (0..255).any { m(rgb(it, it, it), p) in 1..254 })
        }
    }

    @Test
    fun zeroFuzzinessTakesOnlyTheSamplesOwnColors() {
        val p = sampled(0, rgb(200, 40, 40), rgb(10, 90, 200))
        assertEquals(255, m(rgb(200, 40, 40), p))
        assertEquals(255, m(rgb(10, 90, 200), p))
        assertEquals(0, m(rgb(196, 40, 40), p))
        assertEquals(0, m(rgb(10, 90, 196), p))
        assertEquals(0, m(rgb(255, 255, 255), p))
    }

    @Test
    fun theNearestSampleCounts() {
        val p = sampled(20, rgb(255, 0, 0), rgb(0, 0, 255))
        assertEquals(255, m(rgb(250, 5, 5), p))
        assertEquals(255, m(rgb(5, 5, 250), p))
        assertEquals(0, m(rgb(0, 255, 0), p))
        // No samples: nothing.
        val none = sampled(40)
        assertTrue(none.selectsNothing)
        assertEquals(0, m(rgb(255, 0, 0), none))
    }

    @Test
    fun theHuePresetsTakeTheirHues() {
        val swatches = mapOf(
            ColorPreset.REDS to rgb(255, 0, 0),
            ColorPreset.YELLOWS to rgb(255, 255, 0),
            ColorPreset.GREENS to rgb(0, 255, 0),
            ColorPreset.CYANS to rgb(0, 255, 255),
            ColorPreset.BLUES to rgb(0, 0, 255),
            ColorPreset.MAGENTAS to rgb(255, 0, 255),
        )
        for ((preset, own) in swatches) {
            val p = preset(preset)
            assertEquals("$preset on its own hue", 255, m(own, p))
            // Darker shades of the hue count too.
            assertEquals("$preset, darker", 255, m(darker(own), p))
            for ((other, color) in swatches) if (other != preset) assertEquals("$preset on $other", 0, m(color, p))
            // Grays are no hue.
            assertEquals("$preset on gray", 0, m(rgb(128, 128, 128), p))
            assertEquals("$preset on white", 0, m(rgb(255, 255, 255), p))
            assertEquals("$preset on black", 0, m(rgb(0, 0, 0), p))
        }
        // Hue 30 (orange) sits in the Reds' soft band at F = 0 (15..45 degrees) ...
        val orange = rgb(255, 128, 0)
        assertTrue(m(orange, preset(ColorPreset.REDS, 0)) in 64..192)
        // ... and the fuzziness widens the band: at 200 it is 45..75 degrees.
        assertEquals(255, m(orange, preset(ColorPreset.REDS, 200)))
        // A nearly gray red is only partly red (saturation 0.05..0.2).
        assertTrue(m(rgb(200, 180, 180), preset(ColorPreset.REDS)) in 1..254)
    }

    private fun darker(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return rgb(r / 2, g / 2, b / 2)
    }

    @Test
    fun theTonePresets() {
        val white = rgb(255, 255, 255)
        val black = rgb(0, 0, 0)
        val mid = rgb(119, 119, 119) // L about 50
        assertEquals(255, m(white, preset(ColorPreset.HIGHLIGHTS)))
        assertEquals(0, m(black, preset(ColorPreset.HIGHLIGHTS)))
        assertEquals(0, m(mid, preset(ColorPreset.HIGHLIGHTS)))
        assertEquals(255, m(black, preset(ColorPreset.SHADOWS)))
        assertEquals(0, m(white, preset(ColorPreset.SHADOWS)))
        assertEquals(0, m(mid, preset(ColorPreset.SHADOWS)))
        assertEquals(255, m(mid, preset(ColorPreset.MIDTONES)))
        assertEquals(0, m(white, preset(ColorPreset.MIDTONES)))
        assertEquals(0, m(black, preset(ColorPreset.MIDTONES)))
        // Highlights, midtones and shadows share every pixel out between them.
        for (v in 0..255 step 5) {
            val c = rgb(v, v, v)
            val sum = m(c, preset(ColorPreset.HIGHLIGHTS)) + m(c, preset(ColorPreset.MIDTONES)) + m(c, preset(ColorPreset.SHADOWS))
            assertEquals("gray $v", 255f, sum.toFloat(), 2f)
        }
    }

    @Test
    fun vividMutedAndSkin() {
        assertEquals(255, m(rgb(255, 0, 0), preset(ColorPreset.VIVID)))
        assertEquals(0, m(rgb(128, 128, 128), preset(ColorPreset.VIVID)))
        assertEquals(0, m(rgb(60, 50, 50), preset(ColorPreset.VIVID)))
        assertEquals(0, m(rgb(255, 0, 0), preset(ColorPreset.MUTED)))
        assertEquals(255, m(rgb(128, 128, 128), preset(ColorPreset.MUTED)))
        for (skin in listOf(rgb(224, 172, 105), rgb(210, 160, 120), rgb(150, 100, 70), rgb(255, 205, 160))) {
            assertEquals("skin #${Integer.toHexString(skin)}", 255, m(skin, preset(ColorPreset.SKIN)))
        }
        for (other in listOf(rgb(0, 0, 255), rgb(0, 200, 0), rgb(128, 128, 128), rgb(20, 12, 6), rgb(255, 0, 0))) {
            assertEquals("not skin #${Integer.toHexString(other)}", 0, m(other, preset(ColorPreset.SKIN)))
        }
    }

    @Test
    fun invertAlphaAndAntiAlias() {
        val red = rgb(255, 0, 0)
        val blue = rgb(0, 0, 255)
        val p = sampled(40, red)
        val inv = ColorRange.Params.of(ColorPreset.SAMPLED, 40, intArrayOf(red), invert = true)
        assertEquals(0, m(red, inv))
        assertEquals(255, m(blue, inv))
        // Inverting with no samples takes everything painted.
        val all = ColorRange.Params.of(ColorPreset.SAMPLED, 40, IntArray(0), invert = true)
        assertFalse(all.selectsNothing)
        assertEquals(255, m(blue, all))
        // The alpha multiplies the membership; a transparent pixel is never selected.
        assertEquals(128, m(rgb(255, 0, 0, 128), p))
        assertEquals(0, m(rgb(255, 0, 0, 0), p))
        assertEquals(0, m(rgb(0, 0, 255, 0), inv))
        assertEquals(0, m(0, all))
        for (preset in ColorPreset.entries) {
            val q = ColorRange.Params(preset, 200, IntArray(0), invert = true)
            assertEquals("$preset, inverted, transparent", 0, m(0x00000000, q))
            assertEquals("$preset, inverted, transparent white", 0, m(0x00FFFFFF, q))
        }
        // Anti-alias off: 0 or 255, cut at half.
        val hard = ColorRange.Params(ColorPreset.REDS, 0, IntArray(0), antiAlias = false)
        val soft = ColorRange.Params(ColorPreset.REDS, 0, IntArray(0))
        var partial = 0
        for (g in 0..255 step 3) {
            val c = rgb(255, g, 0)
            val s = m(c, soft)
            val h = m(c, hard)
            if (s in 1..254) partial++
            assertTrue("green $g: $h", h == 0 || h == 255)
            if (s >= 129) assertEquals(255, h)
            if (s <= 126) assertEquals(0, h)
        }
        assertTrue(partial > 0)
        assertEquals(0, m(rgb(255, 0, 0, 100), ColorRange.Params.of(ColorPreset.SAMPLED, 40, intArrayOf(red), antiAlias = false)))
        assertEquals(255, m(rgb(255, 0, 0, 200), ColorRange.Params.of(ColorPreset.SAMPLED, 40, intArrayOf(red), antiAlias = false)))
    }

    @Test
    fun fuzzinessIsClamped() {
        assertEquals(200, ColorRange.Params(ColorPreset.REDS, 999, IntArray(0)).fuzziness)
        assertEquals(0, ColorRange.Params(ColorPreset.REDS, -5, IntArray(0)).fuzziness)
        assertEquals(ColorSelectSettings.MAX_FUZZINESS, ColorRange.Params.MAX_FUZZINESS)
    }
}
