package com.brushwork.paint.tools.pathfinder

import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.pathfinder.PathConvert
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * v1.7 (§3.20, area G): Pathfinder on curved shapes, end to end. Two ELLIPSE shape layers
 * (circles of radius 50 whose centres are 50 apart, the Shape tool's own layers) united: one
 * "Pathfinder 1" vector layer holding one Bézier path (a few anchors with handles, not a
 * flattened polygon) of the union's area, rendered. Saved and reopened through the project
 * store, the layer comes back with the same name, the same path and the same pixels; one undo
 * gives back both ellipse layers with their shape data.
 */
@RunWith(RobolectricTestRunner::class)
class PathfinderRoundTripRobolectricTest {
    private val red = 0xFFDD2211.toInt()
    private val blue = 0xFF2244CC.toInt()

    /** A filled circle as the Shape tool's own layer (above the active one). */
    private fun circleLayer(c: EditorController, name: String, cx: Float, cy: Float, r: Float, color: Int): Layer {
        val o = ShapeObject(ShapeType.ELLIPSE, cx = cx, cy = cy, w = 2 * r, h = 2 * r, style = ShapeStyle.FILL, fillColor = color)
        return c.addLayerWithContent(name, "Add shape", shapeData = ShapeCodec.encode(o)) { canvas ->
            canvas.drawCircle(cx, cy, r, Paint().apply { this.color = color; isAntiAlias = true })
        }!!
    }

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    @Test
    fun twoEllipseLayersUniteIntoABezierPathThatSurvivesSaveAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "projects").deleteRecursively()
        val c = Smoke.controller(context, Smoke.document(300, 200, layers = 1))
        val raster = c.doc.layers[0]
        val a = circleLayer(c, "A", 100f, 100f, 50f, red)
        val b = circleLayer(c, "B", 150f, 100f, 50f, blue)
        val aData = a.shapeData
        val bData = b.shapeData
        c.selectTool(ToolId.PATHFINDER)
        val t = (c.currentTool as PathfinderTool).also { it.computeDispatcher = Dispatchers.Unconfined }
        tap(c, 70f, 100f)
        tap(c, 185f, 100f)
        assertEquals(listOf(a, b), t.operands.map { it.layer })
        t.apply(PathfinderOp.UNITE)

        assertEquals(2, c.doc.layers.size)
        val result = c.doc.layers[1]
        assertEquals(PathfinderLabels.resultLayer(1), result.name)
        val o = result.vector!!.objects.single() as VPath
        // Two discs of r = 50, 50 apart: 2πr² minus the lens 2r²·acos(d / 2r) − (d / 2)·√(4r² − d²).
        val union = 2 * PI * 2500 - (2 * 2500 * acos(0.5) - 25 * sqrt(10_000.0 - 2_500.0))
        assertEquals("the union's area", union, PathConvert.area(PathConvert.region(o)).toDouble(), union * 0.01)
        assertEquals(VPaint.Solid(blue), o.fill)
        val anchors = o.subpaths.sumOf { it.anchors.size }
        assertEquals("one outline", 1, o.subpaths.size)
        assertTrue("a Bézier path, not a polygon: $anchors anchors", anchors in 4..24)
        assertTrue("with handles", o.subpaths.single().anchors.any { it.outX != null || it.inX != null })
        val inside = listOf(100 to 100, 60 to 100, 190 to 100, 125 to 60)
        val outside = listOf(100 to 30, 20 to 20, 250 to 100)
        for ((x, y) in inside) assertEquals("rendered at ($x, $y)", 255, Color.alpha(result.bitmap.getPixel(x, y)))
        for ((x, y) in outside) assertEquals("nothing at ($x, $y)", 0, Color.alpha(result.bitmap.getPixel(x, y)))

        // Saved and reopened: the same layer, path and pixels.
        val repo = ProjectRepository(context)
        repo.save(c.doc, null)
        val loaded = repo.load(c.doc.id)
        assertEquals(listOf(raster.id, result.id), loaded.layers.map { it.id })
        val back = loaded.layers[1]
        assertEquals(result.name, back.name)
        assertTrue(back.isVectorLayer)
        assertEquals("the same path", o, back.vector!!.objects.single())
        for ((x, y) in inside + outside) {
            assertEquals("reopened pixel ($x, $y)", result.bitmap.getPixel(x, y), back.bitmap.getPixel(x, y))
        }

        // One undo: both ellipse layers, with their shape data.
        c.undo()
        assertEquals(listOf(raster.id, a.id, b.id), c.doc.layers.map { it.id })
        assertSame(a, c.doc.layers[1])
        assertEquals(aData, a.shapeData)
        assertEquals(bData, b.shapeData)
        Smoke.assertQuiet(c, "unite two ellipse layers")
    }
}
