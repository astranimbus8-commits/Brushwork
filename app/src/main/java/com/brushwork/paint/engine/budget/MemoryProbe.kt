package com.brushwork.paint.engine.budget

import java.io.File

/**
 * v1.10 (F1, design 2.4 and 2.10 M1): what the phone says about its memory at one moment. A
 * [totalMem] of 0 means unknown (J3: the budgets stay v1.9's, `Budgets.v19`).
 */
data class MemSnapshot(
    val totalMem: Long,
    val availMem: Long,
    val threshold: Long,
    val lowMemory: Boolean,
    val isLowRamDevice: Boolean,
    val is64Bit: Boolean,
    val javaMax: Long,
    val javaUsed: Long,
    val nativeAllocated: Long,
)

/** v1.10 (F1, design 2.4 and 2.10): the memory side of the phone, injectable (`EngineConfig.memoryProbe`; tests: FakeMemoryProbe). */
interface MemoryProbe {
    fun snapshot(): MemSnapshot

    /** M2: allocates and touches stepBytes bitmaps until capBytes, or availMem < stopBelowAvail, or a throw; frees all. Returns bytes reached. */
    fun probeNative(stepBytes: Long, stopBelowAvail: Long, capBytes: Long): Long
}

/** v1.10 (F1, design 2.8 and 2.10 M4): the storage side of the phone, injectable (`EngineConfig.storageProbe`). */
interface StorageProbe {
    /** Free bytes on the volume of [dir]. */
    fun freeBytes(dir: File): Long

    /** Writes [bytes] under [dir], syncs, reads them back and deletes them: (write, read) in MB/s. */
    fun writeRead(dir: File, bytes: Long): Pair<Float, Float>
}

/** v1.10 (F1, design 2.12): one past exit of the app as `ApplicationExitInfo` reports it. */
data class ExitRecord(val atMillis: Long, val lowMemory: Boolean, val signaled9: Boolean, val foreground: Boolean)

/** v1.10 (F1, design 2.12): the app's past exits, injectable (`EngineConfig.exitInfo`). */
interface ExitInfoSource {
    /** API 30+; empty below. */
    fun exits(sinceMillis: Long): List<ExitRecord>

    /** `ActivityManager.isLowMemoryKillReportSupported()`. */
    fun lmkReportsSupported(): Boolean
}
