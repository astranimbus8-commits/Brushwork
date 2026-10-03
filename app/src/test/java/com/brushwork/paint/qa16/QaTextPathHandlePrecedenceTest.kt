package com.brushwork.paint.qa16

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextOnPath
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.6 final QA (§3.4 "object guide, then grid, then increment, per axis") for a text path's
 * point handles (the Text tool's PATH_HANDLE drag), as for Curve and Path points: with increments
 * on (Length 10 px) an axis an object guide or the grid placed keeps that place even when the
 * finger is exactly on the guide or grid line; any other axis moves by whole Length steps from
 * where the handle was.
 *
 * (Before the fix the drag told "snapped" from "free" by comparing the snapped coordinate with the
 * finger's, so a finger exactly on the canvas's center line was stepped off it to x 203, and one
 * exactly on a grid intersection to (173, 270) instead of the grid's (176, 272).)
 */
@RunWith(RobolectricTestRunner::class)
class QaTextPathHandlePrecedenceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    /** A 400 × 300 canvas at zoom 1; a text on a line path (250, 200) – (353, 260); Length 10 on. */
    private fun setUp(grid: Boolean): Pair<EditorController, TextTool> {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(400, 300)) }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(300f, 230f)
        tool.setText("Hi")
        tool.setSizePx(30f)
        tool.confirmEditor()
        tool.setPath(TextPathSpec(type = TextPathType.LINE))
        tool.setPath(tool.item!!.path.copy(x1 = 250f, y1 = 200f, x2 = 353f, y2 = 260f))
        assertEquals("the end handle", Vec2(353f, 260f), TextOnPath.handles(tool.item!!.path)[1])
        c.snapping.enabled = true
        if (grid) c.updateGrid(GridSettings(enabled = true, spacingPx = 16f, snap = true))
        c.increments.update { it.copy(enabled = true).with(IncrementKind.LENGTH, 10f) }
        return c to tool
    }

    private fun dragEndTo(c: EditorController, to: Vec2) {
        c.pointerDown(ToolPoint(353f, 260f))
        c.pointerMove(ToolPoint(340f, 262f))
        c.pointerMove(ToolPoint(to.x, to.y))
        c.pointerUp(ToolPoint(to.x, to.y))
    }

    private fun TextTool.end(): Vec2 = item!!.path.let { Vec2(it.x2, it.y2) }

    @Test
    fun aFingerExactlyOnAGuideKeepsTheGuidePlace() {
        val (c, tool) = setUp(grid = false)
        // x = 200: the canvas's center line; y = 263: no line near → whole steps (+3 → 0).
        dragEndTo(c, Vec2(200f, 263f))
        assertEquals("the guide placed x", 200f, tool.end().x, 1e-3f)
        assertEquals("y steps", 260f, tool.end().y, 1e-3f)
    }

    @Test
    fun aFingerExactlyOnAGridIntersectionKeepsTheGridPlace() {
        val (c, tool) = setUp(grid = true)
        dragEndTo(c, Vec2(176f, 272f))
        assertEquals("the grid placed x", 176f, tool.end().x, 1e-3f)
        assertEquals("the grid placed y", 272f, tool.end().y, 1e-3f)
    }

    @Test
    fun offTheLinesTheGridStillWinsAndElseTheStep() {
        val (c, tool) = setUp(grid = true)
        dragEndTo(c, Vec2(179f, 275f))
        assertEquals("grid", Vec2(176f, 272f), tool.end())
        val (c2, tool2) = setUp(grid = false)
        // −176, +17 → −180, +20 (no line within reach of (177, 277)).
        dragEndTo(c2, Vec2(177f, 277f))
        assertEquals("steps", Vec2(173f, 280f), tool2.end())
    }
}
