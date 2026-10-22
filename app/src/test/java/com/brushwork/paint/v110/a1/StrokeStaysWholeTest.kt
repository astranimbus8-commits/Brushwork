package com.brushwork.paint.v110.a1

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R1 (c)): a brush stroke drawn with a mirror keeps its v1.7 copies as part of one object
 * (`VStroke.copies`), so it has no sides: a tap anywhere on it names the whole stroke (k = 0) and
 * it is never split into a side.
 */
@RunWith(RobolectricTestRunner::class)
class StrokeStaysWholeTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    @Test
    fun aMirroredStrokeIsPickedWhole() {
        val mirror = floatArrayOf(-1f, 0f, 512f, 0f, 1f, 0f, 0f, 0f, 1f)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val s = kit.stroke(60f, 100f, 160f, 100f).copy(id = 3, copies = listOf(identity, mirror))
        assertTrue("no linked sides", ObjectCopies.of(s).isEmpty)
        // On the stroke and on its mirrored copy: the whole stroke.
        assertEquals(0, VectorOps.hitCopy(s, Vec2(110f, 101f), 4f))
        assertEquals(0, VectorOps.hitCopy(s, Vec2(512f - 110f, 101f), 4f))
        assertEquals(0, CopyPick.hitCopy(s, Vec2(512f - 110f, 101f), 4f))
        assertEquals(-1, VectorOps.hitCopy(s, Vec2(256f, 300f), 4f))
        // Its only "side" is itself.
        assertSame(s, CopyPick.side(s, 0))
        assertNull(CopyPick.side(s, 1))
        assertEquals(VectorOps.bounds(s), CopyPick.boundsOf(s, 0))
    }
}
