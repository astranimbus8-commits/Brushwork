package com.brushwork.paint.v19.a7b

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * A render worker as slow as the test wants (A7b step 5): every job waits on its own thread until
 * [open] (or 30 s, so a broken test never hangs), however long a flush waits for it. A job
 * cancelled meanwhile still ends only once it is let through, as a busy worker on the phone ends
 * at its next check.
 */
internal class GatedWorker : CoroutineDispatcher() {
    private val ex = Executors.newSingleThreadExecutor { Thread(it, "a7b-gated-worker").apply { isDaemon = true } }
    private val gate = CountDownLatch(1)
    private val through = AtomicInteger()

    /** The jobs let through so far: none while a wait that returned before [open] gave up on its worker. */
    val letThrough: Int get() = through.get()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        ex.execute {
            gate.await(30, TimeUnit.SECONDS)
            through.incrementAndGet()
            block.run()
        }
    }

    fun open() = gate.countDown()

    fun close() {
        open()
        ex.shutdown()
    }
}
