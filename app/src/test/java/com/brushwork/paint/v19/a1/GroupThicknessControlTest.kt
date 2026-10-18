package com.brushwork.paint.v19.a1

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.points.GroupSliderGeometry
import com.brushwork.paint.ui.points.ThicknessRowDefaults
import com.brushwork.paint.ui.points.ThicknessRowLabels
import com.brushwork.paint.ui.vector.POINT_THICKNESS_LABEL
import com.brushwork.paint.v19.a1.A1Tools.steps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * v1.9 R1 and R2 (design 1 R1 (e)): several selected points use the one point's row
 * ([com.brushwork.paint.ui.vector.GroupThicknessControl]) in the editor at 392 dp, driven through
 * its controls: a thumb drag is relative (a taper keeps its ratios, "×1.25" while dragging, one
 * step, one undo and one redo), a point at 0 % rises by the offset and points all at 0 % take the
 * thumb, a tap or a double tap on the track changes nothing, ‹ › move each point on the
 * configured Percent grid (0 % to one step), typed "*2", "/2", "+10", "-10" and a number; and the
 * one-point report of R2 on Polyline, Curve and Path: a point at 0 % goes up again when its thumb
 * is dragged, also from a finger that lands just short of it, in the › button's box (it ticked ›
 * once and dragged nothing before the review; the group form likewise), and by ›.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v19.a1.groupthicknesssandbox"])
class GroupThicknessControlTest {

    @Test
    fun severalPointsUseTheOnePointRow() = Qa19Ui.uiTest { h ->
        h.section("a drag scales each point (×1.25 while dragging), one step") {
            val (s, t) = editor(h, ToolId.POLYLINE)
            group(t, 50f, 100f, 150f)
            assertTrue("the group row: ${SmokeUi.shown().take(60)}", Qa19Ui.openMoreRow() || Qa19Ui.tagged(V19Tags.GROUP_THICKNESS) != null)
            assertNotNull(Qa19Ui.tagged(V19Tags.GROUP_THICKNESS))
            for (label in listOf(ThicknessRowLabels.THINNER, ThicknessRowLabels.TYPE, ThicknessRowLabels.THICKER, ThicknessRowLabels.SLIDER)) {
                assertTrue("$label is there", Qa19Ui.has(label, exact = true))
            }
            assertTrue(Qa19Ui.has("$POINT_THICKNESS_LABEL ${PointLabels.MIXED}"))
            val before = steps(t)
            var during = ""
            dragThumb(s, from = 100f, by = 25f) { during = SmokeUi.shown().firstOrNull { it.startsWith("$POINT_THICKNESS_LABEL ") && it != ThicknessRowLabels.SLIDER }.orEmpty() }
            assertEquals("$POINT_THICKNESS_LABEL ${PointLabels19.FACTOR.format("1.25")}", during)
            assertArrayEquals(floatArrayOf(62.5f, 125f, 187.5f), percents(t), 1e-3f)
            assertEquals("one drag, one step", before + 1, steps(t))
            t.undoStep()
            assertArrayEquals(floatArrayOf(50f, 100f, 150f), percents(t), 1e-3f)
            assertTrue(t.redoStep())
            assertArrayEquals("one redo", floatArrayOf(62.5f, 125f, 187.5f), percents(t), 1e-3f)
        }

        h.section("from 0 %: a point rises by the offset; points all at 0 take the thumb") {
            val (s, t) = editor(h, ToolId.CURVE)
            group(t, 0f, 40f)
            Qa19Ui.openMoreRow()
            dragThumb(s, from = 20f, by = 10f)
            assertArrayEquals(floatArrayOf(10f, 60f), percents(t), 1e-3f)
            group(t, 0f, 0f)
            Qa19Ui.settle()
            assertTrue(Qa19Ui.has("$POINT_THICKNESS_LABEL 0 %", exact = true))
            dragThumb(s, from = 0f, by = 50f)
            assertArrayEquals(floatArrayOf(50f, 50f), percents(t), 1e-3f)
            assertTrue(Qa19Ui.has("$POINT_THICKNESS_LABEL 50 %", exact = true))
            // R2: all at 0 % again, a drag that starts just beside the thumb (in ›'s box, past
            // its icon) takes the thumb: the points rise together, not by ›'s one step.
            group(t, 0f, 0f)
            val before = steps(t)
            val beside = layer().positionInWindow.x - BESIDE_DP * s.density
            touch(slider(s), (0..6).map { beside + it * 6f * s.density })
            val p = percents(t)
            assertTrue("both rose together by the drag: ${p.toList()}", p[0] == p[1] && p[0] > 50f)
            assertEquals("one drag, one step", before + 1, steps(t))
        }

        h.section("a tap and a double tap on the track change nothing") {
            val (s, t) = editor(h, ToolId.PATH)
            group(t, 50f, 150f)
            Qa19Ui.openMoreRow()
            val before = steps(t)
            val el = slider(s)
            val far =layer().positionInWindow.x + layer().size.width * 0.85f
            touch(el, listOf(far))
            assertArrayEquals(floatArrayOf(50f, 150f), percents(t), 0f)
            touch(el, listOf(far))
            touch(el, listOf(far))
            assertArrayEquals(floatArrayOf(50f, 150f), percents(t), 0f)
            // A touch on the thumb that does not move changes nothing either.
            touch(el, listOf(thumbX(s, 100f)))
            assertArrayEquals(floatArrayOf(50f, 150f), percents(t), 0f)
            assertEquals("no step", before, steps(t))
        }

        h.section("‹ › move each point on the configured Percent grid, 0 % to one step") {
            val (_, t) = editor(h, ToolId.POLYLINE)
            t.controller.increments.update { it.copy(enabled = true).with(IncrementKind.PERCENT, 10f) }
            group(t, 0f, 12f, 40f)
            Qa19Ui.openMoreRow()
            val before = steps(t)
            Qa19Ui.click(ThicknessRowLabels.THICKER, exact = true)
            assertArrayEquals(floatArrayOf(10f, 20f, 50f), percents(t), 1e-3f)
            assertEquals("one tap, one step", before + 1, steps(t))
            Qa19Ui.click(ThicknessRowLabels.THINNER, exact = true)
            Qa19Ui.click(ThicknessRowLabels.THINNER, exact = true)
            assertArrayEquals(floatArrayOf(0f, 0f, 30f), percents(t), 1e-3f)
        }

        h.section("typed: *2, /2, +10, -10 and a number") {
            val (_, t) = editor(h, ToolId.CURVE)
            group(t, 20f, 40f)
            for ((text, want) in listOf(
                "*2" to floatArrayOf(40f, 80f),
                "/2" to floatArrayOf(20f, 40f),
                "+10" to floatArrayOf(30f, 50f),
                "-10" to floatArrayOf(20f, 40f),
                "75" to floatArrayOf(75f, 75f),
                "+500" to floatArrayOf(300f, 300f),
            )) {
                val before = steps(t)
                Qa19Ui.click(ThicknessRowLabels.TYPE, exact = true)
                SmokeUi.typeAndDone(POINT_THICKNESS_LABEL, text)
                assertArrayEquals(text, want, percents(t), 1e-3f)
                assertEquals("$text: one step", before + 1, steps(t))
            }
        }

        for (id in listOf(ToolId.POLYLINE, ToolId.CURVE, ToolId.PATH)) h.section("R2, $id: one point at 0 % goes up again: its thumb dragged, from beside it too, and ›") {
            val (s, t) = editor(h, id)
            t.select(1)
            t.setWidth(1, 0f)
            t.endNumericEdit()
            // (Counting the steps walks the history to its start and back, which leaves another
            // point selected: point 1 is selected again after each count.)
            fun count(): Int = steps(t).also {
                t.select(1)
                Qa19Ui.settle()
            }
            val before = count()
            Qa19Ui.openMoreRow()
            assertTrue(Qa19Ui.has("$POINT_THICKNESS_LABEL 0 %", exact = true))
            val el = slider(s)
            val d = s.density
            // The slider's layout starts SLIDER_REACH_DP inside its semantics node; at 0 % the
            // thumb's centre is THUMB_INSET past that, flush against the › button's 40 dp box.
            val start = el.node.positionInWindow.x + SLIDER_REACH_DP * d
            val thumb0 = start + GroupSliderGeometry.THUMB_INSET.value * d
            val beside = start - BESIDE_DP * d
            // The user's report: a finger that lands just short of the thumb at 0 % (in ›'s box,
            // past its icon). A tap there ticks nothing, and a drag from there takes the thumb.
            touch(el, listOf(beside))
            assertEquals("$id: a tap beside the thumb at 0 % ticks nothing", 0f, t.widthOf(1), 0f)
            assertEquals("$id: and makes no step", before, count())
            touch(el, (0..10).map { beside + it * 4f * d })
            val dragged = t.widthOf(1)
            assertTrue("$id: the drag from beside the thumb at 0 % raised it: $dragged", dragged > 0.5f)
            assertEquals("$id: one drag, one step", before + 1, count())
            t.undoStep()
            assertEquals("$id: one undo, 0 % again", 0f, t.widthOf(1), 0f)
            assertTrue(t.redoStep())
            assertEquals("$id: one redo", dragged, t.widthOf(1), 0f)
            // The thumb itself at 0 %, dragged right.
            t.select(1)
            t.setWidth(1, 0f)
            t.endNumericEdit()
            Qa19Ui.settle()
            touch(el, (0..10).map { thumb0 + it * 4f * d })
            assertTrue("$id: the thumb dragged from 0 % raised it: ${t.widthOf(1)}", t.widthOf(1) > 0.3f)
            // With the thumb far from it (100 %), the same touch is ›'s again: one step up.
            t.setWidth(1, 1f)
            t.endNumericEdit()
            Qa19Ui.settle()
            touch(el, listOf(beside))
            assertEquals("$id: › beside a far thumb", 1.05f, t.widthOf(1), 1e-6f)
            // › from 0 % gives one step.
            t.setWidth(1, 0f)
            t.endNumericEdit()
            Qa19Ui.settle()
            Qa19Ui.click(ThicknessRowLabels.THICKER, exact = true)
            assertEquals("$id: › from 0 %", 0.05f, t.widthOf(1), 1e-6f)
        }
    }

    private companion object {
        /** How far the material3 slider's semantics node reaches past its layout at each end (dp). */
        const val SLIDER_REACH_DP = 10f

        /** Where the user's finger lands short of the thumb at 0 %: this far before the slider's layout (dp). */
        const val BESIDE_DP = 4f
    }

    // ---- helpers

    private fun editor(h: com.brushwork.paint.qa19.Qa19Harness, id: ToolId): Pair<ChromeScreen, CurveTool> {
        val s = h.editor()
        s.c.selectTool(id)
        val t = s.c.tools.getValue(id) as CurveTool
        for (p in listOf(Vec2(60f, 200f), Vec2(130f, 90f), Vec2(200f, 160f), Vec2(270f, 80f), Vec2(340f, 200f))) assertTrue(t.addAnchor(p))
        Qa19Ui.settle()
        return s to t
    }

    /** Points 1, 2, … of [t] selected, at [percents]. */
    private fun group(t: CurveTool, vararg percents: Float) {
        t.selectPoints(PointSelection.of(t.pointCount, *IntArray(percents.size) { it + 1 }))
        t.setWidths(t.pointSelection.indices, FloatArray(percents.size) { percents[it] / 100f })
        t.endNumericEdit()
        Qa19Ui.settle()
    }

    private fun percents(t: CurveTool) = t.selectedWidths().map { it * 100f }.toFloatArray()

    /** The thickness slider of [s]'s window (an earlier section's window may still be listed). */
    private fun slider(s: ChromeScreen): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.window === s.activity.window.decorView &&
            e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(ThicknessRowLabels.SLIDER) == true
    }

    /** The group form's touch layer: its bounds are the slider's layout (the slider's semantics node is wider). */
    private fun layer(): SemanticsNode = requireNotNull(Qa19Ui.tagged(ThicknessRowDefaults.GROUP_TOUCHES_TAG)) { "the group's touch layer" }

    /** The thumb's x (window px) at [percent] on the group's slider. */
    private fun thumbX(s: ChromeScreen, percent: Float): Float {
        val l = layer()
        return l.positionInWindow.x + GroupSliderGeometry.thumbX(percent / 300f, l.size.width.toFloat(), GroupSliderGeometry.THUMB_INSET.value * s.density)
    }

    /** A finger down on the thumb at [from] %, moved by [by] % of the track, [beforeUp] while it is still down, then up. */
    private fun dragThumb(s: ChromeScreen, from: Float, by: Float, beforeUp: () -> Unit = {}) {
        val el = slider(s)
        val x0 = thumbX(s, from)
        val track = layer().size.width - 2f * GroupSliderGeometry.THUMB_INSET.value * s.density
        val dx = by / 300f * track
        touch(el, (0..6).map { x0 + dx * it / 6f }, beforeUp)
    }

    /** One finger on [el]'s window at the x positions [xs] in turn (down at the first, up at the last). */
    private fun touch(el: RobolectricUi.Element, xs: List<Float>, beforeUp: () -> Unit = {}) {
        val y = el.node.positionInWindow.y + el.node.size.height / 2f
        val d0 = SystemClock.uptimeMillis()
        var at = d0
        fun send(action: Int, x: Float) {
            val ev = MotionEvent.obtain(d0, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            el.window.dispatchTouchEvent(ev)
            ev.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            at += 16
        }
        send(MotionEvent.ACTION_DOWN, xs.first())
        for (x in xs.drop(1)) send(MotionEvent.ACTION_MOVE, x)
        Qa19Ui.settle(2)
        beforeUp()
        send(MotionEvent.ACTION_UP, xs.last())
        Qa19Ui.settle(4)
        // (A second touch right after counts as a double tap: wait past the double-tap timeout.)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    }
}
