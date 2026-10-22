package com.brushwork.paint.v110.a1

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.v110.a1.A1Kit.withMaps
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.lift.VectorLiftProvider
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R1 (a), J19, A1 step 2): the tap cycle on a mirrored object. The first tap picks the
 * side under the finger, a tap at the same spot picks all sides, the next one goes on with v1.9's
 * deeper cycle (the object below), and round again (side first). A gesture in between, a tool
 * switch, or a selection made elsewhere starts the cycle again; an object without copies is
 * picked whole as in v1.9.
 */
@RunWith(RobolectricTestRunner::class)
class SideTapCycleTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun VectorLiftProvider.side(): Pair<Long, Int>? = sidePick?.let { it.id to it.k }

    @Test
    fun sideThenAllSidesThenTheObjectBelow() {
        val c = kit.controller()
        val layer = c.vec
        // 1 (below) lies under copy 1 of 2 (a box mirrored about x = 256: copy 1 at 392..452).
        val below = kit.box(380f, 80f, 470f, 160f)
        val mirrored = withMaps(kit.box(60f, 100f, 120f, 140f), listOf(identity(), mirrorX()))
        c.vectors.addObjects(layer, listOf(below, mirrored), "Add")
        val provider = VectorLift.providerOf(c)
        val onCopy = Vec2(420f, 120f)
        val onSource = Vec2(90f, 120f)

        assertTrue(provider.tapped(onCopy))
        assertEquals(setOf(2L), c.vectors.selectedIds)
        assertEquals(2L to 1, provider.side())
        assertTrue("same spot: all sides", provider.tapped(onCopy))
        assertEquals(setOf(2L), c.vectors.selectedIds)
        assertNull(provider.side())
        assertTrue("same spot again: the object below", provider.tapped(onCopy))
        assertEquals(setOf(1L), c.vectors.selectedIds)
        assertNull(provider.side())
        assertTrue("round again: the side first", provider.tapped(onCopy))
        assertEquals(setOf(2L), c.vectors.selectedIds)
        assertEquals(2L to 1, provider.side())

        // Elsewhere: the side there (the source, k = 0).
        assertTrue(provider.tapped(onSource))
        assertEquals(2L to 0, provider.side())
        // A drag, pinch or handle gesture in between: the same spot starts again (the side).
        provider.gestureEnded()
        provider.tapped(onSource)
        assertEquals(2L to 0, provider.side())
        assertTrue(provider.tapped(onSource))
        assertNull("then all sides", provider.side())
        // A selection made elsewhere starts it again too.
        c.vectors.setSelection(layer, setOf(1L))
        assertTrue(provider.tapped(onSource))
        assertEquals(2L to 0, provider.side())
        // The tool closed: no side stays picked, and the cycle starts again.
        provider.toolLeft()
        assertNull(provider.side())
        assertTrue(provider.tapped(onSource))
        assertEquals(2L to 0, provider.side())

        // An object without copies: picked whole, as in v1.9.
        assertTrue(provider.tapped(Vec2(465f, 90f)))
        assertEquals(setOf(1L), c.vectors.selectedIds)
        assertNull(provider.side())
    }
}
