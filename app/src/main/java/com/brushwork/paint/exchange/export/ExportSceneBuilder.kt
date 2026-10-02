package com.brushwork.paint.exchange.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextExport
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextLineRun
import com.brushwork.paint.tools.text.TextOutlinePart
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.ContentBounds
import com.brushwork.paint.tools.vector.CurveGeometry
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.VariableWidthOutline
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.tools.vector.brushStrokeSamples
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Text layout for export (A7's [TextExport]; a seam so the writers can be tested on their own). */
interface TextSource {
    /** The laid-out lines of horizontal straight text ([TextExport.lines]); the letters only. */
    fun lines(item: TextItem): List<TextLineRun>?

    /** The letters' outlines in document px ([TextExport.outlines]), filled with the text color. */
    fun outlines(doc: Document, layer: Layer): Path?

    /**
     * Every part the text paints, with its color, in painting order: box fill, box border, text
     * outline (stroke), letters ([TextExport.outlineParts]); null = not known (then the box is
     * rebuilt from the layout and the outline stroke drawn as a line along the letters).
     */
    fun parts(item: TextItem): List<TextOutlinePart>? = null

    object Default : TextSource {
        override fun lines(item: TextItem): List<TextLineRun>? = TextExport.lines(item)
        override fun outlines(doc: Document, layer: Layer): Path? = TextExport.outlines(doc, layer)
        override fun parts(item: TextItem): List<TextOutlinePart>? = TextExport.outlineParts(item)
    }
}

/**
 * Turns the open document into an [ExportScene] (v1.5 §4.10):
 *
 * - everything from the bottom up to the topmost visible adjustment layer becomes one picture
 *   (live adjustments have no vector equivalent); the layers above stay separate;
 * - a clipping group becomes one picture (with the base's opacity and blend mode);
 * - raster layers are pictures cropped to their content; vector layers become paths (brush
 *   strokes of solid brushes as outlines, other strokes as pictures, consecutive ones in one
 *   picture, z-order kept); text layers real text (SVG) or outlines of every painted part
 *   (A7's [TextExport]; PDF always), their pixels only when the letters have no outlines; layer
 *   masks luminance masks;
 * - the payload lists every layer (hidden ones too) with its data and where its pixels are.
 *
 * Runs on the main thread (one layer at a time, yielding between layers: it only takes
 * references to immutable data and decides what goes where); converting vector objects runs on
 * [renderDispatcher]. Pictures stay lazy: pixels are copied on [pixelDispatcher] (main) and
 * vector runs rendered on [renderDispatcher] when a writer reaches them.
 */
class ExportSceneBuilder(
    private val c: EditorController,
    private val options: ExportOptions,
    private val text: TextSource = TextSource.Default,
    private val pixelDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val doc: Document get() = c.doc
    private var keys = 0

    // Read on the main thread when the build starts: vector objects are converted on another
    // thread, which must not touch the document (I3).
    private var docWidth = 0
    private var docHeight = 0
    private var docMode = ColorMode.RGB
    private val notes = LinkedHashSet<String>()

    /** What a layer becomes, decided on the main thread; converted to a [SceneLayer] later. */
    private class Plan(
        val key: String,
        val name: String,
        val opacity: Float,
        val blend: LayerBlendMode,
        val hidden: Boolean,
        val mask: SceneMask?,
        val content: Content,
    )

    private sealed class Content {
        class Picture(val image: SceneImage?) : Content()
        class Objects(val objects: List<VObject>) : Content()
        class Items(val items: List<SceneItem>) : Content()
    }

    private fun key(prefix: String): String = "$prefix-${++keys}"

    suspend fun build(): ExportScene {
        val layers = doc.layers.toList()
        val w = doc.width
        val h = doc.height
        docWidth = w
        docHeight = h
        docMode = doc.colorMode
        val plans = ArrayList<Plan>()
        // Pixels of each layer as written into the file (the payload points at them).
        val ownImage = HashMap<Layer, SceneImage>()
        val ownMask = HashMap<Layer, SceneImage>()

        val topAdjustment = layers.indexOfLast { it.isAdjustmentLayer && it.visible && it.opacity > 0f }
        var i = 0
        if (topAdjustment >= 0) {
            val upTo = layers.subList(0, topAdjustment + 1).toList()
            plans += Plan(
                key("layer"), "${layers[topAdjustment].name} (merged with the layers below)", 1f, LayerBlendMode.NORMAL, false, null,
                Content.Picture(compositeImage(upTo, Rect(0, 0, w, h), opaqueBase = false)),
            )
            notes += "Layers below an adjustment layer are exported as one picture"
            i = topAdjustment + 1
        }
        while (i < layers.size) {
            coroutineContext.ensureActive()
            val base = layers[i]
            var j = i + 1
            while (j < layers.size && layers[j].clipping && !layers[j].isAdjustmentLayer) j++
            val clips = layers.subList(i + 1, j).filter { it.visible && it.opacity > 0f }
            i = j
            val hidden = !base.visible
            if (hidden && !options.includeHidden) continue
            if (base.isAdjustmentLayer) continue // hidden adjustment above the merged part: nothing to draw
            if (clips.isNotEmpty()) {
                val bounds = contentBounds(base) ?: continue
                plans += Plan(
                    key("layer"), base.name, base.opacity, base.blend(), hidden, null,
                    Content.Picture(compositeImage(listOf(base) + clips, bounds, opaqueBase = true)),
                )
                notes += "Clipping groups are exported as pictures"
                yield()
                continue
            }
            val mask = layerMask(base)?.also { m -> m.image?.let { ownMask[base] = it } }
            val content = contentOf(base, ownImage)
            plans += Plan(key("layer"), base.name, base.opacity, base.blend(), hidden, mask, content)
            yield()
        }
        if (options.format == VectorFormat.PDF && layers.any { it.visible && it.blendMode == LayerBlendMode.ADD }) {
            notes += "Add (Glow) is exported as Screen in PDF"
        }

        // The payload: every layer, with where its pixels are.
        var payload: BrushworkPayload? = null
        val payloadImages = ArrayList<SceneImage>()
        if (options.includePayload) {
            val entries = ArrayList<PayloadLayer>()
            for (layer in layers) {
                coroutineContext.ensureActive()
                entries += payloadLayer(layer, ownImage, ownMask, payloadImages)
                yield()
            }
            payload = BrushworkPayload(
                width = w, height = h, dpi = doc.dpi, colorMode = doc.colorMode,
                activeLayer = doc.activeLayerIndex.coerceIn(0, max(0, layers.lastIndex)), layers = entries,
            )
        }

        // Vector objects become items off the main thread (immutable data only).
        val sceneLayers = withContext(renderDispatcher) {
            plans.map { p ->
                coroutineContext.ensureActive()
                val items = when (val ct = p.content) {
                    is Content.Picture -> listOfNotNull(ct.image?.let { SceneItem.Image(it) })
                    is Content.Items -> ct.items
                    is Content.Objects -> objectItems(ct.objects)
                }
                // A grayscale or 1-bit document's colors, as its pixels show them.
                val shown = if (docMode == ColorMode.RGB) items else items.map { constrained(it, docMode) }
                SceneLayer(p.key, p.name, p.opacity, p.blend, p.hidden, p.mask, shown)
            }.filter { it.items.isNotEmpty() }
        }
        return ExportScene(
            w, h, doc.dpi, doc.name,
            if (options.whiteBackground) 0xFFFFFFFF.toInt() else null,
            sceneLayers, payload, payloadImages, notes.toList(),
        )
    }

    private fun Layer.blend(): LayerBlendMode = blendMode

    /** [item] with its colors in [mode] (pictures are already constrained pixels). */
    private fun constrained(item: SceneItem, mode: ColorMode): SceneItem {
        fun c(color: Int) = ColorModeOps.constrainPixel(color, mode)
        fun paint(p: VPaint?): VPaint? = when (p) {
            null -> null
            is VPaint.Solid -> VPaint.Solid(c(p.color))
            is VPaint.Linear -> p.copy(stops = p.stops.map { it.copy(color = c(it.color)) })
            is VPaint.Radial -> p.copy(stops = p.stops.map { it.copy(color = c(it.color)) })
        }
        return when (item) {
            is SceneItem.Image -> item
            is SceneItem.Shape -> SceneItem.Shape(item.path, item.evenOdd, paint(item.fill), item.stroke?.let { it.copy(color = c(it.color)) }, item.opacity)
            is SceneItem.Text -> SceneItem.Text(item.lines, item.style.copy(color = c(item.style.color), strokeColor = c(item.style.strokeColor)), item.matrix)
        }
    }

    // ------------------------------------------------------------------ layer content

    private fun contentOf(layer: Layer, ownImage: MutableMap<Layer, SceneImage>): Content {
        val vector = layer.vector
        if (vector != null) return Content.Objects(vector.objects)
        val shape = layer.shapeData?.let { ShapeCodec.decode(it) }
        if (shape != null) return Content.Objects(listOf(VShape(1, shape = shape)))
        val item = layer.textData?.let { TextCodec.decode(it) }
        if (item != null) textItems(layer, item)?.let { return Content.Items(it) }
        val image = layerImage(layer) ?: return Content.Picture(null)
        ownImage[layer] = image
        return Content.Picture(image)
    }

    /**
     * A text layer (§4.10b) as real text — SVG with "Editable", horizontal straight text in a
     * built-in font: its box (background, border) as shapes under one `<text>` — or else as filled
     * outlines, every part the text paints with its own color (box, outline stroke, letters; PDF
     * always); null = its pixels (letters without outlines, e.g. color emoji only).
     */
    private fun textItems(layer: Layer, item: TextItem): List<SceneItem>? {
        val spec = item.spec
        val straight = !item.path.isActive
        val parts = text.parts(item)
        if (parts != null) {
            if (options.format == VectorFormat.SVG && options.text == TextExportMode.EDITABLE && straight && !spec.vertical && spec.fontId == null) {
                val runs = text.lines(item)
                if (!runs.isNullOrEmpty()) {
                    val box = partItems(parts.filter { it.kind == TextOutlinePart.Kind.BOX_FILL || it.kind == TextOutlinePart.Kind.BOX_BORDER })
                    return box + realText(spec, runs)
                }
            }
            if (item.text.isNotBlank() && parts.none { it.kind == TextOutlinePart.Kind.TEXT }) return null
            return partItems(parts).ifEmpty { null }
        }
        val frame = if (straight && spec.box.hasFrame) frameItems(item) else emptyList()
        if (straight && spec.box.hasFrame && frame == null) return null
        if (options.format == VectorFormat.SVG && options.text == TextExportMode.EDITABLE && straight && !spec.vertical && spec.fontId == null) {
            val runs = text.lines(item)
            if (!runs.isNullOrEmpty()) return frame.orEmpty() + realText(spec, runs)
        }
        val outline = text.outlines(doc, layer) ?: return null
        val path = vectorPathOf(outline)
        if (path.isEmpty) return null
        val evenOdd = outline.fillType == Path.FillType.EVEN_ODD
        val items = ArrayList<SceneItem>()
        items += frame.orEmpty()
        if (spec.strokeWidthPx > 0f) {
            items += SceneItem.Shape(path, evenOdd, null, SceneStroke(spec.strokeColor, spec.strokeWidthPx * 2f, join = JoinStyle.ROUND))
        }
        items += SceneItem.Shape(path, evenOdd, VPaint.Solid(spec.color))
        return items
    }

    /** One `<text>` of [runs] (the letters; their outline stroke is drawn behind the fill). */
    private fun realText(spec: TextSpec, runs: List<TextLineRun>): SceneItem.Text {
        val style = SceneTextStyle(
            family = familyOf(spec.font),
            sizePx = spec.sizePx,
            bold = spec.bold,
            italic = spec.italic,
            color = spec.color,
            strokeWidth = if (spec.strokeWidthPx > 0f) spec.strokeWidthPx * 2f else 0f,
            strokeColor = spec.strokeColor,
            letterSpacing = spec.letterSpacing * spec.sizePx,
        )
        return SceneItem.Text(runs.map { SceneTextLine(it.text, it.x, it.baseline) }, style, runs[0].paintSpec.matrix)
    }

    /** Filled outlines (document px, each with its own color), in order; empty outlines left out. */
    private fun partItems(parts: List<TextOutlinePart>): List<SceneItem> = parts.mapNotNull { part ->
        val path = vectorPathOf(part.path)
        if (path.isEmpty) null else SceneItem.Shape(path, part.path.fillType == Path.FillType.EVEN_ODD, VPaint.Solid(part.color))
    }

    /** The background and border of a straight text's box (as TextBlock draws them), null when it can't be laid out. */
    private fun frameItems(item: TextItem): List<SceneItem>? {
        val block = try {
            TextRenderer.layout(item.text, item.spec)
        } catch (e: Exception) {
            return null
        }
        val m = TextRenderer.matrix(item, block)
        val v = FloatArray(9).also { m.getValues(it) }
        fun map(p: Vec2) = Vec2(v[0] * p.x + v[1] * p.y + v[2], v[3] * p.x + v[4] * p.y + v[5])
        val box = item.spec.box
        val w = block.width
        val h = block.height
        val r = box.roundness.coerceIn(0f, 1f) * min(w, h) / 2f
        val out = ArrayList<SceneItem>()
        if (box.fill) out += SceneItem.Shape(roundRect(0f, 0f, w, h, r).transformed(::map), fill = VPaint.Solid(box.fillColor))
        if (box.borderWidth > 0f) {
            val half = min(box.borderWidth, min(w, h)) / 2f
            val ri = max(0f, r - half)
            out += SceneItem.Shape(
                roundRect(half, half, w - half, h - half, ri).transformed(::map),
                stroke = SceneStroke(box.borderColor, half * 2f, join = JoinStyle.MITER, miter = 10f),
            )
        }
        return out
    }

    private fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float): VectorPath {
        if (rad <= 0f) return VectorPath.polygon(listOf(Vec2(l, t), Vec2(r, t), Vec2(r, b), Vec2(l, b)))
        val k = 0.5522848f * rad
        return VectorPath(listOf(
            PathOp.MoveTo(Vec2(l + rad, t)), PathOp.LineTo(Vec2(r - rad, t)),
            PathOp.CubicTo(Vec2(r - rad + k, t), Vec2(r, t + rad - k), Vec2(r, t + rad)), PathOp.LineTo(Vec2(r, b - rad)),
            PathOp.CubicTo(Vec2(r, b - rad + k), Vec2(r - rad + k, b), Vec2(r - rad, b)), PathOp.LineTo(Vec2(l + rad, b)),
            PathOp.CubicTo(Vec2(l + rad - k, b), Vec2(l, b - rad + k), Vec2(l, b - rad)), PathOp.LineTo(Vec2(l, t + rad)),
            PathOp.CubicTo(Vec2(l, t + rad - k), Vec2(l + rad - k, t), Vec2(l + rad, t)), PathOp.Close,
        ))
    }

    private fun familyOf(f: TextFont): String = when (f) {
        TextFont.SANS -> "sans-serif"
        TextFont.SERIF -> "serif"
        TextFont.MONOSPACE -> "monospace"
        TextFont.CONDENSED -> "Roboto Condensed, Arial Narrow, sans-serif-condensed, sans-serif"
        TextFont.CASUAL -> "Comic Sans MS, casual, cursive"
        TextFont.CURSIVE -> "Dancing Script, cursive"
    }

    // ------------------------------------------------------------------ pictures

    /** [layer]'s pixels cropped to their content (null when empty). */
    private fun layerImage(layer: Layer, prefix: String = "img"): SceneImage? {
        val r = contentBounds(layer) ?: return null
        return pixelImage(key(prefix), layer.bitmap, r, gray = false)
    }

    private fun contentBounds(layer: Layer): Rect? = ContentBounds.of(layer.bitmap)

    /** A picture of [bitmap] within [r], copied when a writer reaches it. */
    private fun pixelImage(key: String, bitmap: Bitmap, r: Rect, gray: Boolean): SceneImage {
        val rect = Rect(r)
        return SceneImage(key, rect.left, rect.top, rect.width(), rect.height(), gray) {
            withContext(pixelDispatcher) {
                val px = IntArray(rect.width() * rect.height())
                bitmap.getPixels(px, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
                ArgbImage(rect.width(), rect.height(), px)
            }
        }
    }

    /** [layer]'s enabled mask (null without one): the gray around its content, and its content. */
    private fun layerMask(layer: Layer): SceneMask? {
        val m = layer.mask ?: return null
        if (!layer.maskEnabled) return null
        return maskOf(m)
    }

    private fun maskOf(m: Bitmap): SceneMask {
        val bg = maskBackground(m)
        val r = ContentBounds.of(m, emptyColor = bg)
        val gray = luma(bg)
        return SceneMask(key("mask"), gray, r?.let { pixelImage(key("mask"), m, it, gray = true) })
    }

    /** The most common of a mask's four corner colors (its background). */
    private fun maskBackground(m: Bitmap): Int {
        val w = m.width - 1
        val h = m.height - 1
        return ContentBounds.majority(intArrayOf(m.getPixel(0, 0), m.getPixel(w, 0), m.getPixel(0, h), m.getPixel(w, h)))
    }

    private fun luma(c: Int): Int = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114 + 500) / 1000

    /**
     * The composite of [layers] within [r] as a picture: a temporary document with views of them
     * (the first one at full opacity and normal blending when [opaqueBase]: a clipping group's
     * own opacity and blend mode go on the exported group).
     */
    private fun compositeImage(layers: List<Layer>, r: Rect, opaqueBase: Boolean): SceneImage {
        val rect = Rect(r)
        val w = doc.width
        val h = doc.height
        val dpi = doc.dpi
        val views = layers.mapIndexed { idx, l ->
            Layer(l.id, l.name, l.bitmap).also { v ->
                v.copyPropsFrom(l.props())
                v.mask = l.mask
                v.adjustment = l.adjustment
                v.maskSpec = l.maskSpec
                // A hidden group (exported with "Include hidden layers") still shows its content:
                // the exported group is the one hidden.
                if (opaqueBase && idx == 0) { v.opacity = 1f; v.blendMode = LayerBlendMode.NORMAL; v.clipping = false; v.visible = true }
            }
        }
        return SceneImage(key("img"), rect.left, rect.top, rect.width(), rect.height(), false) {
            withContext(pixelDispatcher) {
                val tmp = Document("export", "export", w, h, dpi)
                tmp.layers += views
                val rw = rect.width()
                val rh = rect.height()
                // The pixels go straight into the picture's array, band by band: memory is the
                // picture plus one band, not a whole second copy of it (4000 x 5000: 80 MB less).
                val px = IntArray(rw * rh)
                val bandH = minOf(COMPOSITE_BAND, rh)
                val band = BitmapUtils.createLayerBitmap(rw, bandH)
                try {
                    val canvas = Canvas(band)
                    val compositor = Compositor(tmp) { null }
                    // In bands, letting the main thread breathe between them (big canvases).
                    var top = rect.top
                    while (top < rect.bottom) {
                        coroutineContext.ensureActive()
                        val r = Rect(rect.left, top, rect.right, minOf(rect.bottom, top + bandH))
                        band.eraseColor(0)
                        canvas.save()
                        canvas.translate(-rect.left.toFloat(), -top.toFloat())
                        canvas.clipRect(r)
                        compositor.drawDocument(canvas, r, useOverrides = false, target = CompositeTarget.translate(band, rect.left, top))
                        canvas.restore()
                        band.getPixels(px, (top - rect.top) * rw, rw, 0, 0, rw, r.height())
                        top = r.bottom
                        if (top < rect.bottom) yield()
                    }
                    ArgbImage(rw, rh, px)
                } finally {
                    band.recycle()
                }
            }
        }
    }

    // ------------------------------------------------------------------ vector objects

    /** Items of a vector layer's objects (z-order kept; consecutive picture parts share one picture). */
    private suspend fun objectItems(objects: List<VObject>): List<SceneItem> {
        val out = ArrayList<SceneItem>()
        val run = ArrayList<VObject>()
        fun flush() {
            if (run.isEmpty()) return
            pictureOf(run.toList())?.let { out += SceneItem.Image(it) }
            run.clear()
        }
        for (o in objects) {
            coroutineContext.ensureActive()
            for (part in partsOf(o)) {
                when (part) {
                    is Part.Item -> { flush(); out += part.item }
                    is Part.Picture -> run += part.obj
                }
            }
        }
        flush()
        return out
    }

    private sealed class Part {
        class Item(val item: SceneItem) : Part()
        class Picture(val obj: VObject) : Part()
    }

    private fun partsOf(o: VObject): List<Part> = when (o) {
        is VStroke -> {
            val env = if (options.strokes == StrokeExport.OUTLINES) StrokeEnvelopeExport.of(o) else null
            if (env != null) listOf(Part.Item(SceneItem.Shape(env, fill = VPaint.Solid(StrokeEnvelopeExport.fillColor(o.color, o.preset, o.opacity)))))
            else listOf(Part.Picture(o))
        }
        is VPath -> pathParts(o)
        is VShape -> VectorOps.toPaths(o).flatMap { pathParts(it) }
    }

    /** A path's fill and plain line as items, its brush line as an outline or a picture. */
    private fun pathParts(p: VPath): List<Part> {
        val out = ArrayList<Part>()
        val geometry = VectorOps.toVectorPath(p)
        if (geometry.isEmpty && p.subpaths.none { it.anchors.size == 1 }) return out
        val st = p.stroke
        val uniform = p.subpaths.all { s -> s.anchors.all { it.width == 1f } }
        val plainStroke = st?.takeIf { it.kind == VStrokeKind.PLAIN && it.width > 0f && it.width.isFinite() }
        val opacity = p.opacity
        if (p.fill != null || (plainStroke != null && uniform)) {
            val stroke = if (plainStroke != null && uniform) SceneStroke(plainStroke.color, plainStroke.width, plainStroke.cap, plainStroke.join, max(1f, plainStroke.miter)) else null
            out += Part.Item(SceneItem.Shape(geometry, p.fillRule == com.brushwork.paint.vector.VFillRule.EVENODD, p.fill, stroke, opacity))
        }
        if (plainStroke != null && !uniform) {
            val outline = varyingOutline(p, plainStroke)
            if (!outline.isEmpty) out += Part.Item(SceneItem.Shape(outline, fill = VPaint.Solid(plainStroke.color), opacity = opacity))
        }
        if (st != null && st.kind == VStrokeKind.BRUSH) {
            val env = if (options.strokes == StrokeExport.OUTLINES && uniform) brushEnvelope(p, st) else null
            if (env != null) out += Part.Item(SceneItem.Shape(env, fill = VPaint.Solid(StrokeEnvelopeExport.fillColor(st.color, VectorOps.brushOf(st), opacity))))
            else out += Part.Picture(p.copy(fill = null))
        }
        return out
    }

    /** The outline a solid brush paints along [p] (as the renderer replays it), null when not solid. */
    private fun brushEnvelope(p: VPath, st: VStrokeStyle): VectorPath? {
        val brush = VectorOps.brushOf(st)
        if (!StrokeEnvelopeExport.isSolid(brush)) return null
        val taper = (st.taperPercent / 100f).let { if (it.isFinite()) it.coerceIn(0f, 0.5f) else 0f }
        val ops = ArrayList<PathOp>()
        val input = com.brushwork.paint.brush.PathStrokeInput(512)
        p.subpaths.forEachIndexed { index, s ->
            if (s.anchors.size < 2) return@forEachIndexed
            val single = VPath(p.id, subpaths = listOf(s), tension = p.tension, polyline = p.polyline)
            val samples = brushStrokeSamples(VectorOps.toVectorPath(single), taper, null, input)
            val n = samples.size
            if (n < 2) return@forEachIndexed
            val xs = FloatArray(n + 1); val ys = FloatArray(n + 1); val ps = FloatArray(n + 1)
            samples.x.copyInto(xs, 0, 0, n); samples.y.copyInto(ys, 0, 0, n); samples.pressure.copyInto(ps, 0, 0, n)
            xs[n] = xs[n - 1]; ys[n] = ys[n - 1]; ps[n] = ps[n - 1]
            val env = StrokeEnvelopeExport.outline(brush, true, st.seed + index, PackedPoints(xs, ys, ps)) ?: return null
            ops += env.ops
        }
        return if (ops.isEmpty()) null else VectorPath(ops)
    }

    /** A plain line whose anchors have different widths, as the renderer fills it. */
    private fun varyingOutline(p: VPath, st: VStrokeStyle): VectorPath {
        val ops = ArrayList<PathOp>()
        for (s in p.subpaths) {
            val line = widthLine(s, p.tension, p.polyline, st.width) ?: continue
            val o = VariableWidthOutline.build(line.first, line.second, line.third, line.first.size, s.closed && s.anchors.size > 2, 0.25f)
            ops += o.ops
        }
        return if (ops.isEmpty()) VectorPath.EMPTY else VectorPath(ops)
    }

    /** A sub-path flattened with the full line width at each point (smoothstep between anchors along arc length, §4.5). */
    private fun widthLine(s: VSubpath, tension: Float, polyline: Boolean, width: Float): Triple<FloatArray, FloatArray, FloatArray>? {
        val anchors = VectorOps.curveAnchors(s)
        val n = anchors.size
        if (n == 0) return null
        fun w(i: Int) = anchors[i % n].width.let { if (it.isFinite()) it.coerceAtLeast(0f) else 1f } * width
        if (n == 1) return Triple(floatArrayOf(anchors[0].x), floatArrayOf(anchors[0].y), floatArrayOf(w(0)))
        val closed = s.closed && n > 2
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ws = ArrayList<Float>()
        xs += anchors[0].x; ys += anchors[0].y; ws += w(0)
        val pts = ArrayList<Vec2>()
        for (seg in 0 until CurveGeometry.segmentCount(n, closed)) {
            val (p0, c1, c2, p1) = CurveGeometry.segment(anchors, seg, closed, tension, polyline)
            pts.clear()
            pts += p0
            VectorPath.flattenCubic(p0, c1, c2, p1, 0.25f, pts)
            var total = 0f
            for (k in 1 until pts.size) total += pts[k - 1].distanceTo(pts[k])
            var acc = 0f
            val wa = w(seg); val wb = w(seg + 1)
            for (k in 1 until pts.size) {
                val a = pts[k - 1]; val b = pts[k]
                val len = a.distanceTo(b)
                val pieces = if (wa == wb) 1 else ceil(len / 2f).toInt().coerceIn(1, 1024)
                for (q in 1..pieces) {
                    val f = q.toFloat() / pieces
                    val t = if (total > 0f) ((acc + len * f) / total).coerceIn(0f, 1f) else 1f
                    val e = t * t * (3f - 2f * t)
                    val pt = if (q == pieces) b else a.lerp(b, f)
                    xs += pt.x; ys += pt.y; ws += wa + (wb - wa) * e
                }
                acc += len
            }
        }
        return Triple(xs.toFloatArray(), ys.toFloatArray(), ws.toFloatArray())
    }

    /** A picture of [objects] rendered like the layer's cache, over their bounds within the document. */
    private fun pictureOf(objects: List<VObject>): SceneImage? {
        val b = RectF()
        for (o in objects) {
            val ob = VectorOps.bounds(o)
            if (!ob.isEmpty) b.union(ob)
        }
        if (b.isEmpty) return null
        val r = Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
        val docRect = Rect(0, 0, docWidth, docHeight)
        if (!r.intersect(docRect)) return null
        val content = VectorContent(objects = objects)
        val mode = docMode
        return SceneImage(key("img"), r.left, r.top, r.width(), r.height(), false) {
            withContext(renderDispatcher) { renderVector(content, r, docRect, mode) }
        }
    }

    // ------------------------------------------------------------------ payload

    private fun payloadLayer(
        layer: Layer,
        ownImage: Map<Layer, SceneImage>,
        ownMask: Map<Layer, SceneImage>,
        extra: MutableList<SceneImage>,
    ): PayloadLayer {
        val kind = when {
            layer.isAdjustmentLayer -> PayloadKind.ADJUSTMENT
            layer.isVectorLayer -> PayloadKind.VECTOR
            layer.textData != null -> PayloadKind.TEXT
            layer.shapeData != null -> PayloadKind.SHAPE
            else -> PayloadKind.RASTER
        }
        // Pixels: what the file already holds, else a picture for the payload only. Vector
        // layers are rendered again from their objects; adjustment layers have none.
        var image: SceneImage? = null
        if (kind != PayloadKind.VECTOR && kind != PayloadKind.ADJUSTMENT) {
            image = ownImage[layer] ?: layerImage(layer, "pimg")?.also { extra += it }
        }
        var maskImage: SceneImage? = null
        var maskFill = -1
        val m = layer.mask
        if (m != null) {
            val bg = maskBackground(m)
            maskFill = (0xFF shl 24) or (luma(bg) * 0x010101)
            maskImage = ownMask[layer] ?: ContentBounds.of(m, emptyColor = bg)?.let { r -> pixelImage(key("pmask"), m, r, gray = true).also { extra += it } }
        }
        val data = layer.dataSnapshot()
        return PayloadLayer(
            id = layer.id,
            props = layer.props(),
            kind = kind,
            imageRef = image?.key,
            imageRect = image?.let { PayloadRect(it.left, it.top, it.width, it.height) },
            hasMask = m != null,
            maskRef = maskImage?.key,
            maskRect = maskImage?.let { PayloadRect(it.left, it.top, it.width, it.height) },
            maskFill = maskFill,
            vector = data.vector,
            textData = data.text,
            shapeData = data.shape,
            maskSpec = data.maskSpec,
            adjustment = data.adjustment,
        )
    }

    companion object {
        /** Rows of a merged picture composited between two breaks of the main thread. */
        private const val COMPOSITE_BAND = 512

        /**
         * [content] rendered over [r] (document px) like a vector layer's cache: dabs cut at
         * [docRect], the document's color mode applied. Any thread.
         */
        fun renderVector(content: VectorContent, r: Rect, docRect: Rect, mode: ColorMode): ArgbImage {
            val bmp = BitmapUtils.createLayerBitmap(r.width(), r.height())
            val tips = TipCache(8L shl 20)
            try {
                val canvas = Canvas(bmp)
                canvas.translate(-r.left.toFloat(), -r.top.toFloat())
                VectorLayerRenderer.render(canvas, content, r, tips = tips, document = docRect)
                if (mode != ColorMode.RGB) ColorModeOps.constrain(bmp, Rect(0, 0, r.width(), r.height()), mode)
                val px = IntArray(r.width() * r.height())
                bmp.getPixels(px, 0, r.width(), 0, 0, r.width(), r.height())
                return ArgbImage(r.width(), r.height(), px)
            } finally {
                bmp.recycle()
                tips.clear()
            }
        }

        /** An android [Path] as straight segments within 0.25 px (every contour closed: glyph outlines). */
        fun vectorPathOf(path: Path, tolerance: Float = 0.25f): VectorPath {
            val a = path.approximate(tolerance)
            if (a.size < 6) return VectorPath.EMPTY
            val ops = ArrayList<PathOp>()
            var open = false
            var lastFraction = -1f
            var i = 0
            while (i + 2 < a.size) {
                val f = a[i]; val x = a[i + 1]; val y = a[i + 2]
                if (!open || f == lastFraction) {
                    if (open) ops += PathOp.Close
                    ops += PathOp.MoveTo(Vec2(x, y))
                    open = true
                } else {
                    ops += PathOp.LineTo(Vec2(x, y))
                }
                lastFraction = f
                i += 3
            }
            if (open) ops += PathOp.Close
            return VectorPath(ops)
        }
    }
}
