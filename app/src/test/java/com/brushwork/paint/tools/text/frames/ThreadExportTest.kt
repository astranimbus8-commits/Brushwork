package com.brushwork.paint.tools.text.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.text.frames.FrameFixtures.assertWhole
import com.brushwork.paint.tools.text.frames.FrameFixtures.itemOf
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.setup
import com.brushwork.paint.tools.transform.ContentBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Exporting linked frames (v1.6, §3.6c/d "ThreadExportTest"): each frame is an ordinary text, so
 * an SVG gets real `<text>` lines per frame (each frame its own slice, nothing twice) and a PDF
 * gets the letters as outlines where the frame's pixels are; a Brushwork SVG brings the linked
 * story back when it is opened again.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadExportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun scene(c: EditorController, format: VectorFormat): ExportScene =
        runBlocking { ExportSceneBuilder(c, ExportOptions(format), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }

    private fun threeFrames(s: FrameFixtures.Setup): List<Layer> {
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        val f2 = linkFrame(s, f1, 220f, 20f, 380f, 120f)
        val f3 = linkFrame(s, f2, 20f, 160f, 380f, 290f)
        return listOf(f1, f2, f3)
    }

    private fun squash(t: String) = t.filterNot { it.isWhitespace() }

    @Test
    fun anSvgHasEachFramesOwnLinesAsText() {
        val s = setup(context)
        val frames = threeFrames(s)
        val sc = scene(s.c, VectorFormat.SVG)
        for (f in frames) {
            val layer = sc.layers.single { it.name == f.name }
            val text = layer.items.filterIsInstance<SceneItem.Text>().single()
            assertEquals("${f.name}: exactly its slice", squash(itemOf(f).text), squash(text.lines.joinToString("") { it.text }))
        }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(sc).write(out) }
        val svg = out.toString("UTF-8")
        val texts = Regex("<text[ >]").findAll(svg).count()
        assertTrue("real <text> for every frame ($texts)", texts >= frames.size)
        // Every frame's first words are in the file once: no frame repeats its neighbour's text.
        for (f in frames) {
            val first = itemOf(f).text.trim().split(Regex("\\s+")).first()
            assertTrue("\"$first\" of ${f.name} in the SVG", svg.contains(first))
        }
    }

    @Test
    fun aPdfHasEachFramesLettersAsOutlinesWhereItsPixelsAre() {
        val s = setup(context)
        val frames = threeFrames(s)
        val sc = scene(s.c, VectorFormat.PDF)
        for (f in frames) {
            val layer = sc.layers.single { it.name == f.name }
            val shape = layer.items.filterIsInstance<SceneItem.Shape>().single()
            val b = shape.path.controlBounds()!!
            val px = ContentBounds.of(f.bitmap)!!
            assertTrue("${f.name}: outline $b vs pixels $px", abs(b.left - px.left) <= 2f && abs(b.right - px.right) <= 2f)
            assertTrue("${f.name}: outline $b vs pixels $px", abs(b.top - px.top) <= 2f && abs(b.bottom - px.bottom) <= 2f)
        }
        val out = ByteArrayOutputStream()
        runBlocking { PdfWriter(sc).write(out) }
        assertTrue(out.size() > 1000)
        assertTrue(out.toString("ISO-8859-1").startsWith("%PDF"))
    }

    @Test
    fun aBrushworkSvgBringsTheLinkedStoryBack() {
        val s = setup(context)
        val frames = threeFrames(s)
        val story = s.c.textThreads.story(itemOf(frames[0]).thread.storyId)!!.text
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene(s.c, VectorFormat.SVG)).write(out) }
        val svg = com.brushwork.paint.exchange.svg.SvgParser.parse(out.toByteArray())
        val payload = svg.payload()!!

        val fresh = Smoke.document(400, 300, layers = 1, whiteBottom = true)
        val c = Smoke.controller(context, fresh)
        val replace = fresh.layers.toList()
        val target = com.brushwork.paint.exchange.ImportTarget(400, 300, fresh.dpi, fresh.colorMode, com.brushwork.paint.exchange.ImportLayers.room(c) + replace.size, c.maxLayers)
        val prepared = com.brushwork.paint.exchange.PayloadImport.prepare(payload, { key -> svg.imageData(key)?.let { com.brushwork.paint.exchange.image.PngDecoder.decode(it) } }, target)
        com.brushwork.paint.exchange.PayloadImport.apply(c, prepared, replace)
        val restored = c.textThreads.allFrames()
        assertEquals(3, restored.size)
        val id = restored.first().thread.storyId
        assertEquals(story, c.textThreads.story(id)!!.text)
        assertEquals(frames.map { itemOf(it).text }, c.textThreads.framesOf(id).map { it.item.text })
        assertWhole(c, id)
    }
}
