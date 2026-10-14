package com.brushwork.paint.v19.a6

import android.graphics.PointF
import com.brushwork.paint.AppSettings
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.engine.live.StrokePreview
import com.brushwork.paint.tools.ToolId
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 A6 step 8 (P4, [StrokePreview]): the guards of "Drawing preview".
 *
 * - As shipped (LA2 cut P4 on its proxy numbers) no stroke previews "Faster", whatever is saved.
 * - I8: under Robolectric the live policy is EXACT, so no stroke previews "Faster" even with the
 *   setting available and chosen.
 * - Opted in (policy LIVE, setting available, "Faster"): the stroke is drawn from proxies while
 *   the finger is down, and after the lift the canvas, the layer and the undo step are bit for bit
 *   those of the same stroke with "Exact pixels" (Q5, I1, I7).
 * - "Exact pixels" keeps v1.8's preview (the canvas shows the commit, I25).
 * - Zooming faster than [StrokePreview.ZOOM_GUARD_PER_SECOND] shows the rest of the stroke
 *   exactly, frame for frame as "Exact pixels" does; the next stroke previews "Faster" again.
 *
 * Every canvas is the steps' small bench canvas ([A6Bench], 512 px, 8 layers, a 540 x 1204 px
 * view), at the app's fit (about 0.95: proxies at 1/2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h803dp-xxhdpi")
class DrawingPreviewGuardTest {
    private val wasAvailable = StrokePreview.available

    @After
    fun tearDown() {
        StrokePreview.available = wasAvailable
    }

    private fun bench() = A6Bench(512, 512, layers = 8, active = 4, screenW = 540, screenH = 1204, freeH = 923).also { b ->
        b.c.selectTool(ToolId.BRUSH)
        b.c.brush = BrushLibrary.byId("hardround")!!.copy(size = 40f)
        b.fit()
    }

    /** What one stroke showed and left. */
    private class Run(
        val sawSession: Boolean,
        val midFrame: IntArray,
        val frame: IntArray,
        val layer: IntArray,
        val label: String?,
        val showedCommit: Boolean,
        val activeAfter: Boolean,
    )

    private fun A6Bench.layerPixels(): IntArray {
        val bmp = c.doc.layers[c.doc.activeLayerIndex].bitmap
        return IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    /** One stroke through [pts], a frame after every second event, then the lift and its refinement. */
    private fun A6Bench.stroke(pts: List<PointF>): Run {
        val steps = c.undoManager.undoCount
        c.pointerDown(point(pts[0]))
        var saw = false
        var mid: IntArray? = null
        var i = 1
        while (i < pts.size - 1) {
            c.pointerMove(point(pts[i++]))
            if (i < pts.size - 1) c.pointerMove(point(pts[i++]))
            frame()
            if (c.liveAdjust.isActive) saw = true
            if (mid == null && i >= pts.size / 2) mid = pixels()
        }
        c.pointerUp(point(pts.last()))
        settle()
        assertEquals("one undo step", steps + 1, c.undoManager.undoCount)
        val tool = c.tools.getValue(ToolId.BRUSH) as BrushTool
        return Run(saw, mid!!, pixels(), layerPixels(), c.undoManager.undoLabel, tool.lastLiftShowedCommit, c.liveAdjust.isActive)
    }

    private fun A6Bench.strokePoints() = A6Bench.serpentine(visibleArea(), STROKE_LENGTH)

    @Test
    fun asShippedNoStrokePreviewsFaster() {
        assertEquals("the setting is available as LA2 decided", StrokePreview.SHIPPED, StrokePreview.available)
        bench().use { b ->
            b.c.liveAdjust.policy = LiveAdjust.Policy.LIVE
            assertEquals("the saved default", AppSettings.DRAW_PREVIEW_FASTER, b.c.settings.drawPreview)
            assertEquals(StrokePreview.SHIPPED, StrokePreview.faster(b.c.settings))
            val r = b.stroke(b.strokePoints())
            assertEquals("a stroke previews Faster only when P4 ships", StrokePreview.SHIPPED, r.sawSession)
            assertEquals("the canvas showed the commit (v1.8 and I25) unless P4 ships", !StrokePreview.SHIPPED, r.showedCommit)
        }
    }

    @Test
    fun underRobolectricThePolicyIsExactAndNoStrokePreviewsFaster() {
        StrokePreview.available = true
        bench().use { b ->
            assertEquals("I8", LiveAdjust.Policy.EXACT, b.c.liveAdjust.policy)
            b.c.settings.drawPreview = AppSettings.DRAW_PREVIEW_FASTER
            assertTrue(StrokePreview.faster(b.c.settings))
            val r = b.stroke(b.strokePoints())
            assertFalse("no live session", r.sawSession)
            assertTrue("the canvas showed the commit (I25)", r.showedCommit)
        }
    }

    @Test
    fun exactPixelsKeepsV18sPreview() {
        StrokePreview.available = true
        bench().use { b ->
            b.c.liveAdjust.policy = LiveAdjust.Policy.LIVE
            b.c.settings.drawPreview = AppSettings.DRAW_PREVIEW_EXACT
            assertFalse(StrokePreview.faster(b.c.settings))
            val r = b.stroke(b.strokePoints())
            assertFalse("no live session", r.sawSession)
            assertTrue("the canvas showed the commit (I25)", r.showedCommit)
        }
    }

    @Test
    fun fasterDrawsFromProxiesAndLandsExactlyWhatExactPixelsDoes() {
        StrokePreview.available = true
        bench().use { exact ->
            bench().use { live ->
                live.c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                // (The two editors share the preferences: "Faster" for both, the policy decides.)
                live.c.settings.drawPreview = AppSettings.DRAW_PREVIEW_FASTER
                val before = live.pixels()
                val pts = live.strokePoints()
                val e = exact.stroke(pts)
                val l = live.stroke(pts)
                assertFalse("Exact policy: no session", e.sawSession)
                assertTrue("Faster: the stroke was drawn from proxies", l.sawSession)
                assertFalse("the session ended after the lift and its refinement", l.activeAfter)
                assertFalse("mid-stroke the canvas showed the stroke", l.midFrame.contentEquals(before))
                assertFalse("mid-stroke Faster showed the proxies, not exact pixels", l.midFrame.contentEquals(e.midFrame))
                assertArrayEquals("the committed layer (I1)", e.layer, l.layer)
                assertArrayEquals("the canvas after the lift: exact pixels (Q5, I7)", e.frame, l.frame)
                assertEquals("the same undo step", e.label, l.label)
                assertTrue("Exact pixels: the canvas showed the commit (I25)", e.showedCommit)
                assertFalse("Faster: the commit redrew the stroke (the canvas showed proxies)", l.showedCommit)
            }
        }
    }

    @Test
    fun zoomingFasterThanTheGuardShowsTheRestOfTheStrokeExactly() {
        StrokePreview.available = true
        bench().use { exact ->
            bench().use { live ->
                live.c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                live.c.settings.drawPreview = AppSettings.DRAW_PREVIEW_FASTER
                var t = 0L
                for (b in listOf(exact, live)) {
                    b.c.liveAdjust.clock = { t }
                    // No refinement while the finger rests: frames come from proxies until the guard.
                    b.c.liveAdjust.idleNanos = Long.MAX_VALUE
                }
                val both = listOf(exact, live)
                val z0 = live.c.viewTransform.zoom
                val pts = A6Bench.serpentine(live.visibleArea(0.25f), STROKE_LENGTH)
                val q = pts.size / 4
                val parts = listOf(pts.subList(0, q), pts.subList(q, 2 * q), pts.subList(2 * q, 3 * q), pts.subList(3 * q, pts.size))

                /** [part]'s events on both editors, a frame after every second one; [proxies]: live's frames come from proxies. */
                fun moves(part: List<PointF>, proxies: Boolean) {
                    for (k in part.indices step 2) {
                        for (b in both) {
                            b.c.pointerMove(b.point(part[k]))
                            if (k + 1 < part.size) b.c.pointerMove(b.point(part[k + 1]))
                            b.frame()
                        }
                        assertEquals("from proxies at zoom ${live.c.viewTransform.zoom}", proxies, live.c.liveAdjust.isActive)
                        if (!proxies) assertArrayEquals("shown exactly", exact.pixels(), live.pixels())
                    }
                }

                for (b in both) b.c.pointerDown(b.point(parts[0][0]))
                moves(parts[0].drop(1), proxies = true)
                // x 1.1 in 1 s: under the guard (x 1.2 per second), the stroke stays live.
                t += 1_000_000_000L
                for (b in both) b.zoomTo(z0 * 1.1f)
                assertTrue("x 1.1 in 1 s stays live", live.c.liveAdjust.isActive)
                moves(parts[1], proxies = true)
                // x 1.5 in 0.5 s: the guard ends the session; this frame and the rest are exact.
                t += 500_000_000L
                for (b in both) b.zoomTo(z0 * 1.1f * 1.5f)
                assertFalse("x 1.5 in 0.5 s ends the live session", live.c.liveAdjust.isActive)
                assertArrayEquals("the guard's frame is exact", exact.pixels(), live.pixels())
                moves(parts[2], proxies = false)
                for (b in both) {
                    b.c.pointerUp(b.point(parts[2].last()))
                    b.settle()
                }
                assertArrayEquals("after the lift", exact.pixels(), live.pixels())
                assertArrayEquals("the committed layer", exact.layerPixels(), live.layerPixels())

                // The next stroke previews Faster again (the guard held for one stroke).
                for (b in both) b.c.pointerDown(b.point(parts[3][0]))
                moves(parts[3].drop(1), proxies = true)
                for (b in both) {
                    b.c.pointerUp(b.point(parts[3].last()))
                    b.settle()
                }
                assertFalse("refined after the lift", live.c.liveAdjust.isActive)
                assertArrayEquals("after the second lift", exact.pixels(), live.pixels())
                assertArrayEquals("the committed layer after the second stroke", exact.layerPixels(), live.layerPixels())
            }
        }
    }

    private companion object {
        /** A 1500 px serpentine over the visible canvas. */
        const val STROKE_LENGTH = 1500f
    }
}
