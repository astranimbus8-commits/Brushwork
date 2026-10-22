package com.brushwork.paint.testing

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotice
import com.brushwork.paint.engine.EngineNotices
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.ExitInfoSource
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.budget.MemoryBudget
import com.brushwork.paint.engine.budget.MemoryProbe
import com.brushwork.paint.engine.budget.StorageProbe
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.SwapStore
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.storage.engine.EngineCheckResult
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * v1.10 F1 (design 2.16 and 3.3; lead, frozen kit): runs each test at one engine [level] with the
 * given probes, budgets, check, residency hooks and swap store, and puts the engine's whole static
 * state back afterwards. The way a v1.10 test chooses its level (J3: v1.10 numbers are tested only
 * through this rule with a [FakeMemoryProbe]):
 *
 * ```
 * @get:Rule val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), swap = FakeSwapStore())
 * ```
 *
 * **Before the test** it checks for a leak (below), saves `EngineConfig`'s fields,
 * `LayerBudget.current`, `PixelFacade.strict`, `EngineNotices.current` and the
 * `brushwork.engineMode` property, then installs: the property set to [level] (so a session the
 * test opens, an `EditorSession` for example, keeps [level] under any `-PengineMode`, and TILES
 * and FULL get `ChaosResidency` as in `unit-full`), the probes that are not null,
 * `EngineConfig.openSession(level, budgets, check)` with [budgets] or else
 * `Budgets.compute(memoryProbe, check?.stepDown ?: 0)` (F1: always v1.9's numbers; A7: section
 * 2.4), then [residency] and [swap] when not null, and `PixelFacade.strict = strict`.
 *
 * **After the test** (also when it failed) it closes the session it finds open (which closes the
 * swap store installed for the test and forgets the pending activations), then writes every saved
 * value back exactly.
 *
 * **The leak check.** The first time the rule runs in a fork (per Robolectric sandbox, whose
 * classes hold their own `EngineConfig`) it records `EngineConfig.level`, `LayerBudget.current`,
 * the three probes, the swap store, the residency hooks' class and the `brushwork.engineMode`
 * property. On every later entry a value that differs from that record fails the test before it
 * runs, naming the value: an earlier test of the fork changed it and did not put it back. The
 * rule then repairs the values to the record, so one leak fails one test, not every later one.
 * `LayerBudget.current` compares by what it gives: `LayerBudgetV19` and a `LayerBudgetV110` over
 * unknown memory (`budgets.known == false`, which delegates to v1.9, I32) are the same, because
 * an `EditorSession` that a test never closes leaves the latter behind without changing a number.
 *
 * Never nest two rules, and never apply one inside a test already under one: the inner check
 * would see the outer rule's level and report it as a leak. Main thread (the test's).
 */
class EngineRule(
    val level: EngineLevel = EngineLevel.CLASSIC,
    val memory: MemoryProbe? = null,
    val storage: StorageProbe? = null,
    val exits: ExitInfoSource? = null,
    val budgets: Budgets? = null,
    val check: EngineCheckResult? = null,
    val residency: ResidencyHooks? = null,
    val swap: SwapStore? = null,
    val strict: Boolean = false,
) : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            checkNoLeak(description)
            val saved = Saved.take()
            try {
                install()
                base.evaluate()
            } finally {
                saved.restore()
            }
        }
    }

    /** The budgets the rule opens its session with. */
    fun sessionBudgets(): Budgets = budgets ?: Budgets.compute(EngineConfig.memoryProbe, check?.stepDown ?: 0)

    private fun install() {
        System.setProperty(MODE, level.name.lowercase())
        memory?.let { EngineConfig.memoryProbe = it }
        storage?.let { EngineConfig.storageProbe = it }
        exits?.let { EngineConfig.exitInfo = it }
        EngineConfig.openSession(level, sessionBudgets(), check)
        residency?.let { EngineConfig.residency = it }
        swap?.let { EngineConfig.swapStore = it }
        PixelFacade.strict = strict
    }

    /** Everything the rule changes, as it was before the test. */
    private class Saved(
        private val fields: Map<String, Any?>,
        private val current: LayerBudget,
        private val strict: Boolean,
        private val notice: EngineNotice?,
        private val mode: String?,
    ) {
        fun restore() {
            setMode(mode)
            if (field("sessionOpen").get(null) == true) EngineConfig.closeSession()
            for ((name, value) in fields) field(name).set(null, value)
            LayerBudget.current = current
            PixelFacade.strict = strict
            EngineNotices.current = notice
        }

        companion object {
            fun take(): Saved = Saved(
                FIELDS.associateWith { field(it).get(null) },
                LayerBudget.current,
                PixelFacade.strict,
                EngineNotices.current,
                System.getProperty(MODE),
            )
        }
    }

    /** One value the leak check watches: how to read it, compare two readings, and write a reading back. */
    private class Watched(val name: String, val read: () -> Any?, val same: (Any?, Any?) -> Boolean, val write: (Any?) -> Unit)

    companion object {
        /** The system property `EngineConfig.testMode()` reads (`-PengineMode`). */
        const val MODE = "brushwork.engineMode"

        /** `EngineConfig`'s private static state, saved and restored around each test. */
        private val FIELDS = listOf(
            "level", "budget", "strokeBelowMinLayers", "aboveProjection", "residency", "swapStore",
            "memoryProbe", "storageProbe", "exitInfo", "sessionOpen", "idleLayerBudget", "idleBudgets",
        )

        private fun field(name: String) = EngineConfig::class.java.getDeclaredField(name).apply { isAccessible = true }

        private fun setMode(mode: String?) {
            if (mode == null) System.clearProperty(MODE) else System.setProperty(MODE, mode)
        }

        private val identical: (Any?, Any?) -> Boolean = { a, b -> a === b }

        private val WATCHED = listOf(
            Watched("EngineConfig.level", { EngineConfig.level }, { a, b -> a == b }, { field("level").set(null, it) }),
            Watched("LayerBudget.current", { LayerBudget.current }, { a, b -> a === b || (isV19(a) && isV19(b)) }, { LayerBudget.current = it as LayerBudget }),
            Watched("EngineConfig.memoryProbe", { EngineConfig.memoryProbe }, identical, { EngineConfig.memoryProbe = it as MemoryProbe }),
            Watched("EngineConfig.storageProbe", { EngineConfig.storageProbe }, identical, { EngineConfig.storageProbe = it as StorageProbe }),
            Watched("EngineConfig.exitInfo", { EngineConfig.exitInfo }, identical, { EngineConfig.exitInfo = it as ExitInfoSource }),
            Watched("EngineConfig.swapStore", { EngineConfig.swapStore }, identical, { EngineConfig.swapStore = it as SwapStore }),
            Watched("EngineConfig.residency", { EngineConfig.residency }, { a, b -> a?.javaClass == b?.javaClass }, { EngineConfig.residency = it as ResidencyHooks }),
            Watched("property $MODE", { System.getProperty(MODE) }, { a, b -> a == b }, { setMode(it as String?) }),
        )

        /** The fork's first recorded values (one per sandbox class loader). */
        private var first: List<Any?>? = null

        /** `LayerBudgetV19`, or a `LayerBudgetV110` over unknown memory (it gives v1.9's numbers, I32). */
        private fun isV19(b: Any?): Boolean {
            if (b === LayerBudgetV19) return true
            if (b !is LayerBudgetV110) return false
            return try {
                val field = LayerBudgetV110::class.java.getDeclaredField("budget").apply { isAccessible = true }
                val budget: MemoryBudget = field.get(b) as MemoryBudget
                budget.budgets.known.not()
            } catch (e: ReflectiveOperationException) {
                false
            }
        }

        /** Fails (after repairing) when a watched value differs from the fork's first recorded one. */
        internal fun checkNoLeak(description: Description) {
            val now = WATCHED.map { it.read() }
            val record = first ?: run { first = now; return }
            val leaks = WATCHED.indices.filter { !WATCHED[it].same(now[it], record[it]) }
            if (leaks.isEmpty()) return
            val what = leaks.joinToString("; ") { "${WATCHED[it].name} is ${now[it]}, first recorded ${record[it]}" }
            for (i in leaks) WATCHED[i].write(record[i])
            throw AssertionError("EngineRule: an earlier test of this fork left engine state behind before ${description.displayName}: $what (repaired)")
        }
    }
}
