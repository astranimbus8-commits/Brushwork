package com.brushwork.paint.v19.cross

import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.vector.ShapeStripInfo
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 Gate 2 cross test X4 (design 3.4; A9 x A4; I10 with both bars), in the real editor at 392
 * and 360 dp: under the Shape tool, a new shape pending on a vector layer, the Shape More row
 * offers "Duplicate", "Bring forward" and "Send backward" while no bar shows; with vector objects
 * selected the object bar shows and the More row, open, holds none of them, so no label shows
 * twice on screen (nor with the object bar's own row open). The shape stays open throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392)
class CrossObjectsBarsUiTest {
    private val objectLabels = listOf(LayerLabels19.DUPLICATE, ObjectEdits.Arrange.FORWARD.label, ObjectEdits.Arrange.BACKWARD.label)

    @Test
    fun withObjectsSelectedUnderShapeOnlyTheObjectBarOffersTheObjectItems() = Qa19Ui.uiTest { h ->
        h.section("392 dp") { check(h, Qa19Ui.Q392, 392f) }
        h.section("360 dp") { check(h, Qa19Ui.Q360, 360f) }
    }

    private fun settle() {
        Smoke.pump(300)
        Qa19Ui.settle()
    }

    /** A drag on the canvas from document point ([x0], [y0]) to ([x1], [y1]). */
    private fun drag(s: ChromeScreen, x0: Float, y0: Float, x1: Float, y1: Float) {
        val a = s.screen(x0, y0)
        val b = s.screen(x1, y1)
        s.touch.stroke(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)
        settle()
    }

    /** How many different controls on screen show [label]. */
    private fun controls(s: ChromeScreen, label: String): Int = Clickables.onScreen(s).count { label in it.labels }

    /** The object items' labels that two or more controls on screen show. */
    private fun shownTwice(s: ChromeScreen): Map<String, Any> = Clickables.duplicates(Clickables.onScreen(s)).filterKeys { it in objectLabels }

    private fun check(h: Qa19Harness, qualifiers: String, widthDp: Float) {
        val where = "${widthDp.toInt()} dp"
        RuntimeEnvironment.setQualifiers(qualifiers)
        lateinit var vec: Layer
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true)) { c ->
            c.snapping.enabled = false
            vec = c.addVectorLayer()!!
            val o = ShapeObject(ShapeType.RECTANGLE, cx = 100f, cy = 90f, w = 120f, h = 80f, style = ShapeStyle.STROKE, strokeWidth = 4f)
            c.vectors.addObjects(vec, listOf(VShape(0L, shape = o)), "Add")
            c.selectTool(ToolId.SHAPE)
        }
        settle()
        assertEquals("the phone is $where wide", widthDp, s.widthDp, 1f)
        val c = s.c
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        val placed = vec.vector!!.objects.single()

        // A new ellipse on the vector layer, pending: the object items apply to it.
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, strokeWith = ShapeStroke.PLAIN, useBrushSize = false, strokeWidth = 4f) }
        drag(s, 240f, 160f, 340f, 240f)
        assertTrue("$where: the new shape is pending", tool.hasPendingWork)
        assertTrue("$where: the object items apply", ShapeStripInfo.of(tool, c.doc.dpi).objectActions)

        // No bar: the More row shows each object item once (what the checks below would see).
        assertNull("$where: no bar yet", s.tagged(ChromeTags.SELECTION_BAR))
        assertTrue("$where: the More row opens", Qa19Ui.openMoreRow())
        for (label in objectLabels) assertEquals("$where, no bar: \"$label\" in the More row", 1, controls(s, label))
        Qa19Ui.closeMoreRow()

        // Objects selected under Shape: the object bar shows; the shape stays open.
        c.vectors.setSelection(vec, setOf(placed.id))
        settle()
        assertTrue("$where: the shape stays open", tool.hasPendingWork)
        assertNotNull("$where: the object bar shows", s.tagged(ChromeTags.SELECTION_BAR))
        assertEquals("$where: the bar's Duplicate", 1, controls(s, LayerLabels19.DUPLICATE))

        // The More row, open: none of the object items there, so no label shows twice.
        assertTrue("$where: the More row opens under the bar", Qa19Ui.openMoreRow())
        val row = StripTestKit.stripMoreRow() ?: throw AssertionError("$where: no More row")
        val inRow = Clickables.onScreen(s, inside = s.dp(row.boundsInWindow))
        assertTrue("$where: the More row shows its other items", inRow.isNotEmpty())
        assertTrue("$where: no object item in the More row: ${inRow.map { it.labels }}", inRow.none { it.labels.any { l -> l in objectLabels } })
        assertTrue("$where: shown twice with the More row open: ${shownTwice(s)}", shownTwice(s).isEmpty())
        assertEquals("$where: the bar's Duplicate only", 1, controls(s, LayerLabels19.DUPLICATE))

        // The object bar's own row instead (one row at a time): Forward and Backward there, once each.
        assertTrue("$where: the object bar's own row opens", Qa19Ui.openObjectRow())
        assertNull("$where: the More row closed", StripTestKit.stripMoreRow())
        assertTrue("$where: shown twice with the bar's row open: ${shownTwice(s)}", shownTwice(s).isEmpty())
        for (label in objectLabels) assertEquals("$where, the bar's row open: \"$label\" once", 1, controls(s, label))
        Qa19Ui.closeObjectRow()
        assertTrue("$where: the shape still open", tool.hasPendingWork)
    }
}
