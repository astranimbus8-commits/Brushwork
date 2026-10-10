package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.MainActivity
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.vector.VectorLayers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * v1.7 QA (compat, process death): work the user asked for that is still landing in the
 * background when the app leaves the screen. On the phone, "Save selection" compresses for a
 * while (§6.3: up to 300 ms for a full 4000 × 5000 mask) and a large array renders on a worker
 * ("Rendering array…", §6.3: up to 1.5 s). Home pressed meanwhile: the onStop save must hold that
 * work, because the system may kill the app in the background before the next autosave (here
 * the user's longest choice, 120 s), and the artwork then reopens from disk.
 *
 * Through the real app (MainActivity) at 392 dp with fingers, on a project the real v1.6 build
 * saved ("V15 plain"). The two test seams only make the work slow, as on the T606: the
 * saved-selection compressor waits [PACK_MS] and the array worker [RENDER_MS] (and the array
 * renders in the background, as on the phone, instead of Robolectric's synchronous default).
 *
 * - A marquee drag, "Save selection", Home before it lands, process death: the reopened artwork
 *   lists the saved selection.
 * - "Array from selection", then "Array in a circle" while the array still renders, Home,
 *   process death: the reopened artwork has the circle, with the circle's pixels (I1 on disk).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.compatpendingsandbox"])
class Qa17CompatPendingWorkOnStopUiTest {
    private val failures = mutableListOf<Throwable>()
    private lateinit var ctl: ActivityController<MainActivity>
    private lateinit var app: BrushworkApp
    private val activity: MainActivity get() = ctl.get()
    private lateinit var id: String
    private val projectDir: File get() = File(app.filesDir, "projects/$id")

    private fun note(s: String) = println("[qa17 compat pending] $s")

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        val t0 = System.nanoTime()
        try {
            block()
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        }
        note("section \"$name\": ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    // ------------------------------------------------------------------ helpers

    private val session: EditorSession get() = app.editorSession ?: throw AssertionError("no editor session")
    private val c: EditorController get() = (session.state as EditorSession.State.Ready).controller

    private fun canvas(): CanvasView = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas")

    private fun ui(): Qa16Ui = Qa16Ui(ChromeScreen(activity, c))

    private fun screen(x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        canvas().getLocationInWindow(loc)
        val p = c.viewTransform.docToScreen(x, y)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    private fun stroke(vararg doc: Pair<Float, Float>) {
        val t = Smoke.Touch(activity.window.decorView)
        t.idle(300)
        t.stroke(*doc.map { screen(it.first, it.second) }.toTypedArray())
        settle(4)
    }

    private fun waitForEditor(where: String) {
        assertTrue("$where: the editor opened", Smoke.pumpUntil(30_000) {
            settle(1)
            (app.editorSession?.state as? EditorSession.State.Ready) != null &&
                (Smoke.find(activity.window.decorView, CanvasView::class.java)?.width ?: 0) > 0
        })
        assertTrue("$where: settled", Smoke.pumpUntil(30_000) { settle(1); c.busyMessage == null && !c.vectors.isRendering && c.pendingSavedSelections.isEmpty() && !c.arrayRenders.isPending })
        assertEquals(id, c.doc.id)
    }

    private fun cell(label: String): SemanticsNode? {
        var n = SmokeUi.find(label, exact = true)?.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n
    }

    private fun tool(label: String) {
        ui().tool(label)
        settle(2)
        assertTrue("\"$label\" is the tool", has("Tools (current: $label)", exact = true))
    }

    /** ✓ and ✕ show exactly while the tool has pending work. */
    private fun assertPendingButtons(where: String) {
        settle(2)
        val label = c.currentTool.id.label.lowercase()
        val pending = c.currentTool.hasPendingWork
        val stray = SmokeUi.shown().filter { (it.startsWith("Apply ") || it.startsWith("Discard ")) && it.endsWith(" edit") }
        assertEquals("$where: ✓ / ✕ (${c.activeToolId})", if (pending) setOf("Apply $label edit", "Discard $label edit") else emptySet<String>(), stray.toSet())
    }

    /** The layer window's row of [layer] picked by a finger (the window opened and closed again). */
    private fun pick(layer: Layer) {
        if (c.activeLayer === layer) return
        if (!has(LayerLabels.CLOSE, exact = true)) click("Open layers (active layer ${c.doc.activeLayerIndex + 1})", exact = true)
        Smoke.pump(600)
        settle()
        try {
            val n = c.doc.indexOf(layer) + 1
            scrollToRow(n)
            click(LayerLabels.selectRow(n), exact = true)
        } finally {
            if (has(LayerLabels.CLOSE, exact = true)) click(LayerLabels.CLOSE, exact = true)
            settle()
        }
        assertTrue("\"${layer.name}\" picked", c.activeLayer === layer)
    }

    /** Scrolls the layer list as a finger does until row [n] ("Select layer n") is on screen. */
    private fun scrollToRow(n: Int) {
        val label = LayerLabels.selectRow(n)
        val rowRe = Regex("^Select layer (\\d+)$")
        repeat(40) {
            if (cell(label) != null) {
                ui().reach(label, 0f)
                return
            }
            val visible = SmokeUi.shown().mapNotNull { rowRe.find(it)?.groupValues?.get(1)?.toInt() }
            require(visible.isNotEmpty()) { "no layer rows; shown: ${SmokeUi.shown().take(60)}" }
            var p: SemanticsNode? = cell(LayerLabels.selectRow(visible.first()))
            while (p != null && !(p.config.getOrNull(SemanticsActions.ScrollBy) != null && p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null)) p = p.parent
            val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "the layer list does not scroll" }
            scroll.invoke(0f, (if (n > visible.max()) -60f else 60f) * activity.resources.displayMetrics.density)
            settle(2)
        }
        throw AssertionError("row $n never came into the layer list")
    }

    /** A marquee drag by a finger over the raster layer's picture. */
    private fun marquee() {
        pick(c.doc.layers.single { it.name == "Raster" })
        tool(ToolId.MARQUEE.label)
        stroke(80f to 60f, 120f to 90f, 160f to 130f)
        assertTrue("a selection", Smoke.pumpUntil { settle(1); c.selection != null })
    }

    private fun dto(): ProjectFileDto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), File(projectDir, ProjectFormat.PROJECT_FILE).readText())

    /**
     * Home (the onStop save), a moment in the background (what was still landing lands in the
     * app's memory), then the process dies and a new activity starts from the saved state: the
     * artwork reopens from disk. [inMemory] runs just before the death, on the old controller.
     */
    private fun homeThenProcessDeath(inMemory: (EditorController) -> Unit) {
        val old = session
        val oldC = c
        val rev = dto().revision
        val t0 = System.nanoTime()
        ctl.pause().stop()
        assertTrue("the onStop save", Smoke.pumpUntil(5_000) { dto().revision > rev })
        val onStopMs = (System.nanoTime() - t0) / 1_000_000
        // Still alive in the background for a moment (well short of the 120 s autosave).
        Smoke.pumpUntil(1_500) { false }
        assertFalse("nothing still landing in memory", oldC.arrayRenders.isPending || oldC.pendingSavedSelections.isNotEmpty())
        inMemory(oldC)
        val bundle = Bundle()
        ctl.saveInstanceState(bundle)
        ctl.destroy()
        old.scope.cancel()
        val field = BrushworkApp::class.java.getDeclaredField("editorSession\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(app) as androidx.compose.runtime.MutableState<EditorSession?>).value = null
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup(bundle)
        settle()
        assertTrue("a new session", app.editorSession !== old)
        waitForEditor("restored")
        note("Home: onStop save after ${onStopMs} ms (real), restored after ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun shot(name: String) {
        val bmp = IbisShots.capture()
        val qa = File("../../_tools/v17-qa-shots").absoluteFile.normalize()
        val dir = (if (qa.isDirectory) qa else File("build/qa17-shots").absoluteFile).apply { mkdirs() }
        val f = File(dir, "compat-$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        note("shot ${f.path}")
    }

    // ------------------------------------------------------------------ the test

    @Test
    fun workStillLandingWhenTheAppLeavesTheScreenSurvivesProcessDeath() {
        val t0 = System.nanoTime()
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        app = RuntimeEnvironment.getApplication() as BrushworkApp
        // The user's longest autosave interval: only the onStop save can hold the work here.
        app.settings.autosaveSeconds = 120
        id = Qa17CompatV16Goldens.install(app, "plain")
        SmokeUi.markBaseline()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue("the gallery lists the v1.6 project", Smoke.pumpUntil { settle(1); has("V15 plain", exact = true) })
        click("V15 plain", exact = true)
        waitForEditor("from the gallery")
        section("a saved selection still compressing when Home is pressed") { savedSelection() }
        section("an array still rendering when Home is pressed") { arrayRendering() }
        dog.interrupt()
        note("total ${(System.nanoTime() - t0) / 1_000_000} ms")
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun savedSelection() {
        marquee()
        // As on the phone: compressing the mask takes a while.
        c.beforeSavedSelectionPack = { delay(PACK_MS) }
        ui().reach(SavedSelectionLabels.SAVE)
        click(SavedSelectionLabels.SAVE, exact = true)
        val pending = c.pendingSavedSelections.singleOrNull() ?: throw AssertionError("the save is still compressing")
        assertTrue("not landed yet", c.doc.savedSelections.none { it.id == pending.id })
        homeThenProcessDeath { old -> assertTrue("landed in memory", old.doc.savedSelections.any { it.id == pending.id }) }
        assertTrue("saved on leaving: \"${pending.name}\" reopens", c.doc.savedSelections.any { it.id == pending.id && it.name == pending.name })
        assertTrue("in project.json", dto().selections.any { it.id == pending.id })
        assertEquals("format 1 still (v1.6 opens it)", 1, dto().formatVersion)
        assertPendingButtons("restored after a pending save")
        Smoke.assertQuiet(c, "restored after a pending save")
    }

    private fun arrayRendering() {
        marquee()
        ui().reach(ArrayLabels.FROM_SELECTION)
        click(ArrayLabels.FROM_SELECTION, exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); c.busyMessage == null && !c.arrayRenders.isPending })
        val arrayed = c.activeLayer
        assertNotNull("an array layer", arrayed.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertEquals(ArrayMode.LINE, arrayed.array!!.spec.mode)
        val line = pixels(arrayed.bitmap)
        assertTrue("the line's copies show", line.any { it ushr 24 != 0 })
        // As on the phone: a large array's cache renders on the worker for a while.
        c.arrayRenders.policy = VectorLayers.Policy.ASYNC
        c.arrayRenders.workerHook = { Thread.sleep(RENDER_MS) }
        ui().reach(ArrayLabels.CIRCLE, 44f)
        click(ArrayLabels.CIRCLE, exact = true)
        assertTrue("the circle renders in the background", c.arrayRenders.isPendingOn(arrayed))
        assertEquals("the old array until it lands (I1)", ArrayMode.LINE, arrayed.array!!.spec.mode)
        var landed: IntArray? = null
        homeThenProcessDeath { old ->
            val l = old.doc.layerById(arrayed.id)!!
            assertEquals("landed in memory", ArrayMode.CIRCLE, l.array!!.spec.mode)
            landed = pixels(l.bitmap)
            assertFalse("the circle's pixels, not the line's", landed!!.contentEquals(line))
        }
        val again = c.doc.layerById(arrayed.id) ?: throw AssertionError("the array layer reopens")
        assertEquals("saved on leaving: the circle reopens", ArrayMode.CIRCLE, again.array?.spec?.mode)
        assertArrayEquals("with the circle's pixels (data and pixels together, I1)", landed, pixels(again.bitmap))
        assertFalse("no \"${ArrayLabels.RENDERING}\" left", has(ArrayLabels.RENDERING, exact = true))
        assertPendingButtons("restored after a pending array render")
        Smoke.assertQuiet(c, "restored after a pending array render")
        shot("pending-on-stop")
    }

    private companion object {
        const val PACK_MS = 400L
        const val RENDER_MS = 400L
    }
}
