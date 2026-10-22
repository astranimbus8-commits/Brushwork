package com.brushwork.paint.engine.budget

import java.util.concurrent.atomic.AtomicLong

/** v1.10 (F1, design 2.4): what the native pixel bytes the [PixelLedger] counts are for. */
enum class LedgerCategory { RESIDENT_LAYERS, TILES, MASKS, ARRAYS, DISPLAY, PROJECTIONS, UNDO, TRANSIENT, THUMBNAILS }

/** v1.10 (F1, design 2.4): why a native allocation is asked for ([NativeAdmission.admitNative]). */
enum class NativePurpose { LAYER, MASK, TILE, TRANSIENT, PROJECTION, DISPLAY, UNDO, ARRAY, THUMBNAIL }

/**
 * v1.10 (F1, design 2.4): the native pixel bytes the engine holds, per [LedgerCategory] and in
 * total, against [limit] (R). [add] and [sub] count what main allocates and frees; [tryReserve]
 * and [release] are the admission any thread may ask for. Every counter is an [AtomicLong], so
 * the totals are safe to read off main.
 */
class PixelLedger {
    private val total = AtomicLong()
    private val counts = Array(LedgerCategory.entries.size) { AtomicLong() }

    /** Main only: [bytes] more under [c]. */
    fun add(c: LedgerCategory, bytes: Long) {
        counts[c.ordinal].addAndGet(bytes)
        total.addAndGet(bytes)
    }

    /** Main only: [bytes] fewer under [c]. */
    fun sub(c: LedgerCategory, bytes: Long) = add(c, -bytes)

    /** R; set by MemoryBudget (F1: unlimited). */
    @Volatile
    var limit: Long = Long.MAX_VALUE

    /** Any thread: a CAS loop on the AtomicLong total against [limit]; true = the bytes are counted under [c]. */
    fun tryReserve(c: LedgerCategory, bytes: Long): Boolean {
        require(bytes >= 0L) { "a reservation is never negative (got $bytes)" }
        while (true) {
            val t = total.get()
            if (t > limit - bytes) return false
            if (total.compareAndSet(t, t + bytes)) {
                counts[c.ordinal].addAndGet(bytes)
                return true
            }
        }
    }

    /** Any thread; undoes a [tryReserve]. */
    fun release(c: LedgerCategory, bytes: Long) {
        counts[c.ordinal].addAndGet(-bytes)
        total.addAndGet(-bytes)
    }

    /** Every counted byte. */
    val native: Long get() = total.get()

    /** The bytes counted under [c]. */
    fun of(c: LedgerCategory): Long = counts[c.ordinal].get()
}

/** v1.10 (F1, design 2.4): asks for room before a native allocation; `MemoryBudget` is the app's. */
interface NativeAdmission {
    /** On main it may first park and drop projections, then tryReserve; off main it is tryReserve only (never parks). */
    fun admitNative(bytes: Long, purpose: NativePurpose): Boolean

    /** Undoes an [admitNative] that said yes. */
    fun release(bytes: Long, purpose: NativePurpose)
}
