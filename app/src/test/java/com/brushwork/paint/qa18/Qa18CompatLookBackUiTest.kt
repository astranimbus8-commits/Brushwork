package com.brushwork.paint.qa18

import android.graphics.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.MainActivity
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18CompatV17Golden.crc
import com.brushwork.paint.qa18.Qa18CompatV17Golden.resource
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
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
 * v1.8 QA (compat, verifier): a v1.8 artwork the REAL v1.7.0 build opened and saved untouched
 * (`qa18/compat/v18-written` with v1.7's `v17-untouched/project.json`: every array's look gone
 * from its spec, its cache still drawn with the look), back in v1.8 through the real app on the
 * user's 392 dp phone:
 * - "Compat 18" opened from the gallery, "Array 1" picked, the Array tool, its sheet's Look tab:
 *   no look shown (End opacity 100 %, Reset look off), nothing recorded; left, nothing written.
 * - Look › End opacity typed: ONE step "Array look", the spec's look, the copies drawn again
 *   (what the spec draws within the copies' bounds, the old and the new; outside them pixels of
 *   the old look's larger, turned copies stay as v1.7 left them, 257 px here, the first edit
 *   redrawing only `ArrayRenders`' area); undo gives back the spec without a look and
 *   the very pixels v1.7 left (exact restore), redo the edit; back to the gallery, the look and
 *   its pixels are saved together, the project still format 1 (no folders, I14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.compatlookbacksandbox"])
class Qa18CompatLookBackUiTest {
    private lateinit var ctl: ActivityController<MainActivity>
    private lateinit var app: BrushworkApp
    private val activity: MainActivity get() = ctl.get()
    private val id = "qa18-compat-v18"
    private val name = "Compat 18"
    private val projectDir: File get() = File(app.filesDir, "projects/$id")

    private fun note(s: String) = println("[qa18 compat look back] $s")

    private val session: EditorSession get() = app.editorSession ?: throw AssertionError("no editor session")
    private val c: EditorController get() = (session.state as EditorSession.State.Ready).controller

    private fun screenOf(): ChromeScreen = ChromeScreen(activity, c)

    private fun ui(): Qa16Ui = Qa16Ui(screenOf())

    private fun steps(): Int = c.undoManager.undoCount

    /** The stored CRC of each layer as v1.8 wrote it (`v18probe.tsv`), by name; and the files. */
    private val v18 = String(resource("v18-written/v18probe.tsv"), Charsets.UTF_8).lines().map { it.split('\t') }
    private val storedOf: Map<String, Long> = v18.filter { it[0] == "layer" }.associate { it[2] to it[3].toLong() }
    private val files: List<String> = v18.single { it[0] == "files" }[1].split(',')

    /** As v1.7 left it: v1.8's files, v1.7's `project.json`. */
    private fun install() {
        File(app.filesDir, "projects").deleteRecursively()
        projectDir.mkdirs()
        for (f in files) File(projectDir, f).writeBytes(resource("v18-written/$f"))
        File(projectDir, ProjectFormat.PROJECT_FILE).writeBytes(resource("v17-untouched/project.json"))
    }

    private fun onDisk(): Map<String, ByteArray> = projectDir.listFiles()!!.filter { it.name != ProjectFormat.THUMB_FILE }.associate { it.name to it.readBytes() }

    private fun waitForEditor(where: String) {
        assertTrue("$where: the editor opened", Smoke.pumpUntil(30_000) {
            settle(1)
            (app.editorSession?.state as? EditorSession.State.Ready) != null &&
                (Smoke.find(activity.window.decorView, CanvasView::class.java)?.width ?: 0) > 0
        })
        settleRenders(where)
        assertEquals(id, c.doc.id)
    }

    private fun settleRenders(where: String) {
        assertTrue("$where: settled", Smoke.pumpUntil(30_000) {
            settle(1)
            c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending && c.pendingSavedSelections.isEmpty()
        })
        settle()
    }

    private fun open() {
        assertTrue("the gallery lists \"$name\"", Smoke.pumpUntil { settle(1); has(name, exact = true) })
        click(name, exact = true)
        waitForEditor("from the gallery")
    }

    private fun backToGallery() {
        click("Back to gallery", settleAfter = false)
        assertTrue("back in the gallery", Smoke.pumpUntil(30_000) { settle(1); app.editorSession == null && has("New canvas") })
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
        val label = LayerLabels.selectRow(c.doc.indexOf(layer) + 1)
        val rowRe = Regex("^Select layer (\\d+)$")
        val n = c.doc.indexOf(layer) + 1
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

    /** The Array tool on the active layer, its sheet open on the Look tab. */
    private fun lookTab() {
        tool(ToolId.ARRAY.label)
        // The tool opens its sheet on an arrayed layer; the strip's chip opens it otherwise.
        if (screenOf().tagged(V17Tags.ARRAY_SHEET) == null) click("Array settings", exact = true)
        settle()
        assertNotNull("the Array sheet; shown: ${SmokeUi.shown().take(60)}", screenOf().tagged(V17Tags.ARRAY_SHEET))
        ui().reach(ArrayLabels18.LOOK, 44f)
        click(ArrayLabels18.LOOK, exact = true)
        assertNotNull("the Look tab", screenOf().tagged(V18Tags.ARRAY_LOOK))
    }

    /** The number field [label] scrolled wholly into view in its sheet, then typed and Done. */
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
            scroll.invoke(0f, if (n.positionInWindow.y < p!!.boundsInWindow.top) -60f * s.density else 60f * s.density)
            settle(2)
        }
        throw AssertionError("\"$label\" never came wholly into view")
    }

    private fun fieldText(label: String): String? = SmokeUi.field(label).node.config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun pixels(l: Layer): IntArray = Qa18CompatV17Golden.pixels(l.bitmap)

    private fun shot(name: String) {
        val f = Qa18CompatShots.save("lookback-$name", IbisShots.capture())
        note("shot ${f.path}")
    }

    @Test
    fun aLookEditedAfterV17DroppedIt() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        app = RuntimeEnvironment.getApplication() as BrushworkApp
        install()
        val v17 = onDisk()
        SmokeUi.markBaseline()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()

        // Opened, the Look tab looked at, left: nothing recorded, nothing written.
        open()
        assertEquals(emptyList<String>(), c.doc.loadWarnings)
        for (l in c.doc.layers) assertEquals("\"${l.name}\": v1.8's pixels", storedOf.getValue(l.name), crc(l.bitmap))
        var arrayed = c.doc.layers.single { it.name == "Array 1" }
        assertNull("v1.7 dropped the look", arrayed.array!!.spec.look)
        pick(arrayed)
        lookTab()
        assertEquals("no look: End opacity 100 %", "100", fieldText(ArrayLabels18.END_OPACITY))
        ui().reach(ArrayLabels18.RESET_LOOK)
        assertFalse("Reset look is off without a look", SmokeUi.isEnabled(ArrayLabels18.RESET_LOOK, exact = true))
        assertEquals("looking records nothing", 0, steps())
        assertEquals("nothing edited", 0, c.editCount)
        assertEquals("the cache still shows the look", storedOf.getValue("Array 1"), crc(arrayed.bitmap))
        shot("stale")
        Finger.back()
        tool(ToolId.BRUSH.label)
        backToGallery()
        val after = onDisk()
        assertEquals("the same files", v17.keys.sorted(), after.keys.sorted())
        for ((f, bytes) in v17) assertArrayEquals("$f: nothing was saved", bytes, after.getValue(f))

        // End opacity typed: one step, the look set, the copies drawn again.
        open()
        arrayed = c.doc.layers.single { it.name == "Array 1" }
        val stale = pixels(arrayed)
        val plainSpec = arrayed.array!!.spec
        pick(arrayed)
        lookTab()
        typeField(ArrayLabels18.END_OPACITY, "40")
        settleRenders("the look")
        assertEquals("the look is one step", 1, steps())
        assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
        val look = arrayed.array!!.spec.look ?: throw AssertionError("no look")
        assertEquals(0.4f, look.opacityEnd, 1e-4f)
        assertEquals("the rest of the spec", plainSpec, arrayed.array!!.spec.copy(look = null))
        val edited = pixels(arrayed)
        val fresh = Qa18CompatV17Golden.fresh(c.doc, arrayed)!!
        val bounds = Rect().also { ArrayDraw.cacheBounds(arrayed.dataSnapshot())!!.roundOut(it); it.inset(-1, -1) }
        val w = c.doc.width
        var inside = 0
        var outside = 0
        val left = Rect(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        for (i in fresh.indices) if (fresh[i] != edited[i]) {
            val x = i % w
            val y = i / w
            if (bounds.contains(x, y)) inside++ else {
                outside++
                left.set(minOf(left.left, x), minOf(left.top, y), maxOf(left.right, x + 1), maxOf(left.bottom, y + 1))
            }
        }
        note("after the look edit: ${fresh.indices.count { fresh[it] != stale[it] }} px differ from what v1.7 left; the cache differs from what the spec draws by $inside px inside the copies' bounds $bounds, $outside px outside (within $left)")
        assertEquals("within the copies' bounds the cache is what the spec draws (I1)", 0, inside)
        // Outside them the edit redraws nothing today: what differs there is what v1.7 left (the
        // old look's larger, turned copies). ARCHITECTURE "Formats and compatibility (v1.8)" says
        // "until the array is re-rendered"; a re-render redraws only this area. Never anything
        // else: each pixel there is v1.7's or what the spec draws.
        for (i in fresh.indices) if (!bounds.contains(i % w, i / w)) {
            assertTrue("outside the copies' bounds at ${i % w}, ${i / w}: v1.7's pixel or the spec's", edited[i] == stale[i] || edited[i] == fresh[i])
        }
        Finger.back()
        shot("edited")

        // Undo: the spec without a look and the pixels v1.7 left, exactly; redo: the edit.
        click("Undo", exact = true)
        settleRenders("undone")
        assertEquals(0, steps())
        assertNull("undo: no look", arrayed.array!!.spec.look)
        assertEquals("undo: the spec v1.7 left", plainSpec, arrayed.array!!.spec)
        assertArrayEquals("undo: the pixels v1.7 left", stale, pixels(arrayed))
        click("Redo", exact = true)
        settleRenders("redone")
        assertEquals(1, steps())
        assertEquals(0.4f, arrayed.array!!.spec.look!!.opacityEnd, 1e-4f)
        assertArrayEquals("redo: the edited pixels", edited, pixels(arrayed))
        Smoke.assertQuiet(c, "look back")
        tool(ToolId.BRUSH.label)

        // Saved together: the look and its pixels; still format 1.
        backToGallery()
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), File(projectDir, ProjectFormat.PROJECT_FILE).readText())
        assertEquals("no folders: format 1 (I14)", 1, dto.formatVersion)
        val saved = runBlocking { app.repository.load(id) }
        assertEquals(emptyList<String>(), saved.loadWarnings)
        val back = saved.layers.single { it.name == "Array 1" }
        assertEquals("the look saved", 0.4f, back.array!!.spec.look!!.opacityEnd, 1e-4f)
        assertArrayEquals("its pixels saved with it", edited, pixels(back))
        for (l in saved.layers) if (l.name != "Array 1") assertEquals("\"${l.name}\": untouched", storedOf.getValue(l.name), crc(l.bitmap))
        dog.interrupt()
    }
}
