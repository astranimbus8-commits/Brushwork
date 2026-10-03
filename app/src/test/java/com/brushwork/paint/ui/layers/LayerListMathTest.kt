package com.brushwork.paint.ui.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerListMathTest {

    @Test
    fun layersWindowIsSmallInPortraitAndWideOnShortScreens() {
        // The user's phone (ZTE Axon 50 Lite, ~392 x 875 dp): 300 dp wide, half the height.
        assertEquals(WindowDp(300, 438), LayerListMath.windowSize(392, 875))
        // A 360 dp phone: 78 % of the width.
        assertEquals(WindowDp(281, 380), LayerListMath.windowSize(360, 760))
        // Tablets get a slightly wider window.
        assertEquals(WindowDp(360, 640), LayerListMath.windowSize(800, 1280))
        assertEquals(WindowDp(360, 400), LayerListMath.windowSize(1280, 800))
        // A phone in landscape: short, so wider and as tall as the chrome allows (the host clamps).
        assertTrue(LayerListMath.isShortScreen(392))
        assertEquals(WindowDp(520, 392), LayerListMath.windowSize(875, 392))
        assertEquals(WindowDp(494, 360), LayerListMath.windowSize(760, 360))
        assertEquals("never narrower than the side-by-side layout", 380, LayerListMath.windowSize(500, 320).width)
        assertEquals("but never wider than the screen", 300, LayerListMath.windowSize(300, 300).width)
        // Degenerate sizes don't crash.
        assertTrue(LayerListMath.windowSize(0, 0).width > 0)
        assertEquals(240, LayerListMath.windowSize(360, 480).height)
    }

    @Test
    fun displayAndDocumentIndicesAreMirrored() {
        val n = 5
        assertEquals(4, LayerListMath.displayToDoc(0, n)) // top row = last document layer
        assertEquals(0, LayerListMath.displayToDoc(4, n)) // bottom row = document index 0
        for (i in 0 until n) {
            assertEquals(i, LayerListMath.docToDisplay(LayerListMath.displayToDoc(i, n), n))
            assertEquals(i, LayerListMath.displayToDoc(LayerListMath.docToDisplay(i, n), n))
        }
        assertEquals(0, LayerListMath.displayToDoc(0, 1))
    }

    @Test
    fun movedReordersAndClamps() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), LayerListMath.moved(l, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), LayerListMath.moved(l, 3, 0))
        assertEquals(listOf("b", "c", "d", "a"), LayerListMath.moved(l, 0, 99))
        assertEquals(l, LayerListMath.moved(l, 1, 1))
        assertEquals(l, LayerListMath.moved(l, 7, 0))
        assertEquals(emptyList<String>(), LayerListMath.moved(emptyList<String>(), 0, 1))
        // The source list is never modified.
        assertEquals(listOf("a", "b", "c", "d"), l)
    }

    @Test
    fun dropOfDisplayOrderMapsToDocumentIndex() {
        // Document (bottom first): L0 L1 L2 L3  -> display (top first): L3 L2 L1 L0.
        val display = listOf("L3", "L2", "L1", "L0")
        // Drag the top row (L3) down to display index 2: display becomes L2 L1 L3 L0.
        val after = LayerListMath.moved(display, 0, 2)
        assertEquals(listOf("L2", "L1", "L3", "L0"), after)
        // L3 now sits at document index 1 (just above the bottom layer).
        assertEquals(1, LayerListMath.displayToDoc(after.indexOf("L3"), after.size))
        assertEquals(listOf("L0", "L3", "L1", "L2"), after.asReversed())
    }

    @Test
    fun clippingOnBottomLayerIsIgnored() {
        val info = LayerListMath.clipStructure(listOf(true, false))
        assertFalse(info[0].clipped)
        assertFalse(info[1].clipped)
    }

    @Test
    fun clippedChainPointsToItsBase() {
        // doc: 0 base, 1 clip, 2 clip, 3 normal, 4 clip
        val info = LayerListMath.clipStructure(listOf(false, true, true, false, true))
        assertEquals(ClipInfo.NONE, info[0])
        assertEquals(ClipInfo(clipped = true, baseIndex = 0, continuesAbove = true, lowestInGroup = true), info[1])
        assertEquals(ClipInfo(clipped = true, baseIndex = 0, continuesAbove = false, lowestInGroup = false), info[2])
        assertEquals(ClipInfo.NONE, info[3])
        assertEquals(ClipInfo(clipped = true, baseIndex = 3, continuesAbove = false, lowestInGroup = true), info[4])
    }

    @Test
    fun clippingAboveClippedBottomUsesBottomAsBase() {
        // The bottom layer's own clipping flag is ignored, so it is the base of the layer above.
        val info = LayerListMath.clipStructure(listOf(true, true))
        assertFalse(info[0].clipped)
        assertTrue(info[1].clipped)
        assertEquals(0, info[1].baseIndex)
        assertTrue(info[1].lowestInGroup)
    }

    @Test
    fun displayStructureIsTopFirst() {
        val docOrder = listOf(false, true, false)
        val display = LayerListMath.clipStructureForDisplay(docOrder)
        assertEquals(3, display.size)
        assertFalse(display[0].clipped) // doc 2
        assertTrue(display[1].clipped)  // doc 1, indented under...
        assertEquals(0, display[1].baseIndex)
        assertFalse(display[2].clipped) // ...the base, doc 0, shown last
    }

    @Test
    fun emptyAndSingleLayerStructures() {
        assertTrue(LayerListMath.clipStructure(emptyList()).isEmpty())
        assertEquals(listOf(ClipInfo.NONE), LayerListMath.clipStructure(listOf(false)))
        assertEquals(listOf(ClipInfo.NONE), LayerListMath.clipStructure(listOf(true)))
    }

    @Test
    fun draggedRowIsKeptInsideTheViewport() {
        assertEquals(50f, LayerListMath.clampRowTop(50f, 100f, 0f, 600f), 0f)
        assertEquals(0f, LayerListMath.clampRowTop(-80f, 100f, 0f, 600f), 0f)
        assertEquals(500f, LayerListMath.clampRowTop(900f, 100f, 0f, 600f), 0f)
        // A row taller than the viewport is left alone.
        assertEquals(-30f, LayerListMath.clampRowTop(-30f, 100f, 0f, 60f), 0f)
    }

    @Test
    fun edgeScrollOnlyInTheDragDirectionInsideTheZone() {
        val size = 100f
        fun speed(top: Float, travel: Float) = LayerListMath.edgeScrollSpeed(top, size, travel, 0f, 600f)
        // Middle of the list: nothing.
        assertEquals(0f, speed(250f, 120f), 0f)
        assertEquals(0f, speed(250f, -120f), 0f)
        // At the bottom edge while dragging down: scrolls down; dragging up there: nothing.
        assertTrue(speed(500f, 120f) > 0f)
        assertEquals(0f, speed(500f, -120f), 0f)
        // At the top edge while dragging up: scrolls up.
        assertTrue(speed(0f, -120f) < 0f)
        assertEquals(0f, speed(0f, 120f), 0f)
        // A jitter right after the long press (less than a quarter row) never scrolls.
        assertEquals(0f, speed(500f, 10f), 0f)
        assertEquals(0f, speed(0f, 0f), 0f)
    }

    @Test
    fun edgeScrollRampsAndIsCappedBelowOneRowPerFrame() {
        val size = 100f
        fun speed(top: Float) = LayerListMath.edgeScrollSpeed(top, size, 200f, 0f, 600f)
        // Zone is 60 px: the bottom of the row at 570 is halfway in.
        val half = speed(470f)
        val full = speed(540f)
        val beyond = speed(5000f)
        assertEquals(size * 9f * 0.5f, half, 0.01f)
        assertEquals(size * 9f, full, 0.01f)
        assertEquals(full, beyond, 0f)
        // Even a slow 50 ms frame moves less than half a row.
        assertTrue(beyond * 0.05f < size / 2f)
        assertEquals(0f, LayerListMath.edgeScrollSpeed(500f, 0f, 200f, 0f, 600f), 0f)
    }

    @Test
    fun opacityButtonsMoveOnePercentWithoutIncrements() {
        // v1.5: from the rounded percentage, 0 and 100 % reachable and never passed.
        assertEquals(0.38f, LayerListMath.stepOpacity(0.37f, up = true, stepPercent = null), 1e-6f)
        assertEquals(0.36f, LayerListMath.stepOpacity(0.37f, up = false, stepPercent = null), 1e-6f)
        assertEquals(0.38f, LayerListMath.stepOpacity(0.374f, up = true, stepPercent = null), 1e-6f)
        assertEquals(1f, LayerListMath.stepOpacity(1f, up = true, stepPercent = null), 0f)
        assertEquals(0f, LayerListMath.stepOpacity(0f, up = false, stepPercent = null), 0f)
        assertEquals(0.99f, LayerListMath.stepOpacity(Float.NaN, up = false, stepPercent = null), 1e-6f)
        // An unusable step means increments off.
        assertEquals(0.38f, LayerListMath.stepOpacity(0.37f, up = true, stepPercent = 0f), 1e-6f)
        assertEquals(0.38f, LayerListMath.stepOpacity(0.37f, up = true, stepPercent = Float.NaN), 1e-6f)
    }

    @Test
    fun opacityButtonsGoToTheNextMultipleOfTheStep() {
        // 37 % with a 5 % step: 40 up, 35 down; from a multiple, one whole step.
        assertEquals(0.40f, LayerListMath.stepOpacity(0.37f, up = true, stepPercent = 5f), 1e-6f)
        assertEquals(0.35f, LayerListMath.stepOpacity(0.37f, up = false, stepPercent = 5f), 1e-6f)
        assertEquals(0.45f, LayerListMath.stepOpacity(0.40f, up = true, stepPercent = 5f), 1e-6f)
        assertEquals(0.35f, LayerListMath.stepOpacity(0.40f, up = false, stepPercent = 5f), 1e-6f)
        // The ends stay reachable with a step that doesn't divide 100.
        assertEquals(0.98f, LayerListMath.stepOpacity(0.96f, up = true, stepPercent = 7f), 1e-6f)
        assertEquals(1f, LayerListMath.stepOpacity(0.98f, up = true, stepPercent = 7f), 1e-6f)
        assertEquals(0.98f, LayerListMath.stepOpacity(0.91f, up = true, stepPercent = 7f), 1e-6f)
        assertEquals(0f, LayerListMath.stepOpacity(0.05f, up = false, stepPercent = 7f), 1e-6f)
        assertEquals(1f, LayerListMath.stepOpacity(1f, up = true, stepPercent = 10f), 0f)
        // Float noise right at a multiple counts as on it.
        assertEquals(0.30f, LayerListMath.stepOpacity(0.25f + 1e-7f, up = true, stepPercent = 5f), 1e-6f)
        assertEquals(0.20f, LayerListMath.stepOpacity(0.25f - 1e-7f, up = false, stepPercent = 5f), 1e-6f)
        // Fractional steps.
        assertEquals(0.025f, LayerListMath.stepOpacity(0f, up = true, stepPercent = 2.5f), 1e-6f)
    }

    @Test
    fun frameBadgesCountTheFramesOfEachStory() {
        fun t(story: Long, index: Int, overset: Boolean = false) =
            com.brushwork.paint.tools.text.TextThreadSpec(storyId = story, index = index, story = "abc", start = 0, end = 0, overset = overset)
        val badges = LayerListMath.frameBadges(listOf(t(7, 1, overset = true), null, t(9, 0), t(7, 0), com.brushwork.paint.tools.text.TextThreadSpec()))
        assertEquals(FrameBadge(index = 1, count = 2, overset = true), badges[0])
        assertEquals(null, badges[1])
        assertEquals(FrameBadge(index = 0, count = 1, overset = false), badges[2])
        assertEquals(FrameBadge(index = 0, count = 2, overset = false), badges[3])
        assertEquals("an unthreaded spec is no frame", null, badges[4])
        assertEquals("2/2", badges[0]!!.text)
        assertEquals("Text frame 2 of 2, more text than fits", badges[0]!!.description)
        assertEquals("Text frame 1 of 2", badges[3]!!.description)
        // An index past the frames found (a frame on another document state) is clamped.
        assertEquals(FrameBadge(index = 0, count = 1, overset = false), LayerListMath.frameBadges(listOf(t(3, 4)))[0])
    }

    @Test
    fun throttleAllowsAtMostOnePerInterval() {
        val t = PreviewThrottle(33)
        assertTrue(t.offer(1000))
        assertFalse(t.offer(1010))
        assertEquals(23, t.delayUntilNext(1010))
        assertFalse(t.offer(1032))
        assertTrue(t.offer(1033))
        assertEquals(33, t.delayUntilNext(1033))
        t.markApplied(1100)
        assertFalse(t.offer(1120))
        assertEquals(0, t.delayUntilNext(2000))
        t.reset()
        assertTrue(t.offer(1121))
    }
}
