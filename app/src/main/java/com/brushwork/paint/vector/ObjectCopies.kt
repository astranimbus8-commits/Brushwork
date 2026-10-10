package com.brushwork.paint.vector

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeOutlines
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v1.8 (I15, I17; design §3.1, §4.3, §4.4): the one way every reader of a [VPath], a [VShape] or a
 * shape layer's [ShapeObject] sees its live symmetry copies ([CopyMaps]).
 *
 * Copy k of an object is its source drawn under C_k (`canvas.concat`); the first map is the
 * identity, the source itself. Readers either keep the copies (render, bounds and hit tests per
 * copy, affine transforms conjugate them) or bake them ([expanded]: the source plus one plain
 * object per copy, through [VectorOps.transformed]). An object without copies is read exactly as
 * in v1.7: every helper here returns its input (the same instance) or calls the v1.7 code once.
 *
 * - [mapsAt], [isPerspective], [fromSymmetry]: the seams the tools commit through (§4.4).
 * - [of], [source], [withCopies], [expanded], [baked]: the copies of an object, kept or as plain
 *   objects.
 * - [mappedBounds], [eachBounds], [hits], [drawEach]: per-copy bounds, hit tests and drawing.
 * - [transformedAll], [mapped]: transforms of objects and contents (a homography expands first).
 * - [detached]: the object bar's [com.brushwork.paint.ui.common.MirrorLabels18.DETACH] (plain
 *   objects in place of the linked ones).
 *
 * Pure geometry apart from [drawEach] (a canvas) and [mapsAt] (the editor's symmetry); thread-safe.
 */
object ObjectCopies {

    // ------------------------------------------------------------------ seams (§4.4)

    /**
     * The symmetry maps an object started at ([x], [y]) takes (its first anchor, a shape's start
     * point): `SymmetryMaps.transforms(c.symmetry, w, h, x, y)` on the document; empty when
     * symmetry is OFF (or gives nothing but the identity).
     */
    fun mapsAt(c: EditorController, x: Float, y: Float): List<FloatArray> {
        val s = c.symmetry
        if (s.type == SymmetryType.OFF) return emptyList()
        return SymmetryMaps.transforms(s, c.doc.width, c.doc.height, x, y)
    }

    /** True when one of [maps] is not affine (m[6] or m[7] not 0, m[8] not 1): the Perspective array ruler. */
    fun isPerspective(maps: List<FloatArray>): Boolean =
        maps.any { it.size < 9 || it[6] != 0f || it[7] != 0f || it[8] != 1f }

    /**
     * What a vector commit stores for [maps]: [CopyMaps.NONE] for no maps, the sanitized copies
     * for affine maps, null for perspective maps (the commit bakes them: [baked]).
     */
    fun fromSymmetry(maps: List<FloatArray>): CopyMaps? = when {
        maps.isEmpty() -> CopyMaps.NONE
        isPerspective(maps) -> null
        else -> CopyMaps.of(maps)
    }

    // ------------------------------------------------------------------ the copies of an object

    /** [o]'s copies: a path's, a shape's ([ShapeObject.copies]); [CopyMaps.NONE] for a stroke (its own `VStroke.copies` are v1.7's). */
    fun of(o: VObject): CopyMaps = when (o) {
        is VPath -> o.copies
        is VShape -> o.shape.copies
        is VStroke -> CopyMaps.NONE
    }

    /** True when [o] has copies (see [of]). */
    fun hasCopies(o: VObject): Boolean = !of(o).isEmpty

    /** Instances [o] draws: 1 without copies. */
    fun count(o: VObject): Int = of(o).count

    /** [o] without its copies (the source alone); the same instance when it has none. */
    fun source(o: VObject): VObject = when {
        !hasCopies(o) -> o
        o is VPath -> o.copy(copies = CopyMaps.NONE)
        o is VShape -> o.copy(shape = o.shape.copy(copies = CopyMaps.NONE))
        else -> o
    }

    /** [s] without its copies; the same instance when it has none. */
    fun source(s: ShapeObject): ShapeObject = if (s.copies.isEmpty) s else s.copy(copies = CopyMaps.NONE)

    /** [o] with [copies] (a stroke is returned as it is); the same instance when they are already its own. */
    fun withCopies(o: VObject, copies: CopyMaps): VObject = when {
        of(o) == copies -> o
        o is VPath -> o.copy(copies = copies)
        o is VShape -> o.copy(shape = o.shape.copy(copies = copies))
        else -> o
    }

    /**
     * [o] as plain objects, one per copy, in the order of the maps: the source first, then each
     * copy as [VectorOps.transformed] of the source by C_k. Every piece keeps [o]'s id (callers
     * renumber: `VectorContent.replaced`). A list of [o] alone without copies.
     */
    fun expanded(o: VObject): List<VObject> {
        val copies = of(o)
        if (copies.isEmpty) return listOf(o)
        val src = source(o)
        return copies.maps.mapIndexed { k, m -> if (k == 0) src else VectorOps.transformed(src, m) }
    }

    /**
     * A shape layer's [s] as plain vector objects (see [expanded]); [seed] is the shape's brush
     * seed. A list of the one [VShape] without copies.
     */
    fun expanded(s: ShapeObject, seed: Long = 0L): List<VObject> = expanded(VShape(0L, shape = s, seed = seed))

    /**
     * The perspective bake (§3.1, D2): [o] (its source) plus one plain object per map of [maps]
     * other than the identity, each through [VectorOps.transformed] (a homography turns a shape
     * into a path). The commit adds them in ONE step (`VectorContent.plus`). [o] alone for no maps.
     */
    fun baked(o: VObject, maps: List<FloatArray>): List<VObject> {
        val src = source(o)
        if (maps.isEmpty()) return listOf(src)
        val out = ArrayList<VObject>(maps.size)
        out += src
        for (m in maps) {
            if (m.size < 9 || isIdentity(m) || m.any { !it.isFinite() }) continue
            out += VectorOps.transformed(src, m)
        }
        return out
    }

    // ------------------------------------------------------------------ bounds, hits, drawing

    /**
     * The union of [r] mapped by every map of [copies] (the bounding box of each mapped corner box,
     * grown where a map is not a similarity by what a round dab can reach beyond it); [r] itself
     * without copies or when [r] is empty.
     */
    fun mappedBounds(r: RectF, copies: CopyMaps): RectF {
        if (copies.isEmpty || r.isEmpty) return r
        val out = RectF()
        for (b in eachBounds(r, copies)) if (!b.isEmpty) { if (out.isEmpty) out.set(b) else out.union(b) }
        return out
    }

    /** [r] mapped by each map of [copies] (see [mappedBounds]), in their order; a list of [r] without copies. */
    fun eachBounds(r: RectF, copies: CopyMaps): List<RectF> {
        if (copies.isEmpty) return listOf(r)
        return copies.maps.mapIndexed { k, m -> if (k == 0) RectF(r) else boxOf(r, m) }
    }

    private fun boxOf(r: RectF, m: FloatArray): RectF {
        val xs = floatArrayOf(r.left, r.right, r.right, r.left)
        val ys = floatArrayOf(r.top, r.top, r.bottom, r.bottom)
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var rr = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val x = m[0] * xs[i] + m[1] * ys[i] + m[2]
            val y = m[3] * xs[i] + m[4] * ys[i] + m[5]
            l = min(l, x); t = min(t, y); rr = max(rr, x); b = max(b, y)
        }
        if (!(l.isFinite() && t.isFinite() && rr.isFinite() && b.isFinite())) return RectF()
        // A brush dab keeps its round tip, √|det| times its size, which a map that is not a
        // similarity can carry past the mapped box by up to (√|det| − σmin) × its radius (at most
        // half the box's smaller side).
        val (smin, sdet) = stretch(m)
        val extra = (sdet - smin) * min(r.width(), r.height()) / 2f
        val e = if (extra.isFinite() && extra > 0f) extra else 0f
        return RectF(l - e, t - e, rr + e, b + e)
    }

    /** (σmin, √|det|) of [m]'s linear part. */
    private fun stretch(m: FloatArray): Pair<Float, Float> {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[3].toDouble(); val d = m[4].toDouble()
        val det = abs(a * d - b * c)
        val s2 = a * a + b * b + c * c + d * d
        // σmax² + σmin² = s2, σmax·σmin = det.
        val disc = sqrt(max(0.0, s2 * s2 - 4.0 * det * det))
        val smin = sqrt(max(0.0, (s2 - disc) / 2.0))
        return smin.toFloat() to sqrt(det).toFloat()
    }

    /**
     * True when [test] hits ([test] at a point and a tolerance, in the source's own space) for one
     * of the copies: the point is mapped by C_k⁻¹ and the tolerance grown by the map's largest
     * inverse stretch, so a copy is hit wherever it is drawn within [tol]. Without copies, [test]
     * once at [p] and [tol] (v1.7).
     */
    fun hits(copies: CopyMaps, p: Vec2, tol: Float, test: (Vec2, Float) -> Boolean): Boolean {
        if (test(p, tol)) return true
        if (copies.isEmpty) return false
        for (k in 1 until copies.maps.size) {
            val inv = inverse(copies.maps[k]) ?: continue
            val q = Vec2(inv[0] * p.x + inv[1] * p.y + inv[2], inv[3] * p.x + inv[4] * p.y + inv[5])
            if (!(q.x.isFinite() && q.y.isFinite())) continue
            val (smin, _) = stretch(copies.maps[k])
            val t = if (smin > 0f && tol.isFinite()) tol / smin else tol
            if (test(q, t)) return true
        }
        return false
    }

    /** True when [p] is on the shape [s] or one of its copies (`ShapeOutlines.hits` per copy). */
    fun hits(s: ShapeObject, p: Vec2, tol: Float): Boolean {
        if (s.copies.isEmpty) return ShapeOutlines.hits(s, p, tol)
        val src = source(s)
        return hits(s.copies, p, tol) { q, t -> ShapeOutlines.hits(src, q, t) }
    }

    /**
     * Draws [draw] (the source, in document px) once per copy under `canvas.concat(C_k)`, the
     * source first; once, untransformed, without copies.
     */
    fun drawEach(canvas: Canvas, copies: CopyMaps, draw: (Canvas) -> Unit) {
        if (copies.isEmpty) { draw(canvas); return }
        val mx = Matrix()
        copies.maps.forEachIndexed { k, m ->
            if (k == 0) { draw(canvas); return@forEachIndexed }
            val save = canvas.save()
            mx.setValues(m)
            canvas.concat(mx)
            draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    // ------------------------------------------------------------------ transforms

    /**
     * [o] mapped by [m] as the objects that stand for it afterwards: under an affine map (or
     * without copies) the one [VectorOps.transformed] (copies kept, conjugated); under a homography
     * the copies are expanded first and each piece is mapped (§3.1: Distort, Free deform).
     */
    fun transformedAll(o: VObject, m: FloatArray): List<VObject> {
        if (!hasCopies(o) || m.size < 9 || (m[6] == 0f && m[7] == 0f)) return listOf(VectorOps.transformed(o, m))
        return expanded(o).map { VectorOps.transformed(it, m) }
    }

    /**
     * Every object of [content] mapped by [m] ([transformedAll]): the content as v1.7 maps it
     * when [m] is affine or nothing has copies; under a homography the pieces of an object with
     * copies replace it in place, the first keeping its id, the others taking new ids.
     */
    fun mapped(content: VectorContent, m: FloatArray): VectorContent {
        val projective = m.size >= 9 && (m[6] != 0f || m[7] != 0f)
        if (!projective || content.objects.none { hasCopies(it) }) {
            return content.copy(objects = content.objects.map { VectorOps.transformed(it, m) })
        }
        var next = content.nextId
        val out = ArrayList<VObject>(content.objects.size + 8)
        for (o in content.objects) {
            transformedAll(o, m).forEachIndexed { i, piece -> out += if (i == 0) piece.withId(o.id) else piece.withId(next++) }
        }
        return content.copy(objects = out, nextId = next)
    }

    /** [m] scaled to m[8] = 1 when it is affine (else null): the map copies are conjugated by. */
    internal fun affineOf(m: FloatArray): FloatArray? {
        if (m.size < 9 || m[6] != 0f || m[7] != 0f || m[8] == 0f || m.any { !it.isFinite() }) return null
        if (m[8] == 1f) return m
        val k = 1f / m[8]
        return floatArrayOf(m[0] * k, m[1] * k, m[2] * k, m[3] * k, m[4] * k, m[5] * k, 0f, 0f, 1f)
    }

    // ------------------------------------------------------------------ detach

    /**
     * [content] with every object of [ids] that has copies replaced in place by its plain pieces
     * ([expanded]: the source keeps its id, each copy takes a new one), and the ids of all pieces
     * plus the other ids of [ids] that exist; null when none of them has copies.
     */
    fun detached(content: VectorContent, ids: Set<Long>): Pair<VectorContent, List<Long>>? {
        val map = LinkedHashMap<Long, List<VObject>>()
        for (o in content.objects) if (o.id in ids && hasCopies(o)) map[o.id] = expanded(o)
        if (map.isEmpty()) return null
        val after = content.replaced(map)
        val before = content.objects.mapTo(HashSet()) { it.id }
        val out = after.objects.filter { it.id in ids || it.id !in before }.map { it.id }
        return after to out
    }

    // ------------------------------------------------------------------ helpers

    private fun isIdentity(m: FloatArray): Boolean =
        m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 0f && m[4] == 1f && m[5] == 0f && m[6] == 0f && m[7] == 0f && m[8] == 1f

    /** The inverse of the affine [m] (row-major 2×3 part used), or null when singular. */
    private fun inverse(m: FloatArray): FloatArray? {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val det = a * e - b * d
        if (!det.isFinite() || abs(det) < 1e-12) return null
        val ia = e / det; val ib = -b / det; val id = -d / det; val ie = a / det
        return floatArrayOf(ia.toFloat(), ib.toFloat(), (-(ia * c + ib * f)).toFloat(), id.toFloat(), ie.toFloat(), (-(id * c + ie * f)).toFloat())
    }
}
