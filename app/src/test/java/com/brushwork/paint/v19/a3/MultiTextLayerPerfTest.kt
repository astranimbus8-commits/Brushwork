package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 7 (R14 c), the budgets of a full text layer (a JVM proxy of the phone's, the
 * median of 9 warm runs): finding the text under a finger among [TextCodec.MAX_ITEMS] texts
 * takes 1 ms at most; applying a change to one of them takes 30 ms at most (only the changed
 * text's area is cleared and drawn again, with the texts that meet it).
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class MultiTextLayerPerfTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fullLayer(): Triple<EditorController, TextTool, Layer> {
        val w = 1600
        val h = 1200
        val d = Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, d, CoroutineScope(Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(100f, 75f)
        tool.setText("Text 0")
        tool.updateSpec { it.copy(sizePx = 36f, color = 0xFF000000.toInt()) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        val spec: TextSpec = TextCodec.decodeAll(layer.textData).single().spec
        val items = List(TextCodec.MAX_ITEMS) { i -> TextItem("Text $i", spec, 100f + (i % 8) * 190f, 75f + (i / 8) * 145f) }
        assertTrue(c.updateTextLayer(layer, TextCodec.encodeAll(items), "Setup", null) { cv -> for (t in items) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null) })
        return Triple(c, tool, layer)
    }

    @Test
    fun findingTheTextUnderAFingerAmongSixtyFourTexts() {
        val (_, tool, layer) = fullLayer()
        val hitPoint = Vec2(100f + 7 * 190f, 75f + 7 * 145f)
        val empty = Vec2(1590f, 1190f)
        assertEquals(layer, tool.textAt(hitPoint)!!.layer)
        assertNull(tool.textAt(empty))
        repeat(50) { tool.textAt(hitPoint); tool.textAt(empty) }
        PerfBudget.measuredWithin(PerfBudget.ms(1.0), what = "Text: the text under a finger among 64 texts, a miss (median of 9)") {
            val runs = DoubleArray(9) { PerfBudget.timeMs { tool.textAt(empty) } }
            runs.sort()
            runs[4]
        }
    }

    @Test
    fun applyingAChangeToOneTextOfSixtyFour() {
        val (c, tool, layer) = fullLayer()
        var n = 0
        fun change(): Double {
            assertTrue(tool.editLayer(layer, index = 27))
            assertNotNull(tool.item)
            tool.setText("Changed ${n++}")
            return PerfBudget.timeMs { assertTrue(tool.commitItem()) }
        }
        repeat(5) { change() }
        PerfBudget.measuredWithin(PerfBudget.ms(30.0), what = "Text: applying a change to one text of 64 (median of 9)") {
            val runs = DoubleArray(9) { change() }
            runs.sort()
            runs[4]
        }
        assertEquals(TextCodec.MAX_ITEMS, TextCodec.decodeAll(layer.textData).size)
        assertEquals(2, c.doc.layers.size)
    }
}
