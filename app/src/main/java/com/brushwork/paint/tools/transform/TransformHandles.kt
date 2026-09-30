package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2
import kotlin.math.abs

/**
 * Gesture math for the transform handles. Every function derives the new state from the state
 * at the START of the gesture plus the finger's start/current document positions, so dragging
 * never accumulates rounding drift. Pure Kotlin.
 */
object TransformHandles {

    /** Drag inside the box: moves by whole document pixels (keeps pixel-exact copies crisp). */
    fun move(start: TransformState, from: Vec2, to: Vec2): TransformState =
        start.translated(TransformState.roundHalfUp(to.x - from.x), TransformState.roundHalfUp(to.y - from.y))

    /**
     * Free-transform corner drag: scales along the box axes around the opposite corner.
     * [keepAspect] scales both axes by the finger's projection on the diagonal. Dragging past
     * the anchor mirrors.
     */
    fun corner(start: TransformState, index: Int, from: Vec2, to: Vec2, keepAspect: Boolean): TransformState {
        val q = start.corner(index)
        val a = start.corner((index + 2) % 4)
        val target = snapTarget(start, q + (to - from))
        val lq = start.toLocalAxes(q - a)
        val lt = start.toLocalAxes(target - a)
        return if (keepAspect) {
            val d = lq.lengthSq
            val k = start.clampUniform(if (d < 1e-6f) 1f else lt.dot(lq) / d)
            start.scaledAbout(a, k, k)
        } else {
            start.scaledAbout(a, start.clampX(ratio(lt.x, lq.x)), start.clampY(ratio(lt.y, lq.y)))
        }
    }

    /**
     * Free-transform edge drag (edge i joins corners i and i+1: 0 top, 1 right, 2 bottom,
     * 3 left): scales one axis around the opposite edge.
     */
    fun edge(start: TransformState, edge: Int, from: Vec2, to: Vec2): TransformState {
        val q = mid(start.corner(edge), start.corner((edge + 1) % 4))
        val a = mid(start.corner((edge + 2) % 4), start.corner((edge + 3) % 4))
        val target = snapTarget(start, q + (to - from))
        val lq = start.toLocalAxes(q - a)
        val lt = start.toLocalAxes(target - a)
        return if (edge % 2 == 0) start.scaledAbout(a, 1f, start.clampY(ratio(lt.y, lq.y)))
        else start.scaledAbout(a, start.clampX(ratio(lt.x, lq.x)), 1f)
    }

    /**
     * Rotation handle: turns around [pivot] by the angle the finger swept. The absolute angle
     * snaps to multiples of [snapStepDeg] when within [snapToleranceDeg] (0 disables).
     */
    fun rotate(
        start: TransformState,
        pivot: Vec2,
        from: Vec2,
        to: Vec2,
        snapStepDeg: Float = 45f,
        snapToleranceDeg: Float = 2f,
    ): TransformState {
        val v0 = from - pivot
        val v1 = to - pivot
        if (v0.lengthSq < 1e-6f || v1.lengthSq < 1e-6f) return start
        var delta = Math.toDegrees((v1.angle - v0.angle).toDouble()).toFloat()
        if (snapStepDeg > 0f) {
            val target = start.rotationDeg + delta
            val nearest = TransformState.roundHalfUp(target / snapStepDeg) * snapStepDeg
            if (abs(target - nearest) <= snapToleranceDeg) delta = nearest - start.rotationDeg
        }
        return start.rotatedAbout(pivot, delta).snappedToPixels()
    }

    /** Distort: moves one corner freely. Null if the quad would stop being convex. */
    fun distortCorner(start: TransformState, index: Int, from: Vec2, to: Vec2): TransformState? {
        val pts = start.corners().toMutableList()
        pts[index] = pts[index] + (to - from)
        return start.withCorners(pts)
    }

    /** Distort: moves both corners of an edge. Null if the quad would stop being convex. */
    fun distortEdge(start: TransformState, edge: Int, from: Vec2, to: Vec2): TransformState? {
        val d = to - from
        val pts = start.corners().toMutableList()
        pts[edge] = pts[edge] + d
        pts[(edge + 1) % 4] = pts[(edge + 1) % 4] + d
        return start.withCorners(pts)
    }

    /**
     * Two-finger pinch: the content scales UNIFORMLY about [focus] (the fingers' midpoint when
     * the pinch started) by [scale], turns about it by [rotationDeg] (snapped, see
     * [pinchRotation]) and moves by [translation] — all relative to [start], so the content
     * under the fingers follows them. A distorted quad is transformed as a whole (every corner
     * moves), keeping its perspective. Sides never collapse below [TransformState.MIN_SIZE].
     */
    fun pinch(start: TransformState, focus: Vec2, translation: Vec2, scale: Float, rotationDeg: Float): TransformState {
        val k = start.clampUniform(if (scale.isFinite() && scale > 0f) scale else 1f)
        val delta = pinchRotation(start.rotationDeg, if (rotationDeg.isFinite()) rotationDeg else 0f)
        val tx = if (translation.x.isFinite()) translation.x else 0f
        val ty = if (translation.y.isFinite()) translation.y else 0f
        return start.scaledAbout(focus, k, k).rotatedAbout(focus, delta).translated(tx, ty)
    }

    /**
     * Rotation a pinch applies when the fingers turned by [deltaDeg] from an object at
     * [startDeg]. Fingers always turn a little while pinching to scale, so small turns keep the
     * original angle; the result also snaps to multiples of [stepDeg]. Both within
     * [toleranceDeg]. Returns the (normalized) change to apply.
     */
    fun pinchRotation(startDeg: Float, deltaDeg: Float, stepDeg: Float = 45f, toleranceDeg: Float = PINCH_SNAP_DEG): Float {
        val d = TransformState.normalizeDeg(deltaDeg)
        if (abs(d) <= toleranceDeg) return 0f
        if (stepDeg > 0f) {
            val target = startDeg + d
            val nearest = TransformState.roundHalfUp(target / stepDeg) * stepDeg
            if (abs(target - nearest) <= toleranceDeg) return TransformState.normalizeDeg(nearest - startDeg)
        }
        return d
    }

    /** Snap zone of [pinchRotation], degrees. */
    const val PINCH_SNAP_DEG = 4f

    private fun mid(a: Vec2, b: Vec2) = Vec2((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    private fun ratio(n: Float, d: Float): Float = if (abs(d) < 1e-3f) 1f else n / d

    /** Axis-aligned boxes resize to whole pixels so edges stay crisp. */
    private fun snapTarget(start: TransformState, p: Vec2): Vec2 =
        if (start.isAxisAligned) Vec2(TransformState.roundHalfUp(p.x), TransformState.roundHalfUp(p.y)) else p
}

/** What a touch on the transform box grabbed. */
enum class HandleKind { MOVE, CORNER, EDGE, ROTATE }

data class HandleHit(val kind: HandleKind, val index: Int = -1)

/**
 * Screen-space positions of the transform handles for one frame; shared by hit testing and
 * drawing so both always agree. Pure Kotlin: [toScreen] maps document to screen pixels.
 */
class HandleLayout private constructor(
    /** Corner handles (screen), in source order TL, TR, BR, BL. */
    val corners: List<Vec2>,
    /** Edge midpoints (screen): 0 top, 1 right, 2 bottom, 3 left. */
    val edges: List<Vec2>,
    /** Edge handles are hidden on edges too short to grab without hitting a corner. */
    val edgeVisible: List<Boolean>,
    val center: Vec2,
    /** Edge the rotation handle sticks out of (the one highest on screen). */
    val rotateEdge: Int,
    val rotateHandle: Vec2,
    val cornerHitRadius: Float,
    val edgeHitRadius: Float,
    val rotateHitRadius: Float,
) {
    /** The nearest handle within its hit radius, else MOVE (anywhere else drags the content). */
    fun hitTest(p: Vec2): HandleHit {
        var best = HandleHit(HandleKind.MOVE)
        var bestScore = 1f
        fun consider(pos: Vec2, radius: Float, hit: HandleHit) {
            val score = pos.distanceTo(p) / radius
            if (score < bestScore) { bestScore = score; best = hit }
        }
        for (i in 0 until 4) consider(corners[i], cornerHitRadius, HandleHit(HandleKind.CORNER, i))
        for (i in 0 until 4) if (edgeVisible[i]) consider(edges[i], edgeHitRadius, HandleHit(HandleKind.EDGE, i))
        consider(rotateHandle, rotateHitRadius, HandleHit(HandleKind.ROTATE))
        return best
    }

    companion object {
        const val CORNER_HIT_DP = 22f
        const val EDGE_HIT_DP = 18f
        const val ROTATE_HIT_DP = 24f
        const val ROTATE_OFFSET_DP = 36f
        const val MIN_EDGE_FOR_HANDLE_DP = 48f

        /**
         * [density] is screen pixels per dp. [rotateEdge] pins the rotation handle to one edge
         * (used while it is being dragged so it doesn't jump between edges).
         */
        fun compute(state: TransformState, toScreen: (Vec2) -> Vec2, density: Float, rotateEdge: Int? = null): HandleLayout {
            val corners = state.corners().map(toScreen)
            val edges = List(4) { i ->
                val a = corners[i]; val b = corners[(i + 1) % 4]
                Vec2((a.x + b.x) / 2f, (a.y + b.y) / 2f)
            }
            val center = Vec2(corners.sumOf { it.x.toDouble() }.toFloat() / 4f, corners.sumOf { it.y.toDouble() }.toFloat() / 4f)
            val lengths = List(4) { corners[it].distanceTo(corners[(it + 1) % 4]) }
            val minLen = lengths.min()
            val cornerHit = (minLen * 0.3f).coerceIn(8f * density, CORNER_HIT_DP * density)
            val edgeVisible = lengths.map { it >= MIN_EDGE_FOR_HANDLE_DP * density }
            val edgeHit = (minLen * 0.25f).coerceIn(8f * density, EDGE_HIT_DP * density)
            val re = rotateEdge ?: (0 until 4).minBy { edges[it].y }
            var dir = (edges[re] - center).normalized()
            if (dir.lengthSq < 0.5f) dir = Vec2(0f, -1f)
            val rotateHandle = edges[re] + dir * (ROTATE_OFFSET_DP * density)
            return HandleLayout(corners, edges, edgeVisible, center, re, rotateHandle, cornerHit, edgeHit, ROTATE_HIT_DP * density)
        }
    }
}
