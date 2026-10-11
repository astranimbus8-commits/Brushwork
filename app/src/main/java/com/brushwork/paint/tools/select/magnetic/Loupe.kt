package com.brushwork.paint.tools.select.magnetic

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.EyedropperTool
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The magnetic lasso's loupe (v1.8, design §3.4 and D12): while a finger places or drags an
 * anchor, a [DIAMETER_DP] circle shows the canvas under it at [MAGNIFICATION] times the view's
 * zoom, with the outline, the live segment and a crosshair on the finger's point. It sits
 * [OFFSET_DP] above the finger, and below it when the finger is in the top [FLIP_DP] of the
 * canvas: the finger never hides the edge being traced. The canvas view runs under the editor's
 * top bars, so "the top of the canvas" is the bars' lower edge ([chromeTop]): the loupe is never
 * drawn under them.
 *
 * Immutable; the tool makes one per finger position. Positions are canvas-view px.
 */
class Loupe internal constructor(
    /** The circle's centre (view px). */
    val center: Vec2,
    /** True when the loupe is below the finger (the finger is near the canvas's top). */
    val flipped: Boolean,
    /** The document point under the crosshair (the finger's). */
    val focus: Vec2,
    /** The finger (view px). */
    val finger: Vec2,
) {
    /** Draws the loupe: the canvas, then [content] (view px, magnified with it), the crosshair and the rim. */
    internal fun draw(canvas: Canvas, t: ViewTransform, compositor: Compositor, docW: Int, docH: Int, content: (Canvas) -> Unit) {
        val r = t.dp(DIAMETER_DP) / 2f
        val k = MAGNIFICATION
        canvas.save()
        clip.reset()
        clip.addCircle(center.x, center.y, r, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawColor(PAPER)
        // View px to the loupe: [k] times about the finger, the finger at the centre.
        magnify.setTranslate(-finger.x, -finger.y)
        magnify.postScale(k, k)
        magnify.postTranslate(center.x, center.y)
        canvas.concat(magnify)
        // The document area the circle shows: the circle's box back in document px.
        val half = r / k
        area.set(finger.x - half, finger.y - half, finger.x + half, finger.y + half)
        t.inverse.mapRect(area)
        docArea.set(
            floor(area.left).toInt().coerceIn(0, docW), floor(area.top).toInt().coerceIn(0, docH),
            ceil(area.right).toInt().coerceIn(0, docW), ceil(area.bottom).toInt().coerceIn(0, docH),
        )
        if (!docArea.isEmpty) {
            canvas.save()
            canvas.concat(t.matrix)
            canvas.clipRect(docArea)
            compositor.drawDocument(canvas, docArea, target = null)
            canvas.restore()
        }
        content(canvas)
        canvas.restore()

        val arm = t.dp(CROSS_DP)
        line.strokeWidth = t.dp(3f)
        line.color = HALO
        cross(canvas, arm)
        line.strokeWidth = t.dp(1.25f)
        line.color = INK
        cross(canvas, arm)
        rim.strokeWidth = t.dp(2f)
        canvas.drawCircle(center.x, center.y, r, rim)
    }

    private fun cross(canvas: Canvas, arm: Float) {
        canvas.drawLine(center.x - arm, center.y, center.x + arm, center.y, line)
        canvas.drawLine(center.x, center.y - arm, center.x, center.y + arm, line)
    }

    companion object {
        /** The circle's diameter (dp). */
        const val DIAMETER_DP = 104f

        /** Times the view's zoom. */
        const val MAGNIFICATION = 3f

        /** From the finger to the circle's centre (dp). */
        const val OFFSET_DP = 96f

        /** A finger this close (dp) to the canvas's top (under the editor's bars) flips the loupe below it. */
        const val FLIP_DP = 140f

        private const val CROSS_DP = 9f
        private const val PAPER = 0xFFFFFFFF.toInt()
        private const val INK = 0xFF1E1F22.toInt()
        private const val HALO = 0xCCFFFFFF.toInt()

        // Drawing scratch (main thread only, as every overlay).
        private val clip = Path()
        private val magnify = Matrix()
        private val area = RectF()
        private val docArea = Rect()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF4DA3FF.toInt() }

        /**
         * The loupe for a finger on document point [focus] in view [t]; [top]: the view px the
         * editor's top bars cover ([chromeTop]), the canvas's top for the flip.
         */
        fun at(focus: Vec2, t: ViewTransform, top: Float = 0f): Loupe {
            val f = t.docToScreen(focus)
            val flipped = f.y < top + t.dp(FLIP_DP)
            val dy = t.dp(OFFSET_DP)
            return Loupe(Vec2(f.x, if (flipped) f.y + dy else f.y - dy), flipped, focus, f)
        }

        /**
         * The view px of the canvas the editor's top bars cover (the top row and the options
         * strip), as the editor last measured them (`CanvasView.setFitInsets` tells the
         * eyedropper, whose color preview keeps clear of them too); 0 before that.
         */
        internal fun chromeTop(controller: EditorController): Float =
            (controller.tools[ToolId.EYEDROPPER] as? EyedropperTool)?.chromeTop ?: 0f
    }
}
