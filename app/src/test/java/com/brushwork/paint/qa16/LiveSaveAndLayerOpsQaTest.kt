package com.brushwork.paint.qa16

import android.graphics.Bitmap
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.mask.MaskHandles
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.layers.LayerLabels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

private fun Bitmap.px(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

/**
 * v1.6 final QA, I7 for the save (design §3.1 C2: proxies never reach a SAVED bitmap), on the
 * user's phone (392 dp, 1080 x 2408, policy LIVE, injected clock): the project is saved as
 * `EditorSession.save` does (the document and `renderThumbnail(512)`) in the middle of an
 * Exposure drag, while the change refines after Close, in the middle of a mask handle drag and in
 * the middle of the layer window's opacity drag of the adjustment layer; each time the reopened
 * project shows exactly what a compositor that never saw a session renders from the document, the
 * adjustment layer's effect, opacity, mask spec and mask pixels are the document's (the committed
 * mask while the handle is still held), and the saved thumbnail is the exact one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.livesavesandbox"])
class LiveSaveQaTest {
    private val w = 1080
    private val h = 2408

    @Test
    fun aSaveWhileTheAdjustmentIsLiveHoldsNothingPreviewOnly() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 150_000)
        val harness = ChromeHarness()
        harness.section("save while live") {
            var lc: LiveCanvas? = null
            val s = harness.editor(QaDocs.stack(w, h)) { c ->
                c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                c.liveAdjust.clock = { lc?.now ?: 0L }
            }
            val live = LiveCanvas(s).also { lc = it }
            val c = s.c
            File(s.activity.filesDir, "projects").deleteRecursively()
            val repo = ProjectRepository(s.activity.applicationContext)
            live.draw()
            val flow = MasksLiveFlow(live, w, h, "save")
            flow.createLinear()
            flow.addRadial()
            val adj = flow.adj
            val saves = mutableListOf<String>()

            /** Saves as `EditorSession.save` does, reopens, and compares with a session-free render of the document. */
            fun saveAndReopen(where: String, wasLive: Boolean) {
                assertEquals("$where: a session runs", wasLive, c.liveAdjust.isActive)
                val fresh = Compositor(c.doc) { null }
                val thumb = c.compositor.renderThumbnail(512)
                val thumbRef = fresh.renderThumbnail(512)
                assertSamePixels("$where: the thumbnail saved with the project", thumbRef.px(), thumb.px(), thumb.width)
                val shown = fresh.renderFlattened()
                runBlocking { repo.save(c.doc, thumb) }
                val loaded = runBlocking { repo.load(c.doc.id) }
                assertTrue("$where: no load warnings ${loaded.loadWarnings}", loaded.loadWarnings.isEmpty())
                assertEquals(c.doc.layers.map { it.name }, loaded.layers.map { it.name })
                val la = loaded.layers[c.doc.indexOf(adj)]
                assertEquals("$where: the effect and mask spec saved are the document's", adj.dataSnapshot(), la.dataSnapshot())
                assertEquals("$where: the opacity saved is the document's", adj.opacity, la.opacity, 0f)
                assertSamePixels("$where: the saved mask pixels are the document's", adj.mask!!.px(), la.mask!!.px(), w)
                val reopened = Compositor(loaded) { null }.renderFlattened()
                assertSamePixels("$where: the reopened picture", shown.px(), reopened.px(), w)
                listOf(thumb, thumbRef, shown, reopened).forEach { it.recycle() }
                saves += "$where (session ${c.liveAdjust.isActive})"
            }

            // 1. In the middle of an Exposure drag (frames drawn from proxies).
            settle()
            live.s.touch.idle(1300)
            click("Adjust…", exact = true)
            live.sliderDrag(live.sliderUnder("Exposure"), 0.5f, 0.85f, moves = 10) { i ->
                if (c.liveAdjust.isActive) live.liveFrame("Exposure move $i") else live.draw()
                if (i == 6) saveAndReopen("mid Exposure drag", wasLive = true)
            }
            // 2. While the change refines after Close (its one step recorded).
            click("Close", exact = true)
            settle()
            saveAndReopen("refining after Close", wasLive = true)
            live.assertConverged("Exposure")

            // 3. In the middle of a radius handle drag: the committed mask is saved, not the preview.
            val radial = adj.maskSpec!!.components.last() as RadialMask
            val p = MaskHandles.position(radial, MaskHandles.Kind.RX_POS, c.viewTransform)!!
            val specBefore = adj.maskSpec
            val maskBefore = adj.mask!!.px()
            live.canvasDrag(p.x to p.y, p.x + 160f to p.y, moves = 8) { i ->
                if (c.liveAdjust.isActive) live.liveFrame("handle move $i") else live.draw()
                if (i == 6) {
                    assertSame("the spec is committed when the handle lets go", specBefore, adj.maskSpec)
                    assertSamePixels("handle move $i: the layer's mask", maskBefore, adj.mask!!.px(), w)
                    saveAndReopen("mid handle drag", wasLive = true)
                }
            }
            assertEquals("Edit mask", c.undoManager.undoLabel)
            live.assertConverged("handle")

            // 4. In the middle of the layer window's opacity drag of the adjustment layer.
            click("Open layers")
            val slider = SmokeUi.find(LayerLabels.OPACITY, exact = true) ?: throw AssertionError("no layer opacity slider")
            live.sliderDrag(slider, 0.97f, 0.5f, moves = 8) { i ->
                if (c.liveAdjust.isActive) live.liveFrame("opacity move $i") else live.draw()
                if (i == 5) saveAndReopen("mid layer opacity drag", wasLive = true)
            }
            assertEquals("Opacity", c.undoManager.undoLabel)
            live.assertConverged("layer opacity")
            saveAndReopen("converged", wasLive = false)
            click(LayerLabels.CLOSE, exact = true)
            println("[qa16] save while live: ${saves.joinToString("; ")}")
            Smoke.assertQuiet(c, "save while live")
        }
        dog.interrupt()
        harness.finish()
    }
}

/**
 * v1.6 final QA, the layer window's structural actions while an adjustment change is still
 * refining (half the session's tiles exact, half from proxies), on the user's phone (392 dp,
 * 1080 x 2408, 3 layers below the adjustment layer and 2 above, policy LIVE, an injected clock
 * that ticks during the one refinement frame): "Merge down" of a layer BELOW the adjustment layer
 * (the session's below-cache splits the stack there), the eye of a layer below, Below 1 dragged
 * by its ≡ above the adjustment layer, and the adjustment layer deleted: one step each (I2), the
 * merge's pixels and the export the session-free ones, and once refined the canvas is bit for bit
 * the canvas no session touched (I7), after each action and after its Undo. "Merge down" on the
 * layer right above the adjustment layer is refused with one toast and no step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.liveopssandbox"])
class LiveLayerOpsWhileRefiningQaTest {
    private val w = 1080
    private val h = 2408
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun layerWindowActionsWhileTheAdjustmentRefinesConverge() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 150_000)
        val harness = ChromeHarness()
        harness.section("layer ops while refining") {
            var lc: LiveCanvas? = null
            val s = harness.editor(QaDocs.stack(w, h)) { c ->
                c.liveAdjust.policy = LiveAdjust.Policy.LIVE
                c.liveAdjust.clock = { lc?.clockRead() ?: 0L }
            }
            val live = LiveCanvas(s).also { lc = it }
            val c = s.c
            live.draw()
            val flow = MasksLiveFlow(live, w, h, "ops")
            flow.createLinear()
            val adj = flow.adj
            val (photo, below1, below2) = c.doc.layers.take(3)
            val above1 = c.doc.layers[4]
            val above2 = c.doc.layers[5]
            val stack = listOf(photo, below1, below2, adj, above1, above2)
            assertEquals(stack, c.doc.layers)
            settle()
            live.s.touch.idle(1300)
            val lw = LayerWindowQaFlow(s, "ops")
            lw.open()
            val log = mutableListOf<String>()

            /** The export (on white and transparent) is the session-free one. */
            fun assertExportExact(where: String) {
                val fresh = Compositor(c.doc) { null }
                for (bg in listOf<Int?>(white, null)) {
                    val a = if (bg == null) c.compositor.renderFlattened() else c.compositor.renderFlattened(bg)
                    val b = if (bg == null) fresh.renderFlattened() else fresh.renderFlattened(bg)
                    assertSamePixels("$where: export${if (bg == null) "" else " on white"}", b.px(), a.px(), w)
                    a.recycle(); b.recycle()
                }
            }

            /**
             * A discrete change of the adjustment layer ("−" on its opacity: through the live
             * session), then ONE frame with a ticking clock: the change is half refined.
             */
            fun halfRefined(where: String) {
                lw.tapRow(adj)
                assertSame(adj, c.activeLayer)
                lw.oneStep("$where: − on the adjustment layer's opacity", "Opacity") { lw.press(LayerLabels.LESS_OPACITY) }
                assertTrue("$where: the opacity change runs a live session", c.liveAdjust.isActive)
                live.tick = 3_000_000L
                try {
                    live.draw()
                } finally {
                    live.tick = 0L
                }
                assertTrue("$where: still refining after one frame", c.liveAdjust.isActive)
                assertTrue("$where: session tiles still wait", c.tiles.hasDirty)
            }

            // 1. Merge down of Below 2 into Below 1 while refining (the split moves down one layer).
            halfRefined("merge")
            lw.tapRow(below2)
            lw.oneStep("Merge down below the adjustment layer, refining", "Merge down") { lw.press(LayerLabels.MERGE) }
            assertEquals(listOf(photo, below1, adj, above1, above2), c.doc.layers)
            assertExportExact("merged while refining")
            val mergedRefining = below1.bitmap.px()
            log += "merge while refining: ${live.assertConverged("merge while refining")} frames to converge"
            lw.undo()
            assertEquals(stack, c.doc.layers)
            live.assertConverged("undo of the merge")
            lw.tapRow(below2)
            lw.oneStep("Merge down below the adjustment layer, exact", "Merge down") { lw.press(LayerLabels.MERGE) }
            assertSamePixels("Merge down: refining vs exact", below1.bitmap.px(), mergedRefining, w)
            live.assertConverged("merge when exact")
            lw.undo()
            assertEquals(stack, c.doc.layers)
            live.assertConverged("undo of the second merge")

            // 2. The eye of Below 1 while refining.
            halfRefined("eye")
            lw.reveal(below1)
            lw.oneStep("Hide Below 1, refining", "Visibility") { lw.press(LayerLabels.hide(c.doc.indexOf(below1) + 1)) }
            assertFalse(below1.visible)
            log += "hide below while refining: ${live.assertConverged("hide below while refining")} frames"
            lw.undo()
            assertTrue(below1.visible)
            live.assertConverged("undo of the hide")

            // 3. Below 1 dragged by its ≡ above the adjustment layer while refining.
            halfRefined("reorder")
            lw.reveal(below1)
            lw.reveal(adj)
            lw.oneStep("Below 1 above the adjustment layer, refining", "Move layer") { lw.dragReorder(below1, -2.2f) }
            assertTrue("Below 1 is above the adjustment layer now: ${c.doc.layers.map { it.name }}", c.doc.indexOf(below1) > c.doc.indexOf(adj))
            log += "reorder across the split while refining: ${c.doc.layers.map { it.name }}, ${live.assertConverged("reorder while refining")} frames"
            lw.undo()
            assertEquals(stack, c.doc.layers)
            live.assertConverged("undo of the reorder")

            // 4. "Merge down" on Above 1 (right above the adjustment layer): refused, one toast, no step.
            lw.tapRow(above1)
            val n = lw.steps()
            val refusal = "Layers can't be merged into an adjustment layer"
            assertFalse(SmokeUi.has(refusal, exact = true))
            lw.press(LayerLabels.MERGE)
            assertTrue("the refusal shows (the editor's message bar): ${SmokeUi.shown().take(60)}", SmokeUi.has(refusal, exact = true))
            assertEquals("no step", n, lw.steps())
            assertEquals(stack, c.doc.layers)

            // 5. The adjustment layer deleted while refining.
            halfRefined("delete")
            lw.oneStep("Delete the adjustment layer, refining", "Delete layer") { lw.press(LayerLabels.DELETE) }
            assertEquals(-1, c.doc.indexOf(adj))
            live.draw()
            assertFalse("the session ended with its layer (that frame and the next are exact)", c.liveAdjust.isActive)
            live.assertConverged("delete while refining")
            lw.undo()
            assertEquals(stack, c.doc.layers)
            live.assertConverged("undo of the delete")
            assertExportExact("at the end")

            log.forEach { println("[qa16] ops $it") }
            lw.log.forEach { println("[qa16] ops step $it") }
            Smoke.assertQuiet(c, "layer ops while refining")
        }
        dog.interrupt()
        harness.finish()
    }
}
