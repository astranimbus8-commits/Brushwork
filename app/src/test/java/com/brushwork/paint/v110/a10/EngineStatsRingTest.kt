package com.brushwork.paint.v110.a10

import android.os.SystemClock
import com.brushwork.paint.engine.EngineStat
import com.brushwork.paint.engine.EngineStats
import com.brushwork.paint.engine.StatSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A10 (design 7 and 3.3, step 5): `EngineStats` keeps the last 50 values of each stat in
 * memory and summarizes each ring by its nearest-rank median and 95th percentile, the stats with
 * values only, in [EngineStat] order. A negative or not-a-number value is ignored. A UI frame is
 * tagged stroke within 100 ms after input reached a composite, panel scroll within 100 ms after a
 * panel scrolled, other otherwise. Under Robolectric nothing is kept unless a test asks.
 */
@RunWith(RobolectricTestRunner::class)
class EngineStatsRingTest {
    @Before
    fun setUp() {
        EngineStats.keepUnderRobolectric = true
        EngineStats.reset()
    }

    @After
    fun tearDown() {
        EngineStats.reset()
        EngineStats.keepUnderRobolectric = false
    }

    private fun of(stat: EngineStat): StatSummary? = EngineStats.summary().firstOrNull { it.stat == stat }

    @Test
    fun eachRingKeepsTheLastFiftyValuesAndTheirP50AndP95() {
        assertTrue("empty at first", EngineStats.summary().isEmpty())
        // 1..60: the ring keeps 11..60.
        for (v in 1..60) EngineStats.record(EngineStat.SWITCH, v.toFloat())
        val s = of(EngineStat.SWITCH)!!
        assertEquals(EngineStats.RING, s.n)
        assertEquals("nearest-rank median of 11..60", 35f, s.p50)
        assertEquals("nearest-rank 95th percentile of 11..60", 58f, s.p95)

        // Fewer values than the ring, in any order.
        listOf(9f, 1f, 5f, 3f, 7f).forEach { EngineStats.record(EngineStat.OPEN, it) }
        val o = of(EngineStat.OPEN)!!
        assertEquals(5, o.n)
        assertEquals(5f, o.p50)
        assertEquals(9f, o.p95)

        // One value.
        EngineStats.record(EngineStat.LEDGER_PEAK, 1234f)
        assertEquals(StatSummary(EngineStat.LEDGER_PEAK, 1, 1234f, 1234f), of(EngineStat.LEDGER_PEAK))
    }

    @Test
    fun theSummaryListsOnlyStatsWithValuesInEnumOrder() {
        EngineStats.record(EngineStat.LEDGER_PEAK, 1f)
        EngineStats.record(EngineStat.SAVE_LAYER, 2f)
        EngineStats.record(EngineStat.SWITCH, 3f)
        assertEquals(listOf(EngineStat.SWITCH, EngineStat.SAVE_LAYER, EngineStat.LEDGER_PEAK), EngineStats.summary().map { it.stat })
    }

    @Test
    fun negativeAndNotANumberValuesAreIgnored() {
        EngineStats.record(EngineStat.COMPOSITE, -1f)
        EngineStats.record(EngineStat.COMPOSITE, Float.NaN)
        assertNull("nothing kept", of(EngineStat.COMPOSITE))
        EngineStats.record(EngineStat.COMPOSITE, 0f)
        assertEquals(1, of(EngineStat.COMPOSITE)!!.n)
    }

    @Test
    fun uiFramesAreTaggedStrokePanelOrOther() {
        val w = EngineStats.STROKE_WINDOW_MS
        val t0 = SystemClock.uptimeMillis()
        EngineStats.frame(16f, t0)
        assertEquals("nothing happened: other", 1, of(EngineStat.FRAME_OTHER)?.n)

        EngineStats.record(EngineStat.COMPOSITE, 4f)
        EngineStats.frame(15f, SystemClock.uptimeMillis())
        assertEquals("a composite without input (a pan) does not tag a frame stroke", 2, of(EngineStat.FRAME_OTHER)?.n)

        val before = SystemClock.uptimeMillis()
        EngineStats.record(EngineStat.INPUT_TO_COMPOSITE, 8f)
        val after = SystemClock.uptimeMillis()
        EngineStats.frame(20f, before + w)
        assertEquals("input reached a composite just before: stroke", StatSummary(EngineStat.FRAME_STROKE, 1, 20f, 20f), of(EngineStat.FRAME_STROKE))
        EngineStats.frame(17f, after + w + 1)
        assertEquals("too long after: other", 3, of(EngineStat.FRAME_OTHER)?.n)

        EngineStats.reset()
        val p = SystemClock.uptimeMillis()
        EngineStats.panelScrolled()
        EngineStats.frame(24f, p + w)
        assertEquals("a panel scrolled just before: panel", StatSummary(EngineStat.FRAME_PANEL, 1, 24f, 24f), of(EngineStat.FRAME_PANEL))
        EngineStats.record(EngineStat.INPUT_TO_COMPOSITE, 8f)
        EngineStats.frame(30f, SystemClock.uptimeMillis())
        assertEquals("a stroke wins over a panel", 1, of(EngineStat.FRAME_STROKE)?.n)
        assertNull("no other frame since the reset", of(EngineStat.FRAME_OTHER))
    }

    @Test
    fun underRobolectricNothingIsKeptUnlessATestAsks() {
        EngineStats.keepUnderRobolectric = false
        for (s in EngineStat.entries) EngineStats.record(s, 12f)
        EngineStats.frame(16f)
        assertTrue("nothing kept", EngineStats.summary().isEmpty())
        EngineStats.keepUnderRobolectric = true
        EngineStats.record(EngineStat.SWITCH, 12f)
        assertEquals(1, EngineStats.summary().size)
    }
}
