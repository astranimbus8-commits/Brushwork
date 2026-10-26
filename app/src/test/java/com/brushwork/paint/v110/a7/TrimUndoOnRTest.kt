package com.brushwork.paint.v110.a7

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.GovernorPolicies
import com.brushwork.paint.engine.GovernorPolicyV110
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.v19.a7b.FakeHeap
import com.brushwork.paint.v19.a7b.HeapStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A7 step 2 (design 2.4 "Undo trimming", finding 56): [GovernorPolicyV110.trimUndo] at TILES
 * on known memory. Nothing goes while the ledger is at or under 0.90 R; over it, P3 runs first (the
 * residency hears CRITICAL) and only what is still over once every tile byte is left to P3 is
 * dropped from the oldest steps, the newest kept. At full tile RAM the undo history stays whole.
 * The app runs this policy; v1.9's levels and admissions stay ([onPressure] also tells the
 * residency LOW and CRITICAL).
 *
 * The other levels and unknown memory are [TrimUndoOnRFullTest], [TrimUndoOnRClassicTest] and
 * [TrimUndoOnRUnknownMemoryTest] (one [EngineRule] per class).
 */
@RunWith(RobolectricTestRunner::class)
class TrimUndoOnRTest {
    private val ledger = TrimLedger()
    private val residency = TrimResidency(ledger)

    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, budgets = KNOWN, residency = residency)

    @After
    fun tearDown() = ledger.restore()

    @Test
    fun theAppRunsIt() {
        assertSame(GovernorPolicyV110, GovernorPolicies.current())
        assertSame(GovernorPolicyV110, MemoryGovernor.policy)
    }

    @Test
    fun atOrUnderNinetyPercentOfRNothingGoes() {
        val history = steps(ledger, 6)
        ledger.nativeAt(TARGET)
        assertTrimmed(0, 0L, GovernorPolicyV110.trimUndo(history))
        assertEquals(6, history.undoCount)
        assertTrue("P3 is not asked for", residency.events.isEmpty())
    }

    @Test
    fun overItOnlyTheRemainderGoesOldestFirst() {
        val history = steps(ledger, 10)
        ledger.add(LedgerCategory.TILES, 300 * MIB)
        ledger.nonTilesAt(TARGET + 50 * MIB)
        // 50 MiB over: three 20 MiB steps (60 MiB) go, the tiles are P3's.
        assertTrimmed(3, 60 * MIB, GovernorPolicyV110.trimUndo(history))
        assertEquals(7, history.undoCount)
        assertEquals("Step 10", history.undoLabel)
        assertEquals(listOf("pressure CRITICAL", "dispose Step 1", "dispose Step 2", "dispose Step 3"), residency.events)
        assertTrue(ledger.nonTiles() <= TARGET)
        assertTrue("no swap at TILES", residency.swaps.isEmpty())
    }

    @Test
    fun atFullTileRamWithCleanLayersTheUndoHistoryStays() {
        val history = steps(ledger, 5)
        ledger.add(LedgerCategory.TRANSIENT, 100 * MIB)
        // The tiles take all the tile RAM: the ledger is at R, over 0.90 R by tile bytes alone.
        val budget = EngineConfig.budget
        ledger.add(LedgerCategory.TILES, budget.tileRam() - budget.ledger.of(LedgerCategory.TILES))
        assertEquals("the tiles hold all the tile RAM", budget.tileRam(), budget.ledger.of(LedgerCategory.TILES))
        assertEquals("the ledger is at R", R, budget.ledger.native)
        assertTrimmed(0, 0L, GovernorPolicyV110.trimUndo(history))
        assertEquals(5, history.undoCount)
        assertEquals("P3 ran", listOf("pressure CRITICAL"), residency.events)
        // v1.9's mixed target would have dropped steps from a heap this full.
        val heap = FakeHeap(512 * MIB, 100 * MIB, native = R)
        assertTrue(heap.used() + heap.nativeUsed() > (heap.max() * GovernorPolicyV19.HEAP_TARGET).toLong())
    }

    @Test
    fun whatP3FreesIsNotDroppedAgain() {
        val history = steps(ledger, 6)
        // P3 makes room for 31 MiB of the 50 over (the residency frees transients here): one step goes, not three.
        residency.onCritical = { ledger.add(LedgerCategory.TRANSIENT, -31 * MIB) }
        ledger.nonTilesAt(TARGET + 50 * MIB)
        assertTrimmed(1, 20 * MIB, GovernorPolicyV110.trimUndo(history))
        assertEquals(5, history.undoCount)
        assertEquals(listOf("pressure CRITICAL", "dispose Step 1"), residency.events)
    }

    @Test
    fun theNewestStepAlwaysStays() {
        val history = steps(ledger, 4)
        ledger.nonTilesAt(R)
        assertTrimmed(3, 60 * MIB, GovernorPolicyV110.trimUndo(history))
        assertEquals(1, history.undoCount)
        assertEquals("Step 4", history.undoLabel)
    }

    @Test
    fun theLevelsStayV19sAndTheResidencyHearsLowAndCritical() {
        val seen = ArrayList<String>()
        fun entry(name: String, priority: Int) = MemoryGovernor.Entry(name, priority, { 0L }) { p -> seen += "$name:$p"; 0L }
        val entries = listOf(
            entry("undo", GovernorPolicyV19.PRIORITY_UNDO), entry("display", GovernorPolicyV19.PRIORITY_DISPLAY),
            entry("below", GovernorPolicyV19.PRIORITY_BELOW), entry("proxy", GovernorPolicyV19.PRIORITY_PROXIES),
        )
        for (p in MemoryPressure.entries) {
            seen.clear()
            residency.events.clear()
            GovernorPolicyV110.onPressure(p, entries)
            val v19 = ArrayList(seen)
            seen.clear()
            GovernorPolicyV19.onPressure(p, entries)
            assertEquals("$p trims what v1.9 trims", seen, v19)
            val heard = p == MemoryPressure.LOW || p == MemoryPressure.CRITICAL
            assertEquals("$p", if (heard) listOf("pressure $p") else emptyList<String>(), residency.events)
        }
        assertEquals(GovernorPolicyV19.admit(1L shl 20, "Merge down"), GovernorPolicyV110.admit(1L shl 20, "Merge down"))
        assertTrue(GovernorPolicyV110.admit(0L, "nothing"))
    }
}

/**
 * v1.10 A7 step 2: [GovernorPolicyV110.trimUndo] at FULL. After P3's eviction it swaps undo-only
 * tiles (`swapUndoTiles`, for the bytes still over 0.90 R), then drops only what the swap could not
 * free.
 */
@RunWith(RobolectricTestRunner::class)
class TrimUndoOnRFullTest {
    private val ledger = TrimLedger()
    private val residency = TrimResidency(ledger)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, budgets = KNOWN, residency = residency, swap = FakeSwapStore())

    @After
    fun tearDown() = ledger.restore()

    /** The swap's target: every byte over 0.90 R, tiles included. */
    private fun swapTarget(overOutsideTiles: Long) = overOutsideTiles + EngineConfig.budget.ledger.of(LedgerCategory.TILES)

    @Test
    fun undoTilesSwapFirstAndNothingIsDroppedWhenThatSuffices() {
        val history = steps(ledger, 6)
        residency.swapFreeing = { 50 * MIB }
        ledger.nonTilesAt(TARGET + 50 * MIB)
        val target = swapTarget(50 * MIB)
        assertTrimmed(0, 0L, GovernorPolicyV110.trimUndo(history))
        assertEquals(6, history.undoCount)
        assertEquals(listOf("pressure CRITICAL", "swap $target"), residency.events)
    }

    @Test
    fun onlyWhatTheSwapCouldNotFreeIsDropped() {
        val history = steps(ledger, 6)
        residency.swapFreeing = { 30 * MIB }
        ledger.nonTilesAt(TARGET + 50 * MIB)
        val target = swapTarget(50 * MIB)
        // 20 MiB remain over: two steps go (the first frees 20, not more than 20).
        assertTrimmed(2, 40 * MIB, GovernorPolicyV110.trimUndo(history))
        assertEquals(4, history.undoCount)
        assertEquals(listOf("pressure CRITICAL", "swap $target", "dispose Step 1", "dispose Step 2"), residency.events)
    }

    @Test
    fun underTheTargetNothingSwaps() {
        val history = steps(ledger, 3)
        ledger.nativeAt(TARGET - MIB)
        assertTrimmed(0, 0L, GovernorPolicyV110.trimUndo(history))
        assertTrue(residency.events.isEmpty())
    }
}

/**
 * v1.10 A7 step 2: [GovernorPolicyV110.trimUndo] at CLASSIC on known memory. Nothing parks and the
 * ledger holds no layer there, so the measure stays v1.9's (the Java heap plus the native heap)
 * against `0.70·J + 0.90·R`: 30 resident phone layers no longer cost the undo history.
 */
@RunWith(RobolectricTestRunner::class)
class TrimUndoOnRClassicTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.CLASSIC, budgets = KNOWN)

    private lateinit var savedHeap: GovernorPolicyV19.Heap

    @Before
    fun setUp() {
        savedHeap = GovernorPolicyV19.heap
    }

    @After
    fun tearDown() {
        GovernorPolicyV19.heap = savedHeap
    }

    private fun history(heap: FakeHeap, n: Int, bytes: Long) =
        UndoManager(Long.MAX_VALUE).also { h -> (1..n).forEach { h.push(HeapStep("Step $it", heap, bytes)) } }

    @Test
    fun thirtyPhoneLayersKeepTheUndoHistory() {
        // 30 layers of 1080 x 2408 (10.4 MB each) and 10 steps of 20 MiB: v1.9 dropped steps here.
        val heap = FakeHeap(J, 100 * MIB, native = 30L * 1080 * 2408 * 4 + 200 * MIB)
        GovernorPolicyV19.heap = heap
        val v19 = GovernorPolicyV19.trimUndo(history(heap, 10, 20 * MIB))
        assertTrue("v1.9 dropped", v19.steps > 0)
        heap.native = 30L * 1080 * 2408 * 4 + 200 * MIB
        val history = history(heap, 10, 20 * MIB)
        assertTrimmed(0, 0L, GovernorPolicyV110.trimUndo(history))
        assertEquals(10, history.undoCount)
    }

    @Test
    fun overSeventyPercentOfJPlusNinetyPercentOfRTheOldestGo() {
        val target = (J * GovernorPolicyV19.HEAP_TARGET).toLong() + TARGET
        val heap = FakeHeap(J, 100 * MIB, native = target - 100 * MIB + 50 * MIB)
        GovernorPolicyV19.heap = heap
        val history = history(heap, 10, 20 * MIB)
        assertTrimmed(3, 60 * MIB, GovernorPolicyV110.trimUndo(history))
        assertEquals("Step 10", history.undoLabel)
        assertEquals(7, history.undoCount)
    }
}

/**
 * v1.10 A7 step 2 (J3, I32): while memory is unknown [GovernorPolicyV110.trimUndo] is v1.9's, the
 * same steps, bytes and history from the same heap, whatever the ledger holds.
 */
@RunWith(RobolectricTestRunner::class)
class TrimUndoOnRUnknownMemoryTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.UNKNOWN))

    private lateinit var savedHeap: GovernorPolicyV19.Heap
    private val ledger = TrimLedger()

    @Before
    fun setUp() {
        savedHeap = GovernorPolicyV19.heap
    }

    @After
    fun tearDown() {
        GovernorPolicyV19.heap = savedHeap
        ledger.restore()
    }

    private fun run(trim: (UndoManager) -> GovernorPolicyV19.UndoTrim, used: Long, native: Long, steps: Int, bytes: Long): List<Any?> {
        val heap = FakeHeap(1000L, used, native = native)
        GovernorPolicyV19.heap = heap
        val history = UndoManager(Long.MAX_VALUE)
        val pushed = (1..steps).map { HeapStep("Step $it", heap, bytes) }
        pushed.forEach { history.push(it) }
        val t = trim(history)
        return listOf(t.steps, t.bytes, history.undoCount, history.undoLabel, pushed.count { it.disposed }, heap.native)
    }

    @Test
    fun itIsV19s() {
        assertFalse(EngineConfig.budget.budgets.known)
        ledger.add(LedgerCategory.TRANSIENT, 4L shl 30)
        for ((used, native, steps, bytes) in listOf(
            listOf(300L, 600L, 20L, 20L), listOf(300L, 400L, 20L, 20L), listOf(150L, 500L, 5L, 100L), listOf(990L, 9L, 9L, 1L),
        )) {
            val v19 = run(GovernorPolicyV19::trimUndo, used, native, steps.toInt(), bytes)
            assertEquals("used $used native $native", v19, run(GovernorPolicyV110::trimUndo, used, native, steps.toInt(), bytes))
        }
        assertEquals(listOf(11, 220L, 9, "Step 20", 11, 380L), run(GovernorPolicyV110::trimUndo, 300L, 600L, 20, 20L))
    }
}

private const val MIB = 1L shl 20
private const val J = 512L shl 20
private const val R = 1_100_000_000L
private val TARGET = (R * GovernorPolicyV110.R_TARGET).toLong()

/** Known memory, J = 512 MiB, R = 1.1 GB. */
private val KNOWN = Budgets(J, R, known = true, stepDown = 0)

private fun assertTrimmed(steps: Int, bytes: Long, t: GovernorPolicyV19.UndoTrim) {
    assertEquals("steps", steps, t.steps)
    assertEquals("bytes", bytes, t.bytes)
}

/** [n] undo steps of 20 MiB each, their bytes in the ledger's UNDO. */
private fun steps(ledger: TrimLedger, n: Int, residency: TrimResidency? = EngineConfig.residency as? TrimResidency): UndoManager {
    val history = UndoManager(Long.MAX_VALUE)
    for (i in 1..n) {
        ledger.add(LedgerCategory.UNDO, 20 * MIB)
        history.push(TrimStep("Step $i", 20 * MIB, ledger, residency))
    }
    return history
}

/** The session ledger, with every change this test made given back by [restore]. */
private class TrimLedger {
    private val made = LongArray(LedgerCategory.entries.size)
    private val ledger get() = EngineConfig.budget.ledger

    fun add(c: LedgerCategory, bytes: Long) {
        ledger.add(c, bytes)
        made[c.ordinal] += bytes
    }

    /** What the ledger holds outside TILES. */
    fun nonTiles(): Long = ledger.native - ledger.of(LedgerCategory.TILES)

    /** Brings what the ledger holds outside TILES to [bytes] with TRANSIENT bytes. */
    fun nonTilesAt(bytes: Long) = add(LedgerCategory.TRANSIENT, bytes - nonTiles())

    /** Brings everything the ledger holds to [bytes] with TRANSIENT bytes. */
    fun nativeAt(bytes: Long) = add(LedgerCategory.TRANSIENT, bytes - ledger.native)

    fun restore() {
        for (c in LedgerCategory.entries) if (made[c.ordinal] != 0L) ledger.sub(c, made[c.ordinal])
        made.fill(0L)
    }
}

/** An undo step holding [bytes] of the ledger's UNDO; disposing it gives them back. */
private class TrimStep(
    override val label: String,
    private val bytes: Long,
    private val ledger: TrimLedger,
    private val residency: TrimResidency?,
) : UndoAction {
    override val byteSize: Long get() = bytes
    override fun undo(c: EditorController) {}
    override fun redo(c: EditorController) {}
    override fun dispose() {
        ledger.add(LedgerCategory.UNDO, -bytes)
        residency?.events?.add("dispose $label")
    }
}

/**
 * A residency that records what it hears. [onCritical] is what P3 frees under CRITICAL;
 * [swapFreeing] is what `swapUndoTiles(target)` frees of the steps' UNDO bytes, given back to [book].
 */
private class TrimResidency(private val book: TrimLedger) : ResidencyHooks {
    val events = ArrayList<String>()
    val swaps = ArrayList<Long>()
    var onCritical: () -> Unit = {}
    var swapFreeing: (Long) -> Long = { 0L }

    override fun activate(layer: Layer) = Unit

    override fun deactivated(layer: Layer) = Unit

    override fun pressure(p: MemoryPressure) {
        events += "pressure $p"
        if (p == MemoryPressure.CRITICAL) onCritical()
    }

    override fun idleTick() = Unit

    override fun swapUndoTiles(targetBytes: Long): Long {
        events += "swap $targetBytes"
        swaps += targetBytes
        val freed = swapFreeing(targetBytes)
        book.add(LedgerCategory.UNDO, -freed)
        return freed
    }
}
