package com.brushwork.paint.tools.text

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * While the text editor is open (its sheet can be minimized to see the canvas), the text can
 * still be dragged, resized and pinched; taps neither apply it nor start another text, and
 * Cancel takes back everything done meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
class TextEditorOpenGesturesRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theTextCanBeMovedAndPinchedWhileTheEditorIsOpen() {
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool

        tool.startTextAt(150f, 100f)
        tool.setText("Move")
        tool.updateSpec { it.copy(sizePx = 30f) }
        tool.confirmEditor()
        tool.openEditor()
        assertTrue(tool.editorOpen)

        // Drag the text.
        c.pointerDown(ToolPoint(150f, 100f))
        c.pointerMove(ToolPoint(170f, 110f))
        c.pointerMove(ToolPoint(190f, 120f))
        c.pointerUp(ToolPoint(190f, 120f))
        assertEquals(190f, tool.item!!.cx, 0.01f)
        assertEquals(120f, tool.item!!.cy, 0.01f)
        assertTrue(tool.editorOpen)

        // Taps (on the text or away from it) don't apply it or start another text.
        c.pointerDown(ToolPoint(20f, 20f))
        c.pointerUp(ToolPoint(20f, 20f))
        c.pointerDown(ToolPoint(190f, 120f))
        c.pointerUp(ToolPoint(190f, 120f))
        assertTrue(tool.editorOpen)
        assertEquals(1, c.doc.layers.size)
        assertEquals("Move", tool.item!!.text)
        assertEquals(190f, tool.item!!.cx, 0.01f)

        // Two fingers on the text scale it.
        assertTrue(c.twoFingerStart(Vec2(190f, 120f), Vec2(175f, 120f), Vec2(205f, 120f)))
        c.twoFingerGesture(Vec2.ZERO, 2f, 0f)
        c.twoFingerEnd(false)
        assertEquals(60f, tool.item!!.spec.sizePx, 0.01f)

        // Cancel: back to how the text was when the editor opened.
        tool.cancelEditor()
        assertEquals(150f, tool.item!!.cx, 0.01f)
        assertEquals(30f, tool.item!!.spec.sizePx, 0.01f)
        // A new text that is cancelled is removed.
        tool.discard()
        tool.startTextAt(100f, 100f)
        tool.setText("New")
        c.pointerDown(ToolPoint(100f, 100f))
        c.pointerMove(ToolPoint(130f, 100f))
        c.pointerUp(ToolPoint(130f, 100f))
        assertEquals(130f, tool.item!!.cx, 0.01f)
        tool.cancelEditor()
        assertNull(tool.item)
    }
}
