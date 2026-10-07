package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import kotlin.math.min

/**
 * v1.7 (item 8, I11, §3.8 c): drawing a document that has folders. [Compositor.drawDocument]
 * calls it only when the document has a folder; without one the v1.6 loop runs unchanged (I5).
 *
 * The tree is drawn level by level. On each level the sibling units (a layer, or a folder with
 * everything in it) form the groups of the v1.6 loop: an adjustment layer is its own group, and a
 * base takes the clipping units directly above it (Photoshop/ibisPaint: clipping is per level, so
 * a clipping layer at the bottom of a folder has no base and draws unclipped). A group without a
 * folder in it is drawn by the compositor's own [Compositor.drawGroup], exactly as before. A
 * folder then draws:
 * - **pass-through at 100 %** (not a clip base, not clipped): its children straight onto the
 *   current canvas, as if the folder were not there;
 * - **isolated** (pass-through off, a clip base, or clipped): its children into a scratch tile of
 *   at most [TILE]² target px with its own [CompositeTarget] (adjustment layers inside read their
 *   backdrop from it, which a `saveLayer` could not give them, V8), then the scratch onto the
 *   parent with the folder's blend mode and opacity. A folder clip base is rendered once per tile
 *   and reused for every clipped unit's `DST_IN`;
 * - **pass-through below 100 %**, exactly o·C + (1 − o)·B: the backdrop B is copied into a
 *   scratch, the children C are drawn onto it, then the scratch replaces the parent's tile (`SRC`)
 *   under a constant coverage of o (an A8 bitmap drawn as a mask of the scratch's shader), and
 *   coverage lerps. One pass, one rounding, and a convex combination: nothing overflows. (The
 *   design's two passes, `DST_IN` at 1 − o then `PLUS` at o, round twice: off by 2 LSB.)
 *
 * Only folder units are tiled; the layers around them draw untiled, as in v1.6. Scratch tiles are
 * pooled per compositor ([FolderScratchPool]): one per isolated level being drawn, two when a
 * folder clip base and a clipped folder meet on one level.
 *
 * Without a target (no caller passes none) isolated folders use a `saveLayer` (adjustment layers
 * then draw as pass-through anyway), and so does a pass-through folder below 100 %, as an
 * isolated Normal group: an approximation. So is a pass-through folder below 100 % on a target
 * that is not [CompositeTarget.directWrite] (its bitmap may lag behind the canvas).
 */
object FolderComposite {
    /** The side of a scratch tile, in target px (the adjustment stage's chunk). */
    const val TILE = 512

    /**
     * Draws [doc] (which has folders) into [canvas] like [Compositor.drawDocument]: [bounds] =
     * the region being drawn, [target] the bitmap behind [canvas], [layerRange] (flat indices) only
     * those layers. A range may cut through a folder only where the folder draws its children
     * straight onto the canvas (see [Compositor.drawDocument]); elsewhere it throws
     * [IllegalArgumentException].
     */
    fun draw(
        compositor: Compositor,
        doc: Document,
        canvas: Canvas,
        bounds: RectF,
        override: LayerRenderOverride?,
        target: CompositeTarget?,
        layerRange: IntRange?,
    ) {
        val layers = doc.layers
        val selected = layerRange ?: layers.indices
        if (selected.isEmpty()) return
        val pass = Pass(compositor, doc, override, selected)
        pass.level(Surface(canvas, target, bounds, direct = target?.directWrite == true), Layer.ROOT_ID, layers.indices)
    }

    /**
     * True when every folder the layer at [index] is in draws its children straight onto the
     * canvas: pass-through, visible, at 100 %, neither a clip base (the sibling unit directly
     * above it clips) nor clipped (it clips onto the sibling unit below it), exactly as [draw]
     * decides. True at the top level. Then a layer range may start or end at [index] (a live
     * adjustment's below-cache), and the composite below the layer is the plain stack below it.
     *
     * The compositor's own rule: `LayerTree.showsAsIs` reads the layer at `index + 1` as the
     * sibling above, which is the bottom child of a sibling FOLDER above, not that folder.
     */
    fun drawnAsIs(layers: List<Layer>, index: Int): Boolean {
        if (index !in layers.indices || layers[index].parentId == Layer.ROOT_ID) return true
        for (a in LayerTree.ancestors(layers, index)) {
            val f = layers[a]
            if (f.folder?.passThrough != true || !f.visible || f.opacity < 1f) return false
            if (isClipped(layers, a) || isClipBase(layers, a)) return false
        }
        return true
    }

    /**
     * True when the unit whose top is [index] (a layer, or a folder) is clipped, as [draw] groups
     * its level: it clips, it is no adjustment layer, and the top of the sibling unit directly
     * below it (which sits right below its block) is no adjustment layer either. The bottom unit
     * of a level has no sibling below: it draws unclipped. Eyes are not read (a hidden unit still
     * belongs to its group).
     */
    fun isClipped(layers: List<Layer>, index: Int): Boolean {
        val l = layers[index]
        if (!l.clipping || l.isAdjustmentLayer) return false
        val below = LayerTree.block(layers, index).first - 1
        return below >= 0 && layers[below].parentId == l.parentId && !layers[below].isAdjustmentLayer
    }

    /**
     * True when the unit whose top is [index] is the base of a clipping group, as [draw] groups
     * its level: it is neither clipped nor an adjustment layer, and the sibling unit directly
     * above it (whose top is the first layer above with the same parent) clips. Eyes are not
     * read: a base whose clipping units are all hidden is still drawn as a group (a folder base:
     * isolated, with its own blend mode and opacity).
     */
    fun isClipBase(layers: List<Layer>, index: Int): Boolean {
        val l = layers[index]
        if (l.isAdjustmentLayer || isClipped(layers, index)) return false
        val pid = l.parentId
        var j = index + 1
        while (j < layers.size && layers[j].parentId != pid && layers[j].id != pid) j++
        return j < layers.size && layers[j].parentId == pid && layers[j].clipping && !layers[j].isAdjustmentLayer
    }

    /**
     * The composite of [folder]'s children (all levels; the folder's own opacity, blend and eye
     * not applied: its children are composited isolated, onto transparency) as a new
     * document-sized bitmap the caller owns ("Merge folder", "Layer from folder", the folder
     * thumbnails). Eyes, clipping and adjustment layers inside are honoured. Empty when [folder]
     * is not a folder of [doc].
     */
    fun renderBlock(doc: Document, folder: Layer): Bitmap {
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        val f = doc.indexOf(folder)
        if (f < 0 || !folder.isFolder) return out
        val block = LayerTree.block(doc.layers, f)
        val pass = Pass(Compositor(doc) { null }, doc, null, doc.layers.indices)
        val surface = Surface(Canvas(out), CompositeTarget.identity(out), RectF(doc.bounds), direct = true)
        pass.level(surface, folder.id, block.first until f)
        return out
    }

    /**
     * Where one level draws: [canvas] (whose matrix is `target.docToTarget`, the
     * [CompositeTarget] contract), the bitmap behind it, the region in document px, and whether
     * the canvas draws straight into [target]'s bitmap with nothing pending (a pass-through folder
     * below 100 % reads its backdrop from there).
     */
    private class Surface(val canvas: Canvas, val target: CompositeTarget?, val bounds: RectF, val direct: Boolean)

    /** One draw of the tree: [selected] = the flat indices drawn (see [draw]). */
    private class Pass(
        private val c: Compositor,
        private val doc: Document,
        private val override: LayerRenderOverride?,
        private val selected: IntRange,
    ) {
        private val layers = doc.layers
        private val pool = c.folderScratch

        /**
         * Draws the level whose parent is [parentId] ([Layer.ROOT_ID] = the top level): the units
         * of [range] (the folder's inside, or every index), bottom first, in v1.6 groups.
         */
        fun level(s: Surface, parentId: Long, range: IntRange) {
            val units = LayerTree.units(layers, range, parentId)
            var i = 0
            while (i < units.size) {
                var j = i + 1
                if (!layers[units[i].last].isAdjustmentLayer) {
                    while (j < units.size && clips(layers[units[j].last])) j++
                }
                val lo = units[i].first
                val hi = units[j - 1].last
                when {
                    hi < selected.first || lo > selected.last -> Unit
                    lo >= selected.first && hi <= selected.last -> group(s, units, i, j)
                    else -> cut(s, units, i, j)
                }
                i = j
            }
        }

        /** True when [l] clips to the unit below it (an adjustment layer never does). */
        private fun clips(l: Layer): Boolean = l.clipping && !l.isAdjustmentLayer

        /** The group of units [i, j) of a level: a base and the units clipped to it. */
        private fun group(s: Surface, units: List<IntRange>, i: Int, j: Int) {
            val baseUnit = units[i]
            val base = layers[baseUnit.last]
            if (base.isAdjustmentLayer) {
                adjust(s, base)
                return
            }
            if (!base.visible || base.opacity <= 0f) return
            val clipUnits = ArrayList<IntRange>(j - i - 1)
            for (k in i + 1 until j) {
                val l = layers[units[k].last]
                if (l.visible && l.opacity > 0f) clipUnits += units[k]
            }
            if (!base.isFolder && clipUnits.none { layers[it.last].isFolder }) {
                c.drawGroup(s.canvas, base, clipUnits.map { layers[it.last] }, s.bounds, override)
                return
            }
            val spec = base.folder
            if (spec != null && j == i + 1) {
                when {
                    !spec.passThrough -> isolated(s, baseUnit, base.blendMode, base.opacity)
                    base.opacity >= 1f -> level(s, base.id, inside(baseUnit))
                    else -> passThroughBelow(s, baseUnit, base.opacity)
                }
                return
            }
            clipGroup(s, baseUnit, clipUnits)
        }

        /**
         * A group the selected range cuts through: allowed only for a lone folder drawing its
         * children straight onto the canvas (see [Compositor.drawDocument]).
         */
        private fun cut(s: Surface, units: List<IntRange>, i: Int, j: Int) {
            val f = layers[units[i].last]
            val spec = f.folder
            val drawn = f.visible && f.opacity > 0f
            require(j == i + 1 && spec != null && spec.passThrough && (!drawn || f.opacity >= 1f)) {
                "Layer range $selected cuts through $f, which is composited isolated"
            }
            if (drawn) level(s, f.id, inside(units[i]))
        }

        private fun adjust(s: Surface, layer: Layer) {
            if (!layer.visible || layer.opacity <= 0f) return
            c.adjustmentScratch.colorMode = doc.colorMode
            AdjustmentStage.draw(s.canvas, layer, s.bounds, c.overrideFor(layer, override), s.target, c.adjustmentScratch)
        }

        /** The folder unit [u]'s children composited isolated, then drawn with [mode] at [opacity]. */
        private fun isolated(s: Surface, u: IntRange, mode: LayerBlendMode, opacity: Float) {
            if (s.target == null) {
                val save = s.canvas.saveLayer(s.bounds, BlendModes.paint(mode, opacity))
                level(s, layers[u.last].id, inside(u))
                s.canvas.restoreToCount(save)
                return
            }
            val paint = BlendModes.paint(mode, opacity).apply { isFilterBitmap = false }
            tiles(s) { tile, docTile ->
                val scratch = children(s, u, tile, docTile, backdrop = false)
                blit(s.canvas, scratch, tile, paint)
                pool.release(scratch)
            }
        }

        /** A pass-through folder below 100 %: o·C + (1 − o)·B, tile by tile (see the class docs). */
        private fun passThroughBelow(s: Surface, u: IntRange, opacity: Float) {
            val a0 = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            if (a0 <= 0) return
            if (a0 >= 255) {
                level(s, layers[u.last].id, inside(u))
                return
            }
            if (s.target == null || !s.direct) {
                isolated(s, u, LayerBlendMode.NORMAL, opacity)
                return
            }
            val cv = s.canvas
            val coverage = pool.coverage(a0)
            tiles(s) { tile, docTile ->
                val scratch = children(s, u, tile, docTile, backdrop = true)
                val save = cv.save()
                cv.setMatrix(null)
                cv.clipRect(tile)
                // One pass: an A8 bitmap draws as a coverage mask of the paint's shader, and
                // coverage lerps the SRC result with what is there: B + (C − B)·o.
                cv.drawBitmap(coverage, tile.left.toFloat(), tile.top.toFloat(), pool.lerpPaint(scratch, tile))
                cv.restoreToCount(save)
                pool.release(scratch)
            }
        }

        /**
         * A group with a folder in it: [baseUnit] and the visible units [clipUnits] clipped to it,
         * as [Compositor.drawGroup] (the base's blend and opacity for the whole group; each clip
         * kept where the base has alpha). Folders in it composite isolated; a folder base is
         * rendered once per tile.
         */
        private fun clipGroup(s: Surface, baseUnit: IntRange, clipUnits: List<IntRange>) {
            val base = layers[baseUnit.last]
            val groupPaint = BlendModes.paint(base.blendMode, base.opacity)
            val cv = s.canvas
            if (s.target == null) {
                compose(cv, s.bounds, groupPaint, baseUnit, clipUnits) { u, b ->
                    val l = layers[u.last]
                    if (l.isFolder) {
                        val save = cv.saveLayer(b, null)
                        level(s, l.id, inside(u))
                        cv.restoreToCount(save)
                    } else {
                        c.drawLayer(cv, l, null, b, override)
                    }
                }
                return
            }
            tiles(s) { tile, docTile ->
                val baseScratch = if (base.isFolder) children(s, baseUnit, tile, docTile, backdrop = false) else null
                val save = cv.save()
                cv.clipRect(docTile)
                compose(cv, docTile, groupPaint, baseUnit, clipUnits) { u, b ->
                    val l = layers[u.last]
                    when {
                        u === baseUnit && baseScratch != null -> blit(cv, baseScratch, tile, pool.plainPaint)
                        l.isFolder -> {
                            val scratch = children(s, u, tile, docTile, backdrop = false)
                            blit(cv, scratch, tile, pool.plainPaint)
                            pool.release(scratch)
                        }
                        else -> c.drawLayer(cv, l, null, b, override)
                    }
                }
                cv.restoreToCount(save)
                if (baseScratch != null) pool.release(baseScratch)
            }
        }

        /** The saveLayer structure of [Compositor.drawGroup], [content] drawing a unit plainly. */
        private inline fun compose(
            cv: Canvas,
            bounds: RectF,
            groupPaint: Paint,
            baseUnit: IntRange,
            clipUnits: List<IntRange>,
            content: (IntRange, RectF) -> Unit,
        ) {
            val save = cv.saveLayer(bounds, groupPaint)
            content(baseUnit, bounds)
            for (u in clipUnits) {
                val l = layers[u.last]
                val cs = cv.saveLayer(bounds, BlendModes.paint(l.blendMode, l.opacity))
                content(u, bounds)
                // Keep only where the base (a folder: its composite) has alpha.
                val bs = cv.saveLayer(bounds, c.dstInPaint)
                content(baseUnit, bounds)
                cv.restoreToCount(bs)
                cv.restoreToCount(cs)
            }
            cv.restoreToCount(save)
        }

        /**
         * A pooled scratch holding the children of the folder unit [u] for the target tile [tile]
         * (document rect [docTile]) of [s]: composited onto transparency, or with [backdrop] onto
         * a copy of what [s]'s bitmap holds there. Its pixel (0, 0) is the tile's top-left pixel.
         */
        private fun children(s: Surface, u: IntRange, tile: Rect, docTile: RectF, backdrop: Boolean): FolderScratchPool.Scratch {
            val t = s.target!!
            val scratch = pool.acquire()
            val cv = scratch.canvas
            val w = tile.width()
            val h = tile.height()
            val save = cv.save()
            cv.clipRect(0, 0, w, h)
            if (backdrop) cv.drawBitmap(t.bitmap, tile, Rect(0, 0, w, h), pool.copyPaint)
            val m = Matrix(t.docToTarget).apply { postTranslate(-tile.left.toFloat(), -tile.top.toFloat()) }
            cv.concat(m)
            val target = CompositeTarget(scratch.bitmap, m, display = t.display, directWrite = t.directWrite)
            level(Surface(cv, target, docTile, direct = true), layers[u.last].id, inside(u))
            cv.restoreToCount(save)
            return scratch
        }

        /** Draws [scratch] onto [cv] at target tile [tile], pixel for pixel, with [paint]. */
        private fun blit(cv: Canvas, scratch: FolderScratchPool.Scratch, tile: Rect, paint: Paint) {
            val save = cv.save()
            cv.setMatrix(null)
            cv.clipRect(tile)
            cv.drawBitmap(scratch.bitmap, tile.left.toFloat(), tile.top.toFloat(), paint)
            cv.restoreToCount(save)
        }

        /**
         * Calls [body] for each tile of at most [TILE]² target px covering [s]'s region (rounded
         * as Skia rounds a non-anti-aliased clip, and limited to the target bitmap), with the
         * tile's document rect.
         */
        private inline fun tiles(s: Surface, body: (Rect, RectF) -> Unit) {
            val t = s.target ?: return
            val mapped = RectF(s.bounds)
            t.docToTarget.mapRect(mapped)
            val d = Rect(Math.round(mapped.left), Math.round(mapped.top), Math.round(mapped.right), Math.round(mapped.bottom))
            if (!d.intersect(0, 0, t.bitmap.width, t.bitmap.height) || d.isEmpty) return
            val inverse = Matrix()
            if (!t.docToTarget.invert(inverse)) return
            var y = d.top
            while (y < d.bottom) {
                val bottom = min(d.bottom, y + TILE)
                var x = d.left
                while (x < d.right) {
                    val right = min(d.right, x + TILE)
                    val tile = Rect(x, y, right, bottom)
                    val docTile = RectF(tile)
                    inverse.mapRect(docTile)
                    body(tile, docTile)
                    x = right
                }
                y = bottom
            }
        }

        /** The flat indices inside the folder unit [u] (all levels). */
        private fun inside(u: IntRange): IntRange = u.first until u.last
    }
}

/**
 * The [FolderComposite.TILE]² scratch bitmaps of one [Compositor] (v1.7): a stack, so nested
 * isolated folders reuse them draw after draw. Erased when acquired (deterministic output).
 * Used on the compositor's drawing thread only, like its other scratch state.
 */
internal class FolderScratchPool {
    /** One scratch bitmap and the canvas drawing into it. */
    class Scratch(val bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val free = ArrayList<Scratch>()

    /** Source-over, no filtering: a scratch drawn pixel for pixel. */
    val plainPaint = Paint()

    /** Copies pixels as they are (the pass-through backdrop). */
    val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    private val lerp = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val lerpMatrix = Matrix()
    private var coverageBitmap: Bitmap? = null
    private var coverageAlpha = -1

    /** An A8 [FolderComposite.TILE]² bitmap of alpha [a] everywhere (one, refilled when [a] changes). */
    fun coverage(a: Int): Bitmap {
        val b = coverageBitmap ?: Bitmap.createBitmap(FolderComposite.TILE, FolderComposite.TILE, Bitmap.Config.ALPHA_8).also { coverageBitmap = it }
        if (coverageAlpha != a) {
            b.eraseColor(Color.argb(a, 0, 0, 0))
            coverageAlpha = a
        }
        return b
    }

    /** SRC through the pixels of [s], placed at target tile [tile]'s top-left. */
    fun lerpPaint(s: Scratch, tile: Rect): Paint {
        lerpMatrix.setTranslate(tile.left.toFloat(), tile.top.toFloat())
        s.shader.setLocalMatrix(lerpMatrix)
        lerp.shader = s.shader
        return lerp
    }

    /** The number of scratch bitmaps made so far (each [FolderComposite.TILE]² ARGB). */
    var allocated: Int = 0
        private set

    fun acquire(): Scratch {
        val s = if (free.isEmpty()) {
            allocated++
            Scratch(BitmapUtils.createLayerBitmap(FolderComposite.TILE, FolderComposite.TILE))
        } else {
            free.removeAt(free.size - 1)
        }
        s.bitmap.eraseColor(0)
        return s
    }

    fun release(s: Scratch) {
        free += s
    }
}
