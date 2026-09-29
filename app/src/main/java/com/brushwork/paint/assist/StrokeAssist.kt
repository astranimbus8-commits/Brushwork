package com.brushwork.paint.assist

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.max
import kotlin.math.pow

/**
 * Applies ruler snapping (controller.ruler) and the stabilizer (controller.stabilizer) to the
 * raw input of painting tools. The ruler constraint is applied first, then the stabilizer; the
 * math lives in [StrokePipeline]. Screen-relative settings (snap distance, rope length, smoothing
 * lag) are converted to document px once per stroke — zoom can't change mid-stroke because a
 * second finger cancels it.
 */
class StrokeAssist(private val controller: EditorController) {
    private val pipeline = StrokePipeline()
    private val pts = FloatArray(4)

    private val ropeShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000; strokeCap = Paint.Cap.ROUND }
    private val ropePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xE6FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val rangeShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33000000 }
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ACCENT }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xB3000000.toInt() }

    /** Start of a stroke; returns the (possibly snapped) first point. */
    fun down(p: ToolPoint): ToolPoint = pipeline.down(p, paramsFor(controller.viewTransform, controller.stabilizer))

    /** Returns zero or more processed points to feed to the tool. */
    fun move(p: ToolPoint): List<ToolPoint> = pipeline.move(p)

    /** End of stroke; returns remaining points, the LAST one is used as the up point (never empty). */
    fun up(p: ToolPoint): List<ToolPoint> = pipeline.up(p)

    fun cancel() = pipeline.cancel()

    /** Screen-space overlay while a stroke is in progress (e.g. the rope line). */
    fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        if (pipeline.activeMode != StabilizerMode.ROPE) return
        pts[0] = pipeline.brushX; pts[1] = pipeline.brushY
        pts[2] = pipeline.fingerX; pts[3] = pipeline.fingerY
        t.matrix.mapPoints(pts)
        val bx = pts[0]; val by = pts[1]; val fx = pts[2]; val fy = pts[3]
        val radius = pipeline.ropeLength * t.zoom
        rangeShadow.strokeWidth = t.dp(3f)
        rangePaint.strokeWidth = t.dp(1f)
        canvas.drawCircle(fx, fy, radius, rangeShadow)
        canvas.drawCircle(fx, fy, radius, rangePaint)
        ropeShadow.strokeWidth = t.dp(3.5f)
        ropePaint.strokeWidth = t.dp(1.5f)
        canvas.drawLine(bx, by, fx, fy, ropeShadow)
        canvas.drawLine(bx, by, fx, fy, ropePaint)
        dotRing.strokeWidth = t.dp(1.5f)
        canvas.drawCircle(bx, by, t.dp(4f), dotPaint)
        canvas.drawCircle(bx, by, t.dp(4f), dotRing)
    }

    private fun paramsFor(t: ViewTransform, s: StabilizerSettings): StrokePipeline.Params {
        val zoom = t.zoom.coerceAtLeast(1e-4f)
        val docPerDp = t.dp(1f) / zoom
        return StrokePipeline.Params(
            ruler = controller.ruler.takeIf { it.enabled },
            mode = s.mode,
            catchUp = s.catchUp,
            snapDistance = SNAP_DISTANCE_DP * docPerDp,
            ropeLength = t.dp(s.ropeLengthDp.coerceAtLeast(0f)) / zoom,
            smoothLag = smoothLagDp(s.strength) * docPerDp,
            step = max(2f, 2f / zoom),
            minRadialDistance = RADIAL_DIRECTION_DP * docPerDp,
        )
    }

    companion object {
        /** A PARALLEL-mode stroke starting this close (screen dp) to the ruler snaps onto it. */
        const val SNAP_DISTANCE_DP = 40f
        /** SMOOTH at full strength makes the brush trail the finger by about this much (dp). */
        const val SMOOTH_MAX_LAG_DP = 60f
        /** RADIAL strokes starting on the center take their direction once this far away (dp). */
        const val RADIAL_DIRECTION_DP = 6f
        private const val ACCENT = 0xFF4DA3FF.toInt()

        /** Brush lag (dp) of the SMOOTH stabilizer for a strength of 0..1. */
        fun smoothLagDp(strength: Float): Float = strength.coerceIn(0f, 1f).pow(1.5f) * SMOOTH_MAX_LAG_DP
    }
}
