package com.brushwork.paint.v19.a3

import android.graphics.Paint
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.text.OpticalKerning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 4 (R5 c), the "Optical" pair kerning's budgets (JVM proxies of the phone's):
 * measuring the profiles of 100 letters from an empty cache takes 30 ms at most, and laying out
 * 1000 characters whose profiles are cached 1 ms at most (the median of 9 runs).
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class OpticalKerningPerfTest {

    @After
    fun emptyAfter() {
        OpticalKerning.trim(MemoryPressure.CRITICAL)
    }

    @Test
    fun warmUpAndCachedLayoutWithinTheirBudgets() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 48f }
        // 100 letters: A-Z, a-z, 0-9 and 38 accented Latin letters.
        val letters = (('A'..'Z') + ('a'..'z') + ('0'..'9')).map { it.toString() } + (0xC0..0xFF).filter { it != 0xD7 && it != 0xF7 }.take(38).map { it.toChar().toString() }
        assertEquals(100, letters.size)
        PerfBudget.measuredWithin(PerfBudget.ms(30.0), what = "Optical: the profiles of 100 letters from an empty cache") {
            OpticalKerning.trim(MemoryPressure.CRITICAL)
            PerfBudget.timeMs { for (l in letters) OpticalKerning.profile(paint, l) }
        }

        val text = buildString { while (length < 1000) append("The quick brown fox jumps over the lazy dog. AVATAR Tony r. ") }.substring(0, 1000)
        assertNotNull("warm: every profile cached", OpticalKerning.advancesPx(text, 0, paint, null))
        PerfBudget.measuredWithin(PerfBudget.ms(1.0), what = "Optical: a layout of 1000 cached characters (median of 9)") {
            val runs = DoubleArray(9) { PerfBudget.timeMs { OpticalKerning.advancesPx(text, 0, paint, null) } }
            runs.sort()
            runs[4]
        }
    }
}
