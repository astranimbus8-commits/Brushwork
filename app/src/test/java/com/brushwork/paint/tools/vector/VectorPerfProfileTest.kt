package com.brushwork.paint.tools.vector

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.CanvasView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** TEMPORARY: sampling profile (JFR) of the hot vector scenarios. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi")
class VectorPerfProfileTest {
    private val anchors = listOf(Vec2(150f, 300f), Vec2(900f, 700f), Vec2(200f, 1200f), Vec2(900f, 1700f), Vec2(300f, 2100f))

    private fun setup(): Triple<EditorController, CanvasView, VectorPerfHarness> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(1080, 2408, 2, whiteBottom = true))
        val view = CanvasView(activity, c)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        c.tools
        c.brush = BrushLibrary.defaultBrush
        return Triple(c, view, VectorPerfHarness(c, view))
    }

    private fun profile(name: String, block: () -> Unit) {
        val target = Thread.currentThread()
        val self = HashMap<String, Int>()
        val incl = HashMap<String, Int>()
        var total = 0
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                val frames = target.stackTrace
                if (frames.isNotEmpty()) {
                    total++
                    val top = frames.first().let { "${it.className}.${it.methodName}" }
                    self.merge(top, 1, Int::plus)
                    val seen = HashSet<String>()
                    for (fr in frames) {
                        val m = "${fr.className}.${fr.methodName}"
                        if (!m.startsWith("com.brushwork") && !m.startsWith("android.graphics") && !m.startsWith("java.util") && !m.startsWith("org.robolectric.nativeruntime")) continue
                        if (seen.add(m)) incl.merge(m, 1, Int::plus)
                    }
                }
                Thread.sleep(0, 500_000)
            }
        }
        sampler.isDaemon = true
        sampler.start()
        block()
        running.set(false)
        sampler.join()
        println("[prof] ===== $name: $total samples")
        println("[prof] -- self")
        self.entries.sortedByDescending { it.value }.take(30).forEach { println(String.format("[prof] %5.1f%% %s", 100.0 * it.value / total, it.key)) }
        println("[prof] -- inclusive")
        incl.entries.sortedByDescending { it.value }.take(60).forEach { println(String.format("[prof] %5.1f%% %s", 100.0 * it.value / total, it.key)) }
    }
    @Test
    fun profileCurveBrush() {
        val (c, _, h) = setup()
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        tool.update { it.copy(stroke = CurveStroke.BRUSH, fill = false, taper = false) }
        for (a in anchors) h.tap(a)
        h.settle()
        h.drag("warm", anchors[2], { f -> Vec2(anchors[2].x + 6f * (f + 1), anchors[2].y + 3f * (f + 1)) }, frames = 20)
        val p = Vec2(anchors[2].x + 120f, anchors[2].y + 60f)
        profile("curve brush middle anchor") {
            h.drag("curve brush: middle anchor", p, { f -> Vec2(p.x - 6f * (f + 1), p.y + 3f * (f + 1)) }, frames = 60)
        }
        tool.discard()
    }

    @Test
    fun profilePlainShapes() {
        val (c, _, h) = setup()
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.PLAIN) }
        h.drag("(setup)", Vec2(100f, 200f), { f -> Vec2(100f + 15f * (f + 1), 200f + 35f * (f + 1)) }, frames = 60)
        h.settle()
        val grip = Vec2(1000f, 2300f)
        h.drag("warm", grip, { f -> Vec2(grip.x - 5f * (f + 1), grip.y - 8f * (f + 1)) }, frames = 20)
        val g2 = Vec2(900f, 2140f)
        profile("plain ellipse resize") {
            h.drag("shape plain ellipse+fill: resize", g2, { f -> Vec2(g2.x - 5f * (f + 1), g2.y - 8f * (f + 1)) }, frames = 60)
        }
        tool.discard()
    }
}
