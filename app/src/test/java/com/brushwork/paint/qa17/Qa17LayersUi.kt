package com.brushwork.paint.qa17

import android.view.MotionEvent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.sign

/**
 * v1.7 final QA, layers cluster: the user's moves in the full editor that the folder, saved
 * selection and symmetry classes share — the layer window (opened, a row brought into view and
 * tapped, a finger on a row's ≡ handle), the history (exactly one step, or none and the picture
 * unchanged) and the captions the editor hands to its snackbar. [release] it at the end of a
 * section (the caption log observes every snapshot change).
 */
internal class Qa17LayersUi(val s: ChromeScreen) {
    val c: EditorController get() = s.c
    val ui = Qa16Ui(s)
    private val captions = Captions(s.c)

    fun release() = captions.dispose()

    // ================================================================== history and picture

    fun steps(): Int = c.undoManager.undoCount

    /** [block] adds exactly one undo step named [label]. */
    fun oneStep(what: String, label: String, block: () -> Unit) {
        val before = steps()
        block()
        settle()
        assertEquals("$what: one undo step", before + 1, steps())
        assertEquals("$what: the step's name", label, c.undoManager.undoLabel)
    }

    /** [block] adds no step and leaves the picture as it was. */
    fun noStep(what: String, block: () -> Unit) {
        val before = steps()
        val picture = pixels()
        block()
        settle()
        assertEquals("$what: no step", before, steps())
        assertArrayEquals("$what: the picture is unchanged", picture, pixels())
    }

    /** The flattened picture's pixels. */
    fun pixels(): IntArray = Qa17LayersShots.flat(c).let { b ->
        try { IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) } } finally { b.recycle() }
    }

    // ================================================================== captions

    /** The captions handed to the snackbar since the last call, in order. */
    fun captions(): List<String> = captions.take()

    /**
     * [block] is refused: no step, the picture unchanged, and exactly the caption [text] handed to
     * the snackbar (once), which shows it.
     */
    fun refused(what: String, text: String, block: () -> Unit) {
        captions.take()
        noStep(what, block)
        settle()
        assertEquals("$what: the caption", listOf(text), captions.take())
        assertTrue("$what: \"$text\" is shown; shown: ${SmokeUi.shown().take(60)}", has(text, exact = true))
    }

    /**
     * Every caption the editor hands to its snackbar, in order: the editor clears
     * [EditorController.message] as soon as its snackbar takes it, so it is read when the change
     * is applied (before the screen recomposes), each toast once.
     */
    private class Captions(private val c: EditorController) {
        private val seen = mutableListOf<String>()
        private var last: String? = null
        private val handle = Snapshot.registerApplyObserver { _, _ ->
            val m = c.message
            if (m != null && m != last) seen += m
            last = m
        }

        fun take(): List<String> = seen.toList().also { seen.clear() }

        fun dispose() = handle.dispose()
    }

    // ================================================================== the layer window

    fun row(l: Layer): String = LayerLabels.selectRow(c.doc.indexOf(l) + 1)

    fun openLayers() {
        if (s.tagged(ChromeTags.LAYER_WINDOW) != null) return
        click("Open layers (active layer")
        Smoke.pump(600)
        settle()
        assertNotNull("the layer window is open", s.tagged(ChromeTags.LAYER_WINDOW))
    }

    fun closeLayers() {
        if (s.tagged(ChromeTags.LAYER_WINDOW) == null) return
        click(LayerLabels.CLOSE, exact = true)
        Smoke.pump(400)
        settle()
    }

    /** Selects [l]'s row with a tap (the list first scrolled to it, as a finger would). */
    fun pick(l: Layer) {
        openLayers()
        Smoke.pump(600)
        reachRow(l)
        click(row(l), exact = true)
        Smoke.pump(600)
        settle()
        assertSame("${l.name} is active", l, c.activeLayer)
    }

    /**
     * Brings [l]'s row into the layer list as a finger does: the list scrolled toward it (60 dp
     * at a time) until the row is there, then wholly into view.
     */
    fun reachRow(l: Layer) {
        openLayers()
        val label = row(l)
        val n = c.doc.indexOf(l) + 1
        repeat(40) {
            settle(2)
            if (SmokeUi.find(label, exact = true) != null) {
                ui.reach(label, 40f)
                return
            }
            val shown = SmokeUi.shown().mapNotNull { ROW.find(it)?.groupValues?.get(1)?.toInt() }
            if (shown.isEmpty()) throw AssertionError("no layer rows; shown: ${SmokeUi.shown().take(60)}")
            var p: SemanticsNode? = SmokeUi.find(LayerLabels.selectRow(shown.first()), exact = true)!!.node
            while (p != null && p.config.getOrNull(SemanticsActions.ScrollBy) == null) p = p.parent
            val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "the layer list does not scroll" }
            // The top row is the highest layer: lower layers are further down.
            scroll.invoke(0f, (if (n < shown.min()) 60f else -60f) * s.density)
        }
        throw AssertionError("\"$label\" never came into the layer list")
    }

    /** A finger on [layer]'s ≡ handle: [dx] dp across (a swipe) or [dy] dp down (a drag), in 8 dp moves. */
    fun handle(layer: Layer, dx: Float = 0f, dy: Float = 0f) {
        reachRow(layer)
        // The rows' placement animations end first (the list finds the row under the finger by its
        // layout): the handle stays put over two settles.
        val label = LayerLabels.reorder(c.doc.indexOf(layer) + 1)
        var e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"")
        for (i in 0 until 20) {
            Smoke.pump(100)
            settle()
            val now = SmokeUi.find(label, exact = true)!!
            if (now.bounds == e.bounds) break
            e = now
        }
        val hb = e.bounds
        val touch = Smoke.Touch(e.window)
        val n = (maxOf(abs(dx), abs(dy)) / 8f).toInt()
        val sx = sign(dx) * 8f * s.density
        val sy = sign(dy) * 8f * s.density
        touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, hb.center.x, hb.center.y))
        for (i in 1..n) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, hb.center.x + i * sx, hb.center.y + i * sy))
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, Smoke.P(0, hb.center.x + n * sx, hb.center.y + n * sy))
        settle()
        Smoke.pump(600)
        settle()
    }

    private companion object {
        val ROW = Regex("^Select layer (\\d+)$")
    }
}
