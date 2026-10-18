package com.brushwork.paint.v19.a9

import android.content.Context
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
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * v1.9 A9: the editor rig of the Shape tests (a 300 x 240 document, "Layer 1" and, when asked,
 * "Vector 1" active above it; an identity view, so screen and document px are the same). Close it
 * in `@After`: the shape tool's state observer is global and would keep the editor alive.
 */
internal class A9Rig : AutoCloseable {
    val red = 0xFFFF0000.toInt()
    private val scopes = ArrayList<CoroutineScope>()

    fun controller(vectorLayer: Boolean = false, w: Int = 300, h: Int = 240): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("t", "t", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        if (vectorLayer) {
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 1
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.color = red
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    fun shapeTool(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return c.tools.getValue(ToolId.SHAPE) as ShapeTool
    }

    /** A pending rectangle: center (70, 60), 80 x 60, a 4 px outline. */
    fun drawRect(c: EditorController, tool: ShapeTool, type: ShapeType = ShapeType.RECTANGLE) {
        tool.update { it.copy(type = type, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f) }
        drag(c, 30f to 30f, 80f to 60f, 110f to 90f)
    }

    fun drag(c: EditorController, vararg pts: Pair<Float, Float>) {
        c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) c.pointerMove(ToolPoint(pts[i].first, pts[i].second))
        c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    fun tap(c: EditorController, x: Float, y: Float) = drag(c, x to y)

    override fun close() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }
}
