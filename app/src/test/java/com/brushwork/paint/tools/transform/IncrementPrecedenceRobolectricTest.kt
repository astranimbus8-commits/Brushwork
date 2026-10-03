package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.6 §3.4 (d) IncrementPrecedenceRobolectricTest (area G, on Transform): per axis, a guide that
 * engages beats the grid, which beats the increment; an axis nothing snaps to moves by multiples
 * of the Length step from where the drag started. With increments off the same drags land
 * exactly where v1.5 put them (I8).
 *
 * 500 x 400 canvas, zoom 1 and density 1 (the snap distance is 8 document px). "Layer 1" (below)
 * has content at (300, 100)-(400, 160); the active layer a 40 x 20 block at (20, 20). The grid
 * snaps every 50 px from 44 (lines at 44, 94, …, 294, 344 on both axes).
 */
@RunWith(RobolectricTestRunner::class)
class IncrementPrecedenceRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setup(): Pair<EditorController, TransformTool> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 500, 400)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(500, 400)) }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        Canvas(doc.layers[0].bitmap).drawRect(Rect(300, 100, 400, 160), Paint().apply { color = BLUE })
        doc.layers[0].markChanged()
        Canvas(doc.layers[1].bitmap).drawRect(Rect(20, 20, 60, 40), Paint().apply { color = RED })
        doc.layers[1].markChanged()
        c.updateGrid(GridSettings(enabled = true, spacingPx = 50f, offsetXPx = 44f, offsetYPx = 44f, snap = true))
        c.selectTool(ToolId.TRANSFORM)
        shadowOf(Looper.getMainLooper()).idle()
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertEquals(DocBox(20f, 20f, 60f, 40f), t.transformState!!.bounds())
        assertTrue(t.snapToObjects)
        return c to t
    }

    /** Drags the box from its middle by (dx, dy) in two moves and lifts; the box it ended at. */
    private fun dragBy(c: EditorController, t: TransformTool, dx: Float, dy: Float): DocBox {
        val b = t.transformState!!.bounds()
        val x = (b.left + b.right) / 2f
        val y = (b.top + b.bottom) / 2f
        c.pointerDown(ToolPoint(x, y))
        c.pointerMove(ToolPoint(x + dx / 2f, y + dy / 2f))
        c.pointerMove(ToolPoint(x + dx, y + dy))
        c.pointerUp(ToolPoint(x + dx, y + dy))
        return t.transformState!!.bounds()
    }

    @Test
    fun aGuideBeatsTheGridWhichBeatsTheStep() {
        val (c, t) = setup()
        c.increments.update { it.copy(enabled = true, lengthPx = 10f) }

        // X: raw left 297 is 3 px from Layer 1's left (300) and 3 px from the grid line 294: the
        // object guide wins (ties go to the object). Y: raw top 54 is 10 px from the grid line
        // 44 (out of reach): the step, +30.
        assertEquals(DocBox(300f, 50f, 340f, 70f), dragBy(c, t, 277f, 34f))

        // X: raw left 197 is 3 px from the grid line 194 and nothing else is in reach: the grid
        // beats the step (which would give 200).
        t.reset()
        assertEquals(DocBox(194f, 50f, 234f, 70f), dragBy(c, t, 177f, 34f))

        // Nothing in reach (raw left 114, center 134, right 154): the step, +90 / +30.
        t.reset()
        assertEquals(DocBox(110f, 50f, 150f, 70f), dragBy(c, t, 94f, 34f))

        // While a drag is stepped the readout says how far; it goes when the finger lifts.
        t.reset()
        c.pointerDown(ToolPoint(40f, 30f))
        c.pointerMove(ToolPoint(80f, 40f))
        c.pointerMove(ToolPoint(134f, 64f))
        assertEquals("+90 px, +30 px", c.increments.readout)
        c.pointerUp(ToolPoint(134f, 64f))
        assertNull(c.increments.readout)

        // Snapping off: neither guides nor the grid (as in v1.5), only the step.
        t.reset()
        t.snapToObjects = false
        assertEquals(DocBox(300f, 50f, 340f, 70f), dragBy(c, t, 277f, 34f))
        t.reset()
        assertEquals(DocBox(200f, 50f, 240f, 70f), dragBy(c, t, 177f, 34f))
    }

    @Test
    fun withIncrementsOffTheDragsAreTheV15Ones() {
        val (c, t) = setup()
        assertTrue(!c.increments.enabled)
        // The same drags: the guide, the grid, else exactly where the finger puts it.
        assertEquals(DocBox(300f, 54f, 340f, 74f), dragBy(c, t, 277f, 34f))
        t.reset()
        assertEquals(DocBox(194f, 54f, 234f, 74f), dragBy(c, t, 177f, 34f))
        t.reset()
        assertEquals(DocBox(114f, 54f, 154f, 74f), dragBy(c, t, 94f, 34f))
        assertNull("no readout without increments", c.increments.readout)
        // The steps set but switched off change nothing either.
        c.increments.update { it.copy(enabled = false, lengthPx = 7f) }
        t.reset()
        assertEquals(DocBox(114f, 54f, 154f, 74f), dragBy(c, t, 94f, 34f))
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
