package com.brushwork.paint.tools.select.colorrange

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pixels Color select previews on (v1.8, design §3.5 c): a proxy of the Refer pixels, long
 * side at most [ColorMasks.PROXY_MAX], with its Lab cached in hundredths (built once per tool
 * session; every change after that recomputes only the membership). The pixels stay for their
 * alpha and HSV. Immutable once built; any thread.
 */
class ColorProxy internal constructor(
    val width: Int,
    val height: Int,
    internal val pixels: IntArray,
    internal val l: ShortArray,
    internal val a: ShortArray,
    internal val b: ShortArray,
) {
    /** Bytes held. */
    val bytes: Long get() = pixels.size * 4L + (l.size + a.size + b.size) * 2L
}

/**
 * What the tool shows (`ColorSelectTool.preview`): the proxy's membership [mask] (0..255 per
 * pixel, row by row, [width] × [height]) and its [version], which grows with every preview
 * published. [bitmap] is [mask] as an ALPHA_8 bitmap the overlay tints red (main thread only).
 */
class ColorPreview internal constructor(
    val version: Int,
    val mask: ByteArray,
    val width: Int,
    val height: Int,
    internal val bitmap: Bitmap,
)

/**
 * Color select's masks (v1.8, design §3.5 c), computed in row bands on up to [WORKERS] threads (v1.9; v1.8: every core):
 * [proxy] (the Lab cache), [previewMask] (the proxy's membership) and [fullMask] (the document's,
 * at full resolution, in [BAND_ROWS]-row bands). The preview and the commit share
 * [ColorRange.labQ] and [ColorRange.member], so the preview shows what the commit selects.
 */
object ColorMasks {
    /** The preview proxy's longest side (px). */
    const val PROXY_MAX = 1280

    /** Rows per band of the full-resolution mask. */
    const val BAND_ROWS = 256

    /** Rows per band of the proxy's work. */
    private const val PROXY_BAND_ROWS = 64

    /** Size of the cache of colors met in one band of the full-resolution pass (a power of two). */
    private const val CACHE = 4096

    /** The proxy's size for a [docW] × [docH] document (as `Compositor.renderThumbnail(PROXY_MAX)`). */
    fun proxySize(docW: Int, docH: Int): IntArray {
        val s = min(1f, PROXY_MAX.toFloat() / max(docW, docH))
        return intArrayOf(max(1, (docW * s).roundToInt()), max(1, (docH * s).roundToInt()))
    }

    /**
     * v1.9 (A7a step 6): at most this many threads work on one mask: the caller and up to three
     * helpers of [pool].
     */
    const val WORKERS = 4

    /**
     * v1.9 (A7a): the helpers' threads, at most [WORKERS] of `Dispatchers.Default` (no longer the
     * common fork-join pool on every core, which no cancellation reached and the rest of the app
     * shared).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pool: Executor = Dispatchers.Default.limitedParallelism(WORKERS).asExecutor()

    /**
     * Runs [band] for bands 0 until [bands], each once, on the calling thread and up to
     * [WORKERS] - 1 helpers of [pool] (fewer on fewer cores); each thread gets its own scratch from
     * [scratch]. A band starts only while [cancelled] is false: false when it stopped the work.
     * v1.9 (A7a): nothing runs on after the call returns (a helper that starts late takes no
     * band; the call waits for the bands running), and an error in any band stops the others and
     * is thrown here.
     */
    private fun <T> parallel(bands: Int, scratch: () -> T, cancelled: () -> Boolean, band: (Int, T) -> Unit): Boolean {
        if (bands <= 0) return !cancelled()
        val work = Bands(bands, scratch, cancelled, band)
        val helpers = min(bands, min(WORKERS, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))) - 1
        for (k in 0 until helpers) {
            try {
                pool.execute(work)
            } catch (e: Throwable) {
                break // fewer helpers: the caller takes their bands
            }
        }
        work.work()
        work.close()
        work.error?.let { throw it }
        return !cancelled()
    }

    /** The bands of one [parallel] call, shared by its threads ([run]: a helper). */
    private class Bands<T>(
        private val count: Int,
        private val scratch: () -> T,
        private val cancelled: () -> Boolean,
        private val band: (Int, T) -> Unit,
    ) : Runnable {
        private val next = AtomicInteger()
        private val lock = ReentrantLock()
        private val ended = lock.newCondition()
        private var running = 0
        private var closed = false

        /** The first error of a band (the others stop). */
        @Volatile
        var error: Throwable? = null
            private set

        /** A helper: works only while the caller has not closed the bands. */
        override fun run() {
            lock.withLock {
                if (closed) return
                running++
            }
            try {
                work()
            } finally {
                lock.withLock {
                    running--
                    if (running == 0) ended.signalAll()
                }
            }
        }

        /** Takes bands until none is left, the work is cancelled or a band failed. */
        fun work() {
            var s: Any? = NONE
            try {
                while (error == null && !cancelled()) {
                    val i = next.getAndIncrement()
                    if (i >= count) return
                    if (s === NONE) s = scratch()
                    @Suppress("UNCHECKED_CAST")
                    band(i, s as T)
                }
            } catch (e: Throwable) {
                lock.withLock { if (error == null) error = e }
            }
        }

        /** The caller's end: no helper starts after this; returns once the running ones have ended. */
        fun close() {
            lock.withLock {
                closed = true
                while (running > 0) ended.awaitUninterruptibly()
            }
        }

        private companion object {
            val NONE = Any()
        }
    }

    /** The Lab cache of [pixels] (ARGB, unpremultiplied, [w] × [h]); null when cancelled. */
    fun proxy(pixels: IntArray, w: Int, h: Int, cancelled: () -> Boolean = { false }): ColorProxy? {
        val n = w * h
        val l = ShortArray(n); val a = ShortArray(n); val b = ShortArray(n)
        val bands = (h + PROXY_BAND_ROWS - 1) / PROXY_BAND_ROWS
        val done = parallel(bands, { Unit }, cancelled) { band, _ ->
            val from = band * PROXY_BAND_ROWS * w
            val to = min(n, from + PROXY_BAND_ROWS * w)
            var last = 0; var lastQ = ColorRange.labQ(0)
            for (i in from until to) {
                val c = pixels[i] or ALPHA
                val q = if (c == last) lastQ else ColorRange.labQ(c).also { last = c; lastQ = it }
                l[i] = ColorRange.qL(q).toShort(); a[i] = ColorRange.qA(q).toShort(); b[i] = ColorRange.qB(q).toShort()
            }
        }
        return if (done) ColorProxy(w, h, pixels, l, a, b) else null
    }

    /** The Lab cache of [bitmap] (any size; read here, not recycled); null when cancelled. */
    fun proxy(bitmap: Bitmap, cancelled: () -> Boolean = { false }): ColorProxy? {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (cancelled()) return null
        return proxy(pixels, w, h, cancelled)
    }

    /** The membership (0..255) of every pixel of [proxy] under [p]; null when cancelled. */
    fun previewMask(proxy: ColorProxy, p: ColorRange.Params, cancelled: () -> Boolean = { false }): ByteArray? {
        val n = proxy.width * proxy.height
        val out = ByteArray(n)
        if (p.selectsNothing) return out
        val px = proxy.pixels; val l = proxy.l; val a = proxy.a; val b = proxy.b
        val rows = PROXY_BAND_ROWS * proxy.width
        val bands = (n + rows - 1) / rows
        val done = parallel(bands, { Unit }, cancelled) { band, _ ->
            val from = band * rows
            val to = min(n, from + rows)
            for (i in from until to) out[i] = ColorRange.member(px[i], l[i].toInt(), a[i].toInt(), b[i].toInt(), p).toByte()
        }
        return if (done) out else null
    }

    private class Scratch(w: Int) {
        val row = IntArray(w * BAND_ROWS)
        val keys = IntArray(CACHE)
        val values = ShortArray(CACHE)
    }

    /**
     * The document's mask (0..255 per pixel, row by row) under [p], read from [source] (the Refer
     * pixels at full resolution, not recycled here) in [BAND_ROWS]-row bands; null when cancelled.
     */
    fun fullMask(source: Bitmap, p: ColorRange.Params, cancelled: () -> Boolean = { false }): ByteArray? {
        val w = source.width; val h = source.height
        val out = ByteArray(w * h)
        if (p.selectsNothing) return out
        val bands = (h + BAND_ROWS - 1) / BAND_ROWS
        val done = parallel(bands, { Scratch(w) }, cancelled) { band, s ->
            val y = band * BAND_ROWS
            val rows = min(BAND_ROWS, h - y)
            source.getPixels(s.row, 0, w, 0, y, w, rows)
            // A colour met before in this band is looked up, not computed again.
            s.values.fill((-1).toShort())
            val base = y * w
            var last = 0; var lastV = member(0, p)
            for (i in 0 until w * rows) {
                val c = s.row[i]
                val v: Int
                if (c == last) {
                    v = lastV
                } else {
                    val k = (c * HASH) ushr (32 - CACHE_BITS)
                    if (s.values[k] >= 0 && s.keys[k] == c) {
                        v = s.values[k].toInt()
                    } else {
                        v = member(c, p)
                        s.keys[k] = c
                        s.values[k] = v.toShort()
                    }
                    last = c; lastV = v
                }
                out[base + i] = v.toByte()
            }
        }
        return if (done) out else null
    }

    /** The selection value of one pixel (ARGB, unpremultiplied). */
    fun member(argb: Int, p: ColorRange.Params): Int {
        val q = ColorRange.labQ(argb)
        return ColorRange.member(argb, ColorRange.qL(q), ColorRange.qA(q), ColorRange.qB(q), p)
    }

    private const val ALPHA = 0xFF shl 24
    private const val CACHE_BITS = 12
    private const val HASH = -0x61c88647 // 0x9E3779B9, Fibonacci hashing
}
