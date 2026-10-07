package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushTip
import com.brushwork.paint.brush.PaperGrain
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.brush.TipShapes
import com.brushwork.paint.brush.sanitized
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.render.RenderCache
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlin.math.abs

/**
 * How a layer's editable data follows a canvas geometry change (resize image, canvas size, crop,
 * trim, rotate, flip, color mode; v1.5 §5.4 and §4.9's table; API frozen, owned by A1). Called by
 * CanvasOps for every layer whose pixels changed; pure and thread-safe (immutable data in, out).
 *
 * - Vector content is mapped object by object ([VectorOps.transformed]: strokes scale their
 *   `sizeScale` by sqrt|det|, shapes stay shapes under similarities and symmetric mirrors; v1.7:
 *   a stroke's symmetry copies are conjugated, so they move, turn and mirror with it).
 * - A mask spec is mapped by [MaskSpecs.transformed] (dropped when that can't).
 * - Text and shape layers become raster layers (as in v1.4: their pixels are resampled).
 * - v1.7 (item 3, design §3.3 b): a vector array's spec is mapped with its content
 *   ([ArraySpec.mapped]; a CIRCLE centre or TRANSFORM pivot that follows the source is fixed
 *   first from the content's bounds, unless the map only moves, so the copies land where the
 *   old ones map to). Every other array is baked (dropped: the layer's pixels already show the
 *   copies), as text and shape data are: its source is text or shape data that is dropped, or
 *   pixels that the operation does not map.
 * - An identity map (color-mode changes) keeps everything else: in a grayscale or 1-bit document
 *   a vector layer's cache is the constrained rendering of its objects (a vector array is kept;
 *   any other array is baked, its source being dropped text or shape data or unconverted pixels).
 * - Adjustments are kept.
 */
object LayerDataTransforms {
    /** Data after a canvas geometry change [m] (old -> new document px); vector content mapped (sizeScale), maskSpec via MaskSpecs.transformed. */
    fun transformed(d: LayerData, m: Matrix, newW: Int, newH: Int): LayerData {
        if (m.isIdentity) return d.copy(text = null, shape = null, array = d.array?.takeIf { d.vector != null })
        val values = FloatArray(9).also { m.getValues(it) }
        val vector = d.vector?.let { mapped(it, values) }
        val mask = d.maskSpec?.let { MaskSpecs.transformed(it, m) }
        val array = d.array?.let { a -> d.vector?.let { v -> mappedArray(a, v, values) } }
        return d.copy(text = null, shape = null, vector = vector, maskSpec = mask, array = array)
    }

    /**
     * A vector source's [array] under [m] (3x3 row-major; [content] = the source BEFORE the map).
     * A null CIRCLE centre or TRANSFORM pivot follows the source's bounds, which is not where the
     * old placement maps to under a flip or a turn: unless [m] only moves, it is set from the
     * content's paint bounds (`ObjectIndex.unionBounds`, what the array is measured from) first.
     */
    private fun mappedArray(array: LayerArray, content: VectorContent, m: FloatArray): LayerArray {
        var spec = array.spec
        val moveOnly = m[0] == 1f && m[1] == 0f && m[3] == 0f && m[4] == 1f && m[6] == 0f && m[7] == 0f && m[8] == 1f
        if (!moveOnly) {
            val circle = spec.mode == ArrayMode.CIRCLE && (spec.centerX == null || spec.centerY == null)
            val turn = spec.mode == ArrayMode.TRANSFORM && (spec.pivotX == null || spec.pivotY == null)
            if (circle || turn) {
                val r = spec.resolved(ObjectIndex.of(content).unionBounds())
                spec = if (circle) spec.copy(centerX = r.centerX, centerY = r.centerY) else spec.copy(pivotX = r.pivotX, pivotY = r.pivotY)
            }
        }
        return array.copy(spec = spec.mapped(m))
    }

    /** [content] with every object mapped by [m] (3x3 row-major); ids and order kept. */
    fun mapped(content: VectorContent, m: FloatArray): VectorContent =
        content.copy(objects = content.objects.map { VectorOps.transformed(it, m) })

    /**
     * True when moving a vector layer's cache by whole ([dx], [dy]) document px gives the
     * rendering of its moved objects (I1), so the pixels can simply move. Paper grain is anchored
     * to the document's [PaperGrain.SIZE] px grid: a layer that paints with grain moves exactly
     * only by multiples of it (whatever else a brush paints moves with its points). Otherwise
     * the layer is drawn again from its objects, or a later partial re-render would show seams in
     * the grain at tile edges.
     */
    fun shiftsExactly(content: VectorContent, dx: Int, dy: Int): Boolean =
        (dx % PaperGrain.SIZE == 0 && dy % PaperGrain.SIZE == 0) || content.objects.none { o -> brushesOf(o).any { it.grain > 0f } }

    /**
     * True when turning a vector layer's cache by [quarterTurns] (clockwise) quarter turns, or
     * mirroring it ([mirror]), gives the rendering of its mapped objects (I1), so the pixels can
     * be remapped exactly. A replayed brush keeps its scatter offsets along the document's axes
     * and its paper grain anchored to the document, and textured tips turn each dab at random; a
     * tip the map does not carry into itself is turned along with the objects
     * (`VectorOps.turnedBrush`) but stamped anew, which differs slightly from turned pixels. A
     * layer with any of those is drawn again from its mapped objects instead, or a later partial
     * re-render would show seams at tile edges.
     */
    fun turnsExactly(content: VectorContent, quarterTurns: Int, mirror: Boolean): Boolean =
        content.objects.all { o -> brushesOf(o).all { tipTurnsExactly(it, quarterTurns, mirror) } }

    /** The brushes [o] is replayed with (sanitized): a stroke's preset, a brush outline's brush. */
    private fun brushesOf(o: VObject): List<BrushPreset> = when (o) {
        is VStroke -> listOf(o.preset.sanitized())
        is VPath -> o.stroke?.takeIf { it.kind == VStrokeKind.BRUSH }?.let { listOf(VectorOps.brushOf(it).sanitized()) } ?: emptyList()
        is VShape -> if (o.shape.paintsWithBrush) listOf(VectorOps.brushPresetOf(o.shape).sanitized()) else emptyList()
    }

    private fun tipTurnsExactly(p: BrushPreset, quarterTurns: Int, mirror: Boolean): Boolean {
        if (p.grain > 0f || p.scatter > 0f || TipShapes.isTextured(p.tip)) return false
        val round = p.roundness >= 0.999f
        val fourfold = round && (p.tip == BrushTip.SQUARE || p.tip == BrushTip.MARKER)
        // A circle maps into itself under every turn and mirror.
        if (round && !fourfold) return true
        // A square (90° symmetric) or an ellipse / oblong (180° symmetric) at the tip angle.
        val period = if (fourfold) 90.0 else 180.0
        if (Math.floorMod(quarterTurns, 2) == 1 && !fourfold) return false
        if (!mirror) return true
        // A mirror takes the angle a to -a: the same tip when 2a is a multiple of the period.
        return abs(Math.IEEEremainder(2.0 * p.angle, period)) < 1e-3
    }

    /**
     * Background-thread re-render of a vector layer for scaling ops (null = keep CanvasOps'
     * resampled pixels): [content] (in the NEW document's coordinates, i.e. already mapped) drawn
     * on a new [newW] x [newH] bitmap, crisp at any scale. Null when [cancelled] (checked between
     * objects) or out of memory.
     */
    fun renderScaled(content: VectorContent, newW: Int, newH: Int, cancelled: () -> Boolean): Bitmap? =
        try {
            renderScaled(content, newW, newH, cancelled) {}
        } catch (e: OutOfMemoryError) {
            null
        }

    /**
     * [renderScaled] reporting [progress] (0..1, per object; it may throw to abandon the render,
     * e.g. CanvasOps' Stop) and letting out-of-memory errors through to the caller.
     */
    internal fun renderScaled(content: VectorContent, newW: Int, newH: Int, cancelled: () -> Boolean, progress: (Float) -> Unit): Bitmap? {
        if (newW < 1 || newH < 1) return null
        val out = BitmapUtils.createLayerBitmap(newW, newH)
        val tips = TipCache(8L shl 20)
        try {
            val doc = Rect(0, 0, newW, newH)
            val finished = VectorLayerRenderer.renderWith(Canvas(out), content, doc, emptySet(), tips, doc, RenderCache()) { done, n ->
                progress(done.toFloat() / n.coerceAtLeast(1))
                !cancelled()
            }
            if (!finished) {
                out.recycle()
                return null
            }
        } catch (t: Throwable) {
            out.recycle()
            throw t
        } finally {
            tips.clear()
        }
        return out
    }
}
