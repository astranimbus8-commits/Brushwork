package com.brushwork.paint.v110.cross

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.v110.a8.A8Kit
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric

/**
 * v1.10 Gate 2 cross test (R10 x A8): Edit on a shape object of a vector layer that was parked
 * and swapped out. The object is selected (Transform tool, the object bar's selection), the user
 * goes to another layer, and at FULL the editor's own policy parks the vector layer (its tick)
 * and sends its tiles to the swap ([FakeSwapStore], 2 ms a read). Back on the vector layer:
 *
 * 1. The activation waits for the tiles: `activating` holds input and the layer stays PARKED while
 *    the worker reads them; frames drawn meanwhile (a real [CanvasView]) read no tile on main (the
 *    display's waiting tiles read off main, P4). Once FLAT the layer holds its own pixels exactly,
 *    the selection is still there, and Edit opens the shape in the Shape tool (no step, no read on
 *    main). New colors (the Object colors editor) and ✓ are ONE step "Edit shape"; the layer is its
 *    content drawn afresh; Undo and Redo give back the objects and the pixels exactly.
 * 2. The same switch with Edit tapped at once, while the hold is on: the tool switch ends the
 *    activation first (LR1: `selectTool` settles it, reading on main what the worker has not read
 *    yet), so the shape opens on the layer FLAT with exactly its pixels and the hold is over; no
 *    frame before or after reads a tile on main; ✕ records nothing.
 *
 * At CLASSIC the same steps run with no park, no swap and no hold.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CrossEditOpensParkedTest(private val level: EngineLevel) {
    private val swap = FakeSwapStore(latencyMs = 2L)

    @get:Rule
    val engine = EngineRule(
        level,
        residency = if (level == EngineLevel.FULL) ClassicResidency else null,
        swap = if (level == EngineLevel.FULL) swap else null,
    )

    private val kit = ObjectTestKit(400, 300)
    private var controller: EditorController? = null
    private lateinit var view: CanvasView

    @After
    fun tearDown() {
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.let { c ->
            c.dispose()
            for (l in c.doc.layers) l.recycleBitmaps()
        }
        kit.close()
    }

    /** An attached canvas view of the editor (800 x 600), as the editor screen shows it. */
    private fun attach(c: EditorController) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = CanvasView(activity, c)
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(800, 600)) })
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 800, 600)
        assertTrue(view.isAttachedToWindow)
    }

    /** One frame of the canvas view, on main. */
    private fun frame() {
        val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(b))
        } finally {
            b.recycle()
        }
    }

    private val mainName: String get() = Looper.getMainLooper().thread.name

    /** The swap reads made on main since the [from]th read. */
    private fun mainReadsSince(from: Int): Int = swap.readThreads.drop(from).count { it == mainName }

    private fun settle(c: EditorController) = assertTrue("the layer settles", kit.idleUntil { !c.vectors.isRendering })

    /** The layer's pixels, read from its store (call it only when nothing is swapped out: a swapped tile is read on main). */
    private fun pixels(c: EditorController): IntArray = A8Kit.pixelsOf(c.vec).first

    private fun fill(c: EditorController, id: Long): Int? = (c.vec.vector!!.byId(id) as VShape).shape.fillColor

    /** The object [id] selected with the Transform tool (the object bar shows Edit). */
    private fun selectObject(c: EditorController, id: Long) {
        kit.transform(c)
        c.vectors.setSelection(c.vec, setOf(id))
        settle(c)
    }

    /** The background selected; at FULL the vector layer parks (the policy's tick) and every tile of its own goes to the swap. */
    private fun leaveAndSwapOut(c: EditorController) {
        c.selectLayer(c.doc.layers[0])
        kit.idle()
        if (level == EngineLevel.CLASSIC) return
        val p = EngineConfig.residency as ResidencyPolicy
        A8Kit.advance(2600)
        A8Kit.drain()
        assertEquals("parked by the policy", ResidencyState.PARKED, c.vec.residency)
        p.tileRam = { 0L }
        A8Kit.advance(600)
        A8Kit.drain()
        p.tileRam = { Long.MAX_VALUE }
        val own = A8Kit.tilesOf(c.vec).filter { it.solid == null }
        assertTrue("tiles of its own", own.isNotEmpty())
        assertTrue("swapped: ${own.map { it.state }}", own.all { it.state == TileState.SWAPPED })
    }

    /** Back on the vector layer: at FULL the activation waits for the tiles (the hold, still PARKED). */
    private fun comeBack(c: EditorController, what: String) {
        c.selectLayer(c.vec)
        assertSame(c.vec, c.doc.activeLayer)
        if (level == EngineLevel.CLASSIC) {
            assertNull(c.activating)
            return
        }
        assertSame("$what: the hold is on", c.vec, c.activating)
        assertEquals("$what: still parked while the tiles are read", ResidencyState.PARKED, c.vec.residency)
    }

    @Test
    fun editOpensTheObjectOfAParkedLayerAndItsCheckIsOneExactStep() {
        val c = kit.controller().also { controller = it }
        if (level == EngineLevel.FULL) assertNotNull("the editor's own policy", EngineConfig.residency as? ResidencyPolicy)
        val (box, shape) = c.vectors.addObjects(c.vec, listOf(kit.box(40f, 40f, 140f, 120f), kit.rectangle(280f, 180f, 120f, 80f)), "Add")
        settle(c)
        attach(c)
        frame()
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        val steps = c.undoManager.undoCount
        val placed = c.vec.vector!!
        val placedPixels = pixels(c)

        // 1. The selection kept across the switch; back on the layer, the activation waits for its tiles.
        selectObject(c, shape)
        leaveAndSwapOut(c)
        val reads = swap.readThreads.size
        comeBack(c, "1")
        repeat(3) { frame() }
        assertEquals("1: no frame read a tile on main", 0, mainReadsSince(reads))
        A8Kit.drain()
        assertNull("1: the hold ended", c.activating)
        assertEquals(ResidencyState.FLAT, c.vec.residency)
        if (level == EngineLevel.FULL) assertTrue("1: the worker read the tiles", swap.readThreads.size > reads)
        assertEquals("1: no read on main", 0, mainReadsSince(reads))
        assertArrayEquals("1: its own pixels", placedPixels, pixels(c))
        assertEquals("1: the selection is kept", setOf(shape), c.vectors.selectedIds)

        // Edit opens the shape in the Shape tool: no step, no read on main.
        assertTrue(ObjectActions.openInEditor(c))
        assertTrue("1: the shape opens", kit.idleUntil { tool.editingObject })
        assertEquals(ToolId.SHAPE, c.activeToolId)
        assertEquals("1: Edit records nothing", steps, c.undoManager.undoCount)
        frame()
        assertEquals("1: no read on main while open", 0, mainReadsSince(reads))

        // New colors, then ✓: one step "Edit shape", the layer is its content.
        val ed = requireNotNull(tool.objectColors) { "an open shape has colors" }
        ed.begin()
        ed.preview(ObjectColorTarget.FILL, GREEN)
        ed.commit()
        tool.commit()
        settle(c)
        assertEquals("1: Edit and ✓ are one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        assertEquals("1: the new fill", GREEN, fill(c, shape))
        val edited = c.vec.vector!!
        assertEquals("1: the box untouched", placed.byId(box), edited.byId(box))
        val editedPixels = pixels(c)
        assertArrayEquals("1: the layer is its content", kit.render(edited), editedPixels)

        // Undo and Redo: one step each, objects and pixels exact.
        c.undo()
        settle(c)
        assertEquals("1: undo, one step", steps, c.undoManager.undoCount)
        assertEquals("1: undo, the objects", placed.objects, c.vec.vector!!.objects)
        assertArrayEquals("1: undo, the pixels", placedPixels, pixels(c))
        c.redo()
        settle(c)
        assertEquals("1: redo, one step", steps + 1, c.undoManager.undoCount)
        assertEquals("1: redo, the objects", edited.objects, c.vec.vector!!.objects)
        assertArrayEquals("1: redo, the pixels", editedPixels, pixels(c))

        // 2. The same switch, Edit tapped while the hold is on: the tool switch ends the activation first (LR1).
        selectObject(c, shape)
        leaveAndSwapOut(c)
        val reads2 = swap.readThreads.size
        comeBack(c, "2")
        frame()
        assertEquals("2: no frame read a tile on main", 0, mainReadsSince(reads2))
        assertTrue(ObjectActions.openInEditor(c))
        assertNull("2: the activation ended before the tool opened", c.activating)
        assertEquals(ResidencyState.FLAT, c.vec.residency)
        assertTrue("2: the shape opens", kit.idleUntil { tool.editingObject })
        val settled = mainReadsSince(reads2)
        repeat(3) { frame() }
        A8Kit.drain()
        assertEquals("2: no frame after it read a tile on main", settled, mainReadsSince(reads2))
        assertArrayEquals("2: its own pixels", editedPixels, pixels(c))
        assertEquals("2: the shape as edited", GREEN, fill(c, shape))
        tool.discard()
        settle(c)
        assertEquals("2: Edit, then ✕: no step", steps + 1, c.undoManager.undoCount)
        assertEquals(edited.objects, c.vec.vector!!.objects)
        assertArrayEquals("2: the pixels as edited", editedPixels, pixels(c))
    }

    companion object {
        private const val GREEN = 0xFF00C040.toInt()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = listOf(arrayOf(EngineLevel.CLASSIC), arrayOf(EngineLevel.FULL))
    }
}
