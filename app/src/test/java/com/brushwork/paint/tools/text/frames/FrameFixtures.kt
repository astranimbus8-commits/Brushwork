package com.brushwork.paint.tools.text.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.WrapFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Shared set-up of the linked text frame tests (v1.6, §3.6): a document, the Text frames tool, gestures. */
internal object FrameFixtures {
    const val LOREM = WrapFixtures.LOREM

    /** A longer story: three paragraphs. */
    val STORY = LOREM + "\n" + LOREM.replace("Lorem", "Second").uppercase() + "\nThird paragraph: " + LOREM.take(180)

    class Setup(val c: EditorController, val background: Layer, val layer2: Layer, val tool: TextFrameTool) {
        val doc: Document get() = c.doc
    }

    fun setup(context: Context, w: Int = 400, h: Int = 300, scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)): Setup {
        val doc = Document("frames", "frames", w, h)
        val bg = Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(WHITE) }
        val l2 = Layer(doc.newLayerId(), "Layer 2", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += bg
        doc.layers += l2
        doc.activeLayerIndex = 1
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, doc, scope, settings)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT_FRAMES)
        val tool = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        // Previews run at once (no timers to drive in tests).
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
        return Setup(c, bg, l2, tool)
    }

    /** A finger drag in document px (the view is the identity). */
    fun drag(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 4) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            c.pointerMove(ToolPoint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f))
        }
        c.pointerUp(ToolPoint(x1, y1))
    }

    fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    /**
     * Draws a frame over doc rect ([l], [t]) - ([r], [b]), types [text] in [size] px black text
     * in the story editor and confirms it (one step "Add text frame"); returns the new frame.
     */
    fun newFrame(s: Setup, l: Float, t: Float, r: Float, b: Float, text: String = STORY, size: Float = 16f): Layer {
        s.c.color = BLACK
        drag(s.c, l, t, r, b)
        assertTrue("the story editor opened for the new frame", s.tool.story.isOpen && s.tool.story.editingNew)
        s.tool.story.setText(text)
        s.tool.story.setSizePx(size)
        s.tool.story.confirmEditor()
        val layer = s.c.activeLayer
        assertTrue("a frame was created", s.c.textThreads.isFrame(layer))
        return layer
    }

    /** Taps [frame]'s out-port (the view is the identity: screen = document). */
    fun tapOutPort(s: Setup, frame: Layer) {
        val p = FramePorts.outPort(s.c.viewTransform, FrameGeometry.outerRect(itemOf(frame)))
        tap(s.c, p.x, p.y)
    }

    /** Links a new frame over ([l], [t]) - ([r], [b]) after [from] (out-port, then a drag): one step "Link frame". */
    fun linkFrame(s: Setup, from: Layer, l: Float, t: Float, r: Float, b: Float): Layer {
        tapOutPort(s, from)
        assertTrue("the out-port is loaded", s.tool.linkFrom === from)
        drag(s.c, l, t, r, b)
        val layer = s.c.activeLayer
        assertTrue(s.c.textThreads.isFrame(layer))
        return layer
    }

    fun itemOf(layer: Layer): TextItem = TextCodec.decode(layer.textData)!!

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** [item] rendered alone into a [w] x [h] bitmap, as a text layer is drawn. */
    fun render(item: TextItem, w: Int, h: Int): Bitmap =
        BitmapUtils.createLayerBitmap(w, h).also { TextRenderer.drawItem(Canvas(it), item, TextRenderer.prepare(item), null) }

    /** I1: [layer]'s pixels equal a fresh rendering of the item it stores. */
    fun assertRendered(layer: Layer, where: String = layer.name) {
        val item = itemOf(layer)
        assertArrayEquals("$where: pixels equal a fresh rendering of the stored frame (I1)", pixels(render(item, layer.width, layer.height)), pixels(layer.bitmap))
    }

    /** The frames of [layer]'s story in chain order. */
    fun chainOf(c: EditorController, layer: Layer): List<Layer> = c.textThreads.framesOf(itemOf(layer).thread.storyId).map { it.layer }

    /**
     * I9 and I1 for story [storyId]: one story copy, rev and look in every frame, contiguous
     * slices from 0, `text == story[start, end)`, `overset` exactly on an overflowing last frame,
     * each frame's slice ending where its layout says, and each frame's pixels its rendering.
     */
    fun assertWhole(c: EditorController, storyId: Long) {
        val frames = c.textThreads.framesOf(storyId)
        assertTrue("story $storyId has frames", frames.isNotEmpty())
        val first = frames.first().item
        var start = 0
        for ((k, f) in frames.withIndex()) {
            val th = f.item.thread
            assertEquals("same story copy", first.thread.story, th.story)
            assertEquals("same rev", first.thread.rev, th.rev)
            assertEquals("same look", FrameGeometry.storyLook(first.spec), FrameGeometry.storyLook(f.item.spec))
            assertEquals("frame $k starts where the one before ends", start, th.start)
            assertEquals("I9", th.story.substring(th.start, th.end), f.item.text)
            assertEquals("frame $k ends where its layout says", TextRenderer.frameEnd(f.item), th.end)
            assertEquals("overset only on an overflowing last frame", k == frames.lastIndex && th.end < th.story.length, th.overset)
            assertEquals(0f, f.item.rotationDeg, 0f)
            assertRendered(f.layer, "frame $k")
            start = th.end
        }
    }

    /** Every layer's pixels, mask and data, by identity (to compare a document before and after undo). */
    class Snapshot(val entries: List<Triple<Layer, IntArray, String?>>, val active: Int)

    fun snapshot(c: EditorController): Snapshot =
        Snapshot(c.doc.layers.map { Triple(it, pixels(it.bitmap), it.textData) }, c.doc.activeLayerIndex)

    fun assertSnapshot(c: EditorController, s: Snapshot, where: String) {
        assertEquals("$where: layers", s.entries.map { it.first }, c.doc.layers.toList())
        for ((i, e) in s.entries.withIndex()) {
            val l = c.doc.layers[i]
            assertArrayEquals("$where: pixels of ${l.name}", e.second, pixels(l.bitmap))
            assertEquals("$where: text data of ${l.name}", e.third, l.textData)
        }
    }

    /** The outer box of [layer]'s frame. */
    fun boxOf(layer: Layer): RectF = FrameGeometry.outerRect(itemOf(layer))

    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
}
