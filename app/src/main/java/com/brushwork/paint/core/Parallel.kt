package com.brushwork.paint.core

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Tiny data-parallel helper for pixel loops. Splits [0, count) into contiguous chunks and runs
 * them on a shared pool of daemon threads, blocking until all finish. Exceptions (including
 * CancellationException thrown by a cancelled filter) propagate to the caller.
 */
object Parallel {
    val threadCount: Int = max(1, Runtime.getRuntime().availableProcessors())

    private val counter = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(threadCount, ThreadFactory { r ->
        Thread(r, "bw-parallel-${counter.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    })

    /** Runs [body](start, endExclusive) over [0, count) in parallel chunks. */
    fun forRange(count: Int, minChunk: Int = 8, body: (start: Int, end: Int) -> Unit) {
        if (count <= 0) return
        val chunks = min(threadCount * 3, max(1, count / max(1, minChunk)))
        if (chunks <= 1 || Thread.currentThread().name.startsWith("bw-parallel-")) {
            body(0, count)
            return
        }
        val step = (count + chunks - 1) / chunks
        val futures = ArrayList<Future<*>>(chunks)
        var s = 0
        while (s < count) {
            val start = s
            val end = min(count, s + step)
            futures += pool.submit { body(start, end) }
            s = end
        }
        var first: Throwable? = null
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                if (first == null) first = e.cause ?: e
            } catch (e: InterruptedException) {
                if (first == null) first = CancellationException("interrupted")
            }
        }
        first?.let { throw it }
    }

    /** Convenience: parallel over rows of an image; [body] receives (y0, y1Exclusive). */
    fun forRows(height: Int, body: (y0: Int, y1: Int) -> Unit) = forRange(height, 4, body)
}
