package com.brushwork.paint.qa

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
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
 * QA, the vector workflow through the real editor screen on the user's phone width (392 dp):
 * the Vector button, a stroke, the eraser's mode chips, the tools grid's "px" badges, a Lasso
 * loop, the Object bar, Transform from it with the X / Y strip and ✓, and Vector off again — each
 * control on screen (not cut off at the edge) and reachable without scrolling.
 *
 * Its own sandbox (Compose's frame clock only runs in the first test of one): one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa.vectoruisandbox"])
class VectorUiQaTest {

    @Test
    fun vectorWorkflowThroughTheEditorScreen() = VectorUiFlow.run(this)
}

/** The flow shared by the 392 dp and 360 dp classes. */
internal object VectorUiFlow {
    fun run(owner: Any) {
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
            assertTrue("\"$label\" fully on screen (${e.bounds}, width $width)", e.bounds.left >= 0f && e.bounds.right <= width + 0.5f && e.bounds.width > 0f)
        }

        // Vector on: Layer 2 (empty) becomes the vector layer in place.
        onScreen("Vector")
        click("Vector", exact = true)
        assertTrue(c.isVectorMode)
        assertEquals("Vector 2", c.activeLayer.name)
        val vec = c.activeLayer

        // A stroke through the window.
        c.color = 0xFF1A2A6C.toInt()
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f)
        touch.idle(300)
        touch.stroke(screen(40f, 80f), screen(240f, 90f), screen(440f, 80f))
        settle()
        touch.idle(300)
        touch.stroke(screen(40f, 200f), screen(240f, 210f), screen(440f, 200f))
        settle()
        assertEquals(2, vec.vector!!.objects.size)

        // The eraser's modes are the first chips of its strip, on screen.
        click("Switch to eraser")
        assertEquals(ToolId.ERASER, c.activeToolId)
        for (m in VectorEraseMode.entries) onScreen(m.label)
        click(VectorEraseMode.PARTIAL.label, exact = true)
        assertEquals(VectorEraseMode.PARTIAL, VectorEraserModes.mode(c))
        c.eraser = c.eraser.copy(size = 12f)
        touch.idle(300)
        touch.stroke(screen(240f, 50f), screen(240f, 110f))
        settle()
        assertEquals("the top stroke cut in two", 3, vec.vector!!.objects.size)
        click("Eraser on: switch to")
        assertEquals(ToolId.BRUSH, c.activeToolId)

        // The tools grid marks the pixel-only tools in vector mode; Lasso from it.
        click("Tools (current: Brush)")
        SmokeUi.assertPanelShown("Tools")
        val badges = com.brushwork.paint.ui.color.RobolectricUi.elements().count { e ->
            e.node.layoutInfo.isPlaced && e.node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) == com.brushwork.paint.ui.editor.chrome.PIXEL_ONLY_STATE
        }
        assertTrue("pixel-only cells: $badges", badges >= 1)
        assertFalse("the px badge is decoration (I10)", SmokeUi.has("px", exact = true))
        click("Lasso", exact = true)
        assertEquals(ToolId.LASSO, c.activeToolId)
        settle()

        // A lasso loop around the lower stroke: the Object bar, every button on screen.
        touch.idle(300)
        touch.stroke(screen(20f, 170f), screen(460f, 170f), screen(460f, 240f), screen(20f, 240f), screen(20f, 172f))
        assertTrue(Smoke.pumpUntil(10_000) { c.vectors.selectedIds.isNotEmpty() })
        settle()
        assertEquals(1, c.vectors.selectedIds.size)
        for (b in listOf("Delete", "Duplicate", "Forward", "Backward", "Front", "Back", "Recolor", "Transform", "Deselect")) onScreen(b)
        val steps = c.undoManager.undoCount
        click("Duplicate", exact = true)
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        settle()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(4, vec.vector!!.objects.size)

        // Transform from the Object bar: the strip shows Distort, the X / Y strip its sliders.
        click("Transform", exact = true)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        settle()
        onScreen("Distort")
        onScreen("X slider")
        onScreen("Y slider")
        t.setRotation(10.0); t.endNumericEdit()
        settle()
        onScreen("Apply transform edit", exact = false)
        click("Apply transform edit", exact = false)
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        settle()
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        assertTrue(vec.vector!!.objects.all { it is VStroke })

        // Deselect, then Vector off: back to Layer 1.
        if (has("Deselect", exact = true)) click("Deselect", exact = true)
        click("Vector", exact = true)
        assertFalse(c.isVectorMode)
        assertEquals("Layer 1", c.activeLayer.name)
        Smoke.assertQuiet(c, "end")
        assertTrue("scope errors ${Smoke.scopeErrors}", Smoke.scopeErrors.isEmpty())
        assertTrue("pixel-only tools are known", LayerToolRules.PIXEL_ONLY.isNotEmpty())
        runCatching { ctl.pause().stop().destroy() }
        c.dispose()
    }
}
