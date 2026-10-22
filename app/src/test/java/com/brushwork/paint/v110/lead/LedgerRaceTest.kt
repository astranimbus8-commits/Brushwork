package com.brushwork.paint.v110.lead

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.budget.MemoryBudget
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.engine.budget.PixelLedger
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 F1 (design 2.4, finding 51; lead, frozen): [PixelLedger.tryReserve] is a CAS against
 * [PixelLedger.limit] that any thread may call: of three threads each reserving L with the ledger
 * at R - 2L, exactly two succeed and the ledger holds exactly what they reserved; under many
 * threads reserving and releasing, the total never passes R and ends where it started. F1's
 * [MemoryBudget] admits every allocation (the limit stays unlimited) and counts it under the
 * purpose's category until it is released, with v1.9's undo and clone caps.
 */
class LedgerRaceTest {
    @Test
    fun ofThreeReservationsOfLAtRMinus2LExactlyTwoSucceed() {
        val l = 4L shl 20
        val r = 64L shl 20
        repeat(200) { round ->
            val ledger = PixelLedger().apply { limit = r }
            ledger.add(LedgerCategory.RESIDENT_LAYERS, r - 2 * l)
            val start = CyclicBarrier(3)
            val won = AtomicInteger()
            val threads = (0 until 3).map {
                Thread {
                    start.await(5, TimeUnit.SECONDS)
                    if (ledger.tryReserve(LedgerCategory.TILES, l)) won.incrementAndGet()
                }.apply { start() }
            }
            threads.forEach { it.join(10_000) }
            assertEquals("round $round: exactly two of three", 2, won.get())
            assertEquals("round $round: the ledger holds what they reserved", r, ledger.native)
            assertEquals("round $round: counted under their category", 2 * l, ledger.of(LedgerCategory.TILES))
            assertFalse("round $round: full", ledger.tryReserve(LedgerCategory.TILES, 1L))
            assertTrue("round $round: nothing asked always fits", ledger.tryReserve(LedgerCategory.TILES, 0L))
        }
    }

    @Test
    fun manyThreadsNeverPassTheLimitAndReleaseEverything() {
        val ledger = PixelLedger().apply { limit = 10_000L }
        val over = AtomicBoolean(false)
        val start = CyclicBarrier(8)
        val threads = (0 until 8).map { t ->
            Thread {
                start.await(5, TimeUnit.SECONDS)
                val c = LedgerCategory.entries[t % LedgerCategory.entries.size]
                repeat(20_000) { i ->
                    val bytes = 100L + (i * 37 + t * 11) % 900
                    if (ledger.tryReserve(c, bytes)) {
                        if (ledger.native > ledger.limit) over.set(true)
                        ledger.release(c, bytes)
                    }
                }
            }.apply { start() }
        }
        threads.forEach { it.join(60_000) }
        assertFalse("the total never passed the limit", over.get())
        assertEquals(0L, ledger.native)
        for (c in LedgerCategory.entries) assertEquals(c.name, 0L, ledger.of(c))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aReservationIsNeverNegative() {
        PixelLedger().tryReserve(LedgerCategory.TILES, -1L)
    }

    @Test
    fun f1AdmitsEverythingAndCountsItUntilReleased() {
        val ledger = PixelLedger()
        val budget = MemoryBudget(Budgets.v19(512L shl 20), ledger)
        assertEquals("F1: unlimited", Long.MAX_VALUE, ledger.limit)
        val expected = mapOf(
            NativePurpose.LAYER to LedgerCategory.RESIDENT_LAYERS,
            NativePurpose.MASK to LedgerCategory.MASKS,
            NativePurpose.TILE to LedgerCategory.TILES,
            NativePurpose.TRANSIENT to LedgerCategory.TRANSIENT,
            NativePurpose.PROJECTION to LedgerCategory.PROJECTIONS,
            NativePurpose.DISPLAY to LedgerCategory.DISPLAY,
            NativePurpose.UNDO to LedgerCategory.UNDO,
            NativePurpose.ARRAY to LedgerCategory.ARRAYS,
            NativePurpose.THUMBNAIL to LedgerCategory.THUMBNAILS,
        )
        assertEquals("every purpose has a category", NativePurpose.entries.toSet(), expected.keys)
        for ((purpose, category) in expected) {
            assertTrue(purpose.name, budget.admitNative(1L shl 40, purpose))
            assertEquals(purpose.name, 1L shl 40, ledger.of(category))
            budget.release(1L shl 40, purpose)
            assertEquals(purpose.name, 0L, ledger.of(category))
        }
        assertEquals(0L, ledger.native)
    }

    @Test
    fun f1CapsAreV19s() {
        for (heap in listOf(64L shl 20, 192L shl 20, 256L shl 20, 512L shl 20, 1L shl 30)) {
            val budget = MemoryBudget(Budgets.v19(heap), PixelLedger())
            val v19 = maxOf(48L shl 20, heap / 4)
            for (level in EngineLevel.entries) assertEquals("undo cap, $level, heap $heap", v19, budget.undoCap(level))
            assertEquals("clone source cap, heap $heap", v19, budget.cloneSnapshotCap())
            assertEquals("R = 0.55 J", (heap * 0.55).toLong(), budget.nativeBudget)
            assertEquals("tile RAM = R", budget.nativeBudget, budget.tileRam())
            assertFalse("v1.9 numbers: memory unknown", budget.budgets.known)
        }
    }
}
