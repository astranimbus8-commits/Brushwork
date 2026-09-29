package com.brushwork.paint.segmentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.segmentation.SegTestImages.mean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The real service off-device: there is no LiteRT native runtime and no Play services here, so
 * every target must degrade to the heuristics without crashing or hanging.
 */
@RunWith(RobolectricTestRunner::class)
class SegmentationServiceRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun everyTargetDegradesGracefullyOnABackgroundThread() {
        val service = SegmentationService.get(context)
        assertSame(service, SegmentationService.get(context))
        val w = 320; val h = 240
        val img = SegTestImages.skyOverFoliage(w, h)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // Two threads at once, repeatedly.
            val jobs = (0 until 4).map { k ->
                pool.submit<Map<SmartTarget, FloatArray?>> {
                    SmartTarget.entries.associateWith { service.segment(img, it) }.also { if (k == 0) service.prepare() }
                }
            }
            val results = jobs.map { it.get(120, TimeUnit.SECONDS) }
            for (r in results) for ((t, m) in r) {
                assertNotNull("$t", m)
                assertEquals(w * h, m!!.size)
                assertTrue(m.all { it in 0f..1f })
            }
            val sky = results[0][SmartTarget.SKY]!!
            assertTrue(mean(sky, w, 0, 0, w, h / 2 - 8) > 0.9f)
            assertTrue(mean(sky, w, 0, h / 2 + 8, w, h) < 0.05f)
            val subject = pool.submit<FloatArray?> { service.asFilterServices().subjectMask(img) }.get(60, TimeUnit.SECONDS)
            assertNotNull(subject)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun mainThreadCallsStillReturnAResult() {
        val service = SegmentationService(context)
        val img = SegTestImages.disc(200, 150, SegTestImages.GRAY_BG, SegTestImages.RED, 0.2f)
        val fg = service.segment(img, SmartTarget.SUBJECT)!!
        assertTrue(fg[75 * 200 + 100] > 0.9f)
        service.releaseMemory()
        assertNotNull(service.segment(img, SmartTarget.BACKGROUND))
    }
}
