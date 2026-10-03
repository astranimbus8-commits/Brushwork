package com.brushwork.paint.qa16

import android.content.Intent
import android.net.Uri
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.QaExchange
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameGeometry
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * v1.6 final QA, linked frames kept: a three-frame story saved and opened again (the same frames,
 * the same pixels, still linked: a resize re-flows it); exported through More › Export SVG… and
 * Export PDF… and brought back with "Editable layers" into a new picture (linked again) and into
 * the picture it came from (two stories: the copy is taken apart from the original).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.framesexchangesandbox"])
class FramesExchangeUiTest {

    private lateinit var h: ChromeHarness
    private val app get() = RuntimeEnvironment.getApplication()

    private fun pixels(l: Layer) = FrameFixtures.pixels(l.bitmap)

    private fun fresh(): ChromeScreen =
        h.editor(Smoke.document(600, 800, layers = 1, whiteBottom = true)) { it.color = FrameFixtures.BLACK; it.snapping.enabled = false }

    private fun export(s: ChromeScreen, entry: String, name: String): ByteArray {
        click("More options")
        click(entry, exact = true)
        click("Save as…", exact = true)
        val (uri, out) = QaExchange.writableUri(s.activity, name)
        QaExchange.answerPicker(s.activity, uri, Intent.ACTION_CREATE_DOCUMENT)
        assertTrue("export finished", QaExchange.waitIdle(s.c) { out.size() > 0 })
        settle()
        assertTrue("the user is told: ${SmokeUi.shown().take(40)}", has("Saved \"$name\""))
        return out.toByteArray()
    }

    /** More › Import SVG or PDF… with [file], answered "Editable layers": one step. */
    private fun importEditable(s: ChromeScreen, file: File, frames: Int) {
        val c = s.c
        val steps = c.undoManager.undoCount
        val before = c.doc.layers.count { c.textThreads.isFrame(it) }
        click("More options")
        click("Import SVG or PDF…", exact = true)
        QaExchange.answerPicker(s.activity, Uri.fromFile(file), Intent.ACTION_OPEN_DOCUMENT)
        assertTrue("asks what to import", Smoke.pumpUntil { settle(1); has("Made with Brushwork", exact = true) })
        click("Editable layers", exact = true)
        assertTrue("imported", QaExchange.waitIdle(c) { c.doc.layers.count { c.textThreads.isFrame(it) } == before + frames })
        settle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
    }

    /** [back] are [source]'s frames again: the same items and pixels, one linked story. */
    private fun assertSameFrames(source: List<Layer>, back: List<Layer>, c: com.brushwork.paint.EditorController) {
        assertEquals(source.size, back.size)
        for (k in source.indices) {
            assertEquals("frame $k: the same frame", source[k].item(), back[k].item())
            assertArrayEquals("frame $k: the same pixels", pixels(source[k]), pixels(back[k]))
        }
        assertEquals("linked as before", back, chainOf(c, back[0]))
        assertWhole(c, back[0].item().thread.storyId)
    }

    /** A drag of frame 1's bottom-right dot (it must be selected): the story re-flows, one step. */
    private fun resizeFirst(f: FramesUi, first: Layer) {
        val c = f.c
        f.pick()
        f.select(first)
        val end = first.item().thread.end
        val steps = c.undoManager.undoCount
        f.drag(f.handle(first, FrameGeometry.Handle.BOTTOM_RIGHT), f.window(c.viewTransform.docToScreen(Vec2(220f, 160f))))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TextFrameTool.RESIZE_LABEL, c.undoManager.undoLabel)
        assertTrue("frame 1 holds less", first.item().thread.end < end)
        assertWhole(c, first.item().thread.storyId)
    }

    @Test
    fun framesSavedExportedAndImported() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        h = ChromeHarness()
        h.section("saved and opened again: still linked") { saveAndReopen() }
        h.section("SVG: back as editable layers in a new picture (linked) and in its own picture (two stories)") { svg() }
        h.section("PDF: back as editable layers (linked)") { pdf() }
        dog.interrupt()
        h.finish()
    }

    private fun saveAndReopen() {
        val repo = ProjectRepository(app)
        val id = runBlocking { repo.create(NewCanvasSpec("QA frames", 600, 800, 300f, background = FrameFixtures.WHITE)) }
        val s = h.editor(runBlocking { repo.load(id) }) { it.color = FrameFixtures.BLACK; it.snapping.enabled = false }
        val f = FramesUi(s)
        f.pick()
        val frames = f.chain()
        runBlocking { repo.save(s.c.doc, null) }

        val loaded = runBlocking { repo.load(id) }
        assertTrue("no load warnings: ${loaded.loadWarnings}", loaded.loadWarnings.isEmpty())
        val s2 = h.editor(loaded) { it.snapping.enabled = false }
        val c2 = s2.c
        val back = c2.doc.layers.filter { c2.textThreads.isFrame(it) }
        assertSameFrames(frames, chainOf(c2, back.first()).also { assertEquals(back.toSet(), it.toSet()) }, c2)
        resizeFirst(FramesUi(s2), chainOf(c2, back.first())[0])
        Smoke.assertQuiet(c2, "reopened")
    }

    private fun svg() {
        val s = fresh()
        val f = FramesUi(s)
        f.pick()
        val frames = f.chain()
        val id = frames[0].item().thread.storyId
        val bytes = export(s, "Export SVG…", "Smoke.svg")
        val xml = QaExchange.parseXml(bytes)
        val groups = QaExchange.layerGroups(xml).map { it.getAttributeNS(QaExchange.INKSCAPE_NS, "label") }
        for (l in frames) assertTrue("${l.name} is an SVG layer: $groups", l.name in groups)
        val file = File(app.cacheDir, "qa16-frames.svg").apply { writeBytes(bytes) }

        // The picture it came from: the copy becomes a story of its own in the import's step;
        // the original stays. (v1.6 QA bug: the copy kept the story id until the next edit, and
        // when that edit was a frame's own the six frames were flowed as ONE chain.)
        val c = s.c
        val exported = frames.map { it.item() }
        val before = FrameFixtures.snapshot(c)
        importEditable(s, file, 3)
        assertEquals("two stories already", 2, c.textThreads.stories().size)
        val copy = c.doc.layers.filter { c.textThreads.isFrame(it) && it !in frames }
        assertEquals(3, copy.size)
        val copyId = copy[0].item().thread.storyId
        assertNotEquals("the copy is a story of its own", id, copyId)
        assertEquals(copy, chainOf(c, copy[0]))
        val imported = FrameFixtures.snapshot(c)
        // The user goes on: frame 1 of the copy made smaller. The copy re-flows on its own; the
        // original is untouched.
        val original = frames.map { it.textData }
        resizeFirst(f, copy[0])
        assertEquals("the original frames are untouched", original, frames.map { it.textData })
        assertEquals("two stories", 2, c.textThreads.stories().size)
        assertEquals("the original keeps its story", frames, chainOf(c, frames[0]))
        assertEquals(id, frames[0].item().thread.storyId)
        assertEquals(copy, chainOf(c, copy[0]))
        assertWhole(c, id)
        assertWhole(c, copyId)
        // (The file's white "Layer 1" comes in too, above the original frames: the picture shows the copy.)
        Shots.save(c.compositor.renderFlattened(FrameFixtures.WHITE), "frames-svg-into-its-own-picture.png")
        assertEquals("the original frames are as exported", exported, frames.map { it.item() })
        // Two fingers undo the resize, then the import (the picture exactly as before it);
        // three fingers redo the import (the copy apart again).
        f.ui.twoFingerUndo()
        FrameFixtures.assertSnapshot(c, imported, "resize undone")
        f.ui.twoFingerUndo()
        FrameFixtures.assertSnapshot(c, before, "import undone")
        f.ui.threeFingerRedo()
        FrameFixtures.assertSnapshot(c, imported, "import redone")
        assertEquals(2, c.textThreads.stories().size)
        Smoke.assertQuiet(c, "SVG into its own picture")

        // A new picture: the three frames, linked again. (A second editor only now: the clicks
        // go to the newest window.)
        val s2 = fresh()
        importEditable(s2, file, 3)
        val c2 = s2.c
        val back = c2.doc.layers.filter { c2.textThreads.isFrame(it) }
        assertSameFrames(frames, chainOf(c2, back.first()), c2)
        resizeFirst(FramesUi(s2), chainOf(c2, back.first())[0])
        Smoke.assertQuiet(c2, "SVG into a new picture")
    }

    private fun pdf() {
        val s = fresh()
        val f = FramesUi(s)
        f.pick()
        val frames = f.chain()
        val bytes = export(s, "Export PDF…", "Smoke.pdf")
        val r = QaExchange.checkPdf(bytes)
        val (names, _) = QaExchange.ocgs(r)
        for (l in frames) assertTrue("${l.name} is a PDF layer: $names", l.name in names)
        val file = File(app.cacheDir, "qa16-frames.pdf").apply { writeBytes(bytes) }
        val s2 = fresh()
        importEditable(s2, file, 3)
        val c2 = s2.c
        val back = c2.doc.layers.filter { c2.textThreads.isFrame(it) }
        assertSameFrames(frames, chainOf(c2, back.first()), c2)
        resizeFirst(FramesUi(s2), chainOf(c2, back.first())[0])
        Smoke.assertQuiet(c2, "PDF into a new picture")
    }
}
