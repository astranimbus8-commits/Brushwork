package com.brushwork.paint.segmentation

import com.brushwork.paint.segmentation.SegTestImages.mean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SegmentationPerfTest {

    /** Fake scene model: bright blue content is sky, everything else is tree. */
    private val parser = SceneParser { content, lb ->
        val s = lb.size
        ByteArray(s * s) { i ->
            val x = i % s - lb.offsetX; val y = i / s - lb.offsetY
            when {
                x < 0 || y < 0 || x >= lb.contentWidth || y >= lb.contentHeight -> 0
                (content[x, y] and 0xFF) > 150 -> SceneClasses.SKY.toByte()
                else -> SceneClasses.TREE.toByte()
            }
        }
    }

    @Test
    fun twelveMegapixelImageStaysWithinBudget() {
        // Everything but the final guided upsample runs at <= 1280 px, so a 12 MP photo must be
        // cheap. The bound is generous (JVM, shared CI machine); it catches O(n * r) regressions.
        val w = 4000; val h = 3000
        val img = SegTestImages.skyOverFoliage(w, h)
        val subject = SubjectBackend { im -> FloatArray(im.size) { if (it % im.width in im.width / 3 until 2 * im.width / 3) 1f else 0f } }
        val pipeline = SegmentationPipeline(parser, subject)
        val start = System.nanoTime()
        for (t in SmartTarget.entries) {
            val t0 = System.nanoTime()
            val m = pipeline.segment(img, t)!!
            println("$t ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertEquals(w * h, m.size)
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000
        println("all targets on ${w}x$h: $totalMs ms")
        assertTrue("took $totalMs ms", totalMs < 20_000)
        val sky = pipeline.segment(img, SmartTarget.SKY)!!
        assertTrue(mean(sky, w, 0, 0, w, h / 2 - 20) > 0.99f)
        assertTrue(mean(sky, w, 0, h / 2 + 20, w, h) < 0.01f)
    }

    @Test
    fun interruptedCallerStopsEarly() {
        val w = 640; val h = 480
        val img = SegTestImages.skyOverFoliage(w, h)
        val pipeline = SegmentationPipeline(parser, null)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val outcome = pool.submit<String> {
                Thread.currentThread().interrupt()
                try {
                    pipeline.segment(img, SmartTarget.SKY)
                    "finished"
                } catch (e: CancellationException) {
                    "cancelled"
                } finally {
                    Thread.interrupted()
                }
            }.get(30, TimeUnit.SECONDS)
            assertEquals("cancelled", outcome)
            // The same pipeline still works afterwards.
            val sky = pool.submit<FloatArray?> { pipeline.segment(img, SmartTarget.SKY) }.get(30, TimeUnit.SECONDS)!!
            assertTrue(mean(sky, w, 0, 0, w, h / 2 - 8) > 0.95f)
        } finally {
            pool.shutdownNow()
        }
    }
}
