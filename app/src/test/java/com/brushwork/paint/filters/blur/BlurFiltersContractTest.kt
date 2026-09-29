package com.brushwork.paint.filters.blur

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterContext
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurFiltersContractTest {

    @Test
    fun everyBlurFilterIsRegisteredInItsCategory() {
        assertEquals(9, blurFilters.size)
        assertEquals(blurFilters.size, blurFilters.map { it.id }.toSet().size)
        for (f in blurFilters) {
            assertTrue(f.id, f.id.startsWith("blur."))
            assertEquals(FilterCategory.BLUR, f.category)
            assertTrue("${f.id} registered", FilterRegistry.byId(f.id) === f)
            assertFalse("${f.id} needs content", f.generatesContent)
            assertEquals("${f.id} param keys unique", f.params.size, f.params.map { it.key }.toSet().size)
        }
    }

    @Test
    fun pixelDistancesFollowThePreviewScale() {
        // Rendering at preview scale 0.5 must equal rendering with every pixel distance halved.
        val src = randomImage(48, 36, seed = 21)
        for (f in blurFilters) {
            val scaled = f.apply(src, f.defaultValues(), FilterContext(scale = 0.5f))
            val halved = f.defaultValues()
            for (p in f.params) if (p is FilterParam.Slider && p.pixels) halved.set(p.key, p.default * 0.5f)
            val direct = f.apply(src, halved, FilterContext(scale = 1f))
            assertSameImage("${f.id} preview scale", direct, scaled)
        }
    }

    @Test
    fun transparentInputStaysTransparent() {
        for (f in blurFilters) {
            val out = f.apply(PixelBuffer(23, 17), f.defaultValues(), FilterContext())
            assertTrue("${f.id} drew on an empty layer", out.pixels.all { it == 0 })
        }
    }

    @Test
    fun onePixelImagesAreUnchanged() {
        for (f in blurFilters) {
            val src = PixelBuffer(1, 1).fill(0x80336699.toInt())
            val out = f.apply(src, f.defaultValues(), FilterContext())
            assertEquals(f.id, src[0, 0], out[0, 0])
        }
    }
}
