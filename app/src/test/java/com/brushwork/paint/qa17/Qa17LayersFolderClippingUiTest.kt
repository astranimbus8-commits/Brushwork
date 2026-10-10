package com.brushwork.paint.qa17

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerTreeRows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlin.math.roundToInt

/**
 * v1.7 final QA, layers cluster, verifier's flow (item 8, design §3.8: clipping is per level) on a
 * 360 dp phone, by fingers in the layer window, then the whole tree history walked back and
 * forward with two- and three-finger taps made over the layer window itself.
 *
 * The picture: a blue-grey backdrop (Layer 1), an orange block (Layer 2) and a green block
 * (Layer 3) clipped to it. Points: Q1 in orange only, Q2 in both, Q3 in green only, Q4 backdrop.
 *
 * 1. "Clipping" on Layer 3; Layer 2 ⋮ "Put in new folder" (Layer 3 now clips to the folder: the
 *    picture is unchanged); Layer 3 dragged by its ≡ onto the open folder (its top child, clipped
 *    to Layer 2 inside it: unchanged); Layer 2 swiped out of the folder (Layer 3 is left at the
 *    bottom of its level with no base: it draws unclipped, its row shows the greyed mark).
 * 2. Two fingers over the layer window, three times: each tree step undone exactly (order,
 *    parents, clipping, the active layer, the picture), no row reacting to the fingers; with the
 *    folder gone the project saves as format 1 (I14). Three fingers, three times: all redone,
 *    the same folder back, saved as format 3.
 * 3. One undo, then a finger stroke on Layer 3 inside the folder: one step, clipped to Layer 2.
 *    Saved and loaded: the same tree and picture.
 */
internal class Qa17LayersFolderClipping(private val h: ChromeHarness) {
    private lateinit var s: ChromeScreen
    private var u: Qa17LayersUi? = null
    private val l: Qa17LayersUi get() = u!!
    private val c: EditorController get() = s.c

    fun release() {
        u?.release()
        u = null
    }

    private fun document(): Document = Smoke.document(W, H, layers = 3, whiteBottom = false).also { d ->
        fill(d.layers[0], B, 0, 0, W, H)
        fill(d.layers[1], A, 40, 40, 140, 140)
        fill(d.layers[2], D, 90, 20, 200, 160)
    }

    private fun fill(x: Layer, color: Int, left: Int, top: Int, right: Int, bottom: Int) {
        Canvas(x.bitmap).drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Paint().apply { this.color = color })
        x.markChanged()
    }

    private fun at(p: Pair<Int, Int>): Int = Qa17LayersShots.pixel(c, p.first, p.second)

    private fun expectAt(what: String, p: Pair<Int, Int>, want: Int, tol: Int = 0) {
        val got = at(p)
        assertTrue("$what at $p: ${Qa17LayersShots.rgb(got)}, want ${Qa17LayersShots.rgb(want)} ± $tol", Qa17LayersShots.near(got, want, tol))
    }

    /** The tree as the user's history must give it back: order, parents, clipping flags. */
    private fun tree(): List<String> = c.doc.layers.map { x ->
        "${x.name}<${c.doc.layerById(x.parentId)?.name ?: "root"}>${if (x.clipping) " clip" else ""}#${x.id}"
    }

    private class State(val label: String?, val tree: List<String>, val picture: IntArray, val active: Layer)

    private fun state(label: String?) = State(label, tree(), l.pixels(), c.activeLayer)

    private fun expect(what: String, want: State, active: Layer = want.active) {
        assertEquals("$what: the tree", want.tree, tree())
        assertArrayEquals("$what: the picture", want.picture, l.pixels())
        assertSame("$what: the active layer", active, c.activeLayer)
    }

    /** Fingers over the layer window (dp of the editor): the list's middle, left and right of centre. */
    private fun overWindow(vararg fx: Float): List<Pair<Float, Float>> {
        val w = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        return fx.map { Finger.px(s, w.left + w.width * it, w.top + w.height * 0.45f) }
    }

    private fun twoFingersOverWindow() {
        val (a, b) = overWindow(0.3f, 0.7f)
        s.touch.idle(400)
        s.touch.twoFingerTap(a, b)
        settle(4)
    }

    private fun threeFingersOverWindow() {
        val (a, b, d) = overWindow(0.2f, 0.5f, 0.8f)
        s.touch.idle(400)
        s.touch.threeFingerTap(a, b, d)
        settle(4)
    }

    private fun formatVersion(): Int {
        val app = RuntimeEnvironment.getApplication()
        runBlocking { ProjectRepository(app).save(c.doc, null) }
        val json = File(app.filesDir, "projects/${c.doc.id}/project.json").readText()
        return Regex("\"formatVersion\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: throw AssertionError("no formatVersion")
    }

    // ================================================================== 1. by fingers

    private lateinit var states: MutableList<State>
    private lateinit var l1: Layer
    private lateinit var l2: Layer
    private lateinit var l3: Layer
    private lateinit var folder: Layer

    fun clipPerLevel() {
        File(RuntimeEnvironment.getApplication().filesDir, "projects").deleteRecursively()
        s = h.editor(document()) { it.snapping.enabled = false }
        u = Qa17LayersUi(s)
        settle()
        assertEquals("a 360 dp phone", 360f, s.widthDp, 1f)
        l1 = c.doc.layers[0]; l2 = c.doc.layers[1]; l3 = c.doc.layers[2]

        // "Clipping" on Layer 3: clipped to Layer 2.
        l.openLayers()
        l.pick(l3)
        l.oneStep("Clipping", "Clipping") { Finger.tap(s, LayerLabels.CLIPPING) }
        assertTrue(l3.clipping)
        expectAt("orange", Q1, A)
        expectAt("green over orange", Q2, D)
        expectAt("green outside orange is clipped away", Q3, B)
        expectAt("the backdrop", Q4, B)
        states = mutableListOf(state("Clipping"))
        val p0 = states[0].picture

        // Layer 2 ⋮ "Put in new folder": Layer 3 (above the folder) now clips to the folder.
        l.pick(l2)
        Finger.tap(s, LayerLabels.MORE)
        // (The menu is a popup window of its own: its item is clicked, as in the other classes.)
        l.oneStep("Put in new folder", HistoryLabels.PUT_IN_NEW_FOLDER) { SmokeUi.click(FolderLabels.PUT_IN_NEW, exact = true) }
        folder = c.doc.layers.single { it.isFolder }
        assertEquals(listOf(l1, l2, folder, l3), c.doc.layers)
        assertEquals(listOf(Layer.ROOT_ID, folder.id, Layer.ROOT_ID, Layer.ROOT_ID), c.doc.layers.map { it.parentId })
        assertTrue("pass-through", folder.folder!!.passThrough)
        assertArrayEquals("Layer 3 clips to the folder: the picture is unchanged", p0, l.pixels())
        states += state(HistoryLabels.PUT_IN_NEW_FOLDER)

        // Layer 3 dragged by its ≡ down onto the open folder's row (its top child, clipped to
        // Layer 2 inside it). The rows, top first: Layer 3, Folder 1, Layer 2, Layer 1; the drop
        // lands half-way between the folder's row and Layer 2's.
        l.reachRow(l3)
        val r3 = SmokeUi.find(l.row(l3), exact = true)!!.bounds
        val rf = SmokeUi.find(l.row(folder), exact = true)!!.bounds
        val r2 = SmokeUi.find(l.row(l2), exact = true)!!.bounds
        val target = (rf.center.y + r2.center.y) / 2f
        val dy = (((target - r3.center.y) / s.density) / 8f).roundToInt() * 8f
        println("Qa17LayersFolderClipping: rows ${r3.center.y / s.density} ${rf.center.y / s.density} ${r2.center.y / s.density} dp, drag $dy dp")
        l.oneStep("Layer 3 dragged onto the folder", HistoryLabels.MOVE_LAYER) { l.handle(l3, dy = dy) }
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)
        assertEquals(listOf(Layer.ROOT_ID, folder.id, folder.id, Layer.ROOT_ID), c.doc.layers.map { it.parentId })
        assertTrue("still clipping", l3.clipping)
        assertArrayEquals("Layer 3 clips to Layer 2 inside the folder: unchanged", p0, l.pixels())
        states += state(HistoryLabels.MOVE_LAYER)

        // Layer 2 (the folder's bottom child) swiped left out of it: Layer 3 is left at the bottom
        // of the folder with nothing to clip to, so it draws unclipped (its flag kept).
        l.oneStep("Swipe left", FolderLabels.MOVE_OUT) { l.handle(l2, dx = -48f) }
        assertEquals(listOf(l1, l2, l3, folder), c.doc.layers)
        assertEquals(listOf(Layer.ROOT_ID, Layer.ROOT_ID, folder.id, Layer.ROOT_ID), c.doc.layers.map { it.parentId })
        assertTrue("the flag is kept", l3.clipping)
        assertTrue("its row's greyed mark (no base)", LayerTreeRows.clipInfo(c.doc.layers, c.doc.indexOf(l3)).noBase)
        expectAt("orange", Q1, A)
        expectAt("green over orange", Q2, D)
        expectAt("green outside orange now shows", Q3, D)
        expectAt("the backdrop", Q4, B)
        states += state(FolderLabels.MOVE_OUT)
        Qa17LayersShots.saveGrid("folderclip-1-clip-folder-drag-out", states.map { it.picture }, W, H, 4)
        IbisShots.capture().let { b -> try { Qa17LayersShots.save(b, "folderclip-2-window-no-base-360dp") } finally { b.recycle() } }
        Smoke.assertQuiet(c, "by fingers")
    }

    // ================================================================== 2. history by fingers over the layer window

    fun historyOverTheWindow() {
        l.openLayers()
        assertEquals(4, states.size)
        // Undo three times: each tree step goes, exactly. The active layer is the one the step
        // started with (picking a row is no step, and a drop picks the dragged row first, as in
        // v1.6): Layer 2 for "Put in new folder", Layer 3 for the drag.
        val startedWith = mapOf(1 to l2, 2 to l3, 3 to states[2].active)
        for (i in 3 downTo 1) {
            val label = states[i].label!!
            val n = l.steps()
            twoFingersOverWindow()
            assertEquals("undo $label: one step back", n - 1, l.steps())
            expect("undo $label", states[i - 1], startedWith.getValue(i))
            assertTrue("the feedback \"Undo: $label\"; shown ${SmokeUi.shown().take(40)}", SmokeUi.shown().any { it == "Undo: $label" })
            assertNotNull("the layer window stays open", s.tagged(ChromeTags.LAYER_WINDOW))
            assertTrue("nothing opened", SmokeUi.sheetTitles().isEmpty())
        }
        assertTrue("no folder", c.doc.layers.none { it.isFolder })
        assertFalse("its row is gone", SmokeUi.has(FolderLabels.close(folder.name), exact = true))
        assertEquals("no folder: format 1 (I14)", 1, formatVersion())
        // Redo three times.
        for (i in 1..3) {
            val label = states[i].label!!
            val n = l.steps()
            threeFingersOverWindow()
            assertEquals("redo $label: one step forward", n + 1, l.steps())
            expect("redo $label", states[i])
            assertTrue("the feedback \"Redo: $label\"", SmokeUi.shown().any { it == "Redo: $label" })
            assertNotNull("the layer window stays open", s.tagged(ChromeTags.LAYER_WINDOW))
        }
        assertSame("the same folder is back", folder, c.doc.layers.single { it.isFolder })
        assertEquals("a folder: format 3 (I14)", 3, formatVersion())
        Smoke.assertQuiet(c, "history over the layer window")
    }

    // ================================================================== 3. paint inside, save, load

    fun paintInsideAndReload() {
        l.openLayers()
        twoFingersOverWindow()
        expect("back to Layer 3 in the folder over Layer 2", states[2])
        l.pick(l3)
        l.closeLayers()
        c.color = K
        c.brush = c.brush.copy(size = 10f, opacity = 1f, pressureSize = false)
        val before = IntArray(W * H).also { l3.bitmap.getPixels(it, 0, W, 0, 0, W, H) }
        l.oneStep("a stroke on Layer 3 in the folder", "Brush") { l.ui.stroke(60f to 120f, 120f to 121f, 180f to 122f) }
        val after = IntArray(W * H).also { l3.bitmap.getPixels(it, 0, W, 0, 0, W, H) }
        assertTrue("Layer 3 painted", after.indices.count { after[it] != before[it] } > 300)
        expectAt("the stroke over orange shows", 70 to 121, K, tol = 3)
        expectAt("the stroke outside orange is clipped away", 170 to 121, B)
        expectAt("green outside orange is still clipped away", Q3, B)
        val picture = Qa17LayersShots.shoot(c, "folderclip-3-painted-in-folder")
        val tree = tree()

        val app = RuntimeEnvironment.getApplication()
        val repo = ProjectRepository(app)
        runBlocking { repo.save(c.doc, null) }
        val loaded = runBlocking { repo.load(c.doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings.toList())
        u?.release()
        s = h.editor(loaded)
        u = Qa17LayersUi(s)
        settle()
        assertEquals("loaded: the same tree", tree, tree())
        assertArrayEquals("loaded: the same picture", picture, l.pixels())
        Smoke.assertQuiet(c, "paint inside and reload")
    }

    companion object {
        const val W = 240
        const val H = 180
        val B = 0xFF8CB4DC.toInt()
        val A = 0xFFE07830.toInt()
        val D = 0xFF30A050.toInt()
        val K = 0xFF2040C0.toInt()
        val Q1 = 60 to 60
        val Q2 = 110 to 100
        val Q3 = 170 to 100
        val Q4 = 20 to 170

        fun run() {
            ShadowLog.stream = null
            SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 60_000)
            val h = ChromeHarness()
            val t = Qa17LayersFolderClipping(h)
            val times = mutableListOf<String>()
            fun timed(name: String, block: () -> Unit) {
                val t0 = System.nanoTime()
                block()
                times += "$name ${(System.nanoTime() - t0) / 1_000_000} ms"
            }
            // One editor for 1 to 3 (a section closes its editors).
            h.section("clip per level, history over the window, paint and reload at 360 dp") {
                timed("1 clip per level by fingers") { t.clipPerLevel() }
                timed("2 history over the layer window") { t.historyOverTheWindow() }
                timed("3 paint inside, save, load") { t.paintInsideAndReload() }
            }
            println("Qa17LayersFolderClipping times: $times")
            t.release()
            dog.interrupt()
            h.finish()
        }
    }
}

/** Item 8 on a 360 dp phone: clipping per level through folder moves, and the tree's history by fingers over the layer window. */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.layersfolderclipping360sandbox"])
class Qa17LayersFolderClippingUiTest {
    @Test
    fun clippingFollowsTheLevelThroughFolderMovesAndTheTreeHistoryWorksOverTheLayerWindowAt360dp() = Qa17LayersFolderClipping.run()
}
