package com.brushwork.paint.ui.pathfinder

import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.OptionsStripPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 (§3.20, area G): the Pathfinder options in the editor's own options strip (44 dp tall) at
 * the user's phone width. The hint comes first and is on screen without scrolling; the ten
 * operations are buttons at least 56 dp wide and as tall as the strip, their icon and short
 * visible name inside it, each announced by its unique description (I10), disabled until 2
 * objects are picked; "Select all objects" picks them and the count shows; "Unite shapes"
 * combines them into "Pathfinder 1" as one step, and the hint is back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.pathfinder.pathfinderoptionssandbox"])
class PathfinderOptionsUiRobolectricTest {
    private fun shapeLayer(c: EditorController, l: Float, t: Float, r: Float, b: Float, color: Int) {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = (l + r) / 2f, cy = (t + b) / 2f, w = r - l, h = b - t, style = ShapeStyle.FILL, fillColor = color)
        c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { canvas ->
            canvas.drawRect(l, t, r, b, Paint().apply { this.color = color })
        }!!
    }

    /** [e]'s whole box in the window (not clipped by the scrolling strip). */
    private fun box(e: RobolectricUi.Element): Rect {
        val p = e.node.positionInWindow
        return Rect(p.x, p.y, p.x + e.node.size.width, p.y + e.node.size.height)
    }

    @Test
    fun theStripPicksAndCombines() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(300, 200, layers = 1))
        val density = activity.resources.displayMetrics.density
        shapeLayer(c, 20f, 20f, 100f, 100f, 0xFFDD2211.toInt())
        shapeLayer(c, 70f, 20f, 150f, 100f, 0xFF2244CC.toInt())
        c.selectTool(ToolId.PATHFINDER)
        val tool = c.currentTool as PathfinderTool
        tool.computeDispatcher = Dispatchers.Unconfined
        // Hosted as the editor hosts it (EditorScreen): the strip panel, full width less its sides.
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    OptionsStripPanel(c, Modifier.fillMaxWidth().padding(horizontal = IbisDims.OptionsStripSide))
                }
            }
        }
        SmokeUi.settle()
        val strip = box(RobolectricUi.elements().last { it.node.config.getOrNull(SemanticsProperties.TestTag) == ChromeTags.OPTIONS_STRIP })
        assertEquals("the strip", IbisDims.OptionsStripHeight.value, strip.height / density, 0.5f)

        // The hint first, on a 392 dp screen without scrolling.
        val hint = SmokeUi.find(PathfinderLabels.HINT, exact = true) ?: throw AssertionError("no hint; shown: ${SmokeUi.shown()}")
        assertTrue("the hint is on screen", hint.bounds.right <= 392f * density + 1f && hint.bounds.left >= 0f)
        assertTrue(SmokeUi.has(PathfinderLabels.SELECT_ALL, exact = true))

        // Ten buttons at least 56 dp wide, as tall as the strip, announced by their unique
        // descriptions, disabled without picks; their short names show inside them.
        val descriptions = PathfinderOp.entries.map { it.description }
        assertEquals(10, descriptions.toSet().size)
        assertEquals(PathfinderLabels.OUTLINE, PathfinderOp.OUTLINE.description)
        for (op in PathfinderOp.entries) {
            val e = SmokeUi.find(op.description, exact = true) ?: throw AssertionError("no ${op.description}")
            val b = box(e)
            assertEquals("${op.description}: as tall as the strip", IbisDims.OptionsStripHeight.value, b.height / density, 1f)
            assertTrue("${op.description}: inside the strip, top to bottom", b.top >= strip.top - 1f && b.bottom <= strip.bottom + 1f)
            assertTrue("${op.description}: at least 56 dp wide", b.width / density >= 55.5f)
            assertFalse("${op.description}: needs 2 objects", SmokeUi.isEnabled(op.description))
            val name = SmokeUi.find(op.label, exact = true) ?: throw AssertionError("no visible name ${op.label}")
            val n = box(name)
            assertTrue("${op.label}: the name inside its button ($n in $b)", n.top >= b.top - 1f && n.bottom <= b.bottom + 1f && n.left >= b.left - 1f && n.right <= b.right + 1f)
        }

        // Select all: the count, and the operations come alive.
        SmokeUi.click(PathfinderLabels.SELECT_ALL, exact = true)
        assertEquals(2, tool.count)
        assertTrue(SmokeUi.has(PathfinderTool.picked(2), exact = true))
        assertFalse(SmokeUi.has(PathfinderLabels.HINT, exact = true))
        for (d in descriptions) assertTrue(d, SmokeUi.isEnabled(d))

        // Unite: one step, the result layer, and the hint is back.
        val steps = c.undoManager.undoCount
        SmokeUi.click(PathfinderOp.UNITE.description, exact = true)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.pathfinder("Unite"), c.undoManager.undoLabel)
        assertEquals(listOf("Layer 1", PathfinderLabels.resultLayer(1)), c.doc.layers.map { it.name })
        assertTrue(SmokeUi.has(PathfinderLabels.HINT, exact = true))
        assertFalse(SmokeUi.isEnabled(PathfinderOp.UNITE.description))
        Smoke.assertQuiet(c, "pathfinder options")
    }
}
