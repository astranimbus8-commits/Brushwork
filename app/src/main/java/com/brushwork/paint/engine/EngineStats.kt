package com.brushwork.paint.engine

import android.os.Build
import android.os.SystemClock

/**
 * v1.10 (F1, design 3.3 and 7; signatures frozen, A10 bodies): the engine's measurements, each a
 * value in its unit (ms, or bytes for [LEDGER_PEAK]): the layer switch, the open, the save of one
 * changed layer, the composite of a stroke frame, input to composite, the UI frame tagged stroke,
 * panel scroll or other, and the peak ledger total.
 */
enum class EngineStat { SWITCH, OPEN, SAVE_LAYER, COMPOSITE, INPUT_TO_COMPOSITE, FRAME_STROKE, FRAME_PANEL, FRAME_OTHER, LEDGER_PEAK }

/** v1.10 (F1): one stat's [n] recent values and their median and 95th percentile. */
data class StatSummary(val stat: EngineStat, val n: Int, val p50: Float, val p95: Float)

/**
 * v1.10 (F1, design 3.3 and 7; A10's file): in-memory rings of the last [RING] (50) values per
 * [EngineStat], read by `EngineLabels110.COPY_DETAILS` in the layer storage settings (the p50 and p95 of each
 * ring) for the device checks. Nothing is written to disk; a new process starts empty.
 *
 * The callers: the layer switch (A8), the open and the save of a changed layer (A11), the
 * composite and input to composite (A9's `CanvasView`), the UI frames ([frame], from the
 * frame-metrics listener of `EngineEditorChrome`) and the ledger's total (`NativeWatch`, every
 * 2 s while an editor is open; its ring's p95 is the session's peak to within one tick).
 *
 * A UI frame is tagged stroke when input reached a composite ([EngineStat.INPUT_TO_COMPOSITE])
 * within the last [STROKE_WINDOW_MS], panel scroll when [panelScrolled] was called within that
 * window, other otherwise.
 *
 * Under Robolectric (one JVM, thousands of tests) [record] keeps nothing unless a test sets
 * [keepUnderRobolectric], so the stats of one test never leak into another's. Any thread.
 */
object EngineStats {
    /** Values kept per stat. */
    const val RING = 50

    /** A UI frame this soon after a stroke composite or a panel scroll carries its tag. */
    const val STROKE_WINDOW_MS = 100L

    /** Tests: under Robolectric [record] keeps values only while this is set (reset it in `finally`). */
    @Volatile
    internal var keepUnderRobolectric = false

    private val robolectric = Build.FINGERPRINT == "robolectric"
    private val lock = Any()
    private val values = Array(EngineStat.entries.size) { FloatArray(RING) }
    private val counts = IntArray(EngineStat.entries.size)

    @Volatile private var strokeAt = Long.MIN_VALUE / 2
    @Volatile private var panelAt = Long.MIN_VALUE / 2

    /** Records [value] for [stat] (a negative or not-a-number value is ignored). */
    fun record(stat: EngineStat, value: Float) {
        if (robolectric && !keepUnderRobolectric) return
        if (value.isNaN() || value < 0f) return
        if (stat == EngineStat.INPUT_TO_COMPOSITE) strokeAt = SystemClock.uptimeMillis()
        synchronized(lock) {
            val i = stat.ordinal
            values[i][counts[i] % RING] = value
            counts[i]++
        }
    }

    /** The stats with at least one value, in [EngineStat] order: the nearest-rank p50 and p95 of each ring. */
    fun summary(): List<StatSummary> = synchronized(lock) {
        EngineStat.entries.mapNotNull { stat ->
            val n = minOf(counts[stat.ordinal], RING)
            if (n == 0) return@mapNotNull null
            val sorted = values[stat.ordinal].copyOf(n).also { it.sort() }
            StatSummary(stat, n, rank(sorted, 0.50), rank(sorted, 0.95))
        }
    }

    /** One UI frame of [ms] (the frame-metrics listener), tagged as the class KDoc says. */
    internal fun frame(ms: Float, nowUptime: Long = SystemClock.uptimeMillis()) {
        val stat = when {
            nowUptime - strokeAt <= STROKE_WINDOW_MS -> EngineStat.FRAME_STROKE
            nowUptime - panelAt <= STROKE_WINDOW_MS -> EngineStat.FRAME_PANEL
            else -> EngineStat.FRAME_OTHER
        }
        record(stat, ms)
    }

    /** A panel (the layers list, a sheet) scrolled now: the next UI frames are tagged panel scroll. */
    fun panelScrolled() {
        panelAt = SystemClock.uptimeMillis()
    }

    /** Tests: empties every ring and forgets the last stroke and panel scroll. */
    internal fun reset() = synchronized(lock) {
        counts.fill(0)
        strokeAt = Long.MIN_VALUE / 2
        panelAt = Long.MIN_VALUE / 2
    }

    /** The nearest-rank [q] quantile of the ascending [sorted] values. */
    private fun rank(sorted: FloatArray, q: Double): Float {
        val k = Math.ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[k - 1]
    }
}
