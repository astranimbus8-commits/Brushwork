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
 * JVM's native Skia. T606 times are device-only (§6.4 item 10), and a desktop's times swing with
 * whatever else runs, so the probe prints its times and asserts them against the v1.6 Transform
 * paths measured alongside (JVM-relative):
 * - a preview frame (the dense points of a mesh that changed, then a full 4000 x 5000 lift drawn
 *   through them at the phone's fit-to-screen scale, 720 px wide) costs at most [PREVIEW_RATIO]
 *   times the Free / Distort preview's plain matrix draw of the same lift (the tool draws the lift
 *   itself, as Distort does: the 1024 px row is printed, not asserted);
 * - the commit of a deformed 4000 x 5000 layer through the real tool, its undo step included,
 *   costs at most [COMMIT_RATIO] times a Free transform commit of the same layer.
 * Local runs only (T606-size bitmaps): skipped on CI.
 */
@RunWith(RobolectricTestRunner::class)
class FreeDeformBudgetProbeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    @Before
    fun guard() = assumeTrue("T606-size probe: local runs only", System.getenv("CI") == null)

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 4000
    private val h = 5000

    /** A photo-like picture: a diagonal gradient and a few bands. */
    private fun paintPhoto(b: Bitmap) {
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val cv = Canvas(b)
        cv.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, w, h, 0xFF2266AA.toInt(), 0xFFEEBB44.toInt(), Shader.TileMode.CLAMP) })
        val band = Paint().apply { shader = LinearGradient(0f, 0f, w, 0f, 0x8000FF00.toInt(), 0x80FF00FF.toInt(), Shader.TileMode.MIRROR) }
        for (k in 0 until 12) cv.drawRect(0f, h * k / 12f, w, h * k / 12f + h / 30f, band)
    }

    /** The 12 x 12 mesh over [w] x [h] with every vertex pushed a little (frame [k]). */
    private fun wavy(w: Int, h: Int, k: Int): MeshDeform {
        val m = MeshDeform.identity(0f, 0f, w.toFloat(), h.toFloat(), MeshDeform.MAX_CELLS, MeshDeform.MAX_CELLS)
        val pts = m.vertices().mapIndexed { i, p ->
            val a = 0.02f * w * sin(0.7f * i + 0.3f * k)
            Vec2(p.x + a, p.y - a * 0.5f)
        }
        return m.withVertices(pts)
    }

    /**
     * The best ms of a Free deform preview frame and of a plain matrix draw of the same [w] x [h]
     * lift at [scale], frames interleaved so both see the same machine.
     */
    private fun previewMs(w: Int, h: Int, scale: Float): Pair<Double, Double> {
        val src = BitmapUtils.createLayerBitmap(w, h).also { paintPhoto(it) }
        val padded = MeshRenderer.padded(src)!!
        val screen = Bitmap.createBitmap((w * scale).toInt() + 1, (h * scale).toInt() + 1, Bitmap.Config.ARGB_8888)
        val cv = Canvas(screen).apply { scale(scale, scale) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val turn = Matrix()
        var mesh = Double.MAX_VALUE
        var plain = Double.MAX_VALUE
        repeat(18) { k ->
            val m = wavy(w, h, k)
            var t0 = System.nanoTime()
            screen.eraseColor(0)
            val verts = MeshRenderer.vertices(m, w, h, MeshRenderer.PREVIEW_SUB, smooth = true)
            MeshRenderer.draw(cv, padded, m, verts, MeshRenderer.PREVIEW_SUB, paint)
            if (k >= 3) mesh = minOf(mesh, (System.nanoTime() - t0) / 1e6)
            t0 = System.nanoTime()
            screen.eraseColor(0)
            turn.setRotate(2f + k, w / 2f, h / 2f)
            cv.drawBitmap(src, turn, paint)
            if (k >= 3) plain = minOf(plain, (System.nanoTime() - t0) / 1e6)
        }
        src.recycle()
        padded.recycle()
        screen.recycle()
        return mesh to plain
    }

    @Test
    fun aPreviewFrameAtTwelveByTwelveCellsStaysNearAPlainTransformFrame() {
        val (full, fullPlain) = previewMs(w, h, 720f / w)
        val (proxy, proxyPlain) = previewMs(1024, 1280, 720f / 1024f)
        println(
            "[v17f] Free deform preview 12 x 12 (JVM, best frame): full 4000 x 5000 lift ${"%.1f".format(full)} ms " +
                "(plain transform frame ${"%.1f".format(fullPlain)} ms), 1024 px lift ${"%.1f".format(proxy)} ms (plain ${"%.1f".format(proxyPlain)} ms)",
        )
        assertTrue("full lift: Free deform $full ms vs a plain frame $fullPlain ms", full <= PREVIEW_RATIO * fullPlain + 1.0)
    }

    /** A 4000 x 5000 document with one photo layer, the Transform tool on it with the lift ready. */
    private fun lifted(): Pair<EditorController, TransformTool> {
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
        return c to tool
    }

    /** ms of [tool]'s commit; one step recorded. */
    private fun commitMs(c: EditorController, tool: TransformTool): Double {
        val steps = c.undoManager.undoCount
        val t0 = System.nanoTime()
        tool.commit()
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(steps + 1, c.undoManager.undoCount)
        return ms
    }

    @Test
    fun theCommitOfAFullLayerStaysNearAPlainTransformCommit() {
        val (c1, free) = lifted()
        free.setRotation(10.0)
        free.endNumericEdit()
        val plain = commitMs(c1, free)
        c1.dispose()

        val (c2, tool) = lifted()
        tool.mode = TransformTool.Mode.MESH
        tool.setMeshCells(MeshDeform.MAX_CELLS, MeshDeform.MAX_CELLS)
        assertEquals(169, tool.pointCount)
        tool.selectPoints(PointSelection.of(tool.pointCount, 84, 85, 97))
        tool.beginGroupEdit("Move points")
        tool.setGroupTransform(Affine2.translate(180f, -120f))
        tool.endGroupEdit()
        assertTrue(tool.isMeshChanged)
        val mesh = commitMs(c2, tool)
        assertEquals(TransformTool.FREE_DEFORM_LABEL, c2.undoManager.undoLabel)
        c2.dispose()
        println("[v17f] Free deform commit 12 x 12 of a full 4000 x 5000 layer: ${"%.0f".format(mesh)} ms (a Free transform commit: ${"%.0f".format(plain)} ms) (JVM)")
        assertTrue("Free deform commit $mesh ms vs a Free transform commit $plain ms", mesh <= COMMIT_RATIO * plain + 50.0)
    }

    private companion object {
        /** A Free deform preview frame against a plain matrix frame of the same lift. */
        const val PREVIEW_RATIO = 3.0

        /** A Free deform commit against a Free transform commit of the same layer. */
        const val COMMIT_RATIO = 2.5
    }
}
