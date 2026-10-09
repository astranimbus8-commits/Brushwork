package com.brushwork.paint.ui.tools

import android.graphics.Matrix
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.StepTarget
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisDims
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * v1.7 items 9 and 13 (design §3.9 / §3.13; area I): the pill's "# step" cells, its Scale row and
 * its trash cell, against [FakePillTool] (a curve-like object of four points, box 40 × 20 px) on
 * the user's phone (392 dp, xxhdpi):
 * - row 1 `[✥][X][Y][# 10][🗑]`: "Increments" says its step ("Off, step 10 px", the "# 10" is
 *   drawn, not one more label), finger-sized; the trash cell says what it deletes ("Delete
 *   curve");
 * - row 2 4 dp under it, `[⛓][Scale X][Scale Y][# 10]`, while the tool has a scale: "Scale X"
 *   typed 200 with "Keep scale proportions" on doubles the box both ways in ONE setScale (one
 *   edit); with it off (remembered as `pill.keepProportions`) Scale Y alone changes; a drag of
 *   Scale X changes it by 1 % per dp; "Scale increments" switches increments and its long-press
 *   opens the Scale Step popup;
 * - folded, the pill is `[✥][🗑]` (no row 2); the trash cell deletes the selected points with
 *   one tap ("Delete selected points", `delete()` once), and hides with no object open.
 * One test (Compose's frame clock serves the first test of a sandbox only), own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.pillrowssandbox"])
class CoordinatePillRowsUiTest {

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The placed element whose content description is exactly [label]. */
    private fun described(label: String): RobolectricUi.Element? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
    }

    private fun tagged(tag: String): RobolectricUi.Element? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) == tag
    }

    @Test
    fun scaleRowStepCellsAndTrash() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val density = activity.resources.displayMetrics.density
            val c = Smoke.controller(activity, Smoke.document(256, 256, layers = 1))
            c.viewTransform.set(Matrix())
            c.snapping.enabled = false
            val fake = FakePillTool(c)
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = fake
            c.selectTool(ToolId.CURVE)
            assertEquals(ToolId.CURVE, c.activeToolId)
            activity.setContent {
                BrushworkTheme {
                    CompositionLocalProvider(LocalIncrements provides c.increments) {
                        Box(Modifier.fillMaxSize()) { CoordinatePill(c, Modifier.offset(x = 8.dp, y = 135.dp)) }
                    }
                }
            }
            settle()

            // ---------------------------------------------------------------- row 1
            val hash = requireNotNull(described(INCREMENTS_LABEL)) { "the # cell; shown: ${SmokeUi.shown()}" }
            assertEquals("the step is the state", "Off, step 10 px", hash.stateDescription)
            assertEquals(ToggleableState.Off, hash.node.config[SemanticsProperties.ToggleableState])
            assertTrue("40–56 dp wide: ${hash.node.size.width / density}", hash.node.size.width >= 40f * density - 1f && hash.node.size.width <= 56f * density + 1f)
            assertTrue("finger-tall", hash.node.size.height >= 40f * density - 1f)
            assertFalse("\"# 10\" is drawn, not a label", SmokeUi.has("# 10"))
            val trash = requireNotNull(tagged(V17Tags.PILL_TRASH)) { "the trash cell" }
            assertTrue(SmokeUi.has(PillLabels.deleteObject("curve"), exact = true))
            assertTrue("finger-sized trash", trash.node.size.width >= 40f * density - 1f && trash.node.size.height >= 40f * density - 1f)

            // ---------------------------------------------------------------- row 2
            val row2 = requireNotNull(tagged(V17Tags.PILL_SCALE_ROW)) { "row 2 while the tool has a scale" }
            val row1Top = described("Center")!!.bounds.top
            assertEquals("4 dp under the 32 dp row 1", (IbisDims.PillHeight.value + IbisDims.PillRowGap.value) * density, row2.bounds.top - row1Top, 1f)
            assertEquals("100 %", described(PillLabels.SCALE_X)!!.stateDescription)
            assertEquals("100 %", described(PillLabels.SCALE_Y)!!.stateDescription)
            val keep = described(PillLabels.KEEP_PROPORTIONS)!!
            assertEquals("on by default", ToggleableState.On, keep.node.config[SemanticsProperties.ToggleableState])
            assertEquals("Off, step 10 %", described(PillLabels.SCALE_INCREMENTS)!!.stateDescription)

            // Typed, proportions kept: one setScale, both axes, about the box centre.
            var calls = fake.setScaleCalls
            var edits = fake.beginScaleCalls
            SmokeUi.click("Type ${PillLabels.SCALE_X}")
            SmokeUi.typeAndDone(PillLabels.SCALE_X, "200")
            assertEquals("one setScale", calls + 1, fake.setScaleCalls)
            assertEquals("one edit (one undo step)", edits + 1, fake.beginScaleCalls)
            assertEquals("ended", fake.beginScaleCalls, fake.endScaleCalls)
            assertEquals(Vec2(200f, 200f), fake.objectScale!!.scalePercent)
            assertEquals(listOf(Vec2(-10f, 0f), Vec2(70f, 0f), Vec2(70f, 40f), Vec2(-10f, 40f)), fake.points)
            assertEquals("200 %", described(PillLabels.SCALE_Y)!!.stateDescription)

            // Proportions off (remembered): Scale Y alone; an expression is typed like any value.
            SmokeUi.click(PillLabels.KEEP_PROPORTIONS, exact = true)
            assertEquals(false, c.settings.getObject("pill.keepProportions", Boolean.serializer()))
            calls = fake.setScaleCalls
            SmokeUi.click("Type ${PillLabels.SCALE_Y}")
            SmokeUi.typeAndDone(PillLabels.SCALE_Y, "100/4")
            assertEquals(calls + 1, fake.setScaleCalls)
            assertEquals(Vec2(200f, 25f), fake.objectScale!!.scalePercent)

            // A drag of Scale X: 1 % per dp of travel; the other axis stays (keep off).
            val sx = described(PillLabels.SCALE_X)!!
            val b = sx.bounds
            val t0 = SystemClock.uptimeMillis()
            var t = t0
            fun send(action: Int, px: Float) {
                val ev = MotionEvent.obtain(t0, t, action, px, b.center.y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                sx.window.dispatchTouchEvent(ev)
                ev.recycle()
                idle(8)
                t += 8
            }
            edits = fake.beginScaleCalls
            send(MotionEvent.ACTION_DOWN, b.center.x)
            for (i in 1..10) send(MotionEvent.ACTION_MOVE, b.center.x + 50f * density * i / 10f)
            send(MotionEvent.ACTION_UP, b.center.x + 50f * density)
            settle(4)
            val dragged = fake.objectScale!!.scalePercent!!
            assertEquals("+50 dp = +50 %", 250f, dragged.x, 0.6f)
            assertEquals(25f, dragged.y, 1e-3f)
            assertEquals("the whole drag is one edit", edits + 1, fake.beginScaleCalls)
            assertEquals(fake.beginScaleCalls, fake.endScaleCalls)

            // "Scale increments": the same switch as "#"; its long-press is the Scale step.
            SmokeUi.click(PillLabels.SCALE_INCREMENTS, exact = true)
            assertTrue(c.increments.enabled)
            assertEquals("On, step 10 px", described(INCREMENTS_LABEL)!!.stateDescription)
            requireNotNull(described(PillLabels.SCALE_INCREMENTS)!!.node.config[SemanticsActions.OnLongClick].action).invoke()
            settle()
            val scaleStep = StepTarget(IncrementKind.SCALE, null).title
            assertTrue("the Scale Step popup: ${SmokeUi.shown().take(30)}", SmokeUi.has(scaleStep, exact = true))
            SmokeUi.click("Cancel", exact = true)
            SmokeUi.click(INCREMENTS_LABEL, exact = true)
            assertFalse(c.increments.enabled)

            // ---------------------------------------------------------------- folded: [✥][🗑]
            SmokeUi.click("Fold the X / Y strip", exact = true)
            assertNull("no row 2 folded", tagged(V17Tags.PILL_SCALE_ROW))
            assertFalse(SmokeUi.has(INCREMENTS_LABEL, exact = true))
            assertFalse(SmokeUi.has(PillLabels.SCALE_X, exact = true))
            assertNotNull("the trash cell stays", tagged(V17Tags.PILL_TRASH))

            // The trash cell: what it deletes now, one tap, delete() once.
            fake.select(1, 2)
            settle()
            assertTrue(SmokeUi.has(PillLabels.DELETE_POINTS, exact = true))
            val deletes = fake.deleteCalls
            SmokeUi.click(PillLabels.DELETE_POINTS, exact = true)
            assertEquals("one tap, one delete", deletes + 1, fake.deleteCalls)
            assertEquals(2, fake.points.size)
            assertTrue(SmokeUi.has(PillLabels.deleteObject("curve"), exact = true))
            SmokeUi.click("Unfold the X / Y strip", exact = true)
            assertNotNull(tagged(V17Tags.PILL_SCALE_ROW))
            // The whole object: nothing left open, the pill goes.
            SmokeUi.click(PillLabels.deleteObject("curve"), exact = true)
            assertFalse(fake.open)
            assertNull(tagged(V17Tags.PILL_TRASH))
            assertFalse(SmokeUi.has("Fold the X / Y strip", exact = true))
            c.dispose()
        } finally {
            runCatching { ctl.pause().stop().destroy() }
        }
    }
}
