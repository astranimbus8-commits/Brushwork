package com.brushwork.paint.v110.a1

import android.graphics.Canvas
import android.graphics.Color
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.10 (R1 (c)): the edit preview of one side (`VectorLayers.beginEdit` with a side) floats that
 * copy alone, on its own bounds, and the hole shows the object without it, so the other sides stay
 * where they are drawn while it moves. Without a side the same object lifts whole, as in v1.9.
 */
@RunWith(RobolectricTestRunner::class)
class SideEditPlanTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun begin(c: EditorController, side: Int): VectorEditSession {
        var s: VectorEditSession? = null
        var done = false
        if (side < 0) c.vectors.beginEdit(c.vec, setOf(1L)) { s = it; done = true }
        else c.vectors.beginEdit(c.vec, setOf(1L), side) { s = it; done = true }
        assertTrue(kit.idleUntil { done })
        return checkNotNull(s) { "no session" }
    }

    @Test
    fun aSideFloatsAloneAndTheOtherSidesStayInTheHole() {
        val c = kit.controller()
        // The box at x 60..120 and its mirrored copy at x 392..452.
        c.vectors.addObjects(c.vec, listOf(withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))), "Add")
        assertTrue(kit.idleUntil { !c.vectors.isRendering })

        val side = begin(c, 1)
        val r = side.floatingRect
        assertTrue("the copy's box: $r", r.left in 380..392 && r.right in 452..466 && r.top in 88..100 && r.bottom in 140..152)
        val base = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        side.drawBase(Canvas(base))
        val px = kit.pixels(base)
        assertTrue("the source stays", Color.alpha(px[120 * kit.w + 90]) > 200)
        assertEquals("the copy left the hole", 0, Color.alpha(px[120 * kit.w + 422]))
        val f = side.floating!!
        val fx = ((422 - r.left) * side.floatingScale).toInt()
        val fy = ((120 - r.top) * side.floatingScale).toInt()
        assertTrue("the copy floats", Color.alpha(f.getPixel(fx, fy)) > 200)
        side.cancel()

        // Without a side: the whole object, both copies, as in v1.9.
        val whole = begin(c, -1)
        assertTrue("both sides: ${whole.floatingRect}", whole.floatingRect.left <= 60 && whole.floatingRect.right >= 452)
        whole.cancel()
    }

    /**
     * The picked side's preview is the copy itself (`CopyPick.side`: the source under map k, an
     * isometry): rendered alone it gives the copy's pixels exactly as the object draws them, for a
     * path and for shapes, under a mirror and a turn.
     */
    @Test
    fun theSidePreviewIsDrawnAsTheCopy() {
        val objects = listOf(kit.box(60f, 100f, 120f, 140f), kit.rectangle(90f, 120f, 60f, 40f), kit.ellipse(90f, 120f))
        for (base in objects) for (m in listOf(mirrorX(), A1Kit.turn(180.0))) {
            val o = withMaps(base, listOf(identity(), m))
            val whole = kit.render(VectorContent(objects = listOf(o)))
            val alone = kit.render(VectorContent(objects = listOf(CopyPick.side(o, 1)!!)))
            val box = CopyPick.boundsOf(o, 1)
            var differ = 0
            for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(kit.h))
                for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(kit.w)) {
                    val a = whole[y * kit.w + x]
                    val b = alone[y * kit.w + x]
                    if (abs(Color.alpha(a) - Color.alpha(b)) > 8) differ++
                }
            assertTrue("${base::class.simpleName} under ${m.toList()}: $differ pixels differ", differ <= 4)
        }
    }
}
