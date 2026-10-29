package com.brushwork.paint.engine

import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.Tile
import java.util.IdentityHashMap

/**
 * v1.10 (A7 step 2, design 2.4 "Undo trimming", finding 56): the memory policy the app runs with
 * ([GovernorPolicies.current]). What the system's trim levels free and what [admit] admits stay
 * v1.9's ([GovernorPolicyV19]); the residency also hears LOW and CRITICAL ([onPressure]; a no-op
 * at CLASSIC). [trimUndo] measures against R:
 *
 * - Memory unknown (`Budgets.known == false`, J3): v1.9's [GovernorPolicyV19.trimUndo], so the
 *   tests that pin the 70 %-of-`maxMemory` trim stay as they were.
 * - TILES and FULL: P3 runs first ([com.brushwork.paint.engine.tiles.ResidencyHooks.pressure]
 *   CRITICAL; at FULL also `swapUndoTiles` for the bytes over 0.90 R), then only the remainder
 *   `ledger.native − evictableTileBytes − 0.90·R` is dropped from the oldest steps, the newest
 *   [GovernorPolicyV19.KEEP_UNDO_STEPS] kept. `evictableTileBytes` is every tile byte in the
 *   ledger: the tiles leave by P3 (eviction to the saved file, swap), never by the undo history.
 *   Gate 2 (O6, LEAD RULING G2-O-c): at TILES, with no swap store, the bytes of the tiles only
 *   undo steps hold are not in it, so the trim frees them by dropping the oldest steps.
 *   v1.9's mixed target (Java plus native heap over 0.70·J) goes, so a phone with 30 resident
 *   layers no longer loses its undo history.
 * - CLASSIC on known memory: nothing parks and the ledger holds no layer (P6), so the measure
 *   stays v1.9's (the Java heap plus the native heap, [GovernorPolicyV19.heap]) against
 *   `0.70·J + 0.90·R`: the Java share v1.9 allowed plus the native share the layer count uses.
 *
 * Main thread for [trimUndo] (the undo history's entry calls it under CRITICAL), as v1.9.
 */
object GovernorPolicyV110 : GovernorPolicy {
    /** The share of R that memory in use may reach before undo steps go at TILES and FULL. */
    const val R_TARGET = 0.90

    /**
     * v1.9's trims first (the projections and caches go first, P5; under CRITICAL the undo
     * history's entry runs [trimUndo], which parks before it drops), then the residency hears LOW
     * and CRITICAL (P2: every eligible layer parks at once). Main thread.
     */
    override fun onPressure(pressure: MemoryPressure, entries: List<MemoryGovernor.Entry>) {
        GovernorPolicyV19.onPressure(pressure, entries)
        if (pressure != MemoryPressure.LOW && pressure != MemoryPressure.CRITICAL) return
        try {
            EngineConfig.residency.pressure(pressure)
        } catch (t: Exception) {
            runCatching { CrashLog.record(t, "trim residency", fatal = false) }
        }
    }

    override fun admit(bytes: Long, purpose: String): Boolean = GovernorPolicyV19.admit(bytes, purpose)

    override fun trimUndo(history: UndoManager): GovernorPolicyV19.UndoTrim {
        val budget = EngineConfig.budget
        if (!budget.budgets.known) return GovernorPolicyV19.trimUndo(history)
        val r = budget.nativeBudget
        val over = when (EngineConfig.level) {
            EngineLevel.CLASSIC -> {
                val h = GovernorPolicyV19.heap
                h.used() + h.nativeUsed() - (h.max() * GovernorPolicyV19.HEAP_TARGET).toLong() - (r * R_TARGET).toLong()
            }
            EngineLevel.TILES, EngineLevel.FULL -> tiledOver(history, budget.ledger.native, r) ?: return GovernorPolicyV19.UndoTrim(0, 0L)
        }
        return dropOldest(history, over)
    }

    /**
     * P3 first, then what is still over 0.90 R once the tile bytes P3 can move are left to it;
     * null when nothing is over. v1.10 Gate 2 (O6, LEAD RULING G2-O-c): at TILES no swap store
     * can take the tiles only undo steps hold, so their bytes ([undoOnlyBytes]) count toward the
     * trim and memory pressure frees them by dropping the oldest steps; at FULL `swapUndoTiles`
     * stays first and every tile byte is left to P3, unchanged.
     */
    private fun tiledOver(history: UndoManager, nativeBefore: Long, r: Long): Long? {
        val target = (r * R_TARGET).toLong()
        if (nativeBefore - target <= 0L) return null
        val hooks = EngineConfig.residency
        hooks.pressure(MemoryPressure.CRITICAL)
        if (EngineConfig.level == EngineLevel.FULL) {
            val stillOver = EngineConfig.budget.ledger.native - target
            if (stillOver > 0L) hooks.swapUndoTiles(stillOver)
        }
        val ledger = EngineConfig.budget.ledger
        val undoOnly = if (EngineConfig.level == EngineLevel.TILES) undoOnlyBytes(history) else 0L
        val over = ledger.native - (ledger.of(LedgerCategory.TILES) - undoOnly) - target
        return over.takeIf { it > 0L }
    }

    /**
     * v1.10 Gate 2 (O6, G2-O-c): the bytes of the resident tiles that only [history]'s undo steps
     * hold (every hold of the tile is a step's: no layer, redo step or lease holds it), each tile
     * once: what dropping those steps can give back. Main only (tile refs are main-only).
     */
    internal fun undoOnlyBytes(history: UndoManager): Long {
        val holds = IdentityHashMap<Tile, Int>()
        for (i in 0 until history.undoCount) (history.undoAt(i) as? TileHolder)?.forEachTile { t -> holds[t] = (holds[t] ?: 0) + 1 }
        var bytes = 0L
        for ((t, n) in holds) if (n == t.refs && t.bitmap != null && t.solid == null) bytes += t.bytes
        return bytes
    }

    /** v1.9's loop: drops the oldest steps until what they held exceeds [over], keeping the newest. */
    private fun dropOldest(history: UndoManager, over: Long): GovernorPolicyV19.UndoTrim {
        if (over <= 0L) return GovernorPolicyV19.UndoTrim(0, 0L)
        var freed = 0L
        val dropped = history.trimOldest(GovernorPolicyV19.KEEP_UNDO_STEPS) {
            if (freed > over) true else { freed += history.undoAt(0)?.byteSize?.coerceAtLeast(0L) ?: 0L; false }
        }
        return GovernorPolicyV19.UndoTrim(dropped, if (dropped == 0) 0L else freed)
    }
}
