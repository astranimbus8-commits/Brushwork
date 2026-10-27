package com.brushwork.paint.v110.cross

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyPolicy
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TileWorker
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.v110.a2.A2ToolRig
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import com.brushwork.paint.v110.a8.A8Kit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 Gate 2 cross test (R2 x A8 x A10): the Object colors of an open shape, then its layer
 * parked and swapped out. A reopened shape gets a new stroke and fill through the Object colors
 * editor (as the sheet drives it: `begin`, `preview`, OK's `commit`) and ✓: ONE step carrying the
 * colors, the layer's pixels drawn in them. The user then selects another layer; at FULL the
 * editor's own policy parks the shape layer (its tick, past `PARK_DELAY_MS`) and its tiles are
 * written out to the swap store (a [FakeSwapStore], through the tile worker), so the store holds
 * SWAPPED tiles. Undo and Redo are then one step each and give back the shape data and the
 * layer's pixels exactly (read from its store without materializing it). At CLASSIC the same
 * steps run with no park and no swap.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CrossObjectColorsUndoTilesTest(private val level: EngineLevel) {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(
        level,
        residency = if (level == EngineLevel.FULL) ClassicResidency else null,
        swap = if (level == EngineLevel.FULL) swap else null,
    )

    private val rig = A2ToolRig()
    private var controller: EditorController? = null

    @After
    fun tearDown() {
        (EngineConfig.residency as? ResidencyPolicy)?.stop()
        A8Kit.drain()
        controller?.let { c ->
            c.dispose()
            for (l in c.doc.layers) l.recycleBitmaps()
        }
        rig.release()
    }

    private fun settle() {
        frame()
        shadowOf(Looper.getMainLooper()).idle()
        A8Kit.drain()
    }

    private fun shapeOf(layer: Layer): ShapeObject = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "no shape in ${layer.name}" }

    /** The layer's pixels, read from its store (no materialization; a swapped tile is read back for the copy only). */
    private fun pixels(layer: Layer): IntArray = A8Kit.pixelsOf(layer).first

    private fun pixelAt(px: IntArray, c: EditorController, x: Int, y: Int) = px[y * c.doc.width + x]

    /** Another layer selected; at FULL the shape layer parks (the policy's tick) and its tiles go to the swap store. */
    private fun parkAndSwapOut(c: EditorController, layer: Layer) {
        c.selectLayer(c.doc.layers.first { it !== layer })
        settle()
        if (level == EngineLevel.CLASSIC) return
        A8Kit.advance(2600)
        A8Kit.drain()
        assertEquals("parked by the policy", ResidencyState.PARKED, layer.residency)
        val tiles = A8Kit.tilesOf(layer)
        TileOps.nextEpoch()
        val queued = tiles.count { TileWorker.writeOut(it) }
        A8Kit.drain()
        assertTrue("some tiles were written out ($queued of ${tiles.size})", queued > 0)
        assertEquals("every tile written out is swapped", queued, tiles.count { it.state == TileState.SWAPPED })
        assertEquals("still parked", ResidencyState.PARKED, layer.residency)
    }

    @Test
    fun theColorsStepUndoesAndRedoesExactlyOnAParkedSwappedLayer() {
        val c = rig.controller().also { controller = it }
        if (level == EngineLevel.FULL) assertNotNull("the editor's own policy", EngineConfig.residency as? ResidencyPolicy)
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        settle()
        val layer = c.activeLayer
        val placed = shapeOf(layer)
        val placedPixels = pixels(layer)

        // Reopened, its colors changed through the editor, then ✓: one step.
        c.tap(60f, 60f)
        assertSame("reopened", layer, tool.editingLayer)
        val ed = requireNotNull(tool.objectColors) { "an open shape has colors" }
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        ed.preview(ObjectColorTarget.FILL, GREEN)
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        settle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val colored = shapeOf(layer)
        assertEquals(hex(BLUE), hex(colored.strokeColor))
        assertEquals(hex(GREEN), hex(colored.fillColor))
        val coloredPixels = pixels(layer)
        assertEquals("the fill drawn green", hex(GREEN), hex(pixelAt(coloredPixels, c, 70, 60)))
        assertEquals("the stroke drawn blue", hex(BLUE), hex(pixelAt(coloredPixels, c, 30, 60)))

        // Parked and swapped out (FULL), then Undo and Redo: one step each, data and pixels exact.
        parkAndSwapOut(c, layer)
        c.undo()
        settle()
        assertEquals("undo: one step", steps, c.undoManager.undoCount)
        assertEquals("undo: the shape as placed", placed, shapeOf(layer))
        assertArrayEquals("undo: the pixels as placed", placedPixels, pixels(layer))
        c.redo()
        settle()
        assertEquals("redo: one step", steps + 1, c.undoManager.undoCount)
        assertEquals("redo: the shape in its colors", colored, shapeOf(layer))
        assertArrayEquals("redo: the pixels in its colors", coloredPixels, pixels(layer))

        // Once more after the layer is selected again (FLAT at FULL): the same step, the same pixels.
        c.selectLayer(layer)
        settle()
        c.undo()
        settle()
        assertArrayEquals("undo on the layer again", placedPixels, pixels(layer))
        c.redo()
        settle()
        assertArrayEquals("redo on the layer again", coloredPixels, pixels(layer))
        assertEquals(colored, shapeOf(layer))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = listOf(arrayOf(EngineLevel.CLASSIC), arrayOf(EngineLevel.FULL))
    }
}
