package com.brushwork.paint.engine

import android.os.Handler
import android.os.Looper
import com.brushwork.paint.engine.budget.AndroidExitInfo
import com.brushwork.paint.engine.budget.AndroidMemoryProbe
import com.brushwork.paint.engine.budget.AndroidStorageProbe
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.ExitInfoSource
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.budget.MemoryBudget
import com.brushwork.paint.engine.budget.MemoryProbe
import com.brushwork.paint.engine.budget.PixelLedger
import com.brushwork.paint.engine.budget.StorageProbe
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.NoSwap
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.SwapStore
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.engine.EngineCheckResult
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** v1.10 (F1, design 2.11): the layer storage setting (`AppSettings.layerStorageMode`; its label is `LabelsV110`'s). */
enum class LayerStorageMode { AUTOMATIC, FULL, CLASSIC }

/** v1.10 (F1, design 2.0, J1): what the engine runs with while an editor is open (I31: fixed for the session). */
enum class EngineLevel { CLASSIC, TILES, FULL }

/**
 * v1.10 (F1, design 2.0, 2.7 and 2.10; lead, complete): the engine's settings for the open
 * session, or for the gallery between sessions. [openSession] fixes the level, budgets, flags,
 * `LayerBudget.current`, residency and swap before the controller exists; [closeSession] puts the
 * gallery's back. Only this file and `EditorSession.kt` open or close a session (rule E).
 *
 * Without a session the level is CLASSIC with v1.9's budgets ([Budgets.v19]) and the residency is
 * [ClassicResidency], so the app behaves as v1.9. In unit tests run with `-PengineMode` the
 * defaults follow [testMode] instead (TILES and FULL: [ChaosResidency]), so the tests that never
 * open a session run at that level too.
 */
object EngineConfig {
    @Volatile
    var level: EngineLevel = testMode() ?: EngineLevel.CLASSIC
        private set

    @Volatile
    var budget: MemoryBudget = MemoryBudget(Budgets.v19(Runtime.getRuntime().maxMemory()), PixelLedger())
        private set

    /** Classic: `StrokeBelowCache` runs from this many visible layers below the split; null = off (v1.9). */
    @Volatile
    var strokeBelowMinLayers: Int? = null
        private set

    /** The above projection may run (TILES and FULL, when the check's M5 allowed it). */
    @Volatile
    var aboveProjection: Boolean = false
        private set

    /** Swap runs (FULL only). */
    val swap: Boolean get() = level == EngineLevel.FULL

    @Volatile
    var residency: ResidencyHooks = defaultResidency()
        internal set

    @Volatile
    var swapStore: SwapStore = NoSwap
        internal set

    @Volatile
    var memoryProbe: MemoryProbe = AndroidMemoryProbe
        internal set

    @Volatile
    var storageProbe: StorageProbe = AndroidStorageProbe
        internal set

    @Volatile
    var exitInfo: ExitInfoSource = AndroidExitInfo
        internal set

    /** FULL becomes TILES below this much free storage at open (section 2.10). */
    private const val FULL_MIN_FREE = 2L shl 30

    /** True between [openSession] and [closeSession]. */
    @Volatile
    private var sessionOpen = false

    /** The gallery's `LayerBudget.current` ([applyIdle]); v1.9's until A10 calls it. */
    @Volatile
    private var idleLayerBudget: LayerBudget = LayerBudgetV19

    /** The gallery's budgets ([applyIdle]). */
    @Volatile
    private var idleBudgets: Budgets = budget.budgets

    /** Section 2.10 table (J15, I32). */
    fun resolve(mode: LayerStorageMode, check: EngineCheckResult?, deviceKey: String, freeBytes: Long, memoryKnown: Boolean): EngineLevel {
        if (!memoryKnown) return EngineLevel.CLASSIC
        val own = check != null && check.deviceKey == deviceKey
        return when {
            own && mode == LayerStorageMode.CLASSIC -> EngineLevel.CLASSIC
            own && mode == LayerStorageMode.FULL -> if (freeBytes < FULL_MIN_FREE) EngineLevel.TILES else EngineLevel.FULL
            // AUTOMATIC; CLASSIC or FULL without a check of this phone are treated as AUTOMATIC.
            else -> check?.level ?: EngineLevel.CLASSIC
        }
    }

    /** Build.MODEL + totalMem rounded to 256 MiB (J13); a FINGERPRINT change only sets engineCheckPending. */
    fun deviceKey(model: String, totalMem: Long): String {
        val unit = 256L shl 20
        val mib = (totalMem + unit / 2) / unit * 256
        return "$model/${mib}MiB"
    }

    /** EditorSession, before the controller exists: fixes level, budgets, flags, LayerBudget.current, residency and swap. */
    fun openSession(level: EngineLevel, budgets: Budgets, check: EngineCheckResult?) {
        val lv = testMode() ?: level
        val old = swapStore
        swapStore = NoSwap
        if (old !== NoSwap) old.close()
        this.level = lv
        budget = MemoryBudget(budgets, budget.ledger)
        strokeBelowMinLayers = if (lv == EngineLevel.CLASSIC) check?.belowCacheMinLayers else null
        aboveProjection = lv != EngineLevel.CLASSIC && check?.aboveProjection == true
        LayerBudget.current = LayerBudgetV110(budget, lv)
        residency = residencyFor(lv)
        sessionOpen = true
    }

    /** Editor closed: back to the gallery budget of applyIdle. */
    fun closeSession() {
        if (!sessionOpen) return
        sessionOpen = false
        val old = swapStore
        swapStore = NoSwap
        if (old !== NoSwap) old.close()
        level = testMode() ?: EngineLevel.CLASSIC
        budget = MemoryBudget(idleBudgets, budget.ledger)
        strokeBelowMinLayers = null
        aboveProjection = false
        LayerBudget.current = idleLayerBudget
        residency = defaultResidency()
        synchronized(pending) { pending.clear() }
        synchronized(announced) { announced.clear() }
    }

    /** Gallery: LayerBudget.current for new canvases and imports (A10 calls it at start and after a check). */
    fun applyIdle(mode: LayerStorageMode, check: EngineCheckResult?, budgets: Budgets) {
        val lv = when {
            !budgets.known -> EngineLevel.CLASSIC
            mode == LayerStorageMode.CLASSIC -> EngineLevel.CLASSIC
            mode == LayerStorageMode.FULL -> EngineLevel.FULL
            else -> check?.level ?: EngineLevel.CLASSIC
        }
        idleBudgets = budgets
        idleLayerBudget = LayerBudgetV110(MemoryBudget(budgets, budget.ledger), lv)
        if (!sessionOpen) {
            budget = MemoryBudget(budgets, budget.ledger)
            LayerBudget.current = idleLayerBudget
        }
    }

    /** brushwork.engineMode (tests only). */
    internal fun testMode(): EngineLevel? = when (System.getProperty("brushwork.engineMode")?.trim()?.lowercase()) {
        "classic" -> EngineLevel.CLASSIC
        "tiles" -> EngineLevel.TILES
        "full" -> EngineLevel.FULL
        else -> null
    }

    /** brushwork.parkChaos (tests only): [ChaosResidency] parks again at every idle of the main looper. */
    internal fun parkChaos(): Boolean = System.getProperty("brushwork.parkChaos")?.trim()?.lowercase() == "true"

    /** The residency without a session: [ChaosResidency] under a test level above CLASSIC, else [ClassicResidency]. */
    private fun defaultResidency(): ResidencyHooks = residencyFor(testMode() ?: EngineLevel.CLASSIC)

    /** F1: CLASSIC everywhere but under a test level above CLASSIC; A8 installs its `ResidencyPolicy` through the setter. */
    private fun residencyFor(level: EngineLevel): ResidencyHooks =
        if (level != EngineLevel.CLASSIC && testMode() != null) ChaosResidency(parkChaos()) else ClassicResidency

    // ---------------------------------------------------------------- activation (section 2.7)

    /** Documents whose active layer changed in the current main-thread task. */
    private val pending: MutableSet<Document> = Collections.newSetFromMap(IdentityHashMap())

    /** The layer last announced active per document. */
    private val announced = WeakHashMap<Document, Layer>()

    /**
     * The Document.activeLayerIndex setter: at the end of the current main-thread task (at once
     * where there is no main looper) [settle] tells the residency about the change, once per task
     * however many times the index was set. Nothing in CLASSIC.
     */
    internal fun activeIndexSet(doc: Document) {
        if (residency === ClassicResidency) return
        if (!synchronized(pending) { pending.add(doc) }) return
        val main = Looper.getMainLooper()
        if (main == null) settle(doc) else Handler(main).post { settle(doc) }
    }

    /** Calls `deactivated(old)` and `activate(new)` once when [doc]'s resolved active layer id changed. Main thread. */
    private fun settle(doc: Document) {
        synchronized(pending) { pending.remove(doc) }
        val hooks = residency
        if (hooks === ClassicResidency) return
        val active = doc.layers.getOrNull(doc.activeLayerIndex)
        val old = synchronized(announced) { announced[doc] }
        if (old?.id != active?.id) {
            // A layer that left the document belongs to the undo history; it is not deactivated.
            if (old != null && doc.layers.any { it === old }) hooks.deactivated(old)
            if (active != null) hooks.activate(active)
            synchronized(announced) { if (active != null) announced[doc] = active else announced.remove(doc) }
        }
        (hooks as? ChaosResidency)?.settled(doc)
    }
}
