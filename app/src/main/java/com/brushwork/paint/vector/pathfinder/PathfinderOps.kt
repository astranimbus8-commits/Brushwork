package com.brushwork.paint.vector.pathfinder

import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.VPath

/**
 * Illustrator's ten Pathfinder operations (v1.7 item 20, §3.20), in the options row's order:
 * the visible text [label] (also the undo step's "Pathfinder: <label>") and a unique content
 * description [description] (I10).
 */
enum class PathfinderOp(val description: String) {
    UNITE(PathfinderLabels.UNITE),
    MINUS_FRONT(PathfinderLabels.MINUS_FRONT),
    MINUS_BACK(PathfinderLabels.MINUS_BACK),
    INTERSECT(PathfinderLabels.INTERSECT),
    EXCLUDE(PathfinderLabels.EXCLUDE),
    DIVIDE(PathfinderLabels.DIVIDE),
    TRIM(PathfinderLabels.TRIM),
    MERGE(PathfinderLabels.MERGE),
    CROP(PathfinderLabels.CROP),
    OUTLINE(PathfinderLabels.OUTLINE),
    ;

    /** The visible name: "Unite" ... "Outline" (`HistoryLabels.PATHFINDER_OPS`). */
    val label: String get() = HistoryLabels.PATHFINDER_OPS[ordinal]

    /** The undo step's name: "Pathfinder: Unite". */
    val historyLabel: String get() = HistoryLabels.pathfinder(label)

    /** The five shape modes give ONE compound object; the pathfinders give pieces. */
    val isShapeMode: Boolean get() = ordinal <= EXCLUDE.ordinal
}

/** One operand: its region (document px) and its style. */
class PathfinderOperand(val region: Path, val style: PathfinderStyle)

/** What an operation gives. */
sealed class PathfinderResult {
    /** The result objects, bottom first (plain [VPath]s, ids to be assigned). */
    class Done(val objects: List<VPath>) : PathfinderResult()

    /** Nothing is left (the shapes don't overlap, for Intersect and Crop). */
    data object Empty : PathfinderResult()

    /** More than [PathfinderOps.MAX_PIECES] pieces ("Too many pieces: select fewer objects"). */
    data object TooMany : PathfinderResult()

    /** Skia's PathOps gave up on these shapes. */
    data object Failed : PathfinderResult()
}

/**
 * The geometry of Pathfinder (§3.20): `Path.op` (Skia PathOps, exact) for the shape modes;
 * Divide's pieces built operand by operand; Trim, Merge and Crop as the same unions and
 * differences taken per operand (what Divide's pieces would give, without the per-piece work);
 * Outline from Divide's pieces ([PathfinderOutline]). Operands are in picture order, bottom first
 * (the layer order, then the object order inside a layer), already in document coordinates.
 * Pieces under [SLIVER_AREA] px² are dropped. Pure and thread-safe: runs on `Dispatchers.Default`.
 */
object PathfinderOps {
    /** At most this many operands ("Select up to 12 objects"). */
    const val MAX_OPERANDS = 12

    /** At most this many pieces ("Too many pieces: select fewer objects"). */
    const val MAX_PIECES = 256

    /** Pieces smaller than this (px²) are slivers. */
    const val SLIVER_AREA = 0.5f

    /** A piece of Divide: its region and the operand whose style it takes (the frontmost one covering it). */
    class Piece(val path: Path, val source: Int)

    private class OpFailed : RuntimeException()

    private class TooManyPieces : RuntimeException()

    fun run(op: PathfinderOp, operands: List<PathfinderOperand>): PathfinderResult {
        if (operands.size < 2) return PathfinderResult.Empty
        return try {
            val objects = when (op) {
                PathfinderOp.UNITE, PathfinderOp.MINUS_FRONT, PathfinderOp.MINUS_BACK, PathfinderOp.INTERSECT, PathfinderOp.EXCLUDE -> {
                    val region = shapeMode(op, operands)
                    val style = operands[PathfinderStyles.shapeModeSource(op, operands.size)].style
                    if (region == null) emptyList() else listOfNotNull(style.objectOf(region))
                }
                PathfinderOp.DIVIDE -> divide(operands).mapNotNull { operands[it.source].style.objectOf(it.path) }
                PathfinderOp.TRIM -> trim(operands).mapNotNull { operands[it.source].style.objectOf(it.path) }
                PathfinderOp.MERGE -> merge(operands).mapNotNull { operands[it.source].style.objectOf(it.path) }
                PathfinderOp.CROP -> crop(operands).mapNotNull { operands[it.source].style.objectOf(it.path) }
                PathfinderOp.OUTLINE -> PathfinderOutline.edges(divide(operands), operands.map { it.style })
            }
            if (objects.isEmpty()) PathfinderResult.Empty else PathfinderResult.Done(objects)
        } catch (e: OpFailed) {
            PathfinderResult.Failed
        } catch (e: TooManyPieces) {
            PathfinderResult.TooMany
        }
    }

    /** A shape mode's region: null when nothing (or only a sliver) is left. */
    internal fun shapeMode(op: PathfinderOp, operands: List<PathfinderOperand>): Path? {
        val regions = operands.map { it.region }
        val r = when (op) {
            PathfinderOp.UNITE -> regions.drop(1).fold(simple(regions[0])) { acc, p -> op(acc, p, Path.Op.UNION) }
            PathfinderOp.INTERSECT -> regions.drop(1).fold(simple(regions[0])) { acc, p -> op(acc, p, Path.Op.INTERSECT) }
            PathfinderOp.EXCLUDE -> regions.drop(1).fold(simple(regions[0])) { acc, p -> op(acc, p, Path.Op.XOR) }
            PathfinderOp.MINUS_FRONT -> op(regions[0], union(regions.drop(1)), Path.Op.DIFFERENCE)
            PathfinderOp.MINUS_BACK -> op(regions.last(), union(regions.dropLast(1)), Path.Op.DIFFERENCE)
            else -> throw IllegalArgumentException("$op is not a shape mode")
        }
        return r.takeIf { kept(it) }
    }

    /**
     * Divide: every region where a different set of operands overlaps, each connected piece its
     * own, styled by the frontmost operand covering it. Built operand by operand: each piece so
     * far splits into its part inside the new operand (now its source) and outside it, and the
     * new operand's part outside everything below is a new piece. Bottom first by source.
     */
    internal fun divide(operands: List<PathfinderOperand>): List<Piece> {
        var pieces = ArrayList<Piece>()
        var covered = Path()
        for ((i, o) in operands.withIndex()) {
            val next = ArrayList<Piece>(pieces.size * 2 + 1)
            for (p in pieces) {
                val inside = op(p.path, o.region, Path.Op.INTERSECT)
                if (kept(inside)) next += Piece(inside, i)
                val outside = op(p.path, o.region, Path.Op.DIFFERENCE)
                if (kept(outside)) next += Piece(outside, p.source)
            }
            val fresh = op(o.region, covered, Path.Op.DIFFERENCE)
            if (kept(fresh)) next += Piece(fresh, i)
            covered = op(covered, o.region, Path.Op.UNION)
            if (next.size > MAX_PIECES) throw TooManyPieces()
            pieces = next
        }
        val out = ArrayList<Piece>(pieces.size)
        for (p in pieces) for (c in PathConvert.components(p.path, simple = true)) if (kept(c)) out += Piece(c, p.source)
        if (out.size > MAX_PIECES) throw TooManyPieces()
        return out.sortedBy { it.source }
    }

    /** Trim: each operand's visible part (what the operands above leave of it), its own style. */
    internal fun trim(operands: List<PathfinderOperand>): List<Piece> {
        val out = ArrayList<Piece>()
        var above = Path()
        for (i in operands.indices.reversed()) {
            val region = operands[i].region
            val visible = op(region, above, Path.Op.DIFFERENCE)
            if (kept(visible)) out += Piece(visible, i)
            above = op(above, region, Path.Op.UNION)
        }
        return out.asReversed()
    }

    /**
     * Merge: Trim, then the touching visible parts of the same fill become one object (each
     * connected piece of a fill's union is one; its style: the topmost operand it contains). An
     * unfilled operand's visible part stays as Trim leaves it.
     */
    internal fun merge(operands: List<PathfinderOperand>): List<Piece> {
        val trimmed = trim(operands)
        val out = ArrayList<Piece>()
        val groups = LinkedHashMap<Any, MutableList<Piece>>()
        for (p in trimmed) {
            val fill = operands[p.source].style.fill
            if (fill == null) out += p else groups.getOrPut(fill) { ArrayList() } += p
        }
        for (members in groups.values) {
            if (members.size == 1) { out += members[0]; continue }
            val union = members.drop(1).fold(members[0].path) { acc, m -> op(acc, m.path, Path.Op.UNION) }
            for (c in PathConvert.components(union, simple = true)) {
                if (!kept(c)) continue
                // The topmost member inside this piece gives its style.
                val source = members.asReversed().firstOrNull { m -> overlaps(c, m.path) }?.source ?: members.last().source
                out += Piece(c, source)
            }
        }
        if (out.size > MAX_PIECES) throw TooManyPieces()
        return out.sortedBy { it.source }
    }

    /**
     * Crop: the top operand crops the others: each lower operand's visible part (among the
     * lower ones) inside the top one, its own style; the top's own region is dropped.
     */
    internal fun crop(operands: List<PathfinderOperand>): List<Piece> {
        val top = operands.last().region
        val out = ArrayList<Piece>()
        var above = Path()
        for (i in operands.lastIndex - 1 downTo 0) {
            val region = operands[i].region
            val visible = op(op(region, above, Path.Op.DIFFERENCE), top, Path.Op.INTERSECT)
            if (kept(visible)) out += Piece(visible, i)
            above = op(above, region, Path.Op.UNION)
        }
        return out.asReversed()
    }

    private fun union(regions: List<Path>): Path = regions.fold(Path()) { acc, p -> op(acc, p, Path.Op.UNION) }

    /** [p] as non-overlapping contours (an operand may cross itself). */
    private fun simple(p: Path): Path = op(p, Path(), Path.Op.UNION)

    private fun op(a: Path, b: Path, op: Path.Op): Path = PathConvert.op(a, b, op) ?: throw OpFailed()

    private fun overlaps(a: Path, b: Path): Boolean = kept(op(a, b, Path.Op.INTERSECT))

    /** Not empty and not a sliver ([p] is an op result: already simple). */
    private fun kept(p: Path): Boolean {
        if (p.isEmpty) return false
        val r = RectF()
        @Suppress("DEPRECATION") p.computeBounds(r, true)
        if (r.width() * r.height() < SLIVER_AREA) return false
        return PathConvert.area(p, simple = true) >= SLIVER_AREA
    }
}
