package com.brushwork.paint.qa17

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.placement.MeshStepperLabels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 final QA, item 16 (design §3.16) on the user's 392 dp phone, on a photo-like layer: the
 * Transform tool's "Free deform" mode (v1.8 §3.9: in the mode dropdown); a finger drags a vertex
 * of the 3 × 3 mesh; "More mesh columns" / "More mesh rows" (v1.8: in the "Mesh size" popup, the
 * button showing "3 × 3") up to 8 × 8 and 12 × 12 keep the deformation (and stop at 12);
 * "Smooth mesh" on and off; "Select several", three vertices tapped, the gizmo's corner dragged
 * (ONE in-tool step: one "Undo" takes the whole gesture back, "Redo" puts it back); ✓ "Apply
 * transform edit" is ONE step "Free deform" and a two-finger tap takes it back exactly.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.freedeformsandbox"])
class Qa17FreeDeformUiTest {

    @Test
    fun freeDeformOnAPhotoAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("3 × 3, 8 × 8, 12 × 12, Smooth mesh, Select several and the gizmo, ✓ and a two-finger undo") { deform(Qa17ArrayUi(h)) }
        dog.interrupt()
        h.finish()
    }

    /** v1.8 (§3.9): opens the "Mesh size" popup (the "Mesh columns" / "Mesh rows" steppers). */
    private fun openMeshSize(u: Qa17ArrayUi) {
        u.press(TransformLabels18.MESH_SIZE)
        settle()
        assertTrue("the Mesh size popup", has(MeshStepperLabels.MORE_COLUMNS, exact = true))
    }

    /** Closes the open popup with Back. */
    private fun closePopup(u: Qa17ArrayUi) {
        u.ui.backKey()
        settle()
        assertFalse("the popup closed", has(MeshStepperLabels.MORE_COLUMNS, exact = true))
    }

    private fun near(msg: String, want: Vec2, got: Vec2, tol: Float) =
        assertTrue("$msg: want $want, got $got", want.distanceTo(got) <= tol)

    private fun deform(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val layer = c.activeLayer
        // A photo-like layer: soft gradients, a bright spot and a few hard edges, edge to edge.
        assertTrue(
            c.editWholeLayer(layer, "Seed") { b ->
                val cv = Canvas(b)
                val w = b.width.toFloat()
                val hh = b.height.toFloat()
                cv.drawRect(0f, 0f, w, hh, Paint().apply { shader = LinearGradient(0f, 0f, w, hh, Color.rgb(40, 90, 160), Color.rgb(230, 170, 90), Shader.TileMode.CLAMP) })
                cv.drawCircle(w * 0.62f, hh * 0.4f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(w * 0.62f, hh * 0.4f, 70f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP) })
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); strokeWidth = 3f }
                var x = 25f
                while (x < w) { cv.drawLine(x, 0f, x, hh, line); x += 50f }
            },
        )
        settle()
        val before = pixels(layer.bitmap)
        val n = u.steps()

        u.tool("Transform")
        val t = c.currentTool as TransformTool
        assertTrue("lifted", Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { settle(1); t.lifted != null })
        assertEquals(TransformTool.Lifted.PIXELS, t.lifted)
        // v1.8 (§3.9): the modes are in the mode dropdown, its button announced by the current mode.
        u.press(TransformTool.Mode.FREE.label)
        u.press(TransformLabels17.FREE_DEFORM)
        assertEquals(TransformTool.Mode.MESH, t.mode)
        assertTrue(t.isMeshShown)
        assertTrue("the Mesh size button shows the mesh", has("3 × 3", exact = true))
        openMeshSize(u)
        assertEquals("3", u.stateOf(TransformLabels17.COLUMNS))
        assertEquals("3", u.stateOf(TransformLabels17.ROWS))
        closePopup(u)
        assertEquals("a 3 × 3 mesh has 16 vertices", 16, t.pointCount)

        // A finger drags the vertex at a third across and a third down.
        val v = t.pointAt(5)
        near("vertex 5 at a third", Vec2(c.doc.width / 3f, c.doc.height / 3f), v, 1f)
        QaCurves.drag(u.s, v, Vec2(18f, 12f))
        val moved = v + Vec2(18f, 12f)
        near("the vertex followed the finger", moved, t.pointAt(5), 1.5f)
        assertTrue(t.isMeshChanged)
        assertEquals("nothing recorded yet", n, u.steps())
        assertTrue("Reset mesh is enabled", SmokeUi.isEnabled(TransformLabels17.RESET))

        // 8 × 8, then 12 × 12 (the steppers in the "Mesh size" popup, which stays open): the
        // deformation stays (the vertex at a third, a third is still moved).
        openMeshSize(u)
        repeat(5) { u.press(MeshStepperLabels.MORE_COLUMNS) }
        repeat(5) { u.press(MeshStepperLabels.MORE_ROWS) }
        assertEquals(8, t.meshColumns)
        assertEquals(8, t.meshRows)
        assertEquals("8", u.stateOf(TransformLabels17.COLUMNS))
        assertEquals(81, t.pointCount)
        assertTrue("still deformed at 8 × 8", t.isMeshChanged)
        repeat(4) { u.press(MeshStepperLabels.MORE_COLUMNS) }
        repeat(4) { u.press(MeshStepperLabels.MORE_ROWS) }
        assertEquals(12, t.meshColumns)
        assertEquals(12, t.meshRows)
        assertEquals(169, t.pointCount)
        assertFalse("12 is the most", SmokeUi.isEnabled(MeshStepperLabels.MORE_COLUMNS))
        assertFalse(SmokeUi.isEnabled(MeshStepperLabels.MORE_ROWS))
        assertTrue("still deformed at 12 × 12", t.isMeshChanged)
        near("the moved vertex is still where the finger left it", moved, t.pointAt(4 * 13 + 4), 3f)
        assertEquals("subdividing records nothing", n, u.steps())
        closePopup(u)
        assertTrue("the Mesh size button shows the mesh", has("12 × 12", exact = true))

        // "Smooth mesh" on and off: the mesh and its deformation stay.
        val smooth0 = t.smoothMesh
        val mesh0 = (0 until t.pointCount).map { t.pointAt(it) }
        u.press(TransformLabels17.SMOOTH)
        assertEquals(!smooth0, t.smoothMesh)
        assertNotNull("the preview shows", c.renderOverride)
        u.press(TransformLabels17.SMOOTH)
        assertEquals(smooth0, t.smoothMesh)
        assertEquals("the vertices stay", mesh0, (0 until t.pointCount).map { t.pointAt(it) })
        assertTrue(t.isMeshChanged)
        // ("Smooth mesh" is on the More row; closed again, it never takes a canvas touch, D25.)
        StripTestKit.closeMoreRow()
        settle()

        // "Select several" (v1.8, F4: the X / Y pill's cell): three vertices tapped; the gizmo's lower
        // right corner dragged outward.
        PillTestKit.toggleSeveral()
        assertTrue(t.selectSeveral)
        assertEquals("On", PillTestKit.severalState())
        val picked = listOf(8 * 13 + 6, 8 * 13 + 7, 8 * 13 + 8)
        for (i in picked) {
            val p = t.pointAt(i)
            u.ui.tap(p.x, p.y)
        }
        assertEquals("the three tapped vertices are selected", picked, t.pointSelection.indices)
        val all0 = (0 until t.pointCount).map { t.pointAt(it) }
        val layout = requireNotNull(PointGizmo().layout(picked.map { t.pointAt(it) }, c.viewTransform)) { "a gizmo over two or more" }
        val corner = layout.cornersScreen[2]
        val loc = IntArray(2).also { u.s.canvas.getLocationInWindow(it) }
        val x0 = loc[0] + corner.x
        val y0 = loc[1] + corner.y
        val d = 30f * u.s.density
        u.s.touch.idle(400)
        u.s.touch.send(MotionEvent.ACTION_DOWN, P(0, x0, y0))
        for (k in 1..10) {
            u.s.touch.idle(16)
            u.s.touch.send(MotionEvent.ACTION_MOVE, P(0, x0 + d * k / 10f, y0 + d * k / 10f))
        }
        u.s.touch.idle(16)
        u.s.touch.send(MotionEvent.ACTION_UP, P(0, x0 + d, y0 + d))
        settle(4)
        val all1 = (0 until t.pointCount).map { t.pointAt(it) }
        // (The corner scales about the box's centre: the outer two spread apart, the middle one stays.)
        val spread0 = all0[picked.first()].distanceTo(all0[picked.last()])
        val spread1 = all1[picked.first()].distanceTo(all1[picked.last()])
        assertTrue("the selection grew with the gizmo: $spread0 -> $spread1", spread1 > spread0 + 10f)
        for (i in listOf(picked.first(), picked.last())) assertTrue("selected vertex $i moved: ${all0[i]} -> ${all1[i]}", all0[i].distanceTo(all1[i]) > 5f)
        for (i in all0.indices) if (i !in picked) assertEquals("vertex $i stays", all0[i], all1[i])
        assertEquals("nothing recorded yet", n, u.steps())
        u.shot("free-deform-12x12")

        // I12: the gizmo gesture is ONE in-tool step.
        u.press("Undo")
        assertEquals("one Undo takes the whole gesture back", all0, (0 until t.pointCount).map { t.pointAt(it) })
        u.press("Redo")
        assertEquals("Redo puts it back", all1, (0 until t.pointCount).map { t.pointAt(it) })

        // ✓: ONE step "Free deform"; two fingers take it back exactly.
        u.applyEdit("Apply transform edit")
        assertEquals("one step", n + 1, u.steps())
        assertEquals(TransformLabels17.FREE_DEFORM, c.undoManager.undoLabel)
        assertFalse("the layer is deformed", before.contentEquals(pixels(layer.bitmap)))
        u.ui.twoFingerUndo()
        settle()
        assertEquals(n, u.steps())
        assertEquals(TransformLabels17.FREE_DEFORM, c.undoManager.redoLabel)
        assertArrayEquals("the photo as before", before, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "free deform")
    }
}
