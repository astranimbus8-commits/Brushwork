package com.brushwork.paint.v110.g2open

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.storage.CorruptProjectException
import com.brushwork.paint.storage.LayerEntries
import com.brushwork.paint.storage.LayerEntryDto
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.v110.a11.A11Kit
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.10 Gate 2 (lane open, O1; design 4.4 A11 step 6): the open decodes on 4 threads at FULL
 * (T606 memory; the test counts the 4 decoder threads it starts) and still makes the layers
 * exactly as the one-band load: the entry order with
 * slow painted entries between fast blank ones and a folder, the active entry FLAT, the others
 * tiled or BLANK, every byte the same; "Opening layer n of m" hears every entry once in order. A
 * damaged later entry, a cancelled open and a decoder that cannot start release every store the
 * decoders built and leave no decoder thread behind. The decoder count follows the budget (R >= 2·d·L: one when R < 4·L, two
 * below 6·L, three below 8·L), one at Classic and never more than the pixel entries.
 */
@RunWith(RobolectricTestRunner::class)
class G2OpenParallelDecodeTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606))

    private val w = 300
    private val h = 520

    @After
    fun decoders() {
        LayerEntries.maxDecoders = LayerEntries.DECODERS
        LayerEntries.startDecoder = { it.start() }
    }

    /**
     * Project [id] of 12 entries (bottom first): painted layers between blank ones and an empty
     * folder (entry 6), the painted entry 10 active. Returns its folder.
     */
    private fun project(id: String): File {
        A11Kit.freshRepo()
        val files = List(12) { i ->
            when (i) {
                6 -> null
                1, 4, 8 -> { f: File -> A11Kit.writeRows(f, w, h) { _, n, into -> java.util.Arrays.fill(into, 0, n * w * 4, 0) } }
                else -> { f: File -> A11Kit.writeRows(f, w, h, A11Kit.PaintedRows(w, h, seed = 300L + i)::fill) }
            }
        }
        return A11Kit.writeProject(id, w, h, files, active = 10)
    }

    private fun alive(): List<String> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("Brushwork open decoder") }.map { it.name }

    private fun load(id: String, progress: ((Int, Int) -> Unit)? = null): Document = runBlocking { A11Kit.repo().load(id, progress) }

    @Test
    fun fourDecodersMakeTheLayersInEntryOrderWithTheSameBytes() {
        project("order")
        val dto = ProjectFormat.read(A11Kit.dirOf("order"))
        assertEquals("the T606 budget admits 4 decoders here", 4, LayerEntries.decodersFor(dto, w, h, tiled = true))
        val tilesBefore = EngineConfig.budget.ledger.of(LedgerCategory.TILES)
        // Gate 2 review: count the decoder threads each open starts, so a load that falls back to
        // one band (no ParallelDecode, or one thread) fails here and not only in the local timing probe.
        val started = AtomicInteger()
        LayerEntries.startDecoder = { t ->
            started.incrementAndGet()
            t.start()
        }

        LayerEntries.maxDecoders = 1
        val one = load("order")
        val startedByOne = started.getAndSet(0)
        LayerEntries.maxDecoders = LayerEntries.DECODERS
        val heard = ArrayList<Pair<Int, Int>>()
        val two = load("order") { n, m -> synchronized(heard) { heard += n to m } }
        try {
            assertEquals("the one-band load starts no decoder thread", 0, startedByOne)
            assertEquals("the open ran on 4 decoder threads", 4, started.get())
            assertEquals("every entry once, in order", (1..12).map { it to 12 }, heard)
            assertEquals("the entry order", (1L..12L).toList(), two.layers.map { it.id })
            for (i in 0 until 12) {
                val l = two.layers[i]
                when (i) {
                    6 -> assertTrue("entry 6 is the folder", l.isFolder && l.pixels === FolderPixels)
                    10 -> assertTrue("the active entry is FLAT", l.pixels is FlatPixels)
                    1, 4, 8 -> assertTrue("blank entry $i is BLANK", l.pixels is BlankPixels)
                    else -> assertTrue("painted entry $i is tiled", l.pixels is TiledPixels)
                }
                assertEquals("entry $i: the same store kind", one.layers[i].pixels.javaClass, l.pixels.javaClass)
                if (!l.isFolder) assertArrayEquals("entry $i: the same bytes", A11Kit.bytesOf(one.layers[i].pixels, w, h), A11Kit.bytesOf(l.pixels, w, h))
            }
            assertEquals("no decoder is left", emptyList<String>(), alive())
        } finally {
            A11Kit.dispose(one)
            A11Kit.dispose(two)
        }
        assertEquals("the tile ledger is back", tilesBefore, EngineConfig.budget.ledger.of(LedgerCategory.TILES))
    }

    @Test
    fun aDamagedLaterEntryReleasesWhatTheDecodersBuilt() {
        val dir = project("damaged")
        val file = File(dir, ProjectFormat.layerFile(10L, 1))
        file.writeBytes(file.readBytes().copyOf((file.length() / 2).toInt()))
        val tilesBefore = EngineConfig.budget.ledger.of(LedgerCategory.TILES)
        val nativeBefore = EngineConfig.budget.ledger.native
        val heard = AtomicInteger()
        try {
            load("damaged") { n, _ -> heard.set(n) }
            fail("a truncated layer file opens")
        } catch (e: CorruptProjectException) {
            assertTrue(e.message, e.message!!.contains("Layer \"Layer 10\" is damaged or missing"))
        }
        assertEquals("the entries before it were made, in order", 9, heard.get())
        assertEquals("no decoder is left", emptyList<String>(), alive())
        assertEquals("every tile built is released", tilesBefore, EngineConfig.budget.ledger.of(LedgerCategory.TILES))
        assertEquals("every reservation (the bands) is back", nativeBefore, EngineConfig.budget.ledger.native)
    }

    @Test
    fun aCancelledOpenStopsBetweenLayersAndReleasesEverything() {
        project("cancel")
        val tilesBefore = EngineConfig.budget.ledger.of(LedgerCategory.TILES)
        val nativeBefore = EngineConfig.budget.ledger.native
        val heard = AtomicInteger()
        lateinit var job: Job
        runBlocking {
            job = launch {
                A11Kit.repo().load("cancel") { n, _ ->
                    heard.set(n)
                    if (n == 3) job.cancel()
                }
                fail("a cancelled open returned a document")
            }
            job.join()
        }
        assertTrue("the open was cancelled", job.isCancelled)
        assertEquals("it stopped before the 4th layer", 3, heard.get())
        assertEquals("no decoder is left", emptyList<String>(), alive())
        assertEquals("every tile built is released", tilesBefore, EngineConfig.budget.ledger.of(LedgerCategory.TILES))
        assertEquals("every reservation (the bands) is back", nativeBefore, EngineConfig.budget.ledger.native)
    }

    /**
     * Gate 2 review: the 3rd decoder cannot start (a phone out of threads: `Thread.start` throws
     * OutOfMemoryError, which the session says as "Not enough memory to open this artwork"). The
     * open fails with it, the 2 decoders already running are stopped and joined, and everything
     * they built is released.
     */
    @Test
    fun aDecoderThatCannotStartLeavesNoDecoderAndNoStoreBehind() {
        project("nothread")
        val tilesBefore = EngineConfig.budget.ledger.of(LedgerCategory.TILES)
        val nativeBefore = EngineConfig.budget.ledger.native
        val started = AtomicInteger()
        val heard = AtomicInteger()
        LayerEntries.startDecoder = { t ->
            if (started.incrementAndGet() == 3) {
                // Only once the running decoders built tiles (entry 0 is painted), so the release is real.
                val until = System.currentTimeMillis() + 10_000L
                while (EngineConfig.budget.ledger.of(LedgerCategory.TILES) <= tilesBefore) {
                    if (System.currentTimeMillis() > until) throw AssertionError("the first decoders built no tile in 10 s")
                    Thread.sleep(1)
                }
                throw OutOfMemoryError("pthread_create failed")
            }
            t.start()
        }
        try {
            load("nothread") { n, _ -> heard.set(n) }
            fail("an open whose decoders could not start returned a document")
        } catch (e: OutOfMemoryError) {
            assertEquals("pthread_create failed", e.message)
        }
        assertEquals("the 3rd start failed", 3, started.get())
        assertEquals("no layer was made", 0, heard.get())
        assertEquals("no decoder is left", emptyList<String>(), alive())
        assertEquals("every tile built is released", tilesBefore, EngineConfig.budget.ledger.of(LedgerCategory.TILES))
        assertEquals("every reservation (the bands) is back", nativeBefore, EngineConfig.budget.ledger.native)
    }

    @Test
    fun theDecoderCountFollowsTheBudget() {
        fun dto(cw: Int, ch: Int, pixelEntries: Int): ProjectFileDto {
            val entries = List(pixelEntries) { i ->
                val id = i + 1L
                LayerEntryDto(id, "Layer $id", ProjectFormat.defaultProps("Layer $id"), file = ProjectFormat.layerFile(id, 1))
            }
            return ProjectFileDto(formatVersion = 1, id = "n", name = "n", width = cw, height = ch, activeLayerIndex = 0, layers = entries, revision = 1)
        }
        assertEquals(4, LayerEntries.decodersFor(dto(w, h, 5), w, h, tiled = true))
        assertEquals("3 pixel entries: 3 decoders", 3, LayerEntries.decodersFor(dto(w, h, 3), w, h, tiled = true))
        assertEquals("Classic keeps the one-band load", 1, LayerEntries.decodersFor(dto(w, h, 5), w, h, tiled = false))
        assertEquals("one pixel entry: one decoder", 1, LayerEntries.decodersFor(dto(w, h, 1), w, h, tiled = true))
        // Canvases whose layer L is R/3, R/5 and R/7: one decoder below 4·L, two below 6·L,
        // three below 8·L (d decoders need R >= 2·d·L).
        val r = EngineConfig.budget.nativeBudget
        assertTrue("T606 memory is known", EngineConfig.budget.budgets.known)
        val cw = 4096
        for ((part, decoders) in listOf(3 to 1, 5 to 2, 7 to 3)) {
            val ch = (r / part / 4 / cw).toInt()
            val layer = 4L * cw * ch
            assertTrue("R / L = $part", r >= 2L * decoders * layer && r < 2L * (decoders + 1) * layer)
            assertEquals("L = R/$part: $decoders decoder(s)", decoders, LayerEntries.decodersFor(dto(cw, ch, 5), cw, ch, tiled = true))
        }
    }
}
