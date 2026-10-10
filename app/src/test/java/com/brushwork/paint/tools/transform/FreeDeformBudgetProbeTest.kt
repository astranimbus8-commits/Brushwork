package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.math.sin

/**
 * v1.7 (item 16, design §6.3) budget probe for Free deform at the maximum 12 x 12 cells, on the
 * JVM's native Skia (T606 times are device-only, §6.4 item 10): a preview frame (the dense
 * points of a mesh that changed, then the lift drawn through them at the phone's fit-to-screen
 * scale, 720 px wide) of a full 4000 x 5000 lift (≤ 33 ms) and of a 1024 px proxy (≤ 20 ms), and
 * the commit of a deformed 4000 x 5000 layer through the real tool, its undo step included
 * (≤ 1.5 s). Local runs only (T606-size bitmaps): skipped on CI.
 */
@RunWith(RobolectricTestRunner::class)
class FreeDeformBudgetProbeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    @Before
    fun guard() = assumeTrue("T606-size probe: local runs only", System.getenv("CI") == null)

    private val app get() = RuntimeEnvironment.getApplication()

    /** A photo-like [w] x [h] picture: two crossed gradients and a few bands. */
    private fun paintPhoto(b: Bitmap) {
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val cv = Canvas(b)
        cv.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, w, h, 0xFF2266AA.toInt(), 0xFFEEBB44.toInt(), Shader.TileMode.CLAMP) })
        val band = Paint().apply { shader = LinearGradient(0f, 0f, w, 0f, 0x8000FF00.toInt(), 0x80FF00FF.toInt(), Shader.TileMode.MIRROR) }
        for (k in 0 until 12) cv.drawRect(0f, h * k / 12f, w, h * k / 12f + h / 30f, band)
    }

    /** The 12 x 12 mesh over [w] x [h] with every inner vertex pushed a little (frame [k]). */
    private fun wavy(w: Int, h: Int, k: Int): MeshDeform {
        val m = MeshDeform.identity(0f, 0f, w.toFloat(), h.toFloat(), MeshDeform.MAX_CELLS, MeshDeform.MAX_CELLS)
        val pts = m.vertices().mapIndexed { i, p ->
            val a = 0.02f * w * sin(0.7f * i + 0.3f * k)
            Vec2(p.x + a, p.y - a * 0.5f)
        }
        return m.withVertices(pts)
    }

    /** Median ms of a preview frame of a [w] x [h] lift drawn at [scale]. */
    private fun previewMs(w: Int, h: Int, scale: Float): Double {
        val src = BitmapUtils.createLayerBitmap(w, h).also { paintPhoto(it) }
        val padded = MeshRenderer.padded(src)!!
        src.recycle()
        val screen = Bitmap.createBitmap((w * scale).toInt() + 1, (h * scale).toInt() + 1, Bitmap.Config.ARGB_8888)
        val cv = Canvas(screen).apply { scale(scale, scale) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val times = ArrayList<Double>()
        repeat(18) { k ->
            val mesh = wavy(w, h, k)
            val t0 = System.nanoTime()
            screen.eraseColor(0)
            val verts = MeshRenderer.vertices(mesh, w, h, MeshRenderer.PREVIEW_SUB, smooth = true)
            MeshRenderer.draw(cv, padded, mesh, verts, MeshRenderer.PREVIEW_SUB, paint)
            if (k >= 3) times += (System.nanoTime() - t0) / 1e6
        }
        padded.recycle()
        screen.recycle()
        return times.sorted()[times.size / 2]
    }

    @Test
    fun aPreviewFrameAtTwelveByTwelveCellsFitsItsBudget() {
        val full = previewMs(4000, 5000, 720f / 4000f)
        val proxy = previewMs(1024, 1280, 720f / 1024f)
        println("[v17f] Free deform preview 12 x 12: full 4000 x 5000 lift ${"%.1f".format(full)} ms, 1024 px proxy ${"%.1f".format(proxy)} ms (JVM)")
        assertTrue("full lift preview $full ms", full <= PerfBudget.ms(33.0))
        assertTrue("1024 px proxy preview $proxy ms", proxy <= PerfBudget.ms(20.0))
    }

    @Test
    fun theCommitOfAFullLayerFitsItsBudget() {
        val w = 4000
        val h = 5000
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also { paintPhoto(it.bitmap) }
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix().apply { setScale(0.18f, 0.18f) }) }
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.snapToObjects = false
        // A layer this large is lifted in the background.
        val until = System.currentTimeMillis() + 60_000
        while (tool.transformState == null) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < until) { "the 4000 x 5000 lift did not finish" }
            Thread.sleep(5)
        }
        tool.mode = TransformTool.Mode.MESH
        tool.setMeshCells(MeshDeform.MAX_CELLS, MeshDeform.MAX_CELLS)
        assertEquals(169, tool.pointCount)
        tool.selectPoints(PointSelection.of(tool.pointCount, 84, 85, 97))
        tool.beginGroupEdit("Move points")
        tool.setGroupTransform(Affine2.translate(180f, -120f))
        tool.endGroupEdit()
        assertTrue(tool.isMeshChanged)
        val steps = c.undoManager.undoCount
        val t0 = System.nanoTime()
        tool.commit()
        val ms = (System.nanoTime() - t0) / 1e6
        println("[v17f] Free deform commit 12 x 12 of a full 4000 x 5000 layer: ${"%.0f".format(ms)} ms (JVM)")
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue("commit $ms ms", ms <= PerfBudget.ms(1500.0))
        c.dispose()
    }
}
