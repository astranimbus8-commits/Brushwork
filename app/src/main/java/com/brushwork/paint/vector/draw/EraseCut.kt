package com.brushwork.paint.vector.draw

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.pathfinder.PathConvert
import com.brushwork.paint.vector.pathfinder.PathfinderOps
import com.brushwork.paint.vector.pathfinder.PathfinderStyle
import kotlin.math.max

/**
 * v1.9 (R11 b, design 2.4.12; owner A5): the boolean cut of vector objects by an eraser region
 * (Illustrator's Minus Front, with the eraser in front). Per target ([cut]):
 * - its region is [PathConvert.region]: a path's outline under its fill rule, a shape's outline,
 *   live copies united (I17). A brush stroke and a line or arrow shape are no operand: their
 *   region is not what they paint, so they are untouched here (the eraser cuts or removes them as
 *   in v1.8);
 * - the eraser must take at least a sliver ([PathfinderOps.SLIVER_AREA]) of the region, else the
 *   target is untouched: a graze of its outline's outer half leaves it as it is;
 * - the pieces are the connected components of the region minus the eraser
 *   ([PathConvert.components]). Each becomes a plain [VPath] in the object's style
 *   ([PathfinderStyle.objectOf]: fill, stroke, opacity), so the cut edge is stroked too; a shape
 *   reopens as a Bézier path. Slivers are dropped, and nothing left removes the object;
 * - more than [MAX_PIECES] pieces or a failed op (Skia's PathOps gave up) leaves it unchanged.
 *
 * [cutAll] cuts a gesture's targets: the topmost [MAX_TARGETS], each object with live copies baked
 * first (every copy cut on its own), the eraser simplified once. Pure and thread-safe: it runs on
 * `Dispatchers.Default` over immutable objects and paths of its own.
 */
object EraseCut {
    const val MAX_PIECES = 256
    const val MAX_TARGETS = 500

    sealed interface Outcome {
        /** The pieces that stay; empty = the object is removed. */
        data class Cut(val pieces: List<VObject>) : Outcome

        /** A cap was hit; the object is unchanged. */
        data object Partly : Outcome

        /** The op failed or nothing was touched. */
        data object Unchanged : Outcome
    }

    /** What [cutOne] found: an [Outcome] whose failed op is told apart from an untouched target. */
    internal sealed interface Detail {
        /** The pieces that stay (plain paths, id 0); empty = the object is removed. */
        data class Pieces(val pieces: List<VPath>) : Detail

        /** More than [MAX_PIECES] pieces: the object is unchanged. */
        data object TooMany : Detail

        /** Skia's PathOps gave up: the object is unchanged. */
        data object Failed : Detail

        /** No operand, or the eraser takes nothing of its region: the object is unchanged. */
        data object Untouched : Detail
    }

    /** The boolean cut of one target (R11 b). Pure; runs on Dispatchers.Default over immutable copies. */
    fun cut(target: VObject, eraser: Path): Outcome = when (val d = cutOne(target, eraser)) {
        is Detail.Pieces -> Outcome.Cut(d.pieces)
        Detail.TooMany -> Outcome.Partly
        Detail.Failed, Detail.Untouched -> Outcome.Unchanged
    }

    /** True when [o] is an operand of the boolean cut: a path, or a shape that is not a line or an arrow. */
    fun isOperand(o: VObject): Boolean = when (o) {
        is VPath -> true
        is VShape -> !o.shape.type.isLineLike
        is VStroke -> false
    }

    /** [cut], telling a failed op from an untouched target (the caller's toast is for the first only). */
    internal fun cutOne(target: VObject, eraser: Path): Detail {
        if (!isOperand(target)) return Detail.Untouched
        val region = PathConvert.region(target) ?: return Detail.Untouched
        if (region.isEmpty || eraser.isEmpty) return Detail.Untouched
        if (apart(region, eraser)) return Detail.Untouched
        val bite = PathConvert.op(region, eraser, Path.Op.INTERSECT) ?: return Detail.Failed
        if (!PathfinderOps.kept(bite)) return Detail.Untouched
        val rest = PathConvert.op(region, eraser, Path.Op.DIFFERENCE) ?: return Detail.Failed
        val parts = PathConvert.components(rest, simple = true).filter { PathfinderOps.kept(it) }
        if (parts.size > MAX_PIECES) return Detail.TooMany
        val style = styleOf(target) ?: return Detail.Untouched
        return Detail.Pieces(parts.mapNotNull { style.objectOf(it) })
    }

    private fun styleOf(o: VObject): PathfinderStyle? = when (o) {
        is VPath -> PathfinderStyle.of(o)
        is VShape -> PathfinderStyle.of(o)
        is VStroke -> null
    }

    /**
     * True when the boxes of [a] and [b] are apart. A box that is empty or not finite proves nothing
     * (Skia gives a path that is not finite an empty box): such a pair goes on to the op, which
     * fails on a path that is not finite.
     */
    private fun apart(a: Path, b: Path): Boolean {
        val ra = RectF()
        val rb = RectF()
        @Suppress("DEPRECATION") a.computeBounds(ra, true)
        @Suppress("DEPRECATION") b.computeBounds(rb, true)
        if (!finite(ra) || !finite(rb) || ra.isEmpty || rb.isEmpty) return false
        return ra.right < rb.left || rb.right < ra.left || ra.bottom < rb.top || rb.bottom < ra.top
    }

    private fun finite(r: RectF): Boolean = r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite()

    /** What [cutAll] did to a gesture's targets. */
    internal class Cuts(
        /** Per cut object's id: what stays of it, in its place (empty: it is removed). Objects left as they were are not in it. */
        val pieces: Map<Long, List<VObject>>,
        /** Some objects were left as they were because their cut was too complex: a cap or a failed op. */
        val tooComplex: Boolean,
        /** A cut object had live copies: each copy is now a piece of its own. */
        val copiesBaked: Boolean,
    ) {
        companion object {
            val NONE = Cuts(emptyMap(), tooComplex = false, copiesBaked = false)
        }
    }

    /**
     * The boolean cut of [targets] (in their layer's order, bottom first; distinct ids) by
     * [eraser]: the topmost [MAX_TARGETS] are cut, the rest stay ([Cuts.tooComplex]). An object
     * with live copies is baked first ([ObjectCopies.expanded], as v1.8's partial eraser does):
     * each copy is cut on its own, and when one of them changes, all of them replace the object.
     * [eraser] is simplified once (a round-capped stroke region overlaps itself everywhere) and not
     * changed.
     */
    internal fun cutAll(targets: List<VObject>, eraser: Path): Cuts {
        if (targets.isEmpty() || eraser.isEmpty) return Cuts.NONE
        val over = targets.size > MAX_TARGETS
        val cut = if (over) targets.subList(targets.size - MAX_TARGETS, targets.size) else targets
        val e = PathConvert.op(eraser, Path(), Path.Op.UNION) ?: return Cuts(emptyMap(), tooComplex = true, copiesBaked = false)
        val out = HashMap<Long, List<VObject>>()
        var tooComplex = over
        var baked = false
        for (o in cut) {
            val copies = ObjectCopies.expanded(o)
            if (copies.size == 1) {
                when (val d = cutOne(o, e)) {
                    is Detail.Pieces -> out[o.id] = d.pieces
                    Detail.TooMany, Detail.Failed -> tooComplex = true
                    Detail.Untouched -> {}
                }
                continue
            }
            val pieces = ArrayList<VObject>(copies.size)
            var changed = false
            var refused = false
            for (copy in copies) {
                when (val d = cutOne(copy, e)) {
                    is Detail.Pieces -> { pieces += d.pieces; changed = true }
                    Detail.TooMany, Detail.Failed -> { pieces += copy; refused = true }
                    Detail.Untouched -> pieces += copy
                }
            }
            // A copy too complex to cut stays whole beside its cut siblings (they are baked anyway).
            if (refused) tooComplex = true
            if (changed) { out[o.id] = pieces; baked = true }
        }
        return Cuts(out, tooComplex, baked)
    }

    /**
     * The region the vector eraser swept: the union of its capsules as [EraseSession.add] builds
     * them from the [n] points ([xs], [ys]) with radii [rs] (the capsule from point k - 1 to k has
     * the larger of their radii, at least 0.5 px; one point is a disc). Runs of capsules of one
     * radius are one [strokeRegion]: a finger's whole gesture is one. Points that are not finite
     * are skipped, as the session skips them.
     */
    fun sweptRegion(xs: FloatArray, ys: FloatArray, rs: FloatArray, n: Int): Path {
        val out = Path()
        var lastX = Float.NaN
        var lastY = Float.NaN
        var lastR = 0f
        var run: Path? = null
        var runR = 0f
        var capsules = 0
        for (k in 0 until n) {
            val x = xs[k]
            val y = ys[k]
            if (!x.isFinite() || !y.isFinite()) continue
            val r = if (rs[k].isFinite()) max(0.5f, rs[k]) else 0.5f
            if (lastX.isNaN()) {
                lastX = x; lastY = y; lastR = r
                continue
            }
            val rr = max(r, lastR)
            val open = run
            if (open == null || rr != runR) {
                if (open != null) out.addPath(strokeRegion(open, runR))
                run = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
                runR = rr
            } else {
                open.lineTo(x, y)
            }
            capsules++
            lastX = x; lastY = y; lastR = r
        }
        run?.let { out.addPath(strokeRegion(it, runR)) }
        // One point: its disc (with capsules, the first one covers it).
        if (capsules == 0 && !lastX.isNaN()) out.addCircle(lastX, lastY, lastR, Path.Direction.CW)
        out.fillType = Path.FillType.WINDING
        return out
    }

    /**
     * A round-capped, round-joined stroke region along [centre] (the Eraser brush, or an open
     * outline), [radius] on each side. An empty path when [radius] is not a positive number.
     */
    fun strokeRegion(centre: Path, radius: Float): Path {
        val out = Path()
        if (!radius.isFinite() || radius <= 0f) return out
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        paint.getFillPath(centre, out)
        return out
    }
}
