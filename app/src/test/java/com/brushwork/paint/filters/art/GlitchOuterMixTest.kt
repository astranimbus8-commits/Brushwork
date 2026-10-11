package com.brushwork.paint.filters.art

import com.brushwork.paint.filters.art.FringeFixtures.NONE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.8 (item 5): Glitch's row path for a color set whose middle sample feeds no channel
 * (`mixRowOuter`) writes exactly the bytes of the general row path (`mixRow`), on opaque,
 * translucent and transparent samples, inside the row and wrapped around it. Both are private
 * row methods, called here by reflection on the same rows.
 */
class GlitchOuterMixTest {
    private val sets = listOf(
        intArrayOf(0xFFFF8000.toInt(), NONE, 0xFF00A0A0.toInt()),
        intArrayOf(0xFF8040C0.toInt(), NONE, 0xFF40C080.toInt()),
        intArrayOf(0xFF804020.toInt(), NONE, 0xFF2040FF.toInt()),
        intArrayOf(0xFF102030.toInt(), NONE, 0xFF000000.toInt() or 0x30C0A0),
    )

    private val rowArgs = arrayOf(IntArray::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, FringeColors.Mix::class.java)
    private val general = GlitchFilter::class.java.getDeclaredMethod("mixRow", *rowArgs).apply { isAccessible = true }
    private val outer = GlitchFilter::class.java.getDeclaredMethod("mixRowOuter", *rowArgs).apply { isAccessible = true }

    @Test
    fun theOuterRowPathWritesTheGeneralPathsBytes() {
        val g = GlitchFilter()
        val src = FringeFixtures.random(97, 41, 7)
        val w = src.width
        val rnd = Random(11)
        for (colors in sets) {
            assertNull(FringeColors.oneHotFactors(colors))
            val mix = FringeColors.Mix(colors)
            assertTrue("the outer path's set", !mix.colored[1] && mix.uncovered.none { it })
            for (cs in intArrayOf(0, 3, 17, w + 5)) for (row in 0 until src.height) {
                val dxRow = IntArray(w) { rnd.nextInt(-2 * w, 2 * w) }
                val a = IntArray(w); val b = IntArray(w)
                general.invoke(g, src.pixels, row * w, a, 0, w, dxRow, cs, mix)
                outer.invoke(g, src.pixels, row * w, b, 0, w, dxRow, cs, mix)
                assertArrayEquals("${colors.map { Integer.toHexString(it) }} cs $cs row $row", a, b)
            }
        }
    }
}
