package com.brushwork.paint.v19.a1

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * v1.9 A1: the tool-level rig of the area's non-UI tests (Robolectric, no composition): an editor
 * controller on a 400 × 300 document whose active layer is a vector (or pixel) layer, as
 * `CurveMultiPointRobolectricTest` builds it.
 */
internal object A1Tools {
    const val W = 400
    const val H = 300

    /** The three curve tools. */
    val CURVES = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)

    fun controller(vector: Boolean = true): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", W, H)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(W, H))
        doc.layers += Layer(doc.newLayerId(), if (vector) "Vector 1" else "Layer 2", BitmapUtils.createLayerBitmap(W, H)).also {
            if (vector) it.vector = VectorContent.EMPTY
        }
        doc.activeLayerIndex = 1
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = 0xFF203080.toInt()
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.snapping.enabled = false
        }
    }

    fun EditorController.curveTool(id: ToolId): CurveTool {
        selectTool(id)
        return tools.getValue(id) as CurveTool
    }

    /** Five points along the document, each added after the last. */
    val FIVE = listOf(Vec2(40f, 60f), Vec2(100f, 40f), Vec2(160f, 80f), Vec2(220f, 50f), Vec2(280f, 90f))

    /** Tool [id] with the points [points] (none selected), and its controller. */
    fun withPoints(id: ToolId, points: List<Vec2> = FIVE, vector: Boolean = true): Pair<EditorController, CurveTool> {
        val c = controller(vector)
        val t = c.curveTool(id)
        for (p in points) assertTrue("$id adds $p", t.addAnchor(p))
        t.deselect()
        assertEquals(points.size, t.pointCount)
        return c to t
    }

    /** The in-tool steps [t] can undo now (it walks them back and forth). */
    fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }
}
