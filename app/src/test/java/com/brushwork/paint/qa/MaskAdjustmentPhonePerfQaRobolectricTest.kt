package com.brushwork.paint.qa

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskEdits
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.MaskTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 final QA, performance guards at the user's own canvas (1080 x 2408, the one large-canvas
 * test of this QA pass): a Tone slider drag renders once per frame for the newest value, a full
 * frame of a full-canvas adjustment and a full-canvas mask edit stay far from a stall. Times are
 * printed for the report; the bounds leave a loaded desktop JVM plenty of slack over the T606
 * budgets (§4.3c: 50–70 ms per slider frame, about 8 ms per 2.6 MP mask render).
 */
@RunWith(RobolectricTestRunner::class)
class MaskAdjustmentPhonePerfQaRobolectricTest {
    private val w = 1080
    private val h = 2408
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        scope.cancel()
    }

    @Test
    fun aToneSliderDragAndMaskEditsOnThePhonesCanvas() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("perf", "perf", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF203060.toInt(), 0xFFF0C080.toInt(), Shader.TileMode.CLAMP) })
        }
        doc.activeLayerIndex = 0
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        val tone = FilterRegistry.byId("adjust.tone")!!

        // A full-canvas linear mask: one synchronous render on release.
        val spec = MaskSpec(components = listOf(LinearMask(1, x0 = 0f, y0 = 0f, x1 = 0f, y1 = h.toFloat())), nextId = 2)
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), spec)!!
        val t0 = System.nanoTime()
        val moved = spec.copy(components = listOf(LinearMask(1, x0 = 0f, y0 = 200f, x1 = 0f, y1 = h - 200f)))
        assertTrue(MaskEdits.apply(c, adj, moved, "Edit mask"))
        val maskMs = (System.nanoTime() - t0) / 1e6
        // Add a radial: changes only its own area.
        val t1 = System.nanoTime()
        val withRadial = moved.copy(components = moved.components + RadialMask(2, cx = 540f, cy = 1200f, rx = 300f, ry = 300f), nextId = 3)
        assertTrue(MaskEdits.apply(c, adj, withRadial, "Mask: radial"))
        val radialMs = (System.nanoTime() - t1) / 1e6

        // Baseline: the same full-canvas frame without the adjustment (what compositing alone
        // costs here), then the first frame with it.
        adj.visible = false
        c.invalidateDoc(null)
        val tb = System.nanoTime()
        c.tiles.update(c.compositor, null)
        val baseMs = (System.nanoTime() - tb) / 1e6
        adj.visible = true
        c.invalidateDoc(null)
        c.tiles.update(c.compositor, null)
        // The mapper alone over the whole canvas.
        val px = IntArray(w * h) { 0xFF000000.toInt() or (it * 2654435761L.toInt() ushr 8) }
        val mapper = tone.pixelMapper(tone.defaultValues().set("exposure", 1f))!!
        val tm = System.nanoTime()
        com.brushwork.paint.core.Parallel.forRange(h, 8) { r0, r1 -> mapper.map(px, r0 * w, r1 * w) }
        val mapMs = (System.nanoTime() - tm) / 1e6
        // A slider drag: 30 values between two frames render once.
        val edit = tool.adjustmentEdit(adj)
        for (i in 1..30) edit.preview(AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", i / 20f)))
        val visible = Rect(0, 0, w, h)
        val t2 = System.nanoTime()
        assertTrue(c.tiles.update(c.compositor, visible))
        val frameMs = (System.nanoTime() - t2) / 1e6
        assertFalse("one render for 30 slider moves", c.tiles.update(c.compositor, visible))
        edit.flush()
        assertEquals("one step for the drag", "Edit adjustment", c.undoManager.undoLabel)

        // Undo of the mask edit re-renders from the data (no tiles).
        val t3 = System.nanoTime()
        c.undo(); c.undo()
        val undoMs = (System.nanoTime() - t3) / 1e6

        println("[qa] 1080x2408: full mask edit ${"%.1f".format(maskMs)} ms, radial add ${"%.1f".format(radialMs)} ms, full-canvas Tone frame ${"%.1f".format(frameMs)} ms (same frame without the adjustment ${"%.1f".format(baseMs)} ms, Tone mapper alone ${"%.1f".format(mapMs)} ms), undo x2 ${"%.1f".format(undoMs)} ms")
        assertTrue("full mask edit $maskMs ms", maskMs < 2_000)
        assertTrue("radial add $radialMs ms", radialMs < 2_000)
        assertTrue("full-canvas Tone frame $frameMs ms", frameMs < 5_000)
        assertTrue("undo $undoMs ms", undoMs < 4_000)
    }
}
