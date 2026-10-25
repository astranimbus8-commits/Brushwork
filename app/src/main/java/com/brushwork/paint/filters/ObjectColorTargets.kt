package com.brushwork.paint.filters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextLayerData
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * v1.10 (R6, A4): which objects of a vector, text or shape layer Change Drawing Color recolors in
 * object mode, and the layer data with their colors mapped (I35: through [ObjectColorSlots]).
 *
 * The three rules ([of]), the first that applies:
 * 1. [Rule.OBJECTS]: objects of the layer are selected (`VectorLayers.selectedIds`, kept by
 *    `EditorController.startFilter`): only those.
 * 2. [Rule.SELECTION]: a pixel selection is active: every object whose drawn coverage meets the
 *    selection mask is recolored whole. Coverage is what the object paints (its stroke and fill,
 *    every live copy and array copy included), never its bounds: a long diagonal curve whose box
 *    meets the selection while its ink passes beside it is not touched. Vector objects and a
 *    legacy shape are tested by `VectorOps.touching`; a text by its rendered ink.
 * 3. [Rule.ALL]: every object of the layer.
 *
 * Instances are immutable; [of] reads the selection mask (main thread, as the selection is).
 */
internal class ObjectColorTargets private constructor(
    /** The rule that chose the targets. */
    val rule: Rule,
    /** Vector object ids recolored; null = every object. */
    val vectorIds: Set<Long>?,
    /** Text ids (`TextItem.id`) recolored; null = every text. */
    val textIds: Set<Int>?,
    /** The legacy shape of a shape layer is recolored. */
    val shape: Boolean,
) {
    /** Which of the three rules chose the targets. */
    enum class Rule { OBJECTS, SELECTION, ALL }

    /**
     * [data] with the targets' colors mapped by [f]: vector objects by `ObjectColorSlots.map`
     * (an array on the layer draws from them, so its copies follow; its tint stops are kept),
     * texts by `ObjectColorSlots.mapText` (color, outline, box fill and border), the legacy shape
     * by `ObjectColorSlots.mapShape`. [data] itself (the same instance) when no color changes.
     */
    fun map(data: LayerData, f: (Int) -> Int): LayerData {
        var out = data
        data.vector?.let { v ->
            val m = ObjectColorSlots.map(v, vectorIds, f)
            if (m !== v) out = out.copy(vector = m)
        }
        data.text?.let { t -> mapText(t, textIds, f)?.let { out = out.copy(text = it) } }
        val s = data.shape
        if (s != null && shape) {
            val o = ShapeCodec.decode(s)
            val m = o?.let { ObjectColorSlots.mapShape(it, f) }
            if (m != null && m !== o) out = out.copy(shape = ShapeCodec.encode(m))
        }
        return out
    }

    /**
     * C12: an affine mapper (CDC's: per channel, unpremultiplied, alpha kept) applied to the
     * layer's pixels gives what [map] re-rendered gives, within 1 per channel. It holds when
     * every object of [data] is a target ([coversAll]: [Rule.ALL], or every object selected or
     * touched by the pixel selection; the selection then masks nothing, as the objects change
     * whole) and every color the layer paints comes from a slot `ObjectColorSlots` maps; it maps
     * them all (a stroke's color, a path's line and fill with gradient stops, a shape's two
     * colors, a text's color, outline, box fill and border), and an affine map commutes with
     * antialiasing and source-over. An array tint breaks it (the copies keep their tint, and
     * recolor then tint is not tint then recolor); an array opacity does not (alpha only). The
     * tint test is `ArrayInstances.of`'s. Texts break it too: Skia antialiases a glyph's edge by
     * its paint's luminance, so a recolored text covers its edge pixels differently (alpha 12 for
     * 24 measured, `CdcObjectModeNoRasterizeTest`).
     */
    fun exactByPixels(data: LayerData): Boolean {
        if (data.text != null || !coversAll(data)) return false
        val look = data.array?.spec?.sanitized()?.look ?: return true
        return !(look.tint.size >= 2 && look.tintStrength > 0f)
    }

    /**
     * Every object of [data] is a target, so [map] maps what [Rule.ALL] maps: always under
     * [Rule.ALL]; under the other rules when every vector object is in [vectorIds] (a pixel
     * selection over the whole canvas touches every object) and a legacy shape is recolored.
     */
    private fun coversAll(data: LayerData): Boolean {
        if (rule == Rule.ALL) return true
        val ids = vectorIds
        val v = data.vector
        if (v != null && ids != null && v.objects.any { it.id !in ids }) return false
        return data.shape == null || shape
    }

    companion object {
        /** Side of the squares a text's ink is rasterized in for [Rule.SELECTION]. */
        private const val PROBE_TILE = 256

        /** Every target of [data] ([Rule.ALL]). */
        fun all(): ObjectColorTargets = ObjectColorTargets(Rule.ALL, null, null, true)

        /** True when [data] holds objects object mode can recolor: vector objects, texts or a shape. */
        fun hasObjects(data: LayerData): Boolean = data.vector != null || data.text != null || data.shape != null

        /**
         * The legacy shape of [shapeData] as vector content, drawn as a shape layer's cache draws it
         * (`DataRender`: its [VShape] keeps the live copies, which the renderer draws); null when
         * it can't be read.
         */
        fun shapeContent(shapeData: String): VectorContent? = ShapeCodec.decode(shapeData)?.let { VectorContent(objects = listOf(VShape(0L, shape = it))) }

        /** True when [data] has no object to recolor: an empty vector layer, a text layer without a text. */
        fun isEmpty(data: LayerData): Boolean =
            data.vector?.objects.isNullOrEmpty() && data.shape == null && (data.text?.let { TextCodec.decodeAll(it).isEmpty() } ?: true)

        /**
         * The targets of [data] by the three rules: [selectedIds] (the layer's selected objects,
         * empty when none), else the pixel selection [sel], else everything.
         */
        fun of(data: LayerData, selectedIds: Set<Long>, sel: Selection?): ObjectColorTargets {
            val v = data.vector
            if (v != null && selectedIds.isNotEmpty()) {
                val present = selectedIds.filterTo(HashSet()) { v.byId(it) != null }
                if (present.isNotEmpty()) return ObjectColorTargets(Rule.OBJECTS, present, emptySet(), false)
            }
            if (sel == null) return all()
            val ids = if (v != null) touchingVector(data, v, sel) else null
            val texts = data.text?.let { touchingTexts(data, it, sel) }
            val shape = data.shape?.let { touchingShape(data, it, sel) } ?: false
            return ObjectColorTargets(Rule.SELECTION, ids ?: emptySet(), texts ?: emptySet(), shape)
        }

        /**
         * The source ids of the objects whose footprint meets [sel], copies of a live array
         * counted for their source (`ArrayDraw.effectiveVector`: copy k of object id has the id
         * id + (k shl 53), real ids lie within ±2^52, so the low 53 bits sign-extended give id).
         */
        private fun touchingVector(data: LayerData, v: VectorContent, sel: Selection): Set<Long> {
            val view = ArrayDraw.effectiveVector(data) ?: v
            val hit = VectorOps.touching(view, sel)
            if (view === v) return hit
            val shift = 64 - ArrayDraw.COPY_ID_SHIFT
            return hit.mapTo(HashSet()) { (it shl shift) shr shift }
        }

        /** Whether the legacy shape [shapeData] (and its array copies) paints inside [sel]. */
        private fun touchingShape(data: LayerData, shapeData: String, sel: Selection): Boolean {
            val o = ShapeCodec.decode(shapeData) ?: return false
            // Each live copy is tested as an object of its own (I17).
            val content = VectorContent(objects = ObjectCopies.expanded(o))
            val view = ArrayDraw.effectiveVector(LayerData(vector = content, array = data.array)) ?: content
            return VectorOps.touching(view, sel).isNotEmpty()
        }

        /** The ids of the texts of [textData] whose ink (array copies included) meets [sel]. */
        private fun touchingTexts(data: LayerData, textData: String, sel: Selection): Set<Int> {
            val items = TextCodec.decodeAll(textData)
            if (items.isEmpty() || sel.isEmpty) return emptySet()
            val array = data.array
            // Copies repeat the whole layer, measured from the union of its texts.
            val source = if (array != null) ArrayDraw.sourceBounds(data) ?: RectF() else null
            val copies = if (array != null) ArrayDraw.cacheBounds(data) else null
            val out = HashSet<Int>()
            val probe = InkProbe(sel)
            try {
                for (item in items) {
                    val prep = TextRenderer.prepare(item)
                    if (prep.isEmpty) continue
                    val b = RectF(prep.docBounds(item))
                    copies?.let { b.union(it) }
                    val area = Rect(floor(b.left).toInt() - 1, floor(b.top).toInt() - 1, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
                    if (!area.intersect(sel.bounds)) continue
                    val draw: (Canvas) -> Unit = { cv -> TextRenderer.drawItem(cv, item, prep, null) }
                    val touched = probe.touches(area) { cv ->
                        if (array != null && source != null) ArrayDraw.drawWithArray(cv, array, source, draw) else draw(cv)
                    }
                    if (touched) out += item.id
                }
            } finally {
                probe.release()
            }
            return out
        }

        /**
         * [textData] with the texts [ids] (null = all) mapped by [f], encoded again; null when no
         * color changes (the layer keeps its exact bytes).
         */
        private fun mapText(textData: String, ids: Set<Int>?, f: (Int) -> Int): String? {
            val items: List<TextItem> = TextCodec.decodeAll(textData)
            if (items.isEmpty()) return null
            val d = TextLayerData(TextCodec.VERSION, items[0], items.subList(1, items.size))
            val m = ObjectColorSlots.mapText(d, ids, f)
            if (m === d) return null
            return TextCodec.encodeAll(listOf(m.item) + m.more)
        }
    }

    /**
     * Rasterizes ink (document px) in [PROBE_TILE] squares of one reused ALPHA_8 buffer and asks
     * the selection at each inked pixel, stopping at the first selected one. Not thread-safe.
     */
    private class InkProbe(private val sel: Selection) {
        private val tile = Bitmap.createBitmap(PROBE_TILE, PROBE_TILE, Bitmap.Config.ALPHA_8)
        private val canvas = Canvas(tile)
        private val bytes = ByteBuffer.allocate(tile.rowBytes * tile.height)

        /** True when [draw] paints a pixel inside [area] where the selection is set. */
        fun touches(area: Rect, draw: (Canvas) -> Unit): Boolean {
            val rb = tile.rowBytes
            var top = area.top
            while (top < area.bottom) {
                val th = min(PROBE_TILE, area.bottom - top)
                var left = area.left
                while (left < area.right) {
                    val tw = min(PROBE_TILE, area.right - left)
                    tile.eraseColor(0)
                    canvas.save()
                    canvas.clipRect(0, 0, tw, th)
                    canvas.translate(-left.toFloat(), -top.toFloat())
                    draw(canvas)
                    canvas.restore()
                    bytes.rewind()
                    tile.copyPixelsToBuffer(bytes)
                    val a = bytes.array()
                    for (y in 0 until th) {
                        val base = y * rb
                        for (x in 0 until tw) if (a[base + x].toInt() != 0 && sel.alphaAt(left + x, top + y) != 0) return true
                    }
                    left += PROBE_TILE
                }
                top += PROBE_TILE
            }
            return false
        }

        fun release() = tile.recycle()
    }
}
