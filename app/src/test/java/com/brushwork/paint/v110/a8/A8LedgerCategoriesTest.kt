package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.SolidTiles
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.model.Document
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.IdentityHashMap

/**
 * v1.10 A8 step 6 (design 2.0 "Rules", 2.4 the ledger, F1a-D10; FULL against a [FakeSwapStore]):
 * the ledger categories A8 counts under. Every resident tile of its own pixels counts once under
 * TILES, and so does each shared solid color's bitmap, whoever holds the tile: a parked store, or
 * an undo map (`UndoOneMapBytesTest`). That is the pool P3 sends out (orders 2 and 3), which A7's
 * `tileRam` and `trimUndo` leave to it. A tile written to the swap leaves TILES at its commit, and
 * an activation gives the layer's tiles back. Nothing A8 does counts under RESIDENT_LAYERS, MASKS,
 * UNDO or any other category: flat bitmaps are admitted and released, never kept (F1a-D10), and
 * the TRANSIENT copies of `LayerBoundsCache` come back (`LayerBoundsOnTilesTest`).
 */
@RunWith(RobolectricTestRunner::class)
class A8LedgerCategoriesTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap)

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun categories(): Map<LedgerCategory, Long> = LedgerCategory.entries.associateWith { EngineConfig.budget.ledger.of(it) }

    /** What TILES holds for [d] beyond [before]: each resident tile of its own pixels once, and one bitmap per solid color added since [colors]. */
    private fun expectedTiles(d: Document, before: Map<LedgerCategory, Long>, colors: Int): Long {
        val seen = Collections.newSetFromMap(IdentityHashMap<Tile, Boolean>())
        var bytes = 0L
        for (l in d.layers) for (t in A8Kit.tilesOf(l)) if (t.solid == null && t.bitmap != null && seen.add(t)) bytes += t.bytes
        return before.getValue(LedgerCategory.TILES) + bytes + (SolidTiles.colors() - colors) * TILE_SIZE.toLong() * TILE_SIZE * 4
    }

    /** Asserts TILES holds what [d]'s tiles hold, and every other category is as in [before]. */
    private fun assertCategories(what: String, d: Document, before: Map<LedgerCategory, Long>, colors: Int) {
        assertEquals("$what: TILES", expectedTiles(d, before, colors), A8Kit.tiles())
        for (c in LedgerCategory.entries) if (c != LedgerCategory.TILES) {
            assertEquals("$what: nothing under $c", before.getValue(c), EngineConfig.budget.ledger.of(c))
        }
    }

    @Test
    fun parkedTilesCountUnderTilesAloneFromTheParkToTheSwapToTheActivation() {
        val before = categories()
        val colors = SolidTiles.colors()
        val d = A8Kit.doc(3, made).also { docs += it }
        val (p, _) = A8Kit.policy(d)
        d.activeLayerIndex = 2
        A8Kit.idle()
        p.idleTick()
        A8Kit.advance(5000)
        p.idleTick()
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, d.layers[0].residency)
        assertEquals(ResidencyState.PARKED, d.layers[1].residency)
        val parked = A8Kit.tiles()
        assertTrue("the parks counted their tiles", parked > before.getValue(LedgerCategory.TILES))
        assertCategories("parked", d, before, colors)

        // P3 with no tile RAM: the tiles go to the swap and leave TILES as their writes commit.
        p.tileRam = { 0L }
        repeat(3) {
            p.idleTick()
            A8Kit.drain()
        }
        assertTrue("written to the swap", d.layers.flatMap { A8Kit.tilesOf(it) }.any { it.state == TileState.SWAPPED })
        assertTrue("fewer TILES bytes once swapped", A8Kit.tiles() < parked)
        assertCategories("swapped", d, before, colors)

        // An activation reads its tiles back and gives them up: the flat bitmap is not in the ledger.
        p.tileRam = { Long.MAX_VALUE }
        d.activeLayerIndex = 0
        A8Kit.idle()
        A8Kit.drain()
        assertEquals(ResidencyState.FLAT, d.layers[0].residency)
        assertCategories("activated", d, before, colors)
    }
}
