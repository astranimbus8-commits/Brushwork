package com.brushwork.paint.engine.budget

import android.os.Looper
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryPressure
import kotlin.math.max
import kotlin.math.min

/**
 * v1.10 (F1, design 2.4; signatures frozen, bodies A7): J ([javaMax], the Java heap) and R
 * ([nativeBudget], the native pixel budget) for one session. [known] is false when the phone's
 * memory is unknown (J3, I32): every policy then gives v1.9's numbers.
 */
data class Budgets(val javaMax: Long, val nativeBudget: Long, val known: Boolean, val stepDown: Int) {
    companion object {
        /** v1.9's numbers: R = 0.55 J, memory unknown. */
        fun v19(javaMax: Long): Budgets = Budgets(javaMax, (javaMax * 0.55).toLong(), known = false, stepDown = 0)

        /**
         * Section 2.4, read once per editor session at open (I31). J is the probe's `javaMax`
         * (`Runtime.maxMemory` when the probe gives none) and R is [nativeBudgetOf] of the
         * snapshot, with [stepDown] (the safety nets' step, section 2.12) clamped to 0..2.
         *
         * J3: [v19] of `Runtime.maxMemory` when the probe reports `totalMem == 0` (Robolectric and
         * a probe without a context do) or throws, so unknown memory keeps v1.9's numbers (I32).
         */
        fun compute(probe: MemoryProbe, stepDown: Int): Budgets {
            val runtimeMax = Runtime.getRuntime().maxMemory()
            val s = runCatching { probe.snapshot() }.getOrNull()
            if (s == null || s.totalMem <= 0L) return v19(runtimeMax)
            val javaMax = if (s.javaMax > 0L) s.javaMax else runtimeMax
            val step = stepDown.coerceIn(0, STEP_FACTORS.lastIndex)
            return Budgets(javaMax, nativeBudgetOf(s, javaMax, step), known = true, stepDown = step)
        }

        /**
         * R of section 2.4 for snapshot [s], J = [javaMax] and step [step] (0..2):
         *
         * ```
         * base = min(0.30 × totalMem, availMem − 400 MiB)
         * f    = (isLowRamDevice ? 0.6 : 1.0) × (is64Bit ? 1.0 : 0.6) × {1.0, 0.8, 0.64}[step]
         * R    = clamp(base × f, lo = 0.55 × J, hi = is64Bit ? 2 GiB : 700 MiB)
         * ```
         *
         * `lo` is what v1.9 gave its layers, so R is never below it; where `lo` is above `hi` (a
         * 32-bit build with a heap above 1.2 GiB) `lo` wins.
         */
        internal fun nativeBudgetOf(s: MemSnapshot, javaMax: Long, step: Int): Long {
            val base = min(TOTAL_SHARE * s.totalMem, (s.availMem - AVAIL_RESERVE).toDouble())
            val f = (if (s.isLowRamDevice) LOW_RAM_FACTOR else 1.0) *
                (if (s.is64Bit) 1.0 else BIT32_FACTOR) *
                STEP_FACTORS[step.coerceIn(0, STEP_FACTORS.lastIndex)]
            val lo = (javaMax * LO_SHARE).toLong()
            val hi = if (s.is64Bit) HI_64 else HI_32
            return max(lo, min(hi, (base * f).toLong()))
        }

        /** R's share of `totalMem`. */
        private const val TOTAL_SHARE = 0.30

        /** What R leaves of `availMem` at open (400 MiB). */
        private const val AVAIL_RESERVE = 400L shl 20

        /** f on an `isLowRamDevice` phone (Android Go). */
        private const val LOW_RAM_FACTOR = 0.6

        /** f on a 32-bit build (armeabi-v7a). */
        private const val BIT32_FACTOR = 0.6

        /** f per safety-net step (section 2.12): none, one, two. */
        private val STEP_FACTORS = doubleArrayOf(1.0, 0.8, 0.64)

        /** R's floor as a share of J: v1.9's layer share. */
        private const val LO_SHARE = 0.55

        /** R's ceiling on a 64-bit build (2 GiB). */
        private const val HI_64 = 2L shl 30

        /** R's ceiling on a 32-bit build (700 MiB). */
        private const val HI_32 = 700L shl 20
    }
}

/**
 * v1.10 (F1, design 2.4 and 2.6; signatures frozen, bodies A7): the session's budgets and the
 * native admission over [ledger].
 *
 * With known memory the ledger's [PixelLedger.limit] is R: this budget sets it when it is made
 * and again before every admission, because `EngineConfig` makes a new budget over the one shared
 * ledger at every session open and close and for the gallery (`applyIdle`, which may run while a
 * session is open), so the budget that admits is the one whose R holds. With unknown memory the
 * limit is unlimited and every admission is granted and counted, as in v1.9 (I32).
 */
class MemoryBudget(val budgets: Budgets, val ledger: PixelLedger) : NativeAdmission {
    /** J. */
    val javaMax: Long get() = budgets.javaMax

    /** R. */
    val nativeBudget: Long get() = budgets.nativeBudget

    /** What [ledger]'s limit is while this budget admits: R, or unlimited when memory is unknown. */
    private val ledgerLimit: Long = if (budgets.known) budgets.nativeBudget else Long.MAX_VALUE

    /** True while [admitNative] asks the residency for room (main only), so a nested admission never asks again. */
    private var freeing = false

    init {
        ledger.limit = ledgerLimit
    }

    /**
     * Section 2.6: the undo history's cap at [level]. Unknown memory and CLASSIC: v1.9's
     * `max(48 MiB, J/4)`; TILES: `max(128 MiB, R/8)`; FULL: `max(128 MiB, min(1 GiB, swapCap/8))`,
     * where swapCap is the session's swap store capacity (`EngineConfig.swapStore`; none yet
     * gives the 128 MiB floor).
     */
    fun undoCap(level: EngineLevel): Long = undoCapFor(level, EngineConfig.swapStore.capacityBytes)

    /** [undoCap] with the swap capacity [swapCap] given. */
    internal fun undoCapFor(level: EngineLevel, swapCap: Long): Long = when {
        !budgets.known || level == EngineLevel.CLASSIC -> max(V19_FLOOR, javaMax / 4)
        level == EngineLevel.TILES -> max(TILED_UNDO_FLOOR, nativeBudget / 8)
        else -> max(TILED_UNDO_FLOOR, min(FULL_UNDO_CEILING, swapCap.coerceAtLeast(0L) / 8))
    }

    /** Section 2.4: the clone source's snapshot cap, `max(48 MiB, R/8)`; unknown memory: v1.9's `max(48 MiB, J/4)`. */
    fun cloneSnapshotCap(): Long = if (budgets.known) max(V19_FLOOR, nativeBudget / 8) else max(V19_FLOOR, javaMax / 4)

    /**
     * P3 (section 2.0): the native bytes tiles may take now, R less what the ledger holds outside
     * TILES (the resident layers, masks and array sources, the display, the projections, the undo
     * RAM, the transients and the thumbnails), never below 0. Unknown memory: R.
     */
    fun tileRam(): Long {
        if (!budgets.known) return nativeBudget
        val held = ledger.native - ledger.of(LedgerCategory.TILES)
        return (nativeBudget - held).coerceAtLeast(0L)
    }

    /**
     * Reserves [bytes] under [purpose]'s category against R ([PixelLedger.tryReserve]). On the main
     * thread a refused reservation first asks the residency for room (P2 and P5: park every
     * eligible layer, drop the projections; `ResidencyHooks.pressure(CRITICAL)`, a no-op at
     * CLASSIC) and tries once more; off main it is the reservation alone. A caller whose
     * allocation then fails gives the bytes back with [release].
     */
    override fun admitNative(bytes: Long, purpose: NativePurpose): Boolean {
        if (ledger.limit != ledgerLimit) ledger.limit = ledgerLimit
        val c = categoryOf(purpose)
        if (ledger.tryReserve(c, bytes)) return true
        if (freeing || !onMain()) return false
        freeing = true
        try {
            EngineConfig.residency.pressure(MemoryPressure.CRITICAL)
        } finally {
            freeing = false
        }
        return ledger.tryReserve(c, bytes)
    }

    override fun release(bytes: Long, purpose: NativePurpose) = ledger.release(categoryOf(purpose), bytes)

    companion object {
        /** v1.9's 48 MiB floor of the undo and clone caps. */
        private const val V19_FLOOR = 48L shl 20

        /** The undo cap's floor at TILES and FULL (128 MiB). */
        private const val TILED_UNDO_FLOOR = 128L shl 20

        /** The undo cap's ceiling at FULL (1 GiB). */
        private const val FULL_UNDO_CEILING = 1L shl 30

        /** The ledger category an admission for [purpose] counts under. */
        internal fun categoryOf(purpose: NativePurpose): LedgerCategory = when (purpose) {
            NativePurpose.LAYER -> LedgerCategory.RESIDENT_LAYERS
            NativePurpose.MASK -> LedgerCategory.MASKS
            NativePurpose.TILE -> LedgerCategory.TILES
            NativePurpose.TRANSIENT -> LedgerCategory.TRANSIENT
            NativePurpose.PROJECTION -> LedgerCategory.PROJECTIONS
            NativePurpose.DISPLAY -> LedgerCategory.DISPLAY
            NativePurpose.UNDO -> LedgerCategory.UNDO
            NativePurpose.ARRAY -> LedgerCategory.ARRAYS
            NativePurpose.THUMBNAIL -> LedgerCategory.THUMBNAILS
        }

        /** True on the main thread; false where there is no main looper (a plain JVM test). */
        private fun onMain(): Boolean = try {
            Looper.getMainLooper()?.isCurrentThread == true
        } catch (e: RuntimeException) {
            false
        }
    }
}
