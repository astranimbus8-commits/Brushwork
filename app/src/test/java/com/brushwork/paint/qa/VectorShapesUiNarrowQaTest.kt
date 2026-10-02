package com.brushwork.paint.qa

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * QA at 360 dp through the editor screen, in vector mode: a shape dragged on the canvas, its ✓
 * on screen (one object), reopened by a tap with the Points chip and ✕ on screen; a curve tapped
 * point by point and confirmed with its ✓. Its own sandbox: one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.qa.vectorshapesuisandbox"])
class VectorShapesUiNarrowQaTest {

    @Test
    fun shapeAndCurveConfirmedThroughTheScreenAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        Smoke.scopeErrors.clear()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = ctl.get()
        val c = Smoke.controller(activity, Smoke.document(480, 320, 2, whiteBottom = true))
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        c.tools
        c.snapping.enabled = false
        settle()
        val root = activity.window.decorView
        val width = root.width.toFloat()
        val height = root.height.toFloat()
        val touch = Smoke.Touch(root)
        val canvas = Smoke.find(root, CanvasView::class.java) ?: throw AssertionError("no canvas")
        fun screen(x: Float, y: Float): Pair<Float, Float> {
            val loc = IntArray(2)
            canvas.getLocationInWindow(loc)
            val p = c.viewTransform.docToScreen(x, y)
            return (p.x + loc[0]) to (p.y + loc[1])
        }
        fun onScreen(label: String, exact: Boolean = true) {
            val e = SmokeUi.find(label, exact) ?: throw AssertionError("\"$label\" not shown; shown: ${SmokeUi.shown().take(80)}")
            assertTrue("\"$label\" on screen (${e.bounds}, $width x $height)", e.bounds.left >= 0f && e.bounds.right <= width + 0.5f && e.bounds.top >= 0f && e.bounds.bottom <= height + 0.5f)
        }

        click("Vector", exact = true)
        assertTrue(c.isVectorMode)
        val vec = c.activeLayer

        // A shape: dragged, ✓ on screen.
        c.selectTool(ToolId.SHAPE)
        settle()
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 5f, fillColor = 0xFF40B060.toInt()) }
        touch.idle(300)
        touch.stroke(screen(100f, 80f), screen(180f, 140f), screen(260f, 200f))
        settle()
        assertTrue(shape.hasPendingWork)
        onScreen("Apply shape edit", exact = false)
        click("Apply shape edit", exact = false)
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        settle()
        assertEquals(ShapeTool.SHAPE_LABEL, c.undoManager.undoLabel)
        assertTrue(vec.vector!!.objects.single() is VShape)

        // Reopened by a tap: Points and ✕ on screen; ✕ changes nothing.
        touch.idle(300)
        screen(180f, 140f).let { touch.tap(it.first, it.second) }
        settle()
        assertTrue("reopened", shape.editingObject)
        onScreen("Points")
        click("Points", exact = true)
        assertTrue(shape.pointsMode)
        onScreen("Discard shape edit", exact = false)
        val steps = c.undoManager.undoCount
        click("Discard shape edit", exact = false)
        settle()
        assertFalse(shape.editingObject)
        assertEquals(steps, c.undoManager.undoCount)
        shape.setPointEditing(false)

        // A curve: three taps, ✓ on screen.
        c.selectTool(ToolId.CURVE)
        settle()
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        for ((x, y) in listOf(60f to 260f, 200f to 300f, 420f to 250f)) {
            touch.idle(300)
            screen(x, y).let { touch.tap(it.first, it.second) }
            settle()
        }
        assertEquals(3, curve.anchors.size)
        onScreen("Apply curve edit", exact = false)
        click("Apply curve edit", exact = false)
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        settle()
        assertEquals("Curve", c.undoManager.undoLabel)
        assertTrue(vec.vector!!.objects.last() is VPath)
        Smoke.assertQuiet(c, "end")
        assertTrue("scope errors ${Smoke.scopeErrors}", Smoke.scopeErrors.isEmpty())
        runCatching { ctl.pause().stop().destroy() }
        c.dispose()
    }
}
