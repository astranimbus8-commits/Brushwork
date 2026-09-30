package com.brushwork.paint.ui.editor

import com.brushwork.paint.ui.editor.TouchGestureClassifier.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGestureClassifierTest {
    // Slops in px as if density were 2 (12dp / 8dp).
    private fun classifier() = TouchGestureClassifier(tapSlopPx = 24f, longPressSlopPx = 16f)

    @Test
    fun twoFingerTap() {
        val c = classifier()
        c.down(0, 100f, 100f, 1000)
        c.down(1, 300f, 100f, 1030)
        c.move(0, 103f, 101f)
        assertEquals(Tap.NONE, c.up(0, 1150))
        assertEquals(Tap.TWO_FINGER, c.up(1, 1180))
    }

    @Test
    fun threeFingerTap() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.down(1, 200f, 100f, 20)
        c.down(2, 300f, 100f, 40)
        c.up(1, 200)
        c.up(0, 210)
        assertEquals(Tap.THREE_FINGER, c.up(2, 250))
    }

    @Test
    fun threeFingersCountEvenIfOneLiftsEarly() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.down(1, 200f, 100f, 10)
        c.down(2, 300f, 100f, 20)
        c.up(0, 60)
        c.up(1, 70)
        assertEquals(3, c.maxPointers)
        assertEquals(Tap.THREE_FINGER, c.up(2, 90))
    }

    @Test
    fun movedFingersAreNotATap() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.down(1, 300f, 100f, 10)
        c.move(1, 300f, 124f) // exactly the slop
        c.up(0, 100)
        assertEquals(Tap.NONE, c.up(1, 120))
    }

    @Test
    fun slowTapIsNotATap() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.down(1, 300f, 100f, 10)
        c.up(0, 250)
        assertEquals(Tap.NONE, c.up(1, 301))
    }

    @Test
    fun tapWithinTimeoutAtTheLimit() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.down(1, 300f, 100f, 10)
        c.up(0, 250)
        assertEquals(Tap.TWO_FINGER, c.up(1, 300))
    }

    @Test
    fun singleFingerAndFourFingerTapsAreIgnored() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        assertEquals(Tap.NONE, c.up(0, 50))
        for (i in 0 until 4) c.down(i, 100f * i, 100f, 100L + i)
        for (i in 0 until 3) c.up(i, 150)
        assertEquals(Tap.NONE, c.up(3, 160))
    }

    @Test
    fun invalidatedGestureIsNotATap() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.down(1, 300f, 100f, 10)
        c.invalidate()
        c.up(0, 50)
        assertEquals(Tap.NONE, c.up(1, 60))
    }

    @Test
    fun cancelForgetsPointersAndNextGestureStartsFresh() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.down(1, 300f, 100f, 10)
        c.cancel()
        assertEquals(0, c.activePointers)
        c.down(0, 100f, 100f, 1000)
        c.down(1, 300f, 100f, 1010)
        c.up(0, 1100)
        assertEquals(Tap.TWO_FINGER, c.up(1, 1110))
    }

    @Test
    fun newGestureResetsMovementAndTiming() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.move(0, 400f, 400f)
        c.up(0, 5000)
        c.down(0, 100f, 100f, 6000)
        c.down(1, 300f, 100f, 6010)
        c.up(0, 6100)
        assertEquals(Tap.TWO_FINGER, c.up(1, 6120))
    }

    @Test
    fun upOfUnknownPointerIsIgnored() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        assertEquals(Tap.NONE, c.up(7, 10))
        assertEquals(1, c.activePointers)
    }

    // ------------------------------------------------------------------ long press

    @Test
    fun longPressAfterHoldingStill() {
        val c = classifier()
        c.down(0, 100f, 100f, 1000)
        c.move(0, 105f, 103f)
        assertFalse(c.longPressDue(1449))
        assertTrue(c.longPressDue(1450))
        c.markLongPressFired()
        assertFalse("fires once", c.longPressDue(1600))
    }

    @Test
    fun noLongPressWhenMoved() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.move(0, 116f, 100f)
        assertFalse(c.longPressDue(1000))
    }

    @Test
    fun noLongPressWithASecondFinger() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.down(1, 300f, 100f, 100)
        c.up(1, 200)
        assertFalse(c.longPressDue(1000))
    }

    @Test
    fun noLongPressAfterRelease() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.up(0, 100)
        assertFalse(c.longPressDue(1000))
    }

    @Test
    fun longPressFlagResetsForTheNextGesture() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.markLongPressFired()
        c.up(0, 600)
        c.down(0, 100f, 100f, 1000)
        assertTrue(c.longPressDue(1450))
    }

    @Test
    fun singlePointerStillIsOneFingerThatDidNotMove() {
        val c = classifier()
        c.down(0, 100f, 100f, 0)
        c.move(0, 110f, 105f)
        assertTrue(c.isSinglePointerStill())
        // Still known after the finger lifted (one-finger taps are not reported by up()).
        assertEquals(Tap.NONE, c.up(0, 80))
        assertTrue(c.isSinglePointerStill())
        // Moving the tap slop: no longer still.
        c.down(0, 100f, 100f, 1000)
        c.move(0, 124f, 100f)
        assertFalse(c.isSinglePointerStill())
        c.up(0, 1080)
        // Two fingers never count, even without moving.
        c.down(0, 100f, 100f, 2000)
        c.down(1, 200f, 100f, 2010)
        c.up(1, 2050)
        assertFalse(c.isSinglePointerStill())
        c.up(0, 2060)
        // A cancelled gesture neither.
        c.down(0, 100f, 100f, 3000)
        c.cancel()
        assertFalse(c.isSinglePointerStill())
    }
}
