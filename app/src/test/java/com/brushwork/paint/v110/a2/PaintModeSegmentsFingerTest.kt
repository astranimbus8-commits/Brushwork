package com.brushwork.paint.v110.a2

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.IconLongPressScope
import com.brushwork.paint.ui.common.LongPressLabelHost
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.paintModeName
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.vector.PaintModeChoices
import com.brushwork.paint.ui.vector.PaintModeSheetRow
import com.brushwork.paint.ui.vector.PaintModeTags
import com.brushwork.paint.ui.vector.curvePaintRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * v1.10 (R3 (e); design 4.4 A2 step 2): the paint-mode segments under a finger, as a settings
 * sheet shows them (PaintModeSheetRow, the F0 PaintModeSegments) and as the curve strips' Stroke
 * kind menu does (PaintModeChoices): each segment is 48 x 40 dp, icon only, known by
 * CurveLabels17's name of its mode, which a long press shows in a bubble without picking it; a
 * tap picks it. In the menu a mode that is refused (Fill and Both with 1 or 2 points) is greyed,
 * marked disabled, reads why, and a tap says why and picks nothing; the curve settings sheet's row
 * (PaintModeSheetRow with `curvePaintRefusal`, review fix) refuses the same way.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.segmentsfingersandbox"])
class PaintModeSegmentsFingerTest {

    private fun send(v: View, downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(downTime, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Holds for [ms] of REAL time while running the looper (Compose times a long press on the wall clock). */
    private fun holdRealTime(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(10)
            idle(10)
        }
    }

    private fun bubble(): String? = StripTestKit.tagged(V18Tags.LONG_PRESS_BUBBLE)?.let { b ->
        generateSequence(listOf(b)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
            .firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
    }

    private fun byTag(tag: String): RobolectricUi.Element =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag } ?: throw AssertionError("no $tag")

    /** A finger held still on [tag] for 800 ms; [check] runs before it lifts. */
    private fun longPress(tag: String, check: () -> Unit) {
        val e = byTag(tag)
        val x = e.bounds.center.x
        val y = e.bounds.center.y
        val t0 = SystemClock.uptimeMillis()
        send(e.window, t0, t0, MotionEvent.ACTION_DOWN, x, y)
        holdRealTime(800)
        check()
        send(e.window, t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        idle(50)
        RobolectricUi.settle(4, 50)
    }

    @Test
    fun segmentsAreFingerSizedIconsThatSayTheirNames() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var sheetMode by mutableStateOf<PaintMode?>(PaintMode.STROKE)
        var menuMode by mutableStateOf<PaintMode?>(PaintMode.STROKE)
        var curveMode by mutableStateOf<PaintMode?>(PaintMode.STROKE)
        var fillOk by mutableStateOf(false)
        val refused = mutableListOf<String>()
        val sheetRefused = mutableListOf<String>()
        activity.setContent {
            BrushworkTheme {
                LongPressLabelHost(Modifier.fillMaxWidth().height(400.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(40.dp))
                        PaintModeSheetRow(sheetMode, PaintModeTags.SHAPE) { sheetMode = it }
                        Spacer(Modifier.height(80.dp))
                        IconLongPressScope {
                            PaintModeChoices(
                                selected = menuMode,
                                refusal = { m -> if (m != PaintMode.STROKE && !fillOk) CurveLabels17.FILL_NEEDS_3 else null },
                                tag = PaintModeTags.CURVE_STRIP,
                                onSelect = { menuMode = it },
                                onRefused = { refused += it },
                            )
                        }
                        Spacer(Modifier.height(40.dp))
                        // The curve settings sheet's row (review fix): the strip menu's refusal.
                        PaintModeSheetRow(curveMode, PaintModeTags.CURVE, refusal = curvePaintRefusal(fillOk), onRefused = { sheetRefused += it }) { curveMode = it }
                    }
                }
            }
        }
        RobolectricUi.settle(10, 50)
        val density = activity.resources.displayMetrics.density

        // 48 x 40 dp, icon only, known by its name; the selected one reads as selected.
        for (prefix in listOf(PaintModeTags.SHAPE, PaintModeTags.CURVE_STRIP, PaintModeTags.CURVE)) {
            for (m in PaintMode.entries) {
                val e = byTag("$prefix.${m.name}")
                val where = "$prefix.${m.name}"
                assertEquals("$where: 48 dp wide", 48f, e.bounds.width / density, 0.6f)
                assertEquals("$where: 40 dp tall", 40f, e.bounds.height / density, 0.6f)
                assertEquals("$where: known by its name", listOf(paintModeName(m)), e.node.config.getOrNull(SemanticsProperties.ContentDescription))
                assertNull("$where: no visible text", e.node.config.getOrNull(SemanticsProperties.Text))
                assertEquals("$where: selected", m == PaintMode.STROKE, e.node.config.getOrNull(SemanticsProperties.Selected))
            }
        }

        // A long press shows the name and picks nothing; a tap picks.
        for (m in PaintMode.entries) {
            longPress("${PaintModeTags.SHAPE}.${m.name}") { assertEquals("the sheet's ${m.name}: its name in the bubble", paintModeName(m), bubble()) }
        }
        assertEquals("a long press never picks", PaintMode.STROKE, sheetMode)
        byTag("${PaintModeTags.SHAPE}.${PaintMode.BOTH.name}").tap()
        assertEquals(PaintMode.BOTH, sheetMode)
        assertEquals(true, byTag("${PaintModeTags.SHAPE}.${PaintMode.BOTH.name}").node.config.getOrNull(SemanticsProperties.Selected))

        // The menu's refused modes: greyed, disabled, they read why; a long press still names them.
        for (m in listOf(PaintMode.FILL, PaintMode.BOTH)) {
            val n = byTag("${PaintModeTags.CURVE_STRIP}.${m.name}").node
            assertTrue("${m.name} refused: disabled", n.config.contains(SemanticsProperties.Disabled))
            assertEquals("${m.name} refused: says why", CurveLabels17.FILL_NEEDS_3, n.config.getOrNull(SemanticsProperties.StateDescription))
        }
        assertTrue("Stroke is never refused", !byTag("${PaintModeTags.CURVE_STRIP}.${PaintMode.STROKE.name}").node.config.contains(SemanticsProperties.Disabled))
        longPress("${PaintModeTags.CURVE_STRIP}.${PaintMode.FILL.name}") { assertEquals(CurveLabels17.FILL_ONLY, bubble()) }
        assertTrue("a long press says nothing else", refused.isEmpty())
        // A tap on a refused mode says why and picks nothing.
        byTag("${PaintModeTags.CURVE_STRIP}.${PaintMode.FILL.name}").tap()
        assertEquals(listOf(CurveLabels17.FILL_NEEDS_3), refused)
        assertEquals(PaintMode.STROKE, menuMode)
        // The curve settings sheet's row refuses the same way (review fix).
        for (m in listOf(PaintMode.FILL, PaintMode.BOTH)) {
            val n = byTag("${PaintModeTags.CURVE}.${m.name}").node
            assertTrue("the sheet's ${m.name} refused: disabled", n.config.contains(SemanticsProperties.Disabled))
            assertEquals(CurveLabels17.FILL_NEEDS_3, n.config.getOrNull(SemanticsProperties.StateDescription))
        }
        byTag("${PaintModeTags.CURVE}.${PaintMode.BOTH.name}").tap()
        assertEquals(listOf(CurveLabels17.FILL_NEEDS_3), sheetRefused)
        assertEquals(PaintMode.STROKE, curveMode)
        // Possible again (a third point): the tap picks.
        fillOk = true
        RobolectricUi.settle(4, 50)
        val fill = byTag("${PaintModeTags.CURVE_STRIP}.${PaintMode.FILL.name}")
        assertTrue("enabled again", !fill.node.config.contains(SemanticsProperties.Disabled))
        assertNull(fill.node.config.getOrNull(SemanticsProperties.StateDescription))
        fill.tap()
        assertEquals(PaintMode.FILL, menuMode)
        assertEquals(1, refused.size)
        byTag("${PaintModeTags.CURVE}.${PaintMode.BOTH.name}").tap()
        assertEquals("the sheet's row picks too", PaintMode.BOTH, curveMode)
        assertEquals(1, sheetRefused.size)
    }
}
