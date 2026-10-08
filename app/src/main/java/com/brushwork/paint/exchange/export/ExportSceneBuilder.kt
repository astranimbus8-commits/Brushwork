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
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArraySourceBlob
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextExport
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextLineRun
import com.brushwork.paint.tools.text.TextOutlinePart
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.ContentBounds
import com.brushwork.paint.tools.vector.CurveWidths
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
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.util.Base64
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
 *   (A7's [TextExport]; PDF always), their pixels only when some letters have no outlines
 *   (color emoji); layer masks luminance masks;
 * - the payload lists every layer (hidden ones too) with its data and where its pixels are;
 * - v1.7 (item 8): folders are groups of their layers ([SceneLayer.children]), isolated or
 *   not as the canvas composites them; the adjustment and clipping rules apply per level and per
 *   isolated folder ([Planner]).
 * - v1.7 (item 3, I14): a vector array exports every copy's objects, placed by the
 *   `ArrayLayout` matrices (the formats' groups carry no transform here: the geometry is mapped);
 *   a text, shape or raster array exports its cache. The payload writes an arrayed layer as its
 *   cache plus its array (spec and source container), as a project file does.
 * - v1.7 (item 18): a stroke with symmetry copies is one outline of one envelope per copy.
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
        /** v1.7 (item 8): a folder's layers, bottom first ([SceneLayer.children]). */
        val children: List<Plan> = emptyList(),
        /** v1.7 (item 8): false for a folder its children blend through ([SceneLayer.isolated]). */
        val isolated: Boolean = true,
    )

    private sealed class Content {
        class Picture(val image: SceneImage?) : Content()
        class Objects(val objects: List<VObject>) : Content()
        class Items(val items: List<SceneItem>) : Content()
        /** v1.7 (I14): a vector array's source [content], its copies placed by [spec] off the main thread ([arrayObjects]). */
        class Copies(val content: VectorContent, val spec: ArraySpec) : Content()
    }

    private fun key(prefix: String): String = "$prefix-${++keys}"

    suspend fun build(): ExportScene {
        val layers = doc.layers.toList()
        val w = doc.width
        val h = doc.height
        docWidth = w
        docHeight = h
        docMode = doc.colorMode
        // Pixels of each layer as written into the file (the payload points at them).
        val ownImage = HashMap<Layer, SceneImage>()
        val ownMask = HashMap<Layer, SceneImage>()
        // v1.7 (item 8): the tree, level by level, as the compositor draws it.
        val plans = Planner(layers, ownImage, ownMask).context(-1, layers.indices)

        // v1.7 (rule L): a layer is shown when its own eye AND every ancestor folder's are on
        // (read on this snapshot of the list; top-level layers: their own eye, as in v1.6).
        fun shown(index: Int): Boolean {
            val l = layers[index]
            return l.visible && (l.parentId == Layer.ROOT_ID || LayerTree.shownByAncestors(layers, index))
        }
        // A pass-through folder's own blend mode is not used.
        if (options.format == VectorFormat.PDF && layers.indices.any { shown(it) && layers[it].blendMode == LayerBlendMode.ADD && layers[it].folder?.passThrough != true }) {
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
        val sceneLayers = withContext(renderDispatcher) { plans.mapNotNull { sceneLayer(it) } }
        return ExportScene(
            w, h, doc.dpi, doc.name,
            if (options.whiteBackground) 0xFFFFFFFF.toInt() else null,
            sceneLayers, payload, payloadImages, notes.toList(),
        )
    }

    /**
     * [p] as a scene layer with its children (null when it draws nothing: no item, and no child
     * that draws something; an empty folder is left out). Off the main thread.
     */
    private suspend fun sceneLayer(p: Plan): SceneLayer? {
        coroutineContext.ensureActive()
        val items = when (val ct = p.content) {
            is Content.Picture -> listOfNotNull(ct.image?.let { SceneItem.Image(it) })
            is Content.Items -> ct.items
            is Content.Objects -> objectItems(ct.objects)
            is Content.Copies -> objectItems(arrayObjects(ct.content, ct.spec))
        }
        // A grayscale or 1-bit document's colors, as its pixels show them.
        val shown = if (docMode == ColorMode.RGB) items else items.map { constrained(it, docMode) }
        val children = p.children.mapNotNull { sceneLayer(it) }
        if (shown.isEmpty() && children.isEmpty()) return null
        return SceneLayer(p.key, p.name, p.opacity, p.blend, p.hidden, p.mask, shown, children, p.isolated)
    }

    /**
     * v1.7 (item 8, §3.8): what the layer tree becomes, decided the way [FolderComposite] draws it.
     *
     * - A **context** is the top level or a folder composited on its own (pass-through off, below
     *   100 %, a clip base or clipped): an adjustment layer reads the composite below it up to its
     *   context's edge. Everything of a context from its bottom up to its topmost shown adjustment
     *   layer becomes one picture; the layers above it stay separate (the folders drawing their
     *   children as is around that adjustment layer stay groups of what is left of them).
     * - On each level the units (a layer, or a folder with everything in it) form the
     *   compositor's groups: a base with visible units clipping onto it becomes one picture with
     *   the base's opacity and blend mode; an adjustment layer above the merged part draws nothing.
     * - A folder becomes a group of its children: **isolated** with its own blend mode and
     *   opacity (SVG `isolation:isolate`, a PDF transparency group; Normal for a pass-through
     *   clip base, [FolderComposite.drawnBlend]), or, pass-through at 100 %, a plain group its
     *   children blend through. Pass-through below 100 % is a NON-isolated Normal group at its
     *   opacity: PDF draws it exactly (a non-isolated transparency group), SVG writes it isolated
     *   (group opacity always isolates there), an approximation the summary lists. Its layers
     *   still form their own context (an adjustment layer inside merges with the folder's layers
     *   only: an approximation).
     * - A layer's own eye makes it hidden; a hidden folder hides its children with it.
     */
    private inner class Planner(
        private val layers: List<Layer>,
        private val ownImage: MutableMap<Layer, SceneImage>,
        private val ownMask: MutableMap<Layer, SceneImage>,
    ) {
        /** True when the folder at [f] composites its children on their own (see the class docs). */
        fun isolatedForExport(f: Int): Boolean {
            val l = layers[f]
            return l.folder?.passThrough != true || l.opacity < 1f ||
                FolderComposite.isClipped(layers, f) || FolderComposite.isClipBase(layers, f)
        }

        /** The flat index of the context the layer at [index] is in (its nearest isolated folder); -1 = the top level. */
        fun contextOf(index: Int): Int = LayerTree.ancestors(layers, index).firstOrNull { isolatedForExport(it) } ?: -1

        /** The layers of the context [ctx] (a folder's flat index, -1 = the top level) whose inside is [range]. */
        suspend fun context(ctx: Int, range: IntRange): List<Plan> {
            // Shown within the context: its own eye and those of the folders between it and the context.
            fun shownIn(index: Int): Boolean =
                layers[index].visible && LayerTree.ancestors(layers, index).takeWhile { it != ctx }.all { layers[it].visible }
            val top = range.reversed().firstOrNull {
                layers[it].isAdjustmentLayer && layers[it].opacity > 0f && shownIn(it) && contextOf(it) == ctx
            } ?: -1
            val plans = ArrayList<Plan>()
            if (top >= 0) {
                // With the folders it is in (drawn as is), nearest first: a clipping layer at the
                // bottom of one of them stays unclipped, as on the canvas.
                val around = LayerTree.ancestors(layers, top).takeWhile { it != ctx }.map { layers[it] }
                plans += Plan(
                    key("layer"), "${layers[top].name} (merged with the layers below)", 1f, LayerBlendMode.NORMAL, false, null,
                    Content.Picture(compositeImage(layers.subList(range.first, top + 1) + around, Rect(0, 0, docWidth, docHeight), base = null)),
                )
                notes += "Layers below an adjustment layer are exported as one picture"
            }
            plans += level(if (ctx < 0) Layer.ROOT_ID else layers[ctx].id, range, top)
            return plans
        }

        /**
         * The level whose parent is [parentId], [range] = its folder's inside: its units bottom
         * first in the compositor's groups, everything at or below [cut] (the merged part) left out.
         */
        suspend fun level(parentId: Long, range: IntRange, cut: Int): List<Plan> {
            val units = LayerTree.units(layers, range, parentId)
            val plans = ArrayList<Plan>()
            var i = 0
            while (i < units.size) {
                coroutineContext.ensureActive()
                val baseUnit = units[i]
                val base = layers[baseUnit.last]
                var j = i + 1
                if (!base.isAdjustmentLayer) while (j < units.size && clips(layers[units[j].last])) j++
                val clipUnits = units.subList(i + 1, j)
                i = j
                val hi = clipUnits.lastOrNull()?.last ?: baseUnit.last
                if (hi <= cut) continue
                if (cut >= baseUnit.first) {
                    // The merged part ends inside this folder, which draws its children as is.
                    check(clipUnits.isEmpty() && base.isFolder && !isolatedForExport(baseUnit.last)) { "the merged part cuts through $base" }
                    plans += Plan(
                        key("layer"), base.name, 1f, LayerBlendMode.NORMAL, !base.visible, null, Content.Items(emptyList()),
                        level(base.id, inside(baseUnit), cut), isolated = false,
                    )
                    continue
                }
                val hidden = !base.visible
                if (hidden && !options.includeHidden) continue
                if (base.isAdjustmentLayer) continue // above the merged part: hidden or at 0 %, nothing to draw
                val clips = clipUnits.filter { layers[it.last].visible && layers[it.last].opacity > 0f }
                if (clips.isNotEmpty()) {
                    val bounds = unitBounds(baseUnit) ?: continue
                    val members = (listOf(baseUnit) + clips).flatMap { u -> u.map { layers[it] } }
                    plans += Plan(
                        key("layer"), base.name, base.opacity, FolderComposite.drawnBlend(base), hidden, null,
                        Content.Picture(compositeImage(members, bounds, base = base)),
                    )
                    notes += "Clipping groups are exported as pictures"
                    yield()
                    continue
                }
                if (base.isFolder) {
                    plans += folderPlan(baseUnit, hidden)
                    continue
                }
                val mask = layerMask(base)?.also { m -> m.image?.let { ownMask[base] = it } }
                val content = contentOf(base, ownImage)
                plans += Plan(key("layer"), base.name, base.opacity, base.blendMode, hidden, mask, content)
                yield()
            }
            return plans
        }

        /** The folder unit [u] (a lone folder, or a clip base whose clipping units are hidden) as a group. */
        private suspend fun folderPlan(u: IntRange, hidden: Boolean): Plan {
            val f = u.last
            val folder = layers[f]
            val passThrough = folder.folder?.passThrough == true
            val clipBase = FolderComposite.isClipBase(layers, f)
            val isolated = isolatedForExport(f)
            val children = if (isolated) context(f, inside(u)) else level(folder.id, inside(u), -1)
            // Pass-through below 100 % (o·C + (1 − o)·B on the canvas): a NON-isolated group at its
            // opacity, which PDF draws exactly. SVG group opacity always isolates: the SVG writer
            // writes it isolated, an approximation the summary lists (design §9 row 8).
            val lerp = passThrough && !clipBase && folder.opacity < 1f
            if (lerp && options.format == VectorFormat.SVG && folder.opacity > 0f && children.isNotEmpty()) {
                notes += PASS_THROUGH_SVG_NOTE
            }
            // A pass-through folder composited isolated (a clip base) draws Normal, as on the canvas.
            val blend = FolderComposite.drawnBlend(folder)
            return Plan(key("layer"), folder.name, folder.opacity, blend, hidden, null, Content.Items(emptyList()), children, isolated && !lerp)
        }

        /** True when [l] clips onto the unit below it (an adjustment layer never does). */
        private fun clips(l: Layer): Boolean = l.clipping && !l.isAdjustmentLayer

        /** The folder unit [u]'s inside (its block without the folder). */
        private fun inside(u: IntRange): IntRange = u.first until u.last

        /** Where the unit [u] has pixels: a layer's content, a folder's layers' content (null: none). */
        private fun unitBounds(u: IntRange): Rect? {
            var out: Rect? = null
            for (k in u) {
                val l = layers[k]
                if (l.isFolder || l.isAdjustmentLayer) continue
                val r = contentBounds(l) ?: continue
                out = out?.apply { union(r) } ?: Rect(r)
            }
            return out
        }
    }

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
        val array = layer.array
        if (array != null) {
            // v1.7 (I14): a vector array as its copies' objects (as many as the editor lists);
            // a text, shape or raster array as its cache, every copy in it.
            val spec = array.spec
            if (vector != null && vector.objects.isNotEmpty() && spec.count.toLong() * vector.objects.size <= ArraySpec.MAX_INSTANCES) {
                return Content.Copies(vector, spec)
            }
            val cache = layerImage(layer) ?: return Content.Picture(null)
            ownImage[layer] = cache
            return Content.Picture(cache)
        }
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
     * always); null = its pixels: letters without outlines, i.e. color emoji (their glyphs are
     * pictures, so outlines would leave them out; a real `<text>` keeps them).
     */
    private fun textItems(layer: Layer, item: TextItem): List<SceneItem>? {
        val spec = item.spec
        val straight = !item.path.isActive
        val parts = text.parts(item)
        val emoji = ColorGlyphs.has(item.text)
        if (parts != null) {
            if (options.format == VectorFormat.SVG && options.text == TextExportMode.EDITABLE && straight && !spec.vertical && spec.fontId == null) {
                val runs = text.lines(item)
                if (!runs.isNullOrEmpty()) {
                    val box = partItems(parts.filter { it.kind == TextOutlinePart.Kind.BOX_FILL || it.kind == TextOutlinePart.Kind.BOX_BORDER })
                    return box + realText(spec, runs)
                }
            }
            if (emoji) return null
            if (item.text.isNotBlank() && parts.none { it.kind == TextOutlinePart.Kind.TEXT }) return null
            return partItems(parts).ifEmpty { null }
        }
        val frame = if (straight && spec.box.hasFrame) frameItems(item) else emptyList()
        if (straight && spec.box.hasFrame && frame == null) return null
        if (options.format == VectorFormat.SVG && options.text == TextExportMode.EDITABLE && straight && !spec.vertical && spec.fontId == null) {
            val runs = text.lines(item)
            if (!runs.isNullOrEmpty()) return frame.orEmpty() + realText(spec, runs)
        }
        if (emoji) return null
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
     * ([base], a clipping group's base, shown at full opacity and normal blending: the group's
     * own opacity and blend mode go on the exported group). [layers] is a valid tree in flat
     * order: every folder in it has its layers in it, right below it.
     */
    private fun compositeImage(layers: List<Layer>, r: Rect, base: Layer?): SceneImage {
        val rect = Rect(r)
        val w = doc.width
        val h = doc.height
        val dpi = doc.dpi
        val ids = layers.mapTo(HashSet()) { it.id }
        val views = layers.map { l ->
            Layer(l.id, l.name, l.bitmap).also { v ->
                v.copyPropsFrom(l.props())
                v.mask = l.mask
                v.adjustment = l.adjustment
                v.maskSpec = l.maskSpec
                // v1.7 (I11, site 23): the tree as far as it is in the list (a folder outside
                // it: top level), so the temporary document composites folders like the canvas.
                v.folder = l.folder
                v.folderOpen = l.folderOpen
                v.parentId = if (l.parentId in ids) l.parentId else Layer.ROOT_ID
                // A hidden group (exported with "Include hidden layers") still shows its content:
                // the exported group is the one hidden.
                if (l === base) { v.opacity = 1f; v.blendMode = LayerBlendMode.NORMAL; v.clipping = false; v.visible = true }
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

    /**
     * v1.7 (item 3, I14): a vector array's objects as its cache shows them: the source's objects
     * mapped by each [ArrayLayout] matrix (measured from the objects' paint bounds, as
     * `LayerDataTransforms` measures the array), copy N − 1 at the bottom and the source on top;
     * copy k ≥ 1 of object `id` has the id `id + (k shl 53)` (never written). Off the main thread.
     */
    private fun arrayObjects(content: VectorContent, spec: ArraySpec): List<VObject> {
        val ms = ArrayLayout.matrices(spec, ObjectIndex.of(content).unionBounds())
        val out = ArrayList<VObject>(ms.size * content.objects.size)
        for (k in ms.indices.reversed()) {
            for (o in content.objects) out += if (k == 0) o else VectorOps.transformed(o, ms[k]).withId(o.id + (k.toLong() shl 53))
        }
        return out
    }

    /**
     * The outline of [s] for "Outlines": v1.7 (item 18), one envelope per symmetry copy
     * ([StrokeEnvelopeExport.ofCopies]) in ONE path, filled with the non-zero rule, so copies that
     * overlap are covered once, as the one stroke buffer paints them. Null: a picture.
     */
    private fun strokeEnvelope(s: VStroke): VectorPath? {
        if (s.copies.isEmpty()) return StrokeEnvelopeExport.of(s)
        val pieces = StrokeEnvelopeExport.ofCopies(s)
        return if (pieces.isEmpty()) null else VectorPath(pieces.flatMap { it.ops })
    }

    private fun partsOf(o: VObject): List<Part> = when (o) {
        is VStroke -> {
            val env = if (options.strokes == StrokeExport.OUTLINES) strokeEnvelope(o) else null
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

    /**
     * A plain line whose anchors have different widths, exactly as the renderer and the Curve
     * tool fill it ([CurveWidths.line] + [VariableWidthOutline], non-zero contours).
     */
    private fun varyingOutline(p: VPath, st: VStrokeStyle): VectorPath {
        val ops = ArrayList<PathOp>()
        for (s in p.subpaths) {
            val closed = s.closed && s.anchors.size > 2
            val line = CurveWidths.line(VectorOps.curveAnchors(s), closed, p.tension, p.polyline, st.width, CurveWidths.LINE_TOLERANCE) ?: continue
            ops += VariableWidthOutline.build(line.xs, line.ys, line.ws, line.n, closed, CurveWidths.LINE_TOLERANCE).ops
        }
        return if (ops.isEmpty()) VectorPath.EMPTY else VectorPath(ops)
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

    private suspend fun payloadLayer(
        layer: Layer,
        ownImage: Map<Layer, SceneImage>,
        ownMask: Map<Layer, SceneImage>,
        extra: MutableList<SceneImage>,
    ): PayloadLayer {
        val data = layer.dataSnapshot()
        // v1.7 (I14): an arrayed layer is written as its cache (what a v1.6 reader shows: a raster
        // layer with every copy) plus its array, the source living only in the array's container,
        // as in a project file. An array without a source is written as plain pixels.
        val arraySource = ArraySourceBlob.of(data)
        val kind = when {
            layer.isFolder -> PayloadKind.FOLDER
            layer.isAdjustmentLayer -> PayloadKind.ADJUSTMENT
            arraySource != null -> PayloadKind.RASTER
            layer.isVectorLayer -> PayloadKind.VECTOR
            layer.textData != null -> PayloadKind.TEXT
            layer.shapeData != null -> PayloadKind.SHAPE
            else -> PayloadKind.RASTER
        }
        // Pixels: what the file already holds, else a picture for the payload only. Vector
        // layers are rendered again from their objects; adjustment layers have none.
        var image: SceneImage? = null
        // v1.7 (rule P): a folder has no pixels of its own.
        if (kind != PayloadKind.VECTOR && kind != PayloadKind.ADJUSTMENT && kind != PayloadKind.FOLDER) {
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
        val array = data.array
        val payloadArray = if (array != null && arraySource != null) {
            // The source is immutable (shared with undo), so it is encoded off the main thread.
            withContext(renderDispatcher) { payloadArray(array.spec, arraySource) }
        } else {
            null
        }
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
            vector = if (payloadArray != null) null else data.vector,
            textData = if (payloadArray != null) null else data.text,
            shapeData = if (payloadArray != null) null else data.shape,
            maskSpec = data.maskSpec,
            adjustment = data.adjustment,
            // v1.7 (I11): the tree (every layer is written, so the ids resolve on import).
            parentId = layer.parentId,
            folder = layer.folder,
            folderOpen = layer.folderOpen,
            array = payloadArray,
        )
    }

    /** v1.7 (I14): [spec] and [source] as a payload's array: the spec JSON and the base64 of the source container. */
    private fun payloadArray(spec: ArraySpec, source: ArraySourceBlob): PayloadArray {
        val bytes = ByteArrayOutputStream().also { ArrayCodec.writeSource(it, source) }.toByteArray()
        return PayloadArray(ArrayCodec.encodeSpec(spec), Base64.getEncoder().encodeToString(bytes))
    }

    companion object {
        /** Rows of a merged picture composited between two breaks of the main thread. */
        private const val COMPOSITE_BAND = 512

        /**
         * v1.7 (item 8): the SVG summary's line for a pass-through folder below 100 % (PNG, JPEG
         * and PDF draw it exactly).
         */
        const val PASS_THROUGH_SVG_NOTE = "Pass-through folders below 100 % are approximated in SVG (written as isolated groups)"

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

/**
 * Characters Android draws from its color emoji font (pictures, not outlines): text holding them
 * cannot be exported as glyph outlines without losing them.
 */
internal object ColorGlyphs {
    /** True when [text] has a character drawn as a color emoji. */
    fun has(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isEmoji(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isEmoji(cp: Int): Boolean = when {
        // Emoji presentation selector and the keycap mark (❤️, #️⃣), pictographs, emoticons,
        // flags (regional indicators) and the other supplementary emoji blocks.
        cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F000..0x1FAFF -> true
        // BMP characters shown as emoji by default (⚡, ✅, ⌚, ⭐ …).
        android.os.Build.VERSION.SDK_INT >= 28 ->
            android.icu.lang.UCharacter.hasBinaryProperty(cp, android.icu.lang.UProperty.EMOJI_PRESENTATION)
        else -> cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2300..0x23FF
    }
}
