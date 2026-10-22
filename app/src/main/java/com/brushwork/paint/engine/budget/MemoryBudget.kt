package com.brushwork.paint.engine.budget

import com.brushwork.paint.engine.EngineLevel
import kotlin.math.max

/**
 * v1.10 (F1, design 2.4; signatures frozen, bodies A7): J ([javaMax], the Java heap) and R
 * ([nativeBudget], the native pixel budget) for one session. [known] is false when the phone's
 * memory is unknown (J3, I32): every policy then gives v1.9's numbers.
 */
data class Budgets(val javaMax: Long, val nativeBudget: Long, val known: Boolean, val stepDown: Int) {
    companion object {
        /** v1.9's numbers: R = 0.55 J, memory unknown. */
        fun v19(javaMax: Long): Budgets = Budgets(javaMax, (javaMax * 0.55).toLong(), known = false, stepDown = 0)

        /** Section 2.4. J3: v19(javaMax) when totalMem == 0 or the probe throws. F1 body: always v19. */
        @Suppress("UNUSED_PARAMETER")
        fun compute(probe: MemoryProbe, stepDown: Int): Budgets = v19(Runtime.getRuntime().maxMemory())
    }
}

/**
 * v1.10 (F1, design 2.4 and 2.6; signatures frozen, bodies A7): the session's budgets and the
 * native admission over [ledger]. The F1 bodies are v1.9's numbers, and [admitNative] always
 * admits ([PixelLedger.limit] stays unlimited) while counting the bytes.
 */
class MemoryBudget(val budgets: Budgets, val ledger: PixelLedger) : NativeAdmission {
    /** J. */
    val javaMax: Long get() = budgets.javaMax

    /** R. */
    val nativeBudget: Long get() = budgets.nativeBudget

    /** Section 2.6; F1: max(48 MiB, J/4), v1.9's undo cap at every level. */
    @Suppress("UNUSED_PARAMETER")
    fun undoCap(level: EngineLevel): Long = max(V19_FLOOR, javaMax / 4)

    /** Section 2.4: max(48 MiB, R/8); F1: max(48 MiB, J/4), v1.9's clone source snapshot cap. */
    fun cloneSnapshotCap(): Long = max(V19_FLOOR, javaMax / 4)

    /** P3; F1: R. */
    fun tileRam(): Long = nativeBudget

    /** F1: tryReserve with ledger.limit unlimited (always true, counted). */
    override fun admitNative(bytes: Long, purpose: NativePurpose): Boolean = ledger.tryReserve(categoryOf(purpose), bytes)

    override fun release(bytes: Long, purpose: NativePurpose) = ledger.release(categoryOf(purpose), bytes)

    companion object {
        /** v1.9's 48 MiB floor of the undo and clone caps. */
        private const val V19_FLOOR = 48L shl 20

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
    }
}
