package com.brushwork.paint.qa16

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.ImportTarget
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertSnapshot
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.chainOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.snapshot
import com.brushwork.paint.tools.text.frames.FrameGeometry
import com.brushwork.paint.tools.text.frames.TextFrameTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * v1.6 QA bug: a Brushwork SVG imported ("Editable layers") into the picture it came from brought
 * its frames back under the original story id, and the copies were only to be taken apart "on
 * the next committed edit". When that edit was a frame's own (a resize, the story editor, a
 * link...), the Text frames tool flowed the story through all six frames as ONE chain (indices
 * 0..5, original and copy interleaved): the user's original frames lost their text. The import
 * now takes the copy apart in its own step (it reports the layers it adds, as adding a layer
 * does), so the story is never seen twice.
 */
@RunWith(RobolectricTestRunner::class)
class ReimportedStoryEditTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aCopyFrameResizedRightAfterTheImportLeavesTheOriginalAlone() {
        val s = FrameFixtures.setup(context)
        val c = s.c
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 160f, 380f, 290f)
        val frames = listOf(f1, f2, f3)
        val id = itemOf(f1).thread.storyId
        val out = ByteArrayOutputStream()
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        runBlocking { SvgWriter(scene).write(out) }
        val svg = SvgParser.parse(out.toByteArray())
        val before = snapshot(c)
        val steps = c.undoManager.undoCount
        val target = ImportTarget(400, 300, s.doc.dpi, s.doc.colorMode, ImportLayers.room(c), c.maxLayers)
        val prepared = PayloadImport.prepare(svg.payload()!!, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, target)
        PayloadImport.apply(c, prepared, emptyList())
        assertEquals("the import is one step", steps + 1, c.undoManager.undoCount)
        val copy = c.doc.layers.filter { c.textThreads.isFrame(it) && it !in frames }
        assertEquals(3, copy.size)
        assertEquals("taken apart in the import's step: two stories", 2, c.textThreads.stories().size)
        assertEquals(frames, chainOf(c, f1))
        assertEquals(id, itemOf(f1).thread.storyId)
        val copyId = itemOf(copy[0]).thread.storyId
        assertNotEquals(id, copyId)
        assertEquals(copy, chainOf(c, copy[0]))
        assertWhole(c, id)
        assertWhole(c, copyId)
        val afterImport = snapshot(c)

        // The user's next edit is a frame's own: the copy's frame 1 made smaller with its dot.
        val original = frames.map { it.textData }
        s.tool.select(copy[0])
        val end = itemOf(copy[0]).thread.end
        val br = FrameGeometry.Handle.BOTTOM_RIGHT.at(FrameFixtures.boxOf(copy[0]))
        FrameFixtures.drag(c, br.x, br.y, 120f, 80f)
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(TextFrameTool.RESIZE_LABEL, c.undoManager.undoLabel)
        assertEquals("the original frames are untouched", original, frames.map { it.textData })
        assertEquals(frames, chainOf(c, f1))
        assertEquals(copy, chainOf(c, copy[0]))
        assertTrue("the copy re-flowed", itemOf(copy[0]).thread.end < end)
        assertWhole(c, id)
        assertWhole(c, copyId)

        // Undo: the resize, then the import (the original exactly as before); redo brings the copy back apart.
        c.undo()
        assertSnapshot(c, afterImport, "resize undone")
        c.undo()
        assertSnapshot(c, before, "import undone")
        c.redo()
        assertSnapshot(c, afterImport, "import redone")
        assertEquals(2, c.textThreads.stories().size)
    }
}
