package com.brushwork.paint.qa16

import android.graphics.Rect
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.6 final QA, bug: Undo / Redo of an adjustment layer's "Opacity" step (the layer window's
 * slider, −/+ or a typed value) redrew the WHOLE canvas exactly in the next frame (the step's
 * `structural {}` marks every display tile dirty: v1.5's full re-render, ≈ 0.7 s at 20 MP), while
 * the change itself, and the undo of every other adjustment step ("Edit adjustment", "Edit mask",
 * …), goes through the live session: only where the effect shows, from proxies at once, refined
 * within the frame budget (design §3.1 C2; ARCHITECTURE: `changed` is the one-shot form for undo).
 * On the phone (392 dp, 1080 x 2408), a radial part in the middle: − in the layer window, then
 * the top row's Undo and Redo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.opacityundosandbox"])
class AdjustmentOpacityUndoLiveQaTest {

    @Test
    fun undoAndRedoOfAnAdjustmentLayersOpacityRedrawOnlyItsEffectLive() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val harness = ChromeHarness()
        harness.section("Opacity undo / redo of an adjustment layer") {
            var lc: LiveCanvas? = null
            val s = harness.editor(QaDocs.stack(1080, 2408)) { c ->
                c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                c.liveAdjust.clock = { lc?.now ?: 0L }
            }
            val live = LiveCanvas(s).also { lc = it }
            val c = s.c
            live.draw()
            live.tool("Masks")
            click("+ Radial", exact = true)
            live.canvasDrag(540f to 1200f, 760f to 1200f, moves = 6) { live.draw() }
            val adj = c.activeLayer
            assertTrue(adj.isAdjustmentLayer)
            live.assertConverged("radial")
            val region = MaskEdits.effectRegion(c, adj)
            assertNotNull("a radial part shows in its own area", region)
            val tiles = c.tiles
            val inEffect = (0 until tiles.tileCount).count { Rect.intersects(tiles.tileRect(it % tiles.cols, it / tiles.cols), region!!) }
            assertTrue("the effect covers part of the canvas ($inEffect of ${tiles.tileCount} tiles)", inEffect < tiles.tileCount)

            click("Open layers")
            val before = live.steps()
            click(LayerLabels.LESS_OPACITY, exact = true)
            assertEquals(0.99f, adj.opacity, 1e-4f)
            assertEquals("one step", before + 1, live.steps())
            assertEquals("Opacity", c.undoManager.undoLabel)
            live.assertConverged("−")

            fun assertLiveOnlyWhereTheEffectShows(what: String) {
                assertTrue("$what: shown through the live session (proxies, then refined)", c.liveAdjust.isActive)
                val outside = (0 until tiles.tileCount).filter { tiles.isDirty(it) && !Rect.intersects(tiles.tileRect(it % tiles.cols, it / tiles.cols), region!!) }
                assertTrue("$what: no tile outside the effect is drawn again (dirty: $outside)", outside.isEmpty())
                live.assertConverged(what)
            }

            click("Undo", exact = true)
            assertEquals("undo: back to 100 %", 1f, adj.opacity, 1e-4f)
            assertEquals(before, live.steps())
            assertLiveOnlyWhereTheEffectShows("Undo of Opacity")
            assertTrue("the layer window shows it", SmokeUi.has("100%", exact = true))

            click("Redo", exact = true)
            assertEquals("redo: 99 % again", 0.99f, adj.opacity, 1e-4f)
            assertEquals(before + 1, live.steps())
            assertLiveOnlyWhereTheEffectShows("Redo of Opacity")
            assertTrue("the layer window shows it", SmokeUi.has("99%", exact = true))

            // Other properties of the same layer keep their whole-canvas redraw (and their step).
            click(LayerLabels.hide(c.doc.indexOf(adj) + 1), exact = true)
            assertFalse(adj.visible)
            click("Undo", exact = true)
            assertTrue(adj.visible)
            live.assertConverged("eye and its undo")
            Smoke.assertQuiet(c, "opacity undo")
        }
        dog.interrupt()
        harness.finish()
    }
}
