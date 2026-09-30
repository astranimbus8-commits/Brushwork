package com.brushwork.paint.segmentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.segmentation.SegTestImages.mean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The real service off-device: there is no LiteRT native runtime and no Play services here, so
 * every target must degrade to the heuristics without crashing or hanging.
 */
@RunWith(RobolectricTestRunner::class)
class SegmentationServiceRobolectricTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun showLogs() {
        ShadowLog.stream = System.out
    }

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
    fun interruptedCallerGetsNullAndTheServiceRecovers() {
        val service = SegmentationService(context)
        val img = SegTestImages.skyOverFoliage(160, 120)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val cancelled = pool.submit<Boolean> {
                Thread.currentThread().interrupt()
                try {
                    // Null result, and the interrupt stays visible to the caller.
                    service.segment(img, SmartTarget.SKY) == null && Thread.currentThread().isInterrupted
                } finally {
                    Thread.interrupted()
                }
            }.get(60, TimeUnit.SECONDS)
            assertTrue(cancelled)
            assertNotNull(pool.submit<FloatArray?> { service.segment(img, SmartTarget.SKY) }.get(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun objectSelectionFallsBackToColorsWithoutTheNativeModel() {
        val service = SegmentationService(context)
        service.prepareObjectSelect()
        val w = 300; val h = 200
        val img = SegTestImages.disc(w, h, SegTestImages.GRAY_BG, SegTestImages.RED, 0.3f)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val r = pool.submit<SegmentationService.ObjectSelection?> { service.selectObject(img, ObjectPrompt.tap(150f, 100f)) }.get(60, TimeUnit.SECONDS)
            assertNotNull(r)
            assertTrue(!r!!.usedModel)
            assertEquals(w * h, r.mask.size)
            assertTrue(r.mask[100 * w + 150] > 0.95f)
            assertTrue(r.mask[5 * w + 5] < 0.05f)
            // Cancelled: null, and the service keeps working.
            assertNull(pool.submit<SegmentationService.ObjectSelection?> { service.selectObject(img, ObjectPrompt.tap(150f, 100f)) { true } }.get(60, TimeUnit.SECONDS))
            assertNotNull(pool.submit<SegmentationService.ObjectSelection?> { service.selectObject(img, ObjectPrompt.tap(150f, 100f)) }.get(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        service.releaseMemory()
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
