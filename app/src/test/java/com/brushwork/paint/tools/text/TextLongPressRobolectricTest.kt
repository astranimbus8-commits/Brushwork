package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The long-press color pick of the text tool (controller.pointerLongPress) must not take over a
 * finger that rests on the pending text before dragging it; away from the text it still picks.
 */
@RunWith(RobolectricTestRunner::class)
class TextLongPressRobolectricTest {
    private lateinit var c: EditorController
    private lateinit var text: TextTool

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        val layer = Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        Canvas(layer.bitmap).drawRect(0f, 0f, 400f, 300f, Paint().apply { color = BLUE })
        doc.layers += layer
        c = EditorController(app, doc, CoroutineScope(Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.color = RED
        c.selectTool(ToolId.TEXT)
        text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(200f, 150f)
        text.setText("Hello")
        text.confirmEditor()
        assertNotNull(text.item)
    }

    @Test
    fun holdingOnTheTextThenDraggingMovesIt() {
        val start = text.item!!
        c.pointerDown(ToolPoint(200f, 150f))
        assertTrue("the text tool keeps the long press", c.pointerLongPress(ToolPoint(200f, 150f)))
        assertFalse("no color pick on the text", c.holdPicking)
        c.pointerMove(ToolPoint(230f, 160f))
        c.pointerMove(ToolPoint(260f, 170f))
        c.pointerUp(ToolPoint(260f, 170f))
        val moved = text.item!!
        assertEquals(start.cx + 60f, moved.cx, 1e-3f)
        assertEquals(start.cy + 20f, moved.cy, 1e-3f)
        assertEquals("the drawing color stays", RED, c.color)
        assertEquals(ToolId.TEXT, c.activeToolId)
        assertFalse(text.editorOpen)
    }

    @Test
    fun holdingOnTheResizeHandleThenDraggingResizes() {
        val start = text.item!!
        val block = text.blockFor(start)
        val corner = start.corners(block.width, block.height, 6f)[2] // the resize handle (BR)
        c.pointerDown(ToolPoint(corner.x, corner.y))
        assertTrue(c.pointerLongPress(ToolPoint(corner.x, corner.y)))
        assertFalse(c.holdPicking)
        c.pointerMove(ToolPoint(corner.x + 40f, corner.y + 20f))
        c.pointerUp(ToolPoint(corner.x + 40f, corner.y + 20f))
        assertTrue("the text grew", text.item!!.spec.sizePx > start.spec.sizePx)
        assertEquals(RED, c.color)
    }

    @Test
    fun holdingAwayFromTheTextStillPicksAColor() {
        val start = text.item!!
        c.pointerDown(ToolPoint(30f, 30f))
        assertTrue(c.pointerLongPress(ToolPoint(30f, 30f)))
        assertTrue("away from the text: color pick", c.holdPicking)
        c.pointerUp(ToolPoint(30f, 30f))
        assertFalse(c.holdPicking)
        assertEquals(BLUE, c.color)
        assertEquals("the pending text is untouched", start, text.item)
        assertEquals(ToolId.TEXT, c.activeToolId)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
