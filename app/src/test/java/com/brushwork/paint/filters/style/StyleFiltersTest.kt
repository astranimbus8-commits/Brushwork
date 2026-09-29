package com.brushwork.paint.filters.style

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.style.StyleTestImages.alpha
import com.brushwork.paint.filters.style.StyleTestImages.blue
import com.brushwork.paint.filters.style.StyleTestImages.disc
import com.brushwork.paint.filters.style.StyleTestImages.green
import com.brushwork.paint.filters.style.StyleTestImages.luma
import com.brushwork.paint.filters.style.StyleTestImages.red
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** Visual properties of every Style filter on small synthetic images. */
class StyleFiltersTest {
    private val ctx = FilterContext()
    private val black = 0xFF000000.toInt()
    private val orange = 0xFFFF8800.toInt()

    private fun Filter.run(src: PixelBuffer, vararg overrides: Pair<String, Any>, context: FilterContext = ctx): PixelBuffer {
        val v = defaultValues()
        for ((k, value) in overrides) {
            assertTrue("$id has no parameter $k", params.any { it.key == k })
            v.set(k, value)
        }
        return apply(src, v, context)
    }

    /** Disc of radius 20 centred in a 100x100 canvas (pixel x mirrors 99 - x). */
    private fun centredDisc(r: Float = 20f, color: Int = orange) = disc(100, 100, 50f, 50f, r, color)

    private fun assertUnchangedWhereOpaque(src: PixelBuffer, out: PixelBuffer, what: String) {
        for (i in src.pixels.indices) if (alpha(src.pixels[i]) == 255) assertEquals("$what at $i", src.pixels[i], out.pixels[i])
    }

    private fun assertAlphaPreserved(src: PixelBuffer, out: PixelBuffer, what: String) {
        for (i in src.pixels.indices) assertEquals("$what alpha at $i", alpha(src.pixels[i]), alpha(out.pixels[i]))
    }

    private fun assertMirroredLeftRight(img: PixelBuffer, tol: Int, what: String) {
        var worst = 0
        for (y in 0 until img.height) for (x in 0 until img.width / 2) {
            val a = img[x, y]; val b = img[img.width - 1 - x, y]
            worst = max(worst, maxOf(abs(alpha(a) - alpha(b)), abs(red(a) - red(b)), abs(green(a) - green(b)), abs(blue(a) - blue(b))))
        }
        assertTrue("$what not mirror symmetric (max channel difference $worst)", worst <= tol)
    }

    // ------------------------------------------------------------------ registry

    @Test
    fun everyStyleFilterIsRegisteredOnceInTheStyleCategory() {
        assertEquals(16, styleFilters.size)
        assertEquals(styleFilters.size, styleFilters.map { it.id }.toSet().size)
        for (f in styleFilters) {
            assertEquals(FilterCategory.STYLE, f.category)
            assertTrue(f.id, f.id.startsWith("style."))
            assertEquals("${f.id} duplicate keys", f.params.size, f.params.map { it.key }.toSet().size)
            for (p in f.params) if (p is FilterParam.Slider) assertTrue("${f.id}.${p.key} default in range", p.default in p.min..p.max)
        }
    }

    @Test
    fun effectOnlyOutputOfAnEmptyLayerIsEmpty() {
        val empty = PixelBuffer(30, 20)
        for (f in styleFilters) {
            val v = f.defaultValues()
            if (f.params.any { it.key == "output" }) v.set("output", 1)
            val out = f.apply(empty, v, ctx)
            assertTrue("${f.id} drew on an empty layer", out.pixels.all { alpha(it) == 0 })
        }
    }

    /**
     * NaN canary: StyleMath.pack and ColorUtils.clamp255 turn NaN into 0, so a NaN in a coverage
     * or shading term shows up as a hole in the artwork rather than an exception. With the
     * combined output (and the alpha-reducing options neutral) no opaque pixel may lose alpha, at
     * every slider's minimum and maximum.
     */
    @Test
    fun extremeParametersNeverPunchHolesIntoOpaqueArtwork() {
        val src = centredDisc(25f)
        for (f in styleFilters) for (pickMax in listOf(false, true)) {
            val v = f.defaultValues()
            for (p in f.params) when (p) {
                is FilterParam.Slider -> v.set(p.key, if (pickMax) p.max else p.min)
                is FilterParam.Toggle -> v.set(p.key, pickMax)
                is FilterParam.Point -> v.set(p.key, if (pickMax) floatArrayOf(1f, 1f) else floatArrayOf(0f, 0f))
                is FilterParam.Choice -> if (p.key != "output") v.set(p.key, if (pickMax) p.options.lastIndex else 0)
                else -> {}
            }
            if (f.params.any { it.key == "output" }) v.set("output", 0)
            if (f is WetEdgeFilter) v.set("interior", 0f)
            if (f is WaterdropFilter) v.set("transparency", 0f)
            val out = f.apply(src, v, ctx)
            for (i in src.pixels.indices) {
                if (alpha(src.pixels[i]) == 255) {
                    assertEquals("${f.id} (${if (pickMax) "max" else "min"}) alpha at $i", 255, alpha(out.pixels[i]))
                }
            }
            if (f is ReliefFilter || f is ReliefHQFilter) {
                // Shading may darken the orange disc but never to black (a NaN factor would).
                assertTrue("${f.id} (${if (pickMax) "max" else "min"}) went black", red(out[50, 50]) > 0 && red(out[40, 45]) > 0)
            }
        }
    }

    /**
     * Filters work on a crop around the content, or on the whole image when the crop would cover
     * most of it. Faint pixels in the corners force the whole-image path; around the shape both
     * paths must agree.
     */
    @Test
    fun croppedAndWholeImageProcessingAgree() {
        val n = 240
        val small = disc(n, n, 120f, 120f, 14f)
        val spread = small.copy()
        for ((x, y) in listOf(0 to 0, n - 1 to 0, 0 to n - 1, n - 1 to n - 1)) spread[x, y] = 0x01FFFFFF
        for (f in styleFilters) for (output in listOf(0, 1)) {
            val v = f.defaultValues()
            if (output == 1) { if (f.params.none { it.key == "output" }) continue; v.set("output", 1) }
            val a = f.apply(small, v, ctx)
            val b = f.apply(spread, v, ctx)
            var worst = 0
            for (y in 80 until 160) for (x in 80 until 160) {
                val p = a[x, y]; val q = b[x, y]
                worst = max(worst, maxOf(abs(alpha(p) - alpha(q)), abs(red(p) - red(q)), abs(green(p) - green(q)), abs(blue(p) - blue(q))))
            }
            assertTrue("${f.id} output $output: crop and whole-image results differ by $worst", worst <= 2)
        }
    }

    // ------------------------------------------------------------------ strokes

    @Test
    fun strokeOuterSurroundsTheShapeAndKeepsIt() {
        val src = centredDisc()
        val out = StrokeOuterFilter().run(src, "width" to 10f, "color" to black)
        assertEquals(black, out[75, 50])          // 5.5 px outside the edge
        assertEquals(black, out[50, 25])
        assertEquals(0, alpha(out[85, 50]))       // 15.5 px outside
        assertUnchangedWhereOpaque(src, out, "stroke outer")
        // Stroke only: the stroke also fills under the artwork, so there is no gap.
        val only = StrokeOuterFilter().run(src, "width" to 10f, "output" to 1)
        assertEquals(255, alpha(only[50, 50]))
    }

    @Test
    fun strokeWidthScalesWithThePreview() {
        fun rowCoverage(img: PixelBuffer, y: Int) = (0 until img.width).sumOf { alpha(img[it, y]) } / 255.0
        val full = StrokeOuterFilter().run(disc(100, 100, 50f, 50f, 20f), "width" to 10f, "output" to 1)
        val half = StrokeOuterFilter().run(disc(50, 50, 25f, 25f, 10f), "width" to 10f, "output" to 1, context = FilterContext(scale = 0.5f))
        val wFull = rowCoverage(full, 50)
        val wHalf = rowCoverage(half, 25)
        assertEquals(60.0, wFull, 1.0)
        assertEquals(wFull, 2 * wHalf, 1.0)
    }

    @Test
    fun strokeBothStraddlesTheEdge() {
        val src = centredDisc()
        val out = StrokeBothFilter().run(src, "outer_width" to 4f, "inner_width" to 4f, "color" to black)
        assertEquals(black, out[72, 50])          // 2.5 px outside
        assertEquals(black, out[68, 50])          // 1.5 px inside
        assertEquals(src[60, 50], out[60, 50])    // deep inside: artwork
        assertEquals(src[50, 50], out[50, 50])
        assertEquals(0, alpha(out[80, 50]))
        // Zero widths leave the layer alone.
        val none = StrokeBothFilter().run(src, "outer_width" to 0f, "inner_width" to 0f)
        assertArrayEquals(src.pixels, none.pixels)
    }

    @Test
    fun strokeInnerStaysInsideTheShape() {
        val src = centredDisc(color = 0x80FF8800.toInt())
        val out = StrokeInnerFilter().run(src, "width" to 6f, "color" to black)
        assertAlphaPreserved(src, out, "stroke inner")
        assertEquals(0x80000000.toInt(), out[66, 50])
        assertEquals(src[55, 50], out[55, 50])
    }

    // ------------------------------------------------------------------ stained glass & wet edge

    @Test
    fun stainedGlassLeadsColorBoundariesOnly() {
        val src = StyleTestImages.twoColors(100, 40, 50, 0xFFE02020.toInt(), 0xFF2040E0.toInt())
        val lead = 0xFF2B2B2B.toInt()
        val out = StainedGlassFilter().run(src, "width" to 8f, "color" to lead)
        assertEquals(lead, out[49, 20])
        assertEquals(lead, out[50, 20])
        assertEquals(lead, out[46, 20])
        assertEquals(src[43, 20], out[43, 20])
        assertEquals(src[20, 20], out[20, 20])
        assertEquals(src[80, 20], out[80, 20])
        // Line art traced as lead: a dark stroke on a flat fill.
        val art = PixelBuffer(60, 30).fill(0xFFF0E0A0.toInt())
        for (y in 0 until 30) art[30, y] = 0xFF101010.toInt()
        val traced = StainedGlassFilter().run(art, "width" to 12f, "color" to lead, "dark_lines" to true)
        assertEquals(lead, traced[25, 15])
        assertEquals(art[10, 15], traced[10, 15])
    }

    @Test
    fun stainedGlassCellsAreDeterministicPerSeed() {
        val src = StyleTestImages.twoColors(120, 80, 60, 0xFF3060C0.toInt(), 0xFF40A050.toInt())
        val f = StainedGlassCellsFilter()
        val a = f.run(src, "cell_size" to 24f, "seed" to 3)
        val b = f.run(src, "cell_size" to 24f, "seed" to 3)
        val c = f.run(src, "cell_size" to 24f, "seed" to 4)
        assertArrayEquals(a.pixels, b.pixels)
        assertFalse(a.pixels.contentEquals(c.pixels))
        val lead = 0xFF2B2B2B.toInt()
        assertTrue(a.pixels.count { it == lead } > 200)
        assertAlphaPreserved(src, a, "stained glass cells")
    }

    @Test
    fun wetEdgeDarkensEdgesAndThinsTheInterior() {
        val src = StyleTestImages.rect(100, 100, 20, 20, 80, 80, 0xFF4080C0.toInt())
        val out = WetEdgeFilter().run(src, "width" to 16f, "strength" to 60f, "interior" to 30f)
        val edge = out[21, 50]; val centre = out[50, 50]
        assertTrue("edge ${luma(edge)} vs centre ${luma(centre)}", luma(edge) < luma(centre) - 10)
        assertEquals(179f, alpha(centre).toFloat(), 2f)
        assertTrue(alpha(edge) > 235)
        assertEquals(0, alpha(out[10, 10]))
        val identity = WetEdgeFilter().run(src, "strength" to 0f, "interior" to 0f)
        assertArrayEquals(src.pixels, identity.pixels)
    }

    // ------------------------------------------------------------------ glows

    @Test
    fun outerGlowFadesAwayFromTheShape() {
        val src = centredDisc(15f)
        val only = GlowOuterFilter().run(src, "size" to 20f, "output" to 1)
        var prev = 256
        for (x in 66 until 100) {
            val a = alpha(only[x, 50])
            assertTrue("glow increases at x=$x ($a > $prev)", a <= prev + 1)
            prev = a
        }
        assertTrue(alpha(only[67, 50]) > 100)
        assertTrue(alpha(only[99, 50]) <= 3)
        val combined = GlowOuterFilter().run(src)
        assertUnchangedWhereOpaque(src, combined, "outer glow")
        assertTrue(alpha(combined[68, 50]) > 0)
        val crystal = GlowOuterFilter().run(src, "size" to 20f, "crystal" to true, "output" to 1)
        assertEquals(0, alpha(crystal[99, 50]))
        assertTrue(alpha(crystal[70, 50]) > alpha(crystal[80, 50]))
    }

    @Test
    fun innerGlowLightsTheRimInsideTheShape() {
        val src = centredDisc(30f)
        val out = GlowInnerFilter().run(src, "size" to 16f, "color" to -1)
        assertAlphaPreserved(src, out, "inner glow")
        assertTrue(luma(out[21, 50]) > luma(src[21, 50]) + 20)
        assertTrue(abs(luma(out[50, 50]) - luma(src[50, 50])) <= 2)
        val off = GlowInnerFilter().run(src, "opacity" to 0f)
        assertArrayEquals(src.pixels, off.pixels)
    }

    // ------------------------------------------------------------------ lighting

    @Test
    fun outerBevelRimsTheShapeAndFacesTheLight() {
        val src = centredDisc()
        val out = BevelOuterFilter().run(src, "size" to 12f, "light" to floatArrayOf(0.2f, 0.2f))
        assertUnchangedWhereOpaque(src, out, "bevel")
        assertTrue(alpha(out[76, 50]) > 200)
        assertEquals(0, alpha(out[86, 50]))
        assertTrue("lit side ${luma(out[31, 31])} vs shaded ${luma(out[68, 68])}", luma(out[31, 31]) > luma(out[68, 68]) + 20)
    }

    @Test
    fun reliefIsLitFromTheLightAngle() {
        val src = centredDisc(30f)
        val out = ReliefFilter().run(src, "light_angle" to 135f)
        assertAlphaPreserved(src, out, "relief")
        assertTrue(luma(out[34, 34]) > luma(out[65, 65]) + 30)
        val top = ReliefFilter().run(src, "light_angle" to 90f)
        assertMirroredLeftRight(top, 6, "relief lit from the top")
        assertTrue(luma(top[50, 25]) > luma(top[50, 74]))
    }

    @Test
    fun reliefFromBrightnessEmbossesAnOpaqueImage() {
        val src = PixelBuffer(60, 60).fill(0xFF404040.toInt())
        for (y in 20 until 40) for (x in 20 until 40) src[x, y] = 0xFFC0C0C0.toInt()
        val out = ReliefFilter().run(src, "source" to 1, "light_angle" to 180f, "smoothness" to 0f)
        assertAlphaPreserved(src, out, "brightness relief")
        // Light from the left: the left step faces it, the right step faces away.
        assertTrue(luma(out[20, 30]) > luma(out[39, 30]))
    }

    @Test
    fun reliefHqIsLitAndKeepsFlatColors() {
        val src = centredDisc(30f)
        val out = ReliefHQFilter().run(src, "light_angle" to 135f)
        assertAlphaPreserved(src, out, "relief hq")
        assertTrue(luma(out[34, 34]) > luma(out[65, 65]) + 30)
        val top = ReliefHQFilter().run(src, "light_angle" to 90f, "shadows" to false)
        assertMirroredLeftRight(top, 6, "relief hq lit from the top")
        // A flat, unshadowed plateau of a dielectric keeps roughly its own color.
        val slab = StyleTestImages.rect(160, 160, 10, 10, 150, 150, 0xFF7090B0.toInt())
        val flat = ReliefHQFilter().run(slab, "flatness" to 95f, "shadows" to false, "realistic" to false)
        val c = flat[80, 80]
        assertEquals(112f, red(c).toFloat(), 20f)
        assertEquals(144f, green(c).toFloat(), 20f)
        assertEquals(176f, blue(c).toFloat(), 20f)
    }

    @Test
    fun waterdropIsGlossyAndTranslucent() {
        val src = centredDisc(30f, 0xFF9AD0FF.toInt())
        val out = WaterdropFilter().run(src)
        for (i in src.pixels.indices) assertTrue(alpha(out.pixels[i]) <= alpha(src.pixels[i]))
        assertTrue("specular highlight", out.pixels.maxOf { if (alpha(it) > 0) luma(it) else 0 } >= 230)
        val clear = WaterdropFilter().run(src, "transparency" to 100f, "highlight" to 0f)
        assertTrue(alpha(clear[50, 50]) < 20)
        assertTrue(alpha(clear[22, 50]) > 60)
    }

    // ------------------------------------------------------------------ satin, shadow, extrude, rays

    @Test
    fun satinShadesInsideOnly() {
        val src = centredDisc(30f)
        val out = SatinFilter().run(src)
        assertAlphaPreserved(src, out, "satin")
        assertFalse(out.pixels.contentEquals(src.pixels))
        assertArrayEquals(src.pixels, SatinFilter().run(src, "opacity" to 0f).pixels)
        val only = SatinFilter().run(src, "output" to 1)
        assertEquals(0, alpha(only[5, 5]))
        assertFalse(SatinFilter().run(src, "invert" to true).pixels.contentEquals(out.pixels))
    }

    @Test
    fun dropShadowIsOffsetAlongTheAngle() {
        val src = disc(100, 100, 40f, 40f, 10f)
        val shadow = DropShadowFilter().run(src, "angle" to 315f, "distance" to 12f, "blur" to 10f, "output" to 1)
        val cs = StyleTestImages.alphaCentroid(shadow)!!
        val cd = StyleTestImages.alphaCentroid(src)!!
        val d = 12f / kotlin.math.sqrt(2f)
        assertEquals(cd[0] + d, cs[0], 0.3f)
        assertEquals(cd[1] + d, cs[1], 0.3f)
        assertTrue(shadow.pixels.all { alpha(it) <= 154 })
        val combined = DropShadowFilter().run(src)
        assertUnchangedWhereOpaque(src, combined, "drop shadow")
        // Hard shadow: blur 0, full opacity, straight right.
        val hard = DropShadowFilter().run(src, "angle" to 0f, "distance" to 20f, "blur" to 0f, "opacity" to 100f, "color" to black, "output" to 1)
        assertEquals(black, hard[59, 39])
        assertEquals(0, alpha(hard[25, 39]))
    }

    @Test
    fun extrusionGrowsAlongTheDirectionOnly() {
        val src = disc(120, 100, 40f, 50f, 15f)
        val only = ExtrudeParallelFilter().run(src, "angle" to 0f, "depth" to 30f, "side_shading" to 0f, "output" to 1)
        assertEquals(255, alpha(only[65, 49]))
        assertEquals(0, alpha(only[20, 49]))
        assertEquals(0, alpha(only[90, 49]))
        assertEquals(0, alpha(only[70, 28]))
        assertTrue("farther is darker", luma(only[58, 49]) > luma(only[82, 49]))
        val combined = ExtrudeParallelFilter().run(src, "angle" to 0f, "depth" to 30f)
        assertUnchangedWhereOpaque(src, combined, "extrude")
        assertTrue(alpha(combined[70, 49]) == 255)
    }

    @Test
    fun godRaysStreamAwayFromTheLight() {
        val src = disc(120, 100, 60f, 50f, 8f)
        val rays = GodRaysFilter().run(src, "light" to floatArrayOf(0.1f, 0.5f), "output" to 1)
        assertTrue("behind ${alpha(rays[90, 49])} vs before ${alpha(rays[30, 49])}", alpha(rays[90, 49]) > alpha(rays[30, 49]) + 20)
        // Light behind the layer: an opaque bar casts a dark shaft away from the light.
        val bar = StyleTestImages.rect(160, 100, 100, 40, 106, 60, 0xFF202020.toInt())
        val behind = GodRaysFilter().run(bar, "source" to 2, "light" to floatArrayOf(0.5f, 0.5f), "source_size" to 60f, "length" to 90f, "brightness" to 40f, "output" to 0)
        assertUnchangedWhereOpaque(bar, behind, "god rays behind")
        assertTrue("shaft ${alpha(behind[130, 49])} vs open ${alpha(behind[29, 49])}", alpha(behind[130, 49]) < alpha(behind[29, 49]) - 10)
        assertArrayEquals(PixelBuffer(20, 10).pixels, GodRaysFilter().run(PixelBuffer(20, 10)).pixels)
    }
}
