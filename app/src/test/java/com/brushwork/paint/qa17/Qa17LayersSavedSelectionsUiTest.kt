package com.brushwork.paint.qa17

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.MarqueeShape
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * v1.7 final QA, layers cluster (item 14, design §3.14; device checklist §6.4 row 14): saved
 * selections the way the user makes and uses them on the phone, in the full editor, by fingers
 * and the labels shown.
 *
 * Three rectangles dragged with "Select shape" and kept with the selection bar's "Save": the
 * third one's compression held, so its row shows at once with a spinner and no menu, and lands
 * as one step when done. The canvas turned 90° clockwise from the Canvas sheet: the saved masks
 * turn with the picture (checked pixel by pixel against the masks turned here). Each loaded with
 * another mode from its row's menu ("Load selection", "Add to selection", "Subtract from
 * selection", "Intersect with selection"): exactly the set algebra of the turned rectangles, one
 * step each. One renamed ("Rename selection", its "Name" field and "Rename"), one deleted ("Delete
 * saved selection": no question, one step). Saved, reopened in a new editor: the rows and masks
 * are the same, and loading one by finger gives its turned mask.
 */
internal class Qa17LayersSavedSelections(private val h: ChromeHarness) {
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private var u: Qa17LayersUi? = null
    private val l: Qa17LayersUi get() = u!!
    private val c: EditorController get() = s.c

    private fun attach(screen: ChromeScreen) {
        u?.release()
        s = screen
        u = Qa17LayersUi(screen)
        ui = l.ui
        settle()
    }

    fun release() {
        u?.release()
        u = null
    }

    // ================================================================== the picture and masks

    /** A blue-grey backdrop, an orange block top left and a green one bottom right: a turn shows. */
    private fun document(): Document = Smoke.document(W, H, layers = 2, whiteBottom = false).also { d ->
        val cv = Canvas(d.layers[0].bitmap)
        cv.drawColor(0xFF8CB4DC.toInt())
        cv.drawRect(20f, 20f, 120f, 80f, Paint().apply { color = 0xFFE07830.toInt() })
        cv.drawRect(150f, 100f, 240f, 170f, Paint().apply { color = 0xFF30A050.toInt() })
        d.layers[0].markChanged()
    }

    /** The mask of the rectangle [r] (left, top, right, bottom) on a [w] × [h] canvas. */
    private fun rect(r: IntArray, w: Int = W, h: Int = H): BooleanArray =
        BooleanArray(w * h) { i -> val x = i % w; val y = i / w; x >= r[0] && x < r[2] && y >= r[1] && y < r[3] }

    /** [m] (on the [W] × [H] canvas) turned 90° clockwise: [H] × [W], new (x, y) = old (y, H − 1 − x). */
    private fun turned(m: BooleanArray): BooleanArray = BooleanArray(W * H) { i -> val x = i % H; val y = i / H; m[(H - 1 - x) * W + y] }

    private fun bytes(m: BooleanArray): ByteArray = ByteArray(m.size) { if (m[it]) -1 else 0 }

    private fun bytes(sel: Selection?): ByteArray = BitmapUtils.alpha8ToBytes(requireNotNull(sel) { "no selection" }.mask)

    private infix fun BooleanArray.or(o: BooleanArray) = BooleanArray(size) { this[it] || o[it] }
    private infix fun BooleanArray.minus(o: BooleanArray) = BooleanArray(size) { this[it] && !o[it] }
    private infix fun BooleanArray.and(o: BooleanArray) = BooleanArray(size) { this[it] && o[it] }

    private fun assertMask(what: String, want: BooleanArray, got: ByteArray) {
        assertEquals(want.size, got.size)
        var off = 0
        for (i in want.indices) if ((got[i].toInt() and 0xFF) != (if (want[i]) 255 else 0)) off++
        assertEquals("$what: pixels off the expected mask", 0, off)
    }

    // ================================================================== fingers

    /** "Select shape" (a rectangle): a finger drag from corner to corner of [r]. */
    private fun drag(r: IntArray) {
        if (c.activeToolId != ToolId.MARQUEE) ui.tool("Select shape")
        val t = c.currentTool as MarqueeTool
        assertEquals("a rectangle", MarqueeShape.RECTANGLE, t.settings.shape)
        val before = c.selection
        ui.stroke(r[0].toFloat() to r[1].toFloat(), (r[0] + r[2]) / 2f to (r[1] + r[3]) / 2f, r[2].toFloat() to r[3].toFloat())
        assertTrue("the rectangle is selected", Smoke.pumpUntil { settle(1); c.selection != null && c.selection !== before && !t.busy })
        settle()
    }

    /** The selection bar's "Save" ("Save selection"), by finger. */
    private fun saveButton() {
        ui.reach(SavedSelectionLabels.SAVE, 32f)
        // The strip has stopped scrolling (a tap on a moving strip only stops it).
        Smoke.pump(600)
        settle()
        Finger.tap(s, SavedSelectionLabels.SAVE)
    }

    private fun rowNode(id: Long): SemanticsNode? =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == V17Tags.savedSelectionRow(id) }?.node

    private fun SemanticsNode.subtree(): List<SemanticsNode> = listOf(this) + children.flatMap { it.subtree() }

    /** The row of the saved selection named [name] tapped by finger: its menu opens. */
    private fun menu(name: String) {
        l.openLayers()
        ui.reach(name, 40f)
        Finger.tap(s, name)
        assertTrue("$name: its menu", has(SavedSelectionLabels.DELETE, exact = true))
    }

    /** [name]'s menu item [item]: one step [label], the selection [want]. */
    private fun load(name: String, item: String, want: BooleanArray) {
        menu(name)
        assertTrue("$item is enabled", SmokeUi.isEnabled(item))
        val before = l.steps()
        val old = c.selection
        click(item, exact = true)
        assertTrue("$item: done", Smoke.pumpUntil { settle(1); c.selection !== old })
        settle()
        assertEquals("$item: one step", before + 1, l.steps())
        assertEquals(item, c.undoManager.undoLabel)
        assertMask("$item ($name)", want, bytes(c.selection))
    }

    // ================================================================== 1. save three

    private lateinit var r1: BooleanArray
    private lateinit var r2: BooleanArray
    private lateinit var r3: BooleanArray

    fun saveThree() {
        attach(h.editor(document()) { it.snapping.enabled = false })
        assertEquals(392f, s.widthDp, 1f)
        r1 = rect(R1); r2 = rect(R2); r3 = rect(R3)
        val picture = l.pixels()

        // Two by finger: dragged, then "Save" in the selection bar; each lands as one step.
        for ((i, r) in listOf(R1, R2).withIndex()) {
            drag(r)
            assertMask("the dragged rectangle ${i + 1}", rect(r), bytes(c.selection))
            l.oneStep("Save ${i + 1}", HistoryLabels.SAVE_SELECTION) {
                saveButton()
                assertTrue("landed", Smoke.pumpUntil { settle(1); c.pendingSavedSelections.isEmpty() })
            }
            assertEquals("Selection ${i + 1}", c.doc.savedSelections.last().name)
            assertNotNull("the selection stays", c.selection)
        }

        // The third (a big one on the phone) held while it compresses: its row at once, with a
        // spinner and no menu; no step until it lands.
        drag(R3)
        val gate = CompletableDeferred<Unit>()
        c.beforeSavedSelectionPack = { gate.await() }
        val before = l.steps()
        saveButton()
        val pending = c.pendingSavedSelections.single()
        assertEquals("Selection 3", pending.name)
        l.openLayers()
        val row = rowNode(pending.id) ?: throw AssertionError("no row while it saves")
        assertTrue("the spinner", row.subtree().any { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null })
        assertTrue("its name", has("Selection 3", exact = true))
        assertTrue("not tappable", row.subtree().none { it.config.getOrNull(SemanticsActions.OnClick) != null })
        val name = Finger.element(s, "Selection 3") ?: throw AssertionError("its name is not shown")
        Finger.tapAt(s, name.center.x, name.center.y)
        assertFalse("a tap opens no menu", has(SavedSelectionLabels.LOAD, exact = true))
        assertEquals("no step yet", before, l.steps())
        Qa17LayersShots.saveGrid(
            "selections-1-saved",
            listOf(Qa17LayersShots.tinted(Qa17LayersShots.tinted(Qa17LayersShots.tinted(picture, bytes(r1), RED), bytes(r2), YELLOW), bytes(r3), PURPLE)),
            W, H, 1,
        )
        gate.complete(Unit)
        assertTrue("landed", Smoke.pumpUntil { settle(1); c.pendingSavedSelections.isEmpty() })
        settle()
        c.beforeSavedSelectionPack = null
        assertEquals("one step", before + 1, l.steps())
        assertEquals(HistoryLabels.SAVE_SELECTION, c.undoManager.undoLabel)
        val landed = rowNode(pending.id) ?: throw AssertionError("no row once landed")
        assertTrue("tappable once landed", landed.config.getOrNull(SemanticsActions.OnClick) != null)
        assertEquals(listOf("Selection 1", "Selection 2", "Selection 3"), c.doc.savedSelections.map { it.name })
        assertArrayEquals("saving changes nothing on screen", picture, l.pixels())
        for ((e, m) in c.doc.savedSelections.zip(listOf(r1, r2, r3))) assertMask(e.name, m, bytes(e.toSelection(W, H)))
        l.closeLayers()
    }

    // ================================================================== 2. turn the canvas, load with each mode

    fun turnAndLoad() {
        val turnedPicture = run {
            val p = l.pixels()
            IntArray(W * H) { i -> val x = i % H; val y = i / H; p[(H - 1 - x) * W + y] }
        }
        // The Canvas sheet's "Rotate & flip" tab: "Rotate 90° clockwise", one step.
        ui.tool("Canvas")
        SmokeUi.clickTab("Rotate & flip")
        l.oneStep("Rotate 90° clockwise", CanvasRotation.CW_90.label) {
            ui.reach(CanvasRotation.CW_90.label, 40f)
            click(CanvasRotation.CW_90.label, exact = true)
            assertTrue("turned", Smoke.pumpUntil { settle(1); c.doc.width == H && c.busyMessage == null })
        }
        Finger.back()
        settle()
        assertTrue("the sheet closed: ${SmokeUi.sheetTitles()}", SmokeUi.sheetTitles().isEmpty())
        assertEquals(H to W, c.doc.width to c.doc.height)
        assertArrayEquals("the picture turned", turnedPicture, l.pixels())
        assertNull("the selection does not survive a turn", c.selection)
        val t1 = turned(r1); val t2 = turned(r2); val t3 = turned(r3)
        for ((e, m) in c.doc.savedSelections.zip(listOf(t1, t2, t3))) assertMask("${e.name} turned", m, bytes(e.toSelection(H, W)))

        // Without a selection there is nothing to subtract from or intersect with.
        menu("Selection 1")
        assertFalse(SmokeUi.isEnabled(SavedSelectionLabels.SUBTRACT))
        assertFalse(SmokeUi.isEnabled(SavedSelectionLabels.INTERSECT))
        Finger.back()
        settle()

        val tiles = mutableListOf<IntArray>()
        fun tile() { tiles += Qa17LayersShots.tinted(l.pixels(), bytes(c.selection), RED) }
        load("Selection 1", SavedSelectionLabels.LOAD, t1); tile()
        load("Selection 2", SavedSelectionLabels.ADD, t1 or t2); tile()
        load("Selection 3", SavedSelectionLabels.SUBTRACT, (t1 or t2) minus t3); tile()
        load("Selection 2", SavedSelectionLabels.INTERSECT, ((t1 or t2) minus t3) and t2); tile()
        Qa17LayersShots.saveGrid("selections-2-turned-load-add-subtract-intersect", tiles, H, W, 4)
        l.closeLayers()
    }

    // ================================================================== 3. rename and delete

    fun renameAndDelete() {
        val ids = c.doc.savedSelections.map { it.id }
        // Rename: the dialog's "Name" field, then its "Rename" button.
        menu("Selection 2")
        click(SavedSelectionLabels.RENAME, exact = true)
        assertTrue("the dialog", has(SavedSelectionLabels.RENAME, exact = true))
        val field = SmokeUi.field("Name")
        assertEquals("the old name to type over", "Selection 2", field.node.config.getOrNull(SemanticsProperties.EditableText)?.text)
        l.oneStep("Rename", HistoryLabels.RENAME_SAVED_SELECTION) {
            field.focus()
            settle(2)
            SmokeUi.field("Name").type("Sky")
            settle(2)
            SmokeUi.clickIn(SavedSelectionLabels.RENAME, "Rename")
        }
        assertFalse("the dialog closed", has(SavedSelectionLabels.RENAME, exact = true))
        assertEquals(listOf("Selection 1", "Sky", "Selection 3"), c.doc.savedSelections.map { it.name })
        assertTrue(has("Sky", exact = true))
        assertFalse(has("Selection 2", exact = true))

        // Delete: no question, one step; the row goes.
        val picture = l.pixels()
        val sel = bytes(c.selection)
        menu("Selection 3")
        l.oneStep("Delete", HistoryLabels.DELETE_SAVED_SELECTION) { click(SavedSelectionLabels.DELETE, exact = true) }
        assertEquals(listOf(ids[0], ids[1]), c.doc.savedSelections.map { it.id })
        assertNull("its row is gone", rowNode(ids[2]))
        assertFalse(has("Selection 3", exact = true))
        assertArrayEquals("the picture is unchanged", picture, l.pixels())
        assertArrayEquals("the selection is unchanged", sel, bytes(c.selection))
        l.closeLayers()
        Smoke.assertQuiet(c, "saved selections")
    }

    // ================================================================== 4. save and reopen

    fun saveAndReopen() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        val kept = c.doc.savedSelections.map { Triple(it.id, it.name, bytes(it.toSelection(H, W)).toList()) }
        val picture = l.pixels()
        runBlocking { repo.save(c.doc, null) }
        val loaded = runBlocking { repo.load(c.doc.id) }
        assertEquals("the same entries", kept, loaded.savedSelections.map { Triple(it.id, it.name, bytes(it.toSelection(H, W)).toList()) })

        attach(h.editor(loaded))
        assertArrayEquals("reopened: the same picture", picture, l.pixels())
        assertNull("no selection on opening", c.selection)
        l.openLayers()
        for (e in loaded.savedSelections) assertNotNull("${e.name}'s row", rowNode(e.id))
        assertTrue(has("Selection 1", exact = true) && has("Sky", exact = true))
        load("Sky", SavedSelectionLabels.LOAD, turned(r2))
        Qa17LayersShots.saveGrid("selections-3-reopened-sky", listOf(Qa17LayersShots.tinted(l.pixels(), bytes(c.selection), RED)), H, W, 1)
        l.closeLayers()
        Smoke.assertQuiet(c, "reopened")
    }

    companion object {
        const val W = 256
        const val H = 192
        /** The three rectangles (left, top, right, bottom): the second overlaps both others. */
        val R1 = intArrayOf(30, 30, 110, 90)
        val R2 = intArrayOf(90, 60, 200, 140)
        val R3 = intArrayOf(60, 110, 240, 180)
        val RED = 0xFFE02020.toInt()
        val YELLOW = 0xFFF0D020.toInt()
        val PURPLE = 0xFF8030C0.toInt()

        fun run() {
            ShadowLog.stream = null
            SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 60_000)
            val h = ChromeHarness()
            val t = Qa17LayersSavedSelections(h)
            val times = mutableListOf<String>()
            fun timed(name: String, block: () -> Unit) {
                val t0 = System.nanoTime()
                block()
                times += "$name ${(System.nanoTime() - t0) / 1_000_000} ms"
            }
            // One editor for 1 to 3 (a section closes its editors), a new one for 4.
            h.section("1-3 save three, turn and load, rename and delete") {
                timed("1 save three") { t.saveThree() }
                timed("2 turn the canvas, load with each mode") { t.turnAndLoad() }
                timed("3 rename and delete") { t.renameAndDelete() }
            }
            h.section("4 save and reopen") { timed("4 save and reopen") { t.saveAndReopen() } }
            println("Qa17LayersSavedSelections times: $times")
            t.release()
            dog.interrupt()
            h.finish()
        }
    }
}

/** Item 14 on the user's phone (392 dp): saved selections in the full editor, by fingers. */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.layersselectionssandbox"])
class Qa17LayersSavedSelectionsUiTest {
    @Test
    fun savedSelectionsWorkByFingersInTheEditorAt392dp() = Qa17LayersSavedSelections.run()
}
