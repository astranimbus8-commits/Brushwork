package com.brushwork.paint.exchange

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.Document
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.exchange.export.PayloadRect
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.svg.Affine
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps

/**
 * Restores the layers of a Brushwork SVG or PDF from its payload (v1.5 §4.10b, §4.11a): kind,
 * properties, pixels, mask, vector objects, text and shape data, editable mask and adjustment —
 * exactly, when the canvas has the payload's size (a new artwork made for it). On another canvas
 * the layers are placed like an SVG (kept when they fit, else 90 % and centred): pixels and
 * vector objects follow the placement, text and shape layers keep only their pixels then.
 * Vector layers are rendered again from their objects (their pixels are not stored).
 */
object PayloadImport {
    const val LABEL = "Import Brushwork layers"

    /** The pictures a payload refers to (by key). */
    fun interface Images {
        fun load(key: String): ArgbImage?
    }

    class Prepared(val layers: List<NewLayer>, val activeIndex: Int, val outcome: ImportOutcome)

    /** Builds the layers of [p] (bottom first) for [target]. Not on the main thread. */
    fun prepare(p: BrushworkPayload, images: Images, target: ImportTarget): Prepared {
        val place = VectorImport.placement(p.width.toFloat() to p.height.toFloat(), target.width, target.height, fill = false)
        val keep = place == Affine.IDENTITY
        val dropped = LinkedHashMap<String, Int>()
        val out = ArrayList<NewLayer>()
        var missing = 0
        var rasterized = 0
        var damaged = 0
        val fits = fitting(p.layers, target.room)
        if (p.layers.size > fits.size) dropped["layers (layer limit)"] = p.layers.size - fits.size
        for (pl in fits) {
            var bmp: Bitmap? = null
            try {
                var vector: VectorContent? = null
                if (pl.kind == PayloadKind.VECTOR && pl.vector != null) {
                    val sound = sound(pl.vector)
                    damaged += pl.vector.objects.size - sound.objects.size
                    vector = if (keep) sound else transformed(sound, place)
                    // The cache is the rendering itself (no second full-size copy).
                    bmp = VectorImport.renderVector(vector, target)
                } else {
                    bmp = BitmapUtils.createLayerBitmap(target.width, target.height)
                    if (pl.imageRef != null && pl.imageRect != null) {
                        val img = images.load(pl.imageRef)
                        if (img == null) missing++ else draw(bmp, img, pl.imageRect, place, keep)
                        if (target.colorMode != ColorMode.RGB) ColorModeOps.constrain(bmp, Rect(0, 0, bmp.width, bmp.height), target.colorMode)
                    }
                }
                val spec = pl.maskSpec?.let { s -> if (keep) s else MaskSpecs.transformed(s, matrixOf(place)) }
                val mask = when {
                    !pl.hasMask -> null
                    // Placed on another canvas, an editable mask is drawn from its placed spec, so
                    // it stays exactly the spec's rendering (I1) — margins included.
                    !keep && spec != null -> MaskSpecs.newMask(spec, target.width, target.height)
                    else -> mask(pl, images, target, place, keep) { missing++ }
                }
                val dataKept = keep || (pl.textData == null && pl.shapeData == null)
                if (!dataKept) rasterized++
                val data = LayerData(
                    text = if (keep) pl.textData else null,
                    shape = if (keep) pl.shapeData else null,
                    vector = vector,
                    maskSpec = if (mask != null) spec else null,
                    adjustment = pl.adjustment,
                )
                out += NewLayer(pl.props.name, bmp, pl.props, data, mask, sourceId = pl.id)
            } catch (e: Throwable) {
                bmp?.recycle()
                out.forEach { it.bitmap.recycle(); it.mask?.recycle() }
                throw e
            }
        }
        if (missing > 0) dropped["missing pictures"] = missing
        if (damaged > 0) dropped["damaged objects"] = damaged
        if (rasterized > 0) dropped["text and shape layers (kept as pixels: other canvas size)"] = rasterized
        val outcome = ImportOutcome(layers = out.size, dropped = dropped)
        return Prepared(out, p.activeLayer.coerceIn(0, maxOf(0, out.lastIndex)), outcome)
    }

    /**
     * The artwork of [p] as ONE picture for [target] (placed like [prepare]): its layers restored
     * and composited exactly as the editor draws them — masks, blend modes, clipping groups,
     * adjustment layers and texts included. Null when all its layers don't fit in memory next to
     * the picture ([ImportTarget.room] layers). Not on the main thread (only its own layers).
     */
    fun picture(p: BrushworkPayload, images: Images, target: ImportTarget): Bitmap? {
        if (p.layers.isEmpty() || fitting(p.layers, target.room - 1).size < p.layers.size) return null
        val prepared = prepare(p, images, target.copy(room = target.room - 1))
        try {
            val doc = Document("picture", "picture", target.width, target.height, p.dpi)
            doc.colorMode = target.colorMode
            for (n in prepared.layers) {
                doc.layers += Layer(doc.newLayerId(), n.name, n.bitmap).also { l ->
                    n.props?.let { l.copyPropsFrom(it) }
                    l.mask = n.mask
                    l.restoreData(n.data)
                }
            }
            return Compositor(doc) { null }.renderFlattened()
        } finally {
            prepared.layers.forEach { it.bitmap.recycle(); it.mask?.recycle() }
        }
    }

    /**
     * Inserts [prepared] as ONE undo step (removing [replace]; the document taking [colorMode]
     * when given: prepare for a target of that mode) and selects the payload's active layer.
     * Main thread.
     */
    fun apply(c: EditorController, prepared: Prepared, replace: List<Layer> = emptyList(), colorMode: ColorMode? = null): ImportOutcome {
        val created = ImportLayers.insert(c, prepared.layers, LABEL, replace, colorMode)
        created.getOrNull(prepared.activeIndex)?.let { c.selectLayer(it) }
        return ImportOutcome(layers = created.size, dropped = prepared.outcome.dropped)
    }

    /**
     * The bottom layers of [layers] that fit in [room] free layer slots, by the editor's own
     * rules: any layer needs one free slot, an adjustment layer two (`canAddAdjustmentLayer`).
     */
    internal fun fitting(layers: List<PayloadLayer>, room: Int): List<PayloadLayer> {
        var left = room
        val out = ArrayList<PayloadLayer>()
        for (pl in layers) {
            val needed = if (pl.kind == PayloadKind.ADJUSTMENT || pl.adjustment != null) 2 else 1
            if (needed > left) break
            out += pl
            left--
        }
        return out
    }

    /**
     * [content] without objects a damaged or hand-made file could hold that no drawing makes:
     * coordinates that are not finite or lie absurdly far away (the payload's JSON allows NaN).
     * v1.6: Path-tool control points are reduced to usable numbers as a `.vec` file's are when
     * read ([VectorCodec.sanitizedSplines]), so save and reload give the imported document back;
     * v1.7: strokes' symmetry copies likewise ([VectorCodec.sanitizedCopies]).
     */
    internal fun sound(content: VectorContent): VectorContent = VectorCodec.sanitizedSplines(soundObjects(VectorCodec.sanitizedCopies(content)))

    private fun soundObjects(content: VectorContent): VectorContent {
        val ok = content.objects.filter { o ->
            val finite = when (o) {
                is VStroke -> o.points.x.all { it.isFinite() } && o.points.y.all { it.isFinite() } && o.points.p.all { it.isFinite() } && o.sizeScale.isFinite()
                is VPath -> o.subpaths.all { s ->
                    s.anchors.all { a ->
                        a.x.isFinite() && a.y.isFinite() && a.width.isFinite() &&
                            (a.inX ?: 0f).isFinite() && (a.inY ?: 0f).isFinite() && (a.outX ?: 0f).isFinite() && (a.outY ?: 0f).isFinite()
                    }
                }
                is VShape -> true
            }
            if (!finite) return@filter false
            val b = try {
                VectorOps.bounds(o)
            } catch (e: RuntimeException) {
                return@filter false
            }
            b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite() &&
                b.left >= -MAX_COORD && b.top >= -MAX_COORD && b.right <= MAX_COORD && b.bottom <= MAX_COORD
        }
        return if (ok.size == content.objects.size) content else content.copy(objects = ok)
    }

    /** Farthest coordinate a restored object may reach (document px), as SVG import clamps numbers. */
    private const val MAX_COORD = 1e6f

    private fun transformed(content: VectorContent, place: Affine): VectorContent {
        val m = place.toMatrix3()
        return content.copy(objects = content.objects.map { VectorOps.transformed(it, m) })
    }

    private fun matrixOf(a: Affine): Matrix = Matrix().apply { setValues(floatArrayOf(a.a, a.c, a.e, a.b, a.d, a.f, 0f, 0f, 1f)) }

    /** [img] (non-premultiplied) at [r] of [bmp], exactly (setPixels) or through [place]. */
    private fun draw(bmp: Bitmap, img: ArgbImage, r: PayloadRect, place: Affine, keep: Boolean) {
        if (img.width <= 0 || img.height <= 0) return
        if (keep) {
            // Exact: the stored values straight into the layer (cut at the canvas edges).
            val dst = Rect(r.left, r.top, r.left + img.width, r.top + img.height)
            if (!dst.intersect(0, 0, bmp.width, bmp.height)) return
            val off = (dst.top - r.top) * img.width + (dst.left - r.left)
            bmp.setPixels(img.pixels, off, img.width, dst.left, dst.top, dst.width(), dst.height())
            return
        }
        val tmp = Bitmap.createBitmap(img.pixels, 0, img.width, img.width, img.height, Bitmap.Config.ARGB_8888)
        try {
            val m = matrixOf(place)
            m.preTranslate(r.left.toFloat(), r.top.toFloat())
            Canvas(bmp).drawBitmap(tmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
        } finally {
            tmp.recycle()
        }
    }

    private inline fun mask(pl: PayloadLayer, images: Images, target: ImportTarget, place: Affine, keep: Boolean, onMissing: () -> Unit): Bitmap {
        val fill = pl.maskFill or (0xFF shl 24)
        val m = BitmapUtils.createMaskBitmap(target.width, target.height, fill)
        val ref = pl.maskRef
        val rect = pl.maskRect
        if (ref != null && rect != null) {
            val img = images.load(ref)
            if (img == null) onMissing() else draw(m, img, rect, place, keep)
        }
        return m
    }
}
