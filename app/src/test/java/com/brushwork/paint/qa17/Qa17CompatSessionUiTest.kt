package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.MainActivity
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.SymmetryType
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
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.docLength
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * v1.7 QA (compat): a project the real v1.6 build saved ([Qa17CompatV16Goldens], "V15 plain"),
 * through the real app (MainActivity) on the user's 392 dp phone with fingers.
 *
 * - Opened from the gallery and left: nothing is written (the v1.6 files stay v1.6's).
 * - A long session with the v1.7 tools through their labels: the Symmetry tool's "Rotation ruler"
 *   and a symmetric stroke on a vector layer; a stroke whose tool is switched to Pathfinder in its
 *   middle; Path points made "Fill only" while the app goes to the background; a selection saved
 *   ("Save selection") and arrayed ("Array from selection", "Array in a circle"); a layer put in
 *   a new folder; a text kerned in the text editor. A finger still down when the app leaves the
 *   screen. Undo of every step and redo of every step with the top bar's buttons.
 * - Process death (the activity's state saved, the app's memory gone, a new activity from that
 *   state, which reopens the artwork from disk) with the text editor open, and with a Free deform
 *   pending: no crash, the editor opens again, ✓ / ✕ never stuck, what was saved on leaving the
 *   screen is there and the pending work is not applied.
 * - Back to the gallery: the artwork opens again with every v1.6 layer untouched (data and file),
 *   and every v1.7 datum the session made; it has a folder, so it is now format 3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.compatsessionsandbox"])
class Qa17CompatSessionUiTest {
    private val failures = mutableListOf<Throwable>()
    private lateinit var ctl: ActivityController<MainActivity>
    private lateinit var app: BrushworkApp
    private val activity: MainActivity get() = ctl.get()
    private lateinit var id: String
    private val projectDir: File get() = File(app.filesDir, "projects/$id")
    private val log = StringBuilder()

    private fun note(s: String) { log.appendLine(s); println("[qa17 compat session] $s") }

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

    private fun touch() = Smoke.Touch(activity.window.decorView)

    private fun tap(x: Float, y: Float) {
        val t = touch()
        t.idle(300)
        val (sx, sy) = screen(x, y)
        t.tap(sx, sy)
        settle(4)
    }

    private fun stroke(vararg doc: Pair<Float, Float>) {
        val t = touch()
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
        assertTrue("$where: settled", Smoke.pumpUntil(30_000) { settle(1); c.busyMessage == null && !c.vectors.isRendering && c.pendingSavedSelections.isEmpty() })
        assertEquals(id, c.doc.id)
    }

    private fun open() {
        assertTrue("the gallery lists the v1.6 project", Smoke.pumpUntil { settle(1); has("V15 plain", exact = true) })
        click("V15 plain", exact = true)
        waitForEditor("from the gallery")
    }

    private fun backToGallery() {
        click("Back to gallery", settleAfter = false)
        assertTrue("back in the gallery", Smoke.pumpUntil(30_000) { settle(1); app.editorSession == null && has("New canvas") })
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

    /** ✓ and ✕ show exactly while the tool has pending work (never stuck, never missing). */
    private fun assertPendingButtons(where: String) {
        settle(2)
        val label = c.currentTool.id.label.lowercase()
        val pending = c.currentTool.hasPendingWork
        assertEquals("$where: ✓ while pending ($pending, ${c.activeToolId})", pending, has("Apply $label edit", exact = true))
        assertEquals("$where: ✕ while pending ($pending, ${c.activeToolId})", pending, has("Discard $label edit", exact = true))
        val stray = SmokeUi.shown().filter { (it.startsWith("Apply ") || it.startsWith("Discard ")) && it.endsWith(" edit") }
        assertEquals("$where: no ✓ / ✕ of another tool", if (pending) setOf("Apply $label edit", "Discard $label edit") else emptySet<String>(), stray.toSet())
    }

    /** The layer window's row of [layer] picked by a tap (the window opened and closed again). */
    private fun pick(layer: Layer) {
        if (c.activeLayer === layer) return
        openLayers()
        try {
            val row = LayerLabels.selectRow(c.doc.indexOf(layer) + 1)
            scrollToRow(c.doc.indexOf(layer) + 1)
            click(row, exact = true)
        } finally {
            closeLayers()
        }
        assertTrue("\"${layer.name}\" picked", c.activeLayer === layer)
    }

    private fun openLayers() {
        if (!has(LayerLabels.CLOSE, exact = true)) click("Open layers (active layer ${c.doc.activeLayerIndex + 1})", exact = true)
        Smoke.pump(600)
        settle()
    }

    private fun closeLayers() {
        if (has(LayerLabels.CLOSE, exact = true)) click(LayerLabels.CLOSE, exact = true)
        settle()
    }

    /** Scrolls the layer list as a finger does until row [n] ("Select layer n") is wholly on screen. */
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
            while (p != null && !(p.config.getOrNull(SemanticsActions.ScrollBy) != null && p.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) != null)) p = p.parent
            val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "the layer list does not scroll" }
            // The list shows the top layer first: a higher number is further up.
            scroll.invoke(0f, (if (n > visible.max()) -60f else 60f) * activity.resources.displayMetrics.density)
            settle(2)
        }
        throw AssertionError("row $n never came into the layer list")
    }

    /** A tap with the Text tool at ([x], [y]), then "Edit text": the text editor. */
    /** The [k]th spot (left to right, bottom up) where a Text tool tap makes a new text. */
    private fun textSpot(k: Int): Pair<Float, Float> {
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        val free = ArrayList<Pair<Float, Float>>()
        for (y in listOf(400f, 360f, 320f, 280f)) for (x in listOf(440f, 500f, 560f, 380f)) if (text.textLayerAt(Vec2(x, y)) == null) free += x to y
        return free.getOrNull(k) ?: throw AssertionError("no free spot for a text: $free")
    }

    private fun newText(at: Pair<Float, Float>): TextTool {
        tool(ToolId.TEXT.label)
        tap(at.first, at.second)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        if (!text.editorOpen) {
            ui().reach("Edit text")
            click("Edit text", exact = true)
        }
        assertTrue("the text editor opened", Smoke.pumpUntil { settle(1); text.editorOpen })
        return text
    }

    /**
     * Home, then back to the app. `restart()` (onRestart, onStart), not `start()`: after a bare
     * `start()` Robolectric leaves the activity marked stopped, and its next `stop()` skips onStop
     * (and with it the onStop save).
     */
    private fun backgroundAndBack() {
        ctl.pause().stop()
        settle(2)
        ctl.restart().resume()
        settle()
    }

    private fun revision(): Long = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), File(projectDir, ProjectFormat.PROJECT_FILE).readText()).revision

    /**
     * The app leaves the screen (its onStop save writes [unsaved], an applied edit not saved
     * yet), its process dies (the activity's state is kept, the app's memory is not) and a new
     * activity starts from that state, which opens the artwork again from disk.
     */
    private fun processDeath(unsaved: Layer) {
        val old = session
        assertTrue("\"${unsaved.name}\" has an edit to save", unsaved.savedVersion != unsaved.contentVersion)
        val bundle = Bundle()
        val t0 = System.nanoTime()
        ctl.pause().stop()
        // 3 s: well short of the autosave (45 s of fake time), so only the onStop save counts.
        assertTrue("the onStop save wrote \"${unsaved.name}\"", Smoke.pumpUntil(3_000) { unsaved.savedVersion == unsaved.contentVersion })
        val t1 = System.nanoTime()
        ctl.saveInstanceState(bundle)
        ctl.destroy()
        // The process is gone: nothing of the old session runs, the app forgets it.
        old.scope.cancel()
        val field = BrushworkApp::class.java.getDeclaredField("editorSession\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(app) as androidx.compose.runtime.MutableState<EditorSession?>).value = null
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup(bundle)
        settle()
        assertTrue("a new session", app.editorSession !== old)
        waitForEditor("restored")
        note("process death: onStop save ${(t1 - t0) / 1_000_000} ms, restore ${(System.nanoTime() - t1) / 1_000_000} ms")
    }

    /** A brush stroke by a finger on "Raster" (applied, not saved yet); returns its pixels after. */
    private fun rasterStroke(y: Float): IntArray {
        val raster = c.doc.layers.single { it.name == "Raster" }
        pick(raster)
        tool(ToolId.BRUSH.label)
        val steps = c.undoManager.undoCount
        stroke(60f to y, 150f to y + 10f, 240f to y)
        assertEquals("the stroke is one step", steps + 1, c.undoManager.undoCount)
        return pixels(raster.bitmap)
    }

    private fun files(): Map<String, ByteArray> = projectDir.listFiles()!!.filter { it.name != "thumb.png" }.associate { it.name to it.readBytes() }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun shot(name: String) {
        val bmp = IbisShots.capture()
        val qa = File("../../_tools/v17-qa-shots").absoluteFile.normalize()
        val dir = (if (qa.isDirectory) qa else File("build/qa17-shots").absoluteFile).apply { mkdirs() }
        val f = File(dir, "compat-$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        note("shot ${f.path}")
    }

    /** Document points where no object of [layer] is, left to right in a zigzag. */
    private fun emptySpots(layer: Layer, n: Int): List<Pair<Float, Float>> {
        val tol = c.docLength(24f)
        val free = ArrayList<Pair<Float, Float>>()
        for (y in listOf(60f, 120f, 180f, 240f)) for (x in listOf(60f, 140f, 220f, 300f, 380f, 460f, 540f)) {
            if (c.vectors.hitTest(layer, Vec2(x, y), tol) == null) free += x to y
        }
        for (start in free) {
            val out = arrayListOf(start)
            while (out.size < n) {
                val (lx, ly) = out.last()
                out += free.firstOrNull { (x, y) -> x >= lx + 80f && kotlin.math.abs(y - ly) >= 60f } ?: break
            }
            if (out.size == n) return out
        }
        throw AssertionError("no room for $n points: $free")
    }

    // ------------------------------------------------------------------ the test

    private lateinit var v16Data: Map<Long, LayerData>
    private lateinit var v16FileOf: Map<Long, String?>
    private var made = LinkedHashMap<String, Long>()

    @Test
    fun aV16ArtworkThroughALongV17Session() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        app = RuntimeEnvironment.getApplication() as BrushworkApp
        id = Qa17CompatV16Goldens.install(app, "plain")
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), File(projectDir, ProjectFormat.PROJECT_FILE).readText())
        v16FileOf = dto.layers.associate { it.id to it.file }
        SmokeUi.markBaseline()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        section("a v1.6 artwork opens and closes unchanged") { openAndLeave() }
        section("the v1.7 tools") { v17Tools() }
        section("a finger down while the app leaves the screen") { fingerDownOnStop() }
        section("undo and redo across the tools") { undoRedoAll() }
        section("process death with the text editor open") { deathWithTextEditor() }
        section("process death with a Free deform pending") { deathWithFreeDeform() }
        section("back to the gallery: the artwork opens again") { reopen() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun openAndLeave() {
        val v16 = files()
        open()
        assertEquals(emptyList<String>(), c.doc.loadWarnings)
        assertEquals(18, c.doc.layers.size)
        v16Data = c.doc.layers.associate { it.id to it.dataSnapshot() }
        assertPendingButtons("opened")
        Smoke.assertQuiet(c, "opened")
        backToGallery()
        val after = files()
        assertEquals("the same files", v16.keys.sorted(), after.keys.sorted())
        for ((name, bytes) in v16) assertArrayEquals("$name: nothing was saved", bytes, after.getValue(name))
        assertFalse("no thumbnail written", File(projectDir, "thumb.png").exists())
    }

    private fun v17Tools() {
        open()
        val vector = c.doc.layers.single { it.name == "Vector 2" }
        val raster = c.doc.layers.single { it.name == "Raster" }

        // Symmetry: the tool's "Rotation ruler" chip, "Done" back to the brush.
        pick(vector)
        tool(SymmetryLabels.TOOL)
        assertEquals("picking the tool turns the mirror on", SymmetryType.MIRROR, c.symmetry.type)
        ui().reach(SymmetryType.ROTATION.label, 32f)
        click(SymmetryType.ROTATION.label, exact = true)
        assertEquals(SymmetryType.ROTATION, c.symmetry.type)
        click("Done", exact = true)
        assertEquals(ToolId.BRUSH, c.activeToolId)
        val steps0 = c.undoManager.undoCount
        val objects0 = vector.vector!!.objects.size
        c.color = 0xFF2266AA.toInt()
        stroke(*Array(12) { k -> (360f + k * 12f) to (110f + 25f * kotlin.math.sin(k / 2.0).toFloat()) })
        assertTrue(Smoke.pumpUntil { settle(1); !c.vectors.isRendering })
        assertEquals("a symmetric stroke is one step", steps0 + 1, c.undoManager.undoCount)
        val sym = vector.vector!!.objects.last() as VStroke
        assertEquals("one stroke", objects0 + 1, vector.vector!!.objects.size)
        assertEquals("a copy per division", c.symmetry.divisions, sym.copies.size)
        made["symmetric stroke"] = sym.id
        assertPendingButtons("symmetric stroke")

        // A stroke whose tool is switched to Pathfinder in its middle (another finger on the menu).
        val steps1 = c.undoManager.undoCount
        val t = touch()
        val (ax, ay) = screen(380f, 200f)
        val (bx, by) = screen(520f, 200f)
        t.idle(300)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, ax, ay))
        for (k in 1..4) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, ax + (bx - ax) * k / 8f, ay)) }
        tool(ToolId.PATHFINDER.label)
        for (k in 5..8) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, ax + (bx - ax) * k / 8f, ay)) }
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, bx, by))
        settle()
        assertTrue(Smoke.pumpUntil { settle(1); !c.vectors.isRendering })
        assertFalse("no gesture left open", c.isInteracting)
        val kept = c.undoManager.undoCount - steps1
        note("stroke with the tool switched to Pathfinder mid-stroke: ${if (kept == 1) "kept as one step" else "dropped ($kept steps)"}")
        assertTrue("at most one step ($kept)", kept in 0..1)
        assertPendingButtons("tool switched mid-stroke")
        Smoke.assertQuiet(c, "tool switched mid-stroke")

        // Symmetry off again (the Symmetry tool's "Off" chip), then Path points made "Fill only",
        // pending while the app goes to the background and comes back.
        tool(SymmetryLabels.TOOL)
        ui().reach(SymmetryType.OFF.label, 32f)
        click(SymmetryType.OFF.label, exact = true)
        assertEquals(SymmetryType.OFF, c.symmetry.type)
        click("Done", exact = true)
        tool(ToolId.PATH.label)
        val spots = emptySpots(vector, 3)
        for ((x, y) in spots) tap(x, y)
        assertTrue("three points pending", c.currentTool.hasPendingWork)
        ui().reach(CurveLabels17.FILL_ONLY)
        click(CurveLabels17.FILL_ONLY, exact = true)
        assertPendingButtons("Fill only pending")
        backgroundAndBack()
        assertTrue("still pending after the background", c.currentTool.hasPendingWork)
        assertPendingButtons("back from the background")
        val steps2 = c.undoManager.undoCount
        click("Apply path edit", exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); !c.vectors.isRendering })
        assertEquals("✓ is one step", steps2 + 1, c.undoManager.undoCount)
        val path = vector.vector!!.objects.last() as VPath
        assertNotNull("a Path curve", path.spline)
        assertNotNull("filled", path.fill)
        assertNull("no stroke", path.stroke)
        made["fill-only path"] = path.id
        assertPendingButtons("path applied")

        // A selection on the raster layer: saved, then arrayed in a circle.
        pick(raster)
        tool(ToolId.MARQUEE.label)
        stroke(80f to 60f, 120f to 90f, 160f to 130f)
        assertTrue(Smoke.pumpUntil { settle(1); c.selection != null })
        val saved0 = c.doc.savedSelections.size
        val steps3 = c.undoManager.undoCount
        ui().reach(SavedSelectionLabels.SAVE)
        click(SavedSelectionLabels.SAVE, exact = true)
        assertTrue("the selection is saved", Smoke.pumpUntil { settle(1); c.pendingSavedSelections.isEmpty() && c.doc.savedSelections.size == saved0 + 1 })
        assertEquals("\"Save selection\" is one step", steps3 + 1, c.undoManager.undoCount)
        made["saved selection"] = c.doc.savedSelections.last().id
        ui().reach(ArrayLabels.FROM_SELECTION)
        click(ArrayLabels.FROM_SELECTION, exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); c.busyMessage == null })
        val arrayed = c.activeLayer
        assertNotNull("an array layer", arrayed.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        ui().reach(ArrayLabels.CIRCLE, 44f)
        click(ArrayLabels.CIRCLE, exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); c.busyMessage == null })
        made["array"] = arrayed.id
        if (c.selection != null) {
            ui().reach("Clear the selection")
            click("Clear the selection", exact = true)
        }
        tool(ToolId.BRUSH.label)
        assertPendingButtons("arrayed")

        // The array layer put in a new folder (the layer window's ⋮).
        openLayers()
        ui().reach(LayerLabels.MORE, 0f)
        click(LayerLabels.MORE, exact = true)
        ui().reach(FolderLabels.PUT_IN_NEW, 0f)
        click(FolderLabels.PUT_IN_NEW, exact = true)
        settle()
        val folder = c.doc.layerById(arrayed.parentId)
        assertNotNull("the array is in a folder", folder?.folder)
        made["folder"] = folder!!.id
        closeLayers()

        // A text kerned in the text editor.
        val text = newText(textSpot(0))
        SmokeUi.field("Text").focus()
        settle(2)
        SmokeUi.field("Text").type("AVATAR")
        settle(2)
        val set = SmokeUi.field("Text").node.config.getOrNull(SemanticsActions.SetSelection)?.action ?: throw AssertionError("no selection action")
        set(1, 1, false)
        settle()
        val increase = "Increase ${KerningLabels.KERNING}"
        ui().reach(increase)
        click(increase, exact = true)
        assertTrue("kerned", text.item!!.kerns.isNotEmpty())
        click("OK", exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); !text.editorOpen })
        if (c.currentTool.hasPendingWork) click("Apply text edit", exact = true)
        settle()
        val kerned = c.doc.layers.last { it.isTextLayer && TextCodec.decode(it.textData)?.text == "AVATAR" }
        assertTrue("the kern is stored", TextCodec.decode(kerned.textData)!!.kerns.isNotEmpty())
        made["kerned text"] = kerned.id
        assertPendingButtons("text applied")
        Smoke.assertQuiet(c, "the v1.7 tools")
        note("v1.7 tools: ${c.undoManager.undoCount} steps; made $made")
    }

    private fun fingerDownOnStop() {
        val raster = c.doc.layers.single { it.name == "Raster" }
        pick(raster)
        tool(ToolId.BRUSH.label)
        val before = pixels(raster.bitmap)
        val steps = c.undoManager.undoCount
        val t = touch()
        val (ax, ay) = screen(60f, 300f)
        val (bx, by) = screen(240f, 330f)
        t.idle(300)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, ax, ay))
        for (k in 1..4) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, ax + (bx - ax) * k / 8f, ay + (by - ay) * k / 8f)) }
        val rev = revision()
        ctl.pause().stop()
        // 3 s: well short of the autosave (45 s of fake time), so only the onStop save counts.
        val savedOnStop = Smoke.pumpUntil(3_000) { revision() > rev }
        settle(2)
        ctl.restart().resume()
        settle()
        for (k in 5..8) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, ax + (bx - ax) * k / 8f, ay + (by - ay) * k / 8f)) }
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, bx, by))
        settle()
        assertFalse("no gesture left open", c.isInteracting)
        val kept = c.undoManager.undoCount - steps
        note("finger down through onStop: ${if (kept == 1) "the stroke kept as one step" else "dropped ($kept steps)"}; the onStop save ${if (savedOnStop) "landed" else "did not land"}")
        assertTrue("the onStop save landed with a finger down", savedOnStop)
        assertTrue("at most one step ($kept)", kept in 0..1)
        if (kept == 0) assertArrayEquals("dropped: the raster untouched", before, pixels(raster.bitmap))
        assertPendingButtons("finger down through onStop")
        Smoke.assertQuiet(c, "finger down through onStop")
    }

    private fun undoRedoAll() {
        val total = c.undoManager.undoCount
        assertTrue("steps to undo ($total)", total >= 5)
        val after = c.doc.layers.map { it.id to it.dataSnapshot() }
        val tree = c.doc.layers.map { it.id to it.parentId }
        val selections = c.doc.savedSelections.map { it.id }
        repeat(total) { click("Undo", exact = true) }
        assertTrue(Smoke.pumpUntil { settle(1); c.busyMessage == null && !c.vectors.isRendering && c.pendingSavedSelections.isEmpty() })
        assertEquals("all undone", 0, c.undoManager.undoCount)
        assertEquals("the v1.6 layers as they were", v16Data, c.doc.layers.associate { it.id to it.dataSnapshot() })
        assertTrue("no saved selection", c.doc.savedSelections.isEmpty())
        assertTrue("no folder", c.doc.layers.none { it.isFolder })
        assertPendingButtons("all undone")
        repeat(total) { click("Redo", exact = true) }
        assertTrue(Smoke.pumpUntil { settle(1); c.busyMessage == null && !c.vectors.isRendering && c.pendingSavedSelections.isEmpty() })
        assertEquals("all redone", after, c.doc.layers.map { it.id to it.dataSnapshot() })
        assertEquals("the tree", tree, c.doc.layers.map { it.id to it.parentId })
        assertEquals("the saved selections", selections, c.doc.savedSelections.map { it.id })
        assertPendingButtons("all redone")
        Smoke.assertQuiet(c, "undo / redo across tools")
        shot("session")
    }

    private fun deathWithTextEditor() {
        val painted = rasterStroke(250f)
        newText(textSpot(1))
        SmokeUi.field("Text").focus()
        settle(2)
        SmokeUi.field("Text").type("WAVE")
        settle(2)
        val layers = c.doc.layers.map { it.id }
        processDeath(c.doc.layers.single { it.name == "Raster" })
        val restored = c.tools.getValue(ToolId.TEXT) as TextTool
        assertFalse("no text editor after the restore", restored.editorOpen)
        assertEquals("the layers saved on leaving the screen", layers, c.doc.layers.map { it.id })
        assertArrayEquals("the stroke saved on leaving the screen", painted, pixels(c.doc.layers.single { it.name == "Raster" }.bitmap))
        val waves = c.doc.layers.count { it.isTextLayer && TextCodec.decode(it.textData)?.text == "WAVE" }
        note("process death with the text editor open: \"WAVE\" ${if (waves == 0) "not kept (it was never applied)" else "kept"}")
        assertPendingButtons("restored after the text editor")
        Smoke.assertQuiet(c, "restored after the text editor")
    }

    private fun deathWithFreeDeform() {
        val painted = rasterStroke(280f)
        val raster = c.doc.layers.single { it.name == "Raster" }
        tool(ToolId.TRANSFORM.label)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("lifted", Smoke.pumpUntil { settle(1); tool.transformState != null })
        ui().reach(TransformLabels17.FREE_DEFORM, 32f)
        click(TransformLabels17.FREE_DEFORM, exact = true)
        assertTrue("Free deform", Smoke.pumpUntil { settle(1); tool.isMeshShown })
        // A mesh point dragged by a finger: a pending deform.
        val st = tool.transformState!!
        val (cx, cy) = st.cx to st.cy
        stroke(cx to cy, cx + 20f to cy + 10f, cx + 40f to cy + 20f)
        note("free deform pending: changed ${tool.isMeshChanged}, pending ${tool.hasPendingWork}")
        assertTrue("a deform pending", tool.hasPendingWork)
        assertPendingButtons("Free deform pending")
        processDeath(raster)
        val again = c.doc.layers.single { it.name == "Raster" }
        assertArrayEquals("the raster as it was before the deform", painted, pixels(again.bitmap))
        assertFalse("nothing pending", c.currentTool.hasPendingWork)
        assertPendingButtons("restored after a Free deform")
        Smoke.assertQuiet(c, "restored after a Free deform")
    }

    private fun reopen() {
        backToGallery()
        val saved = runBlocking { app.repository.load(id) }
        assertEquals(emptyList<String>(), saved.loadWarnings)
        val json = File(projectDir, ProjectFormat.PROJECT_FILE).readText()
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), json)
        assertEquals("a folder: format 3", 3, dto.formatVersion)
        val raster = saved.layers.single { it.name == "Raster" }
        val wrappedAroundRaster = saved.layers.filter { l -> TextCodec.decode(l.textData)?.wrap?.let { it.isOn && it.sourceLayerId == raster.id } == true }.map { it.id }.toSet()
        val edited = setOf(raster.id, saved.layers.single { it.name == "Vector 2" }.id) + wrappedAroundRaster
        for ((lid, data) in v16Data) {
            if (lid in edited) continue
            val l = saved.layers.single { it.id == lid }
            assertEquals("layer $lid \"${l.name}\": its v1.6 data", data, l.dataSnapshot())
            assertEquals("layer $lid \"${l.name}\": its v1.6 file", v16FileOf[lid], dto.layers.single { it.id == lid }.file)
        }
        val vector = saved.layers.single { it.name == "Vector 2" }.vector!!.objects
        assertTrue("the symmetric stroke", vector.any { it.id == made["symmetric stroke"] && (it as VStroke).copies.isNotEmpty() })
        assertTrue("the fill-only path", vector.any { it.id == made["fill-only path"] && (it as VPath).fill != null && it.stroke == null })
        assertTrue("the saved selection", saved.savedSelections.any { it.id == made["saved selection"] })
        val arrayed = saved.layers.single { it.id == made["array"] }
        assertNotNull("the array", arrayed.array)
        assertEquals("in its folder", made["folder"], arrayed.parentId)
        val ap = pixels(arrayed.bitmap)
        val copiesAway = ap.indices.count { i -> ap[i] ushr 24 != 0 && (i % arrayed.bitmap.width !in 80..160 || i / arrayed.bitmap.width !in 60..130) }
        assertTrue("the array's copies saved as pixels (what v1.6 shows)", copiesAway > 0)
        assertTrue("the kern", TextCodec.decode(saved.layers.single { it.id == made["kerned text"] }.textData)!!.kerns.isNotEmpty())
        note(log.lines().filter { it.isNotBlank() }.size.toString() + " notes")
    }
}
