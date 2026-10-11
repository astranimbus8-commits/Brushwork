package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * v1.8 F: the editor the cog's Robolectric tests draw in: a [w] × [h] document with a raster
 * layer ("Layer 1") and a vector layer ("Vector 1"), the vector one active when [vector], an
 * identity view, snapping off, a hard 6 px brush. [close] cancels the editors' scopes.
 */
internal class CogRig(val w: Int = 320, val h: Int = 280) {
    private val scopes = ArrayList<CoroutineScope>()

    fun controller(vector: Boolean = false): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("cog", "cog", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = if (vector) 1 else 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
            it.tools
            it.color = 0xFF2040C0.toInt()
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    /** The Shape tool set to draw a cog with [cog]'s fields (a plain line and fill unless changed by [more]). */
    fun cogTool(c: EditorController, cog: CogParams = CogParams.DEFAULT, editable: Boolean = true, more: (ShapeSettings) -> ShapeSettings = { it }): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            more(
                it.copy(
                    type = ShapeType.COG, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = 0xFF40C0E0.toInt(),
                    corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false, editable = editable,
                    cogTeeth = cog.teeth, cogDepth = cog.depth, cogToothWidth = cog.toothWidth, cogTaper = cog.taper, cogHole = cog.hole,
                ),
            )
        }
        return tool
    }

    fun drag(c: EditorController, vararg pts: Pair<Float, Float>) {
        c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) c.pointerMove(ToolPoint(pts[i].first, pts[i].second))
        c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    /** A cog dragged out from (60, 40) to (260, 240): a 200 px box. */
    fun drawCog(c: EditorController) = drag(c, 60f to 40f, 160f to 140f, 260f to 240f)

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    fun alphaAt(b: Bitmap, x: Int, y: Int): Int = b.getPixel(x, y) ushr 24

    fun close() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }
}
