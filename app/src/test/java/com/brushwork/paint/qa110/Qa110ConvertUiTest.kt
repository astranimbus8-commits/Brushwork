package com.brushwork.paint.qa110

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.v110.a6.A6Paths
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectEdits
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
 * v1.10 A6 (R11 "Convert with a warning", finding 31; FC6's second half): "Turn into shape" in the
 * real editor on a 392 dp and a 360 dp phone, at font scale 1, 1.3 and 2.0 (I27).
 *
 * One selected path with a gradient fill and mirror copies: the object bar's More row shows "Turn
 * into shape" after Detach and as wide as it (at 360 dp, where Front and Back join the More row
 * and Behind follows Array (A3), Detach starts the row's second line), with no text cut. A tap
 * turns the path into a
 * shape (the same id, one step "Turn into shape") open in the Shape tool, and the note "Gradient
 * fill became its first color" shows with "Undo" and a dismiss action; a later tool message waits
 * behind it. Its Undo drops the open shape and takes the step back: the path returns, as one Undo
 * does.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.a6convertsandbox"])
class Qa110ConvertUiTest {

    @Test
    fun turnIntoShapeFromTheMoreRow() {
        try {
            Qa19Ui.uiTest { h ->
                for (width in listOf(392, 360)) {
                    RuntimeEnvironment.setQualifiers(if (width == 392) Qa19Ui.Q392 else Qa19Ui.Q360)
                    for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                        h.section("$width dp at font scale $scale") { convert(h, width, scale) }
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

    /** A closed curve with a gradient fill and a mirror copy: one note, and Detach on the More row. */
    private fun path(): VPath = A6Paths.curve(closed = true).copy(
        fill = VPaint.Linear(60f, 0f, 180f, 0f, listOf(VStop(0f, RED), VStop(1f, BLUE))),
        copies = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), 400, 300)),
    )

    private fun settle(s: ChromeScreen) {
        Smoke.pumpUntil(10_000) { !s.c.vectors.isRendering }
        SmokeUi.settle()
    }

    private fun convert(h: Qa19Harness, width: Int, scale: Float) {
        val where = "at $width dp, font scale $scale"
        var id = 0L
        val s = h.editor(doc(), fontScale = scale) { c ->
            id = c.vectors.addObjects(c.doc.layers[1], listOf(path()), "Add").single()
            c.vectors.setSelection(c.doc.layers[1], setOf(id))
        }
        val c = s.c
        val layer = c.doc.layers[1]
        settle(s)
        val stored = layer.vector!!.byId(id) as VPath

        // ---- the More row: Turn into shape right after Detach, as wide as it
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        settle(s)
        val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("no More row $where")
        val detach = Finger.control(s, MirrorLabels18.DETACH) ?: throw AssertionError("no Detach $where")
        val toShape = Finger.control(s, ObjectBarLabels110.TO_SHAPE) ?: throw AssertionError("no Turn into shape $where")
        assertNotNull("tagged ${V110Tags.OBJECTS_TO_SHAPE}", s.tagged(V110Tags.OBJECTS_TO_SHAPE))
        assertTrue("on the More row $where: $toShape in $more", more.contains(toShape.center))
        assertEquals("as wide as Detach $where", detach.width, toShape.width, 0.5f)
        if (width == 392) {
            // Forward, Backward, Array, Behind, Detach (2), Turn into shape (2): one line.
            assertEquals("on Detach's line $where", detach.top, toShape.top, 0.5f)
            assertTrue("right after Detach $where: $detach, $toShape", detach.right <= toShape.left + 0.5f)
        } else {
            // Front and Back are on the More row too, and Behind after Array: Detach starts the second
            // line, with Turn into shape right after it.
            assertEquals("on Detach's line, the second $where: $detach, $toShape", detach.top, toShape.top, 0.5f)
            assertTrue("right after Detach $where: $detach, $toShape", detach.right <= toShape.left + 0.5f)
            val forward = Finger.control(s, ObjectEdits.Arrange.FORWARD.label) ?: throw AssertionError("no Forward $where")
            assertEquals("Detach at the line's start, under Forward $where", forward.left, detach.left, 0.5f)
            assertTrue("under Forward's line $where", forward.bottom <= detach.top + 0.5f)
        }
        Qa19Ui.assertNoTextOverflow("with the More row open $where")

        // ---- a tap: the shape, open in the Shape tool, and the note with Undo
        val undoTexts = texts().count { it == UNDO }
        val steps = c.undoManager.undoCount
        Finger.tap(s, ObjectBarLabels110.TO_SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue("the shape opens $where", Smoke.pumpUntil(10_000) { tool.editingObject })
        settle(s)
        assertEquals(ToolId.SHAPE, c.activeToolId)
        assertTrue("the same id, now a shape $where", layer.vector!!.byId(id) is VShape)
        assertEquals("one step $where", steps + 1, c.undoManager.undoCount)
        assertEquals(ObjectBarLabels110.TO_SHAPE, c.undoManager.undoLabel)
        assertNull("the screen took the note", c.pendingNote)
        assertTrue("the note shows $where: ${texts()}", ObjectBarLabels110.NOTE_GRADIENT in texts())
        assertEquals("the note has Undo $where", undoTexts + 1, texts().count { it == UNDO })
        assertTrue("the note has a dismiss action $where", hasDescription(DISMISS))
        Qa19Ui.assertNoTextOverflow("with the note $where")

        // A later tool message waits behind it.
        c.toast(LATER)
        SmokeUi.settle()
        assertTrue("the note stays $where", ObjectBarLabels110.NOTE_GRADIENT in texts())
        assertFalse("the later message waits $where", LATER in texts())

        // ---- its Undo drops the open shape and takes the step back
        click(textNode(UNDO))
        settle(s)
        assertEquals("the step is taken back $where", steps, c.undoManager.undoCount)
        assertEquals("the path is back $where", stored, layer.vector!!.byId(id))
        assertFalse("the open shape is dropped $where", tool.hasPendingWork)
        assertFalse("the note is gone $where", ObjectBarLabels110.NOTE_GRADIENT in texts())
        Smoke.assertQuiet(c, "Turn into shape $where")
    }

    private fun texts(): List<String> =
        RobolectricUi.elements().flatMap { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() }

    private fun hasDescription(d: String): Boolean =
        RobolectricUi.elements().any { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true }

    /** The newest node showing the text [t] (the note's action is above the editor's). */
    private fun textNode(t: String): SemanticsNode =
        RobolectricUi.elements().last { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == t } == true }.node

    private fun click(node: SemanticsNode) {
        var n: SemanticsNode? = node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        requireNotNull(n?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "not clickable" }.invoke()
    }

    private companion object {
        // The note's action and the snackbar's dismiss action (EditorScreen's NoteVisuals; no constants).
        const val UNDO = "Undo"
        const val DISMISS = "Dismiss"
        const val LATER = "A later tool message"
        const val RED = 0xFFE04020.toInt()
        const val BLUE = 0xFF203080.toInt()
    }
}
