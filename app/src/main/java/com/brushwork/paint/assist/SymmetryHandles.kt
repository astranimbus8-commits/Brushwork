package com.brushwork.paint.assist

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A handle of the symmetry rulers on the canvas (v1.7 item 18). */
enum class SymmetryHandle {
    /** The centre (mirror, kaleidoscope, rotation) or the grid's origin corner (array). */
    CENTER,

    /** The angle knob, on the first axis (mirror, kaleidoscope, rotation). */
    ANGLE,

    /** The array cell's far corner along its first edge: sets "Spacing X" and the angle. */
    SPACING_X,

    /** The array cell's far corner along its second edge: sets "Spacing Y". */
    SPACING_Y,

    /** The perspective cell's corners: top left, top right, bottom right, bottom left. */
    CORNER_TL,
    CORNER_TR,
    CORNER_BR,
    CORNER_BL,
    ;

    /** The corner index (0..3, TL TR BR BL) of a perspective corner handle, else -1. */
    val corner: Int get() = if (ordinal >= CORNER_TL.ordinal) ordinal - CORNER_TL.ordinal else -1
}

/**
 * v1.7 (item 18, §3.18): where the symmetry rulers' handles are, shared by the guides
 * ([SymmetryGuides], which draw them) and the Symmetry tool (which drags them). Handles are
 * [HANDLE_DP] across for a finger; the angle knob sits [ARM_DP] from the centre on screen
 * whatever the zoom. Positions are document px; `docPerDp` = document px per dp at the
 * current zoom. Pure arithmetic.
 */
object SymmetryHandles {
    /** A handle's touch target, dp across (§3.18: "Handles are 44 dp"). */
    const val HANDLE_DP = 44f

    /** The drawn handle's radius, dp (the touch target is larger). */
    const val DRAWN_RADIUS_DP = 11f

    /** Distance of the angle knob from the centre, dp on screen. */
    const val ARM_DP = 76f

    /** A dragged angle within this many degrees of a multiple of [SNAP_STEP_DEG] snaps to it. */
    const val SNAP_DEG = 3f
    const val SNAP_STEP_DEG = 15f

    /** The handles of [type], in the order they are hit-tested when two overlap (first wins). */
    fun of(type: SymmetryType): List<SymmetryHandle> = when (type) {
        SymmetryType.OFF -> emptyList()
        SymmetryType.MIRROR, SymmetryType.KALEIDOSCOPE, SymmetryType.ROTATION -> listOf(SymmetryHandle.ANGLE, SymmetryHandle.CENTER)
        SymmetryType.ARRAY -> listOf(SymmetryHandle.SPACING_X, SymmetryHandle.SPACING_Y, SymmetryHandle.CENTER)
        SymmetryType.PERSPECTIVE_ARRAY -> listOf(SymmetryHandle.CORNER_TL, SymmetryHandle.CORNER_TR, SymmetryHandle.CORNER_BR, SymmetryHandle.CORNER_BL)
    }

    /** Where [h] of [s] is on a [docW] × [docH] canvas (document px). */
    fun position(s: SymmetrySettings, docW: Int, docH: Int, h: SymmetryHandle, docPerDp: Float): Vec2 {
        val c = SymmetryMaps.center(s, docW, docH)
        return when (h) {
            SymmetryHandle.CENTER -> c
            SymmetryHandle.ANGLE -> {
                val a = Math.toRadians(s.angleDeg.toDouble())
                val arm = ARM_DP * docPerDp
                Vec2(c.x + (cos(a) * arm).toFloat(), c.y + (sin(a) * arm).toFloat())
            }
            SymmetryHandle.SPACING_X, SymmetryHandle.SPACING_Y -> {
                val b = SymmetryMaps.arrayBasis(s)
                if (h == SymmetryHandle.SPACING_X) Vec2(c.x + b[0].toFloat(), c.y + b[1].toFloat())
                else Vec2(c.x + b[2].toFloat(), c.y + b[3].toFloat())
            }
            else -> {
                val q = SymmetryMaps.quad(s, docW, docH)
                Vec2(q[2 * h.corner], q[2 * h.corner + 1])
            }
        }
    }

    /**
     * The handle of [s] under document point ([x], [y]): the nearest one within half of
     * [HANDLE_DP], null when none is.
     */
    fun hit(s: SymmetrySettings, docW: Int, docH: Int, x: Float, y: Float, docPerDp: Float): SymmetryHandle? {
        val reach = HANDLE_DP / 2f * docPerDp
        var best: SymmetryHandle? = null
        var bestD = Float.MAX_VALUE
        for (h in of(s.type)) {
            val p = position(s, docW, docH, h, docPerDp)
            val d = hypot(p.x - x, p.y - y)
            if (d <= reach && d < bestD - 1e-3f) {
                best = h
                bestD = d
            }
        }
        return best
    }

    /**
     * The angle (degrees, -180..180) of the direction ([dx], [dy]) (y down), snapped to the
     * nearest multiple of [SNAP_STEP_DEG] within [SNAP_DEG]; null for no direction.
     */
    fun angleOf(dx: Float, dy: Float): Float? {
        if (hypot(dx, dy) < 1e-3f) return null
        val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val snapped = (a / SNAP_STEP_DEG).roundToInt() * SNAP_STEP_DEG
        return if (abs(a - snapped) <= SNAP_DEG) snapped else a
    }

    /**
     * [s] with handle [h] moved to ([x], [y]) (document px): the centre, the angle (snapped),
     * an array spacing (and, for "Spacing X", the grid's angle), or a perspective corner; null
     * when the corner would make the cell concave (the caller keeps the last valid cell). Spacings
     * stay within the settings' limits.
     */
    fun moved(s: SymmetrySettings, docW: Int, docH: Int, h: SymmetryHandle, x: Float, y: Float): SymmetrySettings? {
        val c = SymmetryMaps.center(s, docW, docH)
        return when (h) {
            SymmetryHandle.CENTER -> s.copy(centerX = x, centerY = y)
            SymmetryHandle.ANGLE -> angleOf(x - c.x, y - c.y)?.let { s.copy(centerX = c.x, centerY = c.y, angleDeg = it) } ?: s
            SymmetryHandle.SPACING_X -> {
                val len = hypot(x - c.x, y - c.y)
                // The first edge points along angleDeg − 90 (the grid's angle as ruler angles).
                val a = angleOf(x - c.x, y - c.y) ?: return s
                s.copy(centerX = c.x, centerY = c.y, spacingX = spacing(len), angleDeg = normalized(a + 90f))
            }
            SymmetryHandle.SPACING_Y -> {
                // The length along the second edge's own direction (the grid's angle stays).
                val b = SymmetryMaps.arrayBasis(s)
                val l = hypot(b[2], b[3]).toFloat().coerceAtLeast(1e-6f)
                val along = ((x - c.x) * b[2].toFloat() + (y - c.y) * b[3].toFloat()) / l
                s.copy(centerX = c.x, centerY = c.y, spacingY = spacing(along))
            }
            else -> {
                val q = SymmetryMaps.quad(s, docW, docH).toMutableList()
                q[2 * h.corner] = x
                q[2 * h.corner + 1] = y
                if (SymmetrySettings.isConvexQuad(q)) s.copy(quad = q) else null
            }
        }
    }

    /** [s] moved by ([dx], [dy]) document px: its centre, and its perspective cell. */
    fun translated(s: SymmetrySettings, docW: Int, docH: Int, dx: Float, dy: Float): SymmetrySettings {
        if (s.type == SymmetryType.PERSPECTIVE_ARRAY) {
            val q = SymmetryMaps.quad(s, docW, docH)
            val moved = List(8) { i -> q[i] + if (i % 2 == 0) dx else dy }
            return if (SymmetrySettings.isConvexQuad(moved)) s.copy(quad = moved) else s
        }
        val c = SymmetryMaps.center(s, docW, docH)
        return s.copy(centerX = c.x + dx, centerY = c.y + dy)
    }

    /** True when document point ([x], [y]) is inside [s]'s perspective cell. */
    fun insideQuad(s: SymmetrySettings, docW: Int, docH: Int, x: Float, y: Float): Boolean {
        val q = SymmetryMaps.quad(s, docW, docH)
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[2 * i]; val ay = q[2 * i + 1]
            val bx = q[2 * ((i + 1) % 4)]; val by = q[2 * ((i + 1) % 4) + 1]
            val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val sg = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (sg == 0) continue
            if (sign == 0) sign = sg else if (sg != sign) return false
        }
        return true
    }

    private fun spacing(v: Float): Float = max(SymmetrySettings.MIN_SPACING, v).coerceAtMost(SymmetrySettings.MAX_SPACING)

    /** [deg] in (-180, 180]. */
    fun normalized(deg: Float): Float {
        var a = deg % 360f
        if (a <= -180f) a += 360f
        if (a > 180f) a -= 360f
        return a
    }
}
