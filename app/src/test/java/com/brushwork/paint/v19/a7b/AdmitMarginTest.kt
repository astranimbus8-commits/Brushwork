package com.brushwork.paint.v19.a7b

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.StabilityLabels19
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A7b step 2 (design 3.2 A7b, R9): [GovernorPolicyV19.admit] admits a flatten, filter apply,
 * export or merge whose estimate fits in the free Java heap less a 64 MB margin; what is in use may
 * be garbage, so one collection comes before a refusal. A refused filter apply or merge says
 * "Not enough memory for <name>" and changes nothing; with room, the same call goes ahead.
 */
@RunWith(RobolectricTestRunner::class)
class AdmitMarginTest {
    private val mb = 1024L * 1024
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var savedHeap: GovernorPolicyV19.Heap

    @Before
    fun setUp() {
        savedHeap = GovernorPolicyV19.heap
    }

    @After
    fun tearDown() {
        GovernorPolicyV19.heap = savedHeap
    }

    @Test
    fun theMarginIs64MB() {
        assertEquals(64L * mb, GovernorPolicyV19.ADMIT_MARGIN)
        assertSame("the app's policy decides", GovernorPolicyV19, MemoryGovernor.policy)
    }

    @Test
    fun anEstimateUpToTheFreeHeapLessTheMarginIsAdmitted() {
        val heap = FakeHeap(512L * mb, 200L * mb)
        GovernorPolicyV19.heap = heap
        val room = 512L * mb - 200L * mb - 64L * mb
        assertTrue(GovernorPolicyV19.admit(room, "Merge down"))
        assertTrue("through the governor", MemoryGovernor.admit(room, "Merge down"))
        assertEquals("no collection when it fits", 0, heap.collections)
        assertFalse(GovernorPolicyV19.admit(room + 1, "Merge down"))
        assertEquals("one collection before refusing", 1, heap.collections)
        assertFalse(MemoryGovernor.admit(room + 1, "Export PNG"))
    }

    @Test
    fun garbageIsCollectedBeforeARefusal() {
        val heap = FakeHeap(512L * mb, 400L * mb)
        heap.garbage = 150L * mb
        GovernorPolicyV19.heap = heap
        // 48 MB of room until the collection, 198 MB after it.
        assertTrue(GovernorPolicyV19.admit(100L * mb, "Invert Color"))
        assertEquals(1, heap.collections)
        assertEquals(250L * mb, heap.used)
    }

    @Test
    fun nothingAskedIsAlwaysAdmitted() {
        val heap = FakeHeap(100L * mb, 100L * mb)
        GovernorPolicyV19.heap = heap
        assertTrue(GovernorPolicyV19.admit(0L, "Merge down"))
        assertEquals(0, heap.collections)
        assertFalse(GovernorPolicyV19.admit(1L, "Merge down"))
    }

    @Test
    fun aFilterThatDoesNotFitSaysSoAndChangesNothing() {
        val c = Smoke.controller(app, Smoke.document(64, 48, layers = 1))
        val layer = c.activeLayer
        layer.bitmap.eraseColor(Color.RED)
        c.startFilter(FilterRegistry.byId("adjust.invert")!!)
        val s = c.filterSession!!
        // The source and the result buffers: 8 bytes a pixel.
        val need = 64L * 48 * 8
        val max = 1024L * mb
        GovernorPolicyV19.heap = FakeHeap(max, max - 64L * mb - need + 1)
        c.message = null
        s.apply()
        assertEquals(String.format(StabilityLabels19.NO_MEMORY_FOR, "Invert Color"), c.message)
        assertFalse(s.isApplying)
        assertFalse("the session stays open", s.isClosed)
        assertEquals(0, c.undoManager.undoCount)
        assertEquals(Color.RED, layer.bitmap.getPixel(10, 10))

        // One byte more room: the same apply goes ahead, one step.
        GovernorPolicyV19.heap = FakeHeap(max, max - 64L * mb - need)
        c.message = null
        s.apply()
        assertTrue("applied", Smoke.pumpUntil { s.isClosed })
        assertNull(c.message)
        assertEquals(1, c.undoManager.undoCount)
        assertEquals(Color.CYAN, layer.bitmap.getPixel(10, 10))
    }

    @Test
    fun aMergeThatDoesNotFitSaysSoAndChangesNothing() {
        val c = Smoke.controller(app, Smoke.document(64, 48, layers = 2))
        val top = c.activeLayer
        val max = 1024L * mb
        val need = 64L * 48 * 4
        GovernorPolicyV19.heap = FakeHeap(max, max - 64L * mb - need + 1)
        c.mergeDown(top)
        assertEquals(String.format(StabilityLabels19.NO_MEMORY_FOR, "Merge down"), c.message)
        assertEquals(2, c.doc.layers.size)
        assertEquals(0, c.undoManager.undoCount)

        GovernorPolicyV19.heap = FakeHeap(max, max - 64L * mb - need)
        c.mergeDown(top)
        Smoke.pump()
        assertEquals(1, c.doc.layers.size)
        assertEquals(1, c.undoManager.undoCount)
    }
}
