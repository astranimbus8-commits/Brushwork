package com.brushwork.paint.assist

import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.tools.ToolPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class StabilizerTest {
    private fun rope(length: Float = 50f, catchUp: Boolean = true, ruler: RulerSettings? = null) =
        StrokePipeline.Params(ruler = ruler, mode = StabilizerMode.ROPE, ropeLength = length, catchUp = catchUp, step = 2f)

    private fun smooth(lag: Float, catchUp: Boolean = true) =
        StrokePipeline.Params(mode = StabilizerMode.SMOOTH, smoothLag = lag, catchUp = catchUp, step = 2f)

    @Test
    fun ropePaintsNothingWhileFingerStaysWithinTheRope() {
        val p = StrokePipeline()
        p.down(ToolPoint(0f, 0f), rope(50f))
        for (q in listOf(ToolPoint(30f, 0f), ToolPoint(0f, 40f), ToolPoint(-20f, -30f), ToolPoint(35f, 35f), ToolPoint(49.9f, 0f))) {
            assertTrue(p.move(q).isEmpty())
        }
        assertEquals(0f, p.brushX, 0f)
        assertEquals(0f, p.brushY, 0f)
    }

    @Test
    fun ropeBrushTrailsByExactlyTheRopeLengthWhenPulled() {
        val p = StrokePipeline()
        p.down(ToolPoint(0f, 0f), rope(50f))
        val out = p.move(ToolPoint(200f, 0f, pressure = 0.4f, time = 16L))
        assertTrue(out.size > 10)
        // Points are subdivided (~2 px) along the old-B -> new-B segment.
        var prevX = 0f
        for (q in out) {
            assertEquals(0f, q.y, 1e-4f)
            assertTrue(q.x - prevX in 0f..2.01f)
            prevX = q.x
        }
        assertEquals(150f, out.last().x, 1e-3f)
        assertEquals(0.4f, out.last().pressure, 1e-6f)
        assertEquals(16L, out.last().time)
        // Wander along a curve: whenever the rope is taut the lag equals its length.
        for (i in 1..60) {
            val a = i * 0.1f
            val f = ToolPoint(200f + 150f * sin(a), 150f * (1 - cos(a)))
            val res = p.move(f)
            if (res.isNotEmpty()) assertEquals(50f, hypot(f.x - res.last().x, f.y - res.last().y), 1e-2f)
            assertTrue(hypot(f.x - p.brushX, f.y - p.brushY) <= 50f + 1e-2f)
        }
    }

    @Test
    fun ropeCatchUpFinishesAtTheFinger() {
        val p = StrokePipeline()
        p.down(ToolPoint(0f, 0f), rope(50f, catchUp = true))
        p.move(ToolPoint(200f, 0f))
        val end = p.up(ToolPoint(200f, 0f, pressure = 0.7f))
        assertEquals(200f, end.last().x, 1e-4f)
        assertEquals(0.7f, end.last().pressure, 1e-6f)
        for (i in 1 until end.size) assertTrue(end[i].x - end[i - 1].x <= 2.01f)
        assertFalse(p.isActive)
    }

    @Test
    fun ropeWithoutCatchUpEndsAtTheBrush() {
        val p = StrokePipeline()
        p.down(ToolPoint(0f, 0f), rope(50f, catchUp = false))
        p.move(ToolPoint(200f, 0f))
        val end = p.up(ToolPoint(230f, 0f))
        assertEquals(180f, end.last().x, 1e-3f) // pulled once more by the up point, then stops
        val q = StrokePipeline()
        q.down(ToolPoint(5f, 5f), rope(50f, catchUp = false))
        val tap = q.up(ToolPoint(20f, 5f))
        assertEquals(1, tap.size)
        assertEquals(5f, tap.last().x, 0f) // never pulled: ends where it started
    }

    @Test
    fun ropeOnCircularRulerStaysOnTheCircle() {
        val ruler = RulerSettings(enabled = true, type = RulerType.CIRCLE, centerX = 0f, centerY = 0f, radius = 300f)
        val p = StrokePipeline()
        p.down(ToolPoint(300f, 0f), rope(40f, ruler = ruler))
        val all = ArrayList<ToolPoint>()
        for (i in 1..40) all += p.move(ToolPoint(310f * cos(i * 0.05f), 310f * sin(i * 0.05f)))
        all += p.up(ToolPoint(310f * cos(2.05f), 310f * sin(2.05f)))
        assertTrue(all.size > 50)
        for (q in all) assertEquals(300f, hypot(q.x, q.y), 0.05f)
    }

    @Test
    fun smoothWithZeroStrengthIsPassThrough() {
        val p = StrokePipeline()
        p.down(ToolPoint(0f, 0f), smooth(0f))
        for (i in 1..20) {
            val q = ToolPoint(i * 3f, (i % 3) * 2f, pressure = 0.5f, time = i.toLong())
            assertEquals(listOf(q), p.move(q))
        }
    }

    /** Brush lag behind the finger after a long straight stroke. */
    private fun lagAfterStraightStroke(lag: Float): Float {
        val p = StrokePipeline()
        p.down(ToolPoint(0f, 0f), smooth(lag))
        var last = 0f
        for (i in 1..200) {
            val out = p.move(ToolPoint(i * 5f, 0f))
            if (out.isNotEmpty()) last = out.last().x
        }
        return 1000f - last
    }

    @Test
    fun smoothLagGrowsWithStrengthAndMatchesTheSetting() {
        val small = lagAfterStraightStroke(20f)
        val big = lagAfterStraightStroke(60f)
        assertTrue(big > small * 2f)
        assertEquals(20f, small, 3f)
        assertEquals(60f, big, 8f)
        assertTrue(StrokeAssist.smoothLagDp(1f) > StrokeAssist.smoothLagDp(0.5f))
        assertEquals(0f, StrokeAssist.smoothLagDp(0f), 0f)
    }

    @Test
    fun smoothRemovesJitter() {
        val p = StrokePipeline()
        p.down(ToolPoint(0f, 0f), smooth(40f))
        val out = ArrayList<ToolPoint>()
        for (i in 1..300) out += p.move(ToolPoint(i * 2f, if (i % 2 == 0) 4f else -4f))
        val tail = out.filter { it.x > 200f }
        assertTrue(tail.isNotEmpty())
        for (q in tail) assertTrue("y=${q.y}", abs(q.y) < 0.6f)
    }

    @Test
    fun smoothCatchUpEndsAtFingerOtherwiseAtBrush() {
        for (catchUp in listOf(true, false)) {
            val p = StrokePipeline()
            p.down(ToolPoint(0f, 0f), smooth(40f, catchUp))
            for (i in 1..50) p.move(ToolPoint(i * 4f, 0f))
            val end = p.up(ToolPoint(200f, 0f, pressure = 0.2f, time = 500L))
            if (catchUp) {
                assertEquals(200f, end.last().x, 1e-4f)
                for (i in 1 until end.size) assertTrue(end[i].x - end[i - 1].x <= 2.01f)
            } else {
                assertTrue(end.last().x < 180f)
            }
            assertEquals(0.2f, end.last().pressure, 1e-6f)
            assertEquals(500L, end.last().time)
        }
    }

    @Test
    fun upIsNeverEmptyAndCancelResets() {
        for (params in listOf(StrokePipeline.Params(), rope(), smooth(30f))) {
            val p = StrokePipeline()
            p.down(ToolPoint(3f, 4f), params)
            val end = p.up(ToolPoint(3f, 4f))
            assertEquals(1, end.size)
            assertEquals(3f, end[0].x, 0f)
        }
        val p = StrokePipeline()
        p.down(ToolPoint(0f, 0f), rope())
        p.move(ToolPoint(100f, 0f))
        assertEquals(StabilizerMode.ROPE, p.activeMode)
        p.cancel()
        assertFalse(p.isActive)
        assertEquals(StabilizerMode.OFF, p.activeMode)
    }
}
