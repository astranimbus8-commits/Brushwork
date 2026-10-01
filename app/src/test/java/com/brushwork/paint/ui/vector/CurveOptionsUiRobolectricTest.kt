package com.brushwork.paint.ui.vector

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * v1.5 §4.4 / §4.5 (A4): the curve options strip at the user's phone size. The width chip shows
 * the brush size (linked); with a point selected its thickness control shows first, within the
 * screen: the slider (one undo step per drag), the ‹ › steps, the typed value, a double tap back
 * to 100 % and "All points 100 %".
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.curveoptionssandbox"])
class CurveOptionsUiRobolectricTest {

    private fun slider(name: String): RobolectricUi.Element = RobolectricUi.elements().last { e ->
        e.node.config.contains(SemanticsActions.SetProgress) &&
            e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
    }

    @Test
    fun theWidthChipAndThePointThicknessControl() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.tools
        c.brush = c.brush.copy(size = 12f)
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        activity.setContent { BrushworkTheme { ToolOptionsBar(c, Modifier.fillMaxWidth()) } }
        settle()
        // Linked: the chip shows the brush size.
        assertTrue("width chip: ${SmokeUi.shown()}", SmokeUi.has("12 px", exact = true))
        c.brush = c.brush.copy(size = 20f)
        settle()
        assertTrue(SmokeUi.has("20 px", exact = true))
        assertFalse("no point selected: no thickness control", SmokeUi.has("Point thickness slider"))

        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
        tool.select(1)
        settle()
        val s = slider("Point thickness slider")
        val screenW = activity.window.decorView.width
        assertTrue("visible without scrolling: ${s.bounds} in $screenW", s.bounds.right <= screenW)
        assertTrue(SmokeUi.has("Point thickness 100 %"))

        // The slider: one in-tool step.
        requireNotNull(s.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(250f)
        settle(4)
        assertEquals(2.5f, tool.anchors[1].width, 1e-4f)
        assertFalse("the ring goes with the release", tool.thicknessRing)
        assertTrue(SmokeUi.has("Point thickness 250 %"))
        assertTrue(tool.undoStep())
        assertEquals(1f, tool.anchors[1].width, 0f)

        // ‹ ›: 5 % per press.
        RobolectricUi.byDescription("Thicker point").tap()
        assertEquals(1.05f, tool.anchors[1].width, 1e-4f)
        RobolectricUi.byDescription("Thinner point").tap()
        RobolectricUi.byDescription("Thinner point").tap()
        assertEquals(0.95f, tool.anchors[1].width, 1e-4f)

        // Typed.
        SmokeUi.click("Type the point thickness")
        assertTrue(SmokeUi.has("Point thickness", exact = true))
        SmokeUi.typeAndDone("Thickness", "40")
        assertEquals(0.4f, tool.anchors[1].width, 1e-4f)

        // A double tap on the slider: back to 100 %.
        val b = slider("Point thickness slider")
        val x = b.bounds.left + b.bounds.width * 0.8f
        val y = b.bounds.center.y
        val t0 = SystemClock.uptimeMillis()
        fun send(down: Long, at: Long, action: Int) {
            val ev = MotionEvent.obtain(down, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            b.window.dispatchTouchEvent(ev)
            ev.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        }
        send(t0, t0, MotionEvent.ACTION_DOWN)
        send(t0, t0 + 40, MotionEvent.ACTION_UP)
        send(t0 + 120, t0 + 120, MotionEvent.ACTION_DOWN)
        send(t0 + 120, t0 + 160, MotionEvent.ACTION_UP)
        settle(4)
        assertEquals(1f, tool.anchors[1].width, 0f)

        // "All points 100 %".
        tool.setWidth(0, 2f)
        settle()
        SmokeUi.click("All points 100 %")
        assertTrue(CurveGeometry.isUniformWidth(tool.anchors))
        assertFalse(SmokeUi.has("All points 100 %"))
        assertFalse(c.canUndo)
    }
}
