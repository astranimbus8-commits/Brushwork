package com.brushwork.paint.v110.a1

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.lift.VectorLift
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
 * v1.10 (R1 (a), J19): a Perspective array's copies are baked into plain objects at commit (v1.8),
 * so they are not linked sides. The first tap on a copy picks that copy alone (whole: no side);
 * a tap at the same spot continues v1.9's deeper cycle to the object below — there are no "all
 * sides" — and round again.
 */
@RunWith(RobolectricTestRunner::class)
class PerspectiveCopyTapCycleTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    @Test
    fun theFirstTapPicksTheCopyAndTheSecondTheObjectBelow() {
        val c = kit.controller()
        val layer = c.vec
        val perspective = floatArrayOf(0.8f, 0.1f, 200f, 0.05f, 0.9f, 20f, 0.0004f, 0.0002f, 1f)
        assertTrue(ObjectCopies.isPerspective(listOf(perspective)))
        // What a Perspective array commit stores: the source and its baked copy, plain objects.
        val baked = ObjectCopies.baked(kit.box(60f, 100f, 120f, 140f), listOf(perspective))
        assertEquals(2, baked.size)
        val copyBox = VectorOps.bounds(baked[1])
        val p = Vec2(copyBox.centerX(), copyBox.centerY())
        val below = kit.box(p.x - 40f, p.y - 30f, p.x + 40f, p.y + 30f)
        c.vectors.addObjects(layer, listOf(below) + baked, "Add")
        val content = layer.vector!!
        assertEquals(3, content.objects.size)
        for (o in content.objects) assertTrue("no live copies", ObjectCopies.of(o).isEmpty)
        val provider = VectorLift.providerOf(c)

        assertTrue(provider.tapped(p))
        assertEquals("the copy alone", setOf(3L), c.vectors.selectedIds)
        assertNull("not a side", provider.sidePick)
        assertTrue(provider.tapped(p))
        assertEquals("the same spot: the object below, no all sides", setOf(1L), c.vectors.selectedIds)
        assertNull(provider.sidePick)
        assertTrue(provider.tapped(p))
        assertEquals("round again", setOf(3L), c.vectors.selectedIds)
        // The original stays where it is, not picked with its copy.
        assertTrue(provider.tapped(Vec2(90f, 120f)))
        assertEquals(setOf(2L), c.vectors.selectedIds)
        assertNull(provider.sidePick)
    }
}
