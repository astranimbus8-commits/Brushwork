package com.brushwork.paint.engine.check

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult

/** v1.10 (A10, design 2.10 M2): the names of `EngineCheckResult.m2Outcome`. */
object M2Outcome {
    /** The probe reached its 256 MiB cap. */
    const val REACHED = "REACHED"

    /** The `availMem` margin (or `lowMemory`) stopped it first: the phone was busy at that moment. */
    const val MARGIN_LIMITED = "MARGIN_LIMITED"

    /** An allocation threw. */
    const val ALLOC_FAILED = "ALLOC_FAILED"
}

/**
 * v1.10 (A10, design 2.10): what one run of the automatic check measured, the decision's inputs.
 * M1: [totalMem] to [javaMax]; [stepDown] (section 2.12, 0 after a manual check); M2:
 * [nativeProbeBytes] and [m2Outcome] ([M2Outcome]); M3: the Deflate speeds and ratio; M4: the
 * write and read speeds and [freeBytes]; M5: [composite64Ms] and the Classic A/B's
 * [belowCacheMinLayers]. A step the check skipped (M2-M5 after a rule 0 or rule 1 verdict at M1)
 * leaves its zeros.
 */
data class CheckInputs(
    val totalMem: Long,
    val availMem: Long,
    val threshold: Long,
    val lowRam: Boolean,
    val is64Bit: Boolean,
    val javaMax: Long,
    val stepDown: Int = 0,
    val nativeProbeBytes: Long = 0L,
    val m2Outcome: String = "",
    val deflateMBs: Float = 0f,
    val inflateMBs: Float = 0f,
    val ratio: Float = 0f,
    val writeMBs: Float = 0f,
    val readMBs: Float = 0f,
    val freeBytes: Long = 0L,
    val composite64Ms: Float = 0f,
    val belowCacheMinLayers: Int? = null,
)

/**
 * v1.10 (A10, design 2.10): what [AutoCheck.decide] chose: the [level] and its [reason]
 * (`CheckReasons`, "" for FULL), whether the result is [provisional] (an inconclusive M2: it
 * re-runs at the next cold start), the inconclusive runs in a row, and the above projection
 * switch.
 */
data class Decision(
    val level: EngineLevel,
    val reason: String,
    val provisional: Boolean,
    val inconclusiveRuns: Int,
    val aboveProjection: Boolean,
)

/**
 * v1.10 (A10, design 2.10): the automatic check's decision, a pure function of stable inputs:
 * `totalMem`, `isLowRamDevice`, `is64Bit`, J, `stepDown`, the M2 outcome and the M3-M5 speeds.
 * `availMem` never decides a level (it feeds only the per-open R, section 2.4); M2's bytes count
 * only through its outcome below 128 MiB.
 *
 * 0. `stepDown >= 2` gives CLASSIC, `LOW_MEMORY`.
 * 1. `isLowRamDevice`, `totalMem < 3 GiB`, [rStable] `< 512 MiB` or an M2 `ALLOC_FAILED` below
 *    128 MiB give CLASSIC, `LOW_MEMORY` (final).
 * 2. M5 `> 60 ms` gives CLASSIC, `SLOW_COMPOSITING`, unless the previous result was FULL or TILES:
 *    that level is kept, with the above projection off (never a downgrade on M5 alone).
 * 3. Deflate `< 20 MB/s`, write `< 20 MB/s` or free `< 2 GiB` give TILES, `SLOW_STORAGE` (also
 *    under a level rule 2 kept: storage is not M5).
 * 4. Otherwise FULL.
 *
 * An inconclusive M2 (`MARGIN_LIMITED` below 128 MiB) makes the result provisional, its level
 * capped at the previous result's (CLASSIC when there is none); the third inconclusive run in a
 * row is final CLASSIC, `LOW_MEMORY`. A conclusive run decides afresh, so a provisional CLASSIC
 * becomes FULL as soon as the phone has room (case (a) of `AutoCheckDecisionTest`).
 */
object AutoCheck {
    private const val MIB = 1L shl 20
    private const val GIB = 1L shl 30

    /** Rule 1: phones with less memory run CLASSIC. */
    const val MIN_TOTAL_MEM = 3L * GIB

    /** Rule 1: an R this small (with `base = 0.30 totalMem`) is CLASSIC. */
    const val MIN_R_STABLE = 512L * MIB

    /** M2 below this is conclusive only as REACHED: ALLOC_FAILED is rule 1, MARGIN_LIMITED is inconclusive. */
    const val M2_CONCLUSIVE = 128L * MIB

    /** Rule 2: a composite of 64 layers slower than this is slow compositing. */
    const val SLOW_COMPOSITE_MS = 60f

    /** The above projection runs when the composite takes at most this. */
    const val PROJECTION_MAX_MS = 30f

    /** Rule 3: Deflate and write speeds below this are slow storage. */
    const val MIN_STORAGE_MBS = 20f

    /** Rule 3 (and `EngineConfig.resolve` at open): FULL needs this much free storage. */
    const val MIN_FREE = 2L * GIB

    /** Inconclusive runs in a row before the result is final CLASSIC. */
    const val MAX_INCONCLUSIVE = 3

    /** Section 2.4: R's ceiling on 64-bit and on 32-bit. */
    private const val R_CEILING_64 = 2L * GIB
    private const val R_CEILING_32 = 700L * MIB

    /**
     * Section 2.4's R with `base = 0.30 totalMem` and without the step-down: `clamp(base * f,
     * 0.55 J, ceiling)` with `f = 0.6` on a low-RAM phone, times 0.6 on 32-bit.
     */
    fun rStable(totalMem: Long, lowRam: Boolean, is64Bit: Boolean, javaMax: Long): Long {
        var f = 1.0
        if (lowRam) f *= 0.6
        if (!is64Bit) f *= 0.6
        val r = (totalMem * 0.30 * f).toLong()
        val lo = (javaMax * 0.55).toLong()
        val hi = if (is64Bit) R_CEILING_64 else R_CEILING_32
        return r.coerceAtMost(hi).coerceAtLeast(minOf(lo, hi))
    }

    /** M1's verdict alone (rules 0 and 1 without M2): the check skips M2-M5 when it is not null. */
    fun earlyVerdict(totalMem: Long, lowRam: Boolean, is64Bit: Boolean, javaMax: Long, stepDown: Int): Decision? {
        val low = stepDown >= 2 || lowRam || totalMem < MIN_TOTAL_MEM || rStable(totalMem, lowRam, is64Bit, javaMax) < MIN_R_STABLE
        return if (low) Decision(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, provisional = false, inconclusiveRuns = 0, aboveProjection = false) else null
    }

    /** The section 2.10 decision for [inputs]; [previous] is this phone's stored result, or null. */
    fun decide(inputs: CheckInputs, previous: EngineCheckResult?): Decision {
        earlyVerdict(inputs.totalMem, inputs.lowRam, inputs.is64Bit, inputs.javaMax, inputs.stepDown)?.let { return it }
        if (inputs.m2Outcome == M2Outcome.ALLOC_FAILED && inputs.nativeProbeBytes < M2_CONCLUSIVE) return lowMemory()
        val inconclusive = inputs.m2Outcome == M2Outcome.MARGIN_LIMITED && inputs.nativeProbeBytes < M2_CONCLUSIVE
        val runs = if (inconclusive) (if (previous?.provisional == true) previous.inconclusiveRuns else 0) + 1 else 0
        if (runs >= MAX_INCONCLUSIVE) return lowMemory().copy(inconclusiveRuns = runs)

        // Rules 2-4.
        val slow = inputs.composite64Ms > SLOW_COMPOSITE_MS
        val kept = previous?.takeIf { slow && it.level != EngineLevel.CLASSIC }
        if (slow && kept == null) {
            return Decision(EngineLevel.CLASSIC, CheckReasons.SLOW_COMPOSITING, inconclusive, runs, aboveProjection = false)
        }
        val slowStorage = inputs.deflateMBs < MIN_STORAGE_MBS || inputs.writeMBs < MIN_STORAGE_MBS || inputs.freeBytes < MIN_FREE
        var level = if (slowStorage) EngineLevel.TILES else EngineLevel.FULL
        if (kept != null && kept.level < level) level = kept.level
        var reason = reasonOf(level, if (kept != null && kept.level == level) kept.reason else CheckReasons.SLOW_STORAGE)

        // An inconclusive M2 caps the level at the previous result's (CLASSIC without one).
        if (inconclusive) {
            val cap = previous?.level ?: EngineLevel.CLASSIC
            if (cap < level) {
                level = cap
                reason = reasonOf(level, previous?.reason?.takeIf { it.isNotEmpty() } ?: CheckReasons.LOW_MEMORY)
            }
        }
        val above = !slow && inputs.composite64Ms <= PROJECTION_MAX_MS && level != EngineLevel.CLASSIC
        return Decision(level, reason, inconclusive, runs, above)
    }

    private fun lowMemory() = Decision(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, provisional = false, inconclusiveRuns = 0, aboveProjection = false)

    /** "" for FULL; [belowFull] (never empty) for TILES and CLASSIC. */
    private fun reasonOf(level: EngineLevel, belowFull: String): String =
        if (level == EngineLevel.FULL) "" else belowFull.ifEmpty { CheckReasons.SLOW_STORAGE }
}
