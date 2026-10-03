package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.io.File
import java.io.FileOutputStream

/**
 * v1.6 final QA (text): the user's own moves on the full editor — finger touches on the canvas
 * (document coordinates turned into window pixels), the tool menu, sliders, typed values — by the
 * labels the user sees (I10).
 */
internal class Qa16Ui(val s: ChromeScreen) {
    val c: EditorController get() = s.c

    /** A finger stroke through document points. */
    fun stroke(vararg doc: Pair<Float, Float>) {
        s.touch.idle(300)
        s.touch.stroke(*doc.map { s.screen(it.first, it.second) }.toTypedArray())
        settle(4)
    }

    /** A finger stroke through window pixels. */
    fun strokeWindow(vararg px: Pair<Float, Float>) {
        s.touch.idle(300)
        s.touch.stroke(*px)
        settle(4)
    }

    /** A finger tap at document point ([x], [y]). */
    fun tap(x: Float, y: Float) {
        s.touch.idle(300)
        val (sx, sy) = s.screen(x, y)
        s.touch.tap(sx, sy)
        settle(4)
    }

    /** A finger tap at window pixel ([x], [y]). */
    fun tapWindow(x: Float, y: Float) {
        s.touch.idle(300)
        s.touch.tap(x, y)
        settle(4)
    }

    /** Two fingers tap the canvas: undo. */
    fun twoFingerUndo() {
        s.touch.idle(400)
        val a = s.screen(c.doc.width * 0.3f, c.doc.height * 0.5f)
        val b = s.screen(c.doc.width * 0.6f, c.doc.height * 0.5f)
        s.touch.twoFingerTap(a, b)
        settle(4)
    }

    /** Three fingers tap the canvas: redo. */
    fun threeFingerRedo() {
        s.touch.idle(400)
        val a = s.screen(c.doc.width * 0.25f, c.doc.height * 0.5f)
        val b = s.screen(c.doc.width * 0.5f, c.doc.height * 0.5f)
        val d = s.screen(c.doc.width * 0.75f, c.doc.height * 0.5f)
        s.touch.threeFingerTap(a, b, d)
        settle(4)
    }

    /** Picks [label] in the ibisPaint tool menu, scrolling the menu to it as a finger would. */
    fun tool(label: String) {
        click("Tools (current:")
        assertNotNull("the tool menu is open", s.tagged(ChromeTags.TOOL_MENU))
        scrollMenuTo(label)
        click(label, exact = true)
        assertNull("a pick closes the menu", s.tagged(ChromeTags.TOOL_MENU))
    }

    private fun cell(label: String): SemanticsNode? {
        var n = SmokeUi.find(label, exact = true)?.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n
    }

    private fun scrollMenuTo(label: String) {
        fun inside() = cell(label)?.let { n -> n.boundsInWindow.height >= n.size.height - 1f && n.boundsInWindow.width >= n.size.width - 1f } == true
        if (inside()) return
        val node = SmokeUi.find(label, exact = true)?.node ?: throw AssertionError("\"$label\" is not in the tool menu")
        var n: SemanticsNode? = node
        while (n != null && n.config.getOrNull(SemanticsActions.ScrollBy) == null) n = n.parent
        val scroll = requireNotNull(n?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is clipped and the menu does not scroll" }
        repeat(30) {
            if (inside()) return
            scroll.invoke(0f, 60f * s.density)
            settle(2)
        }
        throw AssertionError("\"$label\" never scrolled into the tool menu")
    }

    /**
     * Whether the control labelled [label] is wholly visible now: not cut by a scrolling parent
     * (its visible bounds are its size) and inside the screen, at least [minDp] dp both ways.
     */
    fun wholly(label: String, minDp: Float = 40f): Boolean {
        val n = cell(label) ?: return false
        val b = n.boundsInWindow
        val r = s.root
        return b.width >= n.size.width - 1f && b.height >= n.size.height - 1f &&
            b.left >= r.left - 0.5f && b.right <= r.right + 0.5f && b.top >= r.top - 0.5f && b.bottom <= r.bottom + 0.5f &&
            n.size.width >= minDp * s.density - 1f && n.size.height >= minDp * s.density - 1f
    }

    /**
     * Brings the control labelled [label] wholly into view the way a finger does: by scrolling the
     * strip or sheet it sits in (sideways or up and down), 60 dp at a time.
     */
    fun reach(label: String, minDp: Float = 40f) {
        repeat(40) {
            if (wholly(label, minDp)) return
            val n = cell(label) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(60)}")
            val at = n.positionInWindow
            val step = 60f * s.density
            // Sideways: the nearest strip that scrolls sideways; up and down: the nearest sheet that does.
            val across = scroller(n, SemanticsProperties.HorizontalScrollAxisRange)
            val down = scroller(n, SemanticsProperties.VerticalScrollAxisRange)
            val dx = across?.boundsInWindow?.let { v ->
                when {
                    at.x + n.size.width > minOf(v.right, s.root.right) -> step
                    at.x < maxOf(v.left, s.root.left) -> -step
                    else -> 0f
                }
            } ?: 0f
            val dy = down?.boundsInWindow?.let { v ->
                when {
                    at.y + n.size.height > minOf(v.bottom, s.root.bottom) -> step
                    at.y < maxOf(v.top, s.root.top) -> -step
                    else -> 0f
                }
            } ?: 0f
            if (dx == 0f && dy == 0f) {
                throw AssertionError("\"$label\" cannot be brought wholly into view (at least $minDp dp): ${n.boundsInWindow} of ${n.size}, sideways ${across?.boundsInWindow}, down ${down?.boundsInWindow}")
            }
            if (dx != 0f) requireNotNull(across!!.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(dx, 0f)
            if (dy != 0f) requireNotNull(down!!.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, dy)
            settle(2)
        }
        throw AssertionError("\"$label\" never came wholly into view")
    }

    /** The nearest ancestor of [n] that scrolls along [axis] (and takes ScrollBy). */
    private fun scroller(n: SemanticsNode, axis: androidx.compose.ui.semantics.SemanticsPropertyKey<androidx.compose.ui.semantics.ScrollAxisRange>): SemanticsNode? {
        var p: SemanticsNode? = n.parent
        while (p != null) {
            val range = p.config.getOrNull(axis)
            if (range != null && range.maxValue() > 0f && p.config.getOrNull(SemanticsActions.ScrollBy) != null) return p
            p = p.parent
        }
        return null
    }

    /** The slider whose description contains [name]. */
    fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.layoutInfo.isPlaced && e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(name) } == true
    }

    fun setSlider(name: String, v: Float) {
        requireNotNull(slider(name).node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(v)
        settle(4)
    }

    /** The text editor's Size in pixels: the unit menu ("Change unit" › Pixels), then the typed value. */
    fun textSizePx(px: Int) {
        click("Change unit", exact = true)
        click("${LengthUnit.PX.label} (${LengthUnit.PX.short})", exact = true)
        SmokeUi.typeAndDone("Size", px.toString())
    }

    /** Back key on the topmost window. */
    fun backKey() {
        SmokeUi.windows().last().let { w ->
            w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        settle()
    }
}

/** Renders for the lead (outside the repository; written only where the QA tools folder exists). */
internal object Shots {
    private val DIR = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v16-qa-shots")

    fun save(b: Bitmap, name: String): File? {
        if (!DIR.parentFile!!.isDirectory) return null
        DIR.mkdirs()
        val f = File(DIR, name)
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}

/** The text a (text) layer stores. */
internal fun Layer.item(): TextItem = TextCodec.decode(textData) ?: throw AssertionError("$name stores no text")

/**
 * The letters of light text on a dark flattened picture, measured to a fraction of a pixel:
 * each letter is a run of inked columns (coverage = how far a pixel's lightness went from the
 * background to the text colour); [Letter.stem] is the column with the most ink — the full
 * height of a flat-topped capital (E, L, T, N, H) — measured by its coverage.
 */
internal object InkLetters {
    class Letter(
        val left: Int, val right: Int,
        /** Rows with coverage ≥ ½. */
        val top: Int, val bottom: Int,
        /** Height (px) and centre (y) of the inkiest column, from its coverage. */
        val stemHeight: Double, val stemCentre: Double,
    ) {
        val height: Int get() = bottom - top + 1
        val centre: Double get() = (top + bottom + 1) / 2.0
        val stemTop: Double get() = stemCentre - stemHeight / 2
        val stemBottom: Double get() = stemCentre + stemHeight / 2
        override fun toString() = "x $left–$right rows $top–$bottom (h $height) stem %.2f @ %.2f".format(stemHeight, stemCentre)
    }

    private fun lum(c: Int) = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0

    fun coverage(b: Bitmap, bg: Int, fg: Int): Array<DoubleArray> {
        val l0 = lum(bg)
        val l1 = lum(fg)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Array(b.height) { y -> DoubleArray(b.width) { x -> ((lum(px[y * b.width + x]) - l0) / (l1 - l0)).coerceIn(0.0, 1.0) } }
    }

    fun letters(b: Bitmap, bg: Int, fg: Int, minCover: Double = 0.25): List<Letter> {
        val cov = coverage(b, bg, fg)
        val w = b.width
        val h = b.height
        val inked = BooleanArray(w) { x -> (0 until h).any { y -> cov[y][x] >= minCover } }
        val out = ArrayList<Letter>()
        var x = 0
        while (x < w) {
            if (!inked[x]) { x++; continue }
            val x0 = x
            while (x < w && inked[x]) x++
            var top = h
            var bottom = -1
            var best = -1.0
            var bestCentre = 0.0
            for (cx in x0 until x) {
                var sum = 0.0
                var moment = 0.0
                for (y in 0 until h) {
                    val v = cov[y][cx]
                    if (v >= 0.5) { top = minOf(top, y); bottom = maxOf(bottom, y) }
                    sum += v
                    moment += v * (y + 0.5)
                }
                if (sum > best) { best = sum; bestCentre = moment / sum }
            }
            out += Letter(x0, x - 1, top, bottom, best, bestCentre)
        }
        return out
    }

    /** The lines of text (bands of inked rows, top first), each with its [letters] (rows in the whole picture). */
    fun lines(b: Bitmap, bg: Int, fg: Int, minCover: Double = 0.25): List<List<Letter>> {
        val cov = coverage(b, bg, fg)
        val inked = BooleanArray(b.height) { y -> cov[y].any { it >= minCover } }
        val out = ArrayList<List<Letter>>()
        var y = 0
        while (y < b.height) {
            if (!inked[y]) { y++; continue }
            val y0 = y
            while (y < b.height && inked[y]) y++
            val band = Bitmap.createBitmap(b, 0, y0, b.width, y - y0)
            out += letters(band, bg, fg, minCover).map { Letter(it.left, it.right, it.top + y0, it.bottom + y0, it.stemHeight, it.stemCentre + y0) }
        }
        return out
    }

    /** Letters stacked top to bottom (vertical text): runs of inked rows with their columns (coverage ≥ ½). */
    fun stacked(b: Bitmap, bg: Int, fg: Int, minCover: Double = 0.25): List<android.graphics.Rect> {
        val cov = coverage(b, bg, fg)
        val inked = BooleanArray(b.height) { y -> cov[y].any { it >= minCover } }
        val out = ArrayList<android.graphics.Rect>()
        var y = 0
        while (y < b.height) {
            if (!inked[y]) { y++; continue }
            val y0 = y
            while (y < b.height && inked[y]) y++
            var left = b.width
            var right = -1
            for (cy in y0 until y) for (x in 0 until b.width) if (cov[cy][x] >= 0.5) { left = minOf(left, x); right = maxOf(right, x) }
            out += android.graphics.Rect(left, y0, right + 1, y)
        }
        return out
    }
}
