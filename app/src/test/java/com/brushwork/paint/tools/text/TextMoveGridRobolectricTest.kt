package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.roundToInt

/**
 * Moving a text with the Text tool on a snapping grid (v1.6 integration, item 16; §3.4 gesture
 * precedence per axis: an object's guide, then the grid, then the increment). With increments on,
 * an axis no guide holds puts the text box's top-left corner on the nearest grid point (as a
 * text frame's move does) and falls back to Length steps only when grid snapping is off. With
 * increments off the move is exactly v1.5's (guides only, no grid: I8).
 */
@RunWith(RobolectricTestRunner::class)
class TextMoveGridRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private val spacing = 50f

    /** A 600 x 400 canvas at zoom 1; "Layer 1" has content at (100, 60)-(160, 120); a 50 px square grid that snaps. */
    private fun setup(increments: Boolean): Pair<EditorController, TextTool> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 600, 400)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(600, 400)) }
        doc.activeLayerIndex = 1
        Canvas(doc.layers[0].bitmap).drawRect(Rect(100, 60, 160, 120), Paint().apply { color = 0xFF000000.toInt() })
        doc.layers[0].markChanged()
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.snapping.enabled = false
        c.updateGrid(c.grid.copy(enabled = true, snap = true, type = GridType.SQUARE, spacingPx = spacing))
        c.selectTool(ToolId.TEXT)
        if (increments) c.increments.update { it.copy(enabled = true, lengthPx = 10f) }
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun text(tool: TextTool, x: Float, y: Float) {
        tool.startTextAt(x, y)
        tool.setText("Hello there")
        tool.setSizePx(30f)
        tool.confirmEditor()
    }

    private fun drag(c: EditorController, from: Vec2, vararg to: Vec2) {
        c.pointerDown(ToolPoint(from.x, from.y))
        for (p in to) c.pointerMove(ToolPoint(p.x, p.y))
    }

    private fun onGrid(v: Float): Float = (v / spacing).roundToInt() * spacing

    /** The pending text's box: left, top (unrotated text: centred on the item). */
    private fun topLeft(tool: TextTool): Vec2 {
        val t = tool.item!!
        val b = tool.blockFor(t)
        return Vec2(t.cx - b.width / 2f, t.cy - b.height / 2f)
    }

    @Test
    fun withIncrementsOnTheGridComesBeforeTheStep() {
        val (c, tool) = setup(increments = true)
        text(tool, 300f, 200f)
        val tl = topLeft(tool)
        // The finger travels (37, 41): steps would give (40, 40); the grid puts the corner on (·50, ·50).
        val dx = onGrid(tl.x + 37f) - tl.x
        val dy = onGrid(tl.y + 41f) - tl.y
        assertNotEquals("the case is discriminating", 40f, dx, 0.5f)
        drag(c, Vec2(300f, 200f), Vec2(320f, 220f), Vec2(337f, 241f))
        assertEquals("the left edge on a grid line", onGrid(tl.x + 37f), topLeft(tool).x, 1e-3f)
        assertEquals("the top edge on a grid line", onGrid(tl.y + 41f), topLeft(tool).y, 1e-3f)
        assertEquals(300f + dx, tool.item!!.cx, 1e-3f)
        assertEquals(200f + dy, tool.item!!.cy, 1e-3f)
        assertNotNull("the readout shows the travel", c.increments.readout)
        c.pointerUp(ToolPoint(337f, 241f))

        // Grid snapping off: Length steps from the start again.
        c.updateGrid(c.grid.copy(snap = false))
        val x0 = tool.item!!.cx
        val y0 = tool.item!!.cy
        drag(c, Vec2(x0, y0), Vec2(x0 + 13.7f, y0 + 7.3f))
        assertEquals(x0 + 10f, tool.item!!.cx, 1e-3f)
        assertEquals(y0 + 10f, tool.item!!.cy, 1e-3f)
        c.pointerUp(ToolPoint(x0 + 13.7f, y0 + 7.3f))
    }

    @Test
    fun aGuideBeatsTheGrid() {
        val (c, tool) = setup(increments = true)
        c.snapping.enabled = true
        text(tool, 300f, 300f)
        val block = tool.blockFor(tool.item!!)
        val tl = topLeft(tool)
        // The left side 3 px right of the layer's right edge (160): X snaps there; Y goes onto the grid.
        val target = 163f + block.width / 2f
        drag(c, Vec2(300f, 300f), Vec2(290f, 304f), Vec2(target, 303f))
        assertEquals("the guide holds X", 160f + block.width / 2f, tool.item!!.cx, 1e-3f)
        assertEquals("Y puts the top edge on the grid", onGrid(tl.y + 3f), topLeft(tool).y, 1e-3f)
        c.pointerUp(ToolPoint(target, 303f))
    }

    @Test
    fun withIncrementsOffTheMoveIgnoresTheGridAsInV15() {
        val (c, tool) = setup(increments = false)
        text(tool, 300f, 200f)
        drag(c, Vec2(300f, 200f), Vec2(337f, 241f))
        assertEquals(337f, tool.item!!.cx, 0f)
        assertEquals(241f, tool.item!!.cy, 0f)
        assertNull("no readout while increments are off", c.increments.readout)
        c.pointerUp(ToolPoint(337f, 241f))
    }
}
