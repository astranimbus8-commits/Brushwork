package com.brushwork.paint.v110.a5

import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.drag
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
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * v1.10 (A5, R8 cause E): a reopened path that is the only object of its vector layer does not
 * snap to its own old extent. While a path is open, the layer's pixels still hold it as it was
 * (the edit session only hides it on screen), so the layer's content bounds are the path's own
 * bounds before the edit; in 1.9 the Curve tools began their snap without excluding the layer and
 * a dragged point jumped back onto that old edge. (Shape already excluded its layer.)
 */
@Ignore("v110-A5 repro: fails on 1.9")
@RunWith(RobolectricTestRunner::class)
class CurveSelfSnapExcludeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    /** The last control point is the path's right end (the curve ends there). */
    private val spline = VSpline(
        listOf(VSplinePoint(60f, 200f), VSplinePoint(120f, 80f, sharp = true), VSplinePoint(200f, 220f), VSplinePoint(280f, 70f), VSplinePoint(340f, 190f)),
        order = 4,
    )

    /**
     * A 400 x 300 canvas at zoom 1 with an empty raster layer and the vector layer "Vector 1"
     * whose only object is the spline path [spline] (4 px line), opened in the Path tool.
     */
    private fun opened(): Triple<EditorController, Layer, CurveTool> {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(400, 300))
        val vector = Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        doc.layers += vector
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
        val path = VPath(id = 0, subpaths = listOf(SplineBezier.toSubpath(spline)), stroke = VStrokeStyle(color = 0xFF203080.toInt(), width = 4f), spline = spline)
        c.vectors.addObjects(vector, listOf(path), "Import")
        idle()
        val obj = vector.vector!!.objects.single() as VPath
        val tool = c.tools.getValue(ToolId.PATH) as CurveTool
        tool.openPath(vector.id, obj.id, intArrayOf())
        idle()
        assertTrue("the path is open", tool.isReopened)
        assertEquals("it is the layer's only object", 1, vector.vector!!.objects.size)
        c.snapping.enabled = true
        return Triple(c, vector, tool)
    }

    @Test
    fun aDraggedPointOfTheOnlyObjectIgnoresItsOwnLayersOldEdge() {
        val (c, layer, tool) = opened()
        // What the layer's content bounds are while the path is open: its old extent.
        c.snapping.prepare()
        idle()
        val bounds = c.snapping.bounds(layer)
        assertNotNull("the layer's pixels still hold the old path", bounds)
        val oldRight = bounds!!.right.toFloat()
        val start = Vec2(340f, 190f)
        assertTrue("the old right edge is next to the end point ($oldRight)", abs(oldRight - start.x) < 6f)
        // 5 px past the old edge: within reach of it, and of nothing else on x (the canvas edge
        // 400, its centre 200 and the other points' x 60..280 are far).
        val to = Vec2(oldRight + 5f, 190f)
        assertTrue(abs(to.x - 400f) >= 10f)
        c.drag(start, start + Vec2(12f, 0f), to)
        assertEquals("the point stays where the finger put it", to.x, tool.pointAt(4).x, 0.01f)
        tool.discard()
    }
}
