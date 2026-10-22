package com.brushwork.paint.testing

import com.brushwork.paint.engine.budget.ExitInfoSource
import com.brushwork.paint.engine.budget.ExitRecord
import com.brushwork.paint.engine.budget.MemSnapshot
import com.brushwork.paint.engine.budget.MemoryProbe
import com.brushwork.paint.engine.budget.StorageProbe
import java.io.File

private const val MIB = 1L shl 20

/**
 * v1.10 F1 (design 2.4, 2.10 and 2.16; lead, frozen kit): the phones the memory tests run on, as
 * `ActivityManager.MemoryInfo` and `Runtime` would report them. [T606] is the user's phone (ZTE
 * Axon 50 Lite, 4 GB: `totalMem` about 3.7 GiB, `availMem` at open 1.5-2.2 GiB, section 2.4); the
 * others change one input at a time against it where they can: [GB3], [GB6] and [GB8] the memory
 * size, [LOW_RAM] `isLowRamDevice` (an Android Go phone, factor 0.6), [BIT32] `is64Bit` (an
 * armeabi-v7a build on the T606's memory: factor 0.6, the 700 MiB ceiling). [UNKNOWN] is what
 * Robolectric and the F1 `AndroidMemoryProbe` stub report: all zero, so the budgets stay v1.9's
 * (J3, I32). The numbers are plausible, not measured; a test that depends on one states it.
 */
enum class MemoryPreset(val snapshot: MemSnapshot) {
    T606(mem(total = 3788 * MIB, avail = 1843 * MIB, threshold = 216 * MIB, javaMax = 512 * MIB)),
    GB3(mem(total = 2816 * MIB, avail = 1100 * MIB, threshold = 162 * MIB, javaMax = 512 * MIB)),
    GB6(mem(total = 5632 * MIB, avail = 2900 * MIB, threshold = 226 * MIB, javaMax = 512 * MIB)),
    GB8(mem(total = 7552 * MIB, avail = 3900 * MIB, threshold = 256 * MIB, javaMax = 512 * MIB)),
    LOW_RAM(mem(total = 1920 * MIB, avail = 700 * MIB, threshold = 128 * MIB, javaMax = 256 * MIB, lowRam = true)),
    BIT32(mem(total = 3788 * MIB, avail = 1843 * MIB, threshold = 216 * MIB, javaMax = 512 * MIB, is64 = false)),
    UNKNOWN(MemSnapshot(0L, 0L, 0L, false, false, false, 0L, 0L, 0L)),
}

private fun mem(total: Long, avail: Long, threshold: Long, javaMax: Long, lowRam: Boolean = false, is64: Boolean = true) =
    MemSnapshot(
        totalMem = total, availMem = avail, threshold = threshold, lowMemory = false, isLowRamDevice = lowRam, is64Bit = is64,
        javaMax = javaMax, javaUsed = 48 * MIB, nativeAllocated = 96 * MIB,
    )

/**
 * v1.10 F1 (design 2.16; lead, frozen kit): a [MemoryProbe] that reports a [MemoryPreset] and lets
 * a test change any field ([totalMem], [availMem], ... are settable; [snapshot] reads them each
 * time). [snapshotFailure] makes [snapshot] throw it (J3: a throwing probe keeps v1.9's budgets).
 * [probeNative] (M2) returns `min(nativeReach, capBytes)`, or 0 when `availMem` is already below
 * `stopBelowAvail`, or throws [probeFailure]; it allocates nothing and counts its calls in
 * [probeCalls]. Install it through [EngineRule] (`memory = FakeMemoryProbe(MemoryPreset.T606)`).
 */
class FakeMemoryProbe(preset: MemoryPreset = MemoryPreset.T606) : MemoryProbe {
    @Volatile var totalMem: Long = preset.snapshot.totalMem
    @Volatile var availMem: Long = preset.snapshot.availMem
    @Volatile var threshold: Long = preset.snapshot.threshold
    @Volatile var lowMemory: Boolean = preset.snapshot.lowMemory
    @Volatile var isLowRamDevice: Boolean = preset.snapshot.isLowRamDevice
    @Volatile var is64Bit: Boolean = preset.snapshot.is64Bit
    @Volatile var javaMax: Long = preset.snapshot.javaMax
    @Volatile var javaUsed: Long = preset.snapshot.javaUsed
    @Volatile var nativeAllocated: Long = preset.snapshot.nativeAllocated

    /** What [probeNative] reaches before it would stop (M2); the default is half of [availMem] at creation. */
    @Volatile var nativeReach: Long = preset.snapshot.availMem / 2

    /** Thrown by [snapshot] when not null. */
    @Volatile var snapshotFailure: Throwable? = null

    /** Thrown by [probeNative] when not null. */
    @Volatile var probeFailure: Throwable? = null

    /** How many times [snapshot] and [probeNative] ran. */
    @Volatile var snapshots = 0
        private set
    @Volatile var probeCalls = 0
        private set

    override fun snapshot(): MemSnapshot {
        snapshots++
        snapshotFailure?.let { throw it }
        return MemSnapshot(totalMem, availMem, threshold, lowMemory, isLowRamDevice, is64Bit, javaMax, javaUsed, nativeAllocated)
    }

    override fun probeNative(stepBytes: Long, stopBelowAvail: Long, capBytes: Long): Long {
        probeCalls++
        probeFailure?.let { throw it }
        if (availMem < stopBelowAvail) return 0L
        return minOf(nativeReach, capBytes)
    }
}

/**
 * v1.10 F1 (design 2.8, 2.10 M4 and 2.16; lead, frozen kit): a [StorageProbe] with settable
 * answers: [free] bytes on every volume (default 8 GiB, so FULL's 2 GiB minimum holds), and the
 * [writeMBs] / [readMBs] speeds [writeRead] reports. It touches no file; [dirs] records the
 * directories asked about. [failure] makes both calls throw it.
 */
class FakeStorageProbe(
    @Volatile var free: Long = 8L shl 30,
    @Volatile var writeMBs: Float = 120f,
    @Volatile var readMBs: Float = 250f,
) : StorageProbe {
    /** Thrown by both calls when not null. */
    @Volatile var failure: Throwable? = null

    /** The directories asked about, in order. */
    val dirs: MutableList<File> = java.util.Collections.synchronizedList(mutableListOf())

    override fun freeBytes(dir: File): Long {
        dirs += dir
        failure?.let { throw it }
        return free
    }

    override fun writeRead(dir: File, bytes: Long): Pair<Float, Float> {
        dirs += dir
        failure?.let { throw it }
        return writeMBs to readMBs
    }
}

/**
 * v1.10 F1 (design 2.12 and 2.16; lead, frozen kit): an [ExitInfoSource] over [records] (add
 * `ExitRecord`s in any order). [exits] returns the records strictly after `sinceMillis`, newest
 * first as `ActivityManager.getHistoricalProcessExitReasons` lists them, and nothing when [api30]
 * is false (the API 29 and lower answer). [lmkSupported] answers [lmkReportsSupported].
 */
class FakeExitInfo(
    @Volatile var lmkSupported: Boolean = true,
    @Volatile var api30: Boolean = true,
) : ExitInfoSource {
    /** Every past exit of the app; tests add to it. */
    val records: MutableList<ExitRecord> = java.util.Collections.synchronizedList(mutableListOf())

    /** Adds a low-memory kill (signal 9, background unless [foreground]) at [atMillis]. */
    fun lowMemoryKill(atMillis: Long, foreground: Boolean = false): ExitRecord =
        ExitRecord(atMillis, lowMemory = true, signaled9 = true, foreground = foreground).also { records += it }

    override fun exits(sinceMillis: Long): List<ExitRecord> {
        if (!api30) return emptyList()
        return synchronized(records) { records.filter { it.atMillis > sinceMillis }.sortedByDescending { it.atMillis } }
    }

    override fun lmkReportsSupported(): Boolean = lmkSupported
}
