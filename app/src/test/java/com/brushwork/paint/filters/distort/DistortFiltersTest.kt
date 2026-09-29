package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class DistortFiltersTest {

    private val ctx = FilterContext()

    // ------------------------------------------------------------------ fixtures

    private inline fun <reified T : Filter> filter(): T = distortFilters.filterIsInstance<T>().single()

    private fun Filter.run(src: PixelBuffer, vararg kv: Pair<String, Any>, context: FilterContext = ctx): PixelBuffer {
        val v = defaultValues()
        for ((k, value) in kv) v.set(k, value)
        return apply(src, v, context)
    }

    /** Opaque image whose red channel is the distance to the center (clamped) and blue the angle. */
    private fun radialImage(size: Int): PixelBuffer {
        val b = PixelBuffer(size, size)
        val c = size / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x + 0.5f - c; val dy = y + 0.5f - c
            val r = sqrt(dx * dx + dy * dy)
            val ang = (atan2(dy, dx) / (2 * Math.PI) + 0.5) * 255
            b[x, y] = ColorUtils.argb(255, r.toInt().coerceAtMost(255), 128, ang.toInt())
        }
        return b
    }

    /** Opaque image with red = x, green = y (both scaled to 0..255). */
    private fun coordImage(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) b[x, y] = ColorUtils.argb(255, x * 255 / (w - 1), y * 255 / (h - 1), 90)
        return b
    }

    /** Smooth, low-frequency opaque image (resampling-friendly). */
    private fun smoothImage(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x.toFloat() / w; val v = y.toFloat() / h
            b[x, y] = ColorUtils.argb(
                255,
                (128 + 100 * sin(u * 6.28f)).toInt(),
                (128 + 100 * sin(v * 6.28f + 1f)).toInt(),
                ((u + v) * 120).toInt(),
            )
        }
        return b
    }

    private fun maxChannelDiff(a: Int, b: Int): Int = maxOf(
        abs(ColorUtils.alpha(a) - ColorUtils.alpha(b)),
        abs(ColorUtils.red(a) - ColorUtils.red(b)),
        abs(ColorUtils.green(a) - ColorUtils.green(b)),
        abs(ColorUtils.blue(a) - ColorUtils.blue(b)),
    )

    private fun meanDiff(a: PixelBuffer, b: PixelBuffer): Double {
        var sum = 0.0
        for (i in a.pixels.indices) {
            val p = a.pixels[i]; val q = b.pixels[i]
            sum += abs(ColorUtils.red(p) - ColorUtils.red(q)) + abs(ColorUtils.green(p) - ColorUtils.green(q)) +
                abs(ColorUtils.blue(p) - ColorUtils.blue(q)) + abs(ColorUtils.alpha(p) - ColorUtils.alpha(q))
        }
        return sum / (a.size * 4)
    }

    // ------------------------------------------------------------------ registration

    @Test
    fun allFiltersRegisteredWithExpectedIdsAndCategories() {
        val ids = distortFilters.map { it.id }.toSet()
        val expected = setOf(
            "distort.expansion", "distort.fish_lens", "distort.sphere_lens", "distort.wave", "distort.ripple",
            "distort.twirl", "distort.polar_coordinates", "distort.tile_count", "distort.tile_size",
            "frame.blur_frame", "frame.rain", "frame.table_count", "frame.table_size",
        )
        assertEquals(expected, ids)
        for (f in distortFilters) {
            val cat = if (f.id.startsWith("frame.")) FilterCategory.FRAME else FilterCategory.DISTORT
            assertEquals(f.id, cat, f.category)
            assertNotNull(FilterRegistry.byId(f.id))
            assertEquals(f.id, f.params.size, f.params.map { it.key }.toSet().size)
        }
    }

    // ------------------------------------------------------------------ identity at neutral settings

    @Test
    fun neutralSettingsLeaveImageUnchanged() {
        val src = smoothImage(41, 37)
        val cases: List<Pair<Filter, Array<Pair<String, Any>>>> = listOf(
            filter<ExpansionFilter>() to arrayOf("amount" to 0f),
            filter<FishLensFilter>() to arrayOf("distortion" to 0f),
            filter<SphereLensFilter>() to arrayOf("strength" to 0f),
            filter<WaveFilter>() to arrayOf("amplitude" to 0f),
            filter<RippleFilter>() to arrayOf("amplitude" to 0f),
            filter<TwirlFilter>() to arrayOf("twist" to 0f),
            filter<TileCountFilter>() to arrayOf("columns" to 1f, "rows" to 1f, "gap" to 0f, "mirror" to 3, "offset" to 0f),
            filter<TileSizeFilter>() to arrayOf("width" to 41f, "height" to 37f, "fit" to TileRenderer.FIT_STRETCH, "gap" to 0f),
            filter<BlurFrameFilter>() to arrayOf("size" to 0f),
            filter<BlurFrameFilter>() to arrayOf("opacity" to 0f, "blur" to 0f),
            filter<RainFilter>() to arrayOf("amount" to 0f),
        )
        for ((f, kv) in cases) {
            val out = f.run(src, *kv)
            assertArrayEquals("${f.id} should be identity", src.pixels, out.pixels)
        }
    }

    // ------------------------------------------------------------------ lenses

    private fun redAt(b: PixelBuffer, x: Int, y: Int) = ColorUtils.red(b[x, y])

    @Test
    fun positiveLensesMagnifyAndNegativeShrink() {
        val src = radialImage(101)
        val lenses = listOf(
            Triple(filter<ExpansionFilter>(), "amount", arrayOf<Pair<String, Any>>("radius" to 40f)),
            Triple(filter<FishLensFilter>(), "distortion", arrayOf<Pair<String, Any>>("radius" to 80f)),
            Triple(filter<SphereLensFilter>(), "strength", arrayOf<Pair<String, Any>>("radius" to 80f)),
        )
        for ((f, key, extra) in lenses) {
            val bulge = f.run(src, key to 70f, *extra)
            val pinch = f.run(src, key to -70f, *extra)
            // 20 px right of the center (inside every lens): magnified content comes from closer in.
            val x = 50 + 20
            assertTrue("${f.id} bulge", redAt(bulge, x, 50) < redAt(src, x, 50) - 3)
            assertTrue("${f.id} pinch", redAt(pinch, x, 50) > redAt(src, x, 50) + 3)
            // Far outside the lens radius nothing changes.
            assertEquals("${f.id} corner", src[0, 0], bulge[0, 0])
            assertEquals("${f.id} corner", src[0, 0], pinch[0, 0])
            // Radial mappings keep the angle (blue) of every pixel away from the seam.
            assertTrue("${f.id} angle", abs(ColorUtils.blue(bulge[x, 60]) - ColorUtils.blue(src[x, 60])) <= 3)
        }
    }

    @Test
    fun expansionPinchUndoesBulge() {
        val src = smoothImage(90, 90)
        val f = filter<ExpansionFilter>()
        val there = f.run(src, "amount" to 40f, "radius" to 40f)
        val back = f.run(there, "amount" to -40f, "radius" to 40f)
        var worst = 0
        for (y in 10 until 80) for (x in 10 until 80) worst = max(worst, maxChannelDiff(src[x, y], back[x, y]))
        assertTrue("round trip error $worst", worst <= 8)
    }

    private fun checkerboard(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) b[x, y] = if ((x + y) % 2 == 0) -1 else 0xFF000000.toInt()
        return b
    }

    /** Mean distance from mid-gray of the pixels within [radius] of the image center. */
    private fun grayDeviation(b: PixelBuffer, radius: Float): Double {
        var sum = 0.0; var n = 0
        val cx = b.width / 2f; val cy = b.height / 2f
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            if (dx * dx + dy * dy > radius * radius) continue
            sum += abs(ColorUtils.red(b[x, y]) - 127.5); n++
        }
        return sum / n
    }

    @Test
    fun strongPinchIsAntialiased() {
        // A 1-px checkerboard squeezed ~10-20x must average out to gray instead of sparkling.
        val src = checkerboard(200, 200)
        val out = filter<ExpansionFilter>().run(src, "amount" to -100f, "radius" to 90f)
        // Same mapping, plain bilinear (what the filter did before supersampling).
        val s = 0.95f
        val profile = RadialProfile.inverseOf { t -> t * (1f - s + s * t * t) }
        val plain = DistortMath.warp(src, ctx, 0, 0, 200, 200, DistortMath.EDGE_CLAMP) { px, py, q ->
            val dx = px - 100f; val dy = py - 100f
            val r = sqrt(dx * dx + dy * dy)
            if (r >= 90f) false else {
                val k = profile.ratioAt(r / 90f)
                q[0] = 100f + dx * k; q[1] = 100f + dy * k
                true
            }
        }
        val aa = grayDeviation(out, 20f)
        val aliased = grayDeviation(plain, 20f)
        assertTrue("antialiased $aa vs plain $aliased", aa < aliased * 0.5 && aa < 25.0)
    }

    @Test
    fun sphereLensHasNoHardRingAtTheRim() {
        // Red = half the distance to the center: a clean spherize keeps it monotonic and smooth,
        // without a jump where the asin mapping's slope becomes infinite.
        val size = 601
        val src = PixelBuffer(size, size)
        val c = size / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x + 0.5f - c; val dy = y + 0.5f - c
            src[x, y] = ColorUtils.argb(255, (sqrt(dx * dx + dy * dy) / 2f).toInt().coerceAtMost(255), 0, 0)
        }
        // Radius 80% = 240 px: the rim lies inside the image, at x = 540.
        val out = filter<SphereLensFilter>().run(src, "strength" to 100f, "radius" to 80f)
        var worst = 0
        for (x in 301 until size) {
            val step = ColorUtils.red(out[x, 300]) - ColorUtils.red(out[x - 1, 300])
            assertTrue("not monotonic at $x", step >= -1)
            worst = max(worst, step)
        }
        assertTrue("largest step $worst", worst <= 4)
        // Still a real lens: the middle is magnified (content comes from closer to the center).
        assertTrue(ColorUtils.red(out[400, 300]) < ColorUtils.red(src[400, 300]) - 8)
    }

    @Test
    fun fastAtan2MatchesMath() {
        for (i in -40..40) for (j in -40..40) {
            if (i == 0 && j == 0) continue
            val y = i * 0.37f; val x = j * 1.13f
            val expected = kotlin.math.atan2(y, x)
            assertEquals("atan2($y, $x)", expected, DistortMath.atan2(y, x), 3e-5f)
        }
        assertEquals(0f, DistortMath.atan2(0f, 0f), 0f)
        assertEquals(0f, DistortMath.atan2(Float.NaN, 1f), 0f)
    }

    @Test
    fun supersampleCountFollowsMinification() {
        assertEquals(1, DistortMath.samplesFor(0.3f))
        assertEquals(1, DistortMath.samplesFor(1.4f))
        assertEquals(2, DistortMath.samplesFor(2.5f))
        assertEquals(DistortMath.MAX_SUPERSAMPLE, DistortMath.samplesFor(1000f))
        assertEquals(DistortMath.MAX_SUPERSAMPLE, DistortMath.samplesFor(Float.POSITIVE_INFINITY))
        assertEquals(1, DistortMath.samplesFor(Float.NaN))
        // Magnifying lenses stay plain bilinear; pinching ones supersample near the center.
        val bulge = RadialProfile.of { t -> t * (0.4f + 0.6f * t * t) }
        val pinch = RadialProfile.inverseOf { t -> t * (0.4f + 0.6f * t * t) }
        assertEquals(1, bulge.samplesAt(0.1f))
        assertTrue(pinch.samplesAt(0.05f) >= 2)
    }

    @Test
    fun radialProfileInverseComposesToIdentity() {
        val f = { t: Float -> t * (1f - 0.6f + 0.6f * t * t) }
        val fwd = RadialProfile.of(f)
        val inv = RadialProfile.inverseOf(f)
        for (i in 1..50) {
            val t = i / 50f
            val u = t * fwd.ratioAt(t)
            assertEquals(f(t), u, 1e-3f)
            val back = u * inv.ratioAt(u)
            assertEquals("t=$t", t, back, 2e-3f)
        }
    }

    // ------------------------------------------------------------------ wave / ripple / twirl

    @Test
    fun waveDisplacesOnlyPerpendicularAndWithinAmplitude() {
        val src = coordImage(128, 128)
        val f = filter<WaveFilter>()
        val amp = 6f
        // Angle 0: travels along x, displaces along y. Green encodes y (about 2 levels per pixel).
        val out = f.run(src, "amplitude" to amp, "wavelength" to 32f, "angle" to 0f)
        var moved = 0
        for (y in 10 until 118) for (x in 0 until 128) {
            val o = out[x, y]; val s = src[x, y]
            // Supersampled steep parts may round differently by one level (half a pixel).
            assertTrue("x unchanged at $x,$y", abs(ColorUtils.red(s) - ColorUtils.red(o)) <= 1)
            val dy = abs(ColorUtils.green(o) - ColorUtils.green(s)) * 127f / 255f
            assertTrue("displacement $dy", dy <= amp + 1f)
            if (dy > 2f) moved++
        }
        assertTrue(moved > 1000)
        // Angle 90: displaces along x only.
        val out90 = f.run(src, "amplitude" to amp, "wavelength" to 32f, "angle" to 90f)
        for (y in 0 until 128) for (x in 10 until 118) {
            assertTrue(abs(ColorUtils.green(src[x, y]) - ColorUtils.green(out90[x, y])) <= 1)
        }
    }

    @Test
    fun steepWaveIsAntialiased() {
        // Horizontal 1-px stripes, strongly sheared by a steep vertical wave: the folded areas
        // must average toward gray instead of showing random full-contrast stripes.
        val src = PixelBuffer(160, 160)
        for (y in 0 until 160) for (x in 0 until 160) src[x, y] = if (y % 2 == 0) -1 else 0xFF000000.toInt()
        val out = filter<WaveFilter>().run(src, "amplitude" to 40f, "wavelength" to 40f, "angle" to 0f)
        // Same displacement, plain bilinear.
        val plain = DistortMath.warp(src, ctx, 0, 0, 160, 160, DistortMath.EDGE_CLAMP) { px, py, q ->
            q[0] = px
            q[1] = py - 40f * sin((px - 80f) / 40f * 2f * Math.PI.toFloat())
            true
        }
        fun extremes(b: PixelBuffer): Int {
            var n = 0
            for (y in 45 until 115) for (x in 0 until 160) {
                val r = ColorUtils.red(b[x, y])
                if (r < 40 || r > 215) n++
            }
            return n
        }
        val aa = extremes(out)
        val aliased = extremes(plain)
        assertTrue("antialiased $aa vs plain $aliased", aa * 3 < aliased)
    }

    @Test
    fun waveEdgeModes() {
        val src = coordImage(64, 64)
        val f = filter<WaveFilter>()
        val transparent = f.run(src, "amplitude" to 10f, "wavelength" to 40f, "edges" to DistortMath.EDGE_TRANSPARENT)
        val clamp = f.run(src, "amplitude" to 10f, "wavelength" to 40f, "edges" to DistortMath.EDGE_CLAMP)
        val wrap = f.run(src, "amplitude" to 10f, "wavelength" to 40f, "edges" to DistortMath.EDGE_WRAP)
        assertTrue(transparent.pixels.any { ColorUtils.alpha(it) == 0 })
        assertTrue(clamp.pixels.all { ColorUtils.alpha(it) == 255 })
        assertTrue(wrap.pixels.all { ColorUtils.alpha(it) == 255 })
        // Wrapping brings content from the bottom rows to the top.
        assertTrue(wrap.pixels.take(64 * 3).any { ColorUtils.green(it) > 200 })
    }

    @Test
    fun rippleIsMirrorSymmetricAroundItsCenter() {
        val src = radialImage(100)
        val out = filter<RippleFilter>().run(src, "amplitude" to 8f, "wavelength" to 12f, "radius" to 90f)
        var changed = 0
        for (d in 0 until 50) for (e in 0 until 50) {
            // Red encodes the distance to the center, which mirroring preserves.
            val a = out[50 + d, 50 + e]
            assertTrue(abs(ColorUtils.red(a) - ColorUtils.red(out[49 - d, 50 + e])) <= 1)
            assertTrue(abs(ColorUtils.red(a) - ColorUtils.red(out[50 + d, 49 - e])) <= 1)
            if (abs(ColorUtils.red(a) - ColorUtils.red(src[50 + d, 50 + e])) > 1) changed++
        }
        assertTrue(changed > 200)
    }

    @Test
    fun twirlKeepsDistanceFromCenterButRotates() {
        val src = radialImage(101)
        val out = filter<TwirlFilter>().run(src, "twist" to 180f, "radius" to 90f)
        var rotated = 0
        for (y in 10 until 91) for (x in 10 until 91) {
            // Red = distance: preserved by a rotation about the center.
            assertTrue(abs(ColorUtils.red(out[x, y]) - ColorUtils.red(src[x, y])) <= 2)
            if (abs(ColorUtils.blue(out[x, y]) - ColorUtils.blue(src[x, y])) > 10) rotated++
        }
        assertTrue(rotated > 1000)
        assertEquals(src[0, 0], out[0, 0])
    }

    // ------------------------------------------------------------------ polar

    @Test
    fun polarRoundTripRestoresMiddleBand() {
        val src = smoothImage(96, 96)
        val f = filter<PolarCoordinatesFilter>()
        val polar = f.run(src, "mode" to 0)
        // Outside the inscribed disc the result is transparent; inside it is opaque.
        assertEquals(0, ColorUtils.alpha(polar[0, 0]))
        assertEquals(255, ColorUtils.alpha(polar[48, 20]))
        val back = f.run(polar, "mode" to 1)
        var worst = 0
        // Rows near the top map to the rim, near the bottom to the (information-poor) center.
        for (y in 8 until 60) for (x in 4 until 92) worst = max(worst, maxChannelDiff(src[x, y], back[x, y]))
        assertTrue("round trip error $worst", worst <= 24)
    }

    @Test
    fun polarPutsTopEdgeOnRimAndCanFlip() {
        val src = coordImage(64, 64)
        val f = filter<PolarCoordinatesFilter>()
        val normal = f.run(src, "mode" to 0)
        val flipped = f.run(src, "mode" to 0, "insideOut" to true)
        // Just inside the rim at 12 o'clock: green (= y) is small normally, large when flipped.
        assertTrue(ColorUtils.green(normal[32, 2]) < 40)
        assertTrue(ColorUtils.green(flipped[32, 2]) > 215)
        // Margins leave an empty ring near the rim.
        val margins = f.run(src, "mode" to 0, "marginV" to 100f)
        assertEquals(0, ColorUtils.alpha(margins[32, 3]))
    }

    // ------------------------------------------------------------------ tiles

    @Test
    fun tileCountRepeatsAreaAveragedCopies() {
        val src = smoothImage(64, 48)
        val out = filter<TileCountFilter>().run(src, "columns" to 2f, "rows" to 2f)
        for (y in 0 until 24) for (x in 0 until 32) {
            assertEquals(out[x, y], out[x + 32, y])
            assertEquals(out[x, y], out[x, y + 24])
            var r = 0; var g = 0; var b = 0
            for (k in 0 until 4) {
                val c = src[2 * x + (k and 1), 2 * y + (k shr 1)]
                r += ColorUtils.red(c); g += ColorUtils.green(c); b += ColorUtils.blue(c)
            }
            val o = out[x, y]
            assertTrue(abs(ColorUtils.red(o) - r / 4f) <= 1f && abs(ColorUtils.green(o) - g / 4f) <= 1f && abs(ColorUtils.blue(o) - b / 4f) <= 1f)
        }
    }

    @Test
    fun tileMirrorGapAndOffset() {
        val src = coordImage(64, 64)
        val f = filter<TileCountFilter>()
        val mirrored = f.run(src, "columns" to 2f, "rows" to 1f, "mirror" to 1)
        for (y in 0 until 64) for (x in 0 until 32) assertEquals(mirrored[x, y], mirrored[63 - x, y])

        val gap = f.run(src, "columns" to 2f, "rows" to 2f, "gap" to 8f)
        assertEquals(0, ColorUtils.alpha(gap[32, 10])) // between columns
        assertEquals(0, ColorUtils.alpha(gap[1, 1])) // half gap at the border
        assertEquals(255, ColorUtils.alpha(gap[16, 16]))

        val offset = f.run(src, "columns" to 4f, "rows" to 2f, "offset" to 50f)
        // Odd rows are shifted right by half a tile (8 px).
        for (x in 0 until 64) assertEquals(offset[x, 5], offset[(x + 8) % 64, 37])
    }

    @Test
    fun tileSizeIsPeriodicAndCentersATile() {
        val src = smoothImage(90, 70)
        val out = filter<TileSizeFilter>().run(src, "width" to 30f, "height" to 20f, "fit" to TileRenderer.FIT_STRETCH)
        for (y in 0 until 50) for (x in 0 until 60) {
            assertEquals(out[x, y], out[x + 30, y])
            assertEquals(out[x, y], out[x, y + 20])
        }
        // A tile is centered on the canvas center: its middle shows the image middle.
        assertTrue(maxChannelDiff(out[45, 35], src[45, 35]) <= 12)
        // Fit inside leaves transparent bands when proportions differ.
        val fit = filter<TileSizeFilter>().run(src, "width" to 45f, "height" to 70f, "fit" to TileRenderer.FIT_CONTAIN)
        assertTrue(fit.pixels.any { ColorUtils.alpha(it) == 0 })
    }

    // ------------------------------------------------------------------ frame filters

    @Test
    fun blurFrameDarkensEdgesAndKeepsCenter() {
        val src = PixelBuffer.filled(120, 100, 0xFFC0C0C0.toInt())
        for (shape in 0..1) {
            val out = filter<BlurFrameFilter>().run(src, "shape" to shape, "size" to 30f, "softness" to 60f)
            assertEquals(src[60, 50], out[60, 50])
            assertTrue(ColorUtils.luminance(out[0, 0]) < 90)
            assertTrue(ColorUtils.luminance(out[0, 50]) < ColorUtils.luminance(out[20, 50]))
            assertTrue(out.pixels.all { ColorUtils.alpha(it) == 255 })
        }
        // Blur only (no color) on a uniform image changes nothing visible.
        val blurOnly = filter<BlurFrameFilter>().run(src, "opacity" to 0f, "blur" to 30f)
        assertTrue(blurOnly.pixels.all { maxChannelDiff(it, src[0, 0]) <= 1 })
    }

    @Test
    fun blurFrameWorkingCopyIsBounded() {
        // Small images and radii blur at full resolution; big ones never exceed the pixel budget.
        assertEquals(1f, BlurFrameFilter.blurWorkScale(200, 200, 8f), 0f)
        assertEquals(0.5f, BlurFrameFilter.blurWorkScale(200, 200, 24f), 1e-6f)
        for (blur in listOf(0.5f, 2f, 8f, 12f, 40f, 300f)) {
            val f = BlurFrameFilter.blurWorkScale(4000, 5000, blur)
            val pixels = kotlin.math.ceil(4000 * f).toLong() * kotlin.math.ceil(5000 * f).toLong()
            assertTrue("blur $blur: $pixels px", pixels <= BlurFrameFilter.MAX_WORK_PIXELS * 1.01)
            assertTrue(f > 0f)
        }
        // A tall, thin 1 px image stays valid.
        assertTrue(BlurFrameFilter.blurWorkScale(1, 20000, 5f) > 0f)
    }

    @Test
    fun blurFrameDrawsOnTransparentLayer() {
        val src = PixelBuffer(80, 80)
        val out = filter<BlurFrameFilter>().run(src, "color" to 0xFF102030.toInt(), "opacity" to 100f)
        assertEquals(0, out[40, 40])
        val edge = out[0, 40]
        assertTrue(ColorUtils.alpha(edge) > 150)
        assertEquals(0x102030, edge and 0xFFFFFF)
    }

    @Test
    fun blurFrameBlurSoftensEdgeDetail() {
        val src = PixelBuffer(100, 100)
        for (y in 0 until 100) for (x in 0 until 100) src[x, y] = if ((x / 2 + y / 2) % 2 == 0) -1 else 0xFF000000.toInt()
        val out = filter<BlurFrameFilter>().run(src, "opacity" to 0f, "blur" to 20f, "size" to 40f)
        // Near the edge the checkerboard is blurred towards gray; the center is untouched.
        val e = ColorUtils.red(out[2, 50])
        assertTrue("edge $e", e in 60..195)
        assertEquals(src[50, 50], out[50, 50])
    }

    @Test
    fun rainIsDeterministicAndUsesItsColor() {
        val src = PixelBuffer(160, 120)
        val f = filter<RainFilter>()
        val a = f.run(src, "seed" to 7, "amount" to 60f, "color" to 0xFF8090A0.toInt())
        val b = f.run(src, "seed" to 7, "amount" to 60f, "color" to 0xFF8090A0.toInt())
        val c = f.run(src, "seed" to 8, "amount" to 60f, "color" to 0xFF8090A0.toInt())
        assertArrayEquals(a.pixels, b.pixels)
        assertFalse(a.pixels.contentEquals(c.pixels))
        val drawn = a.pixels.filter { ColorUtils.alpha(it) > 0 }
        assertTrue(drawn.size > 200)
        assertTrue(drawn.all { it and 0xFFFFFF == 0x8090A0 })
        // Opacity 70% caps the alpha of the streaks.
        assertTrue(drawn.all { ColorUtils.alpha(it) <= 179 })
    }

    @Test
    fun rainNeverRemovesContentAndMatchesPreviewDensity() {
        val src = smoothImage(200, 160)
        val f = filter<RainFilter>()
        val out = f.run(src)
        assertTrue(out.pixels.all { ColorUtils.alpha(it) == 255 })
        fun coverage(w: Int, h: Int, scale: Float): Double {
            val r = f.run(PixelBuffer(w, h), context = FilterContext(scale = scale))
            return r.pixels.sumOf { ColorUtils.alpha(it).toDouble() } / (w * h)
        }
        val full = coverage(400, 300, 1f)
        val preview = coverage(200, 150, 0.5f)
        assertTrue("full $full preview $preview", abs(full - preview) / full < 0.2)
    }

    // ------------------------------------------------------------------ preview scale consistency

    @Test
    fun previewAtHalfScaleMatchesDownscaledFullResult() {
        val full = smoothImage(128, 96)
        val half = DistortMath.downscale(full, 64, 48, ctx)
        for (f in distortFilters) {
            if (f is RainFilter) continue // covered by the density test (thin streaks resample poorly)
            val v = f.defaultValues()
            // Keep pixel-sized parameters meaningful at this tiny size.
            if (f is ExpansionFilter) v.set("radius", 40f)
            if (f is TileSizeFilter) { v.set("width", 40f); v.set("height", 32f) }
            if (f is BlurFrameFilter) v.set("blur", 8f)
            val a = DistortMath.downscale(f.apply(full, v, FilterContext(scale = 1f)), 64, 48, ctx)
            val b = f.apply(half, v, FilterContext(scale = 0.5f))
            val diff = meanDiff(a, b)
            assertTrue("${f.id} preview differs by $diff", diff < 6.0)
        }
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun downscaleAveragesInPremultipliedSpace() {
        val src = PixelBuffer(4, 2)
        src[0, 0] = 0xFFFF0000.toInt(); src[1, 0] = 0; src[0, 1] = 0xFFFF0000.toInt(); src[1, 1] = 0
        src[2, 0] = -1; src[3, 0] = 0xFF000000.toInt(); src[2, 1] = -1; src[3, 1] = 0xFF000000.toInt()
        val out = DistortMath.downscale(src, 2, 1, ctx)
        assertEquals(ColorUtils.argb(128, 255, 0, 0), out[0, 0]) // half-covered red stays red
        assertTrue(maxChannelDiff(out[1, 0], ColorUtils.argb(255, 128, 128, 128)) <= 1)
    }

    @Test
    fun blurImageIsAlphaCorrect() {
        val src = PixelBuffer(40, 40)
        for (y in 15 until 25) for (x in 15 until 25) src[x, y] = 0xFF00FF00.toInt()
        val out = DistortMath.blurImage(src, 6f, ctx)
        val halo = out[12, 20]
        assertTrue(ColorUtils.alpha(halo) in 1..254)
        assertEquals(0x00FF00, halo and 0xFFFFFF)
        val sumIn = src.pixels.sumOf { ColorUtils.alpha(it) }
        val sumOut = out.pixels.sumOf { ColorUtils.alpha(it) }
        assertTrue(abs(sumIn - sumOut) < sumIn * 0.02)
    }

    @Test
    fun wrapSamplingIsSeamless() {
        val src = PixelBuffer(4, 1)
        src[0, 0] = 0xFF000000.toInt(); src[3, 0] = 0xFFFFFFFF.toInt()
        src[1, 0] = 0xFF000000.toInt(); src[2, 0] = 0xFF000000.toInt()
        // Exactly between the last and the first pixel.
        assertTrue(maxChannelDiff(DistortMath.sampleWrap(src, 4f, 0.5f), ColorUtils.argb(255, 128, 128, 128)) <= 1)
        assertEquals(src[0, 0], DistortMath.sampleWrap(src, 4.5f, 0.5f))
        assertEquals(src[3, 0], DistortMath.sampleWrap(src, -0.5f, 0.5f))
    }

    @Test
    fun everyFilterStopsWhenCancelled() {
        val src = smoothImage(96, 80)
        for (f in distortFilters) {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val cancelling = FilterContext(cancelled = { calls.incrementAndGet() > 2 })
            try {
                f.apply(src, f.defaultValues(), cancelling)
                throw AssertionError("${f.id} ignored cancellation")
            } catch (_: java.util.concurrent.CancellationException) {
                // expected
            }
        }
    }

    @Test
    fun extremeValuesProduceFiniteResultsOnTinyImages() {
        for (f in distortFilters) for (size in listOf(1 to 1, 2 to 1, 1 to 3, 5 to 4)) {
            val src = PixelBuffer.filled(size.first, size.second, 0xFF336699.toInt())
            for (pick in 0..1) {
                val v: FilterValues = f.defaultValues()
                for (p in f.params) if (p is com.brushwork.paint.filters.FilterParam.Slider) v.set(p.key, if (pick == 0) p.min else p.max)
                val out = f.apply(src, v, ctx)
                assertEquals(src.width, out.width)
                // Opaque single-color input: distortions keep it (no transparent holes, no garbage).
                if (!f.generatesContent && f !is PolarCoordinatesFilter && f !is TileCountFilter && f !is TileSizeFilter) {
                    assertTrue("${f.id} $size", out.pixels.all { maxChannelDiff(it, 0xFF336699.toInt()) <= 1 })
                }
            }
        }
    }
}
