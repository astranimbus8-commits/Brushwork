package com.brushwork.paint.tools.clone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeRecorder
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 integration (A6 clone stamp x A5 adjustment stage): "All layers" samples the composite
 * WITH live adjustment layers — CloneSource fills its snapshot through its own Compositor while
 * the display compositor is drawing the stroke's layer, and the display compositor then applies
 * the adjustment layer above with its own scratch. The live stroke equals the committed result,
 * and the clone copies the adjusted pixels.
 */
@RunWith(RobolectricTestRunner::class)
class CloneAdjustmentIntegrationRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 600
    private val h = 400

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        app.getSharedPreferences(BrushPresetStore.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also { gradient(it.bitmap) }
        doc.layers += Layer(doc.newLayerId(), "Retouch", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(300f, 0f, 600f, 120f, Paint().apply { color = 0x80FF00FF.toInt() })
        }
        // A live Invert over the left part (a radial mask), above the layer being cloned on.
        val spec = MaskSpec(components = listOf(RadialMask(1, cx = 150f, cy = 150f, rx = 200f, ry = 160f, feather = 0.3f)), nextId = 2)
        doc.layers += Layer(doc.newLayerId(), "Invert 1", BitmapUtils.createLayerBitmap(w, h)).also {
            it.adjustment = AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))
            it.mask = MaskSpecs.newMask(spec, w, h)
            it.maskSpec = spec
        }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings)
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.CLONE)
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        c.updatePreset(ToolId.CLONE, pen.copy(size = 20f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f))
        return c
    }

    private fun gradient(b: Bitmap) {
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; 0xFF000000.toInt() or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x * 7 + y * 3) and 0xFF) }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The document as the canvas shows it (overrides included). */
    private fun composite(c: EditorController): IntArray {
        val out = BitmapUtils.createLayerBitmap(w, h)
        c.compositor.drawDocument(Canvas(out), null, target = CompositeTarget.identity(out))
        return pixels(out)
    }

    @Test
    fun allLayersClonesTheAdjustedCompositeAndTheLiveStrokeIsTheResult() {
        val c = setup()
        val t = c.tools.getValue(ToolId.CLONE) as CloneTool
        t.setSampleAllLayers(true)
        // What "All layers" samples: the composite with the adjustment, before the stroke.
        val reference = BitmapUtils.createLayerBitmap(w, h)
        c.compositor.drawDocument(Canvas(reference), null, useOverrides = false, target = CompositeTarget.identity(reference))
        var live: IntArray? = null
        t.brush.strokeHook = {
            StrokeHook.Record(object : StrokeRecorder {
                override val replacesStroke: Boolean get() = false
                override val ignoresSelection: Boolean get() = false
                override fun point(x: Float, y: Float, rawPressure: Float) {}
                override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
                    live = composite(c)
                    return commitPixels()
                }
                override fun cancel() {}
            })
        }
        t.setSource(Vec2(80f, 120f))
        // A stroke from the right half, sampling inside the inverted area, over several snapshot tiles.
        c.pointerDown(ToolPoint(380f, 200f))
        for (i in 1..12) c.pointerMove(ToolPoint(380f + i * 15f, 200f + i * 12f))
        c.pointerUp(ToolPoint(560f, 344f))
        assertEquals(CloneSource.Sample.ALL_LAYERS, t.source.sample)
        assertEquals(1, c.undoManager.undoCount)
        assertArrayEquals("the live stroke (snapshot filled while the canvas drew) equals the result", composite(c), live!!)
        // The clone copied the ADJUSTED pixel (the source sits inside the inverted area).
        val src = reference.getPixel(80, 120)
        assertEquals(src, c.activeLayer.bitmap.getPixel(380, 200))
        assertNotEquals("inverted, not the photo's own pixel", c.doc.layers[0].bitmap.getPixel(80, 120), src)
    }
}
