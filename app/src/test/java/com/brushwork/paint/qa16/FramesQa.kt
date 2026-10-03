package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameGeometry
import com.brushwork.paint.tools.text.frames.FrameOverlay
import com.brushwork.paint.tools.text.frames.FramePorts
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.textframes.LINK_HINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * v1.6 final QA (linked text frames): the Text frames tool on the full editor, driven like a
 * finger does — frames drawn by dragging on the canvas, the red "+" (out-port) pressed where the
 * canvas draws it (the real view transform, not the identity), the strip's buttons by their labels.
 */
internal class FramesUi(val s: ChromeScreen) {
    val ui = Qa16Ui(s)
    val c: EditorController get() = s.c
    val tool: TextFrameTool get() = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool

    /** Picks "Text frames" in the tool menu (previews run at once: no timers to drive). */
    fun pick() {
        ui.tool(ToolId.TEXT_FRAMES.label)
        assertEquals(ToolId.TEXT_FRAMES, c.activeToolId)
        tool.storyPreviewMs = 0L
        tool.dragPreviewMs = 0L
    }

    /** Canvas-local screen px → window px. */
    fun window(p: Vec2): Pair<Float, Float> {
        val loc = IntArray(2)
        s.canvas.getLocationInWindow(loc)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    fun box(layer: Layer) = FrameGeometry.outerRect(layer.item())

    /** [layer]'s red "+" / out-port where the canvas draws it (canvas px). */
    fun portOnCanvas(layer: Layer): Vec2 = FramePorts.outPort(c.viewTransform, box(layer))

    /** [layer]'s out-port in window px. */
    fun port(layer: Layer) = window(portOnCanvas(layer))

    /** Handle [h] of [layer]'s frame in window px. */
    fun handle(layer: Layer, h: FrameGeometry.Handle) = window(c.viewTransform.docToScreen(h.at(box(layer))))

    /** A finger drag in window px from [a] to [b] (through the middle). */
    fun drag(a: Pair<Float, Float>, b: Pair<Float, Float>) =
        ui.strokeWindow(a, ((a.first + b.first) / 2f) to ((a.second + b.second) / 2f), b)

    /** A finger drags a frame over document rect ([l], [t]) - ([r], [b]). */
    fun drawFrame(l: Float, t: Float, r: Float, b: Float) = ui.stroke(l to t, (l + r) / 2f to (t + b) / 2f, r to b)

    /** A finger tap on [layer]'s red "+". */
    fun tapPort(layer: Layer) {
        val (x, y) = port(layer)
        ui.tapWindow(x, y)
    }

    /** A finger tap in the middle of [layer]'s frame. */
    fun tapFrame(layer: Layer) {
        val b = box(layer)
        ui.tap(b.centerX(), b.centerY())
    }

    /** Selects [layer]'s frame with a tap (unless it is selected already: a second tap would open the story). */
    fun select(layer: Layer) {
        if (tool.selected !== layer) tapFrame(layer)
        assertSame("${layer.name} is selected", layer, tool.selected)
    }

    /** Types [text] in the open story editor at [sizePx] px; OK. */
    fun typeStory(text: String, sizePx: Int?) {
        SmokeUi.field("Text").type(text)
        settle()
        if (sizePx != null) ui.textSizePx(sizePx)
        click("OK", exact = true)
    }

    /**
     * Frame 1 drawn and typed ([text], [sizePx] px), frame 2 drawn after a tap on its red "+",
     * frame 3 dragged out of frame 2's "+": the chain the user builds (three steps).
     */
    fun chain(text: String = FrameFixtures.STORY, sizePx: Int = 14, f1: FloatArray = F1, f2: FloatArray = F2, f3End: Vec2 = F3_END): List<Layer> {
        drawFrame(f1[0], f1[1], f1[2], f1[3])
        assertTrue("the story editor opened for the new frame", tool.story.isOpen && tool.story.editingNew)
        typeStory(text, sizePx)
        val first = c.activeLayer
        assertTrue("frame 1 overflows", first.item().thread.overset)
        tapPort(first)
        assertSame("frame 1's + is loaded", first, tool.linkFrom)
        assertTrue(has(LINK_HINT, exact = true))
        drawFrame(f2[0], f2[1], f2[2], f2[3])
        val second = c.activeLayer
        assertEquals(listOf(first, second), FrameFixtures.chainOf(c, first))
        drag(port(second), window(c.viewTransform.docToScreen(f3End)))
        val chain = FrameFixtures.chainOf(c, first)
        assertEquals("three frames", 3, chain.size)
        assertFalse(tool.story.isOpen)
        return chain
    }

    /** The canvas view as the user sees it (the frames' boxes, ports, thread lines and handles are drawn by the view). */
    fun capture(): Bitmap {
        val v = s.canvas
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    /** Pixels of the red "+" colour within the port square centred at [at] (canvas px). */
    fun redAt(shot: Bitmap, at: Vec2): Int {
        val half = c.viewTransform.dp(9f).toInt()
        var n = 0
        for (y in (at.y.toInt() - half)..(at.y.toInt() + half)) for (x in (at.x.toInt() - half)..(at.x.toInt() + half)) {
            if (x !in 0 until shot.width || y !in 0 until shot.height) continue
            val p = shot.getPixel(x, y)
            if (near(p, FrameOverlay.OVERSET)) n++
        }
        return n
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= 40 &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= 40 &&
            abs((a and 0xFF) - (b and 0xFF)) <= 40

    companion object {
        val F1 = floatArrayOf(40f, 40f, 300f, 240f)
        val F2 = floatArrayOf(40f, 320f, 300f, 520f)
        val F3_END = Vec2(560f, 760f)
    }
}
