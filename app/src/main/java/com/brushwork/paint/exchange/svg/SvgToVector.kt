package com.brushwork.paint.exchange.svg

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.vector.Bounds
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VectorOps
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where text is anchored on its x. */
enum class SvgTextAnchor { START, MIDDLE, END }

/** A picture of an SVG file (`<image>`), placed in document px by [placement]. */
class SvgImage(
    /** PNG / JPEG / WebP bytes. */
    val data: ByteArray,
    val mime: String,
    private val x: Float,
    private val y: Float,
    /** Viewport size in user units (0 = the picture's own size). */
    private val w: Float,
    private val h: Float,
    private val par: String?,
    private val ctm: Affine,
    val opacity: Float,
) {
    /** Picture px -> document px for a picture of [nw] x [nh] px. */
    fun placement(nw: Int, nh: Int): Affine {
        val vw = if (w > 0f) w else nw.toFloat()
        val vh = if (h > 0f) h else nh.toFloat()
        val fit = SvgUnits.viewBoxTransform("0 0 $nw $nh", par, x, y, vw, vh) ?: Affine.translate(x, y)
        return ctm * fit
    }
}

/** A `<text>`: [lines] anchored with the first baseline at ([x], [y]) (document px). */
class SvgText(
    val lines: List<String>,
    val x: Float,
    val y: Float,
    val rotationDeg: Float,
    val sizePx: Float,
    /** Generic family: serif, sans-serif, monospace, cursive, casual, condensed. */
    val family: String,
    val bold: Boolean,
    val italic: Boolean,
    val color: Int,
    val anchor: SvgTextAnchor,
    /** Distance between baselines / font size (0 = single line). */
    val lineHeightEm: Float,
)

/** Something an SVG file draws, in document order; [group] = index into [SvgContent.groups]. */
sealed class SvgItem {
    abstract val group: Int

    class Shape(val path: VPath, override val group: Int) : SvgItem()
    class Picture(val image: SvgImage, override val group: Int) : SvgItem()
    class Label(val text: SvgText, override val group: Int) : SvgItem()
}

/** The converted content of an SVG file. */
class SvgContent(
    val items: List<SvgItem>,
    /** Names of the top-level groups (Illustrator / Inkscape layers). */
    val groups: List<String>,
    /** Unsupported features left out (description -> count). */
    val skipped: Map<String, Int>,
    /** The point or element limit was reached: the rest of the file is missing. */
    val truncated: Boolean,
    /** Bounds of everything drawn (document px), null when nothing is. */
    val bounds: Bounds?,
) {
    val shapeCount: Int get() = items.count { it is SvgItem.Shape }
    val pictureCount: Int get() = items.count { it is SvgItem.Picture }
    val textCount: Int get() = items.count { it is SvgItem.Label }
}

/**
 * Turns an [SvgDocument] into vector objects (v1.5 §4.11b): every drawn element becomes one
 * [VPath] with as many sub-paths as it needs (cubic handles become explicit handles of sharp
 * anchors, so the geometry is exact), transforms are baked in (stroke widths × sqrt|det|),
 * styles follow attribute < CSS < `style=""` with inheritance, group opacity is multiplied into
 * the children (an approximation), gradients become [VPaint]s. Pictures and text are returned
 * for the importer to place. Clip paths, masks, filters, patterns, markers and dash arrays are
 * left out and counted. Pure Kotlin.
 */
object SvgToVector {
    const val MAX_POINTS = 2_000_000

    /**
     * Elements drawn at most, `<use>` copies included: references can multiply a small file
     * (50 uses of 50 uses of ... 8 deep) far beyond its element count, also with nothing to draw
     * (no points ever reach [MAX_POINTS]). Over it the content is [SvgContent.truncated].
     */
    const val MAX_VISITS = 200_000
    private const val MAX_USE_DEPTH = 8

    /** Properties set by presentation attributes. */
    private val PROPS = listOf(
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin",
        "stroke-miterlimit", "stroke-dasharray", "opacity", "display", "visibility", "color", "font-family", "font-size",
        "font-weight", "font-style", "text-anchor", "stop-color", "stop-opacity", "clip-path", "mask", "filter",
        "marker-start", "marker-mid", "marker-end", "marker",
    )

    private val INHERITED = setOf(
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin",
        "stroke-miterlimit", "stroke-dasharray", "visibility", "color", "font-family", "font-size", "font-weight", "font-style",
        "text-anchor", "marker-start", "marker-mid", "marker-end",
    )

    /**
     * The size of the file's viewport in document px at [dpi] (width / height; px stay px,
     * physical units at [dpi]; without them the viewBox size); null when the file gives none.
     */
    fun viewportSize(doc: SvgDocument, dpi: Float): Pair<Float, Float>? {
        val r = doc.root
        val vb = SvgUnits.numbers(r.attr("viewBox"))
        val vbw = vb.getOrNull(2)?.takeIf { it > 0f }
        val vbh = vb.getOrNull(3)?.takeIf { it > 0f }
        var w = SvgUnits.rootLength(r.attr("width"), dpi)
        var h = SvgUnits.rootLength(r.attr("height"), dpi)
        if (w == null && h == null) { w = vbw; h = vbh }
        if (w == null && h != null && vbw != null && vbh != null) w = h * vbw / vbh
        if (h == null && w != null && vbw != null && vbh != null) h = w * vbh / vbw
        if (w == null || h == null) return null
        return w to h
    }

    /** The physical size of the file in inches (CSS px at 96 per inch), null when unknown. */
    fun physicalInches(doc: SvgDocument): Pair<Float, Float>? {
        val r = doc.root
        val vb = SvgUnits.numbers(r.attr("viewBox"))
        val vbw = vb.getOrNull(2)?.takeIf { it > 0f }
        val vbh = vb.getOrNull(3)?.takeIf { it > 0f }
        var w = SvgUnits.inches(r.attr("width"))
        var h = SvgUnits.inches(r.attr("height"))
        if (w == null && h == null) { w = vbw?.div(SvgUnits.CSS_DPI); h = vbh?.div(SvgUnits.CSS_DPI) }
        if (w == null && h != null && vbw != null && vbh != null) w = h * vbw / vbh
        if (h == null && w != null && vbw != null && vbh != null) h = w * vbh / vbw
        if (w == null || h == null) return null
        return w to h
    }

    /**
     * Converts [doc]: its viewport (0, 0, [viewportW], [viewportH]) in document px at [dpi] (see
     * [viewportSize]; 0 = unknown: user units become px) is mapped by [place] onto the document.
     * [cancelled] is polled now and then; when it answers true a [CancellationException] is thrown.
     */
    fun convert(
        doc: SvgDocument,
        dpi: Float,
        place: Affine = Affine.IDENTITY,
        maxPoints: Int = MAX_POINTS,
        maxVisits: Int = MAX_VISITS,
        cancelled: () -> Boolean = { false },
    ): SvgContent {
        val size = viewportSize(doc, dpi)
        val root = doc.root
        val rootMap = size?.let { (w, h) -> SvgUnits.viewBoxTransform(root.attr("viewBox"), root.attr("preserveAspectRatio"), 0f, 0f, w, h) }
            ?: Affine.IDENTITY
        val c = Converter(doc, maxPoints, size ?: (0f to 0f), maxVisits, cancelled)
        val rootStyle = c.style(root, null)
        c.walkRoot(root, place * rootMap, rootStyle)
        return SvgContent(c.items, c.groupNames, c.skipped, doc.truncated || c.truncated, c.bounds)
    }

    /** One element's resolved style: its own values and its parent's for inherited properties. */
    private class Style(val own: Map<String, String>, val parent: Style?) {
        fun get(p: String): String? {
            val v = own[p]
            if (v != null) return if (v == "inherit") parent?.get(p) else v
            return if (p in INHERITED) parent?.get(p) else null
        }
    }

    private class Converter(
        val doc: SvgDocument,
        val maxPoints: Int,
        val viewport: Pair<Float, Float>,
        val maxVisits: Int,
        val cancelled: () -> Boolean,
    ) {
        val items = ArrayList<SvgItem>()
        val groupNames = ArrayList<String>()
        val skipped = LinkedHashMap<String, Int>().apply { putAll(doc.skipped) }
        var truncated = false
        var points = 0
        private var visits = 0
        var group = 0
        var bounds: Bounds? = null
        private val useStack = ArrayList<String>()

        // Rules indexed by what their selector names (the others: universal).
        private val byTag = HashMap<String, MutableList<SvgCss.Rule>>()
        private val byClass = HashMap<String, MutableList<SvgCss.Rule>>()
        private val byId = HashMap<String, MutableList<SvgCss.Rule>>()
        private val universal = ArrayList<SvgCss.Rule>()

        init {
            for (r in doc.css.rules) {
                val s = r.selector
                when {
                    s.ids.isNotEmpty() -> byId.getOrPut(s.ids[0]) { ArrayList() } += r
                    s.classes.isNotEmpty() -> byClass.getOrPut(s.classes[0]) { ArrayList() } += r
                    s.tag != null -> byTag.getOrPut(s.tag) { ArrayList() } += r
                    else -> universal += r
                }
            }
        }

        fun skip(what: String, n: Int = 1) { skipped[what] = (skipped[what] ?: 0) + n }

        fun style(e: SvgElement, parent: Style?): Style {
            val own = HashMap<String, String>()
            for (p in PROPS) e.attr(p)?.let { own[p] = it.trim() }
            val id = e.id
            val classes = e.classes
            val rules = ArrayList<SvgCss.Rule>()
            byTag[e.name]?.let { rules += it }
            for (cl in classes) byClass[cl]?.let { rules += it }
            if (id != null) byId[id]?.let { rules += it }
            rules += universal
            val matching = rules.distinct().filter { it.selector.matches(e.name, id, classes) }
                .sortedWith(compareBy<SvgCss.Rule>({ it.selector.specificity }, { it.order }))
            for (r in matching) for ((k, v) in r.declarations) if (k !in r.important) own[k] = v
            e.attr("style")?.let { st ->
                val (decls, _) = SvgCss.declarations(st)
                own.putAll(decls)
            }
            for (r in matching) for (k in r.important) r.declarations[k]?.let { own[k] = it }
            return Style(own, parent)
        }

        // ------------------------------------------------------------------ tree

        /** The root's children: each top-level `<g>` is a group, runs of other elements share one. */
        fun walkRoot(root: SvgElement, ctm: Affine, st: Style) {
            if (st.get("display") == "none") return
            var loose = -1
            for (child in root.children) {
                if (truncated) return
                if (child.isText || !isRendered(child)) continue
                if (child.name == "g") {
                    groupNames += groupName(child)
                    group = groupNames.lastIndex
                    loose = -1
                } else {
                    if (loose < 0) {
                        groupNames += "Layer ${groupNames.size + 1}"
                        loose = groupNames.lastIndex
                    }
                    group = loose
                }
                element(child, ctm, st, opacity(st.own["opacity"]))
            }
        }

        private fun groupName(g: SvgElement): String =
            g.attr("inkscape:label")?.trim()?.takeIf { it.isNotEmpty() }
                ?: g.attr("data-name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: g.id ?: "Group ${groupNames.size + 1}"

        private fun isRendered(e: SvgElement): Boolean = e.name in RENDERED

        /** Draws [e] (and its subtree) with the current transform [ctm] and inherited group opacity [alpha]. */
        fun element(e: SvgElement, ctm: Affine, parentStyle: Style, alpha: Float) {
            if (truncated || e.isText) return
            if (++visits > maxVisits) {
                truncated = true
                return
            }
            if (visits and 1023 == 0 && cancelled()) throw CancellationException("Import stopped")
            val st = style(e, parentStyle)
            if (st.get("display") == "none") return
            val t = SvgUnits.transform(e.attr("transform"))
            if (t == null) skip("unreadable transforms")
            val m = if (t != null) ctm * t else ctm
            val a = alpha * opacity(st.own["opacity"])
            if (st.own["clip-path"]?.let { it != "none" } == true) skip("clip paths")
            if (st.own["mask"]?.let { it != "none" } == true) skip("masks")
            if (st.own["filter"]?.let { it != "none" } == true) skip("filters")
            when (e.name) {
                "g", "a" -> for (c in e.children) element(c, m, st, a)
                "switch" -> e.children.firstOrNull { !it.isText && isRendered(it) && conditionsPass(it) }?.let { element(it, m, st, a) }
                "svg" -> nested(e, m, st, a)
                "use" -> use(e, m, st, a)
                "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> shape(e, m, st, a)
                "image" -> image(e, m, st, a)
                "text" -> text(e, m, st)
                else -> {}
            }
        }

        private fun conditionsPass(e: SvgElement): Boolean = e.attr("requiredExtensions").isNullOrBlank()

        /** A nested `<svg>`: its own viewport and viewBox. */
        private fun nested(e: SvgElement, m: Affine, st: Style, a: Float) {
            val (vw, vh) = viewport
            val x = SvgUnits.length(e.attr("x"), vw) ?: 0f
            val y = SvgUnits.length(e.attr("y"), vh) ?: 0f
            val w = SvgUnits.length(e.attr("width"), vw) ?: vw
            val h = SvgUnits.length(e.attr("height"), vh) ?: vh
            val vb = SvgUnits.viewBoxTransform(e.attr("viewBox"), e.attr("preserveAspectRatio"), x, y, w, h) ?: Affine.translate(x, y)
            for (c in e.children) element(c, m * vb, st, a)
        }

        private fun use(e: SvgElement, m: Affine, st: Style, a: Float) {
            val href = e.href?.trim() ?: return
            if (!href.startsWith("#")) { skip("external references"); return }
            val id = href.substring(1)
            val target = doc.ids[id] ?: return
            if (id in useStack || useStack.size >= MAX_USE_DEPTH) { skip("circular or too deep references"); return }
            val (vw, vh) = viewport
            val x = SvgUnits.length(e.attr("x"), vw) ?: 0f
            val y = SvgUnits.length(e.attr("y"), vh) ?: 0f
            val mt = m * Affine.translate(x, y)
            useStack += id
            try {
                if (target.name == "symbol" || target.name == "svg") {
                    val tst = style(target, st)
                    if (tst.get("display") == "none") return
                    val w = SvgUnits.length(e.attr("width") ?: target.attr("width"), vw) ?: vw
                    val h = SvgUnits.length(e.attr("height") ?: target.attr("height"), vh) ?: vh
                    val vb = SvgUnits.viewBoxTransform(target.attr("viewBox"), target.attr("preserveAspectRatio"), 0f, 0f, w, h) ?: Affine.IDENTITY
                    val ta = a * opacity(tst.own["opacity"])
                    val tt = SvgUnits.transform(target.attr("transform")) ?: Affine.IDENTITY
                    for (c in target.children) element(c, mt * tt * vb, tst, ta)
                } else {
                    element(target, mt, st, a)
                }
            } finally {
                useStack.removeAt(useStack.lastIndex)
            }
        }

        // ------------------------------------------------------------------ shapes

        private fun shape(e: SvgElement, m: Affine, st: Style, alpha: Float) {
            if (!visible(st)) return
            val local = geometry(e, st, m) ?: return
            if (local.isEmpty()) return
            val n = local.sumOf { op -> if (op is PathOp.CubicTo) 3 else 1 }
            if (points + n > maxPoints) { truncated = true; return }
            points += n
            val userPath = VectorPath(local)
            val mapped = userPath.transformed { p -> Vec2(m.mapX(p.x, p.y), m.mapY(p.x, p.y)) }
            val subpaths = VectorOps.subpathsOf(mapped)
            if (subpaths.isEmpty()) return
            val bbox by lazy { userPath.bounds(0.25f) }
            val fill = if (e.name == "line") null else paint(st.get("fill") ?: "black", st, opacity(st.get("fill-opacity")), m) { bbox }
            val stroke = stroke(st, m) { bbox }
            if (st.get("stroke-dasharray")?.let { it != "none" && it.isNotBlank() } == true && stroke != null) skip("dashed lines (drawn solid)")
            if (markers(st)) skip("markers")
            if (fill == null && stroke == null) return
            val rule = if (st.get("fill-rule") == "evenodd") VFillRule.EVENODD else VFillRule.NONZERO
            val path = VPath(0, opacity = alpha.coerceIn(0f, 1f), subpaths = subpaths, fillRule = rule, fill = fill, stroke = stroke)
            items += SvgItem.Shape(path, group)
            mapped.controlBounds()?.let { b ->
                val reach = (stroke?.width ?: 0f) / 2f
                extend(b.outset(reach))
            }
        }

        private fun markers(st: Style): Boolean =
            listOf("marker-start", "marker-mid", "marker-end").any { p -> st.get(p)?.let { it != "none" } == true } ||
                st.own["marker"]?.let { it != "none" } == true

        private fun visible(st: Style): Boolean = st.get("visibility").let { it != "hidden" && it != "collapse" }

        /** The element's outline in its user units. */
        private fun geometry(e: SvgElement, st: Style, m: Affine): List<PathOp>? {
            val (vw, vh) = viewport
            val fs = fontSize(st)
            fun len(name: String, ref: Float) = SvgUnits.length(e.attr(name), ref, fs)
            val tol = 0.05f / max(1e-6f, m.scale)
            return when (e.name) {
                "path" -> SvgPathData.parse(e.attr("d"), tol, maxPoints - points)
                "rect" -> {
                    val x = len("x", vw) ?: 0f
                    val y = len("y", vh) ?: 0f
                    val w = len("width", vw) ?: return null
                    val h = len("height", vh) ?: return null
                    if (w <= 0f || h <= 0f) return null
                    var rx = len("rx", vw)?.takeIf { it > 0f }
                    var ry = len("ry", vh)?.takeIf { it > 0f }
                    if (rx == null) rx = ry
                    if (ry == null) ry = rx
                    roundRect(x, y, w, h, min(rx ?: 0f, w / 2f), min(ry ?: 0f, h / 2f))
                }
                "circle" -> {
                    val r = SvgUnits.length(e.attr("r"), sqrt((vw * vw + vh * vh) / 2f), fs) ?: return null
                    if (r <= 0f) return null
                    ellipse(len("cx", vw) ?: 0f, len("cy", vh) ?: 0f, r, r)
                }
                "ellipse" -> {
                    val rx = len("rx", vw) ?: return null
                    val ry = len("ry", vh) ?: return null
                    if (rx <= 0f || ry <= 0f) return null
                    ellipse(len("cx", vw) ?: 0f, len("cy", vh) ?: 0f, rx, ry)
                }
                "line" -> listOf(
                    PathOp.MoveTo(Vec2(len("x1", vw) ?: 0f, len("y1", vh) ?: 0f)),
                    PathOp.LineTo(Vec2(len("x2", vw) ?: 0f, len("y2", vh) ?: 0f)),
                )
                "polyline", "polygon" -> {
                    val v = SvgUnits.numbers(e.attr("points"))
                    val n = v.size / 2
                    if (n < 2) return null
                    val ops = ArrayList<PathOp>(n + 1)
                    ops += PathOp.MoveTo(Vec2(v[0], v[1]))
                    for (i in 1 until n) ops += PathOp.LineTo(Vec2(v[2 * i], v[2 * i + 1]))
                    if (e.name == "polygon") ops += PathOp.Close
                    ops
                }
                else -> null
            }
        }

        private fun roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float): List<PathOp> {
            if (rx <= 0f || ry <= 0f) {
                return listOf(
                    PathOp.MoveTo(Vec2(x, y)), PathOp.LineTo(Vec2(x + w, y)), PathOp.LineTo(Vec2(x + w, y + h)),
                    PathOp.LineTo(Vec2(x, y + h)), PathOp.Close,
                )
            }
            val kx = rx * KAPPA
            val ky = ry * KAPPA
            val r = x + w
            val b = y + h
            return listOf(
                PathOp.MoveTo(Vec2(x + rx, y)),
                PathOp.LineTo(Vec2(r - rx, y)),
                PathOp.CubicTo(Vec2(r - rx + kx, y), Vec2(r, y + ry - ky), Vec2(r, y + ry)),
                PathOp.LineTo(Vec2(r, b - ry)),
                PathOp.CubicTo(Vec2(r, b - ry + ky), Vec2(r - rx + kx, b), Vec2(r - rx, b)),
                PathOp.LineTo(Vec2(x + rx, b)),
                PathOp.CubicTo(Vec2(x + rx - kx, b), Vec2(x, b - ry + ky), Vec2(x, b - ry)),
                PathOp.LineTo(Vec2(x, y + ry)),
                PathOp.CubicTo(Vec2(x, y + ry - ky), Vec2(x + rx - kx, y), Vec2(x + rx, y)),
                PathOp.Close,
            )
        }

        private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<PathOp> {
            val kx = rx * KAPPA
            val ky = ry * KAPPA
            return listOf(
                PathOp.MoveTo(Vec2(cx + rx, cy)),
                PathOp.CubicTo(Vec2(cx + rx, cy + ky), Vec2(cx + kx, cy + ry), Vec2(cx, cy + ry)),
                PathOp.CubicTo(Vec2(cx - kx, cy + ry), Vec2(cx - rx, cy + ky), Vec2(cx - rx, cy)),
                PathOp.CubicTo(Vec2(cx - rx, cy - ky), Vec2(cx - kx, cy - ry), Vec2(cx, cy - ry)),
                PathOp.CubicTo(Vec2(cx + kx, cy - ry), Vec2(cx + rx, cy - ky), Vec2(cx + rx, cy)),
                PathOp.Close,
            )
        }

        // ------------------------------------------------------------------ paints

        private fun fontSize(st: Style): Float {
            val raw = st.get("font-size") ?: return 16f
            val named = when (raw.trim()) {
                "xx-small" -> 9f; "x-small" -> 10f; "small" -> 13f; "medium" -> 16f; "large" -> 18f; "x-large" -> 24f; "xx-large" -> 32f
                else -> null
            }
            if (named != null) return named
            val parent = st.parent?.let { fontSize(it) } ?: 16f
            return SvgUnits.length(raw, parent, parent)?.takeIf { it > 0f } ?: 16f
        }

        /** The fill [value] as a [VPaint] (null = nothing to paint). */
        private fun paint(value: String, st: Style, opacity: Float, m: Affine, bbox: () -> Bounds?): VPaint? {
            if (opacity <= 0f) return null
            // An unreadable value: the initial fill (black).
            return paint(SvgColors.paint(value) ?: SvgPaint.Color(0xFF000000.toInt()), st, opacity, m, bbox)
        }

        private fun paint(p: SvgPaint, st: Style, opacity: Float, m: Affine, bbox: () -> Bounds?): VPaint? = when (p) {
            SvgPaint.None -> null
            SvgPaint.CurrentColor -> VPaint.Solid(withAlpha(currentColor(st), opacity))
            is SvgPaint.Color -> VPaint.Solid(withAlpha(p.argb, opacity))
            is SvgPaint.Url -> {
                val target = doc.ids[p.id]
                when (target?.name) {
                    "linearGradient", "radialGradient" -> gradient(target, st, opacity, m, bbox)
                    "pattern" -> { skip("patterns"); p.fallback?.let { fb -> paint(fb, st, opacity, m, bbox) } }
                    else -> p.fallback?.let { fb -> paint(fb, st, opacity, m, bbox) }
                }
            }
        }

        private fun currentColor(st: Style): Int = SvgColors.parse(st.get("color")) ?: 0xFF000000.toInt()

        private fun stroke(st: Style, m: Affine, bbox: () -> Bounds?): VStrokeStyle? {
            val v = st.get("stroke") ?: return null
            val opacity = opacity(st.get("stroke-opacity"))
            if (opacity <= 0f) return null
            val p = SvgColors.paint(v) ?: return null
            val color = when (p) {
                SvgPaint.None -> return null
                SvgPaint.CurrentColor -> currentColor(st)
                is SvgPaint.Color -> p.argb
                is SvgPaint.Url -> {
                    val target = doc.ids[p.id]
                    if (target != null && (target.name == "linearGradient" || target.name == "radialGradient")) {
                        skip("gradient outlines (drawn in one color)")
                        val stops = stops(target, st)
                        stops.getOrNull(stops.size / 2)?.color ?: return null
                    } else {
                        if (target?.name == "pattern") skip("patterns")
                        val fb = p.fallback as? SvgPaint.Color ?: return null
                        fb.argb
                    }
                }
            }
            val (vw, vh) = viewport
            val w0 = SvgUnits.length(st.get("stroke-width") ?: "1", sqrt((vw * vw + vh * vh) / 2f), fontSize(st)) ?: 1f
            val width = w0 * m.scale
            if (!(width > 0f) || !width.isFinite()) return null
            val cap = when (st.get("stroke-linecap")) { "round" -> LineCapStyle.ROUND; "square" -> LineCapStyle.SQUARE; else -> LineCapStyle.BUTT }
            val join = when (st.get("stroke-linejoin")) { "round" -> JoinStyle.ROUND; "bevel" -> JoinStyle.BEVEL; else -> JoinStyle.MITER }
            val miter = st.get("stroke-miterlimit")?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 1f } ?: 4f
            return VStrokeStyle(kind = VStrokeKind.PLAIN, color = withAlpha(color, opacity), width = width, cap = cap, join = join, miter = miter)
        }

        /** Gradient stops (href chain followed), opacity and colors resolved. */
        private fun stops(g: SvgElement, st: Style): List<VStop> {
            var cur: SvgElement? = g
            var hops = 0
            while (cur != null && hops++ < MAX_USE_DEPTH) {
                val stopEls = cur.children.filter { it.name == "stop" }
                if (stopEls.isNotEmpty()) {
                    val gst = style(cur, st)
                    var last = 0f
                    return stopEls.map { s ->
                        val sst = style(s, gst)
                        val off = s.attr("offset")?.trim()?.let { o ->
                            if (o.endsWith("%")) o.dropLast(1).toFloatOrNull()?.div(100f) else o.toFloatOrNull()
                        }?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                        last = max(last, off)
                        val colorText = sst.own["stop-color"] ?: "black"
                        val color = if (colorText.equals("currentColor", true)) currentColor(sst) else SvgColors.parse(colorText) ?: 0xFF000000.toInt()
                        VStop(last, withAlpha(color, opacity(sst.own["stop-opacity"])))
                    }
                }
                cur = cur.href?.trim()?.takeIf { it.startsWith("#") }?.let { doc.ids[it.substring(1)] }
            }
            return emptyList()
        }

        /** Gradient attribute [name] along the href chain. */
        private fun gattr(g: SvgElement, name: String): String? {
            var cur: SvgElement? = g
            var hops = 0
            while (cur != null && hops++ < MAX_USE_DEPTH) {
                cur.attr(name)?.let { return it }
                cur = cur.href?.trim()?.takeIf { it.startsWith("#") }?.let { doc.ids[it.substring(1)] }
            }
            return null
        }

        private fun gradient(g: SvgElement, st: Style, opacity: Float, m: Affine, bbox: () -> Bounds?): VPaint? {
            val raw = stops(g, st)
            if (raw.isEmpty()) return null
            val stops = raw.map { it.copy(color = withAlpha(it.color, opacity)) }
            if (stops.size == 1) return VPaint.Solid(stops[0].color)
            val userSpace = gattr(g, "gradientUnits") == "userSpaceOnUse"
            val spread = gattr(g, "spreadMethod")
            if (spread == "reflect" || spread == "repeat") skip("repeating gradients (drawn padded)")
            val gt = SvgUnits.transform(gattr(g, "gradientTransform")) ?: Affine.IDENTITY
            val units: Affine
            val (vw, vh) = viewport
            val refW: Float
            val refH: Float
            if (userSpace) {
                units = Affine.IDENTITY
                refW = vw; refH = vh
            } else {
                val b = bbox() ?: return null
                if (b.width <= 0f || b.height <= 0f) return null
                units = Affine(b.width, 0f, 0f, b.height, b.left, b.top)
                refW = 1f; refH = 1f
            }
            val full = m * units * gt
            fun coord(name: String, def: String, ref: Float): Float {
                val s = gattr(g, name) ?: def
                val t = s.trim()
                return if (t.endsWith("%")) (t.dropLast(1).toFloatOrNull() ?: 0f) / 100f * ref
                else SvgUnits.length(t, ref) ?: 0f
            }
            return if (g.name == "linearGradient") {
                val x1 = coord("x1", "0%", refW); val y1 = coord("y1", "0%", refH)
                val x2 = coord("x2", "100%", refW); val y2 = coord("y2", "0%", refH)
                linear(full, x1, y1, x2, y2, stops)
            } else {
                val ref = if (userSpace) sqrt((vw * vw + vh * vh) / 2f) else 1f
                val cx = coord("cx", "50%", refW); val cy = coord("cy", "50%", refH)
                val r = coord("r", "50%", ref)
                if (gattr(g, "fx") != null || gattr(g, "fy") != null) {
                    val fx = coord("fx", "50%", refW); val fy = coord("fy", "50%", refH)
                    if (fx != cx || fy != cy) skip("gradient focal points")
                }
                if (r <= 0f) return VPaint.Solid(stops.last().color)
                VPaint.Radial(cx, cy, r, stops, full.toList())
            }
        }

        /** A linear gradient (gradient space) mapped by [m]: iso-lines stay where they map to. */
        private fun linear(m: Affine, x1: Float, y1: Float, x2: Float, y2: Float, stops: List<VStop>): VPaint {
            val q0 = Vec2(m.mapX(x1, y1), m.mapY(x1, y1))
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            // Jacobian (dX/dx, dX/dy, dY/dx, dY/dy) of the affine map.
            val ja = m.a; val jb = m.c; val jc = m.b; val jd = m.d
            val det = ja * jd - jb * jc
            if (len2 <= 0f || det == 0f || !det.isFinite()) {
                return VPaint.Solid(stops.last().color)
            }
            val gx = (jd * dx - jc * dy) / det / len2
            val gy = (-jb * dx + ja * dy) / det / len2
            val g2 = gx * gx + gy * gy
            if (g2 <= 0f || !g2.isFinite()) return VPaint.Solid(stops.last().color)
            return VPaint.Linear(q0.x, q0.y, q0.x + gx / g2, q0.y + gy / g2, stops)
        }

        // ------------------------------------------------------------------ pictures and text

        private fun image(e: SvgElement, m: Affine, st: Style, alpha: Float) {
            if (!visible(st)) return
            val data = DataUri.decode(e.href, e.hrefSlice)
            if (data == null) {
                if (e.href != null || e.hrefSlice != null) skip("external pictures")
                return
            }
            val (vw, vh) = viewport
            val x = SvgUnits.length(e.attr("x"), vw) ?: 0f
            val y = SvgUnits.length(e.attr("y"), vh) ?: 0f
            val w = SvgUnits.length(e.attr("width"), vw) ?: 0f
            val h = SvgUnits.length(e.attr("height"), vh) ?: 0f
            if (e.has("width") && w <= 0f || e.has("height") && h <= 0f) return
            val img = SvgImage(data.second, data.first, x, y, w, h, e.attr("preserveAspectRatio"), m, alpha.coerceIn(0f, 1f))
            items += SvgItem.Picture(img, group)
            if (w > 0f && h > 0f) {
                val corners = listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h)).map { Vec2(m.mapX(it.x, it.y), m.mapY(it.x, it.y)) }
                Bounds.of(corners)?.let { extend(it) }
            }
        }

        private fun text(e: SvgElement, m: Affine, st: Style) {
            if (!visible(st)) return
            val (vw, vh) = viewport
            val fs0 = fontSize(st)
            var x = SvgUnits.numbers(e.attr("x")).firstOrNull() ?: 0f
            var y = SvgUnits.numbers(e.attr("y")).firstOrNull() ?: 0f
            val preserve = e.attr("xml:space") == "preserve"
            val lines = ArrayList<StringBuilder>()
            val baselines = ArrayList<Float>()
            var first: Pair<Float, Float>? = null
            var firstStyle: Style? = null
            var lineY = Float.NaN
            fun put(t: String, px: Float, py: Float, s: Style) {
                val txt = if (preserve) t.replace('\n', ' ') else t.replace(Regex("\\s+"), " ")
                if (txt.isEmpty()) return
                if (first == null) {
                    if (txt.isBlank()) return
                    first = px to py
                    firstStyle = s
                }
                if (lines.isEmpty() || (py != lineY && !lineY.isNaN())) {
                    lines += StringBuilder()
                    baselines += py
                }
                lineY = py
                lines.last().append(txt)
            }
            fun walk(el: SvgElement, s: Style) {
                for (c in el.children) {
                    if (c.isText) {
                        put(c.text ?: c.textSlice?.toString() ?: "", x, y, s)
                        continue
                    }
                    if (c.name == "textPath") skip("text on a path (placed straight)")
                    if (c.name != "tspan" && c.name != "textPath" && c.name != "a") continue
                    val cs = style(c, s)
                    if (cs.get("display") == "none") continue
                    SvgUnits.numbers(c.attr("x")).firstOrNull()?.let { x = it }
                    SvgUnits.numbers(c.attr("y")).firstOrNull()?.let { y = it }
                    SvgUnits.numbers(c.attr("dy")).firstOrNull()?.let { y += it }
                    walk(c, cs)
                }
            }
            walk(e, st)
            val anchorPt = first ?: return
            val texts = lines.map { it.toString().trim() }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) return
            val s = firstStyle ?: st
            val fill = SvgColors.paint(s.get("fill") ?: "black")
            val color = when (fill) {
                is SvgPaint.Color -> fill.argb
                SvgPaint.CurrentColor -> currentColor(s)
                is SvgPaint.Url -> (fill.fallback as? SvgPaint.Color)?.argb ?: doc.ids[fill.id]?.let { stops(it, s).firstOrNull()?.color } ?: 0xFF000000.toInt()
                else -> return
            }
            val c = withAlpha(color, opacity(s.get("fill-opacity")))
            val fs = fontSize(s)
            val px = m.mapX(anchorPt.first, anchorPt.second)
            val py = m.mapY(anchorPt.first, anchorPt.second)
            val rot = Math.toDegrees(atan2(m.b.toDouble(), m.a.toDouble())).toFloat()
            val size = fs * m.scale
            if (!(size > 0f) || !size.isFinite()) return
            val weight = s.get("font-weight")?.trim()
            val bold = weight == "bold" || weight == "bolder" || (weight?.toIntOrNull() ?: 400) >= 600
            val italic = s.get("font-style")?.trim().let { it == "italic" || it == "oblique" }
            val anchor = when (s.get("text-anchor")?.trim()) { "middle" -> SvgTextAnchor.MIDDLE; "end" -> SvgTextAnchor.END; else -> SvgTextAnchor.START }
            val lh = if (baselines.size >= 2 && fs0 > 0f) ((baselines[1] - baselines[0]) / fs).coerceIn(0.5f, 4f) else 0f
            items += SvgItem.Label(SvgText(texts, px, py, rot, size, genericFamily(s.get("font-family")), bold, italic, c, anchor, lh), group)
            extend(Bounds(px - size, py - size, px + size * max(1, texts.maxOf { it.length }), py + size * texts.size))
        }

        private fun genericFamily(f: String?): String {
            val t = f?.lowercase() ?: return "sans-serif"
            return when {
                t.contains("mono") || t.contains("courier") || t.contains("consolas") || t.contains("menlo") -> "monospace"
                t.contains("comic") || t.contains("casual") -> "casual"
                t.contains("cursive") || t.contains("script") || t.contains("brush") || t.contains("hand") -> "cursive"
                t.contains("condensed") || t.contains("narrow") -> "condensed"
                t.contains("sans") || t.contains("arial") || t.contains("helvetica") || t.contains("roboto") || t.contains("verdana") -> "sans-serif"
                t.contains("serif") || t.contains("times") || t.contains("georgia") || t.contains("garamond") -> "serif"
                else -> "sans-serif"
            }
        }

        private fun extend(b: Bounds) {
            bounds = bounds?.union(b) ?: b
        }

        companion object {
            /** Cubic circle approximation constant. */
            const val KAPPA = 0.5522848f

            /** Elements that draw something (or contain what does). */
            val RENDERED = setOf("g", "a", "switch", "svg", "use", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "image", "text")
        }
    }

    /** An opacity value (number or percentage) clamped to 0..1; 1 when missing or unreadable. */
    fun opacity(s: String?): Float {
        val t = s?.trim() ?: return 1f
        val v = if (t.endsWith("%")) t.dropLast(1).toFloatOrNull()?.div(100f) else t.toFloatOrNull()
        return v?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    }

    /** [argb] with its alpha multiplied by [opacity]. */
    fun withAlpha(argb: Int, opacity: Float): Int {
        val a = ((argb ushr 24) * opacity.coerceIn(0f, 1f) + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    /** Rotation of [m] in degrees (for placed text). */
    internal fun rotationOf(m: Affine): Float = (atan2(m.b.toDouble(), m.a.toDouble()) * 180.0 / PI).toFloat()
}
