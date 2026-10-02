package com.brushwork.paint.qa

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.clone.CloneAnchor
import com.brushwork.paint.tools.clone.CloneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.5 final QA (clone stamp, Photoshop parity): Aligned strokes carry the ⊕ along with them, but
 * the point the user set stays the "initial sampling point": switching Aligned off (or on again)
 * starts over from it, not from wherever the last aligned stroke left the ⊕.
 */
class CloneAnchorOriginQaTest {

    @Test
    fun switchingAlignedGoesBackToThePointTheUserSet() {
        val a = CloneAnchor()
        a.set(Vec2(60f, 60f))
        // Two aligned strokes 140 px to the right: the ⊕ travels to where the last one ended.
        val off = a.offsetFor(Vec2(200f, 60f), aligned = true)!!
        assertEquals(CloneOffset(140, 0), off)
        a.strokeCompleted(off, aligned = true, end = Vec2(215f, 60f))
        a.strokeCompleted(a.offsetFor(Vec2(300f, 60f), aligned = true)!!, aligned = true, end = Vec2(310f, 60f))
        assertEquals("the ⊕ travelled", Vec2(170f, 60f), a.source)
        assertEquals("the point the user set is kept", Vec2(60f, 60f), a.origin)
        // Aligned off: the next stroke samples from the initial sampling point again.
        a.resetAlignment()
        assertNull(a.fixed)
        assertEquals(Vec2(60f, 60f), a.source)
        assertEquals(CloneOffset(240, 100), a.offsetFor(Vec2(300f, 160f), aligned = false))
        // A cancelled placement puts the origin back too.
        a.set(Vec2(5f, 5f))
        a.restore(Vec2(170f, 60f), CloneOffset(140, 0), Vec2(60f, 60f))
        assertEquals(Vec2(60f, 60f), a.origin)
        a.resetAlignment()
        assertEquals(Vec2(60f, 60f), a.source)
        a.clear()
        assertNull(a.origin)
    }
}
