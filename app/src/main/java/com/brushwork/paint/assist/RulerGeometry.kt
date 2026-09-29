package com.brushwork.paint.assist

import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Draggable parts of the ruler shown while the ruler tool is active. */
enum class RulerHandle { CENTER, ROTATE, RADIUS, RADIUS_X, RADIUS_Y }

/**
 * Pure geometry shared by [RulerRenderer] (drawing handles) and [RulerTool] (hit testing and
 * dragging), so both always agree. Positions are document px; `docPerDp` is the number of
 * document px per screen dp at the current zoom (`t.dp(1f) / t.zoom`).
 */
object RulerGeometry {
    /** Drawn size of a handle (dp). */
    const val HANDLE_DP = 14f
    /** Touch radius around a handle (dp). */
    const val HIT_DP = 26f
    /** Distance of the rotation handle from the center for straight/radial rulers (dp). */
    const val ROTATE_ARM_DP = 88f
    /** Gap between the ellipse's rx handle and its rotation handle (dp). */
    const val ELLIPSE_ROTATE_GAP_DP = 44f
    const val ANGLE_SNAP_STEP = 15f
    const val ANGLE_SNAP_TOLERANCE = 2f
    /** Smallest radius / semi-axis the handles allow (document px). */
    const val MIN_RADIUS = 1f

    /** Handles of [type], in hit-test priority order (the center last so resize handles win ties). */
    fun handlesOf(type: RulerType): List<RulerHandle> = when (type) {
        RulerType.STRAIGHT, RulerType.RADIAL -> LINE_HANDLES
        RulerType.CIRCLE -> CIRCLE_HANDLES
        RulerType.ELLIPSE -> ELLIPSE_HANDLES
    }

    private val LINE_HANDLES = listOf(RulerHandle.ROTATE, RulerHandle.CENTER)
    private val CIRCLE_HANDLES = listOf(RulerHandle.RADIUS, RulerHandle.CENTER)
    private val ELLIPSE_HANDLES = listOf(RulerHandle.ROTATE, RulerHandle.RADIUS_X, RulerHandle.RADIUS_Y, RulerHandle.CENTER)

    /** Document position of handle [h] of [r]. */
    fun handlePosition(r: RulerSettings, h: RulerHandle, docPerDp: Float): Vec2 {
        val a = r.angleDeg * Geometry.DEG
        val ux = cos(a); val uy = sin(a)
        return when (h) {
            RulerHandle.CENTER -> Vec2(r.centerX, r.centerY)
            RulerHandle.ROTATE -> {
                val arm = if (r.type == RulerType.ELLIPSE) r.radiusX + ELLIPSE_ROTATE_GAP_DP * docPerDp else ROTATE_ARM_DP * docPerDp
                Vec2(r.centerX + ux * arm, r.centerY + uy * arm)
            }
            RulerHandle.RADIUS -> Vec2(r.centerX + r.radius, r.centerY)
            RulerHandle.RADIUS_X -> Vec2(r.centerX + ux * r.radiusX, r.centerY + uy * r.radiusX)
            RulerHandle.RADIUS_Y -> Vec2(r.centerX - uy * r.radiusY, r.centerY + ux * r.radiusY)
        }
    }

    /** The handle under document point (x, y), or null (then a drag moves the whole ruler). */
    fun hitHandle(r: RulerSettings, x: Float, y: Float, docPerDp: Float, hitDp: Float = HIT_DP): RulerHandle? {
        val limit = hitDp * docPerDp
        var best: RulerHandle? = null
        var bestD = Float.MAX_VALUE
        for (h in handlesOf(r.type)) {
            val p = handlePosition(r, h, docPerDp)
            val d = hypot(p.x - x, p.y - y)
            if (d <= limit && d < bestD) { best = h; bestD = d }
        }
        return best
    }

    /**
     * The ruler after dragging [handle] (null = the body) from (downX, downY) to (x, y), starting
     * from [start]. Relative, so grabbing a handle off-center doesn't make it jump.
     */
    fun drag(start: RulerSettings, handle: RulerHandle?, downX: Float, downY: Float, x: Float, y: Float): RulerSettings {
        val cx = start.centerX; val cy = start.centerY
        return when (handle) {
            null, RulerHandle.CENTER -> start.copy(centerX = cx + (x - downX), centerY = cy + (y - downY))
            RulerHandle.ROTATE -> {
                val a0 = atan2(downY - cy, downX - cx)
                val a1 = atan2(y - cy, x - cx)
                val deg = start.angleDeg + StrokeConstraint.wrapAngle(a1 - a0) / Geometry.DEG
                start.copy(angleDeg = snapAngle(normalizeAngle(deg)))
            }
            RulerHandle.RADIUS -> {
                val delta = hypot(x - cx, y - cy) - hypot(downX - cx, downY - cy)
                start.copy(radius = (start.radius + delta).coerceAtLeast(MIN_RADIUS))
            }
            RulerHandle.RADIUS_X -> {
                val a = start.angleDeg * Geometry.DEG
                val ux = cos(a); val uy = sin(a)
                val delta = (x - downX) * ux + (y - downY) * uy
                start.copy(radiusX = (start.radiusX + delta).coerceAtLeast(MIN_RADIUS))
            }
            RulerHandle.RADIUS_Y -> {
                val a = start.angleDeg * Geometry.DEG
                val vx = -sin(a); val vy = cos(a)
                val delta = (x - downX) * vx + (y - downY) * vy
                start.copy(radiusY = (start.radiusY + delta).coerceAtLeast(MIN_RADIUS))
            }
        }
    }

    /** Snaps [deg] to the nearest multiple of 15° when it is within 2° of it. */
    fun snapAngle(deg: Float, step: Float = ANGLE_SNAP_STEP, tolerance: Float = ANGLE_SNAP_TOLERANCE): Float {
        val m = (deg / step).roundToInt() * step
        return if (abs(deg - m) <= tolerance) normalizeAngle(m) else deg
    }

    /** Normalizes an angle in degrees to (-180, 180]. */
    fun normalizeAngle(deg: Float): Float {
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    /** [r] moved to the center of a [width] x [height] canvas. */
    fun centered(r: RulerSettings, width: Int, height: Int): RulerSettings =
        r.copy(centerX = width / 2f, centerY = height / 2f)

    /**
     * [r] with its geometry reset to defaults sized for a [width] x [height] canvas (type, snap
     * mode, unit, nudge step and on/off state are kept).
     */
    fun reset(r: RulerSettings, width: Int, height: Int): RulerSettings {
        val m = min(width, height).toFloat().coerceAtLeast(8f)
        return r.copy(
            centerX = width / 2f,
            centerY = height / 2f,
            angleDeg = 0f,
            radius = m * 0.3f,
            radiusX = m * 0.38f,
            radiusY = m * 0.24f,
            radialLines = RulerSettings().radialLines,
        )
    }
}
