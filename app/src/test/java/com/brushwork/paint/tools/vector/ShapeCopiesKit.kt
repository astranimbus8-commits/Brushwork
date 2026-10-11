package com.brushwork.paint.tools.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.8 F (design §3.1): the editor the shape-copies tests draw in, on [CogRig] (a 320 × 280
 * document, a raster "Layer 1" and a vector "Vector 1", identity view, snapping off). The default
 * mirror ruler's axis is the vertical line x = 160: a point (x, y) has its copy at (320 − x, y).
 */
internal class ShapeCopiesKit {
    val rig = CogRig()
    val w get() = rig.w
    val h get() = rig.h

    fun controller(vector: Boolean = false, symmetry: SymmetryType = SymmetryType.MIRROR): EditorController =
        rig.controller(vector).also { it.updateSymmetry(SymmetrySettings(type = symmetry)) }

    /** The Shape tool set to draw sharp rectangles in [style] (a 4 px plain line) with [fill]. */
    fun rectTool(c: EditorController, style: ShapeStyle, editable: Boolean, fill: Int = 0xFF40C0E0.toInt(), more: (ShapeSettings) -> ShapeSettings = { it }): ShapeTool =
        rig.cogTool(c, editable = editable) { more(it.copy(type = ShapeType.RECTANGLE, style = style, fillColor = fill)) }

    /** A rectangle dragged from ([x0], [y0]) to ([x1], [y1]), left open. */
    fun drawRect(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float) =
        rig.drag(c, x0 to y0, (x0 + x1) / 2f to (y0 + y1) / 2f, x1 to y1)

    /** Runs the main looper for [ms] (a brush preview replays there). */
    fun idle(ms: Long = 300L) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A tap at ([x], [y]). */
    fun tap(c: EditorController, x: Float, y: Float) = rig.drag(c, x to y)

    /** The document as the canvas shows it (previews included). */
    fun composite(c: EditorController): Bitmap {
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        c.compositor.drawDocument(Canvas(out), null, target = null)
        return out
    }

    /** The overlays drawn over the canvas (a dragged preview; the view is the identity). */
    fun overlay(c: EditorController): Bitmap {
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        c.drawOverlays(Canvas(out), 0f)
        return out
    }

    /** True when the canvas or its overlay shows something at ([x], [y]). */
    fun shownAt(c: EditorController, x: Int, y: Int): Boolean =
        rig.alphaAt(composite(c), x, y) != 0 || rig.alphaAt(overlay(c), x, y) != 0

    /** What the vector renderer draws for [content] on the whole document. */
    fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return rig.pixels(b)
    }

    fun alphaAt(b: Bitmap, x: Int, y: Int) = rig.alphaAt(b, x, y)

    /** Where [m] (row-major 3 × 3) takes ([x], [y]). */
    fun mapped(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val z = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / z to (m[3] * x + m[4] * y + m[5]) / z
    }

    fun close() = rig.close()
}
