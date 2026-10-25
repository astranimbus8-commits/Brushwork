package com.brushwork.paint.engine.check

import android.content.Context
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.budget.MemSnapshot
import java.io.File

/** v1.10 (A10, design 2.10 M2): the bytes the bounded native probe reached and its [M2Outcome]. */
data class M2Result(val bytes: Long, val outcome: String)

/** v1.10 (A10, design 2.10 M3): Deflate level 1 and Inflate speeds in MB/s of raw pixels, and the ratio (raw / packed). */
data class M3Result(val deflateMBs: Float, val inflateMBs: Float, val ratio: Float)

/** v1.10 (A10, design 2.10 M4): write and read speeds in MB/s (the read is shown, never decides) and the free bytes. */
data class M4Result(val writeMBs: Float, val readMBs: Float, val freeBytes: Long)

/** v1.10 (A10, design 2.10 M5): the median composite of 64 layers in ms and the Classic A/B's smallest N (null: off). */
data class M5Result(val compositeMs: Float, val belowCacheMinLayers: Int?)

/**
 * v1.10 (A10, design 2.10): the check's measuring steps, run one by one on the check's thread by
 * [AutoCheckRunner] (tests: a fake with chosen numbers). A step given a deadline (`System.nanoTime`)
 * measures until it, keeping what it measured; [stop] true makes M3 and M5 return null (not
 * finished: the runner runs the step again when it resumes).
 */
interface CheckSteps {
    /** M1: `MemoryInfo` and J. */
    fun m1(): MemSnapshot

    /** M2: the bounded native probe (the runner keeps the marker around it). */
    fun m2(): M2Result

    /** M3: Deflate and Inflate of 48 synthetic tiles; null when stopped. */
    fun m3(deadlineNanos: Long, stop: () -> Boolean): M3Result?

    /** M4: the storage write, read and free space. */
    fun m4(): M4Result

    /** M5: the composite and the Classic A/B; null when stopped. */
    fun m5(deadlineNanos: Long, stop: () -> Boolean): M5Result?
}

/**
 * v1.10 (A10, design 2.10): the phone's [CheckSteps], through the injectable probes
 * (`EngineConfig.memoryProbe` and `storageProbe`) and the real codec and compositor.
 */
class AndroidCheckSteps(private val context: Context) : CheckSteps {
    override fun m1(): MemSnapshot = EngineConfig.memoryProbe.snapshot()

    /**
     * Up to `min(256 MiB, availMem - threshold - 768 MiB)` in 16 MiB steps, stopping below
     * `threshold + 768 MiB` (the probe re-reads `availMem` before each step). The probe frees what
     * it reached and returns its size, so a stop short of the cap is told apart afterwards: near
     * the margin (or with `lowMemory`) it was the margin, else an allocation failed. A throw is
     * `ALLOC_FAILED` with nothing reached.
     */
    override fun m2(): M2Result {
        val probe = EngineConfig.memoryProbe
        val before = probe.snapshot()
        val stopBelow = before.threshold + M2_MARGIN
        val cap = minOf(M2_CAP, before.availMem - stopBelow)
        if (cap < M2_STEP || before.lowMemory) return M2Result(0L, M2Outcome.MARGIN_LIMITED)
        val reached = try {
            probe.probeNative(M2_STEP, stopBelow, cap)
        } catch (e: OutOfMemoryError) {
            return M2Result(0L, M2Outcome.ALLOC_FAILED)
        } catch (e: Exception) {
            return M2Result(0L, M2Outcome.ALLOC_FAILED)
        }
        if (reached >= M2_CAP) return M2Result(reached, M2Outcome.REACHED)
        val after = runCatching { probe.snapshot() }.getOrNull()
        val margin = before.availMem - reached < stopBelow + M2_STEP || after?.lowMemory == true
        return M2Result(reached, if (margin) M2Outcome.MARGIN_LIMITED else M2Outcome.ALLOC_FAILED)
    }

    override fun m3(deadlineNanos: Long, stop: () -> Boolean): M3Result? = DeflateBench.run(deadlineNanos, stop)

    /** 24 MiB written under `noBackupFilesDir/engine-probe/` (outside `swap/`); a probe that throws reads as slow and full. */
    override fun m4(): M4Result {
        val probe = EngineConfig.storageProbe
        val (write, read) = runCatching { probe.writeRead(File(context.noBackupFilesDir, PROBE_DIR), M4_BYTES) }.getOrDefault(0f to 0f)
        val free = runCatching { probe.freeBytes(context.noBackupFilesDir) }.getOrDefault(0L)
        return M4Result(write, read, free)
    }

    override fun m5(deadlineNanos: Long, stop: () -> Boolean): M5Result? = CompositeBench.run(deadlineNanos, stop)

    companion object {
        private const val MIB = 1L shl 20

        /** M2: one 2048 x 2048 ARGB bitmap. */
        const val M2_STEP = 16L * MIB

        /** M2: the most it allocates. */
        const val M2_CAP = 256L * MIB

        /** M2 always stays this far above the low-memory killer's threshold. */
        const val M2_MARGIN = 768L * MIB

        /** M4: what it writes (in 4 MiB chunks, `StorageProbe`'s). */
        const val M4_BYTES = 24L * MIB

        /** M4's folder in `noBackupFilesDir` (never under `swap/`, so the swap janitor never sees it). */
        const val PROBE_DIR = "engine-probe"
    }
}
