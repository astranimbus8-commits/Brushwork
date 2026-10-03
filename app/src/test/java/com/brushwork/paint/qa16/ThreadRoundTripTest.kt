package com.brushwork.paint.qa16

import android.net.Uri
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.6 final QA: a linked story (`TextItem.thread`, three frames) stays whole — I9 in every frame
 * (`text == story[start, end)`, contiguous slices, one story copy and revision) — through
 * everything a user does with an artwork: save and reload (and the story can still be edited
 * there), Duplicate artwork, SVG / PDF export brought back into a new artwork of the same size
 * (the same story) or into the artwork it came from (a second story with a new id, after the
 * next edit). Canvas operations turn the frames into raster layers (text layers do that since
 * v1.4) in ONE step without breaking anything; undo brings the whole story back.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadRoundTripTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private class Chain(val s: FrameFixtures.Setup, val frames: List<Layer>) {
        val storyId: Long get() = itemOf(frames[0]).thread.storyId
    }

    private fun chain(): Chain {
        val s = FrameFixtures.setup(app)
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 160f, 380f, 290f)
        val ch = Chain(s, listOf(f1, f2, f3))
        assertWhole(s.c, ch.storyId)
        assertTrue("the story spans the frames", itemOf(f2).text.isNotEmpty())
        return ch
    }

    /** The frames [c] holds of story [id], with their data and I9. */
    private fun assertStory(c: EditorController, id: Long, data: List<String?>, where: String) {
        val frames = c.textThreads.framesOf(id)
        assertEquals("$where: three frames", 3, frames.size)
        assertEquals("$where: the frames' data", data, frames.map { it.layer.textData })
        assertWhole(c, id)
    }

    /** The story can be edited after the round trip: one step, every frame re-flowed (I9). */
    private fun assertEditable(c: EditorController, id: Long, where: String) {
        c.selectTool(ToolId.TEXT_FRAMES)
        val tool = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
        val frames = c.textThreads.framesOf(id).map { it.layer }
        val steps = c.undoManager.undoCount
        assertTrue("$where: the story editor opens", tool.openStoryEditor(frames[1]))
        tool.story.setText("Edited after the round trip.\n" + FrameFixtures.STORY.take(400))
        tool.story.confirmEditor()
        assertEquals("$where: the edit is one step", steps + 1, c.undoManager.undoCount)
        assertTrue("$where: re-flowed", itemOf(frames[0]).text.startsWith("Edited after the round trip."))
        assertWhole(c, id)
        c.undo()
        assertWhole(c, id)
    }

    @Test
    fun saveReloadAndDuplicateArtworkKeepTheStoryWhole() {
        val ch = chain()
        val data = ch.frames.map { it.textData }
        val repo = ProjectRepository(app)
        runBlocking { repo.save(ch.s.doc, null) }
        val loaded = runBlocking { repo.load(ch.s.doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val lc = Smoke.controller(app, loaded)
        assertStory(lc, ch.storyId, data, "reloaded")
        for ((a, b) in ch.frames.zip(lc.textThreads.framesOf(ch.storyId).map { it.layer })) {
            assertTrue("reloaded: ${a.name}'s pixels", a.bitmap.sameAs(b.bitmap))
        }
        assertEditable(lc, ch.storyId, "reloaded")
        val copyId = runBlocking { repo.duplicate(ch.s.doc.id) }
        val copy = runBlocking { repo.load(copyId) }
        val cc = Smoke.controller(app, copy)
        assertStory(cc, ch.storyId, data, "duplicate")
        assertEditable(cc, ch.storyId, "duplicate")
        File(app.filesDir, "projects/$copyId").deleteRecursively()
    }

    private fun exportFile(c: EditorController, format: VectorFormat): File =
        File(app.cacheDir, "thread-roundtrip.${format.extension}").apply { writeBytes(V15Fixtures.export(c, ExportOptions(format))) }

    private fun importEditable(c: EditorController, file: File) {
        val state = ExchangeUiState(c)
        state.context = app
        val before = c.doc.layers.size
        state.importUri(Uri.fromFile(file))
        assertTrue("asked", Smoke.pumpUntil { state.dialog is ExchangeDialog.MadeWithBrushwork && c.busyMessage == null })
        state.answerEditable()
        assertTrue("imported", Smoke.pumpUntil { c.busyMessage == null && c.doc.layers.size > before })
    }

    @Test
    fun svgAndPdfExportsBringTheStoryBack() {
        for (format in VectorFormat.entries) {
            val ch = chain()
            val data = ch.frames.map { it.textData }
            val file = exportFile(ch.s.c, format)
            // A new artwork of the same size: the same story, whole and editable.
            val fresh = Smoke.controller(app, Smoke.document(400, 300, layers = 1, whiteBottom = true))
            importEditable(fresh, file)
            assertStory(fresh, ch.storyId, data, "$format into a new artwork")
            for ((a, b) in ch.frames.zip(fresh.textThreads.framesOf(ch.storyId).map { it.layer })) {
                assertEquals("$format: ${a.name}'s pixels", 0, V15Fixtures.maxDiff(a.bitmap, b.bitmap))
            }
            assertEditable(fresh, ch.storyId, "$format into a new artwork")
            // The artwork it came from: the copy becomes a second story (a new id) on the next
            // edit; neither story re-flows into the other.
            val c = ch.s.c
            importEditable(c, file)
            assertEquals("$format: six frames share the id until the next edit", 6, c.textThreads.allFrames().count { it.thread.storyId == ch.storyId })
            val steps = c.undoManager.undoCount
            c.editWholeLayer(ch.s.background, "Fill") { b -> b.eraseColor(0xFFEEEEEE.toInt()) }
            assertEquals("$format: still one step", steps + 1, c.undoManager.undoCount)
            val stories = c.textThreads.stories()
            assertEquals("$format: two stories", 2, stories.size)
            assertEquals("$format: the original keeps its story and frames", ch.frames, stories.getValue(ch.storyId).map { it.layer })
            val other = stories.keys.single { it != ch.storyId }
            assertNotEquals(ch.storyId, other)
            for ((id, frames) in stories) {
                assertEquals("$format: the slices", data.map { TextCodec.decode(it)!!.text }, frames.map { it.item.text })
                assertWhole(c, id)
            }
            assertEditable(c, other, "$format second story")
            assertStory(c, ch.storyId, data, "$format original after the copy's edit")
        }
    }

    @Test
    fun canvasOperationsRasterizeTheFramesInOneStepAndUndoBringsTheStoryBack() {
        val ch = chain()
        val c = ch.s.c
        val data = ch.frames.map { it.textData }
        for ((name, op) in listOf<Pair<String, () -> Boolean>>(
            "Rotate 90° clockwise" to { CanvasOps.applyRotate(c, CanvasRotation.CW_90) },
            "Flip canvas horizontally" to { CanvasOps.applyFlip(c, true) },
            "Canvas size" to { CanvasOps.applyResizeCanvas(c, c.doc.width + 40, c.doc.height + 20, 1, 1, null) },
        )) {
            val steps = c.undoManager.undoCount
            assertTrue("$name ran", op())
            assertTrue("$name done", Smoke.pumpUntil(30_000) { c.busyMessage == null })
            assertEquals("$name: one step", steps + 1, c.undoManager.undoCount)
            assertTrue("$name: no frame left", c.textThreads.allFrames().isEmpty())
            for (f in ch.frames) assertNull("$name: ${f.name} is a raster layer", f.textData)
            Smoke.assertQuiet(c, name)
            c.undo()
            assertTrue(Smoke.pumpUntil { c.busyMessage == null })
            assertStory(c, ch.storyId, data, "$name undone")
        }
    }
}
