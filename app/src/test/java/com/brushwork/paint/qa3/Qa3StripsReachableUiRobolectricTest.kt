package com.brushwork.paint.qa3

import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Final QA (v1.5 §4.9, §5.9) of the editor chrome on the narrowest phone (360 dp): every control
 * of the options strip of every new or changed tool (Masks, Clone stamp, Curve and Polyline with
 * a point selected, Text with a pending text, Shape with its own points, Transform of lifted
 * vector objects, and Brush, Eraser, Bucket and Lasso in vector mode) can be scrolled fully onto
 * the screen and is not cut off at the top or bottom of the strip; the controls the user needs
 * first are on the screen without scrolling.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.stripsreachsandbox"])
class Qa3StripsReachableUiRobolectricTest {

    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            runCatching { settle() }
        }
    }

    private fun editor(c: (ComponentActivity) -> EditorController): Pair<ComponentActivity, EditorController> {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val controller = c(activity)
        controller.snapping.enabled = false
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools
        settle()
        return activity to controller
    }

    /** The options strip: the topmost horizontally scrolling node of the editor window. */
    private fun strip(activity: ComponentActivity): RobolectricUi.Element = RobolectricUi.elements()
        .filter { it.window === activity.window.decorView.rootView || it.window.rootView === activity.window.decorView.rootView }
        .filter { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        .minByOrNull { it.bounds.top } ?: throw AssertionError("no options strip")

    private fun SemanticsNode.isControl() =
        config.getOrNull(SemanticsActions.OnClick) != null || config.getOrNull(SemanticsActions.SetProgress) != null

    private fun SemanticsNode.label(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            children.flatMap { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + c.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            .joinToString(" ").ifBlank { "#$id" }

    private fun controlsOf(bar: SemanticsNode, all: Boolean = false): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (all || n.isControl()) out += n
            n.children.forEach(::walk)
        }
        bar.children.forEach(::walk)
        return out
    }

    /** Where [n] is in the window, NOT clipped by the strip (boundsInWindow is clipped). */
    private fun unclipped(n: SemanticsNode): androidx.compose.ui.geometry.Rect {
        val p = n.positionInWindow
        return androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + n.size.width, p.y + n.size.height)
    }

    /**
     * Wholly on the screen (within 4 dp: a slider's node is a little wider than its track, for
     * its thumb's touch target).
     */
    private fun wholly(n: SemanticsNode, width: Int): Boolean {
        val u = unclipped(n)
        val slack = 4f * density
        return u.width > 0f && n.boundsInWindow.width > 0f && u.left >= -slack && u.right <= width + slack
    }

    private var density = 1f

    /**
     * Scrolls the strip so each of its controls is in view and checks it is wholly on the
     * screen; [first] are the labels that must show without scrolling.
     */
    private fun assertStripReachable(activity: ComponentActivity, what: String, first: List<String> = emptyList()) {
        settle()
        density = activity.resources.displayMetrics.density
        val width = activity.window.decorView.width
        val bar0 = strip(activity)
        val visible0 = controlsOf(bar0.node, all = true).filter { wholly(it, width) }.map { it.label() }
        val range0 = bar0.node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        val where0 = controlsOf(bar0.node, all = true).map { "${it.label()}@${unclipped(it)}" }
        for (f in first) assertTrue("$what: \"$f\" shows without scrolling; shown: $visible0; scroll ${range0?.value?.invoke()} of ${range0?.maxValue?.invoke()}; all: $where0", visible0.any { it.contains(f) })
        val labels = controlsOf(bar0.node).map { it.label() }
        assertTrue("$what: the strip has controls", labels.isNotEmpty())
        for ((i, label) in labels.withIndex()) {
            val bar = strip(activity)
            val node = controlsOf(bar.node).getOrNull(i) ?: throw AssertionError("$what: control $i ($label) went away")
            val b = unclipped(node)
            val delta = when {
                b.right > bar.bounds.right -> b.right - bar.bounds.right
                b.left < bar.bounds.left -> b.left - bar.bounds.left
                else -> 0f
            }
            if (delta != 0f) {
                requireNotNull(bar.node.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(delta, 0f)
                settle(12)
            }
            val bar2 = strip(activity)
            val n2 = controlsOf(bar2.node).getOrNull(i) ?: throw AssertionError("$what: control $i ($label) went away")
            val b2 = unclipped(n2)
            assertTrue("$what: \"$label\" can be scrolled wholly onto the screen: $b2 (shown ${n2.boundsInWindow}) in 0..$width (strip ${bar2.bounds})", wholly(n2, width))
            assertTrue("$what: \"$label\" is not cut off by the strip: $b2 in ${bar2.bounds}", b2.top >= bar2.bounds.top - 0.5f && b2.bottom <= bar2.bounds.bottom + 0.5f)
        }
        // Back to the start.
        strip(activity).node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(-100_000f, 0f)
        settle(12)
    }

    /**
     * [n] frames as a phone runs them: each frame's composition is laid out in that same frame
     * (Robolectric runs the layout pass later, so an effect that waits a frame and then scrolls a
     * control into view would see it unmeasured).
     */
    private fun deviceFrames(activity: ComponentActivity, n: Int = 60) {
        fun roots(v: android.view.View): List<androidx.compose.ui.platform.ViewRootForTest> = when (v) {
            is androidx.compose.ui.platform.ViewRootForTest -> listOf(v)
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
            else -> emptyList()
        }
        repeat(n) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
            roots(activity.window.decorView).forEach { it.measureAndLayoutForTest() }
        }
    }

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float) = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(x0, y0, sharp = true), VAnchor(x1, y0, sharp = true), VAnchor(x1, y1, sharp = true), VAnchor(x0, y1, sharp = true)), closed = true)),
        polyline = true,
        fill = VPaint.Solid(0xFF2266CC.toInt()),
    )

    /** An editor on a picture layer, or (with [vector]) on a vector layer with a square. */
    private fun open(vector: Boolean): Pair<ComponentActivity, EditorController> {
        val (activity, c) = editor { Smoke.controller(it, Smoke.document(400, 300, layers = 2, whiteBottom = true)) }
        if (vector) {
            val v = c.addVectorLayer()!!
            c.vectors.addObjects(v, listOf(square(100f, 80f, 200f, 160f)), "Add square")
            c.settleVectorWork()
        } else {
            c.editWholeLayer(c.activeLayer, "Seed") { b -> Canvas(b).drawCircle(200f, 150f, 60f, Paint().apply { color = 0xFF2266CC.toInt() }) }
        }
        settle()
        return activity to c
    }

    @Test
    fun everyStripControlIsReachableAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        section("curve in vector mode, a point selected") {
            val (a, c) = open(vector = true)
            c.selectTool(ToolId.CURVE)
            val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
            // The strip of the tool is up before the points are placed (as when tapping them in).
            settle()
            for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
            tool.select(1)
            tool.setWidth(1, 2f)
            tool.endNumericEdit()
            deviceFrames(a)
            assertStripReachable(a, "curve", first = listOf("Point thickness slider"))
        }
        section("masks, a component selected") {
            val (a, c) = open(vector = false)
            c.selectTool(ToolId.MASK)
            val tool = c.tools.getValue(ToolId.MASK) as MaskTool
            tool.arm(MaskTool.Kind.RADIAL)
            c.pointerDown(ToolPoint(200f, 150f))
            for (i in 1..10) c.pointerMove(ToolPoint(200f + 5f * i, 150f + 4f * i))
            c.pointerUp(ToolPoint(250f, 190f))
            Smoke.pump(300)
            assertTrue(tool.selected != null)
            assertStripReachable(a, "masks", first = listOf("+ Linear"))
        }
        section("clone stamp, source set") {
            val (a, c) = open(vector = false)
            c.selectTool(ToolId.CLONE)
            (c.tools.getValue(ToolId.CLONE) as CloneTool).setSource(Vec2(120f, 90f))
            assertStripReachable(a, "clone")
        }
        section("polyline in vector mode, a point selected") {
            val (a, c) = open(vector = true)
            c.selectTool(ToolId.POLYLINE)
            val tool = c.tools.getValue(ToolId.POLYLINE) as CurveTool
            settle()
            for (p in listOf(Vec2(60f, 200f), Vec2(200f, 80f), Vec2(340f, 200f))) tool.addAnchor(p)
            tool.select(2)
            deviceFrames(a)
            assertStripReachable(a, "polyline", first = listOf("Point thickness slider"))
        }
        section("text, pending") {
            val (a, c) = open(vector = false)
            c.selectTool(ToolId.TEXT)
            val tool = c.tools.getValue(ToolId.TEXT) as TextTool
            tool.startTextAt(200f, 150f)
            tool.setText("Hello there")
            tool.confirmEditor()
            assertStripReachable(a, "text", first = listOf("Wrap around picture"))
        }
        section("shape in vector mode, own points") {
            val (a, c) = open(vector = true)
            c.selectTool(ToolId.SHAPE)
            val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
            c.pointerDown(ToolPoint(220f, 180f))
            c.pointerMove(ToolPoint(260f, 220f))
            c.pointerMove(ToolPoint(300f, 260f))
            c.pointerUp(ToolPoint(300f, 260f))
            settle()
            shape.setPointEditing(true)
            assertStripReachable(a, "shape")
        }
        section("transform of lifted vector objects") {
            val (a, c) = open(vector = true)
            c.selectTool(ToolId.TRANSFORM)
            val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
            assertTrue(Smoke.pumpUntil { tool.transformState != null })
            assertStripReachable(a, "transform")
        }
        section("brush, eraser, bucket, lasso in vector mode") {
            val (a, c) = open(vector = true)
            assertTrue(c.isVectorMode)
            c.selectTool(ToolId.BRUSH)
            assertStripReachable(a, "brush", first = listOf("Vector mode is on"))
            c.selectTool(ToolId.ERASER)
            assertStripReachable(a, "vector eraser", first = listOf("Object", "Partial"))
            c.selectTool(ToolId.FILL)
            assertStripReachable(a, "bucket")
            c.selectTool(ToolId.LASSO)
            assertStripReachable(a, "lasso")
        }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }
}
