package com.brushwork.paint.ui.vector

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Object bar (v1.5 A2) in a real activity at the user's phone width: all nine actions are
 * there with finger-sized buttons, each edit is one undo step, Recolor's long press paints the
 * lines only, and the bar goes away with the selection (also when another layer is selected).
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.objectbarsandbox"])
class VectorObjectBarUiRobolectricTest {

    @Test
    fun theBarActsOnTheSelectedObjects() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val kit = ObjectTestKit(400, 300)
        val doc = Document("bar", "Bar", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(400, 300))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        val c = Smoke.controller(activity, doc)
        c.viewTransform.set(Matrix())
        val layer = doc.layers[1]
        c.vectors.addObjects(layer, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(60f, 40f, 160f, 140f), kit.box(250f, 150f, 330f, 230f)), "Add")
        c.vectors.setSelection(layer, setOf(1L, 2L))
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxWidth()) {
                    if (c.vectors.selectedIds.isNotEmpty()) VectorObjectBar(c, Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        SmokeUi.settle()
        for (label in listOf("Delete", "Duplicate", "Forward", "Backward", "Front", "Back", "Recolor", "Transform", "Deselect")) {
            assertTrue("\"$label\" shown", SmokeUi.has(label, exact = true))
        }
        assertTrue(SmokeUi.has("2 objects selected", exact = true))
        // Finger-sized buttons, all nine on the 392 dp screen (no scrolling to reach Deselect),
        // every label whole (long words are set smaller, never clipped).
        val density = activity.resources.displayMetrics.density
        val screen = activity.resources.displayMetrics.widthPixels
        val buttons = listOf(
            "Delete the selected objects", "Duplicate the selected objects", "Bring forward", "Send backward", "Bring to front",
            "Send to back", "Recolor with the main color", "Transform the selected objects", "Deselect the objects",
        ).map { clickableOf(it) }
        for (b in buttons) assertTrue(b.bounds.width >= 40 * density - 1 && b.bounds.height >= 46 * density - 1)
        assertTrue("Deselect is on screen (${buttons.last().bounds.right} of $screen)", buttons.last().bounds.right <= screen - 8 * density + 1)
        for (i in 1 until buttons.size) assertTrue(buttons[i - 1].bounds.right <= buttons[i].bounds.left + 1)
        for (label in listOf("Delete", "Duplicate", "Forward", "Backward", "Front", "Back", "Recolor", "Transform", "Deselect")) {
            val node = RobolectricUi.byText(label).node
            val layouts = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            assertTrue("\"$label\" laid out", layouts.isNotEmpty())
            assertFalse("\"$label\" fits its button", layouts[0].didOverflowWidth)
            assertEquals(1, layouts[0].lineCount)
        }

        // Duplicate: one step, the copies are selected.
        var steps = c.undoManager.undoCount
        SmokeUi.click("Duplicate the selected objects")
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), layer.vector!!.objects.map { it.id })
        assertEquals(setOf(4L, 5L), c.vectors.selectedIds)

        // Send to back, then undo it.
        steps = c.undoManager.undoCount
        SmokeUi.click("Send to back")
        assertEquals(listOf(4L, 5L, 1L, 2L, 3L), layer.vector!!.objects.map { it.id })
        c.undo()
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), layer.vector!!.objects.map { it.id })

        // Recolor: a long press paints the lines only.
        c.color = 0xFF10A040.toInt()
        val recolor = clickableOf("Recolor with the main color")
        val longPress = recolor.node.config.getOrNull(SemanticsActions.OnLongClick)
        assertEquals("Recolor the lines only", longPress?.label)
        longPress!!.action!!.invoke()
        SmokeUi.settle()
        assertEquals(ObjectActions.RECOLOR_LINES_LABEL, c.undoManager.undoLabel)
        val copyBox = layer.vector!!.byId(5) as VPath
        assertEquals(0xFF10A040.toInt(), copyBox.stroke!!.color)
        assertEquals(0xFF10A040.toInt(), (layer.vector!!.byId(4) as VStroke).color)
        assertFalse("the fill stays", copyBox.fill == com.brushwork.paint.vector.VPaint.Solid(0xFF10A040.toInt()))
        SmokeUi.click("Recolor with the main color")
        assertEquals(ObjectActions.RECOLOR_LABEL, c.undoManager.undoLabel)
        assertEquals(com.brushwork.paint.vector.VPaint.Solid(0xFF10A040.toInt()), (layer.vector!!.byId(5) as VPath).fill)

        // Transform lifts the selection.
        SmokeUi.click("Transform the selected objects")
        Smoke.pump()
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        assertNotNull(VectorLift.activeLift(c))
        assertEquals(setOf(4L, 5L), VectorLift.activeLift(c)!!.ids)

        // Delete: the objects go, so does the bar.
        SmokeUi.click("Delete the selected objects")
        assertEquals(listOf(1L, 2L, 3L), layer.vector!!.objects.map { it.id })
        assertTrue(c.vectors.selectedIds.isEmpty())
        SmokeUi.settle()
        assertFalse(SmokeUi.has("Deselect", exact = true))

        // Selecting another layer drops the object selection.
        c.currentTool.discard()
        c.vectors.setSelection(layer, setOf(3L))
        SmokeUi.settle()
        assertTrue(SmokeUi.has("1 object selected", exact = true))
        c.selectLayer(doc.layers[0])
        SmokeUi.settle()
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertFalse(SmokeUi.has("Deselect", exact = true))

        // Deselect.
        c.selectLayer(layer)
        c.vectors.setSelection(layer, setOf(3L))
        SmokeUi.settle()
        SmokeUi.click("Deselect the objects")
        assertTrue(c.vectors.selectedIds.isEmpty())
        kit.close()
    }

    /** The clickable node whose click label is [clickLabel]. */
    private fun clickableOf(clickLabel: String): RobolectricUi.Element =
        RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == clickLabel }
}
