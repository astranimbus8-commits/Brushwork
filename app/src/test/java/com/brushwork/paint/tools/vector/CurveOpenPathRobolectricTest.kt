package com.brushwork.paint.tools.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 6, design §3.6; area B's body of the F3 stub): `CurveTool.openPath` switches to the
 * Path tool on the layer and opens the path object there with the control points `select`
 * selected (the Shape tool's "Turn into path" selects the converted corner this way); indices past
 * the path are ignored, several selected show the group, an empty array selects nothing.
 */
@RunWith(RobolectricTestRunner::class)
class CurveOpenPathRobolectricTest {
    private val w = 400
    private val h = 300

    private val spline = VSpline(
        listOf(VSplinePoint(60f, 200f), VSplinePoint(120f, 80f, sharp = true), VSplinePoint(200f, 220f), VSplinePoint(280f, 70f), VSplinePoint(340f, 190f)),
        order = 4,
    )

    /** A background (active) and a vector layer holding one spline path; the Brush is active. */
    private fun controller(): Pair<EditorController, Layer> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        val vector = Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.layers += vector
        doc.activeLayerIndex = 1
        val c = EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx))
        c.tools
        val path = VPath(id = 0, subpaths = listOf(SplineBezier.toSubpath(spline)), stroke = VStrokeStyle(color = 0xFF203080.toInt(), width = 4f), spline = spline)
        c.vectors.addObjects(vector, listOf(path), "Import")
        c.selectLayer(0)
        return c to vector
    }

    @Test
    fun itOpensThePathInThePathToolWithThePointsSelected() {
        val (c, layer) = controller()
        val obj = layer.vector!!.objects.single() as VPath
        assertEquals(ToolId.BRUSH, c.activeToolId)
        // Called on the Curve tool's instance: the Path tool opens it all the same.
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.openPath(layer.id, obj.id, intArrayOf(1))
        val path = c.tools.getValue(ToolId.PATH) as CurveTool
        assertEquals(ToolId.PATH, c.activeToolId)
        assertSame(layer, c.activeLayer)
        assertTrue(path.isReopened)
        assertEquals(obj.spline, path.spline)
        assertEquals("the corner is selected", 1, path.selectedPoint)
        assertEquals(PointSelection.of(5, 1), path.pointSelection)
        assertFalse(curve.hasPendingWork)
        path.discard()

        // Several (and one past the end, ignored): the group.
        path.openPath(layer.id, obj.id, intArrayOf(0, 2, 9))
        assertTrue(path.isReopened)
        assertEquals(PointSelection.of(5, 0, 2), path.pointSelection)
        path.discard()
        // None.
        path.openPath(layer.id, obj.id, intArrayOf())
        assertTrue(path.isReopened)
        assertTrue(path.pointSelection.isEmpty)
        // A tap reopens it later with nothing selected (nothing carries over).
        path.discard()
        assertTrue(path.reopen(obj))
        assertTrue(path.pointSelection.isEmpty)
        path.discard()
    }

    @Test
    fun anUnknownLayerOrObjectChangesNothingBeyondTheTool() {
        val (c, layer) = controller()
        val path = c.tools.getValue(ToolId.PATH) as CurveTool
        path.openPath(layerId = -5L, objectId = 1L, select = intArrayOf())
        assertEquals("no such layer: nothing happens", ToolId.BRUSH, c.activeToolId)
        path.openPath(layer.id, objectId = 999L, select = intArrayOf())
        assertEquals("the Path tool is active on the layer", ToolId.PATH, c.activeToolId)
        assertSame(layer, c.activeLayer)
        assertFalse(path.isReopened)
        assertFalse(path.hasPendingWork)
    }
}
