package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.LayerEntryDto
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.v110.a11.A11Kit
import com.brushwork.paint.v110.lead.F1Pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * v1.10 A8 step 6 (design 2.2, 2.16 "Facade and residency", I28): under `PixelFacade.strict` (a
 * materialization outside the allow-list throws) an editor at FULL over 200 layers, every other one
 * masked, all parked but the active one, materializes nothing for: a frame composited from the
 * parked stores, a pixel edit pushed to the history (and its undo and redo, also once its layer is
 * parked), a delete of a parked masked layer pushed and undone, `LayerBudget.used`, and a layer
 * switch, which makes only the new active layer FLAT. A save and an autosave of such a project
 * opened through `EditorSession` (every layer changed) write every layer and materialize nothing
 * either: A11's `LayerEntries` and `SaveThumbnail` read the stores, and the parked layers stay
 * parked. (The layers panel is A9's, after its step 4.)
 */
@RunWith(RobolectricTestRunner::class)
class PixelFacadeMaterializationTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap, strict = true)

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null
    private var session: EditorSession? = null
    private var sessionClosed = false

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        session?.let { if (!sessionClosed) close(it) }
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    /** An editor over [n] noisy 128 x 96 layers (every odd one masked), the top one active, every other one parked. */
    private fun parkedEditor(n: Int = 200): EditorController {
        val d = Document("a8", "a8", W, H).also { docs += it }
        repeat(n) { i ->
            d.layers += Layer(d.newLayerId(), "Layer $i", F1Pixels.mixed(W, H, 300L + i).also { made += it }).apply {
                if (i % 2 == 1) mask = F1Pixels.mixedMask(W, H, 900L + i).also { made += it }
            }
        }
        d.activeLayerIndex = n - 1
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), d, sc).also { controller = it }
        assertNotNull("the editor installed its policy at FULL", EngineConfig.residency as? ResidencyPolicy)
        A8Kit.idle()
        A8Kit.advance(3000)
        A8Kit.drain()
        for (l in d.layers) {
            val expected = if (l === d.activeLayer) ResidencyState.FLAT else ResidencyState.PARKED
            assertEquals("${l.name}", expected, l.residency)
        }
        return c
    }

    /** Runs [step] and asserts it materialized nothing (strict would have thrown). */
    private fun none(what: String, step: () -> Unit) {
        val before = PixelFacade.materializations
        step()
        assertEquals("$what materializes nothing", before, PixelFacade.materializations)
    }

    @Test
    fun aFrameOverParkedMaskedLayersMaterializesNothing() {
        val c = parkedEditor()
        val out = BitmapUtils.createLayerBitmap(W, H).also { made += it }
        none("a frame") { Compositor(c.doc) { null }.drawDocument(Canvas(out), null, target = null) }
        assertTrue("the parked layers stay parked", c.doc.layers.count { it.residency == ResidencyState.PARKED } == c.doc.layers.size - 1)
    }

    @Test
    fun anEditPushedUndoneAndRedoneMaterializesNothing() {
        val c = parkedEditor()
        val active = c.doc.activeLayer
        none("a pixel edit pushed") { A8Kit.paint(c, active, Rect(10, 10, 100, 80), 0xFF3366AA.toInt()) }
        none("its undo") { c.undo() }
        none("its redo") { c.redo() }

        // The edited layer parked: its undo and redo swap tiles in place.
        none("a switch") {
            c.selectLayer(c.doc.layers[0])
            A8Kit.drain()
        }
        A8Kit.advance(3000)
        A8Kit.drain()
        assertEquals(ResidencyState.PARKED, active.residency)
        none("an undo on a parked layer") { c.undo() }
        none("a redo on a parked layer") { c.redo() }
        assertEquals(ResidencyState.PARKED, active.residency)
    }

    @Test
    fun aDeleteOfAParkedMaskedLayerAndLayerBudgetUsedMaterializeNothing() {
        val c = parkedEditor()
        val masked = c.doc.layers[101]
        assertTrue(masked.hasMask)
        assertEquals(ResidencyState.PARKED, masked.residency)
        none("LayerBudget.used") { LayerBudget.current.used(c.doc) }
        none("a delete pushed") { c.deleteLayer(masked) }
        assertEquals(199, c.doc.layers.size)
        none("its undo") { c.undo() }
        assertSame(masked, c.doc.layers[101])
        none("LayerBudget.used again") { LayerBudget.current.used(c.doc) }
    }

    @Test
    fun aSwitchMakesOnlyTheNewActiveLayerFlat() {
        val c = parkedEditor()
        val next = c.doc.layers[51]
        none("a switch") {
            c.selectLayer(next)
            A8Kit.drain()
        }
        assertSame(next, c.doc.activeLayer)
        for (l in c.doc.layers) {
            val expected = if (l === next || l === c.doc.layers.last()) ResidencyState.FLAT else ResidencyState.PARKED
            assertEquals("${l.name}", expected, l.residency)
        }
    }

    /**
     * A project of [n] painted 128 x 96 layers (every odd one masked: visible above the middle,
     * half visible below it), the top one active, written as pixel files and opened through an
     * [EditorSession] at FULL, with A8's policy: every layer but the active one opens parked.
     */
    private fun openedProject(n: Int = 200): EditorController {
        A11Kit.freshRepo()
        val dir = A11Kit.dirOf(PROJECT).apply { mkdirs() }
        val white = A11Kit.opaqueBytes(0xFFFFFF)
        val gray = A11Kit.opaqueBytes(0x808080)
        val entries = (1L..n.toLong()).map { id ->
            val file = ProjectFormat.layerFile(id, 1)
            A11Kit.writeRows(File(dir, file), W, H, A11Kit.PaintedRows(W, H, seed = 300L + id)::fill)
            if (id % 2 == 0L) {
                val mask = ProjectFormat.maskFile(id, 1)
                A11Kit.writeRows(File(dir, mask), W, H) { y, rows, into ->
                    for (r in 0 until rows) {
                        val px = if (y + r < H / 2) white else gray
                        for (x in 0 until W) System.arraycopy(px, 0, into, (r * W + x) * 4, 4)
                    }
                }
                LayerEntryDto(id, "Layer $id", ProjectFormat.defaultProps("Layer $id"), hasMask = true, file = file, maskFile = mask)
            } else {
                LayerEntryDto(id, "Layer $id", ProjectFormat.defaultProps("Layer $id"), file = file)
            }
        }
        ProjectFormat.write(dir, ProjectFileDto(formatVersion = 3, id = PROJECT, name = PROJECT, width = W, height = H, activeLayerIndex = n - 1, layers = entries, revision = 1))
        val s = EditorSession(RuntimeEnvironment.getApplication() as BrushworkApp, PROJECT).also { session = it }
        pumpUntil("the editor") { s.state is EditorSession.State.Ready || s.state is EditorSession.State.Failed }
        val c = (s.state as? EditorSession.State.Ready)?.controller ?: throw AssertionError("not opened: ${s.state}")
        docs += c.doc
        // Under a test engine mode the session installs F1's ChaosResidency, so the editor built
        // no policy of its own: the app's (ClassicResidency at open, then A8's policy) is put in here.
        EngineConfig.residency = ClassicResidency
        assertNotNull("the editor's policy at FULL", ResidencyPolicy.attach(c))
        A8Kit.drain()
        assertEquals(n, c.doc.layers.size)
        assertEquals("every odd layer masked", n / 2, c.doc.layers.count { it.hasMask })
        assertParked(c)
        return c
    }

    /** Every layer of [c]'s document but the active one is parked; no pin is held. */
    private fun assertParked(c: EditorController) {
        for (l in c.doc.layers) {
            val expected = if (l === c.doc.activeLayer) ResidencyState.FLAT else ResidencyState.PARKED
            assertEquals("${l.name}", expected, l.residency)
        }
        assertEquals("no pin is held", 0, c.doc.layers.sumOf { it.pins })
    }

    /** Every layer of [c] changed (as after a whole-canvas edit) and an edit for the session to save. */
    private fun changeEverything(c: EditorController) {
        for (l in c.doc.layers) l.markChanged()
        c.updateGrid(GridSettings(enabled = !c.grid.enabled))
        assertTrue("unsaved", c.doc.layers.all { it.savedVersion != it.contentVersion })
    }

    private fun saved(c: EditorController) = c.doc.layers.all { it.savedVersion == it.contentVersion }

    private fun close(s: EditorSession) {
        var closed = false
        s.close { closed = true }
        pumpUntil("the session to close") { closed }
        sessionClosed = true
    }

    /** Runs the main looper in [stepMs] steps (virtual time) and gives the save's IO work real time, until [done]. */
    private fun pumpUntil(what: String, stepMs: Long = 10, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 3000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMs))
            Thread.sleep(2)
        }
        assertTrue("timed out waiting for $what", done())
    }

    @Test
    fun aSaveOfChangedParkedMaskedLayersMaterializesNothing() {
        val c = openedProject()
        val s = session!!
        changeEverything(c)
        none("a save") { runBlocking { s.save() } }
        assertTrue("every layer written (a strict throw would have failed the save)", saved(c))
        assertParked(c)
        close(s)
    }

    @Test
    fun anAutosaveOfChangedParkedMaskedLayersMaterializesNothing() {
        val app = RuntimeEnvironment.getApplication() as BrushworkApp
        val seconds = app.settings.autosaveSeconds
        app.settings.autosaveSeconds = 10
        try {
            val c = openedProject()
            changeEverything(c)
            none("an autosave") { pumpUntil("the autosave", stepMs = 50) { saved(c) } }
            assertParked(c)
            close(session!!)
        } finally {
            app.settings.autosaveSeconds = seconds
        }
    }

    private companion object {
        const val W = 128
        const val H = 96
        const val PROJECT = "a8facade"
    }
}
