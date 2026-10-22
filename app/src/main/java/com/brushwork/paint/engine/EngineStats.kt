package com.brushwork.paint.engine

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
 * v1.10 (F1, design 3.3; A10's file): in-memory rings of the last 50 values per [EngineStat]
 * (A10). F1 declares it with no-op bodies so that A8, A9 and A11 can call [record] before A10
 * merges: nothing is kept and [summary] is empty.
 */
object EngineStats {
    /** Records [value] for [stat] (F1: nothing). */
    @Suppress("UNUSED_PARAMETER")
    fun record(stat: EngineStat, value: Float) {}

    /** The stats with at least one value, in [EngineStat] order (F1: none). */
    fun summary(): List<StatSummary> = emptyList()
}
