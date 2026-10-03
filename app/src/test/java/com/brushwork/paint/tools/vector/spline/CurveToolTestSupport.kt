package com.brushwork.paint.tools.vector.spline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Shared set-up of the v1.6 curve-tool Robolectric tests (area B): a 400 × 300 document, identity view. */
internal object CurveToolTestSupport {
    const val W = 400
    const val H = 300
    const val INK = 0xFF203080.toInt()

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
            it.color = INK
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.snapping.enabled = false
        }
    }

    fun EditorController.tool(id: ToolId): CurveTool {
        selectTool(id)
        return tools.getValue(id) as CurveTool
    }

    fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    fun EditorController.tap(x: Float, y: Float) = drag(x to y)

    fun EditorController.tap(p: Vec2) = drag(p.x to p.y)

    fun CurveTool.plainLine() = update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 4f) }

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** A fresh rendering of [content], as a vector layer's cache must be (I1). */
    fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(W, H)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, W, H), tips = TipCache(), document = Rect(0, 0, W, H))
        return pixels(b)
    }

    /** A point on [p]'s line (the middle of its flattened curve). */
    fun onLine(p: VPath): Vec2 {
        val pts = VectorOps.toVectorPath(p).flatten(0.25f).first().points
        return pts[pts.size / 2]
    }

    /** The flattened curve of [t]'s pending path (document px). */
    fun flattened(t: CurveTool): List<Vec2> = t.path().flatten(0.05f).first().points
}
