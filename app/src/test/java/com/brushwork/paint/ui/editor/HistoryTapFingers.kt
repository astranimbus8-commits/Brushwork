package com.brushwork.paint.ui.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewConfiguration

/**
 * Real two- and three-finger taps on the editor (v1.7 item 10) for the history-tap tests over the
 * v1.7 controls: points in dp of the editor's root, the controls found by their labels, a known
 * last step ([seed]) whose name the tap's feedback must carry.
 */
internal class HistoryTapFingers(private val s: ChromeScreen) {

    /** Window px of editor dp [p]. */
    fun px(p: Pair<Float, Float>): Pair<Float, Float> = Finger.px(s, p.first, p.second)

    /** The point at fractions ([fx], [fy]) of this box. */
    fun Rect.at(fx: Float, fy: Float = 0.5f): Pair<Float, Float> = (left + width * fx) to (top + height * fy)

    fun region(tag: String): Rect = requireNotNull(s.tagged(tag)) { "no $tag on screen; shown: ${SmokeUi.shown().take(60)}" }

    /** What shows (dp) of the clickable labelled [label]. */
    fun control(label: String, region: Rect? = null): Rect {
        val r = requireNotNull(Finger.control(s, label, region)) { "no \"$label\"; shown: ${SmokeUi.shown().take(80)}" }
        assertTrue("\"$label\" shows something: $r", r.width > 4f && r.height > 4f)
        return r
    }

    /** The placed slider described exactly [name] (its SetProgress action). */
    fun slider(name: String): SemanticsNode = s.placed().lastOrNull { e ->
        e.node.config.contains(SemanticsActions.SetProgress) && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }?.node ?: throw AssertionError("no slider \"$name\"; shown: ${SmokeUi.shown().take(80)}")

    /** What shows (dp) of slider [name], which must show whole. */
    fun sliderBox(name: String): Rect {
        val n = slider(name)
        val b = n.boundsInWindow
        assertTrue("slider \"$name\" shows whole: $b of ${n.size}", b.width >= n.size.width - 1f && b.height >= n.size.height - 1f)
        return s.dp(b)
    }

    fun sliderValue(name: String): Float = slider(name).config[SemanticsProperties.ProgressBarRangeInfo].current

    /** Scrolls the sheet slider [name] sits in until it shows whole (60 dp at a time), and lets the scroll end. */
    fun reachSlider(name: String) {
        repeat(30) {
            val n = slider(name)
            val b = n.boundsInWindow
            val r = s.root
            val top = n.positionInWindow.y
            if (b.height >= n.size.height - 1f && top >= r.top && top + n.size.height <= r.bottom) {
                settle()
                return
            }
            var p: SemanticsNode? = n.parent
            while (p != null && p.config.getOrNull(SemanticsActions.ScrollBy) == null) p = p.parent
            val sheet = p ?: throw AssertionError("slider \"$name\" is cut and nothing scrolls it: $b")
            val dy = if (top + n.size.height > minOf(sheet.boundsInWindow.bottom, r.bottom)) 60f else -60f
            requireNotNull(sheet.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, dy * s.density)
            settle(2)
        }
        throw AssertionError("slider \"$name\" never showed whole")
    }

    /** What shows (dp) of the text [text] inside [region] (a sheet's title, a row's name). */
    fun textIn(text: String, region: Rect): Rect = s.placed()
        .filter { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        .map { s.dp(it.node.boundsInWindow) }
        .lastOrNull { region.contains(it.center) && it.width > 0f }
        ?: throw AssertionError("no \"$text\" inside $region; shown: ${SmokeUi.shown().take(60)}")

    /**
     * Scrolls [label] wholly into view ([Qa16Ui.reach]), then lets the scroll end. A semantics
     * scroll animates: a control still gliding moves away from where it was measured, and a
     * finger on a container still scrolling stops the scroll instead of reaching the control.
     */
    fun reach(label: String, minDp: Float = 40f) {
        Qa16Ui(s).reach(label, minDp)
        settle()
    }

    /** Scrolls the options strip to the control described [description] ([QaCurves.scrollStripTo]), then lets the scroll end (see [reach]). */
    fun reachInStrip(description: String) {
        QaCurves.scrollStripTo(s, description)
        settle()
    }

    // The gestures below go down where the caller measured, at once: no time passes between the
    // measure and the first down (see [reach]). Each ends with a settle, which keeps one
    // gesture's taps apart from the next one's.

    fun twoFingers(a: Pair<Float, Float>, b: Pair<Float, Float>) {
        s.touch.twoFingerTap(px(a), px(b))
        settle()
    }

    fun threeFingers(a: Pair<Float, Float>, b: Pair<Float, Float>, d: Pair<Float, Float>) {
        s.touch.threeFingerTap(px(a), px(b), px(d))
        settle()
    }

    /** Sets the activity's touch slop to a phone's 8 dp (Robolectric's shadow keeps 16 dp). */
    fun phoneTouchSlop() {
        val shadow = Shadow.extract<ShadowViewConfiguration>(ViewConfiguration.get(s.activity))
        ShadowViewConfiguration::class.java.getDeclaredField("touchSlop").apply { isAccessible = true }.setInt(shadow, (8 * s.density).toInt())
        assertEquals((8 * s.density).toInt(), ViewConfiguration.get(s.activity).scaledTouchSlop)
    }

    /**
     * The first finger goes down at [first] (editor dp) and drags 10 dp to the right (under the
     * 12 dp tap slop), [whileDragging] checks what the drag did, then a second finger lands at
     * [other] and both lift: a two-finger tap.
     */
    fun dragThenSecondFinger(first: Pair<Float, Float>, other: Pair<Float, Float>, whileDragging: () -> Unit) {
        val a = px(first)
        val t = s.touch
        t.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        for (i in 1..5) {
            t.idle(16)
            t.send(MotionEvent.ACTION_MOVE, P(0, a.first + 2f * i * s.density, a.second))
        }
        settle(2, 10)
        whileDragging()
        secondFingerTaps(a.first + 10f * s.density to a.second, px(other))
    }

    /**
     * The first finger goes down at [first] (editor dp) and stays still, [whileHolding] checks
     * what its touch-down did (a stepper steps on touch-down), then a second finger lands at
     * [other] and both lift: a two-finger tap.
     */
    fun holdThenSecondFinger(first: Pair<Float, Float>, other: Pair<Float, Float>, whileHolding: () -> Unit) {
        val a = px(first)
        val t = s.touch
        t.send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
        settle(2, 10)
        whileHolding()
        secondFingerTaps(a, px(other))
    }

    /** The first finger is down at [a] (window px): a second one lands at [b], then both lift. */
    private fun secondFingerTaps(a: Pair<Float, Float>, b: Pair<Float, Float>) {
        val t = s.touch
        t.send(MotionEvent.ACTION_POINTER_DOWN, P(0, a.first, a.second), P(1, b.first, b.second), index = 1)
        t.idle(40)
        t.send(MotionEvent.ACTION_POINTER_UP, P(0, a.first, a.second), P(1, b.first, b.second), index = 0)
        t.idle(20)
        t.send(MotionEvent.ACTION_UP, P(1, b.first, b.second))
        settle()
    }

    val steps: Int get() = s.c.undoManager.undoCount

    /** Whether the undo / redo feedback [text] ("Undo: Seed") shows. */
    fun saw(text: String): Boolean = SmokeUi.has(text, exact = true)

    companion object {
        const val SEED = "Seed"

        /**
         * Records ONE known step "Seed" on the bottom layer (whatever tool is active): the step a
         * history tap must undo, and its name the feedback must carry.
         */
        fun seed(c: EditorController, y: Float = 20f) {
            val before = c.undoManager.undoCount
            assertTrue(c.editWholeLayer(c.doc.layers[0], SEED) { Canvas(it).drawRect(4f, y, 24f, y + 8f, Paint().apply { color = 0xFF33AA55.toInt() }) })
            settle(4)
            assertEquals("the seed is one step", before + 1, c.undoManager.undoCount)
            assertEquals(SEED, c.undoManager.undoLabel)
        }
    }
}
