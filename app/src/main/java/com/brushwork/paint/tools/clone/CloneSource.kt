package com.brushwork.paint.tools.clone

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.CoverageCopies
import com.brushwork.paint.brush.CoverageSource
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.StrokeCopies
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A whole-pixel clone offset: destination − source, in document px. */
data class CloneOffset(val dx: Int, val dy: Int) {
    /** The point [p] (a destination) samples from. */
    fun sourceOf(p: Vec2): Vec2 = Vec2(p.x - dx, p.y - dy)
}

/**
 * Where the clone stamp copies from, and Photoshop's "Aligned" rule (v1.5 §4.2). Main thread.
 *
 * With Aligned on, the first stroke after the source is set fixes the offset (destination −
 * source) and every later stroke keeps it, so the source travels with the strokes: after each
 * stroke the source point is where that stroke's end sampled (a stroke continuing from there
 * samples it). With Aligned off every stroke starts sampling at the source point again. Offsets
 * are whole pixels, so cloned pixels are copied exactly (no resampling blur).
 */
class CloneAnchor {
    /** The source point (document px), null until one is set. Compose state. */
    var source: Vec2? by mutableStateOf(null)
        private set

    /** The offset kept by Aligned (null until the first stroke after setting the source). */
    var fixed: CloneOffset? = null
        private set

    /**
     * Where the user put the source (long-press, Set source, the ⊕, the X / Y strip). Aligned
     * strokes carry [source] along with them; switching Aligned starts over from here, as
     * Photoshop's non-aligned strokes "use the sampled pixels from the initial sampling point".
     */
    var origin: Vec2? = null
        private set

    /**
     * A new source: the next stroke fixes the offset again. A point that is not finite is ignored
     * (a typed or computed NaN must never reach the offset math); coordinates are kept within
     * ±[MAX_COORD] so offsets and source rectangles stay far from integer overflow.
     */
    fun set(p: Vec2) {
        if (!p.x.isFinite() || !p.y.isFinite()) return
        val v = Vec2(p.x.coerceIn(-MAX_COORD, MAX_COORD), p.y.coerceIn(-MAX_COORD, MAX_COORD))
        source = v
        origin = v
        fixed = null
    }

    fun clear() {
        source = null
        origin = null
        fixed = null
    }

    /** Puts back a state saved before a gesture that was cancelled. */
    fun restore(source: Vec2?, fixed: CloneOffset?, origin: Vec2? = source) {
        this.source = source
        this.fixed = fixed
        this.origin = origin
    }

    /**
     * Aligned was switched: the next stroke starts at the point the user set (not where Aligned
     * strokes carried the source) and fixes the offset again.
     */
    fun resetAlignment() {
        fixed = null
        origin?.let { source = it }
    }

    /** The offset of a stroke starting at [start] (null without a source). */
    fun offsetFor(start: Vec2, aligned: Boolean): CloneOffset? {
        val s = source ?: return null
        if (aligned) fixed?.let { return it }
        return CloneOffset((start.x - s.x).roundToInt(), (start.y - s.y).roundToInt())
    }

    /**
     * A stroke painted with [offset] ended at [end] (document px). With Aligned the first one
     * fixes the offset and the source moves along to what [end] sampled.
     */
    fun strokeCompleted(offset: CloneOffset, aligned: Boolean, end: Vec2) {
        if (!aligned || source == null) return
        val kept = fixed ?: offset
        val s = kept.sourceOf(end)
        if (s.x.isFinite() && s.y.isFinite()) source = Vec2(s.x.coerceIn(-MAX_COORD, MAX_COORD), s.y.coerceIn(-MAX_COORD, MAX_COORD))
        fixed = kept
    }

    companion object {
        /** The source stays within ±this many document px (far beyond any canvas, far below Int overflow). */
        const val MAX_COORD = 1_000_000f
    }
}

/**
 * The clone stamp's [CoverageSource] (v1.5 §4.2): the brush engine paints its coverage with
 * [shader], which shows the sampled pixels, [CloneOffset] away from the destination, through
 * every coverage path, so the live stroke and its commit look the same.
 *
 * - **This layer** samples the layer's bitmap (or its mask, when the mask is edited) directly:
 *   it does not change until the stroke commits. Before the commit, [prepareCommit] copies the
 *   source region when the commit could otherwise read pixels it has already changed (V13).
 * - **All layers** samples a document-sized snapshot of the composite (adjustment layers
 *   included), filled lazily per [TILE] px tile as the stroke reaches new source areas, and
 *   refilled from scratch at every stroke (the previous stroke changed the document). It is
 *   allocated on first use, within [snapshotBudgetBytes], and freed by [releaseSnapshot].
 *
 * A source outside the document copies nothing: regions whose source leaves the document are
 * sampled through a copy that is transparent there. Main thread only.
 *
 * v1.8 D (design §3.1 D1, [CoverageCopies]): under a symmetry ruler a stroke also has copies,
 * one per map M_k after the identity ([beginStroke]'s `maps`). Copy k draws the stroke's one
 * coverage buffer under M_k with a shader whose local matrix is T(o)·M_k⁻¹ (o = the offset), so
 * a destination pixel q shows S(q − A_k·o): copy k clones from the mirrored source spot (A_k the
 * linear part of M_k; for a perspective map the same composition, M_k·T(−o)·M_k⁻¹). Copies
 * sample bilinearly; the original keeps its exact whole-pixel shader. The source rect of copy k
 * is M_k applied to the original's ([sourceRectOf]); [readsOnlyOutside] checks all of them, the
 * All-layers snapshot is filled for all of them, and with copies [prepareCommitCopies] always
 * copies their union first (one commit copy, transparent outside the document).
 */
class CloneSource(private val controller: EditorController) : CoverageSource, CoverageCopies {
    enum class Sample { THIS_LAYER, ALL_LAYERS }

    /** What the current (or last) stroke samples. */
    var sample: Sample = Sample.THIS_LAYER
        private set

    /** True between [beginStroke] and [endStroke]. */
    var isActive: Boolean = false
        private set

    /** The All-layers snapshot may not be larger than this (falls back to This layer). */
    internal var snapshotBudgetBytes: Long = max(48L shl 20, Runtime.getRuntime().maxMemory() / 4)

    /** Tiles composited into the snapshot so far (tests, diagnostics). */
    internal var tilesRendered: Int = 0
        private set

    /** Copies made because a source region left the document or overlapped the commit (tests). */
    internal var regionCopies: Int = 0
        private set

    private val doc get() = controller.doc
    private var backing: Bitmap? = null
    private var dx = 0
    private var dy = 0
    private var direct: Shader = idleShader()
    private var current: Shader = direct

    override val shader: Shader get() = current

    private var snapshot: Bitmap? = null
    private var snapshotCanvas: Canvas? = null
    private var snapshotTarget: CompositeTarget? = null

    /**
     * Composites the snapshot tiles. Its own instance, never the controller's: tiles are filled
     * while the display compositor is in the middle of drawing (from the stroke's render
     * override), and a compositor keeps per-instance scratch (adjustment layers) that a nested
     * call must not share. No overrides: the stroke in progress is not part of what it samples.
     */
    private val snapshotCompositor = Compositor(controller.doc) { null }
    private var filled = BooleanArray(0)
    /** Snapshot tiles the stroke's path can sample ([notePath]); the commit fills only those. */
    private var needed = BooleanArray(0)
    private var neededAny = false
    private var cols = 0
    private var rows = 0

    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null
    private var commitCopy: Bitmap? = null

    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val src = Rect()
    private val part = Rect()
    private val dst = Rect()
    private val tile = Rect()

    // v1.8 D: the copies of the stroke in progress (index k = 1..[copyCount]; `[0]` unused).
    private var copyN = 0
    private var copyValues: Array<FloatArray> = emptyArray()
    private var copyMaps: Array<Matrix> = emptyArray()
    /** M_k⁻¹. */
    private var copyInverse: Array<Matrix> = emptyArray()
    /** G_k = M_k·T(−o)·M_k⁻¹: a destination point of copy k to the source point it shows. */
    private var copyTexture: Array<Matrix> = emptyArray()
    private var copyTextureValues: Array<FloatArray> = emptyArray()
    private var copyDirect: Array<Shader> = emptyArray()
    private var copyCurrent: Array<Shader> = emptyArray()
    private var copyScratch: Bitmap? = null
    private var copyScratchCanvas: Canvas? = null
    private val copySrc = Rect()
    private val mapF = RectF()
    private val pt = FloatArray(2)

    /** Region copies made for the copies' sources (tests). */
    internal var copyRegionCopies: Int = 0
        private set

    /** Commit copies made for strokes with copies (tests). */
    internal var commitCopiesTaken: Int = 0
        private set

    /**
     * Sets up a stroke on [layer] ([target]: its pixels or its mask) that paints with [offset].
     * Returns what it samples: All layers falls back to This layer when the mask is edited (a
     * mask clones its own values) or when the snapshot can't be allocated.
     *
     * v1.8 D: [maps] (row-major 3×3, the identity first, as `SymmetryMaps.transforms` gives them
     * at the stroke's start) make the stroke draw a copy per map after the identity
     * ([CoverageCopies]); fewer than two maps: no copies, the v1.7 stroke exactly. The caller
     * caps their number (`CloneTool.MAX_MAPS`).
     */
    fun beginStroke(layer: Layer, target: EditTarget, wanted: Sample, offset: CloneOffset, maps: List<FloatArray> = emptyList()): Sample {
        endStroke()
        dx = offset.dx
        dy = offset.dy
        val maskTarget = target == EditTarget.MASK
        var mode = if (maskTarget) Sample.THIS_LAYER else wanted
        var bmp: Bitmap? = null
        if (mode == Sample.ALL_LAYERS) {
            bmp = ensureSnapshot()
            if (bmp == null) mode = Sample.THIS_LAYER else filled.fill(false)
        }
        needed.fill(false)
        neededAny = false
        if (bmp == null) bmp = (if (maskTarget) layer.mask else null) ?: layer.bitmap
        backing = bmp
        direct = shaderOf(bmp, dx, dy)
        current = direct
        sample = mode
        isActive = true
        setUpCopies(bmp, maps)
        return mode
    }

    /** The copies of [maps] (the identity first), sampling [bmp] directly until a region needs a copy. */
    private fun setUpCopies(bmp: Bitmap, maps: List<FloatArray>) {
        val usable = maps.drop(1).filter { it.size == 9 && it.all { v -> v.isFinite() } }
        val ms = ArrayList<Matrix>()
        val inv = ArrayList<Matrix>()
        val vals = ArrayList<FloatArray>()
        for (m in usable) {
            val mk = Matrix().apply { setValues(m) }
            val ik = Matrix()
            if (!mk.invert(ik)) continue
            ms += mk
            inv += ik
            vals += m
        }
        copyN = ms.size
        if (copyN == 0) {
            clearCopies()
            return
        }
        val id = Matrix()
        copyMaps = Array(copyN + 1) { k -> if (k == 0) id else ms[k - 1] }
        copyInverse = Array(copyN + 1) { k -> if (k == 0) id else inv[k - 1] }
        copyValues = Array(copyN + 1) { k -> if (k == 0) IDENTITY_VALUES else vals[k - 1] }
        copyTexture = Array(copyN + 1) { k ->
            // x ↦ M_k(M_k⁻¹(x) − o)
            Matrix(copyInverse[k]).apply {
                postTranslate(-dx.toFloat(), -dy.toFloat())
                postConcat(copyMaps[k])
            }
        }
        copyTextureValues = Array(copyN + 1) { k -> FloatArray(9).also { copyTexture[k].getValues(it) } }
        copyDirect = Array(copyN + 1) { k -> if (k == 0) direct else copyShaderOf(bmp, k, 0, 0) }
        copyCurrent = copyDirect.copyOf()
    }

    /** No copies (the v1.7 stroke). */
    private fun clearCopies() {
        copyN = 0
        copyValues = emptyArray()
        copyMaps = emptyArray()
        copyInverse = emptyArray()
        copyTexture = emptyArray()
        copyTextureValues = emptyArray()
        copyDirect = emptyArray()
        copyCurrent = emptyArray()
    }

    /**
     * Copy [k]'s shader over [bitmap], which holds the source from ([left], [top]) on: local
     * matrix T(o)·M_k⁻¹·T(left, top), so a destination point q of the copy samples
     * G_k(q) − (left, top) of [bitmap].
     */
    private fun copyShaderOf(bitmap: Bitmap, k: Int, left: Int, top: Int): Shader =
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setTranslate(left.toFloat(), top.toFloat())
                    postConcat(copyInverse[k])
                    postTranslate(dx.toFloat(), dy.toFloat())
                }
            )
        }

    // ------------------------------------------------------------------ CoverageCopies (v1.8 D)

    override val copyCount: Int get() = if (isActive) copyN else 0

    override fun copyMap(k: Int): Matrix = copyMaps[k]

    override fun copyShader(k: Int): Shader = copyCurrent.getOrNull(k) ?: idleShader()

    override fun prepareCopy(k: Int, destDocRect: Rect) {
        if (!isActive || k !in 1..copyN || destDocRect.isEmpty) return
        val bounded = sourceRectOf(k, destDocRect, copySrc)
        if (sample == Sample.ALL_LAYERS) fill(copySrc)
        copyCurrent[k] = if (bounded && insideDocument(copySrc)) copyDirect[k] else copyRegionShader(k, copySrc) ?: copyDirect[k]
    }

    override fun prepareCommitCopies(dests: Array<Rect>) {
        if (!isActive) return
        if (copyN == 0) {
            dests.firstOrNull()?.let { prepareCommit(it) }
            return
        }
        // The union of every part's source rect, within the document and a transparent 1 px
        // border around it (sampling beyond that border repeats it: transparent).
        val union = Rect()
        val s = Rect()
        for (k in 0..min(copyN, dests.size - 1)) {
            val d = dests[k]
            if (d.isEmpty) continue
            if (k == 0) {
                s.set(d)
                s.offset(-dx, -dy)
            } else {
                sourceRectOf(k, d, s)
            }
            if (sample == Sample.ALL_LAYERS) fill(s, onlyNeeded = neededAny)
            union.union(s)
        }
        if (union.isEmpty || !union.intersect(-1, -1, doc.width + 1, doc.height + 1)) {
            current = direct
            for (k in 1..copyN) copyCurrent[k] = copyDirect[k]
            return
        }
        val copy = try {
            Bitmap.createBitmap(union.width(), union.height(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            controller.toast(LOW_MEMORY_MESSAGE)
            current = direct
            for (k in 1..copyN) copyCurrent[k] = copyDirect[k]
            return
        }
        copySource(Canvas(copy), union)
        commitCopy?.recycle()
        commitCopy = copy
        commitCopiesTaken++
        current = shaderOf(copy, dx + union.left, dy + union.top)
        for (k in 1..copyN) copyCurrent[k] = copyShaderOf(copy, k, union.left, union.top)
    }

    /**
     * The source rect (document px, rounded out, a pixel of margin for the bilinear sampling)
     * that copy [k] reads for its destination [dest]: G_k([dest]), which is M_k applied to the
     * original's source rect. False (and [out] = the document with a pixel around it) when a
     * perspective map takes part of it behind its horizon.
     */
    private fun sourceRectOf(k: Int, dest: Rect, out: Rect): Boolean {
        if (!mapBounded(copyTexture[k], copyTextureValues[k], dest, out)) {
            out.set(-1, -1, doc.width + 1, doc.height + 1)
            return false
        }
        out.inset(-1, -1)
        return true
    }

    /**
     * [out] = [r] mapped through [m] ([v]: its values), rounded out. False when [m] is a
     * homography that takes part of [r] across its horizon (w changes sign over [r], or the
     * result is not finite or absurdly large): the image is unbounded.
     */
    private fun mapBounded(m: Matrix, v: FloatArray, r: Rect, out: Rect): Boolean {
        if (v[6] != 0f || v[7] != 0f) {
            // w is linear, so the corners bound it over the rect: one sign, away from 0.
            var pos = 0
            var neg = 0
            for (c in 0 until 4) {
                val x = (if (c and 1 == 0) r.left else r.right).toFloat()
                val y = (if (c and 2 == 0) r.top else r.bottom).toFloat()
                val w = v[6] * x + v[7] * y + v[8]
                if (w > HORIZON_EPS) pos++ else if (w < -HORIZON_EPS) neg++
            }
            if (pos != 4 && neg != 4) return false
        }
        mapF.set(r)
        m.mapRect(mapF)
        val l = mapF.left
        val t = mapF.top
        val rr = mapF.right
        val b = mapF.bottom
        if (!(l > -MAX_MAPPED && t > -MAX_MAPPED && rr < MAX_MAPPED && b < MAX_MAPPED)) return false
        mapF.roundOut(out)
        return true
    }

    /** A shader for copy [k] whose source rect [s] leaves the document: a copy of it, transparent outside the document. */
    private fun copyRegionShader(k: Int, s: Rect): Shader? {
        val w = s.width()
        val h = s.height()
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SCRATCH_PIXELS) return null
        var bmp = copyScratch
        if (bmp == null || bmp.width < w || bmp.height < h) {
            bmp?.recycle()
            bmp = try {
                Bitmap.createBitmap(max(w, bmp?.width ?: 0), max(h, bmp?.height ?: 0), Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                copyScratch = null
                copyScratchCanvas = null
                return null
            }
            copyScratch = bmp
            copyScratchCanvas = Canvas(bmp)
        }
        // (Each copy is drawn right after its prepare, so one scratch serves them all in turn.)
        val canvas = copyScratchCanvas!!
        canvas.save()
        canvas.clipRect(0, 0, w + 1, h + 1)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.restore()
        copySource(canvas, s)
        copyRegionCopies++
        return copyShaderOf(bmp, k, s.left, s.top)
    }

    /** Copies the source pixels of [s] (source px, within the document) to (0, 0) of [canvas]. */
    private fun copySource(canvas: Canvas, s: Rect) {
        val from = backing ?: return
        part.set(s)
        if (!part.intersect(0, 0, min(doc.width, from.width), min(doc.height, from.height))) return
        dst.set(part.left - s.left, part.top - s.top, part.right - s.left, part.bottom - s.top)
        canvas.drawBitmap(from, part, dst, copyPaint)
    }

    /**
     * The stroke went from ([ax], [ay]) to ([bx], [by]) (document px, destination) with dabs
     * reaching [radius] around it. All layers: the commit then composites only the snapshot
     * tiles this path can sample instead of every tile of the stroke's bounding box (a long
     * diagonal stroke would otherwise composite most of the document at once).
     */
    fun notePath(ax: Float, ay: Float, bx: Float, by: Float, radius: Float) {
        if (!isActive || sample != Sample.ALL_LAYERS || needed.isEmpty()) return
        neededAny = true
        val len = hypot(bx - ax, by - ay)
        val step = TILE / 4f
        val n = max(1, ceil(len / step).toInt())
        // Samples are at most step / 2 from any point of the segment, and the brush's smoothed
        // path (quadratic curves between the midpoints of input segments) strays from the input
        // polyline by less than a quarter of a segment.
        val r = radius + step / 2f + max(PATH_MARGIN, len * 0.5f)
        for (i in 0..n) {
            val t = i.toFloat() / n
            val sx = ax + (bx - ax) * t - dx
            val sy = ay + (by - ay) * t - dy
            markNeeded(sx, sy, r)
            // v1.8 D: copy k samples M_k(p − o) where the original samples p − o, its dabs
            // scaled by M_k there (a pixel more for the bilinear sampling).
            for (k in 1..copyN) {
                val v = copyValues[k]
                pt[0] = sx
                pt[1] = sy
                copyMaps[k].mapPoints(pt)
                if (!pt[0].isFinite() || !pt[1].isFinite()) {
                    needed.fill(true)
                    return
                }
                markNeeded(pt[0], pt[1], r * StrokeCopies.scaleAt(v, sx, sy) + 1f)
            }
        }
    }

    /** Marks the snapshot tiles that come within [r] of ([cx], [cy]) (a disc, not its bounding square). */
    private fun markNeeded(cx: Float, cy: Float, r: Float) {
        val l = max(0, floor(cx - r).toInt())
        val t = max(0, floor(cy - r).toInt())
        val rr = min(doc.width, ceil(cx + r).toInt())
        val b = min(doc.height, ceil(cy + r).toInt())
        if (rr <= l || b <= t) return
        val r2 = r * r
        for (row in t / TILE..(b - 1) / TILE) for (col in l / TILE..(rr - 1) / TILE) {
            val idx = row * cols + col
            if (idx !in needed.indices || needed[idx]) continue
            // Distance from the point to the tile (0 inside it).
            val ex = max(0f, max(col * TILE - cx, cx - (col + 1) * TILE))
            val ey = max(0f, max(row * TILE - cy, cy - (row + 1) * TILE))
            if (ex * ex + ey * ey <= r2) needed[idx] = true
        }
    }

    /**
     * True when everything [destBounds] (document px, the original's) samples lies outside the
     * document: the stroke copies nothing. v1.8 D: with copies, also every copy's (copy k shows
     * M_k([destBounds]) and samples M_k of the original's source rect).
     */
    fun readsOnlyOutside(destBounds: Rect): Boolean {
        src.set(destBounds)
        src.offset(-dx, -dy)
        if (src.intersect(0, 0, doc.width, doc.height)) return false
        if (!isActive || destBounds.isEmpty) return true
        val shown = Rect()
        for (k in 1..copyN) {
            // Where the copy lands within the document, and what that part samples.
            if (mapBounded(copyMaps[k], copyValues[k], destBounds, shown)) {
                shown.inset(-1, -1)
                if (!shown.intersect(0, 0, doc.width, doc.height)) continue
            } else {
                shown.set(0, 0, doc.width, doc.height)
            }
            if (!sourceRectOf(k, shown, copySrc)) return false
            if (copySrc.intersect(0, 0, doc.width, doc.height)) return false
        }
        return true
    }

    override fun prepare(destDocRect: Rect) {
        if (!isActive || destDocRect.isEmpty) return
        src.set(destDocRect)
        src.offset(-dx, -dy)
        if (sample == Sample.ALL_LAYERS) fill(src)
        current = if (insideDocument(src)) direct else regionShader(destDocRect) ?: direct
    }

    override fun prepareCommit(commitRect: Rect) {
        if (!isActive || commitRect.isEmpty) return
        src.set(commitRect)
        src.offset(-dx, -dy)
        if (sample == Sample.ALL_LAYERS) fill(src, onlyNeeded = neededAny)
        // This layer: the commit writes the layer it reads, tile by tile; where the regions meet
        // it must read the pixels as they were before the stroke (a 1 px margin for filtering).
        val overlaps = sample == Sample.THIS_LAYER &&
            Rect.intersects(Rect(src.left - 1, src.top - 1, src.right + 1, src.bottom + 1), commitRect)
        if (!overlaps && insideDocument(src)) {
            current = direct
            return
        }
        val copy = try {
            Bitmap.createBitmap(commitRect.width(), commitRect.height(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            controller.toast(LOW_MEMORY_MESSAGE)
            current = direct
            return
        }
        copyRegion(Canvas(copy), commitRect)
        commitCopy?.recycle()
        commitCopy = copy
        current = shaderOf(copy, commitRect.left, commitRect.top)
    }

    override fun endStroke() {
        isActive = false
        backing = null
        direct = idleShader()
        current = direct
        commitCopy?.recycle()
        commitCopy = null
        clearCopies()
    }

    /** Frees the All-layers snapshot (the tool was put away). */
    fun releaseSnapshot() {
        if (isActive && sample == Sample.ALL_LAYERS) return
        snapshot?.recycle()
        snapshot = null
        snapshotCanvas = null
        snapshotTarget = null
        filled = BooleanArray(0)
        needed = BooleanArray(0)
        neededAny = false
    }

    /** Frees everything (the editor closes). */
    fun release() {
        endStroke()
        releaseSnapshot()
        scratch?.recycle()
        scratch = null
        scratchCanvas = null
        copyScratch?.recycle()
        copyScratch = null
        copyScratchCanvas = null
    }

    /** True while an All-layers snapshot is allocated (tests). */
    internal val hasSnapshot: Boolean get() = snapshot != null

    // ------------------------------------------------------------------ internals

    private fun insideDocument(r: Rect): Boolean = r.left >= 0 && r.top >= 0 && r.right <= doc.width && r.bottom <= doc.height

    /**
     * A shader for [dest] whose source leaves the document: a copy of the source region,
     * transparent outside the document (null when no copy can be made).
     */
    private fun regionShader(dest: Rect): Shader? {
        val w = dest.width()
        val h = dest.height()
        if (w.toLong() * h > MAX_SCRATCH_PIXELS) return null
        var bmp = scratch
        if (bmp == null || bmp.width < w || bmp.height < h) {
            bmp?.recycle()
            bmp = try {
                Bitmap.createBitmap(max(w, bmp?.width ?: 0), max(h, bmp?.height ?: 0), Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                scratch = null
                scratchCanvas = null
                return null
            }
            scratch = bmp
            scratchCanvas = Canvas(bmp)
        }
        // Only the part this region uses (the scratch keeps the size of the largest region).
        val canvas = scratchCanvas!!
        canvas.save()
        canvas.clipRect(0, 0, w, h)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.restore()
        copyRegion(canvas, dest)
        return shaderOf(bmp, dest.left, dest.top)
    }

    /** Copies the source of [dest] (its pixels [dx], [dy] away, within the document) to (0, 0) of [canvas]. */
    private fun copyRegion(canvas: Canvas, dest: Rect) {
        val from = backing ?: return
        part.set(dest)
        part.offset(-dx, -dy)
        val left = part.left
        val top = part.top
        if (!part.intersect(0, 0, min(doc.width, from.width), min(doc.height, from.height))) return
        dst.set(part.left - left, part.top - top, part.right - left, part.bottom - top)
        canvas.drawBitmap(from, part, dst, copyPaint)
        regionCopies++
    }

    private fun ensureSnapshot(): Bitmap? {
        val w = doc.width
        val h = doc.height
        snapshot?.let { s ->
            if (!s.isRecycled && s.width == w && s.height == h) return s
            releaseSnapshot()
        }
        if (w.toLong() * h * 4L > snapshotBudgetBytes) return null
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        snapshot = bmp
        snapshotCanvas = Canvas(bmp)
        snapshotTarget = CompositeTarget.identity(bmp)
        cols = (w + TILE - 1) / TILE
        rows = (h + TILE - 1) / TILE
        filled = BooleanArray(cols * rows)
        needed = BooleanArray(cols * rows)
        return bmp
    }

    /**
     * Composites every snapshot tile of [region] (document px) that is not filled yet; with
     * [onlyNeeded], only those [notePath] marked.
     */
    private fun fill(region: Rect, onlyNeeded: Boolean = false) {
        val canvas = snapshotCanvas ?: return
        val target = snapshotTarget ?: return
        val w = doc.width
        val h = doc.height
        val l = max(0, region.left)
        val t = max(0, region.top)
        val r = min(w, region.right)
        val b = min(h, region.bottom)
        if (r <= l || b <= t) return
        for (row in t / TILE..(b - 1) / TILE) {
            for (col in l / TILE..(r - 1) / TILE) {
                val idx = row * cols + col
                if (idx !in filled.indices || filled[idx]) continue
                if (onlyNeeded && !needed[idx]) continue
                filled[idx] = true
                tile.set(col * TILE, row * TILE, min(w, (col + 1) * TILE), min(h, (row + 1) * TILE))
                canvas.save()
                canvas.clipRect(tile)
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                snapshotCompositor.drawDocument(canvas, tile, useOverrides = false, target = target)
                canvas.restore()
                tilesRendered++
            }
        }
    }

    companion object {
        /** Snapshot tile size (document px). */
        const val TILE = 256

        /** Larger off-document regions are sampled directly (their edge pixels repeat). */
        private const val MAX_SCRATCH_PIXELS = 4L * 1024 * 1024

        /** Least slack around a noted path (document px). */
        private const val PATH_MARGIN = 32f

        const val LOW_MEMORY_MESSAGE = "Low memory: the clone may repeat what it just painted"

        private val IDENTITY_VALUES = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /** |w| below this is at a perspective map's horizon. */
        private const val HORIZON_EPS = 1e-6f

        /** A mapped rect reaching beyond ±this (document px) counts as unbounded. */
        private const val MAX_MAPPED = 1e7f

        private fun shaderOf(bitmap: Bitmap, left: Int, top: Int): Shader =
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(left.toFloat(), top.toFloat()) })
            }

        private var idle: Shader? = null

        /** A transparent shader for when no stroke runs ([CoverageSource.shader] is never null). */
        private fun idleShader(): Shader = idle ?: BitmapShader(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP,
        ).also { idle = it }
    }
}
