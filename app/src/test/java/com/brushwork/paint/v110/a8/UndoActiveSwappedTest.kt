package com.brushwork.paint.v110.a8

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.LayerPropsAction
import com.brushwork.paint.engine.MaskChangeAction
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

    /**
     * Review fix (7/7): a step pushed while an undo waits (one that changes no pixels through a
     * `PixelEditRecorder`, which would settle the undo first) drops the redo steps, but the
     * waiting undo still applies, and its step is let go after it: before, the push disposed the
     * waiting step, its undo was lost and the step's change stayed in the pixels with no step
     * left to undo it.
     */
    @Test
    fun anEditPushedWhileItWaitsStillLetsTheUndoApplyThenDropsItsStep() {
        val c = editor()
        val l = c.doc.layers[2]
        val other = c.doc.layers[0]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val tiles = swapOutNewest(c)
        val steps = c.undoManager.undoCount

        c.undo()
        assertSame(l, c.activating)
        pushOpacity(c, other)
        assertFalse("the push dropped the redo side", c.undoManager.canRedo)
        assertSame("the push settled nothing", l, c.activating)
        assertTrue("the waiting step is not let go before it applied", tiles.any { it.pins > 0 || it.refs > 0 })

        A8Kit.drain()
        assertNull("the hold ended", c.activating)
        assertArrayEquals("the undo asked before the edit applied", before, A8Kit.pixelsOf(l).first)
        assertEquals("the edit is there", 0.5f, other.opacity, 0f)
        assertEquals("the edit is the newest step", steps, c.undoManager.undoCount)
        assertFalse(c.undoManager.canRedo)
        assertTrue("the undone step was let go after its apply", tiles.all { it.refs <= 0 && it.pins == 0 && it.slot == null })
    }

    /** Sets [layer]'s opacity to 0.5 and pushes the step as is: no pixel edit, no editor entry point. */
    private fun pushOpacity(c: EditorController, layer: com.brushwork.paint.model.Layer) {
        val step = LayerPropsAction(layer, layer.props(), layer.props().copy(opacity = 0.5f), "Opacity")
        step.redo(c)
        c.undoManager.push(step)
    }

    /**
     * LR1 inside A8: a pixel edit started while an undo of the same pixels waits for its tiles
     * (no explicit settle: a menu fill, a filter landing) settles that undo when its recorder is
     * made, so the undo applies first and the edit lands on the undone pixels; the worker's late
     * reads apply nothing, and undoing the edit gives the undone pixels back.
     */
    @Test
    fun anEditStartedWhileAnUndoWaitsLandsAfterIt() {
        val c = editor()
        val l = c.doc.layers[2]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val tiles = swapOutNewest(c)
        val steps = c.undoManager.undoCount

        c.undo()
        assertSame(l, c.activating)
        A8Kit.paint(c, l, Rect(150, 100, 300, 200), 0xFF00FF00.toInt())
        assertNull("the recorder settled the undo", c.activating)
        val edited = A8Kit.pixelsOf(l).first
        assertEquals("the edit is there", 0xFF00FF00.toInt(), edited[150 * 512 + 200])
        assertEquals("beside it, the undone pixels", before[60 * 512 + 110], edited[60 * 512 + 110])
        assertEquals("the edit is the newest step", steps, c.undoManager.undoCount)
        assertFalse(c.undoManager.canRedo)

        A8Kit.drain()
        assertArrayEquals("the late reads applied nothing", edited, A8Kit.pixelsOf(l).first)
        assertTrue("the undone step was let go", tiles.all { it.refs <= 0 && it.pins == 0 && it.slot == null })
        c.undo()
        A8Kit.drain()
        assertArrayEquals("undoing the edit gives the undone pixels", before, A8Kit.pixelsOf(l).first)
    }

    /**
     * LR1: `UndoManager.settle` (what an edit made while an undo waits asks first) applies the
     * waiting undo at once, reading its SWAPPED tiles on main, so an edit of the same pixels that
     * follows lands on the undone layer and is never overwritten by the undo; the worker's reads
     * still in flight then apply nothing a second time.
     */
    @Test
    fun aSettleAppliesTheWaitingUndoBeforeAnEditOfTheSamePixels() {
        val c = editor()
        val l = c.doc.layers[2]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val tiles = swapOutNewest(c)
        val reads = swap.reads
        val steps = c.undoManager.undoCount

        c.undo()
        assertSame(l, c.activating)
        c.undoManager.settle()
        assertNull("the hold ended at once", c.activating)
        assertArrayEquals("the undo applied at once", before, A8Kit.pixelsOf(l).first)
        assertTrue("its tiles were read on main", swap.readThreads.drop(reads).contains(mainName))

        A8Kit.paint(c, l, Rect(150, 100, 300, 200), 0xFF00FF00.toInt())
        val edited = A8Kit.pixelsOf(l).first
        assertEquals("the edit is there", 0xFF00FF00.toInt(), edited[150 * 512 + 200])
        assertEquals("under it, the undone pixels", before[60 * 512 + 110], edited[60 * 512 + 110])
        assertEquals("the edit is the newest step", steps, c.undoManager.undoCount)
        assertFalse(c.undoManager.canRedo)

        A8Kit.drain()
        assertNull(c.activating)
        assertArrayEquals("the late reads applied nothing", edited, A8Kit.pixelsOf(l).first)
        assertTrue("the undone step was let go", tiles.all { it.refs <= 0 && it.pins == 0 && it.slot == null })
        c.undo()
        A8Kit.drain()
        assertArrayEquals("undoing the edit gives the undone pixels", before, A8Kit.pixelsOf(l).first)
    }

    /**
     * LR2: an apply that waited and the steps queued behind it run as undo and redo run theirs
     * (the editor's `inHistoryDo`): what a step queues for the edit listeners while it is undone
     * (a tool reacting) reaches none of them.
     */
    @Test
    fun theStepsQueuedBehindAWaitingUndoApplyAsHistory() {
        val c = editor()
        val l = c.doc.layers[2]
        val heard = ArrayList<com.brushwork.paint.EditEvent>()
        c.addEditListener { heard += it }
        var undone = 0
        val reacting = object : com.brushwork.paint.engine.UndoAction {
            override val label = "Reacting"
            override val byteSize = 0L
            override fun undo(c: EditorController) {
                undone++
                c.queueEdit(com.brushwork.paint.EditEvent(l, com.brushwork.paint.engine.EditTarget.CONTENT, null, "Reaction"))
            }
            override fun redo(c: EditorController) = Unit
        }
        c.pushUndo(reacting)
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        swapOutNewest(c)
        heard.clear()

        c.undo()
        c.undo()
        assertEquals("queued behind the waiting undo", 0, undone)
        A8Kit.drain()
        assertNull(c.activating)
        assertEquals("then undone", 1, undone)
        assertTrue("no listener heard of it: $heard", heard.isEmpty())
    }

    /**
     * LR3: a canvas change (a rotation: `CanvasOps`' step around its stores step) undone while a
     * pixel undo waits is queued behind it at the manager, so it never moves the layers before the
     * waiting tiles go back where they were taken: both undone, the pixels are the original ones.
     */
    @Test
    fun aCanvasChangeUndoneWhileAnUndoWaitsAppliesAfterIt() {
        val c = editor()
        val l = c.doc.layers[2]
        val original = A8Kit.pixelsOf(l).first
        assertTrue("started", com.brushwork.paint.engine.CanvasOps.applyRotate(c, com.brushwork.paint.engine.CanvasRotation.CW_90))
        repeat(4000) {
            if (c.busyMessage == null) return@repeat
            A8Kit.idle()
            Thread.sleep(2)
        }
        A8Kit.drain()
        assertNull("rotated", c.busyMessage)
        val rotated = A8Kit.pixelsOf(l).first
        assertFalse("the rotation moved the pixels", rotated.contentEquals(original))
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        swapOutNewest(c)

        c.undo()
        c.undo()
        assertSame("the pixel undo waits", l, c.activating)
        A8Kit.drain()
        assertNull(c.activating)
        assertArrayEquals("both undone, in order", original, A8Kit.pixelsOf(l).first)
    }

    /**
     * Review fix (7/7): an undo of a mask change queued behind a waiting undo, then an edit
     * pushed: the queued undo applies (the mask comes back) and only then is its step disposed.
     * Before, the push disposed the step first, and the queued undo then put the null it was
     * left holding into the layer: the mask never came back.
     */
    @Test
    fun aMaskUndoQueuedBehindAWaitingUndoSurvivesAPush() {
        val c = editor()
        val masked = c.doc.layers[1]
        val l = c.doc.layers[2]
        val maskBefore = A8Kit.pixelsOf(masked).second!!
        val delete = MaskChangeAction(masked, masked.maskPixels, null, "Delete mask")
        delete.redo(c)
        c.undoManager.push(delete)
        assertFalse(masked.hasMask)
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        swapOutNewest(c)

        c.undo()
        c.undo()
        assertSame("the first undo waits", l, c.activating)
        assertFalse("the mask undo is queued", masked.hasMask)
        pushOpacity(c, c.doc.layers[0])

        A8Kit.drain()
        assertNull(c.activating)
        assertArrayEquals(before, A8Kit.pixelsOf(l).first)
        assertTrue("the queued mask undo applied", masked.hasMask)
        assertArrayEquals("the mask as it was", maskBefore, A8Kit.pixelsOf(masked).second)
        assertFalse(c.undoManager.canRedo)
    }

    /**
     * Review fix (7/7): a composite step whose pixel part waits for its tiles holds its later
     * parts too: a redo of [a stroke, an opacity change] changes the opacity only after the
     * stroke's tiles were read and applied, never out of turn.
     */
    @Test
    fun aCompositesLaterPartsWaitBehindItsWaitingPart() {
        val c = editor()
        val l = c.doc.layers[2]
        val opacity = l.opacity
        val mark = c.undoManager.undoCount
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val after = A8Kit.pixelsOf(l).first
        c.setLayerProps(l, l.props().copy(opacity = 0.5f))
        val parts = c.undoManager.takeSince(mark)
        assertEquals("a stroke and a props change", 2, parts.size)
        val both = CompositeAction("Both", parts)
        c.undoManager.pushRaw(both)

        c.undo()
        A8Kit.drain()
        assertEquals(opacity, l.opacity, 0f)
        // The undone step (on the redo side now) holds the stroke's pixels: sent to the swap.
        val tiles = A8Kit.tilesOfStep(both)
        assertTrue(tiles.isNotEmpty())
        TileOps.nextEpoch()
        for (t in tiles) assertTrue("queued", TileWorker.writeOut(t))
        A8Kit.drain()
        assertTrue(tiles.all { it.state == TileState.SWAPPED })

        c.redo()
        assertSame("the stroke part waits", l, c.activating)
        assertEquals("the props part waits behind it", opacity, l.opacity, 0f)
        A8Kit.drain()
        assertNull(c.activating)
        assertArrayEquals(after, A8Kit.pixelsOf(l).first)
        assertEquals("then the props part applied", 0.5f, l.opacity, 0f)
    }

    /**
     * Lead (A8 LR1): Clear layer (a blank store swapped in: no `PixelEditRecorder`) asked while an
     * undo of the same layer waits for its tiles settles that undo first (`withToolPaused` and
     * `editScope`), so the clear lands after it: undoing the clear gives the undone pixels back.
     * Before, the clear swapped the store first, the late apply found no flat store and applied
     * nothing, and undoing the clear brought back the pixels of the undone stroke.
     */
    @Test
    fun aClearAskedWhileAnUndoWaitsLandsAfterIt() {
        val c = editor()
        val l = c.doc.layers[2]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        val tiles = swapOutNewest(c)
        val steps = c.undoManager.undoCount

        c.undo()
        assertSame(l, c.activating)
        assertNull("no selection", c.selection)
        c.clearLayer(l)
        assertNull("the clear settled the undo first", c.activating)
        assertTrue("cleared", A8Kit.pixelsOf(l).first.all { it == 0 })
        assertEquals("the clear is the newest step", steps, c.undoManager.undoCount)
        assertEquals("Clear", A8Kit.newestStep(c).label)
        assertFalse(c.undoManager.canRedo)

        A8Kit.drain()
        assertNull(c.activating)
        assertTrue("the late reads applied nothing", A8Kit.pixelsOf(l).first.all { it == 0 })
        assertTrue("the undone step was let go", tiles.all { it.refs <= 0 && it.pins == 0 && it.slot == null })
        c.undo()
        A8Kit.drain()
        assertArrayEquals("undoing the clear gives the undone pixels", before, A8Kit.pixelsOf(l).first)
    }

    /**
     * Lead (A8 LR1): any editor action (`editScope`, here a layer property change of another
     * layer) settles a waiting undo before it runs, so the undo is never applied after it.
     */
    @Test
    fun anEditScopeSettlesAWaitingUndoFirst() {
        val c = editor()
        val l = c.doc.layers[2]
        val other = c.doc.layers[0]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        swapOutNewest(c)
        val steps = c.undoManager.undoCount

        c.undo()
        assertSame(l, c.activating)
        c.editScope { c.setLayerProps(other, other.props().copy(opacity = 0.5f), "Opacity") }
        assertNull("settled before the action ran", c.activating)
        assertArrayEquals("the undo applied first", before, A8Kit.pixelsOf(l).first)
        assertEquals(0.5f, other.opacity, 0f)
        assertEquals("the action is the newest step", steps, c.undoManager.undoCount)
        assertEquals("Opacity", A8Kit.newestStep(c).label)
        assertFalse(c.undoManager.canRedo)
        A8Kit.drain()
        assertArrayEquals(before, A8Kit.pixelsOf(l).first)
    }

    /**
     * Lead (A8 LR1): a tool switched while an undo waits (a strip tap: only canvas input is held)
     * settles it before the new tool's `onActivate`, which may read the layer (a lift, a proxy).
     */
    @Test
    fun aToolSwitchedWhileAnUndoWaitsFindsTheLayerUndone() {
        val c = editor()
        val l = c.doc.layers[2]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        swapOutNewest(c)

        c.undo()
        assertSame(l, c.activating)
        val to = if (c.activeToolId == com.brushwork.paint.tools.ToolId.ERASER) com.brushwork.paint.tools.ToolId.BRUSH else com.brushwork.paint.tools.ToolId.ERASER
        c.selectTool(to)
        assertEquals(to, c.activeToolId)
        assertNull("settled before the switch", c.activating)
        assertArrayEquals("undone", before, A8Kit.pixelsOf(l).first)
        A8Kit.drain()
        assertArrayEquals(before, A8Kit.pixelsOf(l).first)
    }

    /**
     * Lead (A8 LR1): a rollback asked while an undo waits settles it first, then takes back the
     * steps after the mark: both strokes are gone, none is lost to a disposed step.
     */
    @Test
    fun aRollbackWhileAnUndoWaitsTakesBackEveryStep() {
        val c = editor()
        val l = c.doc.layers[2]
        val original = A8Kit.pixelsOf(l).first
        val marker = c.undoMarker()
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        A8Kit.paint(c, l, Rect(200, 100, 500, 290), 0xFFAA6633.toInt())
        swapOutNewest(c)

        c.undo()
        assertSame(l, c.activating)
        assertTrue(c.rollbackTo(marker))
        assertNull("settled", c.activating)
        assertEquals(marker.depth, c.undoManager.undoCount)
        A8Kit.drain()
        assertNull(c.activating)
        assertArrayEquals("both strokes taken back", original, A8Kit.pixelsOf(l).first)
    }

    /**
     * Lead (A8): a canvas operation started while an undo waits (`CanvasOps.run`) settles it
     * before it takes its snapshot, so it works on the undone layer: rotated back, the layer is
     * the undone one, and the undone stroke is nowhere in it.
     */
    @Test
    fun aRotationStartedWhileAnUndoWaitsWorksOnTheUndoneLayer() {
        val c = editor()
        val l = c.doc.layers[2]
        val before = A8Kit.pixelsOf(l).first
        A8Kit.paint(c, l, Rect(100, 50, 400, 250), 0xFF3366AA.toInt())
        swapOutNewest(c)

        c.undo()
        assertSame(l, c.activating)
        assertTrue("started", rotate(c, com.brushwork.paint.engine.CanvasRotation.CW_90))
        assertNull("settled before the snapshot", c.activating)
        assertEquals("rotated", 300, c.doc.width)
        assertFalse("the rotation dropped the redo side", c.undoManager.canRedo)
        assertTrue("rotated back", rotate(c, com.brushwork.paint.engine.CanvasRotation.CCW_90))
        assertEquals(512, c.doc.width)
        assertArrayEquals("the undone layer, never the stroke", before, A8Kit.pixelsOf(l).first)
    }

    /** Runs a rotation of the canvas through `CanvasOps` to its end. */
    private fun rotate(c: EditorController, r: com.brushwork.paint.engine.CanvasRotation): Boolean {
        if (!com.brushwork.paint.engine.CanvasOps.applyRotate(c, r)) return false
        repeat(4000) {
            if (c.busyMessage == null) return@repeat
            A8Kit.idle()
            Thread.sleep(2)
        }
        A8Kit.drain()
        assertNull("the rotation ended", c.busyMessage)
        return true
    }

    /**
     * Lead (A8, lead note): a rollback over [a rotation, a stroke whose tiles are SWAPPED]: the
     * stroke's undo waits for its tiles, the rotation's undo (`CanvasOps`' canvas step, applied
     * directly by the rollback, not through the manager) waits behind it, and both apply before
     * the rollback disposes the steps: the layer is the original one, at its original size.
     */
    @Test
    fun aRollbackOverARotationAndASwappedStrokeAppliesBothInOrder() {
        val c = editor()
        val l = c.doc.layers[2]
        val original = A8Kit.pixelsOf(l).first
        val width = c.doc.width
        val marker = c.undoMarker()
        assertTrue("started", com.brushwork.paint.engine.CanvasOps.applyRotate(c, com.brushwork.paint.engine.CanvasRotation.CW_90))
        repeat(4000) {
            if (c.busyMessage == null) return@repeat
            A8Kit.idle()
            Thread.sleep(2)
        }
        A8Kit.drain()
        assertNull("rotated", c.busyMessage)
        A8Kit.paint(c, l, Rect(50, 100, 250, 400), 0xFF3366AA.toInt())
        swapOutNewest(c)

        assertTrue(c.rollbackTo(marker))
        A8Kit.drain()
        assertNull(c.activating)
        assertEquals(marker.depth, c.undoManager.undoCount)
        assertEquals("the original size", width, c.doc.width)
        assertArrayEquals("the original pixels", original, A8Kit.pixelsOf(l).first)
    }
}
