package com.brushwork.paint.v110.a10

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

/**
 * v1.10 A10 (design 2.8, step 3): the swap file is allocated in 4 KiB pages within slabs (1 MiB
 * here, 64 MiB in the app), and what is freed is used again before the file grows: the lowest run
 * of free pages long enough takes a write, a full slab opens the next one (`slab-001.bin`), a freed
 * slab takes writes again, and write and free cycles never add slabs. A slot takes whole pages;
 * `usedBytes` counts them; a second free of a slot changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
class SwapSlabReuseTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL)

    private val slab = 1L shl 20
    private val pagesPerSlab = (slab / SlabSwapStore.PAGE).toInt()

    @After
    fun tearDown() = SwapKit.cleanUp()

    private fun written(store: SlabSwapStore, seed: Long, side: Int = 64): Tile = SwapKit.noiseTile(seed, side, side).first.also {
        store.write(it)
        SwapKit.commit(it)
    }

    @Test
    fun freedPagesAreTakenLowestFirst() {
        val store = SwapKit.store(slabBytes = slab)
        val a = written(store, 1)
        val b = written(store, 2)
        val c = written(store, 3)
        val sa = SwapKit.slot(a)!!
        val sb = SwapKit.slot(b)!!
        val sc = SwapKit.slot(c)!!
        assertEquals("the first slot starts the slab", 0L, sa.offset)
        assertEquals("slots follow each other in whole pages", SwapKit.pageBytes(sa.length), sb.offset)
        assertEquals(sb.offset + SwapKit.pageBytes(sb.length), sc.offset)
        assertTrue("4 KiB pages", listOf(sa, sb, sc).all { it.offset % SlabSwapStore.PAGE == 0L })
        val used = store.usedBytes
        assertEquals(listOf(sa, sb, sc).sumOf { SwapKit.pageBytes(it.length) }, used)

        SwapKit.free(store, b)
        assertEquals("a free returns its pages", used - SwapKit.pageBytes(sb.length), store.usedBytes)
        val d = written(store, 4)
        val sd = SwapKit.slot(d)!!
        assertEquals("the hole takes the next write of its size", sb.offset, sd.offset)
        assertEquals(0, sd.slab)
        assertEquals(used, store.usedBytes)
        // A larger tile does not fit the hole left by a small one: it goes after the last slot.
        SwapKit.free(store, d)
        val big = written(store, 5, side = 128)
        assertEquals(sc.offset + SwapKit.pageBytes(sc.length), SwapKit.slot(big)!!.offset)
        val small = written(store, 6, side = 32)
        assertEquals("a smaller one does", sb.offset, SwapKit.slot(small)!!.offset)
        assertEquals(1, store.slabCount)
    }

    @Test
    fun aFullSlabOpensTheNextAndAFreedSlabIsUsedAgain() {
        val store = SwapKit.store(slabBytes = slab)
        val first = ArrayList<Tile>()
        var t = written(store, 0)
        while (SwapKit.slot(t)!!.slab == 0) {
            first += t
            t = written(store, first.size.toLong())
        }
        assertEquals("the write that did not fit opened slab 1", 1, SwapKit.slot(t)!!.slab)
        assertEquals(0L, SwapKit.slot(t)!!.offset)
        assertEquals(2, store.slabCount)
        assertEquals(listOf("slab-000.bin", "slab-001.bin"), store.dir.list()!!.sorted())
        val pagesHeld = first.sumOf { SwapKit.pageBytes(SwapKit.slot(it)!!.length) } / SlabSwapStore.PAGE
        assertTrue("slab 0 was full: $pagesHeld of $pagesPerSlab pages", pagesHeld <= pagesPerSlab && pagesPerSlab - pagesHeld < SwapKit.pageBytes(SwapKit.slot(t)!!.length) / SlabSwapStore.PAGE)

        for (x in first) SwapKit.free(store, x)
        val again = written(store, 99)
        assertEquals("the lowest slab with room takes it", 0, SwapKit.slot(again)!!.slab)
        assertEquals(0L, SwapKit.slot(again)!!.offset)
        assertEquals("no new slab", 2, store.slabCount)
        F1Pixels.assertBitEqual("slab 1 still reads", SwapKit.noiseTile(first.size.toLong(), 64, 64).second, store.read(t))
    }

    @Test
    fun writeAndFreeCyclesNeverAddSlabs() {
        val store = SwapKit.store(slabBytes = slab)
        val rnd = Random(5)
        val live = ArrayList<Tile>()
        repeat(1_000) {
            if (live.size < 6 || rnd.nextBoolean()) {
                live += SwapKit.randomTile(rnd, 64).first.also { tile -> store.write(tile); SwapKit.commit(tile) }
            }
            if (live.size > 10 || (live.isNotEmpty() && rnd.nextInt(3) == 0)) SwapKit.free(store, live.removeAt(rnd.nextInt(live.size)))
        }
        // At most 11 tiles of at most 64 x 64 px (5 pages each) live at once: one slab of 256 pages always has room.
        assertEquals("one slab served every cycle", 1, store.slabCount)
        for (x in live.toList()) SwapKit.free(store, x)
        assertEquals("every page back", 0L, store.usedBytes)
    }

    @Test
    fun aSecondFreeOfASlotChangesNothing() {
        val store = SwapKit.store(slabBytes = slab)
        val a = written(store, 1)
        val b = written(store, 2)
        val sa = SwapKit.slot(a)!!
        val sb = SwapKit.slot(b)!!
        store.free(sa)
        val after = store.usedBytes
        store.free(sa)
        assertEquals(after, store.usedBytes)
        assertEquals(SwapKit.pageBytes(sb.length), store.usedBytes)
        // A slot outside every slab is ignored too.
        store.free(sa.copy(slab = 7))
        store.free(sa.copy(offset = slab * 2))
        assertEquals(after, store.usedBytes)
        F1Pixels.assertBitEqual("b reads", SwapKit.noiseTile(2).second, store.read(b))
    }
}
