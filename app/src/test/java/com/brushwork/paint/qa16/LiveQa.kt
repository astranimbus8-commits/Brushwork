package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.live.LiveAdjust
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * v1.6 final QA (adjustment masks and the layer window): what the qa16 tests share. The user's
 * documents (a photo, two painted layers below the adjustment layer the Masks tool makes, two
 * above it, one of them Multiply), and the editor's canvas drawn as the phone draws it — one
 * `CanvasView.draw` per vsync — under the LIVE policy with an injected clock (I8: tests opt in).
 */
internal object QaDocs {
    /**
     * A [w] x [h] document: "Photo" (a gradient), "Below 1" and "Below 2" (shapes, partly
     * transparent), "Above 1" (Multiply) and "Above 2" (half transparent). The active layer is
     * "Below 2", so the adjustment layer the Masks tool makes sits with 3 layers below and 2 above.
     */
    fun stack(w: Int, h: Int): Document {
        val doc = Document("qa16", "QA16", w, h)
        fun layer(name: String, draw: (Canvas) -> Unit): Layer =
            Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { draw(Canvas(it.bitmap)); doc.layers += it }
        val aa = Paint.ANTI_ALIAS_FLAG
        layer("Photo") { it.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF203060.toInt(), 0xFFF0C080.toInt(), Shader.TileMode.CLAMP) }) }
        layer("Below 1") { cv ->
            val p = Paint(aa).apply { color = 0xCCE04020.toInt() }
            cv.drawCircle(w * 0.35f, h * 0.4f, w * 0.25f, p)
            cv.drawRect(w * 0.1f, h * 0.65f, w * 0.9f, h * 0.72f, p)
        }
        layer("Below 2") { cv -> cv.drawCircle(w * 0.65f, h * 0.55f, w * 0.3f, Paint(aa).apply { color = 0x9930A050.toInt() }) }
        layer("Above 1") { cv -> cv.drawRect(w * 0.2f, h * 0.1f, w * 0.8f, h * 0.25f, Paint().apply { color = 0xFF7088E0.toInt() }) }.blendMode = LayerBlendMode.MULTIPLY
        layer("Above 2") { cv -> cv.drawRect(w * 0.3f, h * 0.8f, w * 0.7f, h * 0.9f, Paint().apply { color = 0x80F0F0F0.toInt() }) }
        doc.activeLayerIndex = 2
        return doc
    }
}

/** The editor's canvas as the phone draws it, with the live adjustment under test control. */
internal class LiveCanvas(val s: ChromeScreen) {
    /** The injected clock (ns): frozen unless a test moves it (the finger resting, refinement). */
    var now = 0L

    /**
     * How far the clock moves per read ([clockRead]; 0 = frozen): a few ms make a refinement
     * frame's 8 ms budget run out after a tile or two, so a change can be caught half refined.
     * Keep it 0 during drags (a ticking clock makes live frames look slow and halves the scale).
     */
    var tick = 0L

    /** The clock a test installs as `liveAdjust.clock` when it wants [tick]. */
    fun clockRead(): Long {
        now += tick
        return now
    }
    val c: EditorController get() = s.c
    private var bmp: Bitmap? = null

    val width: Int get() = s.canvas.width
    val height: Int get() = s.canvas.height

    /** One frame (`onDraw`, what a vsync does); returns its time in ms. */
    fun draw(): Double {
        val v = s.canvas
        val b = bmp?.takeIf { it.width == v.width && it.height == v.height }
            ?: Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { bmp?.recycle(); bmp = it }
        val t0 = System.nanoTime()
        v.draw(Canvas(b))
        return (System.nanoTime() - t0) / 1e6
    }

    /** The last frame's pixels. */
    fun lastPixels(): IntArray {
        val b = bmp ?: throw AssertionError("no frame drawn")
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    /** A new frame's pixels. */
    fun pixels(): IntArray {
        draw()
        return lastPixels()
    }

    /** The last frame (to save as a PNG). */
    fun lastBitmap(): Bitmap = bmp ?: throw AssertionError("no frame drawn")

    /**
     * A frame while the finger moves: a session runs, the frame is drawn from its proxies, and
     * refinement doesn't start under the finger. Returns its time in ms without the overlays.
     */
    fun liveFrame(where: String): Double {
        assertTrue("$where: a live session runs", c.liveAdjust.isActive)
        val ms = drawWithoutOverlays()
        assertTrue("$where: the session still runs after the frame (no refinement while the finger moves)", c.liveAdjust.isActive)
        assertTrue("$where: the frame was drawn from proxies", c.liveAdjust.proxyBytes > 0)
        assertTrue("$where: the session's tiles wait for refinement", c.tiles.hasDirty)
        return ms
    }

    /** Frames until the session ends (the finger rested or lifted); returns how many. */
    fun drain(where: String): Int {
        now += c.liveAdjust.idleNanos
        var n = 0
        while (c.liveAdjust.isActive) {
            draw()
            if (++n > 400) throw AssertionError("$where: refinement never ends")
        }
        return n
    }

    /** The frame without any session: everything invalidated and drawn exactly. */
    fun exact(): IntArray {
        val policy = c.liveAdjust.policy
        c.liveAdjust.policy = LiveAdjust.Policy.EXACT
        c.invalidateDoc(null)
        val px = pixels()
        c.liveAdjust.policy = policy
        return px
    }

    /** I7: once refined, the canvas is bit for bit the canvas no session ever touched. */
    fun assertConverged(where: String): Int {
        val frames = drain(where)
        val refined = pixels()
        assertSamePixels("$where (I7: refined canvas = exact canvas)", exact(), refined, width)
        return frames
    }

    /** The document area on screen (what `CanvasView.onDraw` asks tiles and sessions for). */
    fun visibleDoc(): Rect {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        c.viewTransform.inverse.mapRect(r)
        return Rect().also { r.roundOut(it) }
    }

    /**
     * A frame's canvas part, timed (ms): what `CanvasView.onDraw` draws between the document's
     * outline and the overlays (the live session's frame from its proxies, or the display tiles
     * brought up to date and drawn), on the view's own bitmap. The overlays are left out: the
     * Masks tool's red tint is a ColorMatrix bitmap draw that host Skia runs about 300 times
     * slower than a plain one (about 1 s per screen on the JVM, one GPU pass on the phone), so a
     * whole frame's time on the JVM would be the tint's, and subtracting a second timing of it
     * leaves its noise (±100 ms) in the result.
     */
    fun drawWithoutOverlays(): Double {
        val v = s.canvas
        val b = bmp?.takeIf { it.width == v.width && it.height == v.height }
            ?: Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { bmp?.recycle(); bmp = it }
        val canvas = Canvas(b)
        val t = c.viewTransform
        val visible = visibleDoc()
        val smooth = t.zoom < 2.5f // CanvasView.SMOOTH_ZOOM_LIMIT
        val t0 = System.nanoTime()
        if (!c.liveAdjust.drawFrame(canvas, t.matrix, visible, smooth)) {
            c.tiles.update(c.compositor, visible)
            val save = canvas.save()
            canvas.concat(t.matrix)
            c.tiles.draw(canvas, visible, smooth)
            canvas.restoreToCount(save)
        }
        return (System.nanoTime() - t0) / 1e6
    }

    /**
     * What v1.5 drew per frame for the visible area: every display tile on screen composited by
     * the v1.5 Skia stage (`directWrite = false`: saveLayer + DST_IN), from the current state.
     */
    fun v15StageMs(): Double {
        val tiles = c.tiles
        val visible = visibleDoc()
        val tile = BitmapUtils.createLayerBitmap(tiles.tileSize, tiles.tileSize)
        val tv = Canvas(tile)
        val tr = Rect()
        quiet()
        val t0 = System.nanoTime()
        for (idx in 0 until tiles.tileCount) {
            tiles.tileRect(idx % tiles.cols, idx / tiles.cols, tr)
            if (!Rect.intersects(tr, visible)) continue
            tv.save()
            tv.translate(-tr.left.toFloat(), -tr.top.toFloat())
            tv.clipRect(tr)
            tv.drawColor(0, PorterDuff.Mode.CLEAR)
            val target = CompositeTarget(tile, Matrix().apply { setTranslate(-tr.left.toFloat(), -tr.top.toFloat()) }, display = true, directWrite = false)
            c.compositor.drawDocument(tv, tr, target = target)
            tv.restore()
        }
        val ms = (System.nanoTime() - t0) / 1e6
        tile.recycle()
        return ms
    }

    /** An exact frame of the current state (no session; every tile re-rendered), without overlays. */
    fun exactFrameMs(): Double {
        val policy = c.liveAdjust.policy
        c.liveAdjust.policy = LiveAdjust.Policy.EXACT
        c.invalidateDoc(null)
        quiet()
        val ms = drawWithoutOverlays()
        c.liveAdjust.policy = policy
        return ms
    }

    /**
     * A collection before a measured frame or drag: in the full suite a fork runs 40 classes and
     * keeps their sandboxes, and a 20 MP stack leaves little of the 1.5 GB heap, so a collection
     * that falls inside a measured frame would be counted as frame time (the phone's heap holds
     * this document only).
     */
    fun quiet() {
        System.gc()
    }

    // ------------------------------------------------------------------ fingers

    /** One finger on the canvas from document point [from] to [to] in [moves] moves, [each] after every move. */
    fun canvasDrag(from: Pair<Float, Float>, to: Pair<Float, Float>, moves: Int = 8, each: (Int) -> Unit = {}) {
        val a = s.screen(from.first, from.second)
        val b = s.screen(to.first, to.second)
        s.touch.idle(300)
        s.touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, a.first, a.second))
        for (i in 1..moves) {
            s.touch.idle(16)
            s.touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, a.first + (b.first - a.first) * i / moves, a.second + (b.second - a.second) * i / moves))
            each(i)
        }
        s.touch.idle(16)
        s.touch.send(MotionEvent.ACTION_UP, Smoke.P(0, b.first, b.second))
        SmokeUi.settle(4)
    }

    /**
     * One finger on [slider] (a placed Material slider) from track fraction [from] to [to] in
     * [moves] moves, [each] after every move (the thumb's centre runs r .. w − r).
     */
    fun sliderDrag(slider: RobolectricUi.Element, from: Float, to: Float, moves: Int = 8, each: (Int) -> Unit = {}) {
        val b = slider.bounds
        val r = 10f * s.density
        val y = b.center.y
        fun x(f: Float) = b.left + r + f * (b.width - 2f * r)
        val t = Smoke.Touch(slider.window)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, x(from), y))
        for (i in 1..moves) {
            t.idle(16)
            t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, x(from + (to - from) * i / moves), y))
            each(i)
        }
        t.idle(16)
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, x(to), y))
        SmokeUi.settle(4)
    }

    // ------------------------------------------------------------------ chrome

    /** Picks [label] in the tool menu, scrolling it to the cell as a finger would. */
    fun tool(label: String) {
        click("Tools (current:")
        assertNotNull("the tool menu is open", s.tagged(ChromeTags.TOOL_MENU))
        fun cell() = SmokeUi.find(label, exact = true)?.node?.let { n ->
            var k: androidx.compose.ui.semantics.SemanticsNode? = n
            while (k != null && k.config.getOrNull(SemanticsActions.OnClick) == null) k = k.parent
            k
        }
        fun inside() = cell()?.let { n -> n.boundsInWindow.height >= n.size.height - 1f && n.boundsInWindow.width >= n.size.width - 1f } == true
        if (!inside()) {
            val n = SmokeUi.find(label, exact = true)?.node ?: throw AssertionError("\"$label\" is not in the tool menu")
            var k: androidx.compose.ui.semantics.SemanticsNode? = n
            while (k != null && k.config.getOrNull(SemanticsActions.ScrollBy) == null) k = k.parent
            val scroll = requireNotNull(k?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is clipped and the menu does not scroll" }
            var tries = 0
            while (!inside()) {
                scroll.invoke(0f, 60f * s.density)
                SmokeUi.settle(2)
                if (++tries > 30) throw AssertionError("\"$label\" never scrolled into the tool menu")
            }
        }
        click(label, exact = true)
        assertNull("a pick closes the menu", s.tagged(ChromeTags.TOOL_MENU))
    }

    /** The placed slider right under the text [label] (a LabeledSlider's own row). */
    fun sliderUnder(label: String): RobolectricUi.Element {
        val text = SmokeUi.find(label, exact = true)?.bounds ?: throw AssertionError("no \"$label\" on screen: ${SmokeUi.shown().take(80)}")
        return RobolectricUi.elements()
            .filter { it.node.layoutInfo.isPlaced && it.node.config.contains(SemanticsActions.SetProgress) && it.bounds.top >= text.top - 1f && it.bounds.width > 0f }
            .minByOrNull { it.bounds.top } ?: throw AssertionError("no slider under \"$label\"")
    }

    fun steps(): Int = c.undoManager.undoCount
}

/** [actual] equals [expected] pixel for pixel; otherwise says how many differ and where the first is. */
internal fun assertSamePixels(where: String, expected: IntArray, actual: IntArray, width: Int) {
    if (expected.size != actual.size) throw AssertionError("$where: sizes ${expected.size} vs ${actual.size}")
    var n = 0
    var first = -1
    var worst = 0
    for (i in expected.indices) {
        val e = expected[i]
        val a = actual[i]
        if (e != a) {
            if (first < 0) first = i
            n++
            for (sh in 0..24 step 8) worst = maxOf(worst, kotlin.math.abs((e ushr sh and 0xFF) - (a ushr sh and 0xFF)))
        }
    }
    if (n > 0) {
        throw AssertionError(
            "$where: $n of ${expected.size} pixels differ (worst channel ${worst}); first at (${first % width}, ${first / width}): " +
                "expected #${Integer.toHexString(expected[first])}, was #${Integer.toHexString(actual[first])}",
        )
    }
}

/** Mean channel difference of two frames (how far a proxy frame is from the exact one). */
internal fun meanDiff(a: IntArray, b: IntArray): Double {
    var s = 0L
    for (i in a.indices) for (sh in 0..16 step 8) s += kotlin.math.abs((a[i] shr sh and 0xFF) - (b[i] shr sh and 0xFF))
    return s / (a.size * 3.0)
}

internal fun median(v: List<Double>): Double = v.sorted()[v.size / 2]
