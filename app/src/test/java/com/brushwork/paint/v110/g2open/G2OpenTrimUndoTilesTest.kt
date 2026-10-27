package com.brushwork.paint.v110.g2open

import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.GovernorPolicyV110
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.v110.a8.A8Kit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 Gate 2 (lane open, O6's last clause, LEAD RULING G2-O-c): at TILES (no swap store) the
 * bytes of the tiles only undo steps hold count toward the memory-pressure trim. Seven strokes
 * over the whole of a painted 512 x 512 layer leave seven steps of 1 MiB of tiles each, held by
 * nothing else; with the ledger 2.5 MiB over 0.90 R beyond the tiles the undo history does not
 * hold alone, the editor's CRITICAL trim drops the three
 * oldest (3 MiB, the first sum over 2.5 MiB), says so, and the TILES ledger falls by exactly their
 * bytes; the layer keeps its pixels and the newer steps still undo. (Before the ruling the tile
 * bytes were left to P3 and nothing went.) FULL keeps `swapUndoTiles` first:
 * `TrimUndoOnRFullTest`. The residency parks and packs nothing ([Quiet]), so the trim is alone.
 */
@RunWith(RobolectricTestRunner::class)
class G2OpenTrimUndoTilesTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = Quiet())

    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null
    private var reserved = 0L

    @After
    fun tearDown() {
        if (reserved > 0L) EngineConfig.budget.ledger.sub(LedgerCategory.TRANSIENT, reserved)
        controller?.dispose()
        scope?.cancel()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun underPressureTheOldestStepsThatHoldTilesGoAndTheTilesLedgerFallsByTheirBytes() {
        val doc = Smoke.document(SIZE, SIZE, layers = 1)
        doc.layers[0].bitmap.eraseColor(0xFF406020.toInt())
        val s = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, s).also { controller = it }
        idle()
        val layer = doc.layers[0]
        assertEquals(ResidencyState.FLAT, layer.residency)
        val history = c.undoManager
        val colors = List(STROKES) { 0xFF000000.toInt() or (0x203040 * (it + 1)) }
        for (color in colors) A8Kit.paint(c, layer, Rect(0, 0, SIZE, SIZE), color)
        idle()
        val sizes = (0 until history.undoCount).map { history.undoAt(it)!!.byteSize }
        assertEquals("seven steps of 1 MiB of tiles", List(STROKES) { MIB }, sizes)
        assertEquals("only the steps hold their tiles", sizes.sum(), GovernorPolicyV110.undoOnlyBytes(history))

        val ledger = EngineConfig.budget.ledger
        val target = (EngineConfig.budget.nativeBudget * GovernorPolicyV110.R_TARGET).toLong()
        // The ledger is the process' own: tiles other tests' documents still hold count as P3's
        // (not the undo history's), so the reservation leaves OVER beyond them.
        val otherTiles = ledger.of(LedgerCategory.TILES) - sizes.sum()
        reserved = target + OVER + otherTiles - ledger.native
        assertTrue("room to reserve", reserved > 0L)
        ledger.add(LedgerCategory.TRANSIENT, reserved)
        val tilesBefore = ledger.of(LedgerCategory.TILES)
        val after = A8Kit.pixelsOf(layer).first
        c.message = null

        val freed = c.trimUndoForMemory(MemoryPressure.CRITICAL)
        idle()
        assertEquals("the three oldest steps went (the first sum over 2.5 MiB)", 3 * MIB, freed)
        assertEquals(STROKES - 3, history.undoCount)
        assertEquals("the TILES ledger fell by their bytes", tilesBefore - 3 * MIB, ledger.of(LedgerCategory.TILES))
        assertEquals("the trim is said", StabilityLabels19.UNDO_TRIMMED, c.message)
        assertTrue("the layer keeps its pixels", after.contentEquals(A8Kit.pixelsOf(layer).first))

        // The newer steps still undo: back to the 4th stroke's color.
        repeat(STROKES - 3) { c.undo(); idle() }
        assertEquals(colors[2], layer.bitmap.getPixel(SIZE / 2, SIZE / 2))
        repeat(STROKES - 3) { c.redo(); idle() }
        assertEquals(colors.last(), layer.bitmap.getPixel(SIZE / 2, SIZE / 2))
    }

    /** A residency that parks, packs and swaps nothing (the editor then installs no policy of its own). */
    private class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit
        override fun deactivated(layer: Layer) = Unit
        override fun pressure(p: MemoryPressure) = Unit
        override fun idleTick() = Unit
    }

    private companion object {
        const val SIZE = 512
        const val STROKES = 7
        const val MIB = 1L shl 20
        const val OVER = (5L shl 20) / 2
    }
}
