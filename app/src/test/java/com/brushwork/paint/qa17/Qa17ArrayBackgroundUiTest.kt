package com.brushwork.paint.qa17

import android.view.MotionEvent
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.RED
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.differing
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshRender
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VectorLayers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.7 final QA, item 3 / §6.3 on the user's 392 dp phone: a raster array whose cache renders in
 * the background (forced, with a renderer held at a gate: a slow phone), driven by a finger on the
 * Array sheet's "Drag sideways to change Constant X" handle.
 * - Drag and release: no step at once, the preview stays up (no flash of the old copies) through
 *   every frame until the swap; "Rendering array…" past 300 ms; the swap is ONE "Edit array" and
 *   the cache equals a fresh render.
 * - Drag and release again, then a two-finger tap on the canvas while it renders: the render lands
 *   first and the undo takes it back (one undo, nothing half done; I2); three fingers redo it.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.arraybackgroundsandbox"])
class Qa17ArrayBackgroundUiTest {

    @Test
    fun backgroundArrayRenderAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("a slider drag renders in the background; the preview stays; a two-finger tap meanwhile") { background(Qa17ArrayUi(h)) }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    private val scrub = "Drag sideways to change Constant X"

    /** One finger on the Constant X handle, [dxDp] dp to the right, [during] checked at every move, then lifted. */
    private fun dragHandle(u: Qa17ArrayUi, dxDp: Float, during: () -> Unit) {
        u.showArraySheet()
        u.intoView(scrub) { requireNotNull(SmokeUi.find(scrub, exact = true)) { "no \"$scrub\"" }.node }
        settle(20)
        val e = requireNotNull(SmokeUi.find(scrub, exact = true))
        val b = e.bounds
        // The window that shows the handle gets the finger (the sheet may sit in a window of its own).
        val touch = Smoke.Touch(e.window)
        val x0 = b.center.x
        val y0 = b.center.y
        u.s.touch.idle(300)
        touch.send(MotionEvent.ACTION_DOWN, P(0, x0, y0))
        try {
            for (i in 1..10) {
                u.s.touch.idle(16)
                touch.send(MotionEvent.ACTION_MOVE, P(0, x0 + dxDp * u.s.density * i / 10f, y0))
                settle(1)
                // (Past the touch slop and a step or two: the value has moved by then.)
                if (i >= 6) during()
            }
        } finally {
            u.s.touch.idle(16)
            touch.send(MotionEvent.ACTION_UP, P(0, x0 + dxDp * u.s.density, y0))
            settle(1, 16)
        }
    }

    private fun background(u: Qa17ArrayUi) {
        u.editor(Smoke.document(512, 512, layers = 2, whiteBottom = true))
        val c = u.c
        // A raster array of 12 copies of a 100 × 100 square, set up through the sheet (renders at once).
        u.seed(c.activeLayer, 20f, 20f, 120f, 120f, RED)
        c.setSelection(u.rectSelection(14f, 14f, 126f, 126f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        val layer: Layer = c.activeLayer
        val tool = c.currentTool as ArrayTool
        u.typeField("Count", "12")
        u.typeField("Relative X", "30")
        u.typeField("Relative Y", "30")
        u.settleRenders("12 copies")
        assertEquals(12, layer.array!!.spec.count)

        // From here the cache renders on a worker, held at a gate (a slow phone).
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        var gate = CountDownLatch(1)
        c.arrayRenders.workerHook = { gate.await(20, TimeUnit.SECONDS) }
        try {
            // ---- drag and release: the preview stays until the swap
            var spec0: ArraySpec = layer.array!!.spec
            var before = pixels(layer.bitmap)
            var n = u.steps()
            dragHandle(u, 60f) {
                assertNotNull("the preview shows while the finger moves: preview ${tool.previewSpec?.constantX}, layer ${layer.array?.spec?.constantX}", c.renderOverride)
                assertEquals("nothing recorded while dragging", n, u.steps())
            }
            val dragged = tool.previewSpec
            assertNotNull("the preview holds the dragged value", dragged)
            assertNotEquals("Constant X changed", spec0.constantX, dragged!!.constantX)
            assertTrue("released: rendering in the background", c.arrayRenders.isPending)
            assertEquals("no step before the swap", n, u.steps())
            assertEquals("the layer keeps its data until the swap", spec0, layer.array!!.spec)
            assertArrayEquals("and its pixels (I1)", before, pixels(layer.bitmap))
            // Every frame until the swap shows the preview: no flash of the old copies.
            repeat(4) { k ->
                settle(1, 100)
                assertNotNull("frame ${k + 1}: the preview is still up", c.renderOverride)
                assertEquals(dragged, tool.previewSpec)
            }
            assertTrue("\"${ArrayLabels.RENDERING}\" past 300 ms; shown: ${SmokeUi.shown().take(60)}", has(ArrayLabels.RENDERING))
            u.shot("background-rendering")
            gate.countDown()
            u.settleRenders("swapped")
            assertFalse("the chip goes", has(ArrayLabels.RENDERING))
            assertEquals("ONE step", n + 1, u.steps())
            assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
            assertEquals(dragged, layer.array!!.spec)
            assertNull("the preview handed over to the layer", c.renderOverride)
            assertEquals("I1: the swapped cache is the array's own render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))

            // ---- drag and release again; two fingers tap the canvas while it renders
            gate = CountDownLatch(1)
            spec0 = layer.array!!.spec
            before = pixels(layer.bitmap)
            n = u.steps()
            dragHandle(u, -40f) {}
            val again = requireNotNull(tool.previewSpec)
            assertTrue(c.arrayRenders.isPending)
            settle(2, 100)
            settle(2, 100)
            assertTrue("rendering, chip up", has(ArrayLabels.RENDERING))
            // The worker finishes a moment after the fingers land (the undo waits for it).
            val release = Thread { Thread.sleep(300); gate.countDown() }.apply { start() }
            u.s.touch.idle(400)
            val a = u.s.screen(150f, 60f)
            val b = u.s.screen(350f, 60f)
            assertTrue("the fingers land on the canvas", u.onCanvas(150f, 60f) || SmokeUi.has("Show Array", exact = true))
            u.s.touch.twoFingerTap(a, b)
            settle(4)
            release.join(5_000)
            u.settleRenders("undone")
            assertFalse("nothing left rendering", c.arrayRenders.isPending)
            assertFalse(has(ArrayLabels.RENDERING))
            assertEquals("the render landed and the undo took it back", n, u.steps())
            assertEquals("redo holds it", ArrayLabels.EDIT, c.undoManager.redoLabel)
            assertEquals(spec0, layer.array!!.spec)
            assertArrayEquals("the pixels as before the drag", before, pixels(layer.bitmap))
            assertNull(c.renderOverride)
            u.ui.threeFingerRedo()
            u.settleRenders("redone")
            assertEquals(n + 1, u.steps())
            assertEquals(again, layer.array!!.spec)
            assertEquals("I1 after the redo", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
            Smoke.assertQuiet(c, "background render")
        } finally {
            gate.countDown()
            c.arrayRenders.workerHook = null
        }
    }
}
