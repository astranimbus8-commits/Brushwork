package com.brushwork.paint.v110.cross

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.PointCornerEditor
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.tools.faceOf
import com.brushwork.paint.v110.a6.A6Paths
import com.brushwork.paint.v110.a8.A8Kit
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 Gate 2 cross test (R11 then R12): a smooth closed path turned into a shape, then two of
 * its points made sharp with the pill's corner cell. "Turn into shape" is ONE step
 * ([ObjectBarLabels110.TO_SHAPE]) and opens the shape; each tap on the cell (as the cell does it:
 * the face of the `corner` state says what a tap makes, a mixed selection becoming all sharp) is
 * ONE in-tool step that the toolbar's Undo takes back and Redo brings again; ✓ is ONE step
 * ([ShapeTool.EDIT_SHAPE_LABEL]) holding both taps. Undo then walks back exactly to the shape as
 * turned and to the path (data and pixels), and Redo forward again. Saved and reopened through the
 * project repository, the layer holds the same objects (the shape with its sharp and smooth
 * points) and the same pixels, and Edit opens the reopened shape in the Shape tool with those
 * points. CLASSIC and FULL.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CrossConvertThenSharpTest(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val kit = ObjectTestKit(400, 300)
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<EditorController>()

    @After
    fun tearDown() {
        for (c in controllers) {
            c.dispose()
            A8Kit.drain()
            for (l in c.doc.layers) l.recycleBitmaps()
        }
        kit.close()
    }

    private fun settle(c: EditorController) = assertTrue("the layer settles", kit.idleUntil { !c.vectors.isRendering })

    /** The vector layer's pixels, read from its store (no materialization). */
    private fun pixels(c: EditorController): IntArray = A8Kit.pixelsOf(c.vec).first

    /** A tap on the pill's Sharp / Smooth cell: what its face says a tap makes (a greyed face does nothing). */
    private fun pillTap(e: PointCornerEditor): Boolean = faceOf(e.corner!!).makesSharp?.let { e.setSelectedSharp(it) } ?: false

    private fun smooth(tool: ShapeTool): List<Boolean> = tool.docAnchors()!!.map { it.smooth }

    private fun storedSmooth(content: VectorContent, id: Long): List<Boolean> =
        ((content.byId(id) as? VShape) ?: throw AssertionError("a shape")).shape.points!!.map { it.smooth }

    @Test
    fun turnIntoShapeThenSharpPointsAreOneStepEachAndSurviveSaveAndReopen() {
        val c = kit.controller().also { controllers += it }
        val id = A6Paths.add(kit, c, A6Paths.curve(closed = true).copy(fill = VPaint.Solid(RED))).single()
        val path = c.vec.vector!!
        val pathPixels = pixels(c)
        val steps = c.undoManager.undoCount

        // Turn into shape: one step; the shape opens in the Shape tool with its 4 smooth points.
        A6Paths.select(c, id)
        assertTrue(ObjectActions.turnIntoShape(c))
        settle(c)
        assertEquals("turn into shape: one step", steps + 1, c.undoManager.undoCount)
        assertEquals(ObjectBarLabels110.TO_SHAPE, c.undoManager.undoLabel)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue("the shape opens", kit.idleUntil { tool.editingObject })
        val turned = c.vec.vector!!
        val turnedPixels = pixels(c)
        tool.setPointEditing(true)
        assertEquals(4, tool.pointCount)
        val e = tool.pointCorners!!
        assertEquals("the path's smooth anchors are smooth points", List(4) { true }, smooth(tool))

        // Tap 1 on points 0 and 2 (all smooth: the face makes them sharp).
        tool.selectPoints(PointSelection.of(4, 0, 2))
        assertEquals(CornerState.SMOOTH, e.corner)
        assertTrue(pillTap(e))
        val tap1 = listOf(false, true, false, true)
        assertEquals("tap 1", tap1, smooth(tool))
        c.undo()
        assertEquals("undo: tap 1 is one in-tool step", List(4) { true }, smooth(tool))
        assertEquals("no document step yet", steps + 1, c.undoManager.undoCount)
        c.redo()
        assertEquals("redo: tap 1 again", tap1, smooth(tool))

        // Tap 2 on points 1 and 2 (one smooth, one sharp: mixed becomes all sharp).
        tool.selectPoints(PointSelection.of(4, 1, 2))
        assertEquals(CornerState.MIXED, e.corner)
        assertTrue(pillTap(e))
        val tap2 = listOf(false, false, false, true)
        assertEquals("tap 2", tap2, smooth(tool))
        c.undo()
        assertEquals("undo: tap 2 is one in-tool step", tap1, smooth(tool))
        c.redo()
        assertEquals("redo: tap 2 again", tap2, smooth(tool))
        assertEquals("no document step yet", steps + 1, c.undoManager.undoCount)

        // ✓: one step holding both taps; the layer is its content drawn afresh.
        tool.commit()
        settle(c)
        assertEquals("✓: one step", steps + 2, c.undoManager.undoCount)
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        val sharpened = c.vec.vector!!
        assertEquals("the stored points", tap2, storedSmooth(sharpened, id))
        val sharpenedPixels = pixels(c)
        assertArrayEquals("the layer is its content", kit.render(sharpened), sharpenedPixels)

        // Undo walks back: the shape as turned, then the path, data and pixels exact; Redo forward.
        c.undo()
        settle(c)
        assertEquals("undo 1: one step", steps + 1, c.undoManager.undoCount)
        assertEquals("undo 1: the shape as turned", turned.objects, c.vec.vector!!.objects)
        assertArrayEquals("undo 1: its pixels", turnedPixels, pixels(c))
        c.undo()
        settle(c)
        assertEquals("undo 2: one step", steps, c.undoManager.undoCount)
        assertEquals("undo 2: the path", path.objects, c.vec.vector!!.objects)
        assertArrayEquals("undo 2: its pixels", pathPixels, pixels(c))
        c.redo()
        settle(c)
        assertEquals("redo 1: the shape as turned", turned.objects, c.vec.vector!!.objects)
        assertArrayEquals("redo 1: its pixels", turnedPixels, pixels(c))
        c.redo()
        settle(c)
        assertEquals("redo 2: one step each", steps + 2, c.undoManager.undoCount)
        assertEquals("redo 2: the sharpened shape", sharpened.objects, c.vec.vector!!.objects)
        assertArrayEquals("redo 2: its pixels", sharpenedPixels, pixels(c))

        // Saved and reopened: no warning, the same objects and pixels, the sharp points kept.
        c.selectTool(ToolId.BRUSH)
        settle(c)
        runBlocking { ProjectRepository(app).save(c.doc, null) }
        val loaded = runBlocking { ProjectRepository(app).load(c.doc.id) }
        assertTrue("no load warnings: ${loaded.loadWarnings}", loaded.loadWarnings.isEmpty())
        assertEquals("the same objects", sharpened.objects, loaded.layers[1].vector!!.objects)
        val c2 = EditorController(app, loaded, kit.scope, AppSettings(app)).also {
            controllers += it
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
        }
        settle(c2)
        assertArrayEquals("reopened: the same pixels", sharpenedPixels, pixels(c2))
        assertEquals("reopened: the stored points", tap2, storedSmooth(c2.vec.vector!!, id))

        // Edit opens the reopened shape with its sharp and smooth points; ✕ records nothing.
        val steps2 = c2.undoManager.undoCount
        c2.selectLayer(c2.vec)
        c2.vectors.setSelection(c2.vec, setOf(id))
        assertTrue(ObjectActions.openInEditor(c2))
        val tool2 = c2.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue("the reopened shape opens", kit.idleUntil { tool2.editingObject })
        tool2.setPointEditing(true)
        assertEquals("reopened: the points", tap2, smooth(tool2))
        tool2.discard()
        settle(c2)
        assertEquals("Edit, then ✕: no step", steps2, c2.undoManager.undoCount)
    }

    companion object {
        private const val RED = 0xFFE04020.toInt()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = listOf(arrayOf(EngineLevel.CLASSIC), arrayOf(EngineLevel.FULL))
    }
}
