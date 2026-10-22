package com.brushwork.paint.qa110

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.v110.a6.A6Paths
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectEdits
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 A6 (R10, J26, UQ10; FC6's first half): "Edit" on the object bar in the real editor on a
 * 392 dp and a 360 dp phone, at font scale 1, 1.3 and 2.0 (I27).
 *
 * One selected shape shows Edit on row 1 right after Transform, with no text cut: at 392 dp eight
 * buttons at 40.5 dp, Front and Back among them; at 360 dp six at 46 dp, Front and Back on the
 * More row. A tap on Edit opens the shape in the Shape tool (the object bar goes; no step); its ✓
 * ends the edit and the user stays in the Shape tool. A selected path opens in the Curve tool and
 * ✕ keeps the user there. A brush stroke shows no Edit, so Front and Back stay on row 1 at 360 dp
 * too.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.a6editsandbox"])
class Qa110EditUiTest {

    @Test
    fun editOnTheObjectBar() {
        try {
            Qa19Ui.uiTest { h ->
                for (width in listOf(392, 360)) {
                    RuntimeEnvironment.setQualifiers(if (width == 392) Qa19Ui.Q392 else Qa19Ui.Q360)
                    for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                        h.section("$width dp at font scale $scale") { edit(h, width, scale) }
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    /** A 400 × 300 document: a white layer and a vector layer (active). */
    private fun doc(): Document = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
    }

    private fun settle(s: ChromeScreen) {
        Smoke.pumpUntil(10_000) { !s.c.vectors.isRendering }
        SmokeUi.settle()
    }

    private fun edit(h: Qa19Harness, width: Int, scale: Float) {
        val where = "at $width dp, font scale $scale"
        val kit = ObjectTestKit(400, 300)
        try {
            var ids = emptyList<Long>()
            val s = h.editor(doc(), fontScale = scale) { c ->
                val shapes = listOf(kit.rectangle(110f, 90f, 120f, 80f), A6Paths.curve(), kit.stroke(220f, 240f, 360f, 270f))
                ids = c.vectors.addObjects(c.doc.layers[1], shapes, "Add")
                c.vectors.setSelection(c.doc.layers[1], setOf(ids[0]))
            }
            val c = s.c
            val layer = c.doc.layers[1]
            settle(s)

            // ---- one shape: Edit on row 1 right after Transform, nothing cut
            val bar = s.tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("no object bar $where")
            val transform = Finger.control(s, TRANSFORM) ?: throw AssertionError("no Transform $where")
            val edit = Finger.control(s, ObjectBarLabels110.EDIT_DESC) ?: throw AssertionError("no Edit $where")
            val deselect = Finger.control(s, DESELECT) ?: throw AssertionError("no Deselect $where")
            assertNotNull("tagged ${V110Tags.OBJECTS_EDIT}", s.tagged(V110Tags.OBJECTS_EDIT))
            assertEquals("Edit on row 1 $where", transform.top, edit.top, 0.5f)
            assertTrue("Edit right after Transform $where: $transform, $edit", transform.right <= edit.left + 0.5f && edit.right <= deselect.left + 0.5f)
            assertInside("Edit", edit, bar, where)
            if (width == 392) {
                assertEquals("eight buttons at 40.5 dp $where", 40.5f, edit.width, 0.5f)
                assertOnRow1(s, FRONT, transform, where)
                assertOnRow1(s, BACK, transform, where)
            } else {
                assertEquals("six buttons at 46 dp $where", 46f, edit.width, 0.5f)
                assertFalse("Front waits on the More row $where", SmokeUi.has(FRONT, exact = true))
                assertFalse("Back waits on the More row $where", SmokeUi.has(BACK, exact = true))
            }
            Qa19Ui.assertNoTextOverflow("with Edit on the object bar $where")

            // ---- a tap opens the shape in the Shape tool; ✓ ends it there
            val steps = c.undoManager.undoCount
            Finger.tap(s, ObjectBarLabels110.EDIT_DESC)
            val shapeTool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
            assertTrue("the shape opens $where", Smoke.pumpUntil(10_000) { shapeTool.editingObject })
            settle(s)
            assertEquals(ToolId.SHAPE, c.activeToolId)
            assertNull("the object bar goes while the shape is open $where", s.tagged(ChromeTags.SELECTION_BAR))
            assertTrue("the object selection is cleared", c.vectors.selectedIds.isEmpty())
            assertEquals("Edit adds no step", steps, c.undoManager.undoCount)
            Qa19Ui.assertNoTextOverflow("with the shape open $where")
            Finger.tap(s, applyLabel(ToolId.SHAPE))
            settle(s)
            assertFalse("✓ ends the edit $where", shapeTool.editingObject)
            assertEquals("the user stays in the Shape tool $where", ToolId.SHAPE, c.activeToolId)
            assertEquals("the same objects", ids, layer.vector?.objects?.map { it.id })

            // ---- a path opens in the Curve tool; ✕ keeps the user there
            c.vectors.setSelection(layer, setOf(ids[1]))
            settle(s)
            Finger.tap(s, ObjectBarLabels110.EDIT_DESC)
            val curveTool = c.tools.getValue(ToolId.CURVE) as CurveTool
            assertTrue("the path opens $where", Smoke.pumpUntil(10_000) { curveTool.isReopened })
            settle(s)
            assertEquals(ToolId.CURVE, c.activeToolId)
            Qa19Ui.assertNoTextOverflow("with the path open $where")
            Finger.tap(s, discardLabel(ToolId.CURVE))
            settle(s)
            assertFalse("✕ ends the edit $where", curveTool.isReopened)
            assertEquals("the user stays in the Curve tool $where", ToolId.CURVE, c.activeToolId)
            assertEquals("✕ adds no step", steps, c.undoManager.undoCount)

            // ---- a brush stroke: no Edit, Front and Back stay on row 1
            c.vectors.setSelection(layer, setOf(ids[2]))
            settle(s)
            assertNotNull("the object bar $where", s.tagged(ChromeTags.SELECTION_BAR))
            assertNull("no Edit for a brush stroke $where", s.tagged(V110Tags.OBJECTS_EDIT))
            val t2 = Finger.control(s, TRANSFORM) ?: throw AssertionError("no Transform $where")
            assertOnRow1(s, FRONT, t2, where)
            assertOnRow1(s, BACK, t2, where)
            Qa19Ui.assertNoTextOverflow("with a brush stroke selected $where")
            Smoke.assertQuiet(c, "Edit $where")
        } finally {
            kit.close()
        }
    }

    private fun assertInside(name: String, r: Rect, bar: Rect, where: String) =
        assertTrue("$name inside the bar $where: $r in $bar", r.left >= bar.left - 0.5f && r.right <= bar.right + 0.5f)

    private fun assertOnRow1(s: ChromeScreen, label: String, row1: Rect, where: String) {
        val r = Finger.control(s, label) ?: throw AssertionError("\"$label\" not shown $where")
        assertEquals("\"$label\" on row 1 $where", row1.top, r.top, 0.5f)
    }

    /** The ✓ of a tool's open edit (`PendingWorkBar`). */
    private fun applyLabel(tool: ToolId) = "Apply ${tool.label.lowercase()} edit"

    /** The ✕ of a tool's open edit (`PendingWorkBar`). */
    private fun discardLabel(tool: ToolId) = "Discard ${tool.label.lowercase()} edit"

    private companion object {
        // The object bar's click labels (VectorObjectBar; no constants of their own).
        const val TRANSFORM = "Transform the selected objects"
        const val DESELECT = "Deselect the objects"
        val FRONT = ObjectEdits.Arrange.FRONT.label
        val BACK = ObjectEdits.Arrange.BACK.label
    }
}
