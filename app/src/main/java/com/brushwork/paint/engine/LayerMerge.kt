package com.brushwork.paint.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListKind
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.EditEvent
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayerOps
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlin.math.ceil
import kotlin.math.floor

/** v1.9 (design 2.4.6): what merging two layers keeps (see [LayerMerge.kindOf]). */
enum class MergeKind { TEXT_ITEMS, VECTOR_OBJECTS, RASTER }

/**
 * v1.9 (design 2.4.6; owner A4): "Merge down", moved out of [EditorController], which keeps its
 * `mergeDown` as a one-line delegate (callers and tests do not change). R19: two text layers keep
 * their texts ([MergeKind.TEXT_ITEMS]) and two shape or vector layers their objects
 * ([MergeKind.VECTOR_OBJECTS], as v1.8's [VectorLayerOps.mergeVector] did for two vector layers)
 * when the result looks the same ([kindOf]); anything else is
 * flattened into the layer below through a temporary two-layer document, so the result matches
 * the screen. Every kind is ONE step "Merge down". Never draws the paper (I21). "Merge folder"
 * (`EditorController.mergeFolder`) asks [folderData] whether its merged layer keeps the type.
 */
internal class LayerMerge(private val c: EditorController) {

    /** Merges [layer] into the layer below it (v1.7: its sibling below; a folder merges as "Merge folder"). */
    fun mergeDown(layer: Layer) {
        if (layer.isFolder) { c.mergeFolder(layer); return }
        val idx = c.doc.indexOf(layer)
        val lower = siblingBelow(idx)
        if (lower == null) { c.toast("There is no layer below to merge into"); return }
        if (lower.isFolder) { c.toast(FolderLabels.MERGE_INTO_REFUSAL); return }
        // An adjustment layer has no pixels to receive the merge (its effect on the layers below
        // would be lost); merging one DOWN applies its effect.
        if (lower.isAdjustmentLayer) { c.toast("Layers can't be merged into an adjustment layer"); return }
        c.withToolPaused { mergeDownNow(layer) }
    }

    /** The layer directly below the row at [idx] at its own level (v1.6: `layers[idx - 1]`), or null. */
    fun siblingBelow(idx: Int): Layer? {
        val doc = c.doc
        if (idx <= 0 || idx > doc.layers.lastIndex) return null
        return doc.layers[idx - 1].takeIf { it.parentId == doc.layers[idx].parentId }
    }

    /** [mergeDown] once the tool is paused: one edit scope around [mergeDownInto]. */
    fun mergeDownNow(layer: Layer) {
        val idx = c.doc.indexOf(layer)
        val lower = siblingBelow(idx) ?: return
        if (lower.isAdjustmentLayer || lower.isFolder) return
        // v1.9 (design 2.4.13): the merged bitmap must fit (A7b's policy; DefaultPolicy admits everything).
        if (!MemoryGovernor.admit(c.doc.width.toLong() * c.doc.height * 4, "Merge down")) {
            c.toast(String.format(StabilityLabels19.NO_MEMORY_FOR, "Merge down"))
            return
        }
        c.editScope { mergeDownInto(layer, idx, lower) }
    }

    /** Merges [layer] (at [idx]) into [lower] as ONE step "Merge down" (inside the caller's edit scope). */
    fun mergeDownInto(layer: Layer, idx: Int, lower: Layer) {
        val doc = c.doc
        // v1.9 (R19): two text layers keep their texts, shape and vector layers their objects;
        // otherwise the result is pixels.
        val kept = when (mergeKind(layer, lower)) {
            MergeKind.TEXT_ITEMS -> mergeTexts(layer, lower)
            MergeKind.VECTOR_OBJECTS -> mergeObjects(layer, lower)
            MergeKind.RASTER -> false
        }
        if (kept) {
            c.queueLayerList(LayerListEvent(LayerListKind.MERGED, layer, lower, MERGE_LABEL))
            return
        }
        // Flatten lower (+ its mask, opacity) and upper (with blend, opacity, mask, clipping) via a
        // temporary two-layer document so the result matches what's on screen. When both clip to
        // the same base further down, the upper one is simply drawn over the lower one here (the
        // merged layer keeps the lower layer's clipping). The views carry the adjustment and mask
        // specs: merging an adjustment layer down applies its effect to the layer below.
        // v1.10 (A11, design 2.5): the views share the two layers' stores (a parked store draws
        // from its tiles, so nothing is materialized), pinned while the merge renders.
        val tmpDoc = Document("merge", "merge", doc.width, doc.height)
        val lowerView = Layer(-1, lower.name, lower.pixels).also {
            it.copyPropsFrom(lower.props().copy(blendMode = LayerBlendMode.NORMAL, visible = true, clipping = false))
            it.maskPixels = lower.maskPixels
            it.maskSpec = lower.maskSpec
            it.adjustment = lower.adjustment
        }
        val upperView = Layer(-2, layer.name, layer.pixels).also {
            it.copyPropsFrom(layer.props().copy(visible = true, clipping = layer.clipping && !lower.clipping))
            it.maskPixels = layer.maskPixels
            it.maskSpec = layer.maskSpec
            it.adjustment = layer.adjustment
        }
        tmpDoc.layers += lowerView
        tmpDoc.layers += upperView
        val pins = listOf(layer.pin(), lower.pin())
        val merged = try {
            Compositor(tmpDoc) { null }.renderFlattened()
        } finally {
            pins.forEach { it.close() }
        }
        val beforeProps = lower.props()
        // The merged pixels are plain pixels: no editable data, no mask. The lower layer's own
        // stores move into the undo step by reference (no copy).
        val replace = MergedStoresAction(
            lower, lower.pixels, lower.maskPixels, FlatPixels(merged),
            lower.dataSnapshot(), LayerData.NONE, beforeProps, beforeProps.copy(opacity = 1f, maskEnabled = true),
        )
        val remove = RemoveLayerAction(layer, idx, "Merge down")
        replace.redo(c)
        remove.redo(c)
        c.structural { doc.activeLayerIndex = doc.indexOf(lower) }
        c.pushUndo(CompositeAction("Merge down", listOf(replace, remove)))
        // A committed edit of the lower layer (text wrapped around it re-flows).
        c.queueEdit(EditEvent(lower, EditTarget.CONTENT, null, "Merge down"))
        // v1.6: the upper layer left the stack (a linked text frame heals).
        c.queueLayerList(LayerListEvent(LayerListKind.MERGED, layer, lower, "Merge down"))
    }

    /**
     * What merging [upper] into [lower] (the layer right below it) keeps: [MergeKind.VECTOR_OBJECTS]
     * where v1.8 already merges vector onto vector (the guard of [VectorLayerOps.mergeVector],
     * [VectorLayerOps.mergesAsObjects]); R19: [MergeKind.TEXT_ITEMS] for two text layers that
     * [textWhy] lets through, [MergeKind.VECTOR_OBJECTS] for two shape or vector layers that
     * [objectWhy] lets through; else [MergeKind.RASTER].
     */
    fun kindOf(upper: Layer, lower: Layer): MergeKind = when {
        VectorLayerOps.mergesAsObjects(c, upper, lower) -> MergeKind.VECTOR_OBJECTS
        !adjacent(upper, lower) -> MergeKind.RASTER
        upper.isTextLayer && lower.isTextLayer -> if (textWhyOf(upper, lower) == null) MergeKind.TEXT_ITEMS else MergeKind.RASTER
        holdsObjects(upper) && holdsObjects(lower) -> if (objectWhy(upper, lower) == null) MergeKind.VECTOR_OBJECTS else MergeKind.RASTER
        else -> MergeKind.RASTER
    }

    /** A shape layer (one shape, R19: converted by the merge) or a vector layer. */
    private fun holdsObjects(layer: Layer): Boolean = layer.isVectorLayer || layer.isShapeLayer

    /** True when [lower] is the row right below [upper] (the pair "Merge down" works on). */
    private fun adjacent(upper: Layer, lower: Layer): Boolean {
        val idx = c.doc.indexOf(upper)
        return idx > 0 && c.doc.layers[idx - 1] === lower
    }

    /**
     * [textWhy] of the text layers [upper] and [lower]; [UNREADABLE] when either holds no text that
     * decodes (such a layer is merged as pixels, as in v1.8).
     */
    private fun textWhyOf(upper: Layer, lower: Layer): String? {
        val above = TextCodec.decodeAll(upper.textData)
        val below = TextCodec.decodeAll(lower.textData)
        if (above.isEmpty() || below.isEmpty()) return UNREADABLE
        return textWhy(upper, lower, above, below)
    }

    /**
     * R19: why the texts [above] of [upper] can't join the texts [below] of [lower] (a
     * [LayerLabels19] WHY_* text), or null when they can. Both layers Normal, without a mask,
     * clipping or copies; no text a frame of a story or wrapped around a picture (I24: those stay
     * alone in their layer); at most [TextCodec.MAX_ITEMS] texts in all; the same opacity, and
     * under 100 % no text of one layer meeting a text of the other (the merged layer's one opacity
     * would show their overlap differently). Texts meet when their paint bounds share a pixel.
     */
    private fun textWhy(upper: Layer, lower: Layer, above: List<TextItem>, below: List<TextItem>): String? {
        if (upper.hasMask || upper.maskSpec != null || lower.hasMask || lower.maskSpec != null) return LayerLabels19.WHY_MASK
        if (upper.blendMode != LayerBlendMode.NORMAL || lower.blendMode != LayerBlendMode.NORMAL) return LayerLabels19.WHY_BLEND
        if (upper.clipping || lower.clipping) return LayerLabels19.WHY_CLIP
        if (upper.array != null || lower.array != null) return LayerLabels19.WHY_COPIES
        if (above.any { it.threaded || it.wrap.isOn } || below.any { it.threaded || it.wrap.isOn }) return LayerLabels19.WHY_FRAMES
        if (above.size + below.size > TextCodec.MAX_ITEMS) return LayerLabels19.WHY_TOO_MANY
        if (upper.opacity.compareTo(lower.opacity) != 0) return LayerLabels19.WHY_OPACITY
        if (lower.opacity < 1f) {
            val tops = textPixelRects(above)
            val bottoms = textPixelRects(below)
            if (tops.any { t -> bottoms.any { b -> Rect.intersects(t, b) } }) return LayerLabels19.WHY_OVERLAP
        }
        return null
    }

    /** The pixels each of [items] may paint (its paint bounds rounded out); texts that paint nothing are left out. */
    private fun textPixelRects(items: List<TextItem>): List<Rect> = items.mapNotNull { item ->
        val prep = TextRenderer.prepare(item)
        if (prep.isEmpty) null else pixelRect(prep.docBounds(item)).takeUnless { it.isEmpty }
    }

    /**
     * R19 [MergeKind.TEXT_ITEMS]: the texts of [upper] join those of [lower] on top of them, in
     * picture order (`TextCodec.encodeAll` keeps or assigns their ids); [lower] keeps its name and
     * opacity. The merged pixels are [upper]'s drawn over [lower]'s where [upper]'s texts paint
     * (one SRC_OVER per pixel: the picture before the merge, as the texts of the two layers share
     * no pixel under 100 %). ONE step "Merge down" that undo restores; false (nothing done) when
     * the texts don't decode or the pixels don't fit in memory.
     */
    private fun mergeTexts(upper: Layer, lower: Layer): Boolean {
        val above = TextCodec.decodeAll(upper.textData)
        val below = TextCodec.decodeAll(lower.textData)
        if (above.isEmpty() || below.isEmpty() || above.size + below.size > TextCodec.MAX_ITEMS) return false
        val data = TextCodec.encodeAll(below + above)
        // Each text's own pixels, not their bounding box: two texts in opposite corners touch two
        // places, not the whole canvas.
        return mergeData(upper, lower, lower.dataSnapshot().copy(text = data), textPixelRects(above))
    }

    /**
     * The objects of a shape or vector layer: its vector content, or the one shape of a legacy shape
     * layer as [LayerConvert.shapeToVector] converts it; null when that shape does not decode.
     */
    private fun objectsOf(layer: Layer): VectorContent? = layer.vector ?: LayerConvert.shapeToVector(layer)

    /**
     * R19: why the objects of the shape or vector layer [upper] can't join those of [lower] (a
     * [LayerLabels19] WHY_* text), or null when they can; [UNREADABLE] when a shape does not decode.
     * v1.8's guard ([VectorLayerOps.mergesAsObjects]) on both kinds: neither layer masked or with
     * copies (a live array), [upper] Normal and not clipping (the lower layer's blend mode and
     * clipping apply to the merged layer, as in v1.8). A layer under 100 % folds its opacity into
     * its objects ([foldsExactly]); [LayerLabels19.WHY_OVERLAP], the last reason, when that would
     * change the picture.
     */
    private fun objectWhy(upper: Layer, lower: Layer): String? {
        val above = objectsOf(upper) ?: return UNREADABLE
        val below = objectsOf(lower) ?: return UNREADABLE
        if (upper.hasMask || upper.maskSpec != null || lower.hasMask || lower.maskSpec != null) return LayerLabels19.WHY_MASK
        if (upper.blendMode != LayerBlendMode.NORMAL) return LayerLabels19.WHY_BLEND
        if (upper.clipping) return LayerLabels19.WHY_CLIP
        if (upper.array != null || lower.array != null) return LayerLabels19.WHY_COPIES
        if (upper.adjustment != null || lower.adjustment != null) return LayerLabels19.WHY_KINDS
        if (!foldsExactly(upper, above) || !foldsExactly(lower, below)) return LayerLabels19.WHY_OVERLAP
        return null
    }

    /**
     * R19 (D26): [layer]'s opacity folds into the opacity of each of its objects [content] without
     * changing the picture: it is 100 %, or no two objects share a pixel (their paint bounds rounded
     * out are pairwise disjoint; an object's opacity applies to it as a whole, its copies too). A
     * sweep over the bounds sorted by their left edge.
     */
    private fun foldsExactly(layer: Layer, content: VectorContent): Boolean {
        if (layer.opacity >= 1f) return true
        val index = ObjectIndex.of(content)
        val rects = content.objects.indices.mapNotNull { i -> pixelRect(index.bounds(i)).takeUnless { it.isEmpty } }.sortedBy { it.left }
        for (i in rects.indices) {
            var j = i + 1
            while (j < rects.size && rects[j].left < rects[i].right) {
                if (Rect.intersects(rects[i], rects[j])) return false
                j++
            }
        }
        return true
    }

    /** [content] with [opacity] folded into each object's own opacity ([content] itself at 100 %). */
    private fun faded(content: VectorContent, opacity: Float): VectorContent {
        if (opacity >= 1f) return content
        val f = opacity.coerceAtLeast(0f)
        return content.copy(
            objects = content.objects.map { o ->
                when (o) {
                    is VStroke -> o.copy(opacity = o.opacity * f)
                    is VPath -> o.copy(opacity = o.opacity * f)
                    is VShape -> o.copy(opacity = o.opacity * f)
                }
            },
        )
    }

    /**
     * R19 [MergeKind.VECTOR_OBJECTS]: the objects of [upper] join those of [lower] on top of them
     * (with new ids, [VectorContent.plus]). A shape layer converts on the way ([LayerConvert]: its
     * one shape, its pixels kept), so [lower] becomes or stays a vector layer and keeps its name.
     * Each layer's opacity is folded into its objects ([faded]) and the merged layer is at 100 %.
     * The merged pixels: [lower]'s scaled by its opacity within its objects' paint bounds (DST_IN),
     * then [upper]'s drawn over them at its opacity within its objects' paint bounds (a shape
     * layer's: the whole layer); exactly the picture before the merge when the folds are exact
     * ([foldsExactly]). Otherwise ([LayerLabels19.KEEP_EDITABLE], [mergeDownEditable]) the merged objects are
     * rendered anew wherever either layer painted. ONE step "Merge down"; false (nothing done) when
     * a shape does not decode or the pixels don't fit in memory.
     */
    private fun mergeObjects(upper: Layer, lower: Layer): Boolean {
        // Renders still on their way land first: the pixels drawn are those of the objects.
        c.vectors.flushPending()
        val above = objectsOf(upper) ?: return false
        val below = objectsOf(lower) ?: return false
        val merged = faded(below, lower.opacity).plus(faded(above, upper.opacity).objects).first
        val after = lower.dataSnapshot().copy(text = null, shape = null, vector = merged)
        val props = lower.props().copy(opacity = 1f)
        if (!foldsExactly(upper, above) || !foldsExactly(lower, below)) {
            // LayerLabels19.KEEP_EDITABLE (WHY_OVERLAP): the folded objects look a little different where they
            // overlap; the pixels are their rendering (I1), redrawn wherever either layer painted.
            val all = Rect(paintArea(lower, below)).apply { union(paintArea(upper, above)) }
            val document = Rect(0, 0, c.doc.width, c.doc.height)
            return mergeData(upper, lower, after, listOf(all), props) { cv, _ ->
                cv.save()
                cv.clipRect(all)
                cv.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                VectorLayerRenderer.render(cv, merged, all, tips = TipCache(8L shl 20), document = document)
                cv.restore()
            }
        }
        val upperParts = disjoint(paintParts(upper, above))
        val lowerArea = if (lower.opacity < 1f) paintArea(lower, below) else Rect()
        return mergeData(upper, lower, after, upperParts + listOf(lowerArea), props) { cv, _ ->
            if (!lowerArea.isEmpty) {
                cv.save()
                cv.clipRect(lowerArea)
                cv.drawColor(Color.argb(alphaOf(lower.opacity), 0, 0, 0), PorterDuff.Mode.DST_IN)
                cv.restore()
            }
            val paint = if (upper.opacity < 1f) Paint().apply { alpha = alphaOf(upper.opacity) } else null
            for (r in upperParts) cv.drawBitmap(upper.bitmap, r, r, paint)
        }
    }

    /** The pixels a shape or vector [layer] (its objects [content]) may paint: its objects' paint bounds rounded out; a legacy shape layer's whole bitmap. */
    private fun paintArea(layer: Layer, content: VectorContent): Rect =
        if (layer.isVectorLayer) pixelRect(ObjectIndex.of(content).unionBounds()) else Rect(0, 0, c.doc.width, c.doc.height)

    /**
     * [paintArea] in pieces: up to [MAX_PARTS] objects, each object's own paint bounds rounded out
     * (objects far apart touch only their own tiles); more objects, or a legacy shape layer, the
     * one [paintArea].
     */
    private fun paintParts(layer: Layer, content: VectorContent): List<Rect> {
        if (!layer.isVectorLayer || content.objects.size > MAX_PARTS) return listOf(paintArea(layer, content))
        val index = ObjectIndex.of(content)
        return (0 until index.size).map { pixelRect(index.bounds(it)) }.filterNot { it.isEmpty }
    }

    /**
     * [rects] within the document as disjoint rects covering the same pixels (a [Region]'s), so a
     * draw over each piece reaches every pixel once.
     */
    private fun disjoint(rects: List<Rect>): List<Rect> {
        val region = Region()
        for (r in rects) {
            val x = Rect(r)
            if (x.intersect(0, 0, c.doc.width, c.doc.height)) region.op(x, Region.Op.UNION)
        }
        val out = ArrayList<Rect>()
        val pieces = RegionIterator(region)
        val r = Rect()
        while (pieces.next(r)) out += Rect(r)
        return out
    }

    /** [opacity] as a paint alpha, rounded as the vector renderer rounds an object's opacity. */
    private fun alphaOf(opacity: Float): Int = (opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /**
     * Stores [after] as [lower]'s data (and [props] as its properties when given) and changes its
     * pixels within [parts] (document px; only the undo tiles they meet are saved) by [draw],
     * given them as disjoint pieces ([disjoint]; by default [upper]'s pixels drawn over each),
     * then removes [upper]: ONE step "Merge down" (as in [VectorLayerOps.mergeVector], a locked or
     * hidden lower layer is merged into as well). False, with nothing recorded, when the pixels
     * don't fit in memory.
     */
    private fun mergeData(
        upper: Layer,
        lower: Layer,
        after: LayerData,
        parts: List<Rect>,
        props: LayerProps? = null,
        draw: (Canvas, List<Rect>) -> Unit = { cv, pieces -> for (r in pieces) cv.drawBitmap(upper.bitmap, r, r, null) },
    ): Boolean {
        val doc = c.doc
        val pieces = disjoint(parts)
        var ok = false
        c.groupUndo(MERGE_LABEL) {
            val before = lower.dataSnapshot()
            val dataAction = LayerDataAction(MERGE_LABEL, lower, before, after)
            if (pieces.isNotEmpty()) {
                // The data changes with the pixels: the edit keeps it (preserveData).
                val rec = c.beginEdit(lower, EditTarget.CONTENT).also { it.preserveData = true }
                try {
                    for (r in pieces) rec.touch(r)
                    draw(Canvas(lower.bitmap), pieces)
                } catch (e: OutOfMemoryError) {
                    rec.abort()
                    return@groupUndo
                }
                lower.restoreData(after)
                if (!c.commitEdit(rec, MERGE_LABEL, listOf(dataAction))) pushData(lower, dataAction)
            } else {
                lower.restoreData(after)
                pushData(lower, dataAction)
            }
            if (props != null) c.applyLayerProps(lower, props, MERGE_LABEL, c::pushUndo)
            val remove = RemoveLayerAction(upper, doc.indexOf(upper), MERGE_LABEL)
            remove.redo(c)
            c.pushUndo(remove)
            c.structural { doc.activeLayerIndex = doc.indexOf(lower) }
            ok = true
        }
        return ok
    }

    /** Records [action] (already applied to [layer]) as a data change: saved, redrawn, reported. */
    private fun pushData(layer: Layer, action: LayerDataAction) {
        layer.markChanged()
        c.pushUndo(action)
        c.notifyLayersChanged()
        c.queueEdit(EditEvent(layer, EditTarget.CONTENT, null, MERGE_LABEL))
    }

    /** [r] rounded out to whole pixels (empty for an empty or NaN [r]). */
    private fun pixelRect(r: RectF): Rect =
        if (!(r.left < r.right && r.top < r.bottom)) Rect() else Rect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())

    /**
     * Why [kindOf] is [MergeKind.RASTER] for two data layers (text, shape or vector), as a
     * [LayerLabels19] WHY_* text: [LayerLabels19.WHY_KINDS] for two kinds, else the first condition
     * of R19 that fails, [LayerLabels19.WHY_OVERLAP] last (so it is the reason only when it is the
     * only one). Null when either layer is raster, an adjustment layer or a folder, when [lower] is
     * not right below [upper], when the type is kept, and when a text or shape does not decode
     * (merged as pixels as in v1.8). Non-null means the layers panel shows the merge dialog (R19)
     * before calling mergeDown.
     */
    fun rasterReason(upper: Layer, lower: Layer): String? {
        if (!isDataLayer(upper) || !isDataLayer(lower) || !adjacent(upper, lower)) return null
        if (kindOf(upper, lower) != MergeKind.RASTER) return null
        val why = when {
            upper.isTextLayer && lower.isTextLayer -> textWhyOf(upper, lower)
            holdsObjects(upper) && holdsObjects(lower) -> objectWhy(upper, lower)
            else -> LayerLabels19.WHY_KINDS
        }
        return why?.takeUnless { it == UNREADABLE }
    }

    /**
     * R19 (A4 step 8): the data "Merge folder" keeps for [folder], or null when the merged layer is
     * pixels as in v1.8. The merged layer's pixels stay the folder's composite
     * (`FolderComposite.renderBlock`), so the type is kept only where that composite is exactly the
     * rendering of the data: the folder holds no folder, and its shown children (a hidden one is
     * left out, as the composite leaves it out) are
     * - all text layers, each Normal and at 100 %, without a mask, clipping or copies, with no
     *   threaded or wrapped text, [TextCodec.MAX_ITEMS] texts in all at most: their texts, bottom
     *   first, in one text layer; or
     * - all shape or vector layers, each Normal, without a mask, clipping or copies, whose opacity
     *   folds exactly ([foldsExactly]): their objects, bottom first, in one vector layer, each
     *   layer's opacity folded into its objects ([faded]).
     * The merged layer carries the folder's name and properties as in v1.8 (its opacity included).
     */
    fun folderData(folder: Layer): LayerData? {
        val doc = c.doc
        val f = doc.indexOf(folder)
        if (f < 0 || !folder.isFolder) return null
        val members = doc.layers.subList(LayerTree.block(doc.layers, f).first, f).toList()
        if (members.any { it.isFolder }) return null
        val shown = members.filter { it.visible }
        if (shown.isEmpty() || shown.any { !keepsInFolder(it) }) return null
        return when {
            shown.all { it.isTextLayer } -> {
                // (Under 100 % the composite holds the texts faded, which a re-render would undo.)
                if (shown.any { it.opacity < 1f }) return null
                val items = shown.flatMap { TextCodec.decodeAll(it.textData).ifEmpty { return null } }
                if (items.size > TextCodec.MAX_ITEMS || items.any { it.threaded || it.wrap.isOn }) return null
                LayerData(text = TextCodec.encodeAll(items))
            }
            shown.all { holdsObjects(it) } -> {
                // Renders still on their way land first: the composite is that of the objects.
                c.vectors.flushPending()
                var merged: VectorContent? = null
                for (layer in shown) {
                    val content = objectsOf(layer) ?: return null
                    if (!foldsExactly(layer, content)) return null
                    val objects = faded(content, layer.opacity)
                    merged = merged?.plus(objects.objects)?.first ?: objects
                }
                LayerData(vector = merged)
            }
            else -> null
        }
    }

    /** The conditions a shown child of a folder meets in [folderData] whatever its kind: Normal, no mask, clipping, copies or adjustment. */
    private fun keepsInFolder(layer: Layer): Boolean =
        !layer.hasMask && layer.maskSpec == null && layer.blendMode == LayerBlendMode.NORMAL && !layer.clipping &&
            layer.array == null && layer.adjustment == null

    /** A layer whose type R19 keeps: text, a legacy shape or vector objects (never a folder or an adjustment layer). */
    private fun isDataLayer(layer: Layer): Boolean =
        !layer.isFolder && !layer.isAdjustmentLayer && (layer.isTextLayer || layer.isShapeLayer || layer.isVectorLayer)

    /**
     * R19 [LayerLabels19.KEEP_EDITABLE] (the merge dialog, when [rasterReason] is [LayerLabels19.WHY_OVERLAP]):
     * [mergeDown], keeping the type although see-through overlapping parts change a little — two
     * text layers append their texts (the lower layer's opacity applies to them all), shape and
     * vector layers fold their opacities into their objects ([mergeObjects]). ONE step "Merge
     * down". For any other pair it is [mergeDown].
     */
    fun mergeDownEditable(layer: Layer) {
        keepEditable = true
        try {
            mergeDown(layer)
        } finally {
            keepEditable = false
        }
    }

    /** True during [mergeDownEditable]. */
    private var keepEditable = false

    /** [kindOf], or during [mergeDownEditable] the type the pair keeps when overlap is the only reason against it. */
    private fun mergeKind(upper: Layer, lower: Layer): MergeKind {
        val kind = kindOf(upper, lower)
        if (!keepEditable || kind != MergeKind.RASTER || rasterReason(upper, lower) != LayerLabels19.WHY_OVERLAP) return kind
        return if (upper.isTextLayer) MergeKind.TEXT_ITEMS else MergeKind.VECTOR_OBJECTS
    }

    private companion object {
        const val MERGE_LABEL = "Merge down"

        /** At most this many objects are drawn piece by piece ([paintParts]); more take their one bounding box. */
        const val MAX_PARTS = 64

        /** [textWhyOf] of a text layer whose data does not decode: pixels, with no reason to show. */
        const val UNREADABLE = ""
    }
}

/**
 * v1.10 (A11, design 2.5 and 2.11): the lower layer's half of a raster "Merge down": its own
 * content and mask stores (held by reference, never copied or materialized) against the merged
 * FLAT store. Each side is taken from the layer when the other is put in, so a store the
 * residency policy parked or unparked meanwhile is the one kept. [dispose] lets go of the tiles
 * of the side the layer does not hold (a FLAT side is left to the collector, as in v1.9).
 */
private class MergedStoresAction(
    private val lower: Layer,
    private var before: PixelStore,
    private var beforeMask: PixelStore?,
    private var after: PixelStore,
    private val dataBefore: LayerData,
    private val dataAfter: LayerData,
    private val propsBefore: LayerProps,
    private val propsAfter: LayerProps,
) : UndoAction {
    override val label: String get() = "Merge down"

    override val byteSize: Long = before.residentBytes() + (beforeMask?.residentBytes() ?: 0L)

    /** True while the layer shows the merged side. */
    private var applied = false

    override fun undo(c: EditorController) = c.structural {
        after = lower.pixels
        lower.pixels = before
        lower.maskPixels = beforeMask
        lower.restoreData(dataBefore)
        lower.copyPropsFrom(propsBefore)
        lower.markChanged()
        applied = false
    }

    override fun redo(c: EditorController) = c.structural {
        before = lower.pixels
        beforeMask = lower.maskPixels
        lower.pixels = after
        lower.maskPixels = null
        lower.restoreData(dataAfter)
        lower.editingMask = false
        lower.copyPropsFrom(propsAfter)
        lower.markChanged()
        applied = true
    }

    override fun dispose() {
        val held = if (applied) listOf(before, beforeMask) else listOf(after)
        for (p in held) if (p is TiledPixels && p !== lower.pixels && p !== lower.maskPixels) TileOps.releaseStore(p)
    }
}
