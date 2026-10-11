package com.brushwork.paint.ui.tools.strip

import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.V18Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 integration (v18I): a window narrower than the design's 360 dp (a larger Display size on
 * the user's phone). The design's row-1 budgets do not hold there, so the host neither checks them
 * (a debug build's I18 check would throw while the strip composes) nor clips row 1: its cells
 * scroll sideways and the More toggle stays pinned at the end.
 *
 * At 320 dp, for Brush, Curve, Shape, Lasso and Transform (a picture lifted: the box modes) on a
 * raster and on a vector layer, and for a fake row 1 too wide even for 360 dp: nothing throws;
 * every row-1 item of the split, scrolled into view through the row's own scroll action, shows
 * whole inside row 1 and on screen; the cells get the room the toggle leaves and the row scrolls
 * exactly as far as its content needs; the toggle shows whole on screen without scrolling and
 * stays put while the cells scroll; and the More row opens on screen. `StripRow1CheckTest` keeps
 * the check throwing at 360 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.row1narrowsandbox"])
class StripRow1NarrowTest {

    private val tools = listOf(ToolId.BRUSH, ToolId.CURVE, ToolId.SHAPE, ToolId.LASSO, ToolId.TRANSFORM)

    /** The node's own box in the window, not clipped (for nodes that are not turned: the scroller, the toggle, the More row). */
    private fun SemanticsNode.box(): Rect = Rect(positionInWindow.x, positionInWindow.y, positionInWindow.x + size.width, positionInWindow.y + size.height)

    /**
     * What shows of the node is all of it: its clipped bounds cover its own area (a turned node's
     * bounds are its bounding box, at least its area; clipping takes some away).
     */
    private fun SemanticsNode.whole(): Boolean {
        val b = boundsInWindow
        return b.width * b.height >= 0.99f * size.width * size.height
    }

    private fun SemanticsNode.shows(): Boolean = config.getOrNull(SemanticsProperties.Text) != null ||
        config.getOrNull(SemanticsProperties.ContentDescription) != null ||
        config.getOrNull(SemanticsActions.OnClick) != null ||
        config.getOrNull(SemanticsActions.SetProgress) != null

    private fun SemanticsNode.under(ancestor: SemanticsNode): Boolean = generateSequence(parent) { it.parent }.any { it.id == ancestor.id }

    /** Row 1's scroller below 360 dp: the node under the one tagged [V18Tags.STRIP_ROW1] that takes ScrollBy. */
    private fun scroller(): SemanticsNode? {
        val row1 = StripTestKit.tagged(V18Tags.STRIP_ROW1) ?: return null
        return RobolectricUi.elements().map { it.node }.firstOrNull { n ->
            n.config.getOrNull(SemanticsActions.ScrollBy) != null && n.under(row1)
        }
    }

    /** Two 200 dp items that never leave row 1 (wider than its 360 dp budget too) and a More item. */
    private fun tooWide(): StripSpec {
        fun cell(label: String, w: Int): StripItem = StripItem("narrow.$label", label, w.dp) {
            Box(Modifier.size(w.dp, 40.dp).semantics { contentDescription = label })
        }
        return StripSpec(listOf(cell("Wide one", 200), cell("Wide two", 200), StripItem("narrow.more", "Wide more", 120.dp, StripRow.MORE) {
            Box(Modifier.size(120.dp, 40.dp).semantics { contentDescription = "Wide more" })
        }))
    }

    private class Tally {
        val bad = mutableListOf<String>()
        var scrolled = 0
        var opened = 0
    }

    /** Checks the strip [rig] shows now (its tool's, or [StripRig.override]) as [key]. */
    private fun checkRow1(rig: StripRig, key: String, density: Float, screenPx: Float, tally: Tally) {
        val bad = tally.bad
        val spec = rig.spec
        val chip = if (rig.c.isVectorMode) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
        val split = StripLayout.split(spec, StripInventory.content(320f) - chip)
        val s0 = scroller()
        if (s0 == null) {
            bad += "$key: row 1 has no scroller below 360 dp"
            return
        }
        val viewport = s0.box()
        val vectorDp = if (rig.c.isVectorMode) StripLayout.VECTOR_CHIP_DP + if (split.row1.isEmpty()) 0f else StripLayout.GAP_DP else 0f
        val contentPx = (vectorDp + StripLayout.rowWidth(split.row1)) * density
        // The cells get the room the toggle leaves, no more than they need.
        val room = (StripInventory.content(320f) - if (split.toggle) StripLayout.TOGGLE_DP + StripLayout.GAP_DP else 0f) * density
        if (abs(viewport.width - minOf(room, contentPx)) > 3f) bad += "$key: the cells get ${viewport.width} px, not ${minOf(room, contentPx)} px"
        val expectedMax = maxOf(0f, contentPx - viewport.width)
        val range = s0.config[SemanticsProperties.HorizontalScrollAxisRange]
        if (abs(range.maxValue() - expectedMax) > 3f) bad += "$key: row 1 scrolls ${range.maxValue()} px, its content needs $expectedMax px"
        if (range.maxValue() > 0f) tally.scrolled++

        val toggle0 = StripTestKit.toggle()?.box()
        if (split.toggle != (toggle0 != null)) bad += "$key: the toggle shows = ${toggle0 != null}, the split says ${split.toggle}"
        if (toggle0 != null) {
            if (toggle0.left < -0.5f || toggle0.right > screenPx + 0.5f) bad += "$key: the toggle is off screen without scrolling: $toggle0"
            if (toggle0.left < viewport.right - 0.5f) bad += "$key: the toggle overlaps the scrolling cells: $toggle0, $viewport"
            if (!StripTestKit.toggle()!!.whole()) bad += "$key: the toggle is clipped: ${StripTestKit.toggle()!!.boundsInWindow}"
        }

        // Each shown row-1 item, at its place in the split, scrolled into view.
        var x = vectorDp
        for (item in split.row1) {
            val from = x * density
            val to = (x + item.width.value) * density
            x += item.width.value + StripLayout.GAP_DP
            if (!item.shown) continue
            val what = "$key: \"${item.label}\""
            val s = scroller()!!
            val cur = s.config[SemanticsProperties.HorizontalScrollAxisRange].value()
            val target = when {
                from < cur -> from
                to > cur + viewport.width -> to - viewport.width
                else -> cur
            }
            if (target != cur) {
                s.config[SemanticsActions.ScrollBy].action?.invoke(target - cur, 0f)
                rig.settle()
            }
            val now = scroller()!!
            val at = now.config[SemanticsProperties.HorizontalScrollAxisRange].value()
            if (abs(at - target) > 1.5f) {
                bad += "$what: the row scrolled to $at px, not $target px"
                continue
            }
            // The item's cell in the window, and what it shows there.
            val left = viewport.left - at + from
            val right = viewport.left - at + to
            if (left < viewport.left - 1f || right > viewport.right + 1f) bad += "$what: its cell [$left, $right] is not in row 1 $viewport"
            val parts = RobolectricUi.elements().map { it.node }
                .filter { it.id != now.id && it.under(now) && it.shows() }
                .filter { it.boundsInWindow.width > 0f && it.boundsInWindow.center.x in left..right }
            if (parts.isEmpty()) bad += "$what: nothing shows in its cell"
            for (p in parts) {
                val u = p.boundsInWindow
                if (!p.whole()) bad += "$what: clipped ($u of ${p.size})"
                if (u.left < viewport.left - 1f || u.right > viewport.right + 1f) bad += "$what: outside row 1 ($u, $viewport)"
                if (u.left < -1f || u.right > screenPx + 1f) bad += "$what: off screen ($u)"
            }
            val toggle = StripTestKit.toggle()?.box()
            if (toggle != toggle0) bad += "$what: the toggle moved while the cells scrolled ($toggle0 to $toggle)"
        }

        if (split.toggle) {
            if (!StripTestKit.openMoreRow() || !StripTestKit.moreRowShown()) {
                bad += "$key: the More row does not open"
            } else {
                tally.opened++
                val more = StripTestKit.tagged(V18Tags.STRIP_MORE_ROW)!!.box()
                if (more.left < -0.5f || more.right > screenPx + 0.5f) bad += "$key: the More row is off screen: $more"
                StripTestKit.closeMoreRow()
            }
        }
    }

    @Test
    fun belowThe360dpBudgetsRow1ScrollsAndEveryItemAndTheToggleStayReachable() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        // A debug build's I18 check throws while the strip composes; Compose hands the exception
        // to the main thread's handler (and its recomposer stops), so the test collects it there.
        val thrown = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val thread = Thread.currentThread()
        val handler = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t -> thrown += t }
        val tally = Tally()
        try {
            val rig = StripRig(panel = false).show()
            assertTrue("a debug build: the host checks I18 at 360 dp and wider", rig.activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            val metrics = rig.activity.resources.displayMetrics
            val density = metrics.density
            val screenPx = metrics.widthPixels.toFloat()
            assertEquals("a 320 dp window", 320f, screenPx / density, 0.5f)
            try {
                // A picture on the raster layer: Transform lifts it (the box modes).
                rig.select(ToolId.BRUSH)
                assertTrue(rig.c.editWholeLayer(rig.doc.layers[0], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
                for (vector in listOf(false, true)) for (id in tools) {
                    val key = id.name + if (vector) "@vector" else ""
                    rig.select(id, vector)
                    if (id == ToolId.TRANSFORM && !vector) {
                        val t = rig.c.currentTool as TransformTool
                        assertTrue("$key: lifted", Smoke.pumpUntil { rig.settle(); t.transformState != null })
                        rig.settle()
                    }
                    if (thrown.isNotEmpty()) throw AssertionError("$key: the strip threw at 320 dp", thrown.first())
                    if (rig.c.activeToolId != id) {
                        tally.bad += "$key: not selected"
                        continue
                    }
                    checkRow1(rig, key, density, screenPx, tally)
                }
                // Row 1 too wide even for the 360 dp budget (non-AUTO items never leave it).
                rig.select(ToolId.BRUSH)
                rig.override = tooWide()
                rig.settle()
                if (thrown.isNotEmpty()) throw AssertionError("too wide: the strip threw at 320 dp", thrown.first())
                assertEquals(listOf("narrow.Wide one", "narrow.Wide two"), StripLayout.split(rig.spec, StripInventory.content(360f)).row1.map { it.key })
                checkRow1(rig, "too wide", density, screenPx, tally)
                rig.override = null
                rig.settle()
            } finally {
                rig.close()
            }
        } finally {
            thread.uncaughtExceptionHandler = handler
        }
        if (thrown.isNotEmpty()) throw AssertionError("the strip threw at 320 dp", thrown.first())
        assertTrue("row 1 below 360 dp:\n" + tally.bad.joinToString("\n"), tally.bad.isEmpty())
        // Not vacuous: some rows are wider than 320 dp allows (Brush with the VECTOR cell, the
        // fake) and several tools have a More row.
        assertTrue("some row 1 scrolled (${tally.scrolled})", tally.scrolled >= 2)
        assertTrue("some More rows opened (${tally.opened})", tally.opened >= 4)
    }
}
