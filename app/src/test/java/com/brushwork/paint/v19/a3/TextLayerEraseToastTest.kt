package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.TextLabels19
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * v1.9 A3 step 8 (C13): erasing on a text layer turns it into a regular layer as in v1.8, in
 * one undoable step; for a layer of several texts the message says how many texts can no longer
 * be edited ([TextLabels19.RASTERIZED_TEXTS]), for a layer of one text it is v1.8's. Undo brings
 * every text back.
 */
@RunWith(RobolectricTestRunner::class)
class TextLayerEraseToastTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setup(texts: List<TextItem>, name: String): Pair<EditorController, Layer> {
        val d = Document("t", "t", 300, 200).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(300, 200)) }
        val c = EditorController(context, d, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        val layer = Layer(d.newLayerId(), name, BitmapUtils.createLayerBitmap(300, 200))
        for (t in texts) TextRenderer.drawItem(Canvas(layer.bitmap), t, TextRenderer.prepare(t), null)
        layer.textData = TextCodec.encodeAll(texts)
        d.layers += layer
        c.selectLayer(layer)
        return c to layer
    }

    private fun erase(c: EditorController) {
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        c.pointerDown(ToolPoint(20f, 100f))
        for (i in 1..10) c.pointerMove(ToolPoint(20f + i * 25f, 100f))
        c.pointerUp(ToolPoint(270f, 100f))
    }

    private val texts = listOf(
        TextItem("North", TextSpec(sizePx = 30f), cx = 80f, cy = 60f),
        TextItem("Middle", TextSpec(sizePx = 30f), cx = 150f, cy = 100f),
        TextItem("South", TextSpec(sizePx = 30f), cx = 220f, cy = 150f),
    )

    @Test
    fun erasingALayerOfSeveralTextsSaysHowManyTexts() {
        val (c, layer) = setup(texts, "Titles")
        val data = layer.textData
        val steps = c.undoManager.undoCount
        c.message = null
        erase(c)
        assertNull("a regular layer now", layer.textData)
        assertFalse(layer.isTextLayer)
        assertEquals(String.format(Locale.ROOT, TextLabels19.RASTERIZED_TEXTS, "Titles", 3), c.message)
        assertTrue(c.message!!.contains("its 3 texts"))
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        c.undo()
        assertEquals("undo brings every text back", data, layer.textData)
        assertEquals(3, TextCodec.decodeAll(layer.textData).size)
    }

    @Test
    fun erasingALayerOfOneTextKeepsV18sMessage() {
        val (c, layer) = setup(texts.take(1).map { it.copy(cx = 150f, cy = 100f) }, "North")
        c.message = null
        erase(c)
        assertNull(layer.textData)
        assertEquals("\"North\" is now a regular layer (its text can no longer be edited; undo to get it back)", c.message)
        c.undo()
        assertEquals(1, TextCodec.decodeAll(layer.textData).size)
    }
}
