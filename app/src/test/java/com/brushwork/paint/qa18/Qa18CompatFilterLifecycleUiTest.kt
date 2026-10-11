package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.MainActivity
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.fxsmoke.Fx
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
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * v1.8 QA (compat, verifier): the v1.8 fringe filters on the real v1.7.0 artwork
 * ([Qa18CompatV17Golden], "Compat") through the app's life on the user's 392 dp phone: the panel
 * open while the app goes to the background and comes back, and while its process dies.
 * - Chromatic aberration (Moving) with "Red/Cyan" and a picked middle color, the app to the
 *   background (its onStop save writes a brush stroke) and back: the same session, the same
 *   colors, the layer and the gallery thumbnail without the preview; Apply: ONE step, the filter's
 *   own result with those colors.
 * - Glitch with its static's color picker open through the background: the picker still open, a
 *   typed hex picked, Apply: ONE step, the filter's own result.
 * - A filter opened again starts from its defaults (RGB fringes).
 * - Process death with Chromatic aberration open on custom colors: no crash, no panel, ✓ / ✕ or
 *   picker left, nothing applied, the layer as the onStop save wrote it.
 * - Back to the gallery: "Background" holds both applies; the 19 other v1.7 layers keep v1.7's
 *   data and files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.compatfiltersandbox"])
class Qa18CompatFilterLifecycleUiTest {
    private lateinit var ctl: ActivityController<MainActivity>
    private lateinit var app: BrushworkApp
    private val activity: MainActivity get() = ctl.get()
    private val id = Qa18CompatV17Golden.ID
    private val projectDir: File get() = File(app.filesDir, "projects/$id")

    private fun note(s: String) = println("[qa18 compat filters] $s")

    private val session: EditorSession get() = app.editorSession ?: throw AssertionError("no editor session")
    private val c: EditorController get() = (session.state as EditorSession.State.Ready).controller

    private fun screenOf(): ChromeScreen = ChromeScreen(activity, c)

    private fun ui(): Qa16Ui = Qa16Ui(screenOf())

    private fun steps(): Int = c.undoManager.undoCount

    private fun canvas(): CanvasView = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas")

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
        assertTrue("$where: settled", Smoke.pumpUntil(30_000) {
            settle(1)
            c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending && c.pendingSavedSelections.isEmpty()
        })
        settle()
        assertEquals(id, c.doc.id)
    }

    private fun tool(label: String) {
        ui().tool(label)
        settle(2)
        assertTrue("\"$label\" is the tool", has("Tools (current: $label)", exact = true))
    }

    private fun cell(label: String): SemanticsNode? {
        var n = SmokeUi.find(label, exact = true)?.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n
    }

    /** The layer window's row of [layer] picked by a tap (the window opened and closed again). */
    private fun pick(layer: Layer) {
        if (c.activeLayer === layer) return
        if (!has(LayerLabels.CLOSE, exact = true)) click("Open layers (active layer ${c.doc.activeLayerIndex + 1})", exact = true)
        Smoke.pump(600)
        settle()
        val n = c.doc.indexOf(layer) + 1
        val label = LayerLabels.selectRow(n)
        val rowRe = Regex("^Select layer (\\d+)$")
        run scroll@{
            repeat(40) {
                if (cell(label) != null) { ui().reach(label, 0f); return@scroll }
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
        click(label, exact = true)
        if (has(LayerLabels.CLOSE, exact = true)) click(LayerLabels.CLOSE, exact = true)
        settle()
        assertTrue("\"${layer.name}\" picked", c.activeLayer === layer)
    }

    /** Opens [f] from the Filters panel: its search, then its name. */
    private fun openFilter(f: Filter, search: String): FilterSession {
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

    private fun byText(label: String): RobolectricUi.Element = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    } ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(80)}")

    private fun byDescription(label: String): RobolectricUi.Element = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    } ?: throw AssertionError("nothing described \"$label\"; shown: ${SmokeUi.shown().take(80)}")

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

    /** The element [find] returns, after scrolling each scrolling ancestor until it lies wholly inside it. */
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

    /** Home, then back to the app (`restart()`: onRestart, onStart). */
    private fun backgroundAndBack(saved: () -> Boolean = { true }) {
        ctl.pause().stop()
        assertTrue("the onStop save", onStopSave(saved))
        ctl.restart().resume()
        settle()
    }

    /** Waits up to 10 s of real time for the onStop save, letting at most 30 s of fake time pass (the autosave is 45 s). */
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

    /** The app leaves the screen (its onStop save writes [unsaved]), its process dies, a new activity starts from its saved state. */
    private fun processDeath(unsaved: Layer) {
        val old = session
        assertTrue("\"${unsaved.name}\" has an edit to save", unsaved.savedVersion != unsaved.contentVersion)
        val bundle = Bundle()
        ctl.pause().stop()
        assertTrue("the onStop save wrote \"${unsaved.name}\"", onStopSave { unsaved.savedVersion == unsaved.contentVersion })
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
    }

    private fun pixels(b: Bitmap): IntArray = Qa18CompatV17Golden.pixels(b)

    private fun direct(f: Filter, src: Bitmap, values: FilterValues): IntArray =
        Fx.roundTrip(Fx.direct(f, BitmapUtils.toPixelBuffer(src), values, 1f, c.doc.dpi))

    /** The session's defaults of [f]: its own, a drawing-color parameter at the drawing color. */
    private fun defaults(f: Filter): Map<String, Any> {
        val v = f.defaultValues()
        for (p in f.params) if (p is FilterParam.Color && p.useDrawingColor) v.set(p.key, c.color)
        return v.asMap()
    }

    private fun comparable(m: Map<String, Any>): Map<String, Any> = m.mapValues { (_, v) ->
        when (v) { is IntArray -> v.toList(); is FloatArray -> v.toList(); else -> v }
    }

    /** No filter panel, no color picker, no ✓ / ✕ of a tool. */
    private fun assertNothingStuck(where: String) {
        settle(2)
        assertNull("$where: no filter session", c.filterSession)
        assertFalse("$where: no Apply filter", has("Apply filter", exact = true))
        assertFalse("$where: no Cancel filter", has("Cancel filter", exact = true))
        assertFalse("$where: no color picker", has("OK", exact = true))
        val stray = SmokeUi.shown().filter { (it.startsWith("Apply ") || it.startsWith("Discard ")) && it.endsWith(" edit") }
        assertEquals("$where: no ✓ / ✕", if (c.currentTool.hasPendingWork) 2 else 0, stray.size)
    }

    private fun shot(name: String) {
        val f = Qa18CompatShots.save("filters-$name", IbisShots.capture())
        note("shot ${f.path}")
    }

    private fun data(l: Layer): Any {
        val d = l.dataSnapshot()
        return d.copy(array = d.array?.copy(pixels = null)) to d.array?.pixels?.let { Triple(it.left, it.top, Qa18CompatV17Golden.crc(it.bitmap)) }
    }

    @Test
    fun fringeFiltersThroughTheBackgroundAndProcessDeath() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        app = RuntimeEnvironment.getApplication() as BrushworkApp
        Qa18CompatV17Golden.install(app)
        val v17Dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), File(projectDir, ProjectFormat.PROJECT_FILE).readText())
        SmokeUi.markBaseline()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue("the gallery lists the v1.7 project", Smoke.pumpUntil { settle(1); has(Qa18CompatV17Golden.NAME, exact = true) })
        click(Qa18CompatV17Golden.NAME, exact = true)
        waitForEditor("from the gallery")
        val v17Data = c.doc.layers.associate { it.id to data(it) }
        val bg = c.doc.layers.single { it.name == "Background" }
        pick(bg)

        // A brush stroke (edges for the fringes; an edit for the onStop save to write).
        tool(ToolId.BRUSH.label)
        stroke(40f to 60f, 200f to 150f, 360f to 240f)
        assertEquals("the stroke is one step", 1, steps())
        val painted = pixels(bg.bitmap)

        // Chromatic aberration (Moving), Red/Cyan, a picked middle color; the background and back.
        val moving = FilterRegistry.byId("art.chromatic_aberration_moving") ?: throw AssertionError("Chromatic aberration (Moving) is registered")
        var fs = openFilter(moving, "chromatic")
        assertArrayEquals("RGB at first", FringeColors.rgb, fs.values.colors(FringeColors.KEY))
        visible { byText(FringeLabels18.RED_CYAN) }
        click(FringeLabels18.RED_CYAN, exact = true)
        visible { byDescription(FringeLabels18.MIDDLE) }.tap()
        settle()
        pickHex("FF8000")
        val custom = intArrayOf(FringeColors.RED, 0xFFFF8000.toInt(), FringeColors.CYAN)
        assertArrayEquals("Red/Cyan, the middle picked", custom, fs.values.colors(FringeColors.KEY))
        Fx.awaitPreview(fs, "custom fringes")
        val shown = Fx.overrideContent(c) ?: throw AssertionError("no preview")
        assertFalse("the preview shows the fringes", shown.contentEquals(painted))
        backgroundAndBack { bg.savedVersion == bg.contentVersion }
        assertTrue("the same session", c.filterSession === fs)
        assertFalse(fs.isClosed)
        assertArrayEquals("the same colors", custom, fs.values.colors(FringeColors.KEY))
        assertTrue("the panel", has("Apply filter", exact = true))
        assertArrayEquals("the preview never reached the layer", painted, pixels(bg.bitmap))
        val thumb = BitmapFactory.decodeFile(File(projectDir, ProjectFormat.THUMB_FILE).path) ?: throw AssertionError("no thumbnail")
        // The artwork with no override at all (not the editor's compositor, which knows the preview).
        val plain = Compositor(c.doc) { null }.renderFlattened()
        assertEquals("the thumbnail's size", plain.width to plain.height, thumb.width to thumb.height)
        val tp = pixels(thumb)
        val pp = pixels(plain)
        val off = tp.indices.count { i -> (0..24 step 8).any { s -> abs((tp[i] ushr s and 0xFF) - (pp[i] ushr s and 0xFF)) > 2 } }
        assertEquals("the gallery thumbnail is the artwork without the preview", 0, off)
        Fx.awaitPreview(fs, "back from the background")
        val src = bg.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val expected = direct(moving, src, fs.values.copy())
        assertFalse("the fringes change the layer", expected.contentEquals(painted))
        applyFilter(moving)
        assertArrayEquals("the layer is Moving's result with the picked fringes", expected, pixels(bg.bitmap))
        shot("fringes")

        // Glitch: the static's color picker open through the background, then a hex picked.
        val glitch = FilterRegistry.byId("art.glitch") ?: throw AssertionError("Glitch is registered")
        fs = openFilter(glitch, "glitch")
        assertEquals("the static is white at first", FringeColors.WHITE, fs.values.color(GlitchFilter.STATIC_COLOR_KEY))
        visible { clickableRightOf(FringeLabels18.STATIC_COLOR) }.tap()
        settle()
        assertTrue("the color picker is open", has("OK", exact = true))
        backgroundAndBack()
        assertTrue("the same session", c.filterSession === fs)
        assertTrue("the color picker is still open", has("OK", exact = true))
        pickHex("20C040")
        assertEquals("the static's color picked", 0xFF20C040.toInt(), fs.values.color(GlitchFilter.STATIC_COLOR_KEY))
        Fx.awaitPreview(fs, "static")
        val src2 = bg.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val expected2 = direct(glitch, src2, fs.values.copy())
        applyFilter(glitch)
        assertArrayEquals("the layer is Glitch's result with the picked static", expected2, pixels(bg.bitmap))
        val glitched = pixels(bg.bitmap)
        assertEquals("three steps", 3, steps())

        // Opened again: the defaults, not the last colors; ✕ records nothing.
        fs = openFilter(moving, "chromatic")
        assertEquals("Chromatic aberration (Moving) starts from its defaults", comparable(defaults(moving)), comparable(fs.values.asMap()))
        click("Cancel filter", exact = true)
        settle()
        assertNothingStuck("cancelled")
        assertEquals("✕ records nothing", 3, steps())
        assertArrayEquals(glitched, pixels(bg.bitmap))

        // Process death with Chromatic aberration open on custom colors.
        fs = openFilter(moving, "chromatic")
        visible { byText(FringeLabels18.BLUE_YELLOW) }
        click(FringeLabels18.BLUE_YELLOW, exact = true)
        visible { byDescription(FringeLabels18.MIDDLE) }.tap()
        settle()
        pickHex("8040FF")
        Fx.awaitPreview(fs, "before death")
        processDeath(bg)
        val restored = c.doc.layers.single { it.name == "Background" }
        assertNothingStuck("restored")
        assertEquals("a fresh history", 0, steps())
        assertArrayEquals("the layer as saved on leaving: nothing applied", glitched, pixels(restored.bitmap))
        Smoke.assertQuiet(c, "restored")
        shot("restored")
        fs = openFilter(moving, "chromatic")
        assertArrayEquals("RGB again after the restart", FringeColors.rgb, fs.values.colors(FringeColors.KEY))
        click("Cancel filter", exact = true)
        settle()
        assertNothingStuck("cancelled after the restart")

        // Back to the gallery: both applies saved; every other v1.7 layer as v1.7 left it.
        click("Back to gallery", settleAfter = false)
        assertTrue("back in the gallery", Smoke.pumpUntil(30_000) { settle(1); app.editorSession == null && has("New canvas") })
        val saved = runBlocking { app.repository.load(id) }
        assertEquals(emptyList<String>(), saved.loadWarnings)
        assertArrayEquals("\"Background\" saved with both filters", glitched, pixels(saved.layers.single { it.name == "Background" }.bitmap))
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), File(projectDir, ProjectFormat.PROJECT_FILE).readText())
        assertEquals("folders: format 3", 3, dto.formatVersion)
        var untouched = 0
        for (l in saved.layers) {
            if (l.id == bg.id) continue
            assertEquals("\"${l.name}\": v1.7's data", v17Data.getValue(l.id), data(l))
            assertEquals("\"${l.name}\": v1.7's file", v17Dto.layers.single { it.id == l.id }.file, dto.layers.single { it.id == l.id }.file)
            untouched++
        }
        assertEquals("19 v1.7 layers untouched", 19, untouched)
        dog.interrupt()
    }
}
