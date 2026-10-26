package com.brushwork.paint.v110.a7

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.budget.AndroidMemoryProbe
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.budget.MemoryBudget
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.engine.budget.PixelLedger
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.concurrent.thread

/**
 * v1.10 A7 step 1 (design 2.4, 2.6 and 2.15, J3, I32): the R formula of `Budgets.compute` on the
 * kit's phones (64 and 32 bits, low RAM, both clamps, the safety nets' steps), unknown memory
 * keeping v1.9's numbers, Classic never below v1.9's cap, and `MemoryBudget`'s caps, tile RAM and
 * admission against R.
 *
 * The session of [engine] runs at TILES on the T606 with [spy] as its residency, for the
 * admission's request for room; the formula cases make their own budgets over their own ledgers.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryBudgetFormulaTest {
    private val spy = PressureSpy()

    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = spy)

    private fun compute(preset: MemoryPreset, stepDown: Int = 0, edit: FakeMemoryProbe.() -> Unit = {}): Budgets =
        Budgets.compute(FakeMemoryProbe(preset).apply(edit), stepDown)

    private fun known(r: Long, j: Long = 512 * MIB) = Budgets(j, r, known = true, stepDown = 0)

    @Test
    fun theT606GetsThirtyPercentOfItsMemory() {
        val b = compute(MemoryPreset.T606)
        assertTrue(b.known)
        assertEquals(512 * MIB, b.javaMax)
        assertEquals(0, b.stepDown)
        // base = min(0.30 x 3788 MiB, 1843 MiB - 400 MiB): the total's share is the smaller.
        assertEquals(T606_BASE.toLong(), b.nativeBudget)
        // Section 2.4's estimate: R about 1100 MB.
        assertTrue("R = ${b.nativeBudget}", b.nativeBudget in 1_000_000_000L..1_300_000_000L)
    }

    @Test
    fun littleAvailableMemoryLowersR() {
        // 1200 MiB available at open leaves 800 MiB, below 0.30 x 3788 MiB.
        assertEquals(800 * MIB, compute(MemoryPreset.T606) { availMem = 1200 * MIB }.nativeBudget)
    }

    @Test
    fun theSafetyNetStepsScaleR() {
        val r = compute(MemoryPreset.T606).nativeBudget
        val one = compute(MemoryPreset.T606, stepDown = 1)
        val two = compute(MemoryPreset.T606, stepDown = 2)
        assertEquals(1, one.stepDown)
        assertEquals(2, two.stepDown)
        assertEquals(T606_BASE * 0.8, one.nativeBudget.toDouble(), 1.0)
        assertEquals(T606_BASE * 0.64, two.nativeBudget.toDouble(), 1.0)
        assertTrue(two.nativeBudget < one.nativeBudget && one.nativeBudget < r)
        // Out-of-range steps are clamped to 0..2.
        assertEquals(two, compute(MemoryPreset.T606, stepDown = 7))
        assertEquals(compute(MemoryPreset.T606), compute(MemoryPreset.T606, stepDown = -1))
    }

    @Test
    fun aLowRamPhoneGetsSixTenths() {
        // base = min(0.30 x 1920 MiB, 700 MiB - 400 MiB) = 300 MiB; x 0.6 = 180 MiB, above lo = 0.55 x 256 MiB.
        val b = compute(MemoryPreset.LOW_RAM)
        assertEquals(256 * MIB, b.javaMax)
        assertEquals(300 * MIB * 0.6, b.nativeBudget.toDouble(), 1.0)
    }

    @Test
    fun aThirtyTwoBitBuildGetsSixTenthsUnder700MiB() {
        assertEquals(T606_BASE * 0.6, compute(MemoryPreset.BIT32).nativeBudget.toDouble(), 1.0)
        // On an 8 GB phone the 32-bit build stops at its 700 MiB ceiling.
        val big = compute(MemoryPreset.GB8) { is64Bit = false }
        assertEquals(700 * MIB, big.nativeBudget)
    }

    @Test
    fun theClampsHold() {
        // hi: a 64-bit phone never passes 2 GiB.
        assertEquals(2L shl 30, compute(MemoryPreset.GB8) { totalMem = 16L shl 30; availMem = 10L shl 30 }.nativeBudget)
        // lo: never below v1.9's layer share, 0.55 J.
        assertEquals((512 * MIB * 0.55).toLong(), compute(MemoryPreset.T606) { availMem = 500 * MIB }.nativeBudget)
        assertEquals((512 * MIB * 0.55).toLong(), compute(MemoryPreset.T606) { availMem = 100 * MIB }.nativeBudget)
        // lo above hi (a 32-bit build with a 1.5 GiB heap): lo wins.
        assertEquals((1536 * MIB * 0.55).toLong(), compute(MemoryPreset.BIT32) { javaMax = 1536 * MIB }.nativeBudget)
        // R grows with the phone's memory between the clamps; the 8 GB phone reaches the ceiling.
        val rs = listOf(MemoryPreset.GB3, MemoryPreset.T606, MemoryPreset.GB6).map { compute(it).nativeBudget }
        assertEquals(rs.sorted(), rs)
        assertTrue(rs.toString(), rs.all { it > (512 * MIB * 0.55).toLong() && it < (2L shl 30) })
        assertEquals(2L shl 30, compute(MemoryPreset.GB8).nativeBudget)
    }

    @Test
    fun unknownMemoryKeepsV19() {
        val v19 = Budgets.v19(Runtime.getRuntime().maxMemory())
        assertEquals(v19, compute(MemoryPreset.UNKNOWN))
        assertEquals("totalMem 0 alone is unknown", v19, compute(MemoryPreset.T606) { totalMem = 0L })
        assertEquals("a throwing probe is unknown", v19, compute(MemoryPreset.T606) { snapshotFailure = SecurityException("no") })
        assertEquals("a failing probe is unknown", v19, compute(MemoryPreset.T606) { snapshotFailure = OutOfMemoryError("no") })
        assertFalse(v19.known)
        // A probe that knows the phone but not the heap takes J from the runtime.
        assertEquals(Runtime.getRuntime().maxMemory(), compute(MemoryPreset.T606) { javaMax = 0L }.javaMax)
    }

    @Test
    fun underRobolectricThePhoneProbeKnowsNothing() {
        // Robolectric's MemoryInfo is all zero: every test that opens an editor keeps v1.9's numbers (I32).
        val v19 = Budgets.v19(Runtime.getRuntime().maxMemory())
        assertEquals(0L, AndroidMemoryProbe.snapshot().totalMem)
        assertEquals(v19, Budgets.compute(AndroidMemoryProbe, 0))
        AndroidMemoryProbe.attach(RuntimeEnvironment.getApplication())
        assertEquals(0L, AndroidMemoryProbe.snapshot().totalMem)
        assertEquals(v19, Budgets.compute(AndroidMemoryProbe, 2))
        // M2 reads availMem (0 here) before any allocation, so it allocates nothing.
        assertEquals(0L, AndroidMemoryProbe.probeNative(16 * MIB, 256 * MIB, 1L shl 30))
        assertEquals(0L, AndroidMemoryProbe.probeNative(0L, 0L, 1L shl 30))
    }

    @Test
    fun classicNeverHoldsFewerLayersThanV19() {
        val j = 512 * MIB
        val lo = (j * 0.55).toLong()
        for ((w, h) in SIZES) {
            val l = w.toLong() * h * 4
            for (r in listOf(lo, 512 * MIB, 1_100_000_000L, 2L shl 30)) {
                val classic = LayerBudgetV110.classicMaxLayers(r, j, l)
                assertTrue("${w}x$h at R = $r: $classic", classic >= LayerBudgetV110.v19Cap(j, l))
                assertTrue(classic <= LayerBudget.MAX_LAYERS_GUARD)
            }
        }
        // At R = lo the R term alone is about 6 phone layers; the floor keeps v1.9's 25.
        assertEquals(25, LayerBudgetV110.v19Cap(j, PHONE))
        assertEquals(25, LayerBudgetV110.classicMaxLayers(lo, j, PHONE))
        // At R = 512 MiB the R term (about 30) passes the floor.
        assertEquals(30, LayerBudgetV110.classicMaxLayers(512 * MIB, j, PHONE))
    }

    @Test
    fun classicCountsMatchTheDesignTable() {
        // Section 2.15, R = 1100 MB on the T606: 84 / 50 / 7 / 2.
        val j = 512 * MIB
        val r = 1_100_000_000L
        assertEquals(84, LayerBudgetV110.classicMaxLayers(r, j, PHONE))
        assertEquals(50, LayerBudgetV110.classicMaxLayers(r, j, 2048L * 2048 * 4))
        assertEquals(7, LayerBudgetV110.classicMaxLayers(r, j, 4000L * 5000 * 4))
        assertEquals(2, LayerBudgetV110.classicMaxLayers(r, j, 6000L * 6000 * 4))
        // A tiny canvas stops at the structural guard.
        assertEquals(LayerBudget.MAX_LAYERS_GUARD, LayerBudgetV110.classicMaxLayers(2L shl 30, j, 16L * 16 * 4))
    }

    @Test
    fun undoCapsFollowTheLevel() {
        val r = 1_100_000_000L
        val b = MemoryBudget(known(r), PixelLedger())
        val v19 = MemoryBudget(Budgets.v19(512 * MIB), PixelLedger())
        for (level in EngineLevel.entries) {
            assertEquals("unknown memory at $level: v1.9", 128 * MIB, v19.undoCapFor(level, 8L shl 30))
        }
        assertEquals(128 * MIB, b.undoCapFor(EngineLevel.CLASSIC, 0L))
        assertEquals(r / 8, b.undoCapFor(EngineLevel.TILES, 0L))
        assertEquals(128 * MIB, MemoryBudget(known(512 * MIB), PixelLedger()).undoCapFor(EngineLevel.TILES, 0L))
        assertEquals("FULL without swap: the floor", 128 * MIB, b.undoCapFor(EngineLevel.FULL, 0L))
        assertEquals(3_460_000_000L / 8, b.undoCapFor(EngineLevel.FULL, 3_460_000_000L))
        assertEquals("FULL stops at 1 GiB", 1L shl 30, b.undoCapFor(EngineLevel.FULL, 16L shl 30))
        // The session's cap reads the session's swap store (none here: TILES).
        assertEquals(EngineConfig.budget.undoCapFor(EngineLevel.FULL, 0L), EngineConfig.budget.undoCap(EngineLevel.FULL))
        // v1.9's 48 MiB floor on a small heap.
        assertEquals(48 * MIB, MemoryBudget(Budgets.v19(128 * MIB), PixelLedger()).undoCap(EngineLevel.CLASSIC))
    }

    @Test
    fun cloneSnapshotCapIsAnEighthOfR() {
        assertEquals(1_100_000_000L / 8, MemoryBudget(known(1_100_000_000L), PixelLedger()).cloneSnapshotCap())
        assertEquals(48 * MIB, MemoryBudget(known(256 * MIB), PixelLedger()).cloneSnapshotCap())
        assertEquals("unknown memory: v1.9's J/4", 128 * MIB, MemoryBudget(Budgets.v19(512 * MIB), PixelLedger()).cloneSnapshotCap())
    }

    @Test
    fun tileRamIsRLessWhatIsHeldOutsideTiles() {
        val ledger = PixelLedger()
        val b = MemoryBudget(known(1000 * MIB), ledger)
        assertEquals(1000 * MIB, b.tileRam())
        ledger.add(LedgerCategory.TILES, 300 * MIB)
        assertEquals("tiles are what tile RAM holds", 1000 * MIB, b.tileRam())
        ledger.add(LedgerCategory.RESIDENT_LAYERS, 100 * MIB)
        ledger.add(LedgerCategory.DISPLAY, 50 * MIB)
        ledger.add(LedgerCategory.UNDO, 25 * MIB)
        assertEquals(825 * MIB, b.tileRam())
        ledger.add(LedgerCategory.TRANSIENT, 2000 * MIB)
        assertEquals(0L, b.tileRam())
        assertEquals("unknown memory: R", (512 * MIB * 0.55).toLong(), MemoryBudget(Budgets.v19(512 * MIB), ledger).tileRam())
    }

    @Test
    fun admissionStopsAtR() {
        val ledger = PixelLedger()
        val b = MemoryBudget(known(100 * MIB), ledger)
        assertEquals(100 * MIB, ledger.limit)
        assertTrue(b.admitNative(60 * MIB, NativePurpose.LAYER))
        assertEquals(60 * MIB, ledger.of(LedgerCategory.RESIDENT_LAYERS))
        assertTrue(b.admitNative(40 * MIB, NativePurpose.TRANSIENT))
        assertFalse("R is full", b.admitNative(1L, NativePurpose.TILE))
        b.release(40 * MIB, NativePurpose.TRANSIENT)
        assertTrue(b.admitNative(40 * MIB, NativePurpose.MASK))
        assertEquals(100 * MIB, ledger.native)
    }

    @Test
    fun unknownMemoryAdmitsEverythingAndCounts() {
        val ledger = PixelLedger()
        val b = MemoryBudget(Budgets.v19(512 * MIB), ledger)
        assertEquals(Long.MAX_VALUE, ledger.limit)
        assertTrue(b.admitNative(4L shl 30, NativePurpose.LAYER))
        assertTrue(b.admitNative(4L shl 30, NativePurpose.UNDO))
        assertEquals(8L shl 30, ledger.native)
    }

    @Test
    fun theAdmittingBudgetsRHolds() {
        // EngineConfig makes a new budget over the shared ledger at every open, close and gallery
        // update: the one that admits puts its own R back first.
        val ledger = PixelLedger()
        val session = MemoryBudget(known(100 * MIB), ledger)
        MemoryBudget(known(10 * MIB), ledger)
        assertEquals(10 * MIB, ledger.limit)
        assertTrue(session.admitNative(50 * MIB, NativePurpose.LAYER))
        assertEquals(100 * MIB, ledger.limit)
        MemoryBudget(Budgets.v19(512 * MIB), ledger)
        assertEquals(Long.MAX_VALUE, ledger.limit)
        assertFalse(session.admitNative(51 * MIB, NativePurpose.LAYER))
        assertEquals(100 * MIB, ledger.limit)
    }

    @Test
    fun onMainARefusalAsksTheResidencyForRoomOnce() {
        val b = EngineConfig.budget
        assertTrue(b.budgets.known)
        assertTrue(LayerBudget.current is LayerBudgetV110)
        val r = b.nativeBudget
        val tiles = b.ledger.of(LedgerCategory.TILES)
        b.ledger.add(LedgerCategory.TILES, r - 10 * MIB - b.ledger.native)
        try {
            // The residency parks what it can: here it frees 40 MiB of tiles.
            spy.onPressure = { b.ledger.sub(LedgerCategory.TILES, 40 * MIB) }
            assertTrue(b.admitNative(20 * MIB, NativePurpose.TRANSIENT))
            assertEquals(listOf(MemoryPressure.CRITICAL), spy.calls)
            b.release(20 * MIB, NativePurpose.TRANSIENT)
            // When parking frees nothing the admission is refused after one request.
            spy.calls.clear()
            spy.onPressure = {}
            assertFalse(b.admitNative(100 * MIB, NativePurpose.LAYER))
            assertEquals(listOf(MemoryPressure.CRITICAL), spy.calls)
            // A nested admission (the residency allocating while it parks) never asks again.
            spy.calls.clear()
            var nested: Boolean? = null
            spy.onPressure = { nested = b.admitNative(100 * MIB, NativePurpose.TILE) }
            assertFalse(b.admitNative(100 * MIB, NativePurpose.LAYER))
            assertEquals(false, nested)
            assertEquals(1, spy.calls.size)
        } finally {
            b.ledger.sub(LedgerCategory.TILES, b.ledger.of(LedgerCategory.TILES) - tiles)
        }
    }

    @Test
    fun offMainARefusalIsFinal() {
        val b = EngineConfig.budget
        val r = b.nativeBudget
        val tiles = b.ledger.of(LedgerCategory.TILES)
        b.ledger.add(LedgerCategory.TILES, r - b.ledger.native)
        try {
            spy.onPressure = { b.ledger.sub(LedgerCategory.TILES, r) }
            var admitted: Boolean? = null
            thread { admitted = b.admitNative(1 * MIB, NativePurpose.TRANSIENT) }.join()
            assertEquals(false, admitted)
            assertTrue("off main nothing parks", spy.calls.isEmpty())
        } finally {
            b.ledger.sub(LedgerCategory.TILES, b.ledger.of(LedgerCategory.TILES) - tiles)
        }
    }

    /** A residency that records the pressure the admission asks for and runs [onPressure]. */
    private class PressureSpy : ResidencyHooks {
        val calls = mutableListOf<MemoryPressure>()
        var onPressure: (MemoryPressure) -> Unit = {}

        override fun activate(layer: Layer) = Unit

        override fun deactivated(layer: Layer) = Unit

        override fun pressure(p: MemoryPressure) {
            calls += p
            onPressure(p)
        }

        override fun idleTick() = Unit
    }

    private companion object {
        const val MIB = 1L shl 20

        /** The T606's R before any factor: 0.30 x its 3788 MiB (the smaller of the two bases). */
        const val T606_BASE = 0.30 * (3788 * MIB)

        /** One phone-size layer, 1080 x 2408 (10.4 MB). */
        const val PHONE = 1080L * 2408 * 4

        /** Section 2.15's sizes and a small one. */
        val SIZES = listOf(1080 to 2408, 2048 to 2048, 4000 to 5000, 6000 to 6000, 256 to 256)
    }
}
