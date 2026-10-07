package com.brushwork.paint.ui.layers

import com.brushwork.paint.ui.layers.HandleGesture.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.7 (§3.8, risk R13): the ≡ handle tells a swipe from a reorder: 24 dp horizontal before
 * 12 dp vertical is a swipe, otherwise the move is a reorder; without folders the handle is
 * v1.6's (the reorder starts after the touch slop, horizontal travel is ignored).
 */
class HandleGestureTest {
    // 1 dp = 2.75 px (a 392 dp phone at 440 dpi); the touch slop is 8 dp.
    private val density = 2.75f
    private val swipePx = HandleGesture.SWIPE_DP * density
    private val verticalPx = HandleGesture.SWIPE_VERTICAL_DP * density
    private val slopPx = 8f * density

    private fun intent(dxDp: Float, dyDp: Float, swipes: Boolean = true) =
        HandleGesture.intent(dxDp * density, dyDp * density, swipes, swipePx, verticalPx, slopPx)

    @Test
    fun aHorizontalTravelOf24DpBefore12DpVerticalIsASwipe() {
        assertEquals(Intent.SWIPE_RIGHT, intent(24f, 0f))
        assertEquals(Intent.SWIPE_LEFT, intent(-24f, 0f))
        assertEquals(Intent.SWIPE_RIGHT, intent(30f, 11f))
        assertEquals(Intent.SWIPE_LEFT, intent(-26f, -11.5f))
        assertEquals("not yet", Intent.UNDECIDED, intent(23f, 5f))
    }

    @Test
    fun twelveDpVerticalFirstIsAReorder() {
        assertEquals(Intent.REORDER, intent(0f, 12f))
        assertEquals(Intent.REORDER, intent(0f, -12f))
        assertEquals("vertical wins once reached, whatever the horizontal travel", Intent.REORDER, intent(40f, 12f))
        assertEquals("the slop alone no longer starts it", Intent.UNDECIDED, intent(0f, 9f))
    }

    @Test
    fun withoutFoldersTheHandleIsV16s() {
        assertEquals(Intent.REORDER, intent(0f, 8.5f, swipes = false))
        assertEquals(Intent.UNDECIDED, intent(60f, 2f, swipes = false))
        assertEquals(Intent.UNDECIDED, intent(0f, 8f, swipes = false))
    }

    @Test
    fun aLargeTouchSlopStillWinsOverTheVerticalThreshold() {
        val big = 16f * density
        assertEquals(Intent.UNDECIDED, HandleGesture.intent(0f, 13f * density, true, swipePx, verticalPx, big))
        assertEquals(Intent.REORDER, HandleGesture.intent(0f, 16f * density, true, swipePx, verticalPx, big))
    }
}
