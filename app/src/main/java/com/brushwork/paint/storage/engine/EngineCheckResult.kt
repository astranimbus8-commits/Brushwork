package com.brushwork.paint.storage.engine

import com.brushwork.paint.engine.EngineLevel
import kotlinx.serialization.Serializable

/**
 * v1.10 (F1, design 2.10; rule K: lead): the automatic check's result, kept as
 * `noBackupFilesDir/engine-check.json` (never backed up, so never carried to another phone). Read
 * with `coerceInputValues` and the defaults; an unreadable or newer-[schema] file counts as no
 * result. [stepDown] and [lmkDeathsAt] are carried into every new result; [m2Outcome] is
 * `REACHED`, `MARGIN_LIMITED` or `ALLOC_FAILED`; [reason] is one of [CheckReasons].
 */
@Serializable
data class EngineCheckResult(
    val schema: Int = 1,
    val appVersionCode: Int,
    val deviceKey: String,
    val ranAtMillis: Long,
    val durationMs: Long,
    val level: EngineLevel,
    val reason: String,
    val totalMem: Long,
    val availMem: Long,
    val threshold: Long,
    val lowRam: Boolean,
    val is64Bit: Boolean,
    val javaMax: Long,
    val nativeProbeBytes: Long,
    val deflateMBs: Float,
    val inflateMBs: Float,
    val ratio: Float,
    val writeMBs: Float,
    val readMBs: Float,
    val freeBytes: Long,
    val composite64Ms: Float,
    val belowCacheMinLayers: Int? = null,
    val aboveProjection: Boolean = false,
    val stepDown: Int = 0,
    val lmkDeathsAt: List<Long> = emptyList(),
    val provisional: Boolean = false,
    val m2Outcome: String = "",
    val inconclusiveRuns: Int = 0,
)

/** v1.10 (F1, design 2.10): why the check chose a level below FULL ([EngineCheckResult.reason]). */
object CheckReasons {
    const val LOW_MEMORY = "low memory"
    const val SLOW_COMPOSITING = "slow compositing"
    const val SLOW_STORAGE = "slow or full storage"
}
