package com.brushwork.paint.v110.a1

import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.translate
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 (R1 (c)): tap to box of one side at 256 copies (the most a map list holds): from the tap
 * on a copy of an Array of 256 boxes to that side lifted in the Transform tool (its box shown).
 * `CopyPick.hitCopy` inverts at most 255 maps and the hole renders only the side's tiles, so the
 * pick stays within PC 16 ms (the T606's 100 ms over the x 6 proxy; x 4 on CI): the median of 15
 * taps after 3 warm-ups, each tap a new tap cycle (the tool chosen again). The tap is on copy 1,
 * the last one the top-down search reaches. On a 512 x 512 document everywhere, and on the
 * design's 2048 x 2048 vector layer locally only (a T606-size document).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class SidePickTimingTest {

    private fun median(v: DoubleArray): Double = v.sorted().let { it[it.size / 2] }

    /**
     * An Array of 256 boxes of [box] px on a [side] px square vector layer, [step] px apart (16 x
     * 16), as one object; the median ms of [count] taps on copy 1 after [warm] warm-ups.
     */
    private fun tapToBox(side: Int, box: Float, step: Float, count: Int = 15, warm: Int = 3): Double {
        val kit = ObjectTestKit(side, side)
        try {
            val c = kit.controller()
            val maps = ArrayList<FloatArray>()
            maps += identity()
            for (j in 0 until 16) for (i in 0 until 16) if (i != 0 || j != 0) maps += translate(i * step, j * step)
            val o = withMaps(kit.box(8f, 8f, 8f + box, 8f + box), maps)
            c.vectors.addObjects(c.vec, listOf(o), "Add")
            assertTrue(kit.idleUntil { !c.vectors.isRendering })
            val added = c.vec.vector!!.objects.single()
            assertEquals("256 copies", 256, ObjectCopies.of(added).maps.size)
            val b = CopyPick.boundsOf(added, 1)
            val ms = DoubleArray(count)
            for (k in 0 until warm + count) {
                val t = pick(kit, c, b.centerX(), b.centerY(), 1)
                if (k >= warm) ms[k - warm] = t
            }
            return median(ms)
        } finally {
            kit.close()
        }
    }

    /** One tap on copy [k] at ([x], [y]) with a new tap cycle: ms from the tap to that side lifted; then ✕. */
    private fun pick(kit: ObjectTestKit, c: EditorController, x: Float, y: Float, k: Int): Double {
        c.selectTool(ToolId.BRUSH)
        val tool = kit.transform(c)
        assertTrue("settled before the tap", kit.idleUntil { !c.vectors.isRendering && !tool.isPreparing })
        val looper = shadowOf(Looper.getMainLooper())
        val t0 = System.nanoTime()
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
        while (VectorLift.activeLift(c)?.side != k) {
            looper.idle()
            if (System.nanoTime() - t0 > 10_000_000_000L) fail("side $k never lifted")
        }
        val ms = (System.nanoTime() - t0) / 1e6
        tool.discard()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        return ms
    }

    @Test
    fun tapToBoxAt256CopiesOnA512Layer() {
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(16.0), what = "tap to box, 256 copies, 512 x 512") {
            tapToBox(512, box = 14f, step = 30f)
        }
        println("v110 A1 SidePickTimingTest: tap to box, 256 copies, 512 x 512: median %.3f ms of 15 after 3 warm-ups".format(ms))
    }

    @Test
    fun tapToBoxAt256CopiesOnA2048Layer() {
        assumeTrue("a T606-size document: locally only", System.getenv("CI") == null)
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(16.0), what = "tap to box, 256 copies, 2048 x 2048") {
            tapToBox(2048, box = 56f, step = 120f)
        }
        println("v110 A1 SidePickTimingTest: tap to box, 256 copies, 2048 x 2048: median %.3f ms of 15 after 3 warm-ups (x 6 = %.1f ms; device budget 100 ms)".format(ms, ms * 6))
    }
}
