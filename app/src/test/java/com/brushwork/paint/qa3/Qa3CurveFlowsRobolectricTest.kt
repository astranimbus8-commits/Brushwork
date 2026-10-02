package com.brushwork.paint.qa3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Final QA (v1.5 §4.4, §4.5, §4.9 Curve row): a plain curve that follows the brush size and has
 * a 300 % point, committed on a vector layer, scaled with Transform, saved and reloaded, then
 * reopened in the Curve tool: its width and per-point thickness survive every step, the cache
 * always equals the rendering of the objects (I1), and each user action is one undo step.
 */
@RunWith(RobolectricTestRunner::class)
class Qa3CurveFlowsRobolectricTest {
    private val ink = 0xFF203080.toInt()
    private val w = 400
    private val h = 300
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun controller(doc: Document = newDoc(), clearSettings: Boolean = true): EditorController {
        if (clearSettings) ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        return EditorController(ctx, doc, scope, AppSettings(ctx)).also {
            it.color = ink
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.snapping.enabled = false
        }
    }

    private fun newDoc(): Document {
        val doc = Document("qa3curve", "Curve", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return doc
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private fun assertCacheIsRendering(layer: Layer) {
        assertArrayEquals("the vector layer's pixels are its objects' rendering (I1)", render(layer.vector!!), pixels(layer.bitmap))
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun pathOf(layer: Layer): VPath = layer.vector!!.objects.single() as VPath

    @Test
    fun aThickPlainPathSurvivesTransformReloadAndReopen() = runBlocking<Unit> {
        File(ctx.filesDir, "projects").deleteRecursively()
        val c = controller()
        val layer = c.activeLayer
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN) }
        assertTrue(tool.widthLinked)
        for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
        tool.setWidth(1, 3f)
        tool.endNumericEdit()
        // The side slider (brush size) resizes the pending plain line.
        c.brush = c.brush.copy(size = 8f)
        Snapshot.sendApplyNotifications()
        assertEquals(8f, tool.lineWidth, 0f)
        assertEquals("the ring shows the real diameter", 24f, tool.diameterAt(1), 0.01f)
        tool.commit()
        assertEquals("one step", 1, c.undoManager.undoCount)
        assertTrue(layer.isVectorLayer)
        val committed = pathOf(layer)
        assertEquals(8f, committed.stroke!!.width, 0f)
        assertEquals(listOf(1f, 3f, 1f), committed.subpaths[0].anchors.map { it.width })
        assertCacheIsRendering(layer)

        // Transform: scale the objects to 50 % (Numbers).
        c.selectTool(ToolId.TRANSFORM)
        val tt = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { tt.transformState != null })
        tt.setScalePercent(50.0)
        tt.endNumericEdit()
        tt.commit()
        assertTrue(Smoke.pumpUntil(10_000) { c.settleVectorWork(); !tt.hasPendingWork })
        c.settleVectorWork()
        Smoke.pump(100)
        assertEquals("✓ is one step", 2, c.undoManager.undoCount)
        val scaled = pathOf(layer)
        assertEquals("the line scales with the objects", 4f, scaled.stroke!!.width, 0.01f)
        assertEquals("per-point thickness is kept", listOf(1f, 3f, 1f), scaled.subpaths[0].anchors.map { it.width })
        assertCacheIsRendering(layer)

        // Save, reload, reopen in a new editor.
        val repo = ProjectRepository(ctx)
        repo.save(c.doc, null)
        val doc2 = repo.load(c.doc.id)
        val c2 = controller(doc2, clearSettings = false)
        val layer2 = doc2.layers.first { it.id == layer.id }
        assertEquals(scaled, pathOf(layer2))
        assertCacheIsRendering(layer2)
        c2.selectLayer(layer2)
        c2.selectTool(ToolId.CURVE)
        val tool2 = c2.tools.getValue(ToolId.CURVE) as CurveTool
        val a0 = scaled.subpaths[0].anchors[0]
        c2.tap(a0.x, a0.y)
        assertTrue("reopened by a tap on its line", tool2.isReopened)
        assertFalse("its own width: not linked to the brush", tool2.widthLinked)
        assertEquals(4f, tool2.lineWidth, 0.01f)
        assertEquals(listOf(1f, 3f, 1f), tool2.anchors.map { it.width })
        // The brush size changing doesn't touch the reopened line.
        c2.brush = c2.brush.copy(size = 30f)
        Snapshot.sendApplyNotifications()
        assertEquals(4f, tool2.lineWidth, 0.01f)
        // Thinner middle point: ✓ is one "Edit path" step.
        val steps = c2.undoManager.undoCount
        tool2.select(1)
        tool2.setWidth(1, 1.5f)
        tool2.endNumericEdit()
        tool2.commit()
        assertEquals("one step", steps + 1, c2.undoManager.undoCount)
        assertEquals(listOf(1f, 1.5f, 1f), pathOf(layer2).subpaths[0].anchors.map { it.width })
        assertEquals(4f, pathOf(layer2).stroke!!.width, 0.01f)
        assertCacheIsRendering(layer2)
        c2.undo()
        assertEquals(scaled, pathOf(layer2))
        assertCacheIsRendering(layer2)
        // "All points 100 %" on a reopened path: one in-tool step, then one step on ✓.
        c2.tap(a0.x, a0.y)
        assertTrue(tool2.isReopened)
        tool2.resetAllWidths()
        assertTrue(tool2.undoStep())
        assertEquals(listOf(1f, 3f, 1f), tool2.anchors.map { it.width })
        assertTrue(tool2.redoStep())
        tool2.commit()
        assertEquals(listOf(1f, 1f, 1f), pathOf(layer2).subpaths[0].anchors.map { it.width })
        assertCacheIsRendering(layer2)
        c2.dispose()
        c.dispose()
    }
}
