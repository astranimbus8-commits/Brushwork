package com.brushwork.paint.v110.a11

import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.storage.ClassicCapacityException
import com.brushwork.paint.storage.ClassicOpen
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.LayerEntries
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.common.EngineLabels110
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * v1.10 (A11, J16, UQ16, design 2.9): Classic layer storage decides whether it opens a format-4
 * artwork on a capacity that depends on the phone alone (`totalMem` and J, never `availMem` or the
 * step-down), so the same artwork opens in every session on one phone: on the T606 an artwork of
 * 101 painted layers (256 x 256, format 4) opens with plenty of free memory and again with little
 * (`availMem` 450 MiB, the session's R all but spent: the load admits against `Rstable`). On a
 * 1 GB Android Go phone (capacity 100 at 256 x 256) the same artwork is refused before anything is
 * decoded, with `NEEDS_FULL_STORAGE`, and the editor offers "Open with Full storage", which opens
 * it once at TILES or FULL; 100 painted layers open there, and v1.9's formats always open. The
 * refusal adds `NEEDS_FULL_NO_MEMORY` and offers nothing only when the check chose Classic for low
 * memory. Unknown memory keeps v1.9's cap.
 */
@RunWith(RobolectricTestRunner::class)
class ClassicRefusesBigArtworkTest {
    private val probe = FakeMemoryProbe(MemoryPreset.T606)

    @get:Rule
    val engine = EngineRule(EngineLevel.CLASSIC, memory = probe)

    private val side = 256

    /** A 1 GB Android Go phone: Rstable = max(0.55 J, 0.30 x 1 GiB x 0.6), so the capacity at 256 x 256 is v1.9's 100. */
    private fun goPhone() {
        probe.totalMem = 1024 * MIB
        probe.availMem = 500 * MIB
        probe.isLowRamDevice = true
        probe.javaMax = 256 * MIB
    }

    /** An 8 x 8 opaque patch of its own colour and place for layer [i] (pixel-file rows). */
    private fun patch(i: Int): (Int, Int, ByteArray) -> Unit {
        val px = A11Kit.opaqueBytes(0x102030 + i * 0x010101)
        val x0 = (i * 13) % (side - 8)
        val y0 = (i * 29) % (side - 8)
        return { y, n, into ->
            into.fill(0)
            for (r in 0 until n) {
                if (y + r !in y0 until y0 + 8) continue
                for (x in x0 until x0 + 8) System.arraycopy(px, 0, into, (r * side + x) * 4, 4)
            }
        }
    }

    /** [painted] painted layers and a blank one on top (the active one), as format [format]. */
    private fun write(id: String, painted: Int, format: Int = ClassicOpen.BIG_FORMAT) {
        A11Kit.freshRepo()
        val files = List(painted + 1) { i ->
            if (i == painted) { f: File -> LayerCodec.writeBlank(f, side, side) } else { f: File -> A11Kit.writeRows(f, side, side, patch(i)) }
        }
        A11Kit.writeProject(id, side, side, files, active = painted, formatVersion = format)
    }

    /** Opens [id] and checks its painted layers came back FLAT (Classic), the blank active one too. */
    private fun opens(id: String, painted: Int) {
        val doc = runBlocking { A11Kit.repo().load(id) }
        try {
            assertEquals(painted + 1, doc.layers.size)
            assertEquals("painted layers and the active one, FLAT", painted + 1, doc.layers.count { it.pixels is FlatPixels })
        } finally {
            A11Kit.dispose(doc)
        }
    }

    @Test
    fun theSameArtworkOpensInEverySessionWhateverTheFreeMemory() {
        write("fits", PAINTED)
        val first = ClassicOpen.capacity(side, side)
        assertTrue("101 painted layers fit the T606 at 256 x 256", first.layers >= PAINTED)
        opens("fits", PAINTED)

        // Reopened with little free memory and a session R all but spent (as A7's R would be): room
        // for the load's band and half a layer, so no layer could be admitted against it.
        probe.availMem = 450 * MIB
        val ledger = EngineConfig.budget.ledger
        val limit = ledger.limit
        ledger.limit = ledger.native + side * side * 4L + side * side * 2L
        try {
            val second = ClassicOpen.capacity(side, side)
            assertEquals("the capacity ignores availMem", first.layers, second.layers)
            assertEquals("Rstable ignores availMem", first.room, second.room)
            opens("fits", PAINTED)
        } finally {
            ledger.limit = limit
        }
    }

    @Test
    fun theCapacityFollowsTheFormula() {
        // T606: Rstable = 0.30 x 3788 MiB = 1,191,591,936 B. At 1080 x 2408 (L = 10,402,560 B):
        // (Rstable - 3.8 L - 184 MB) / L = 93.06, above v1.9's 25 (design 2.15 estimates 84 at R = 1100 MB).
        assertEquals(93, ClassicOpen.capacity(1080, 2408).layers)
        // 4000 x 5000 (L = 80 MB): 8.79; v1.9's cap is 2.
        assertEquals(8, ClassicOpen.capacity(4000, 5000).layers)
        // availMem and the step-down are not inputs; a lower totalMem is.
        probe.availMem = 300 * MIB
        assertEquals(93, ClassicOpen.capacity(1080, 2408).layers)
        goPhone()
        assertEquals("the Go phone at 256 x 256: v1.9's floor", 100, ClassicOpen.capacity(side, side).layers)
        // Unknown memory (J3): v1.9's cap exactly, at this heap (v1.9's `maxMemory`, as A7's
        // `Budgets.compute` takes J when memory is unknown; the session's budgets were computed
        // from the known T606 probe, whose J differs from this JVM's heap).
        probe.totalMem = 0L
        val phone = Document("u", "u", 1080, 2408)
        assertEquals(LayerBudgetV19.maxLayers(phone), ClassicOpen.capacity(1080, 2408).layers)
        assertEquals((Runtime.getRuntime().maxMemory() * 0.55).toLong(), ClassicOpen.capacity(1080, 2408).room)
    }

    @Test
    fun anArtworkOverTheCapacityIsRefusedBeforeAnythingIsDecoded() {
        write("big", PAINTED)
        goPhone()
        val ledger = EngineConfig.budget.ledger
        val before = ledger.native
        LayerEntries.loadScratchBytes = 0
        val e = assertThrows(ClassicCapacityException::class.java) { runBlocking { A11Kit.repo().load("big") } }
        assertEquals(EngineLabels110.NEEDS_FULL_STORAGE, e.message)
        assertEquals(PAINTED, e.painted)
        assertEquals(100, e.capacity)
        assertEquals("no band was made", 0, LayerEntries.loadScratchBytes)
        assertEquals("nothing is held", before, ledger.native)
    }

    @Test
    fun theCapacityItselfOpensAndVersion19FormatsAlwaysOpen() {
        goPhone()
        write("edge", 100)
        opens("edge", 100)
        // v1.9's format (an over-limit artwork opens and only adding stops, as in v1.9).
        write("old", PAINTED, format = 1)
        opens("old", PAINTED)
    }

    @Test
    fun theEditorOffersOpenWithFullStorageWhichOpensItOnce() {
        write("offer", PAINTED)
        goPhone()
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val mode = app.settings.layerStorageMode
        val session = EditorSession(app, "offer")
        pumpUntil("the refusal") { session.state is EditorSession.State.Failed }
        assertEquals(EngineLabels110.NEEDS_FULL_STORAGE, (session.state as EditorSession.State.Failed).message)
        assertTrue("no check: Classic's reason is not memory", session.offersFullStorage)
        // Open with Full storage: once at TILES or FULL, whatever -PengineMode says (the rule puts it back).
        System.clearProperty(EngineRule.MODE)
        session.openWithFullStorage()
        assertFalse(session.offersFullStorage)
        assertEquals("the setting is unchanged", mode, app.settings.layerStorageMode)
        pumpUntil("the editor") { session.state is EditorSession.State.Ready }
        assertTrue("opened above Classic", EngineConfig.level != EngineLevel.CLASSIC)
        val doc = (session.state as EditorSession.State.Ready).controller.doc
        assertEquals(PAINTED + 1, doc.layers.size)
        assertTrue("painted layers are tiles", doc.layers.count { it.pixels !is FlatPixels && it.pixels !is BlankPixels } >= PAINTED - 1)
        var closed = false
        session.close { closed = true }
        pumpUntil("the session to close") { closed }
    }

    @Test
    fun onlyLowMemoryRefusesWithoutTheOffer() {
        val key = "T606/3840MiB"
        fun check(reason: String, level: EngineLevel = EngineLevel.CLASSIC, deviceKey: String = key) = EngineCheckResult(
            appVersionCode = 110, deviceKey = deviceKey, ranAtMillis = 1L, durationMs = 1L, level = level, reason = reason,
            totalMem = 3788 * MIB, availMem = 300 * MIB, threshold = 216 * MIB, lowRam = false, is64Bit = true,
            javaMax = 512 * MIB, nativeProbeBytes = 0L, deflateMBs = 1f, inflateMBs = 1f, ratio = 4f, writeMBs = 1f,
            readMBs = 1f, freeBytes = 8L shl 30, composite64Ms = 1f,
        )
        assertFalse("no check yet", ClassicOpen.refusedForMemory(null, LayerStorageMode.AUTOMATIC, key))
        assertTrue("the check chose Classic for memory", ClassicOpen.refusedForMemory(check(CheckReasons.LOW_MEMORY), LayerStorageMode.AUTOMATIC, key))
        assertFalse("slow drawing", ClassicOpen.refusedForMemory(check(CheckReasons.SLOW_COMPOSITING), LayerStorageMode.AUTOMATIC, key))
        assertFalse("slow storage", ClassicOpen.refusedForMemory(check(CheckReasons.SLOW_STORAGE), LayerStorageMode.AUTOMATIC, key))
        assertFalse("the user chose Classic", ClassicOpen.refusedForMemory(check(CheckReasons.LOW_MEMORY), LayerStorageMode.CLASSIC, key))
        assertTrue("another phone's check", ClassicOpen.refusedForMemory(check(CheckReasons.LOW_MEMORY, deviceKey = "other"), LayerStorageMode.CLASSIC, key))
        assertEquals(EngineLabels110.NEEDS_FULL_STORAGE, ClassicOpen.refusalText(forMemory = false))
        assertEquals(EngineLabels110.NEEDS_FULL_STORAGE + " " + EngineLabels110.NEEDS_FULL_NO_MEMORY, ClassicOpen.refusalText(forMemory = true))
        assertEquals(EngineLevel.FULL, ClassicOpen.onceLevel(2L shl 30))
        assertEquals(EngineLevel.TILES, ClassicOpen.onceLevel((2L shl 30) - 1))
    }

    private fun pumpUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 2000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private companion object {
        const val MIB = 1L shl 20
        const val PAINTED = 101
    }
}
