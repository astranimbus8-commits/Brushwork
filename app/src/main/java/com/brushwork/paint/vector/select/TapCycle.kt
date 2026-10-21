package com.brushwork.paint.vector.select

import kotlin.math.hypot

/**
 * v1.10 (R1, J19): counts same-spot taps, spatially only, as v1.9's cycle does (no timer). A1 maps
 * the depth onto its candidate list (side, whole object, then v1.9's deeper list).
 *
 * A tap is at the "same spot" when it is within [SAME_SPOT_DP] of the previous tap and has the
 * same selection key, with no [reset] in between. There is no time limit: the user may look at the
 * picked side and tap again later. The cycle never reads a clock.
 */
class TapCycle {
    private var hasLast = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastKey: Any? = null
    private var depth = 0

    /** 0 for a spot more than SAME_SPOT_DP away or a new key; else the previous depth + 1. [xDp], [yDp] in dp; [key] = selection key. */
    fun next(xDp: Float, yDp: Float, key: Any?): Int {
        val same = hasLast && key == lastKey && hypot(xDp - lastX, yDp - lastY) <= SAME_SPOT_DP
        depth = if (same) depth + 1 else 0
        hasLast = true
        lastX = xDp
        lastY = yDp
        lastKey = key
        return depth
    }

    /** Called by A1 after any drag, pinch or handle gesture in between, and on a tool switch: the next tap starts at 0. */
    fun reset() {
        hasLast = false
        lastKey = null
        depth = 0
    }

    companion object { const val SAME_SPOT_DP = 16f }
}
