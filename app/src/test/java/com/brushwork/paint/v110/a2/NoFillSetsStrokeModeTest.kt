package com.brushwork.paint.v110.a2

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.v110.a2.A2SheetKit.assertClosed
import com.brushwork.paint.v110.a2.A2SheetKit.openSheet
import com.brushwork.paint.v110.a2.A2SheetKit.selected
import com.brushwork.paint.v110.a2.A2SheetKit.selectedTag
import com.brushwork.paint.v110.a2.A2SheetKit.settle
import com.brushwork.paint.v110.a2.A2SheetKit.tab
import com.brushwork.paint.v110.a2.A2SheetKit.tapIn
import com.brushwork.paint.v110.a2.A2SheetKit.tapTag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import com.brushwork.paint.vector.VPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 (R2 (c), R3; design 4.4 A2 step 7): the Object colors sheet's [ObjectColorLabels110.NO_FILL]
 * button is the object's paint mode Stroke (no fill field of its own). Driven as the user does,
 * the sheet opened from the pill's cell, its Fill tab:
 * - a reopened shape: "No fill" shows selected, the sheet's Stroke glyph too, the shape's style is
 *   Stroke and its Fill tab shows none; Cancel gives back Stroke and fill; OK then ✓ places the
 *   shape without fill, and the user's own style comes back to the tool;
 * - a new shape: OK then ✓ places it without fill; the user's style comes back;
 * - a reopened path (the Polyline tool): "No fill" turns its fill off (paint mode Stroke); Cancel
 *   gives it back; OK then ✓ places the path without fill, and the user's fill option comes back.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.nofillsandbox"])
class NoFillSetsStrokeModeTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private val strokeGlyph = "${V110Tags.COLORS_SHEET_MODE}.${PaintMode.STROKE.name}"

    /** Opens the sheet on its Fill tab and taps "No fill": the sheet shows Stroke. */
    private fun noFill(where: String) {
        openSheet(where)
        tapTag(tab(ObjectColorTarget.FILL))
        tapIn(ObjectColorLabels110.CELL, ObjectColorLabels110.NO_FILL)
        settle()
        assertEquals("$where: \"No fill\" is selected", true, selected(ObjectColorLabels110.NO_FILL))
        assertEquals("$where: the sheet's Stroke glyph is selected", true, selectedTag(strokeGlyph))
    }

    @Test
    fun noFillIsPaintModeStrokeForShapesAndPaths() {
        val c = rig.controller()
        c.snapping.enabled = false
        A2SheetKit.show(c)

        // ---- A reopened shape.
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        shape.commit()
        settle()
        val layer = c.activeLayer
        c.tap(60f, 60f)
        settle()
        assertSame("reopened", layer, shape.editingLayer)
        val editor = requireNotNull(shape.objectColors)
        noFill("a reopened shape, Cancel")
        assertEquals("No fill: the shape's style is Stroke", ShapeStyle.STROKE, requireNotNull(shape.pendingObject()).style)
        assertEquals("No fill: the editor's paint mode", PaintMode.STROKE, editor.paintMode)
        assertEquals("No fill: the Fill tab shows none", ObjectColorFace.None, editor.face(ObjectColorTarget.FILL))
        tapIn(ObjectColorLabels110.CELL, "Cancel")
        settle()
        assertClosed("Cancel")
        assertEquals("Cancel: filled and stroked again", ShapeStyle.STROKE_FILL, requireNotNull(shape.pendingObject()).style)

        noFill("a reopened shape, OK")
        tapIn(ObjectColorLabels110.CELL, "OK")
        settle()
        assertClosed("OK")
        shape.commit()
        settle()
        assertEquals("✓: placed without fill", ShapeStyle.STROKE, requireNotNull(ShapeCodec.decode(layer.shapeData)).style)
        assertEquals("✓: the user's style is back", ShapeStyle.STROKE_FILL, shape.settings.style)

        // ---- A new shape (on the pixel layer: it gets a shape layer of its own there).
        c.selectLayer(0)
        c.drag(120f to 120f, 150f to 150f, 180f to 180f)
        settle()
        assertTrue("a new shape", shape.hasPendingWork && shape.editingLayer == null)
        noFill("a new shape")
        tapIn(ObjectColorLabels110.CELL, "OK")
        settle()
        shape.commit()
        settle()
        val drawn = c.doc.layers.lastOrNull { it !== layer && it.shapeData != null }
            ?: throw AssertionError("a second shape layer; layers: ${c.doc.layers.map { it.name to (it.shapeData != null) }}")
        assertEquals("✓: the new shape has no fill", ShapeStyle.STROKE, requireNotNull(ShapeCodec.decode(drawn.shapeData)).style)
        assertEquals("✓: the user's style is back", ShapeStyle.STROKE_FILL, shape.settings.style)

        // ---- A reopened path (on the pixel layer: a path gets a vector layer of its own there).
        c.selectLayer(0)
        c.selectTool(ToolId.POLYLINE)
        val line = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        line.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = true, fillColor = null, closed = true, ownLayer = true) }
        for (p in listOf(Vec2(40f, 110f), Vec2(110f, 110f), Vec2(110f, 190f), Vec2(40f, 190f))) assertTrue(line.addAnchor(p))
        line.deselect()
        line.commit()
        settle()
        val vlayer = c.doc.activeLayer
        assertTrue("a vector layer of its own", vlayer.isVectorLayer)
        fun path() = vlayer.vector!!.objects.single() as VPath
        assertTrue(line.reopen(path()))
        settle()
        noFill("a reopened path, Cancel")
        assertEquals("No fill: the path's fill is off", false, line.settings.fill)
        assertEquals("No fill: paint mode Stroke", PaintMode.STROKE, requireNotNull(line.objectColors).paintMode)
        tapIn(ObjectColorLabels110.CELL, "Cancel")
        settle()
        assertClosed("Cancel on the path")
        assertEquals("Cancel: filled again", true, line.settings.fill)

        noFill("a reopened path, OK")
        tapIn(ObjectColorLabels110.CELL, "OK")
        settle()
        assertClosed("OK on the path")
        line.commit()
        settle()
        assertNull("✓: the path is placed without fill", path().fill)
        assertEquals("✓: the user's fill option is back", true, line.settings.fill)
    }
}
