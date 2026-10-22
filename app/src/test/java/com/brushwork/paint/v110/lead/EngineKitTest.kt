package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.ExitRecord
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.testing.EngineGolden
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeClock
import com.brushwork.paint.testing.FakeCornerEditor
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeObjectColorEditor
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.ProjectionOracle
import com.brushwork.paint.testing.RandomStacks
import com.brushwork.paint.testing.TiledGoldenKit
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.ui.common.PaintMode
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.16; lead, frozen): the frozen test kit does what its KDoc promises, so the
 * areas can build on it: [RandomStacks] is deterministic and makes every kind of row,
 * [TiledGoldenKit]'s three copies draw the same picture (an engine golden), [ProjectionOracle]
 * is the plain composite of any region, [FakeSwapStore] round-trips a tile and injects its three
 * faults, the probe presets and fakes answer as set, and the fake editors keep their contracts.
 * Runs under [EngineRule] at FULL with the swap store installed.
 */
@RunWith(RobolectricTestRunner::class)
class EngineKitTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), swap = swap)

    private fun noise(seed: Long): Bitmap = F1Pixels.noise(256, 256, seed)

    @Test
    fun randomStacksAreDeterministicAndMakeEveryKind() {
        val a = RandomStacks.document(7L)
        val b = RandomStacks.document(7L)
        assertEquals(a.layers.map { it.name }, b.layers.map { it.name })
        assertEquals(a.layers.map { it.parentId }, b.layers.map { it.parentId })
        F1Pixels.assertBitEqual("seed 7 twice", F1Pixels.flatten(a), F1Pixels.flatten(b))
        val kinds = (1L..16L).flatMap { RandomStacks.kinds(RandomStacks.document(it)) }.toSet()
        assertEquals(RandomStacks.Kind.entries.toSet(), kinds)
        val doc = RandomStacks.document(3L, RandomStacks.Spec(layers = 14))
        assertEquals(14, doc.layers.size)
        // Children sit directly below their folder.
        for ((i, l) in doc.layers.withIndex()) if (l.parentId != 0L) {
            val folder = doc.layers.indexOfFirst { it.id == l.parentId }
            assertTrue("${l.name} below its folder", folder > i)
        }
    }

    @Test
    @Category(EngineGolden::class)
    fun theThreeStoresOfOneDocumentDrawTheSamePicture() {
        for (seed in listOf(11L, 12L, 13L)) {
            val v = TiledGoldenKit.of(seed, RandomStacks.Spec(width = 300, height = 280, layers = 12))
            assertTrue("seed $seed: tiles", TiledGoldenKit.count(v.tiled, TiledGoldenKit.Stores.TILED) > 0)
            assertEquals("seed $seed: no flat store left in the tiled copy", 0, TiledGoldenKit.count(v.tiled, TiledGoldenKit.Stores.FLAT))
            if (RandomStacks.kinds(v.blank).any { it == RandomStacks.Kind.BLANK || it == RandomStacks.Kind.ADJUSTMENT }) {
                assertTrue("seed $seed: blank stores", v.blank.layers.any { it.pixels is BlankPixels })
            }
            TiledGoldenKit.assertSameRender("seed $seed", v)
            v.recycle()
        }
    }

    @Test
    fun theOracleIsThePlainCompositeOfAnyRegion() {
        val doc = RandomStacks.document(21L)
        val whole = F1Pixels.flatten(doc)
        F1Pixels.assertBitEqual("whole", whole, ProjectionOracle.frame(doc))
        val r = Rect(40, 30, 200, 150)
        val part = ProjectionOracle.frame(doc, r)
        F1Pixels.assertBitEqual("region", Bitmap.createBitmap(whole, r.left, r.top, r.width(), r.height()), part)
        ProjectionOracle.assertFrame("assertFrame", doc, part, r)
        val above = EngineConfig.aboveProjection
        ProjectionOracle.withoutProjections { assertFalse(EngineConfig.aboveProjection) }
        assertEquals("switch put back", above, EngineConfig.aboveProjection)
    }

    @Test
    fun theFakeSwapStoreRoundTripsATileAndInjectsItsFaults() {
        assertSame("installed by the rule", swap, EngineConfig.swapStore)
        val src = noise(31L)
        val tile = Tile.of(src.copy(Bitmap.Config.ARGB_8888, true))
        swap.swapOut(tile)
        assertEquals(TileState.SWAPPED, tile.state)
        assertTrue(swap.usedBytes > 0)
        F1Pixels.assertBitEqual("read back", src, tile.bitmapForRead())
        assertEquals(1, swap.reads)
        // CRC corruption.
        val bad = Tile.of(noise(32L))
        swap.write(bad)
        swap.corrupt(bad.slot!!)
        assertThrows(IOException::class.java) { swap.read(bad) }
        // A write failure after n writes, and a latency.
        val failing = FakeSwapStore(failAfterWrites = 1, latencyMs = 1L)
        failing.write(Tile.of(noise(33L)))
        assertThrows(IOException::class.java) { failing.write(Tile.of(noise(34L))) }
        assertEquals(1, failing.writes)
        // Free and capacity.
        val used = swap.usedBytes
        swap.liveSlots.forEach { swap.free(it) }
        assertEquals(0L, swap.usedBytes)
        assertTrue(used > 0 && swap.frees == 2)
        assertFalse(FakeSwapStore(capacityBytes = 10L).canWrite(11L))
    }

    @Test
    fun theProbePresetsAndFakesAnswerAsSet() {
        val t606 = FakeMemoryProbe(MemoryPreset.T606).snapshot()
        assertTrue("about 3.7 GiB", t606.totalMem in (3600L shl 20)..(3900L shl 20))
        assertTrue(t606.is64Bit && !t606.isLowRamDevice)
        assertTrue(FakeMemoryProbe(MemoryPreset.LOW_RAM).snapshot().isLowRamDevice)
        assertFalse(FakeMemoryProbe(MemoryPreset.BIT32).snapshot().is64Bit)
        assertEquals(0L, FakeMemoryProbe(MemoryPreset.UNKNOWN).snapshot().totalMem)
        val p = FakeMemoryProbe(MemoryPreset.GB6).apply { availMem = 123L; nativeReach = 1000L }
        assertEquals(123L, p.snapshot().availMem)
        assertEquals(500L, p.probeNative(10L, 0L, 500L))
        assertEquals(0L, p.probeNative(10L, 200L, 500L))
        p.snapshotFailure = IllegalStateException("probe")
        assertThrows(IllegalStateException::class.java) { p.snapshot() }
        assertFalse("unknown memory keeps v1.9 (J3)", Budgets.compute(FakeMemoryProbe(MemoryPreset.UNKNOWN), 0).known)
        val exits = FakeExitInfo()
        exits.lowMemoryKill(100L)
        exits.records += ExitRecord(200L, lowMemory = false, signaled9 = false, foreground = true)
        assertEquals(listOf(200L, 100L), exits.exits(0L).map { it.atMillis })
        assertEquals(listOf(200L), exits.exits(100L).map { it.atMillis })
        exits.api30 = false
        assertTrue(exits.exits(0L).isEmpty())
        val clock = FakeClock()
        val t0 = clock.nowMillis
        clock.advance(250L)
        assertEquals(t0 + 250L, clock.millis())
        assertEquals(250_000_000L, clock.nanos())
    }

    @Test
    fun theFakeEditorsKeepTheirContracts() {
        val e = FakeObjectColorEditor()
        e.faces[ObjectColorTarget.FILL] = ObjectColorFace.Solid(0xFF112233.toInt())
        e.begin()
        e.preview(ObjectColorTarget.FILL, 0xFF445566.toInt())
        assertEquals(ObjectColorFace.Solid(0xFF445566.toInt()), e.face(ObjectColorTarget.FILL))
        e.setPaintMode(PaintMode.STROKE)
        e.cancel()
        assertEquals("cancel puts the start back", ObjectColorFace.Solid(0xFF112233.toInt()), e.face(ObjectColorTarget.FILL))
        assertEquals(PaintMode.BOTH, e.paintMode)
        e.begin()
        e.setNone(ObjectColorTarget.STROKE)
        e.commit()
        assertEquals(ObjectColorFace.None, e.face(ObjectColorTarget.STROKE))
        assertEquals(listOf("begin", "preview FILL #FF445566", "setPaintMode STROKE", "cancel", "begin", "setNone STROKE", "commit"), e.calls)
        assertThrows(IllegalStateException::class.java) { e.preview(ObjectColorTarget.FILL, 0) }
        e.refusals[ObjectColorTarget.FILL] = "refused"
        assertNotNull(e.unavailable(ObjectColorTarget.FILL))
        val c = FakeCornerEditor(CornerState.MIXED)
        assertTrue(c.setSelectedSharp(true))
        assertEquals(CornerState.SHARP, c.corner)
        assertFalse("already sharp", c.setSelectedSharp(true))
        c.cornerLocked = true
        assertFalse("locked", c.setSelectedSharp(false))
        c.cornerLocked = false
        c.corner = CornerState.ENDS_ONLY
        assertFalse("ends only", c.setSelectedSharp(false))
        assertEquals(1, c.steps)
    }
}
