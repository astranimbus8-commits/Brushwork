package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.7 (item 11, design §3.11 c, §6.3) probe of a data layer's Transform commit on a 4000 x 5000
 * document, with maps that move the data (areas C and D own the real ones; their cost is the
 * same arithmetic): a 220 px text about 1400 px wide (≤ 120 ms on the phone) and a 1200 x 1000
 * star (≤ 60 ms) are mapped
 * and re-rendered as one step. Desktop times swing with the machine's load, so the probe prints
 * them and asserts them against the v1.6 pixel commit of the same layer measured alongside
 * (JVM-relative). Local runs only (T606-size bitmaps): skipped on CI.
 */
@RunWith(RobolectricTestRunner::class)
class TransformDataCommitProbeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    @Before
    fun guard() = assumeTrue("T606-size probe: local runs only", System.getenv("CI") == null)

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 4000
    private val h = 5000

    /** Maps that move a text's or a shape's centre (enough for the cost: decode, map, encode, render). */
    private object MoveMaps : DataMaps {
        override fun textCanMap(m: FloatArray): Boolean = true
        override fun text(textData: String, m: FloatArray): String? = TextCodec.decode(textData)?.let { t ->
            TextCodec.encode(t.copy(cx = m[0] * t.cx + m[1] * t.cy + m[2], cy = m[3] * t.cx + m[4] * t.cy + m[5]))
        }
        override fun shape(shapeData: String, m: FloatArray): String? = ShapeCodec.decode(shapeData)?.let { o ->
            ShapeCodec.encode(o.copy(cx = m[0] * o.cx + m[1] * o.cy + m[2], cy = m[3] * o.cx + m[4] * o.cy + m[5]))
        }
        override fun array(layer: Layer, m: FloatArray): LayerData? = null
    }

    private fun controller(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix().apply { setScale(0.18f, 0.18f) }) }
    }

    /** ms of a moved commit of the active layer with the Transform tool (lifted as [expected]); one step. */
    private fun moveCommitMs(c: EditorController, expected: TransformTool.Lifted): Double {
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.dataMaps = { MoveMaps }
        tool.snapToObjects = false
        val until = System.currentTimeMillis() + 60_000
        while (tool.transformState == null) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < until) { "the lift did not finish" }
            Thread.sleep(5)
        }
        assertEquals(expected, tool.lifted)
        tool.moveBy(40f, 30f)
        val steps = c.undoManager.undoCount
        val t0 = System.nanoTime()
        tool.commit()
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        c.dispose()
        return ms
    }

    private val text = TextItem("Brushwork 1.7", spec = TextSpec(sizePx = 220f), cx = 2000f, cy = 2500f)

    private fun drawText(cv: Canvas) = TextRenderer.drawItem(cv, text, TextRenderer.prepare(text), null)

    private val shape = ShapeObject(ShapeType.STAR, cx = 2000f, cy = 2500f, w = 1200f, h = 1000f, style = ShapeStyle.FILL, fillColor = 0xFF2244CC.toInt())

    private fun drawShape(cv: Canvas) {
        val all = Rect(0, 0, w, h)
        VectorLayerRenderer.render(cv, VectorContent(objects = listOf(VShape(0L, shape = shape))), all, tips = TipCache(), document = all)
    }

    @Test
    fun aTextAndAShapeCommitNearAPixelCommit() {
        val textMs = controller().let { c ->
            assertNotNull(c.addLayerWithContent("Text", "Add text", textData = TextCodec.encode(text)) { drawText(it) })
            moveCommitMs(c, TransformTool.Lifted.TEXT)
        }
        val textPixelsMs = controller().let { c ->
            assertNotNull(c.addLayerWithContent("Text pixels", "Add") { drawText(it) })
            moveCommitMs(c, TransformTool.Lifted.PIXELS)
        }
        val shapeMs = controller().let { c ->
            assertNotNull(c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(shape)) { drawShape(it) })
            moveCommitMs(c, TransformTool.Lifted.SHAPE)
        }
        val shapePixelsMs = controller().let { c ->
            assertNotNull(c.addLayerWithContent("Shape pixels", "Add") { drawShape(it) })
            moveCommitMs(c, TransformTool.Lifted.PIXELS)
        }
        println(
            "[v17f] Transform commit on 4000 x 5000 (JVM): text kept as text ${"%.0f".format(textMs)} ms (as pixels ${"%.0f".format(textPixelsMs)} ms), " +
                "shape kept as shape ${"%.0f".format(shapeMs)} ms (as pixels ${"%.0f".format(shapePixelsMs)} ms)",
        )
        assertTrue("text $textMs ms vs pixels $textPixelsMs ms", textMs <= RATIO * textPixelsMs + 30.0)
        assertTrue("shape $shapeMs ms vs pixels $shapePixelsMs ms", shapeMs <= RATIO * shapePixelsMs + 30.0)
    }

    private companion object {
        /** A data commit (map, re-render, one step) against the pixel commit of the same layer. */
        const val RATIO = 3.0
    }
}
