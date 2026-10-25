package com.brushwork.paint.v110.a2

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.composite
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 (R2 object-only colors; design 4.4 A2 step 4, review fix): the Object colors of a NEW
 * path are the path's own, and nothing else is. The fill, the paint mode and the line color the
 * sheet gives a new path come back to the user's when the path ends (✓ or ✕), while an edit the
 * person makes on the strip meanwhile (the width, the stroke kind) stays the tool's setting, saved
 * for the next path, as it does without the sheet and as the Shape tool does
 * (`FillFollowsPinTest`). A value the person changes on the strip after the sheet set it is theirs.
 */
@RunWith(RobolectricTestRunner::class)
class CurveStripEditAfterColorsTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private val square = arrayOf(Vec2(40f, 40f), Vec2(160f, 40f), Vec2(160f, 160f), Vec2(40f, 160f))

    private fun settle() {
        frame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun EditorController.drawing(id: ToolId): CurveTool {
        selectTool(id)
        val t = tools.getValue(id) as CurveTool
        t.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = true, fillColor = null, closed = true, ownLayer = false) }
        for (p in square) assertTrue("$id adds $p", t.addAnchor(p))
        t.deselect()
        settle()
        return t
    }

    private fun editor(tool: CurveTool): ObjectColorEditor = tool.objectColors ?: throw AssertionError("an open path has colors")

    /** The options a new tool of [kind] reads back (what the next session starts with). */
    private fun saved(c: EditorController, kind: CurveKind): CurveSettings = CurveTool(c, kind).settings

    @Test
    fun aStripEditAfterTheSheetStaysTheUsersAndTheColorsComeBack() {
        for ((id, kind) in listOf(ToolId.POLYLINE to CurveKind.POLYLINE, ToolId.CURVE to CurveKind.CURVE, ToolId.PATH to CurveKind.PATH)) {
            val c = rig.controller()
            c.snapping.enabled = false
            val tool = c.drawing(id)
            val ed = editor(tool)
            ed.begin()
            ed.preview(ObjectColorTarget.FILL, BLUE)
            ed.preview(ObjectColorTarget.STROKE, GREEN)
            ed.commit()
            assertEquals("$id: the path shows its fill", BLUE, tool.settings.fillColor)
            // The strip, after the sheet: a wider line.
            tool.update { it.copy(plainWidth = 14f) }
            assertEquals("$id: the path shows the strip's width", 14f, tool.settings.plainWidth, 0f)
            tool.commit()
            settle()
            assertEquals("$id: painted with its fill", hex(BLUE), hex(c.composite().getPixel(100, 100)))
            if (id == ToolId.POLYLINE) assertEquals("painted with its line color", hex(GREEN), hex(c.composite().getPixel(100, 40)))
            assertEquals("$id: the user's drawing color is back", hex(RED), hex(c.color))
            assertNull("$id: the user's fill is back", tool.settings.fillColor)
            assertEquals("$id: the strip's width stays the tool's", 14f, tool.settings.plainWidth, 0f)
            val next = saved(c, kind)
            assertNull("$id: saved: the user's fill", next.fillColor)
            assertEquals("$id: saved: the strip's width", 14f, next.plainWidth, 0f)
        }
    }

    @Test
    fun theSheetsPaintModeComesBackButAStripChangeOfItStays() {
        val c = rig.controller()
        c.snapping.enabled = false
        // The sheet's Fill only on a new path: the user's Both comes back after ✕.
        var tool = c.drawing(ToolId.POLYLINE)
        var ed = editor(tool)
        ed.begin()
        ed.setPaintMode(PaintMode.FILL)
        ed.commit()
        assertEquals(CurvePaint.FILL, tool.paintMode)
        tool.discard()
        settle()
        assertEquals("the user's paint mode is back", CurvePaint.BOTH, tool.paintMode)
        assertEquals(CurveStroke.PLAIN, tool.settings.stroke)
        assertEquals("saved: Both", CurvePaint.BOTH, CurveTool(c, CurveKind.POLYLINE).paintMode)

        // The sheet's Fill only, then the strip's Stroke only: the strip's choice stays.
        tool = c.drawing(ToolId.POLYLINE)
        ed = editor(tool)
        ed.begin()
        ed.setPaintMode(PaintMode.FILL)
        ed.commit()
        tool.setPaintMode(CurvePaint.STROKE)
        tool.commit()
        settle()
        assertEquals("the strip's paint mode stays", CurvePaint.STROKE, tool.paintMode)
        assertEquals("saved: the strip's", CurvePaint.STROKE, CurveTool(c, CurveKind.POLYLINE).paintMode)

        // Cancel of the sheet: the strip edits of the rest of the path stay the tool's.
        tool.update { it.copy(fill = true) }
        tool = c.drawing(ToolId.POLYLINE)
        ed = editor(tool)
        ed.begin()
        ed.preview(ObjectColorTarget.FILL, BLUE)
        ed.cancel()
        assertNull(tool.settings.fillColor)
        tool.update { it.copy(plainWidth = 5f) }
        tool.commit()
        settle()
        assertEquals("after a Cancel: the strip's width stays", 5f, CurveTool(c, CurveKind.POLYLINE).settings.plainWidth, 0f)
        assertEquals(hex(RED), hex(c.color))
    }
}
