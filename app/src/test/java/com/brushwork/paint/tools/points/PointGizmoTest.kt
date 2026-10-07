package com.brushwork.paint.tools.points

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.IncrementSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 1, design §3.1): the group gizmo: each handle's map, the 56 dp minimum box, the
 * rotate knob and increments.
 */
@RunWith(RobolectricTestRunner::class)
class PointGizmoTest {
    private val g = PointGizmo()

    /** Zoom 2 with an offset; density 2 (so 56 dp = 112 screen px = 56 document px). */
    private fun view(rotateDeg: Float = 0f): ViewTransform = ViewTransform().apply {
        density = 2f
        set(Matrix().apply { setScale(2f, 2f); postRotate(rotateDeg); postTranslate(30f, 40f) })
    }

    private fun near(e: Vec2, a: Vec2, tol: Float = 1e-3f) {
        assertEquals("x of $a", e.x, a.x, tol)
        assertEquals("y of $a", e.y, a.y, tol)
    }

    @Test
    fun layoutNeedsTwoPointsAndGrowsToTheMinimum() {
        val t = view()
        assertNull(g.layout(emptyList(), t))
        assertNull(g.layout(listOf(Vec2(1f, 1f)), t))
        // Two close points: the box is 56 x 56 document px (56 dp on screen) about their centre.
        val l = g.layout(listOf(Vec2(100f, 100f), Vec2(110f, 100f)), t)!!
        assertEquals(Vec2(105f, 100f), l.pivotDoc)
        near(t.docToScreen(Vec2(77f, 72f)), l.cornersScreen[0])
        near(t.docToScreen(Vec2(133f, 72f)), l.cornersScreen[1])
        near(t.docToScreen(Vec2(133f, 128f)), l.cornersScreen[2])
        near(t.docToScreen(Vec2(77f, 128f)), l.cornersScreen[3])
        assertEquals(112f, l.cornersScreen[0].distanceTo(l.cornersScreen[1]), 1e-3f)
        // The knob is 36 dp beyond the top edge's middle.
        near(t.docToScreen(Vec2(105f, 72f)) + Vec2(0f, -72f), l.rotateHandleScreen)
        // A box bigger than the minimum is the points' bounds.
        val big = g.layout(listOf(Vec2(0f, 0f), Vec2(200f, 100f), Vec2(50f, 30f)), t)!!
        near(t.docToScreen(Vec2(0f, 0f)), big.cornersScreen[0])
        near(t.docToScreen(Vec2(200f, 100f)), big.cornersScreen[2])
        assertEquals(Vec2(100f, 50f), big.pivotDoc)
    }

    @Test
    fun hitFindsTheHandles() {
        val t = view()
        val l = g.layout(listOf(Vec2(0f, 0f), Vec2(200f, 100f)), t)!!
        val c = l.cornersScreen
        assertEquals(PointGizmo.Part.SCALE_NW, g.hit(l, c[0] + Vec2(10f, 10f), t))
        assertEquals(PointGizmo.Part.SCALE_NE, g.hit(l, c[1], t))
        assertEquals(PointGizmo.Part.SCALE_SE, g.hit(l, c[2], t))
        assertEquals(PointGizmo.Part.SCALE_SW, g.hit(l, c[3], t))
        assertEquals(PointGizmo.Part.SCALE_N, g.hit(l, (c[0] + c[1]) * 0.5f, t))
        assertEquals(PointGizmo.Part.SCALE_E, g.hit(l, (c[1] + c[2]) * 0.5f, t))
        assertEquals(PointGizmo.Part.SCALE_S, g.hit(l, (c[2] + c[3]) * 0.5f, t))
        assertEquals(PointGizmo.Part.SCALE_W, g.hit(l, (c[3] + c[0]) * 0.5f, t))
        assertEquals(PointGizmo.Part.ROTATE, g.hit(l, l.rotateHandleScreen + Vec2(20f, 0f), t))
        assertEquals(PointGizmo.Part.MOVE, g.hit(l, t.docToScreen(Vec2(60f, 60f)), t))
        assertEquals(PointGizmo.Part.NONE, g.hit(l, t.docToScreen(Vec2(-100f, 50f)), t))
        // 44 dp touch = 22 dp (44 px) around a handle.
        assertEquals(PointGizmo.Part.NONE, g.hit(l, c[0] + Vec2(-44.5f, 0f), t))
        // Under a rotated view the same document box is hit at its turned corners.
        val tr = view(90f)
        val lr = g.layout(listOf(Vec2(0f, 0f), Vec2(200f, 100f)), tr)!!
        assertEquals(PointGizmo.Part.SCALE_NW, g.hit(lr, tr.docToScreen(Vec2(0f, 0f)), tr))
        assertEquals(PointGizmo.Part.MOVE, g.hit(lr, tr.docToScreen(Vec2(60f, 60f)), tr))
    }

    @Test
    fun eachHandleMapsAboutTheCentre() {
        val t = view()
        val l = g.layout(listOf(Vec2(0f, 0f), Vec2(200f, 100f)), t)!!
        val p = l.pivotDoc
        // An edge: one axis.
        val e = g.dragMap(l, PointGizmo.Part.SCALE_E, Vec2(200f, 50f), Vec2(300f, 70f), true, null)
        near(Vec2(300f, 100f), e.map(Vec2(200f, 100f)))
        near(p, e.map(p))
        val n = g.dragMap(l, PointGizmo.Part.SCALE_N, Vec2(100f, 0f), Vec2(120f, -50f), true, null)
        near(Vec2(0f, -50f), n.map(Vec2(0f, 0f)))
        near(Vec2(200f, 150f), n.map(Vec2(200f, 100f)))
        // A corner, proportional: the progress along the diagonal.
        val se = g.dragMap(l, PointGizmo.Part.SCALE_SE, Vec2(200f, 100f), Vec2(300f, 150f), true, null)
        near(Vec2(300f, 150f), se.map(Vec2(200f, 100f)))
        near(Vec2(-100f, -50f), se.map(Vec2(0f, 0f)))
        // A corner, free: each axis.
        val free = g.dragMap(l, PointGizmo.Part.SCALE_NW, Vec2(0f, 0f), Vec2(-100f, 25f), false, null)
        near(Vec2(-100f, 25f), free.map(Vec2(0f, 0f)))
        near(Vec2(300f, 75f), free.map(Vec2(200f, 100f)))
        // Rotate: a quarter turn about the centre.
        val r = g.dragMap(l, PointGizmo.Part.ROTATE, Vec2(100f, -20f), Vec2(170f, 50f), true, null)
        near(Vec2(100f, -50f), r.map(Vec2(0f, 50f)))
        // Move.
        assertEquals(Affine2.translate(12f, -3f), g.dragMap(l, PointGizmo.Part.MOVE, Vec2(10f, 10f), Vec2(22f, 7f), true, null))
        assertEquals(Affine2.IDENTITY, g.dragMap(l, PointGizmo.Part.NONE, Vec2(10f, 10f), Vec2(22f, 7f), true, null))
        // A finger that starts on the pivot's axis cannot scale that axis.
        assertEquals(Affine2.IDENTITY, g.dragMap(l, PointGizmo.Part.SCALE_E, Vec2(100f, 50f), Vec2(150f, 50f), true, null))
    }

    @Test
    fun incrementsStepMovesScalesAndAngles() {
        val t = view()
        val l = g.layout(listOf(Vec2(0f, 0f), Vec2(200f, 100f)), t)!!
        val on = IncrementSettings(enabled = true, lengthPx = 10f, scalePercent = 10f, angleDeg = 15f)
        assertEquals(Affine2.translate(10f, -10f), g.dragMap(l, PointGizmo.Part.MOVE, Vec2(0f, 0f), Vec2(13f, -7f), true, on))
        // ×1.23 snaps to ×1.2.
        val e = g.dragMap(l, PointGizmo.Part.SCALE_E, Vec2(200f, 50f), Vec2(223f, 50f), true, on)
        near(Vec2(220f, 50f), e.map(Vec2(200f, 50f)))
        // A mirrored ×-0.96 snaps to ×-1.
        val m = g.dragMap(l, PointGizmo.Part.SCALE_E, Vec2(200f, 50f), Vec2(4f, 50f), true, on)
        near(Vec2(0f, 50f), m.map(Vec2(200f, 50f)))
        // 50° snaps to 45°.
        val p = l.pivotDoc
        val start = p + Vec2(0f, -70f)
        val now = p + Vec2(70f, 0f).rotated(Math.toRadians(-40.0).toFloat())
        val r = g.dragMap(l, PointGizmo.Part.ROTATE, start, now, true, on)
        near(p + Vec2(0f, -10f).rotated(Math.toRadians(45.0).toFloat()), r.map(p + Vec2(0f, -10f)))
        // Increments off (or null): exact.
        assertEquals(Affine2.translate(13f, -7f), g.dragMap(l, PointGizmo.Part.MOVE, Vec2(0f, 0f), Vec2(13f, -7f), true, on.copy(enabled = false)))
        assertEquals(Affine2.translate(13f, -7f), g.dragMap(l, PointGizmo.Part.MOVE, Vec2(0f, 0f), Vec2(13f, -7f), true, null))
    }

    @Test
    fun drawsTheBoxAndHandles() {
        val t = view()
        val l = g.layout(listOf(Vec2(20f, 30f), Vec2(120f, 90f)), t)!!
        val bmp = Bitmap.createBitmap(320, 260, Bitmap.Config.ARGB_8888)
        g.draw(Canvas(bmp), l, t, PointGizmo.Part.SCALE_SE)
        val c = l.cornersScreen[2]
        assertNotEquals(0, bmp.getPixel(c.x.toInt(), c.y.toInt()))
        val k = l.rotateHandleScreen
        assertNotEquals(0, bmp.getPixel(k.x.toInt(), k.y.toInt()))
        bmp.recycle()
    }
}
