package com.brushwork.paint.v110.a5

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointGroupSnap
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.idle
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (A5, R8 (c)): one move of a group of 500 points of a path, snapped, takes at most 4 ms on
 * a desktop (24 ms on the phone: the T606 proxy is 6 times the desktop). The path has 600 control
 * points on a 512 x 512 canvas, 500 of them selected (above the 256-point cap, so the box's lines
 * are the candidates) and 100 left as targets; the finger drags one selected point with snapping
 * on. A move is the tool's whole pointer move: the snap, the group's new places and the path made
 * again from them.
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class GroupSnapPerfProbeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    @Test
    fun aMoveOf500SnappedPointsTakesAtMost4Ms() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 512, 512)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(512, 512))
        val vector = Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(512, 512)).also { it.vector = VectorContent.EMPTY }
        doc.layers += vector
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
        // A zigzag of 600 control points across the canvas.
        val n = 600
        val spline = VSpline(List(n) { i -> VSplinePoint(20f + i * 470f / (n - 1), if (i % 2 == 0) 120f else 380f) }, order = 4)
        val path = VPath(id = 0, subpaths = listOf(SplineBezier.toSubpath(spline)), stroke = VStrokeStyle(color = 0xFF203080.toInt(), width = 4f), spline = spline)
        c.vectors.addObjects(vector, listOf(path), "Import")
        idle()
        c.selectTool(ToolId.PATH)
        val tool = c.tools.getValue(ToolId.PATH) as CurveTool
        tool.openPath(vector.id, vector.vector!!.objects.first().id, intArrayOf())
        idle()
        assertEquals(n, tool.pointCount)
        val moving = 500
        tool.selectPoints(PointSelection.none(n).plusAll((0 until moving).toList()))
        assertTrue(moving > PointGroupSnap.MAX_POINT_CANDIDATES)
        c.snapping.enabled = true
        // Grab point 250 (selected) and drag it past the touch slop.
        val grab = tool.pointAt(250)
        c.pointerDown(ToolPoint(grab.x, grab.y))
        c.pointerMove(ToolPoint(grab.x + 10f, grab.y))
        idle()
        assertNotEquals("the group moves", grab, tool.pointAt(250))
        var x = grab.x + 10f
        fun move(): Double {
            // Back and forth by 3 px: the box passes guides (the canvas centre and the other points).
            x += if (x > grab.x + 40f) -37f else 3f
            val t0 = System.nanoTime()
            c.pointerMove(ToolPoint(x, grab.y + 1f))
            return (System.nanoTime() - t0) / 1e6
        }
        repeat(3) { move() }
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(4.0), what = "a move of 500 snapped points of a 600-point path") {
            median(List(15) { move() })
        }
        println("GroupSnapPerfProbeTest: median ${"%.3f".format(ms)} ms (x6 ${"%.3f".format(ms * 6)} ms)")
        c.pointerUp(ToolPoint(x, grab.y + 1f))
        idle()
        tool.discard()
    }
}
