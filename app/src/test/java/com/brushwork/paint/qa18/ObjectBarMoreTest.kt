package com.brushwork.paint.qa18

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (design §3.9): the vector object bar on the user's phone (392 × 873 dp) keeps one row that
 * never scrolls: the most used seven (Delete, Duplicate, Front, Back, Recolor, Transform,
 * Deselect), finger-sized, in their v1.6 order, then "Show more object actions"
 * ([StripLabels18.MORE_OBJECT]). The toggle drops the More row under the bar with Forward,
 * Backward, Array and, while a selected object has mirror copies, "Detach mirror copies" (twice as
 * wide). The row stays open while its buttons act. v1.9 (R3, design 2.6): only the toggle closes
 * it. A canvas touch is never consumed and leaves it open (one finger paints, a pinch zooms, a
 * two- or three-finger tap undoes or redoes once), and so do a tool change and the More menu; when
 * the bar goes (Deselect) its row goes along, remembered, and nothing open takes the next touch;
 * the next selection shows it again.
 *
 * v1.10 (A6, R10, R11): the one selected path also shows [ObjectBarLabels110.EDIT] after Transform
 * (row 1: eight buttons at 40.5 dp) and, on the More row after Detach, [ObjectBarLabels110.TO_SHAPE]
 * (as wide as Detach).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.objectbarsandbox"])
class ObjectBarMoreTest {

    private val row1 = listOf(
        "Delete the selected objects", "Duplicate the selected objects", "Bring to front", "Send to back",
        "Recolor with the main color", "Transform the selected objects", ObjectBarLabels110.EDIT_DESC, "Deselect the objects",
    )

    private fun doc(): Document = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
    }

    private fun expanded(s: ChromeScreen): Boolean = when (Fingers18.state(s, StripLabels18.MORE_OBJECT)) {
        StripLabels18.EXPANDED -> true
        StripLabels18.COLLAPSED -> false
        else -> throw AssertionError("no \"${StripLabels18.MORE_OBJECT}\" with a state")
    }

    private fun open(s: ChromeScreen) {
        assertFalse(expanded(s))
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        assertTrue("the toggle opens the More row", expanded(s))
        assertNotNull(s.tagged(V18Tags.STRIP_MORE_ROW))
    }

    private fun assertClosed(s: ChromeScreen, why: String) {
        assertFalse("$why: closed", expanded(s))
        assertNull("$why: no More row", s.tagged(V18Tags.STRIP_MORE_ROW))
    }

    private fun assertOpen(s: ChromeScreen, why: String) {
        Smoke.pump(300); SmokeUi.settle()
        assertTrue("$why: the row stays open", expanded(s))
        assertNotNull("$why: its buttons show", Finger.control(s, "Bring forward"))
    }

    @Test
    fun oneRowAndAMoreRowThatClosesOnlyByItsToggle() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        val kit = ObjectTestKit(400, 300)
        h.section("the object bar at 392 dp") { at392(h, kit) }
        kit.close()
        dog.interrupt()
        h.finish()
    }

    private fun at392(h: ChromeHarness, kit: ObjectTestKit) {
        val s = h.editor(doc()) { c ->
            val layer = c.doc.layers[1]
            val mirror = CopyMaps.of(SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), 400, 300))
            c.vectors.addObjects(layer, listOf(ObjectCopies.withCopies(kit.box(40f, 40f, 120f, 110f), mirror), kit.stroke(60f, 200f, 200f, 230f)), "Add")
            c.vectors.setSelection(layer, setOf(1L))
        }
        val c = s.c
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        SmokeUi.settle()
        val bar = s.tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("no object bar")

        // ---- row 1: the most used seven, finger-sized, in their v1.6 order, then the toggle
        val cells = row1.map { Finger.control(s, it) ?: throw AssertionError("\"$it\" not on row 1") }
        for ((label, r) in row1.zip(cells)) {
            assertTrue("\"$label\" finger-sized: $r", r.width >= 40f - 0.5f && r.height >= 46f - 0.5f)
            assertTrue("\"$label\" inside the bar", r.left >= bar.left - 0.5f && r.right <= bar.right + 0.5f)
            assertEquals("\"$label\" on row 1", cells[0].top, r.top, 0.5f)
        }
        for (i in 1 until cells.size) assertTrue("left to right", cells[i - 1].right <= cells[i].left + 0.5f)
        val toggle = Finger.control(s, StripLabels18.MORE_OBJECT) ?: throw AssertionError("no toggle")
        assertEquals("the toggle is 44 dp", 44f, toggle.width, 0.5f)
        assertTrue("the toggle ends row 1", toggle.left >= cells.last().right - 0.5f && toggle.right <= bar.right + 0.5f)
        assertTrue("the bar fits the screen: $bar", bar.left >= -0.5f && bar.right <= s.widthDp + 0.5f)
        val delete = s.placed().last { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == row1[0] }.node
        assertNull("row 1 never scrolls", StripTestKit.horizontalScroller(delete))
        assertFalse(expanded(s))
        assertNull(s.tagged(V18Tags.STRIP_MORE_ROW))
        for (gone in listOf("Bring forward", "Send backward", ArrayLabels.FROM_OBJECTS, MirrorLabels18.DETACH, ObjectBarLabels110.TO_SHAPE)) assertFalse("\"$gone\" waits on the More row", SmokeUi.has(gone, exact = true))

        // ---- the More row: Forward, Backward, Array, Detach and Turn into shape under the bar, as wide as it
        open(s)
        val more = s.tagged(V18Tags.STRIP_MORE_ROW)!!
        assertEquals("4 dp under row 1 (its 2 dp padding)", cells[0].bottom + 2f + 4f, more.top, 1f)
        assertEquals("as wide as the bar", bar.width, more.width, 1f)
        val forward = Finger.control(s, "Bring forward")!!
        val backward = Finger.control(s, "Send backward")!!
        val array = Finger.control(s, ArrayLabels.FROM_OBJECTS)!!
        val detach = Finger.control(s, MirrorLabels18.DETACH)!!
        val toShape = Finger.control(s, ObjectBarLabels110.TO_SHAPE)!!
        for ((name, r) in listOf("Forward" to forward, "Backward" to backward, "Array" to array, "Detach" to detach, ObjectBarLabels110.TO_SHAPE to toShape)) {
            assertTrue("$name on the More row: $r in $more", more.contains(r.center) && r.height >= 46f - 0.5f)
            assertEquals("$name as tall as a row-1 button", cells[0].height, r.height, 0.5f)
        }
        assertEquals("the More row's buttons as wide as row 1's", cells[0].width, forward.width, 0.5f)
        assertEquals("Detach twice as wide", 2f * forward.width, detach.width, 0.5f)
        assertEquals("Turn into shape as wide as Detach", detach.width, toShape.width, 0.5f)
        assertTrue(
            "in order",
            forward.right <= backward.left + 0.5f && backward.right <= array.left + 0.5f && array.right <= detach.left + 0.5f &&
                detach.right <= toShape.left + 0.5f,
        )
        assertTrue("the bar's slot takes the More row in (what sits under the bar moves down)", s.tagged(ChromeTags.SELECTION_BAR)!!.bottom >= more.bottom - 0.5f)

        // A button of the More row acts and the row stays open (a strip's More row does too).
        val steps = c.undoManager.undoCount
        Finger.tap(s, MirrorLabels18.DETACH)
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        SmokeUi.settle()
        assertEquals("Detach is one step", steps + 1, c.undoManager.undoCount)
        assertTrue("the row stays open", expanded(s))
        assertFalse("nothing left to detach", SmokeUi.has(MirrorLabels18.DETACH, exact = true))
        // (The pieces are selected: several objects, so neither Edit nor Turn into shape.)
        assertFalse(SmokeUi.has(ObjectBarLabels110.TO_SHAPE, exact = true))
        assertFalse(SmokeUi.has(ObjectBarLabels110.EDIT_DESC, exact = true))
        assertNotNull(Finger.control(s, "Bring forward"))

        // ---- v1.9 (R3): a canvas touch never closes the row and is never consumed: a pinch
        // zooms, a two- or three-finger tap undoes or redoes once, one finger reaches the tool
        var before = Fingers18.canvasState(s)
        Fingers18.pinch(s, 200f, 150f)
        val zoomed = Fingers18.canvasState(s)
        assertNotEquals("a pinch zooms", before.zoom, zoomed.zoom)
        assertEquals("…and does nothing else", before.copy(zoom = zoomed.zoom), zoomed)
        assertOpen(s, "a pinch")
        before = zoomed
        Fingers18.twoFingerTap(s, 200f, 150f)
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        SmokeUi.settle()
        val undone = Fingers18.canvasState(s)
        assertEquals("a two-finger tap: one undo (Detach)", before.undo - 1, undone.undo)
        assertTrue("…to redo", undone.redo)
        assertEquals("…the selection as before Detach (its copy gone)", setOf(1L), undone.objects)
        assertOpen(s, "a two-finger tap")
        Fingers18.threeFingerTap(s, 200f, 150f)
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        SmokeUi.settle()
        assertEquals("a three-finger tap: one redo (Detach)", before, Fingers18.canvasState(s))
        assertOpen(s, "a three-finger tap")
        Fingers18.stroke(s, 190f, 140f, 215f, 160f)
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        SmokeUi.settle()
        val stroked = Fingers18.canvasState(s)
        assertNotEquals("one finger: the brush paints", before.pixels, stroked.pixels)
        assertEquals("…one step", before.undo + 1, stroked.undo)
        assertEquals("…the selection kept", before.objects, stroked.objects)
        assertOpen(s, "a one-finger stroke")

        // ---- a tool change and the More menu leave it open (one memory for the bar's row)
        Finger.tap(s, "Switch to eraser")
        assertEquals(ToolId.ERASER, c.activeToolId)
        assertOpen(s, "a tool change")
        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals("the More menu drops", 2, SmokeUi.windows().size)
        assertOpen(s, "the More menu")
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)
        assertOpen(s, "the More menu closed")

        // ---- Deselect (row 1) while the More row is open: the bar goes and its row with it,
        // remembered; nothing open takes the next touch; a new selection shows the row again
        Finger.tap(s, "Deselect the objects")
        Smoke.pump(500); SmokeUi.settle()
        assertTrue("deselected", c.vectors.selectedIds.isEmpty())
        assertNull("the bar went", s.tagged(ChromeTags.SELECTION_BAR))
        assertNull(s.tagged(V18Tags.STRIP_MORE_ROW))
        val zoom = c.viewTransform.zoom
        Fingers18.pinch(s, 200f, 150f)
        assertNotEquals("the next pinch zooms", zoom, c.viewTransform.zoom)
        c.vectors.setSelection(c.doc.layers[1], setOf(1L))
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        assertOpen(s, "selected again: the remembered row")

        // ---- only the toggle closes it
        Finger.tap(s, StripLabels18.MORE_OBJECT)
        Smoke.pump(300); SmokeUi.settle()
        assertClosed(s, "the toggle")
        Smoke.assertQuiet(c, "object bar")
    }
}
