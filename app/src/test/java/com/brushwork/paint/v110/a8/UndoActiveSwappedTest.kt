package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A8 step 4 (design 2.6, 2.7; FULL with a [FakeSwapStore] that takes 2 ms a read): an undo
 * of the active (FLAT) layer whose step holds SWAPPED tiles runs asynchronously. `undo` returns
 * with `EditorController.activating` set and the pixels unchanged; a gesture meanwhile reaches no
 * tool; the worker reads the tiles (never main); then main applies the step, the hold ends, and
 * the pixels are those of a synchronous undo. Undos and redos asked while it waits run after it,
 * in order. A history cleared while it waits applies nothing and lets every tile go.
 */
@RunWith(RobolectricTestRunner::class)
class UndoActiveSwappedTest {
    private val swap = FakeSwapStore(latencyMs = 2L)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, residency = ClassicResidency, swap = swap)

    private val made = ArrayList<Bitmap>()
    private val docs = ArrayList<Document>()
    private lateinit var baseline: A8Kit.Baseline
    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        baseline = A8Kit.Baseline()
    }

    @After
    fun tearDown() {
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.dispose()
        scope?.cancel()
        baseline.assertBack(docs)
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun editor(): EditorController {
        val doc = A8Kit.doc(3, made).also { docs += it }
        doc.activeLayerIndex = 2
        val sc = Smoke.newScope().also { scope = it }
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc, sc).also { controller = it }
        assertNotNull("the editor installed its policy at FULL", EngineConfig.residency as? ResidencyPolicy)
        A8Kit.idle()
        return c
    }

    private val mainName: String get() = Looper.getMainLooper().thread.name

    /** Sends every tile of [c]'s newest undo step to the swap (as P3 order 2 does with undo-only tiles). */
    private fun swapOutNewest(c: EditorController): List<Tile> {
        val tiles = A8Kit.tilesOfStep(A8Kit.newestStep(c))
        assertTrue("the step holds tiles", tiles.isNotEmpty())
        TileOps.nextEpoch()
        for (t in tiles) assertTrue("queued", TileWorker.writeOut(t))
        A8Kit.drain()
        assertTrue("swapped: ${tiles.map { it.state }}", tiles.all { it.state == TileState.SWAPPED })
        return tiles
    }

    @Test
    fun anUndoThatFindsSwappedTilesWaitsForTheWorkerThenApplies() {
        val c = editor()
        val l = c.doc.layers[2]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val after = A8Kit.pixelsOf(l).first
        swapOutNewest(c)
        val reads = swap.reads
        val steps = c.undoManager.undoCount

        assertTrue(c.undoManager.canUndo)
        c.undo()
        assertSame("the hold is on when undo returns", l, c.activating)
        assertEquals("the step moved to the redo side", steps - 1, c.undoManager.undoCount)
        assertArrayEquals("not applied before its tiles are read", after, A8Kit.pixelsOf(l).first)
        c.pointerDown(ToolPoint(40f, 40f))
        assertFalse("no tool took the gesture", c.isInteracting)
        c.pointerUp(ToolPoint(40f, 40f))

        A8Kit.drain()
        assertNull("the hold ended", c.activating)
        assertEquals(ResidencyState.FLAT, l.residency)
        assertArrayEquals("the pixels of a synchronous undo", before, A8Kit.pixelsOf(l).first)
        assertTrue("the tiles were read", swap.reads > reads)
        val threads = swap.readThreads.drop(reads)
        assertFalse("no read on main: $threads", threads.contains(mainName))
        assertEquals("nothing was drawn while held", steps - 1, c.undoManager.undoCount)

        // The step's tiles are resident now: the redo is synchronous.
        c.redo()
        assertNull(c.activating)
        assertArrayEquals(after, A8Kit.pixelsOf(l).first)
    }

    @Test
    fun undosAndRedosAskedWhileItWaitsRunAfterItInOrder() {
        val c = editor()
        val l = c.doc.layers[2]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val first = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(200, 100, 500, 290), 0xFFAA6633.toInt())
        val second = A8Kit.pixelsOf(l).first
        swapOutNewest(c)

        c.undo()
        c.undo()
        assertSame(l, c.activating)
        A8Kit.drain()
        assertNull(c.activating)
        assertArrayEquals("both undone, the newest first", before, A8Kit.pixelsOf(l).first)

        c.redo()
        assertArrayEquals(first, A8Kit.pixelsOf(l).first)
        // The second step's map holds its own tiles read back (resident): synchronous.
        c.redo()
        assertArrayEquals(second, A8Kit.pixelsOf(l).first)

        swapOutNewest(c)
        c.undo()
        c.redo()
        c.undo()
        A8Kit.drain()
        assertNull(c.activating)
        assertArrayEquals("undo, redo, undo: one step back", first, A8Kit.pixelsOf(l).first)
    }

    @Test
    fun aHistoryClearedWhileItWaitsAppliesNothing() {
        val c = editor()
        val l = c.doc.layers[2]
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val after = A8Kit.pixelsOf(l).first
        val tiles = swapOutNewest(c)

        c.undo()
        assertSame(l, c.activating)
        c.undoManager.clear()
        A8Kit.drain()
        assertNull("the hold ended", c.activating)
        assertArrayEquals("nothing applied", after, A8Kit.pixelsOf(l).first)
        assertTrue("every tile of the step let go", tiles.all { it.refs <= 0 && it.pins == 0 && it.slot == null })
    }
}
