package com.brushwork.paint.qa110

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.MirrorLabels110
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.v19.a4.A4Kit
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.lift.CopyLift
import com.brushwork.paint.vector.lift.VectorLift
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
 * v1.10 QA (R1, A1; I27): one mirror side in Transform at 392 and 360 dp and at each font scale.
 * A finger tap on the mirrored copy of a box on the canvas picks that side alone: the hint
 * [MirrorLabels110.SIDE_HINT] shows whole, the pill's Scale row is gone (LA4: one side is only
 * moved, turned and flipped), the mode menu names Distort and Free deform
 * [MirrorLabels110.SIDE_RESIZE_BLOCKED], and the pill's trash is [MirrorLabels110.DELETE_SIDE].
 * A tap on the same spot lifts all sides (the Scale row is back, no trash). Picked again, the
 * trash deletes that side alone as one step: the box is plain where its source was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392)
class Qa110OneSideUiTest {

    /** The box at (60, 110)-(140, 170) mirrored across x = 200 (its copy at 260-340). */
    private fun mirrored(kit: A4Kit) = ObjectCopies.withCopies(
        kit.box(60f, 110f, 140f, 170f),
        CopyMaps.of(listOf(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(-1f, 0f, 400f, 0f, 1f, 0f, 0f, 0f, 1f))),
    )

    /** A finger tap at document point ([x], [y]) on the canvas. */
    private fun tapDoc(s: ChromeScreen, x: Float, y: Float) {
        val (sx, sy) = s.screen(x, y)
        s.touch.tap(sx, sy)
        Qa19Ui.settle()
    }

    private fun trashLabel(): String? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) == V17Tags.PILL_TRASH
    }?.node?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    /** The state description of [n] or of the nearest ancestor that has one (a menu row's text sits inside the row). */
    private fun stateOf(n: SemanticsNode): String? {
        var e: SemanticsNode? = n
        while (e != null) {
            e.config.getOrNull(SemanticsProperties.StateDescription)?.let { return it }
            e = e.parent
        }
        return null
    }

    /** Transform chosen again (a new tap cycle), its activation lift settled. */
    private fun transform(c: EditorController): TransformTool {
        c.selectTool(ToolId.BRUSH)
        Qa19Ui.settle()
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("Transform lifts", Smoke.pumpUntil { Qa19Ui.settle(1); tool.transformState != null && !c.vectors.isRendering })
        return tool
    }

    private fun pickSide(c: EditorController, s: ChromeScreen, where: String) {
        tapDoc(s, 300f, 140f)
        assertTrue("$where: the side is picked", Smoke.pumpUntil { Qa19Ui.settle(1); (VectorLift.activeLift(c) as? CopyLift)?.side == 1 })
        Qa19Ui.settle()
    }

    @Test
    fun oneSideInTransform() = Qa19Ui.uiTest { h ->
        val kit = A4Kit(400, 300)
        for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val where = "at ${width.substringBefore('-')} and font scale $scale"
            h.section(where) {
                RuntimeEnvironment.setQualifiers(width)
                lateinit var layer: Layer
                val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true), fontScale = scale) { c ->
                    layer = kit.vectorLayer(c, listOf(mirrored(kit)), "Mirrored")
                }
                val c = s.c
                assertEquals(where, if (width == Qa19Ui.Q360) 360 else 392, s.activity.resources.configuration.screenWidthDp)
                var tool = transform(c)
                assertNotNull("$where: all sides lifted on activation: the Scale row", s.tagged(V17Tags.PILL_SCALE_ROW))

                // The first tap on the copy: that side alone.
                pickSide(c, s, where)
                assertTrue("$where: the hint", Qa19Ui.has(MirrorLabels110.SIDE_HINT, exact = true))
                assertTrue("$where: a side is picked", tool.sidePicked)
                assertNull("$where: no Scale row for one side", s.tagged(V17Tags.PILL_SCALE_ROW))
                val trash = s.tagged(V17Tags.PILL_TRASH) ?: throw AssertionError("$where: no trash; shown ${Qa19Ui.shown().take(60)}")
                assertEquals("$where: the trash's name", MirrorLabels110.DELETE_SIDE, trashLabel())
                assertTrue("$where: the trash $trash within the screen ${s.widthDp} dp", trash.left >= -0.5f && trash.right <= s.widthDp + 0.5f)
                Qa19Ui.assertNoTextOverflow("$where, one side picked")

                // The mode menu: Distort is refused with the reason, shown whole.
                Qa19Ui.click(TransformTool.Mode.FREE.label, exact = true)
                val distort = Qa19Ui.find(TransformTool.Mode.DISTORT.label, exact = true) ?: throw AssertionError("$where: no Distort in the mode menu")
                assertEquals("$where: Distort refused", MirrorLabels110.SIDE_RESIZE_BLOCKED, stateOf(distort.node))
                Qa19Ui.click(TransformTool.Mode.DISTORT.label, exact = true)
                assertTrue("$where: the reason shows", Qa19Ui.has(MirrorLabels110.SIDE_RESIZE_BLOCKED, exact = true))
                assertEquals("$where: still Free", TransformTool.Mode.FREE, tool.mode)
                Qa19Ui.assertNoTextOverflow("$where, the mode menu")
                Finger.back()
                Qa19Ui.settle()

                // The same spot again: all sides, as v1.9 (the Scale row back, no trash).
                tapDoc(s, 300f, 140f)
                assertTrue("$where: all sides", Smoke.pumpUntil { Qa19Ui.settle(1); VectorLift.activeLift(c).let { it != null && it !is CopyLift } })
                Qa19Ui.settle()
                assertNotNull("$where: all sides: the Scale row", s.tagged(V17Tags.PILL_SCALE_ROW))
                assertNull("$where: all sides: no trash", s.tagged(V17Tags.PILL_TRASH))

                // Picked again (a new cycle), the trash deletes that side alone: one step.
                tool = transform(c)
                pickSide(c, s, where)
                val steps = c.undoManager.undoCount
                val r = s.tagged(V17Tags.PILL_TRASH) ?: throw AssertionError("$where: no trash the second time")
                s.touch.idle(300)
                Finger.tapAt(s, r.center.x, r.center.y)
                assertTrue("$where: deleted", Smoke.pumpUntil { Qa19Ui.settle(1); c.undoManager.undoCount == steps + 1 && !c.vectors.isRendering })
                assertEquals(where, MirrorLabels110.DELETE_SIDE, c.undoManager.undoLabel)
                val o = layer.vector!!.objects.single()
                assertTrue("$where: plain", ObjectCopies.of(o).isEmpty)
                assertTrue("$where: where its source was", com.brushwork.paint.vector.VectorOps.bounds(o).left < 100f)
                assertTrue("$where: no side picked", !tool.sidePicked)
            }
        }
    }
}
