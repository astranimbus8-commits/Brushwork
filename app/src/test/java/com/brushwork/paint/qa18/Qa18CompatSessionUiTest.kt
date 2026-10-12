package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.MainActivity
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.tools.vector.docLength
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.time.Duration
import kotlin.math.abs

/**
 * v1.8 QA (compat): a project the real v1.7.0 build saved ([Qa18CompatV17Golden], "Compat":
 * folders, the four kinds of live array, saved selections, symmetry Rotation × 6), through the
 * real app (MainActivity) on the user's 392 dp phone, the controls tapped and the canvas touched
 * by fingers.
 *
 * - Opened from the gallery and left: nothing is written (the v1.7 files stay v1.7's).
 * - The v1.8 tools on it through their labels: the Symmetry tool's Mirror and a curve with its
 *   mirror copy, a generated spiral ("Spirals and waves"), a holed cog with its mirror copy
 *   ("Shape type" › Cog, "Shape settings" › Hole size) on the vector layer "Paths"; on a raster
 *   layer of the session's own: a Magnetic lasso outline and a Color select range, each saved
 *   ("Save selection"), the range arrayed ("Array from selection") and given a look (the Array
 *   sheet's Look › End opacity), Chromatic aberration with Blue/Yellow fringes and Glitch with a
 *   picked static color.
 * - Tools switched in the middle of a brush stroke and of a shape drag; the strip's More row left
 *   open while the tool changes, the layer window opens and the app goes to the background; a
 *   Magnetic lasso outline pending through the background.
 * - Undo of every step and redo of every step with the top row's buttons: all undone, every v1.7
 *   layer holds v1.7's pixels and data again.
 * - Process death (the activity's state saved, the app's memory gone, a new activity from that
 *   state, which reopens the artwork from disk) with a cog pending, and with Color select samples
 *   pending under an open More row: no crash, the editor opens again, ✓ / ✕ and the More row
 *   never stuck, what was saved on leaving the screen is there, the pending work is not applied.
 * - Back to the gallery: the artwork opens again with every v1.7 layer the session did not touch
 *   unchanged (data and file) and every v1.8 datum the session made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.compatsessionsandbox"])
class Qa18CompatSessionUiTest {
    private val failures = mutableListOf<Throwable>()
    private lateinit var ctl: ActivityController<MainActivity>
    private lateinit var app: BrushworkApp
    private val activity: MainActivity get() = ctl.get()
    private val id = Qa18CompatV17Golden.ID
    private val projectDir: File get() = File(app.filesDir, "projects/$id")

    private fun note(s: String) = println("[qa18 compat session] $s")

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        }
    }

    // ------------------------------------------------------------------ helpers

    private val session: EditorSession get() = app.editorSession ?: throw AssertionError("no editor session")
    private val c: EditorController get() = (session.state as EditorSession.State.Ready).controller

    private fun canvas(): CanvasView = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas")

    private fun screenOf(): ChromeScreen = ChromeScreen(activity, c)

    private fun ui(): Qa16Ui = Qa16Ui(screenOf())

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

    /** A finger on the control [label] (brought wholly into view first), real touch events at its centre. */
    private fun finger(label: String, minDp: Float = 32f) {
        ui().reach(label, minDp)
        touch().idle(300)
        SmokeUi.tap(label, exact = true)
        settle(4)
    }

    private fun steps(): Int = c.undoManager.undoCount

    private fun layer(name: String): Layer = c.doc.layers.single { it.name == name }

    private fun waitForEditor(where: String) {
        assertTrue("$where: the editor opened", Smoke.pumpUntil(30_000) {
            settle(1)
            (app.editorSession?.state as? EditorSession.State.Ready) != null &&
                (Smoke.find(activity.window.decorView, CanvasView::class.java)?.width ?: 0) > 0
        })
        settleRenders(where)
        assertEquals(id, c.doc.id)
    }

    /** Every render on its way has landed (a vector layer, an array cache, a saved selection, a busy overlay). */
    private fun settleRenders(where: String) {
        assertTrue(
            "$where: settled",
            Smoke.pumpUntil(30_000) {
                settle(1)
                c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending && c.pendingSavedSelections.isEmpty()
            },
        )
        settle()
    }

    private fun open() {
        assertTrue("the gallery lists the v1.7 project", Smoke.pumpUntil { settle(1); has(Qa18CompatV17Golden.NAME, exact = true) })
        click(Qa18CompatV17Golden.NAME, exact = true)
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

    /** ✓ and ✕ show exactly while the tool has pending work (never stuck, never missing); the More row agrees with its toggle. */
    private fun assertPendingButtons(where: String) {
        settle(2)
        val label = c.currentTool.id.label.lowercase()
        val pending = c.filterSession == null && c.currentTool.hasPendingWork
        assertEquals("$where: ✓ while pending ($pending, ${c.activeToolId})", pending, has("Apply $label edit", exact = true))
        assertEquals("$where: ✕ while pending ($pending, ${c.activeToolId})", pending, has("Discard $label edit", exact = true))
        val stray = SmokeUi.shown().filter { (it.startsWith("Apply ") || it.startsWith("Discard ")) && it.endsWith(" edit") }
        assertEquals("$where: no ✓ / ✕ of another tool", if (pending) setOf("Apply $label edit", "Discard $label edit") else emptySet<String>(), stray.toSet())
        assertMoreRowAgrees(where)
    }

    /** The strip's More row shows exactly while its toggle says Expanded (a toggle stuck on Expanded with no row, or a row with a Collapsed toggle, is a stuck control). */
    private fun assertMoreRowAgrees(where: String) {
        val toggle = StripTestKit.toggle()
        val state = toggle?.config?.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("$where: the More row shows exactly while its toggle says Expanded (toggle $state)", state == StripLabels18.EXPANDED, StripTestKit.moreRowShown())
    }

    /** The strip's More row opened by a finger on its toggle ("Show more tool options"). */
    private fun openMoreRowByFinger(where: String) {
        assertNotNull("$where: the tool has a More row", StripTestKit.toggle())
        if (StripTestKit.moreRowShown()) return
        touch().idle(300)
        SmokeUi.tap(StripLabels18.MORE_TOGGLE, exact = true)
        settle(4)
        assertTrue("$where: the More row is open", StripTestKit.moreRowShown())
        assertMoreRowAgrees(where)
    }

    /** The layer window's row of [layer] picked by a tap (the window opened and closed again). */
    private fun pick(layer: Layer) {
        if (c.activeLayer === layer) return
        openLayers()
        try {
            val n = c.doc.indexOf(layer) + 1
            scrollToRow(n)
            click(LayerLabels.selectRow(n), exact = true)
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
            while (p != null && !(p.config.getOrNull(SemanticsActions.ScrollBy) != null && p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null)) p = p.parent
            val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "the layer list does not scroll" }
            // The list shows the top layer first: a higher number is further up.
            scroll.invoke(0f, (if (n > visible.max()) -60f else 60f) * activity.resources.displayMetrics.density)
            settle(2)
        }
        throw AssertionError("row $n never came into the layer list")
    }

    /** Taps the floating ✓ ("Apply <tool> edit") with a finger and waits for what it starts. */
    private fun applyEdit(label: String) {
        finger(label)
        assertTrue("$label: done", Smoke.pumpUntil(30_000) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending })
        settle()
    }

    /** The Symmetry tool's type dropdown set to [type], then Done. */
    private fun symmetry(type: SymmetryType) {
        tool(SymmetryLabels.TOOL)
        click(c.symmetry.type.label, exact = true)
        click(type.label, exact = true)
        assertEquals(type, c.symmetry.type)
        click("Done", exact = true)
        assertNotEquals("Done leaves the Symmetry tool", ToolId.SYMMETRY, c.activeToolId)
    }

    /**
     * Home, then back to the app. `restart()` (onRestart, onStart), not `start()`: after a bare
     * `start()` Robolectric leaves the activity marked stopped, and its next `stop()` skips onStop.
     */
    private fun backgroundAndBack() {
        ctl.pause().stop()
        settle(2)
        ctl.restart().resume()
        settle()
    }

    /**
     * Waits for the onStop save: up to 10 s of real time (a loaded runner can take more than 3 s),
     * letting at most 30 s of fake time pass; the periodic autosave is off in this test, so
     * only the onStop save can make [done] true.
     */
    private fun onStopSave(done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 10_000
        var fakeMs = 0L
        while (System.currentTimeMillis() < end) {
            if (fakeMs < 30_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                fakeMs += 20
            } else {
                shadowOf(Looper.getMainLooper()).idle()
            }
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    /**
     * The app leaves the screen (its onStop save writes [unsaved], an applied edit not saved
     * yet), its process dies (the activity's state is kept, the app's memory is not) and a new
     * activity starts from that state, which opens the artwork again from disk.
     */
    private fun processDeath(unsaved: Layer) {
        val old = session
        assertTrue("\"${unsaved.name}\" has an edit to save", unsaved.savedVersion != unsaved.contentVersion)
        val bundle = Bundle()
        ctl.pause().stop()
        assertTrue("the onStop save wrote \"${unsaved.name}\"", onStopSave { unsaved.savedVersion == unsaved.contentVersion })
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
    }

    /** A brush stroke by a finger on the session's raster layer (applied, not saved yet); returns its pixels after. */
    private fun rasterStroke(y: Float): IntArray {
        val mine = c.doc.layerById(made.getValue("raster"))!!
        pick(mine)
        tool(ToolId.BRUSH.label)
        val n = steps()
        stroke(60f to y, 150f to y + 10f, 240f to y)
        assertEquals("the stroke is one step", n + 1, steps())
        return pixels(mine.bitmap)
    }

    private fun files(): Map<String, ByteArray> = projectDir.listFiles()!!.filter { it.name != ProjectFormat.THUMB_FILE }.associate { it.name to it.readBytes() }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** [l]'s data in a form that compares by value: an array's source pixels by place and CRC (an [com.brushwork.paint.model.ArrayPixels] compares by identity). */
    private fun data(l: Layer): Any {
        val d = l.dataSnapshot()
        return d.copy(array = d.array?.copy(pixels = null)) to d.array?.pixels?.let { Triple(it.left, it.top, Qa18CompatV17Golden.crc(it.bitmap)) }
    }

    private fun shot(name: String) {
        val f = Qa18CompatShots.save("session-$name", IbisShots.capture())
        note("shot ${f.path}")
    }

    /** Document points where no object of [layer] is (nor, with [mirror], at their mirror images), left to right in a zigzag. */
    private fun emptySpots(layer: Layer, n: Int, mirror: Boolean): List<Pair<Float, Float>> {
        val tol = c.docLength(24f)
        val free = ArrayList<Pair<Float, Float>>()
        val xs = if (mirror) listOf(40f, 80f, 120f, 160f) else (1..9).map { it * 40f }
        for (y in (1..6).map { it * 40f }) for (x in xs) {
            if (c.vectors.hitTest(layer, Vec2(x, y), tol) == null && (!mirror || c.vectors.hitTest(layer, Vec2(c.doc.width - x, y), tol) == null)) free += x to y
        }
        for (start in free) {
            val out = arrayListOf(start)
            while (out.size < n) {
                val (lx, ly) = out.last()
                out += free.firstOrNull { (x, y) -> x >= lx + 40f && abs(y - ly) >= 40f } ?: break
            }
            if (out.size == n) return out
        }
        throw AssertionError("no room for $n points: $free")
    }

    /** Opens [f] from the Filters tool: its search, then its name. */
    private fun openFilter(f: Filter, search: String): FilterSession {
        // Filters is a panel in the tool menu, not a tool.
        ui().tool("Filters")
        settle(2)
        SmokeUi.assertPanelShown("Filters")
        SmokeUi.field("Search filters").let { it.focus(); settle(2); it.type(search) }
        settle(4)
        click(f.name, exact = true)
        val fs = c.filterSession ?: throw AssertionError("no session for ${f.name} (${c.message})")
        assertEquals(f.id, fs.filter.id)
        Fx.awaitPreview(fs, f.name)
        settle()
        return fs
    }

    private fun applyFilter(f: Filter) {
        val before = steps()
        click("Apply filter", exact = true)
        assertTrue("${f.name}: applied", Smoke.pumpUntil(30_000) { settle(1); c.filterSession == null && c.busyMessage == null })
        settle()
        assertEquals("${f.name}: one step", before + 1, steps())
        assertEquals(f.name, c.undoManager.undoLabel)
    }

    /** In the open color picker: the hex typed, then OK. */
    private fun pickHex(hex: String) {
        assertTrue("the color picker is open; shown: ${SmokeUi.shown().take(80)}", has("OK", exact = true))
        SmokeUi.field("Hex").type(hex)
        settle(2)
        click("OK", exact = true)
        settle()
        assertFalse("the color picker closed", has("OK", exact = true))
    }

    /** The placed node whose text is [label] (the last one). */
    private fun byText(label: String): RobolectricUi.Element = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    } ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(80)}")

    /** The clickable on the row of the text [label], right of it (a color swatch). */
    private fun clickableRightOf(label: String): RobolectricUi.Element {
        val t = byText(label)
        val right = t.node.positionInWindow.x + t.node.size.width
        val middle = t.node.positionInWindow.y + t.node.size.height / 2f
        val density = activity.resources.displayMetrics.density
        fun dy(e: RobolectricUi.Element) = abs(e.node.positionInWindow.y + e.node.size.height / 2f - middle)
        return RobolectricUi.elements().filter { e ->
            e.window === t.window && e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsActions.OnClick) != null &&
                e.node.positionInWindow.x >= right && dy(e) < 24f * density
        }.minByOrNull { dy(it) } ?: throw AssertionError("nothing clickable right of \"$label\"")
    }

    /**
     * The element [find] returns, after scrolling each scrolling ancestor until it lies wholly
     * inside it, as a finger scrolls before it taps.
     */
    private fun visible(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(12) {
            val e = find()
            val pos = e.node.positionInWindow
            val l = pos.x; val t = pos.y; val r = l + e.node.size.width; val b = t + e.node.size.height
            val scrollers = generateSequence(e.node.parent) { it.parent }.filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.toList().asReversed()
            for (n in scrollers) {
                val p = n.positionInWindow
                val vl = p.x; val vt = p.y; val vr = vl + n.size.width; val vb = vt + n.size.height
                val across = n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                val delta = if (across) {
                    when { r > vr -> r - vr + 8f; l < vl -> l - vl - 8f; else -> 0f }
                } else {
                    when { b > vb -> b - vb + 8f; t < vt -> t - vt - 8f; else -> 0f }
                }
                if (delta != 0f) {
                    val scroll = requireNotNull(n.config[SemanticsActions.ScrollBy].action)
                    if (across) scroll(delta, 0f) else scroll(0f, delta)
                    Smoke.pump(400)
                    settle()
                    return@repeat
                }
            }
            return e
        }
        return find()
    }

    /** The slider right under the text [label] (the next placed node after it that sets a progress), or null. */
    private fun sliderUnder(label: String): RobolectricUi.Element? {
        val all = RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }
        val at = all.indexOfLast { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        if (at < 0) return null
        return all.drop(at + 1).firstOrNull { it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
    }

    /**
     * The slider under the text [label] in the open sheet, scrolled (the topmost list that scrolls
     * up and down, 60 dp at a time, as a finger does) until it is wholly in view, then dragged by a
     * finger from its left end to [fraction] of its width.
     */
    private fun dragSlider(label: String, fraction: Float) {
        val density = activity.resources.displayMetrics.density
        // The sheet slides in first.
        Smoke.pump(400)
        settle(2)
        repeat(40) {
            val slider = sliderUnder(label)
            val root = screenOf().root
            if (slider != null) {
                val b = slider.bounds
                if (slider.node.boundsInWindow.height >= slider.node.size.height - 1f && b.bottom <= root.bottom - 8f * density) {
                    // A finger moves once a frame (a drag whose moves all land in one frame never
                    // starts the slider's drag); the thumb's centre runs r .. w - r.
                    val y = b.center.y
                    val r = 10f * density
                    fun x(f: Float) = b.left + r + f * (b.width - 2f * r)
                    val t = Smoke.Touch(slider.window)
                    t.idle(300)
                    t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, x(0f), y))
                    for (i in 1..10) {
                        t.idle(16)
                        t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, x(fraction * i / 10f), y))
                    }
                    t.idle(16)
                    t.send(MotionEvent.ACTION_UP, Smoke.P(0, x(fraction), y))
                    settle(4)
                    return
                }
            }
            val list = RobolectricUi.elements().lastOrNull { e ->
                e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null && e.node.config.getOrNull(SemanticsActions.ScrollBy) != null
            } ?: throw AssertionError("nothing scrolls to \"$label\"; shown: ${SmokeUi.shown().take(60)}")
            requireNotNull(list.node.config[SemanticsActions.ScrollBy].action).invoke(0f, 60f * density)
            // The scroll animates: a touch while it runs only stops it (as a finger on a moving list).
            Smoke.pump(400)
            settle(2)
        }
        throw AssertionError("the slider \"$label\" never came wholly into view; shown: ${SmokeUi.shown().take(80)}")
    }

    /** The number field [label] scrolled wholly into view in its sheet (60 dp at a time), then typed and Done. */
    private fun typeField(label: String, value: String) {
        val s = screenOf()
        repeat(40) {
            val n = SmokeUi.field(label).node
            val b = n.boundsInWindow
            val r = s.root
            if (b.height >= n.size.height - 1f && b.top >= r.top && b.bottom <= r.bottom) {
                SmokeUi.typeAndDone(label, value)
                return
            }
            var p = n.parent
            while (p != null && (p.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null || p.config.getOrNull(SemanticsActions.ScrollBy) == null)) p = p.parent
            val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is cut and nothing scrolls it" }
            val v = p!!.boundsInWindow
            scroll.invoke(0f, if (n.positionInWindow.y < v.top) -60f * s.density else 60f * s.density)
            settle(2)
        }
        throw AssertionError("\"$label\" never came wholly into view")
    }

    // ------------------------------------------------------------------ the test

    private val probe = Qa18CompatV17Golden.probe()
    private lateinit var v17Data: Map<Long, Any>
    private lateinit var v17FileOf: Map<Long, String?>
    private lateinit var v17Selections: List<Long>
    private val made = LinkedHashMap<String, Long>()

    @Test
    fun aV17ArtworkThroughALongV18Session() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        app = RuntimeEnvironment.getApplication() as BrushworkApp
        // No periodic autosave in this session: it could land just before a section's onStop and
        // leave that save nothing to write, and only the onStop and close saves are under test here.
        app.settings.autosaveSeconds = 24 * 3600
        Qa18CompatV17Golden.install(app)
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), File(projectDir, ProjectFormat.PROJECT_FILE).readText())
        v17FileOf = dto.layers.associate { it.id to it.file }
        SmokeUi.markBaseline()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        section("a v1.7 artwork opens and closes unchanged") { openAndLeave() }
        section("the v1.8 tools on a v1.7 artwork") { v18Tools() }
        section("tools switched in the middle of a gesture") { midGesture() }
        section("the More row left open while switching") { moreRows() }
        section("an outline pending through the background") { outlineThroughTheBackground() }
        section("undo and redo across the tools") { undoRedoAll() }
        section("process death with a cog pending") { deathWithCog() }
        section("process death with samples pending under an open More row") { deathWithSamples() }
        section("back to the gallery: the artwork opens again") { reopen() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun openAndLeave() {
        val v17 = files()
        open()
        assertEquals(emptyList<String>(), c.doc.loadWarnings)
        assertEquals(20, c.doc.layers.size)
        assertEquals("every layer holds v1.7's pixels", emptyList<String>(), Qa18CompatV17Golden.storedMismatches(c.doc, probe))
        assertEquals("v1.7's symmetry", SymmetryType.ROTATION, c.symmetry.type)
        v17Data = c.doc.layers.associate { it.id to data(it) }
        v17Selections = c.doc.savedSelections.map { it.id }
        assertEquals(2, v17Selections.size)
        assertPendingButtons("opened")
        Smoke.assertQuiet(c, "opened")
        backToGallery()
        val after = files()
        assertEquals("the same files", v17.keys.sorted(), after.keys.sorted())
        for ((name, bytes) in v17) assertArrayEquals("$name: nothing was saved", bytes, after.getValue(name))
        assertFalse("no thumbnail written", File(projectDir, ProjectFormat.THUMB_FILE).exists())
    }

    private fun v18Tools() {
        open()
        val paths = layer("Paths")
        val background = layer("Background")
        pick(background)

        // A raster layer of the session's own above "Background" (the layer window's Add layer; on "Paths" it adds a vector layer), with
        // something to select: a white page, a red and a blue block, a black disc.
        openLayers()
        click(LayerLabels.ADD, exact = true)
        settle()
        closeLayers()
        val mine = c.activeLayer
        assertTrue("a new raster layer: \"${mine.name}\" ${mine.id} in ${mine.parentId} (\"Background\" in ${background.parentId}), vector ${mine.vector != null}, layers ${c.doc.layers.map { it.name }}", mine.id !in v17Data && mine.vector == null && !mine.isFolder && mine.parentId == background.parentId)
        made["raster"] = mine.id
        assertTrue(
            c.editWholeLayer(mine, "Seed") { bmp ->
                Canvas(bmp).apply {
                    drawColor(0xFFFFFFFF.toInt())
                    drawRect(20f, 20f, 100f, 100f, Paint().apply { color = RED })
                    drawRect(300f, 200f, 380f, 280f, Paint().apply { color = BLUE })
                    drawCircle(DISC.x, DISC.y, DISC_R, Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = true })
                }
            },
        )
        settle()

        // The Symmetry tool's Mirror (v1.7 saved Rotation × 6), then a curve by three taps on "Paths".
        pick(paths)
        symmetry(SymmetryType.MIRROR)
        tool("Curve")
        val curve = c.currentTool as CurveTool
        val spots = emptySpots(paths, 3, mirror = true)
        for ((x, y) in spots) tap(x, y)
        assertEquals("three points by three taps at $spots", 3, curve.pointCount)
        assertTrue("the mirror copy follows", curve.hasCopies)
        var n = steps()
        applyEdit("Apply curve edit")
        assertEquals("the curve is one step", n + 1, steps())
        val mirrored = paths.vector!!.objects.last() as VPath
        assertEquals("the curve and its mirror copy, one object", 2, ObjectCopies.of(mirrored).count)
        made["mirrored curve"] = mirrored.id
        assertPendingButtons("mirrored curve")

        // A generated spiral: Curve with nothing drawn, "Spirals and waves" › Archimedean spiral.
        assertEquals(0, curve.pointCount)
        click(CurveGenLabels18.CURVES_DESC, exact = true)
        click(CurveGenLabels18.ARCHIMEDEAN, exact = true)
        val g = curve.generator ?: throw AssertionError("no spiral")
        n = steps()
        applyEdit("Apply curve edit")
        assertEquals("the spiral is one step", n + 1, steps())
        val spiral = paths.vector!!.objects.last() as VPath
        assertEquals("the spiral keeps its settings", g, spiral.generator)
        made["spiral"] = spiral.id
        assertPendingButtons("spiral")

        // A holed cog with its mirror copy: "Shape type" › Cog, "Shape settings" › Hole size.
        tool("Shape")
        val shape = c.currentTool as ShapeTool
        click("Shape type", exact = true)
        click(ShapeType.COG.label, exact = true)
        assertEquals(ShapeType.COG, shape.settings.type)
        click("Shape settings", exact = true)
        // Half way along its 0 to 80 % track.
        dragSlider(CogLabels18.HOLE_SIZE, 0.5f)
        val hole = shape.settings.cogHole
        assertTrue("a hole about 40 %: $hole", hole in 0.3f..0.5f)
        Finger.back()
        assertFalse("the sheet closed", has(CogLabels18.HOLE_SIZE, exact = true))
        // The More row (where Shape settings is) swallows the next canvas touch (D25): closed first.
        StripTestKit.closeMoreRow()
        stroke(50f to 120f, 90f to 160f, 130f to 200f)
        assertNotNull("a pending cog", shape.box)
        assertTrue("its mirror copy shows while it is drawn", shape.hasCopies)
        assertPendingButtons("cog pending")
        n = steps()
        applyEdit("Apply shape edit")
        assertEquals("the cog is one step", n + 1, steps())
        val cog = paths.vector!!.objects.last() as VShape
        assertEquals(ShapeType.COG, cog.shape.type)
        assertEquals("the hole", hole, cog.shape.cogHole, 1e-4f)
        assertEquals("the cog and its mirror copy", 2, cog.shape.copies.count)
        made["cog"] = cog.id
        symmetry(SymmetryType.OFF)
        assertPendingButtons("cog")

        // A Magnetic lasso outline round the disc on the session's layer, closed on its first point, saved.
        pick(mine)
        tool("Magnetic lasso")
        val lasso = c.currentTool as MagneticLassoTool
        assertTrue("the edges are found", Smoke.pumpUntil(30_000) { settle(1); lasso.edgeMapReady && lasso.idle })
        val rim = listOf(Vec2(DISC.x, DISC.y - DISC_R), Vec2(DISC.x + DISC_R, DISC.y), Vec2(DISC.x, DISC.y + DISC_R), Vec2(DISC.x - DISC_R, DISC.y))
        for (p in rim) {
            tap(p.x, p.y)
            assertTrue(Smoke.pumpUntil(30_000) { settle(1); lasso.idle })
        }
        assertEquals("four points by four taps", 4, lasso.anchorCount)
        assertPendingButtons("outline pending")
        tap(rim[0].x, rim[0].y)
        assertTrue("a tap on the first point closes and selects", Smoke.pumpUntil(30_000) { settle(1); lasso.idle && c.selection != null && c.busyMessage == null })
        settle()
        val outline = c.selection!!
        assertEquals("the disc's centre is selected", 255, outline.alphaAt(DISC.x.toInt(), DISC.y.toInt()))
        assertEquals("a corner is not", 0, outline.alphaAt(5, 5))
        val saved0 = c.doc.savedSelections.size
        ui().reach(SavedSelectionLabels.SAVE)
        click(SavedSelectionLabels.SAVE, exact = true)
        assertTrue("the outline is saved", Smoke.pumpUntil { settle(1); c.pendingSavedSelections.isEmpty() && c.doc.savedSelections.size == saved0 + 1 })
        made["saved outline"] = c.doc.savedSelections.last().id
        assertPendingButtons("outline saved")

        // Color select: a tap samples the red, Select colors, saved, arrayed, given a look.
        tool("Color select")
        val range = c.currentTool as ColorSelectTool
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); range.proxyReady && range.idle && range.preview != null })
        val v0 = range.preview!!.version
        tap(60f, 60f)
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); range.idle && range.preview!!.version > v0 })
        assertEquals("the tap sampled the red", listOf(RED), range.sampleColors)
        assertPendingButtons("a sample pending")
        val prev = c.selection
        click(ColorSelectLabels18.SELECT_COLORS, exact = true)
        assertTrue("a new selection", Smoke.pumpUntil(30_000) { settle(1); range.idle && c.busyMessage == null && c.selection != null && c.selection !== prev })
        settle()
        val red = c.selection!!
        assertEquals("the red block is selected", 255, red.alphaAt(60, 60))
        assertEquals("the disc is not", 0, red.alphaAt(DISC.x.toInt(), DISC.y.toInt()))
        StripTestKit.closeMoreRow()
        ui().reach(SavedSelectionLabels.SAVE)
        click(SavedSelectionLabels.SAVE, exact = true)
        assertTrue("the range is saved", Smoke.pumpUntil { settle(1); c.pendingSavedSelections.isEmpty() && c.doc.savedSelections.size == saved0 + 2 })
        made["saved range"] = c.doc.savedSelections.last().id
        ui().reach(ArrayLabels.FROM_SELECTION)
        click(ArrayLabels.FROM_SELECTION, exact = true)
        settleRenders("the array")
        val arrayed = c.activeLayer
        assertNotNull("an array layer", arrayed.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        made["array"] = arrayed.id
        if (!SmokeUi.has("Show Array", exact = true) && screenOf().tagged(V17Tags.ARRAY_SHEET) == null) click("Array settings", exact = true)
        if (SmokeUi.has("Show Array", exact = true)) click("Show Array", exact = true)
        settle()
        assertNotNull("the Array sheet; shown: ${SmokeUi.shown().take(60)}", screenOf().tagged(V17Tags.ARRAY_SHEET))
        ui().reach(ArrayLabels18.LOOK, 44f)
        click(ArrayLabels18.LOOK, exact = true)
        assertNotNull("the Look tab", screenOf().tagged(V18Tags.ARRAY_LOOK))
        n = steps()
        typeField(ArrayLabels18.END_OPACITY, "40")
        settleRenders("the look")
        assertEquals("the look is one step", n + 1, steps())
        assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
        assertEquals(0.4f, arrayed.array!!.spec.look!!.opacityEnd, 1e-4f)
        Finger.back()
        if (c.selection != null) {
            ui().reach("Clear the selection")
            click("Clear the selection", exact = true)
        }
        tool(ToolId.BRUSH.label)
        assertPendingButtons("arrayed")

        // Chromatic aberration with Blue/Yellow fringes, Glitch with a picked static color, on the session's layer.
        pick(mine)
        val moving = FilterRegistry.byId("art.chromatic_aberration_moving") ?: throw AssertionError("Chromatic aberration (Moving) is registered")
        var fs = openFilter(moving, "chromatic")
        visible { byText(FringeLabels18.BLUE_YELLOW) }
        click(FringeLabels18.BLUE_YELLOW, exact = true)
        assertArrayEquals(intArrayOf(FringeColors.BLUE, FringeColors.NONE, FringeColors.YELLOW), fs.values.colors(FringeColors.KEY))
        Fx.awaitPreview(fs, "fringes")
        val before = pixels(mine.bitmap)
        applyFilter(moving)
        assertFalse("the fringes changed the layer", before.contentEquals(pixels(mine.bitmap)))
        val glitch = FilterRegistry.byId("art.glitch") ?: throw AssertionError("Glitch is registered")
        fs = openFilter(glitch, "glitch")
        val swatch = visible { clickableRightOf(FringeLabels18.STATIC_COLOR) }
        swatch.tap()
        settle()
        pickHex("20C040")
        assertEquals("the static's color picked", 0xFF20C040.toInt(), fs.values.color(GlitchFilter.STATIC_COLOR_KEY))
        Fx.awaitPreview(fs, "static")
        applyFilter(glitch)
        assertPendingButtons("filters")
        Smoke.assertQuiet(c, "the v1.8 tools")
        shot("tools")
        note("v1.8 tools: ${steps()} steps; made $made")
    }

    private fun midGesture() {
        // A brush stroke on the session's layer, the tool switched to Magnetic lasso by another finger in its middle.
        val mine = c.doc.layerById(made.getValue("raster"))!!
        pick(mine)
        tool(ToolId.BRUSH.label)
        var n = steps()
        val t = touch()
        val (ax, ay) = screen(40f, 260f)
        val (bx, by) = screen(260f, 290f)
        t.idle(300)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, ax, ay))
        for (k in 1..4) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, ax + (bx - ax) * k / 8f, ay + (by - ay) * k / 8f)) }
        tool("Magnetic lasso")
        for (k in 5..8) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, ax + (bx - ax) * k / 8f, ay + (by - ay) * k / 8f)) }
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, bx, by))
        settle()
        val lasso = c.currentTool as MagneticLassoTool
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); lasso.idle })
        assertFalse("no gesture left open", c.isInteracting)
        var kept = steps() - n
        note("brush stroke with the tool switched to Magnetic lasso: ${if (kept == 1) "kept as one step" else "dropped ($kept steps)"}; lasso points ${lasso.anchorCount}")
        assertTrue("at most one step ($kept)", kept in 0..1)
        assertEquals("the rest of the stroke adds no lasso point", 0, lasso.anchorCount)
        assertPendingButtons("brush switched mid-stroke")
        Smoke.assertQuiet(c, "brush switched mid-stroke")

        // A cog drag on "Paths", the tool switched to Curve in its middle.
        val paths = layer("Paths")
        pick(paths)
        tool("Shape")
        val objects = paths.vector!!.objects.size
        n = steps()
        val (cx, cy) = screen(240f, 60f)
        val (dx, dy) = screen(300f, 120f)
        t.idle(300)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, cx, cy))
        for (k in 1..4) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, cx + (dx - cx) * k / 8f, cy + (dy - cy) * k / 8f)) }
        tool("Curve")
        for (k in 5..8) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, cx + (dx - cx) * k / 8f, cy + (dy - cy) * k / 8f)) }
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, dx, dy))
        settle()
        assertTrue(Smoke.pumpUntil { settle(1); !c.vectors.isRendering })
        assertFalse("no gesture left open", c.isInteracting)
        kept = steps() - n
        val curve = c.currentTool as CurveTool
        note("cog drag with the tool switched to Curve: ${paths.vector!!.objects.size - objects} objects, $kept steps; curve points ${curve.pointCount}")
        assertTrue("at most one step ($kept)", kept in 0..1)
        assertEquals("the rest of the drag adds no curve point", 0, curve.pointCount)
        assertPendingButtons("shape switched mid-drag")
        tool("Shape")
        assertNull("no cog left pending in the Shape tool", (c.currentTool as ShapeTool).box)
        assertPendingButtons("back on Shape")
        Smoke.assertQuiet(c, "shape switched mid-drag")
    }

    private fun moreRows() {
        val paths = layer("Paths")
        pick(paths)
        // Open on Curve, then the tool menu picks Shape: the tool change closes it (D25), nothing stuck, the next touch acts.
        tool("Curve")
        openMoreRowByFinger("Curve")
        tool("Shape")
        assertFalse("a tool change closes the More row", StripTestKit.moreRowShown())
        assertPendingButtons("Curve's More row, then Shape")
        stroke(240f to 200f, 280f to 230f, 320f to 260f)
        val shape = c.currentTool as ShapeTool
        assertNotNull("the first canvas touch after the switch draws", shape.box)
        assertPendingButtons("a cog pending")
        finger("Discard shape edit")
        assertNull(shape.box)

        // Open on Shape, then the layer window: closed by it, and still closed after it.
        openMoreRowByFinger("Shape")
        openLayers()
        assertFalse("the layer window closes the More row", StripTestKit.moreRowShown())
        closeLayers()
        assertPendingButtons("Shape's More row, then the layer window")

        // Three curve points pending, the More row open: the floating ✓ applies them (it is no canvas touch).
        tool("Curve")
        val curve = c.currentTool as CurveTool
        val spots = emptySpots(paths, 3, mirror = false)
        for ((x, y) in spots) tap(x, y)
        assertEquals(3, curve.pointCount)
        openMoreRowByFinger("Curve, points pending")
        assertPendingButtons("Curve's More row with points pending")
        val n = steps()
        applyEdit("Apply curve edit")
        assertEquals("✓ through an open More row is one step", n + 1, steps())
        assertPendingButtons("applied with the More row open")

        // Open on Curve, the app to the background and back: the row and its toggle still agree.
        openMoreRowByFinger("Curve again")
        backgroundAndBack()
        assertPendingButtons("Curve's More row through the background")
        StripTestKit.closeMoreRow()
        // Open on Color select, then on to Magnetic lasso, then Brush.
        val mine = c.doc.layerById(made.getValue("raster"))!!
        pick(mine)
        tool("Color select")
        openMoreRowByFinger("Color select")
        tool("Magnetic lasso")
        assertFalse(StripTestKit.moreRowShown())
        assertPendingButtons("Color select's More row, then Magnetic lasso")
        tool(ToolId.BRUSH.label)
        assertPendingButtons("Brush")
        Smoke.assertQuiet(c, "More rows")
    }

    private fun outlineThroughTheBackground() {
        val mine = c.doc.layerById(made.getValue("raster"))!!
        pick(mine)
        tool("Magnetic lasso")
        val lasso = c.currentTool as MagneticLassoTool
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); lasso.edgeMapReady && lasso.idle })
        for (p in listOf(Vec2(DISC.x, DISC.y - DISC_R), Vec2(DISC.x + DISC_R, DISC.y), Vec2(DISC.x, DISC.y + DISC_R))) {
            tap(p.x, p.y)
            assertTrue(Smoke.pumpUntil(30_000) { settle(1); lasso.idle })
        }
        assertEquals(3, lasso.anchorCount)
        val n = steps()
        backgroundAndBack()
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); lasso.idle })
        assertSame("the same tool", lasso, c.currentTool)
        assertEquals("still three points after the background", 3, lasso.anchorCount)
        assertPendingButtons("outline back from the background")
        finger("Discard magnetic lasso edit")
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); lasso.idle })
        assertEquals("✕ drops the points", 0, lasso.anchorCount)
        assertEquals("and records nothing", n, steps())
        tool(ToolId.BRUSH.label)
        assertPendingButtons("outline discarded")
        Smoke.assertQuiet(c, "outline through the background")
    }

    private fun assertSame(what: String, a: Any, b: Any) = assertTrue("$what: $a, $b", a === b)

    private fun undoRedoAll() {
        tool(ToolId.BRUSH.label)
        val total = steps()
        assertTrue("steps to undo ($total)", total >= 10)
        val after = c.doc.layers.map { it.id to data(it) }
        val afterPixels = c.doc.layers.map { it.id to Qa18CompatV17Golden.crc(it.bitmap) }
        val selections = c.doc.savedSelections.map { it.id }
        repeat(total) { click("Undo", exact = true) }
        settleRenders("all undone")
        assertEquals("all undone", 0, steps())
        assertEquals("the v1.7 layers as they were", v17Data, c.doc.layers.associate { it.id to data(it) })
        assertEquals("every layer holds v1.7's pixels again", emptyList<String>(), Qa18CompatV17Golden.storedMismatches(c.doc, probe))
        assertEquals("v1.7's saved selections", v17Selections, c.doc.savedSelections.map { it.id })
        assertPendingButtons("all undone")
        shot("all-undone")
        repeat(total) { click("Redo", exact = true) }
        settleRenders("all redone")
        assertEquals("all redone", total, steps())
        val redone = c.doc.layers.map { it.id to data(it) }
        assertEquals("the layers", after.map { it.first }, redone.map { it.first })
        for ((a, b) in after.zip(redone)) assertEquals("\"${c.doc.layerById(a.first)!!.name}\": its data", a.second, b.second)
        assertEquals("the pixels", afterPixels, c.doc.layers.map { it.id to Qa18CompatV17Golden.crc(it.bitmap) })
        assertEquals("the saved selections", selections, c.doc.savedSelections.map { it.id })
        assertPendingButtons("all redone")
        Smoke.assertQuiet(c, "undo / redo across tools")
        shot("all-redone")
    }

    private fun deathWithCog() {
        val painted = rasterStroke(250f)
        val mine = c.doc.layerById(made.getValue("raster"))!!
        val layers = c.doc.layers.map { it.id }
        tool("Shape")
        val shape = c.currentTool as ShapeTool
        assertEquals(ShapeType.COG, shape.settings.type)
        StripTestKit.closeMoreRow()
        stroke(150f to 30f, 190f to 70f, 230f to 110f)
        assertNotNull("a cog pending", shape.box)
        assertPendingButtons("a cog pending")
        processDeath(mine)
        assertEquals("the layers saved on leaving the screen (no cog layer)", layers, c.doc.layers.map { it.id })
        assertArrayEquals("the stroke saved on leaving the screen", painted, pixels(c.doc.layerById(mine.id)!!.bitmap))
        assertFalse("nothing pending", c.currentTool.hasPendingWork)
        assertPendingButtons("restored after a pending cog")
        Smoke.assertQuiet(c, "restored after a pending cog")
        shot("restored")
    }

    private fun deathWithSamples() {
        val painted = rasterStroke(280f)
        val mine = c.doc.layerById(made.getValue("raster"))!!
        val selection = c.selection
        tool("Color select")
        val range = c.currentTool as ColorSelectTool
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); range.proxyReady && range.idle && range.preview != null })
        // The fringes and the glitch moved the blue square's colors: the tap samples what is there now.
        val there = range.sampleAt(340f, 240f) ?: throw AssertionError("no color at the blue square")
        tap(340f, 240f)
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); range.idle })
        assertEquals("the tap sampled the square", listOf(there), range.sampleColors)
        openMoreRowByFinger("Color select with a sample")
        assertPendingButtons("a sample pending under the More row")
        processDeath(mine)
        assertArrayEquals("the stroke saved on leaving the screen", painted, pixels(c.doc.layerById(mine.id)!!.bitmap))
        assertFalse("nothing pending", c.currentTool.hasPendingWork)
        assertEquals("no selection made from the pending sample", selection == null, c.selection == null)
        assertPendingButtons("restored after pending samples")
        Smoke.assertQuiet(c, "restored after pending samples")
    }

    private fun reopen() {
        backToGallery()
        val saved = runBlocking { app.repository.load(id) }
        assertEquals(emptyList<String>(), saved.loadWarnings)
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), File(projectDir, ProjectFormat.PROJECT_FILE).readText())
        assertEquals("folders: format 3", 3, dto.formatVersion)
        val edited = setOf(saved.layers.single { it.name == "Paths" }.id)
        var untouched = 0
        for ((lid, data) in v17Data) {
            if (lid in edited) continue
            val l = saved.layers.single { it.id == lid }
            assertEquals("layer $lid \"${l.name}\": its v1.7 data", data, data(l))
            assertEquals("layer $lid \"${l.name}\": its v1.7 file", v17FileOf[lid], dto.layers.single { it.id == lid }.file)
            untouched++
        }
        assertEquals("19 v1.7 layers untouched", 19, untouched)
        val objects = saved.layers.single { it.name == "Paths" }.vector!!.objects
        assertTrue("the mirrored curve", objects.any { it.id == made["mirrored curve"] && ObjectCopies.of(it).count == 2 })
        assertTrue("the spiral", objects.any { it.id == made["spiral"] && (it as VPath).generator != null })
        assertTrue("the holed cog and its copy", objects.any { it.id == made["cog"] && (it as VShape).shape.cogHole >= 0.3f && it.shape.copies.count == 2 })
        assertTrue("the saved outline", saved.savedSelections.any { it.id == made["saved outline"] })
        assertTrue("the saved range", saved.savedSelections.any { it.id == made["saved range"] })
        val arrayed = saved.layers.single { it.id == made["array"] }
        assertEquals("the array's look", 0.4f, arrayed.array!!.spec.look!!.opacityEnd, 1e-4f)
        assertNotNull("the session's layer", saved.layerById(made.getValue("raster")))
    }

    private companion object {
        const val RED = 0xFFE01010.toInt()
        const val BLUE = 0xFF1030D0.toInt()
        val DISC = Vec2(200f, 150f)
        const val DISC_R = 70f
    }
}
