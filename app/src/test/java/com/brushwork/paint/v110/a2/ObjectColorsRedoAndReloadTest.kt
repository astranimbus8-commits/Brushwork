package com.brushwork.paint.v110.a2

import android.app.Application
import android.graphics.Matrix
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextColorsDelegate
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 (R2 (b), J21; design 4.4 A2 steps 3-5, review): colors set through the Object colors
 * editor live in the fields v1.9 already stores (no new field). For a shape, a path object and a
 * text: ✓ keeps them in one step, Undo takes them back, Redo brings them back, and a save and a
 * load of the project keep them (no load warning). The user's drawing color stays theirs.
 */
@RunWith(RobolectricTestRunner::class)
class ObjectColorsRedoAndReloadTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun settle() {
        frame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun saveAndLoad(doc: Document): Document = runBlocking {
        val repo = ProjectRepository(app)
        repo.save(doc, null)
        repo.load(doc.id).also { assertEquals("no load warnings", emptyList<String>(), it.loadWarnings) }
    }

    private fun ObjectColorEditor?.open(): ObjectColorEditor = this ?: throw AssertionError("an open object has colors")

    private fun shapeOf(layer: Layer): ShapeObject = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "no shape in ${layer.name}" }

    @Test
    fun aShapesColorsUndoRedoAndReload() {
        val c = rig.controller()
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        tool.commit()
        settle()
        val layer = c.activeLayer
        c.tap(60f, 60f)
        assertSame("reopened", layer, tool.editingLayer)
        val ed = tool.objectColors.open()
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, BLUE)
        ed.preview(ObjectColorTarget.FILL, GREEN)
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        settle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(hex(BLUE), hex(shapeOf(layer).strokeColor))
        assertEquals(hex(GREEN), hex(shapeOf(layer).fillColor))
        assertEquals("the user's drawing color", hex(RED), hex(c.color))

        c.undo()
        settle()
        assertEquals("Undo", hex(RED), hex(shapeOf(layer).strokeColor))
        c.redo()
        settle()
        assertEquals("Redo: the stroke", hex(BLUE), hex(shapeOf(layer).strokeColor))
        assertEquals("Redo: the fill", hex(GREEN), hex(shapeOf(layer).fillColor))
        assertEquals("Redo keeps the user's color", hex(RED), hex(c.color))

        val loaded = saveAndLoad(c.doc)
        val back = shapeOf(loaded.layers.single { it.shapeData != null })
        assertEquals("reloaded: the stroke", hex(BLUE), hex(back.strokeColor))
        assertEquals("reloaded: the fill", hex(GREEN), hex(back.fillColor))
        assertEquals("reloaded: still filled and stroked", ShapeStyle.STROKE_FILL, back.style)
    }

    @Test
    fun aPathsColorsUndoRedoAndReload() {
        val c = rig.controller()
        c.snapping.enabled = false
        c.selectTool(ToolId.POLYLINE)
        val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = true, fillColor = null, closed = true, ownLayer = true) }
        for (p in listOf(Vec2(40f, 40f), Vec2(160f, 40f), Vec2(160f, 160f), Vec2(40f, 160f))) assertTrue(tool.addAnchor(p))
        tool.deselect()
        tool.commit()
        settle()
        val layer = c.doc.activeLayer
        assertTrue("a vector layer of its own", layer.isVectorLayer)
        fun placed() = layer.vector!!.objects.single() as VPath
        assertTrue(tool.reopen(placed()))
        settle()
        val ed = tool.objectColors.open()
        ed.begin()
        ed.preview(ObjectColorTarget.STROKE, BLACK)
        ed.preview(ObjectColorTarget.FILL, GREEN)
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        settle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(hex(BLACK), hex(placed().stroke!!.color))
        assertEquals(VPaint.Solid(GREEN), placed().fill)
        assertEquals("the user's drawing color", hex(RED), hex(c.color))

        c.undo()
        settle()
        assertEquals("Undo", hex(RED), hex(placed().stroke!!.color))
        c.redo()
        settle()
        assertEquals("Redo: the line", hex(BLACK), hex(placed().stroke!!.color))
        assertEquals("Redo: the fill", VPaint.Solid(GREEN), placed().fill)

        val loaded = saveAndLoad(c.doc)
        val back = loaded.layers.single { it.isVectorLayer }.vector!!.objects.single() as VPath
        assertEquals("reloaded: the line", hex(BLACK), hex(back.stroke!!.color))
        assertEquals("reloaded: the fill", VPaint.Solid(GREEN), back.fill)
    }

    @Test
    fun aTextsColorsUndoRedoAndReload() {
        val c = rig.controller(400, 300)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        c.tap(200f, 150f)
        assertTrue("the editor opened for a new text", tool.editorOpen && tool.editingNew)
        tool.setText("HH")
        tool.updateSpec { it.copy(sizePx = 80f, color = BLACK, strokeWidthPx = 0f) }
        tool.confirmEditor()
        val ed = tool.objectColors.open()
        ed.begin()
        ed.preview(ObjectColorTarget.TEXT, BLUE)
        ed.preview(ObjectColorTarget.OUTLINE, GREEN)
        ed.commit()
        val steps = c.undoManager.undoCount
        tool.commit()
        settle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        fun spec(d: Document): TextSpec = TextCodec.decodeAll(d.layers.single { it.isTextLayer }.textData).single().spec
        val width = TextColorsDelegate.defaultOutlineWidth(80f)
        fun assertColors(what: String, s: TextSpec) {
            assertEquals("$what: the text color", hex(BLUE), hex(s.color))
            assertEquals("$what: the outline color", hex(GREEN), hex(s.strokeColor))
            assertEquals("$what: the outline width", width, s.strokeWidthPx, 1e-4f)
        }
        assertColors("placed", spec(c.doc))
        assertEquals("the drawing color never changes", hex(RED), hex(c.color))

        c.undo()
        settle()
        assertTrue("Undo takes the text back", c.doc.layers.none { it.isTextLayer && TextCodec.decodeAll(it.textData).isNotEmpty() })
        c.redo()
        settle()
        assertColors("Redo", spec(c.doc))

        assertColors("reloaded", spec(saveAndLoad(c.doc)))
    }
}
