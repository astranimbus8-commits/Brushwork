package com.brushwork.paint.exchange

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.svg.Affine
import com.brushwork.paint.exchange.svg.SvgContent
import com.brushwork.paint.exchange.svg.SvgDocument
import com.brushwork.paint.exchange.svg.SvgImage
import com.brushwork.paint.exchange.svg.SvgItem
import com.brushwork.paint.exchange.svg.SvgText
import com.brushwork.paint.exchange.svg.SvgTextAnchor
import com.brushwork.paint.exchange.svg.SvgToVector
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextAlign
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The open document as an import target (read on the main thread, used off it). */
data class ImportTarget(val width: Int, val height: Int, val dpi: Float, val colorMode: ColorMode, val room: Int, val maxLayers: Int)

/** What an import did, for the summary toast. */
class ImportOutcome(
    val shapes: Int = 0,
    val pictures: Int = 0,
    val texts: Int = 0,
    val pages: Int = 0,
    val layers: Int = 0,
    /** Left out by the file format subset (description -> count). */
    val skipped: Map<String, Int> = emptyMap(),
    /** Left out for lack of layers / memory (description -> count). */
    val dropped: Map<String, Int> = emptyMap(),
)

/**
 * SVG files into an open artwork (v1.5 §4.11a): every path into ONE vector layer "Imported SVG"
 * (or, for a new artwork, one vector layer per top-level group when there are at most
 * min(8, layer limit − 2) of them), the pictures into one "SVG pictures" raster layer under it
 * (in their order), and up to 8 simple texts as text layers above — each layer only if it still
 * fits, in that priority. One undo step; then Transform opens with the imported objects.
 *
 * [prepare] converts and renders off the main thread (pure data in, bitmaps out); [apply] inserts
 * on the main thread.
 */
object VectorImport {
    const val MAX_TEXTS = 8
    const val LABEL = "Import SVG"

    /** Layers prepared by [prepare]: bottom first, texts placed on the main thread. */
    class Prepared(
        val layers: List<NewLayer>,
        /** Index in [layers] of the layer Transform opens on (-1 = none). */
        val transformLayer: Int,
        val texts: List<SvgText>,
        val outcome: ImportOutcome,
        /**
         * The file is over the limits ([SvgToVector.MAX_POINTS] points or [SvgToVector.MAX_VISITS]
         * drawn elements): nothing was prepared; it can come in as a picture of what fits.
         */
        val tooComplex: Boolean = false,
        /** Made for a new artwork (it fills the canvas: no Transform afterwards). */
        val newArtwork: Boolean = false,
    )

    /**
     * Where the file's viewport goes on a [docW] x [docH] canvas: kept as it is when it fits
     * ([viewport] null: content bounds decide), otherwise scaled to 90 % and centred; with [fill]
     * (a new artwork made for it) it covers the whole canvas.
     */
    fun placement(viewport: Pair<Float, Float>?, docW: Int, docH: Int, fill: Boolean): Affine {
        val (w, h) = viewport ?: return Affine.IDENTITY
        if (!(w > 0f) || !(h > 0f)) return Affine.IDENTITY
        if (fill) {
            val s = min(docW / w, docH / h)
            return Affine(s, 0f, 0f, s, (docW - w * s) / 2f, (docH - h * s) / 2f)
        }
        if (w <= docW && h <= docH) return Affine.IDENTITY
        val s = 0.9f * min(docW / w, docH / h)
        return Affine(s, 0f, 0f, s, (docW - w * s) / 2f, (docH - h * s) / 2f)
    }

    /**
     * Converts [svg] for [target]: the placement, the layers' pixels (rendered here). Not on the
     * main thread; [cancelled] is polled while converting. A file over the limits gives a
     * [Prepared.tooComplex] result without layers unless [asPicture] (then what fits is drawn).
     */
    fun prepare(svg: SvgDocument, target: ImportTarget, newArtwork: Boolean, asPicture: Boolean = false, cancelled: () -> Boolean = { false }): Prepared {
        val viewport = SvgToVector.viewportSize(svg, target.dpi)
        var place = placement(viewport, target.width, target.height, newArtwork)
        var content = SvgToVector.convert(svg, target.dpi, place, cancelled = cancelled)
        if (viewport == null) {
            // No size in the file: its drawing decides (kept when it fits, else 90 % and centred).
            val b = content.bounds
            if (b != null && (b.right > target.width || b.bottom > target.height || b.left < 0f || b.top < 0f)) {
                val w = b.width.coerceAtLeast(1f)
                val h = b.height.coerceAtLeast(1f)
                val s = if (newArtwork) min(target.width / w, target.height / h) else min(1f, 0.9f * min(target.width / w, target.height / h))
                place = Affine(s, 0f, 0f, s, (target.width - w * s) / 2f - b.left * s, (target.height - h * s) / 2f - b.top * s)
                content = SvgToVector.convert(svg, target.dpi, place, cancelled = cancelled)
            }
        }
        if (content.truncated && !asPicture) {
            // Not silently half imported: the user decides (as a picture of what fits, or not).
            return Prepared(emptyList(), -1, emptyList(), ImportOutcome(skipped = content.skipped), tooComplex = true)
        }
        return prepare(content, target, newArtwork, asPicture)
    }

    /** [prepare] for already converted [content]. */
    fun prepare(content: SvgContent, target: ImportTarget, newArtwork: Boolean, asPicture: Boolean = false): Prepared {
        var room = target.room
        val dropped = LinkedHashMap<String, Int>()
        val layers = ArrayList<NewLayer>()
        val shapes = content.items.filterIsInstance<SvgItem.Shape>()
        val pictures = content.items.filterIsInstance<SvgItem.Picture>()
        val labels = content.items.filterIsInstance<SvgItem.Label>()
        var transformLayer = -1

        try {
            if (asPicture) {
                if (room <= 0 || (shapes.isEmpty() && pictures.isEmpty())) {
                    if (shapes.isNotEmpty() || pictures.isNotEmpty()) dropped["picture (layer limit)"] = 1
                } else {
                    val bmp = BitmapUtils.createLayerBitmap(target.width, target.height)
                    drawPictures(bmp, content.items, target)
                    layers += NewLayer("Imported SVG (picture)", bmp)
                    transformLayer = layers.lastIndex
                    room--
                }
            } else {
                // Pictures first: they go under the paths.
                if (pictures.isNotEmpty()) {
                    // Reserve the vector layer's slot before the pictures' (priority: vector, pictures, texts).
                    val needVector = if (shapes.isNotEmpty()) 1 else 0
                    if (room - needVector > 0) {
                        val bmp = BitmapUtils.createLayerBitmap(target.width, target.height)
                        drawPictures(bmp, pictures, target)
                        layers += NewLayer("SVG pictures", bmp)
                        room--
                    } else {
                        dropped["pictures (layer limit)"] = pictures.size
                    }
                }
                if (shapes.isNotEmpty()) {
                    val groups = shapes.groupBy { it.group }.toSortedMap()
                    val perGroup = newArtwork && groups.size > 1 && groups.size <= min(8, target.maxLayers - 2) && groups.size <= room
                    val sets: List<Pair<String, List<SvgItem.Shape>>> = if (perGroup) {
                        groups.map { (g, items) -> (content.groups.getOrNull(g) ?: "Imported SVG") to items }
                    } else {
                        listOf("Imported SVG" to shapes)
                    }
                    for ((name, items) in sets) {
                        if (room <= 0) {
                            dropped["shapes (layer limit)"] = (dropped["shapes (layer limit)"] ?: 0) + items.size
                            continue
                        }
                        val objects: List<VObject> = items.map { it.path }
                        val data = VectorContent.EMPTY.plus(objects).first
                        layers += NewLayer(name, renderVector(data, target), data = LayerData(vector = data))
                        if (transformLayer < 0) transformLayer = layers.lastIndex
                        room--
                    }
                }
            }
        } catch (e: Throwable) {
            // Out of memory or stopped: nothing half made stays allocated.
            layers.forEach { it.bitmap.recycle() }
            throw e
        }
        val texts = ArrayList<SvgText>()
        for (l in labels) {
            if (texts.size >= MAX_TEXTS) { dropped["texts (more than $MAX_TEXTS)"] = (dropped["texts (more than $MAX_TEXTS)"] ?: 0) + 1; continue }
            if (room <= 0) { dropped["texts (layer limit)"] = (dropped["texts (layer limit)"] ?: 0) + 1; continue }
            texts += l.text
            room--
        }
        if (asPicture && labels.isEmpty() && layers.isEmpty() && dropped.isEmpty()) dropped["nothing to draw"] = 1
        val outcome = ImportOutcome(
            shapes = if (asPicture) 0 else shapes.size - (dropped["shapes (layer limit)"] ?: 0),
            pictures = pictures.size - (dropped["pictures (layer limit)"] ?: 0),
            texts = texts.size,
            layers = layers.size + texts.size,
            skipped = content.skipped,
            dropped = dropped,
        )
        return Prepared(layers, transformLayer, texts, outcome, newArtwork = newArtwork)
    }

    /** A vector layer's cache: the content rendered over the document (exactly what its re-renders give). */
    fun renderVector(content: VectorContent, target: ImportTarget): Bitmap {
        val bmp = BitmapUtils.createLayerBitmap(target.width, target.height)
        val tips = TipCache(8L shl 20)
        try {
            val doc = Rect(0, 0, target.width, target.height)
            VectorLayerRenderer.render(Canvas(bmp), content, doc, tips = tips, document = doc)
            if (target.colorMode != ColorMode.RGB) ColorModeOps.constrain(bmp, doc, target.colorMode)
        } catch (e: Throwable) {
            bmp.recycle()
            throw e
        } finally {
            tips.clear()
        }
        return bmp
    }

    /** Draws the pictures (and, for a picture import, the paths) of [items] in their order into [bmp]. */
    private fun drawPictures(bmp: Bitmap, items: List<SvgItem>, target: ImportTarget) {
        val canvas = Canvas(bmp)
        val doc = Rect(0, 0, target.width, target.height)
        val tips = TipCache(8L shl 20)
        try {
            var run = ArrayList<VObject>()
            fun flush() {
                if (run.isEmpty()) return
                VectorLayerRenderer.render(canvas, VectorContent.EMPTY.plus(run).first, doc, tips = tips, document = doc)
                run = ArrayList()
            }
            for (item in items) {
                when (item) {
                    is SvgItem.Shape -> run += item.path
                    is SvgItem.Picture -> { flush(); drawImage(canvas, item.image, target) }
                    is SvgItem.Label -> {}
                }
            }
            flush()
        } finally {
            tips.clear()
        }
        if (target.colorMode != ColorMode.RGB) ColorModeOps.constrain(bmp, doc, target.colorMode)
    }

    /** Decodes and draws one `<image>` (sampled down when much larger than where it lands). */
    private fun drawImage(canvas: Canvas, img: SvgImage, target: ImportTarget) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(img.data, 0, img.data.size, bounds)
        val nw = bounds.outWidth
        val nh = bounds.outHeight
        if (nw <= 0 || nh <= 0) return
        val place = img.placement(nw, nh)
        // How large it lands (px), to decode no more than about twice that.
        val shown = max(1f, place.scale * max(nw, nh))
        var sample = 1
        while (max(nw, nh) / (sample * 2) >= shown * 2f && sample < 64) sample *= 2
        // Never decode beyond what fits the canvas several times over.
        while (nw.toLong() * nh / (sample.toLong() * sample) > 4L * target.width * target.height + 4_000_000L && sample < 64) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(img.data, 0, img.data.size, opts) ?: return
        try {
            val m = Matrix()
            val sx = nw.toFloat() / decoded.width
            val sy = nh.toFloat() / decoded.height
            m.setValues(floatArrayOf(place.a * sx, place.c * sy, place.e, place.b * sx, place.d * sy, place.f, 0f, 0f, 1f))
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = (img.opacity * 255f + 0.5f).toInt().coerceIn(0, 255) }
            canvas.drawBitmap(decoded, m, paint)
        } finally {
            decoded.recycle()
        }
    }

    /**
     * Inserts [prepared] (and its texts as text layers) as ONE undo step, removing [replace]
     * in the same step; then the imported objects are selected and Transform opens on them (an
     * import into an open artwork; a new artwork made for the file is already filled by it, so
     * only its first imported layer is selected). Main thread. Returns the outcome, with the
     * texts that could not be laid out dropped.
     */
    fun apply(c: EditorController, prepared: Prepared, replace: List<Layer> = emptyList()): ImportOutcome {
        val layers = ArrayList(prepared.layers)
        val dropped = LinkedHashMap(prepared.outcome.dropped)
        var texts = 0
        for (t in prepared.texts) {
            val nl = try {
                textLayer(c, t)
            } catch (e: OutOfMemoryError) {
                null
            }
            if (nl == null) { dropped["texts (not readable)"] = (dropped["texts (not readable)"] ?: 0) + 1; continue }
            layers += nl
            texts++
        }
        if (layers.isEmpty()) return ImportOutcome(skipped = prepared.outcome.skipped, dropped = dropped)
        val created = ImportLayers.insert(c, layers, LABEL, replace)
        val focus = created.getOrNull(prepared.transformLayer)
        if (focus != null) {
            c.selectLayer(focus)
            if (!prepared.newArtwork) {
                focus.vector?.let { v -> c.vectors.setSelection(focus, v.objects.map { it.id }.toSet()) }
                openTransform(c)
            }
        }
        return ImportOutcome(
            shapes = prepared.outcome.shapes, pictures = prepared.outcome.pictures, texts = texts,
            layers = created.size, skipped = prepared.outcome.skipped, dropped = dropped,
        )
    }

    /** Opens the Transform tool on the active layer (again, when it is already the tool). */
    fun openTransform(c: EditorController) {
        if (c.activeToolId == ToolId.TRANSFORM) {
            c.currentTool.onDeactivate()
            c.currentTool.onActivate()
        } else {
            c.selectTool(ToolId.TRANSFORM)
        }
    }

    /** A text layer for [t]: the first baseline's start (or middle / end) where the SVG puts it. */
    private fun textLayer(c: EditorController, t: SvgText): NewLayer? {
        val text = t.lines.joinToString("\n")
        if (text.isBlank()) return null
        val font = when (t.family) {
            "serif" -> TextFont.SERIF
            "monospace" -> TextFont.MONOSPACE
            "cursive" -> TextFont.CURSIVE
            "casual" -> TextFont.CASUAL
            "condensed" -> TextFont.CONDENSED
            else -> TextFont.SANS
        }
        var spec = TextSpec(
            font = font, bold = t.bold, italic = t.italic,
            sizePx = t.sizePx.coerceIn(TextSpec.MIN_SIZE_PX, TextSpec.MAX_SIZE_PX), color = t.color,
            align = when (t.anchor) { SvgTextAnchor.START -> TextAlign.START; SvgTextAnchor.MIDDLE -> TextAlign.CENTER; SvgTextAnchor.END -> TextAlign.END },
        )
        val paint = TextPaint().apply { typeface = TextRenderer.typeface(spec); textSize = spec.sizePx }
        val fm = paint.fontMetrics
        val natural = (fm.descent - fm.ascent) / spec.sizePx
        if (t.lineHeightEm > 0f && natural > 0f) spec = spec.copy(lineSpacing = (t.lineHeightEm / natural).coerceIn(TextSpec.MIN_LINE_SPACING, TextSpec.MAX_LINE_SPACING))
        val block = TextRenderer.layout(text, spec)
        val w = block.width
        val h = block.height
        val lx = when (t.anchor) {
            SvgTextAnchor.START -> block.inset
            SvgTextAnchor.MIDDLE -> w / 2f
            SvgTextAnchor.END -> w - block.inset
        }
        val ly = block.inset - fm.ascent
        val r = Math.toRadians(t.rotationDeg.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        val dx = lx - w / 2f
        val dy = ly - h / 2f
        val item = TextItem(
            text = text, spec = spec,
            cx = t.x - (dx * cs - dy * sn), cy = t.y - (dx * sn + dy * cs),
            rotationDeg = TextItem.normalizeDegrees(t.rotationDeg),
        ).sanitized()
        val bmp = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        TextRenderer.drawItem(Canvas(bmp), item, TextRenderer.prepare(item), null)
        if (c.doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(bmp, Rect(0, 0, bmp.width, bmp.height), c.doc.colorMode)
        return NewLayer(item.layerName(), bmp, data = LayerData(text = TextCodec.encode(item)))
    }
}
