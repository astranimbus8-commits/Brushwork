package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Renders of the v1.8 curves QA for the lead (outside the repository; written only where the QA tools folder exists). */
internal object Qa18CurvesShots {
    private val DIR = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

    fun save(b: Bitmap, name: String): File? {
        // On other hosts (CI on Linux) the Windows path has no parent: no renders there.
        if (DIR.parentFile?.isDirectory != true) return null
        DIR.mkdirs()
        val f = File(DIR, name)
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}

/**
 * v1.8 final QA (items 6 and 7): the editor on the phone for the curves and cog flows, driven as
 * the user does it: real touches on the canvas and on the controls (found by the names a screen
 * reader gives them), slider drags with a finger, the Back key; the documents are at most 512 px.
 */
internal class Qa18CurvesUi(private val h: ChromeHarness, private val widthDp: Float) {
    lateinit var s: ChromeScreen
    lateinit var ui: Qa16Ui
    val c: EditorController get() = s.c

    companion object {
        /** How long a render or a commit may take to land (a UI wait's timeout; no time is asserted). */
        const val WAIT_MS = 30_000L

        /** A white page and an empty vector layer above it, active. */
        fun vectorDoc(w: Int = 400, h: Int = 300): Document = Smoke.document(w, h, layers = 1, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = d.layers.lastIndex
        }

        /** A white page and an empty raster layer above it, active. */
        fun rasterDoc(w: Int = 400, h: Int = 300): Document = Smoke.document(w, h, layers = 2, whiteBottom = true)
    }

    fun editor(doc: Document): ChromeScreen {
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the phone is ${widthDp.roundToInt()} dp wide", widthDp, s.widthDp, 1f)
        return s
    }

    fun steps(): Int = c.undoManager.undoCount

    /** Picks [label] in the tool menu, scrolling it as a finger would. */
    fun tool(label: String) {
        ui.tool(label)
        settle()
    }

    /**
     * A finger tap on the control known as [label], in whatever window shows it (the editor, a
     * dropdown, a sheet): brought wholly into view first (the strip's More row opened, a strip or
     * sheet scrolled), then touched at the centre of what shows of it.
     */
    fun tap(label: String) {
        val first = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(80)}")
        // The editor's own window: its strip may need a scroll; a sheet scrolls its column.
        val find = { SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(80)}") }
        if (first.window === s.activity.window.decorView) {
            val at = first.node.positionInWindow
            ui.reach(label, 24f)
            if (find().node.positionInWindow != at) standStill(find)
        }
        val e = visible(find)
        var n: androidx.compose.ui.semantics.SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null && n.config.getOrNull(SemanticsActions.SetProgress) == null) n = n.parent
        val b = (n ?: e.node).boundsInWindow
        assertTrue("\"$label\" shows something: $b", b.width > 0f && b.height > 0f)
        RobolectricUi.tap(e.window, b.center.x, b.center.y)
        settle()
    }

    /** A finger tap on the canvas at document point [p]. */
    fun tapDoc(p: Vec2) = ui.tap(p.x, p.y)

    /** A finger stroke on the canvas through document points. */
    fun strokeDoc(vararg p: Vec2) = ui.stroke(*p.map { it.x to it.y }.toTypedArray())

    /** The top row's Undo, tapped. */
    fun undo() = Finger.tap(s, "Undo")

    /** The top row's Redo, tapped. */
    fun redo() = Finger.tap(s, "Redo")

    /** The floating ✓ ([label], "Apply <tool> edit") tapped; waits for what it starts. */
    fun applyEdit(label: String) {
        Finger.tap(s, label)
        assertTrue(
            "$label: done",
            Smoke.pumpUntil(WAIT_MS) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending },
        )
        settle()
    }

    /** Every render on its way has landed. */
    fun settleRenders(what: String) {
        assertTrue("$what: renders landed", Smoke.pumpUntil(WAIT_MS) { settle(1); !c.vectors.isRendering && !c.arrayRenders.isPending && c.busyMessage == null })
        settle()
    }

    /**
     * The slider under the text [label] (the next node after the last one showing it that sets a
     * progress), or the slider named "[label] slider" (a length field's own slider).
     */
    fun sliderUnder(label: String): RobolectricUi.Element {
        val all = RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }
        all.lastOrNull { e ->
            e.node.config.getOrNull(SemanticsActions.SetProgress) != null &&
                e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("$label slider") == true
        }?.let { return it }
        val at = all.indexOfLast { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        assertTrue("\"$label\" shows; shown: ${SmokeUi.shown().take(60)}", at >= 0)
        return all.drop(at + 1).firstOrNull { it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
            ?: throw AssertionError("no slider under \"$label\"")
    }

    /**
     * Waits until what [find] returns stands still: a scroll by its semantics is animated (a
     * spring), and a touch while it still moves only stops it, as a finger on a moving list does.
     */
    fun standStill(find: () -> RobolectricUi.Element) {
        var last = find().node.positionInWindow
        var still = 0
        var waited = 0
        while (still < 3 && waited < 2000) {
            Smoke.pump(100)
            waited += 100
            val now = find().node.positionInWindow
            if (now == last) still++ else still = 0
            last = now
        }
        settle()
    }

    /**
     * The element [find] returns, after scrolling each scrolling ancestor (a sheet's column, a
     * chip row) until it lies wholly inside it and inside its window, as a finger scrolls before it touches.
     */
    fun visible(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(16) {
            val e = find()
            val pos = e.node.positionInWindow
            val l = pos.x; val t = pos.y; val r = l + e.node.size.width; val b = t + e.node.size.height
            val scrollers = generateSequence(e.node.parent) { it.parent }.filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.toList().asReversed()
            for (n in scrollers) {
                val p = n.positionInWindow
                val vl = p.x; val vr = vl + n.size.width
                val vt = maxOf(p.y, 0f); val vb = minOf(p.y + n.size.height, e.window.height.toFloat())
                val across = n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                val delta = if (across) {
                    when { r > vr -> r - vr + 8f; l < vl -> l - vl - 8f; else -> 0f }
                } else {
                    when { b > vb -> b - vb + 8f; t < vt -> t - vt - 8f; else -> 0f }
                }
                if (delta != 0f) {
                    val scroll = requireNotNull(n.config[SemanticsActions.ScrollBy].action)
                    if (across) scroll(delta, 0f) else scroll(0f, delta)
                    standStill(find)
                    return@repeat
                }
            }
            return e
        }
        return find()
    }

    /**
     * A finger put on the thumb of the slider under [label] (where its value shows) and dragged
     * sideways by [by] × the slider's width: right for more, left when the value is past [downPast]
     * of its range (so settings carried from the last curve stay in the middle).
     */
    fun dragSlider(label: String, by: Float, downPast: Float = 2f) {
        val e = visible { sliderUnder(label) }
        assertTrue("the \"$label\" slider is on screen: ${e.bounds} in ${e.window.height} px", e.bounds.top >= 0f && e.bounds.bottom <= e.window.height + 0.5f)
        val info = e.node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) ?: throw AssertionError("\"$label\": no range")
        val span = info.range.endInclusive - info.range.start
        val at = if (span > 0f) (info.current - info.range.start) / span else 0f
        val b = e.bounds
        val y = b.center.y
        // (Never on the slider's very edge: the finger lands on its track.)
        val x0 = (b.left + b.width * at).coerceIn(b.left + b.width * 0.03f, b.right - b.width * 0.03f)
        val d = if (at > downPast) -by else by
        fingerDrag(e.window, x0 to y, (x0 + b.width * d).coerceIn(b.left + 1f, b.right - 1f) to y)
    }

    /** A finger drag in [v] from [a] to [b] (window px) at a finger's pace: a move every 16 ms, frames in between. */
    fun fingerDrag(v: android.view.View, a: Pair<Float, Float>, b: Pair<Float, Float>, moves: Int = 16) {
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        fun send(down: Long, action: Int, x: Float, y: Float) {
            val ev = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            v.dispatchTouchEvent(ev)
            ev.recycle()
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        send(t0, android.view.MotionEvent.ACTION_DOWN, a.first, a.second)
        for (i in 1..moves) {
            looper.idleFor(java.time.Duration.ofMillis(16))
            send(t0, android.view.MotionEvent.ACTION_MOVE, a.first + (b.first - a.first) * i / moves, a.second + (b.second - a.second) * i / moves)
        }
        looper.idleFor(java.time.Duration.ofMillis(16))
        send(t0, android.view.MotionEvent.ACTION_UP, b.first, b.second)
        settle()
    }

    /** A point on [p]'s own line (its source, not a copy), at [at] of its flattened points. */
    fun onLine(p: VPath, at: Float = 0.5f): Vec2 {
        val pts = VectorOps.toVectorPath(ObjectCopies.source(p) as VPath).flatten(0.25f).first().points
        return pts[((pts.size - 1) * at).roundToInt()]
    }

    /** What the screen shows now (the canvas with its overlays and the chrome over it). */
    fun capture(): Bitmap {
        val v = s.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    /** Pixels of [b] in the window rectangle of document [l, t, r, b] that differ from [other]. */
    fun changed(a: Bitmap, other: Bitmap, l: Float, t: Float, r: Float, bottom: Float): Int {
        val (x0, y0) = s.screen(l, t)
        val (x1, y1) = s.screen(r, bottom)
        var n = 0
        for (y in y0.toInt().coerceAtLeast(0) until y1.toInt().coerceAtMost(a.height)) {
            for (x in x0.toInt().coerceAtLeast(0) until x1.toInt().coerceAtMost(a.width)) if (a.getPixel(x, y) != other.getPixel(x, y)) n++
        }
        return n
    }

    /** The top (window px) of the open sheet panel, or the window's height without one. */
    fun sheetTop(): Float = RobolectricUi.elements().filter { e ->
        e.window === s.activity.window.decorView && e.node.layoutInfo.isPlaced && e.node.config.getOrNull(com.brushwork.paint.ui.common.BwSheetTitleKey) != null
    }.minOfOrNull { it.bounds.top } ?: s.activity.window.decorView.height.toFloat()

    /** [changed], above the open sheet only (its own sliders and values left out). */
    fun changedAboveSheet(a: Bitmap, other: Bitmap, l: Float, t: Float, r: Float, bottom: Float): Int {
        val top = sheetTop()
        val (_, y1) = s.screen(l, bottom)
        val cut = if (y1 > top) s.doc(0f, top).second else bottom
        return changed(a, other, l, t, r, minOf(bottom, cut))
    }

    /** Pixels of [layer] with any alpha in [l, r) × [t, b). */
    fun ink(layer: Layer, l: Int = 0, t: Int = 0, r: Int = layer.bitmap.width, b: Int = layer.bitmap.height): Int {
        val bmp = layer.bitmap
        var n = 0
        for (y in t.coerceAtLeast(0) until b.coerceAtMost(bmp.height)) {
            for (x in l.coerceAtLeast(0) until r.coerceAtMost(bmp.width)) if (bmp.getPixel(x, y) ushr 24 > 0) n++
        }
        return n
    }

    /** Whether [layer] shows anything at document point [p] (within [r] px). */
    fun inkAt(layer: Layer, p: Vec2, r: Int = 2): Boolean {
        val x = p.x.roundToInt()
        val y = p.y.roundToInt()
        return ink(layer, x - r, y - r, x + r + 1, y + r + 1) > 0
    }

    /** [layer] on white, saved for the lead as [name]. */
    fun shot(layer: Layer, name: String) {
        val b = BitmapUtils.createLayerBitmap(layer.bitmap.width, layer.bitmap.height)
        val cv = Canvas(b)
        cv.drawColor(Color.WHITE)
        cv.drawBitmap(layer.bitmap, 0f, 0f, null)
        Qa18CurvesShots.save(b, name)
    }
}
