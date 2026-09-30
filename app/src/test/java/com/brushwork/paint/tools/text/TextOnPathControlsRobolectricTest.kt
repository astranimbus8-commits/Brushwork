package com.brushwork.paint.tools.text

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.placement.TextPathControls
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/** The text path controls, composed in a real activity at phone width and driven like a user. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h850dp-xxhdpi")
class TextOnPathControlsRobolectricTest {

    @Before
    fun setUp() {
        ShadowLog.stream = null
        rearmComposeMainDispatcher()
        // Like the other Compose UI tests: frames arrive as the clock advances, popups don't spin.
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
    }

    /**
     * Compose's main dispatcher is shared by every test class of a Robolectric sandbox. When a
     * test ends while the dispatcher has a handler / frame callback posted, the reset drops that
     * callback but the dispatcher still believes it is scheduled, so it never runs anything
     * again: no later test can recompose (seen after the editor smoke tests). Clear its
     * "scheduled" flags and dispatch a no-op, which posts fresh callbacks that drain its queue.
     */
    private fun rearmComposeMainDispatcher() {
        runCatching {
            val dispatcher = mainDispatcher()
            for (name in SCHEDULED_FLAGS) {
                AndroidUiDispatcher::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(dispatcher, false)
            }
            dispatcher.dispatch(EmptyCoroutineContext, Runnable {})
        }
    }

    private fun mainDispatcher() = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher

    /** Whether Compose's main dispatcher still has a callback posted (null when that can't be read). */
    private fun dispatcherScheduled(): Boolean? = runCatching {
        SCHEDULED_FLAGS.any { AndroidUiDispatcher::class.java.getDeclaredField(it).apply { isAccessible = true }.getBoolean(mainDispatcher()) }
    }.getOrNull()

    private val activities = ArrayList<ActivityController<ComponentActivity>>()

    private fun newActivity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also { activities += it }.get()

    /** Leaves nothing behind for the next test class: screens closed, the dispatcher idle. */
    @After
    fun tearDown() {
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        activities.clear()
        var n = 0
        do settle(2) while (dispatcherScheduled() == true && ++n < 50)
    }

    /** Delivers state written outside composition (test code, click actions), then lets frames run. */
    private fun settle(steps: Int = 12) {
        Snapshot.sendApplyNotifications()
        SmokeUi.settle(steps)
    }

    private fun click(label: String) {
        SmokeUi.click(label, exact = true, settleAfter = false)
        settle()
    }

    private fun type(label: String, text: String) {
        SmokeUi.typeAndDone(label, text)
        settle()
    }

    @Test
    fun everyControlEditsTheSpec() {
        val activity = newActivity()
        var spec by mutableStateOf(TextPathSpec())
        val sent = ArrayList<TextPathSpec>()
        // Like the text tool: a new shape type is fitted to the text.
        fun onChange(next: TextPathSpec) {
            sent += next
            spec = if (next.type != spec.type && next.isActive) TextOnPath.defaultFor(next.type, Vec2(300f, 400f), 360f, 48f, next) else next
        }
        activity.setContent {
            BrushworkTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextPathControls(spec, 300f, ::onChange)
                }
            }
        }
        settle()
        assertTrue("straight text explains the shapes", SmokeUi.has("drag the handles on the canvas"))
        assertFalse(SmokeUi.has("Bend letters"))

        click("Circle")
        assertEquals(TextPathType.CIRCLE, sent.last().type)
        assertEquals("only the type changes", TextPathSpec().copy(type = TextPathType.CIRCLE), sent.last())
        assertEquals(TextPathType.CIRCLE, spec.type)
        assertTrue("circle fields are shown", SmokeUi.has("Radius"))

        click("Rotate letters")
        assertEquals(TextPathMode.ROTATE, spec.mode)
        click("Inside")
        assertEquals(TextPathSide.INSIDE, spec.side)
        val angle = spec.startAngleDeg
        click("Counter-clockwise")
        assertFalse(spec.clockwise)
        assertEquals("the text moves to the bottom to stay upright", TextPathGeometry.normalizeDegrees(angle + 180f), spec.startAngleDeg, 1e-3f)
        assertTrue(SmokeUi.has("upright along the bottom"))
        click("End")
        assertEquals(TextPathAlign.END, spec.align)

        type("Radius", "250")
        assertEquals(250f, spec.radius, 0.01f)
        type("Text position", "-45")
        assertEquals(-45f, spec.startAngleDeg, 0.01f)
        type("Offset", "12")
        assertEquals(12f, spec.offset, 0.01f)
        type("Baseline shift", "-8")
        assertEquals(-8f, spec.baselineShift, 0.01f)
        type("Center X", "321")
        assertEquals(321f, spec.cx, 0.01f)

        click("Square / rectangle")
        assertEquals(TextPathType.RECT, spec.type)
        type("Width", "300")
        assertEquals(300f, spec.width, 0.01f)
        type("Height", "180")
        assertEquals(180f, spec.height, 0.01f)
        click("Keep square")
        assertTrue(spec.keepSquare)
        assertEquals("keeping it square evens the sides", spec.width, spec.height, 0f)
        type("Width", "260")
        assertEquals(260f, spec.height, 0.01f)
        type("Corner radius", "500")
        assertEquals("corner radius is limited to half a side", 130f, spec.cornerRadius, 0.01f)
        type("Rotation", "30")
        assertEquals(30f, spec.rotationDeg, 0.01f)

        click("Curve")
        assertEquals(TextPathType.CURVE, spec.type)
        type("Control 1 X", "123")
        assertEquals(123f, spec.cx1, 0.01f)
        type("End Y", "456")
        assertEquals(456f, spec.y2, 0.01f)
        val before = spec
        click("Reverse direction")
        assertEquals(before.x2, spec.x1)
        assertEquals(before.cx1, spec.cx2)

        click("Line")
        assertEquals(TextPathType.LINE, spec.type)
        type("Start X", "10")
        assertEquals(10f, spec.x1, 0.01f)
        assertFalse("open paths have no side", SmokeUi.has("Side of the shape"))

        click("Straight")
        assertEquals(TextPathType.NONE, spec.type)
        SmokeUi.assertIdle("text path controls")
    }

    @Test
    fun handleDragsOnTheCanvasUpdateTheFieldsWithoutRecomposingForever() {
        val activity = newActivity()
        var spec by mutableStateOf(TextOnPath.defaultFor(TextPathType.CURVE, Vec2(300f, 300f), 300f, 40f, TextPathSpec()))
        activity.setContent {
            BrushworkTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { TextPathControls(spec, 300f) { spec = it } }
            }
        }
        settle()
        // Far outside the slider window: the window follows the value.
        repeat(5) { i ->
            spec = TextOnPath.moveHandle(spec, 1, Vec2(5000f + i * 700f, -3000f))
            settle(4)
            assertEquals(spec.cx1.toDouble(), SmokeUi.field("Control 1 X").text!!.toDouble(), 0.05)
        }
        SmokeUi.assertIdle("after handle drags")
    }

    private companion object {
        /** AndroidUiDispatcher's "a handler / frame callback is posted" flags. */
        val SCHEDULED_FLAGS = listOf("scheduledTrampolineDispatch", "scheduledFrameDispatch")
    }
}
