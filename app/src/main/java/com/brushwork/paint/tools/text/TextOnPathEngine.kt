package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import android.os.Build
import java.text.Bidi
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * One grapheme cluster (a letter with its accents, an emoji sequence...) of a text on a path, in
 * visual order. [x0]..[x1] is its extent on the straight line; [start]..[end] its characters and
 * [contextStart]..[contextEnd] the bidi run it is shaped in (joined Arabic letters stay joined).
 */
internal class PathCluster(
    val start: Int,
    val end: Int,
    val contextStart: Int,
    val contextEnd: Int,
    val rtl: Boolean,
    val x0: Float,
    val x1: Float,
    /** Only spaces: nothing to draw. */
    val blank: Boolean,
    /** Drawn unbent even when letters bend: color emoji have no outline to bend. */
    val rigid: Boolean,
    /** Ink bounds when drawn alone at x = 0 on baseline 0 ([dy] and [scale] included). */
    val ink: RectF,
    /** v1.6 letter scaling: the cluster's size factor (1 = the paint's size). */
    val scale: Float = 1f,
    /** v1.6 letter scaling: its baseline shift in layout y (negative = away from the path, up): Center / Top alignment. */
    val dy: Float = 0f,
) {
    val advance: Float get() = x1 - x0

    /** True when this cluster is drawn at another size or place than plain text would (v1.6). */
    val scaled: Boolean get() = scale != 1f || dy != 0f
}

/**
 * The straight single-line layout of a text with one paint: its clusters, advance width, cap
 * height and (built on first use, for bent letters) its flattened outline. Immutable once built.
 */
internal class PathTextLayout(
    val line: String,
    val width: Float,
    val capHeight: Float,
    val clusters: List<PathCluster>,
    private val paint: Paint,
    /** v1.6: the letters have their own sizes (each cluster is outlined and drawn on its own). */
    val lettersScaled: Boolean = false,
) {
    /**
     * Flattened glyph outlines (closed polygons, x, y pairs, baseline 0) and for each contour the
     * index of the cluster it belongs to (the one its middle falls in).
     */
    class Outline(val contours: List<FloatArray>, val owners: IntArray, val fillType: Path.FillType)

    private var outline: Outline? = null

    /** Flattened glyph outlines of every non-rigid cluster (built once; call under the engine's lock). */
    fun outline(): Outline {
        outline?.let { return it }
        val path = Path()
        if (lettersScaled) {
            // v1.6 scaled letters: every cluster at its own size and baseline shift.
            val piece = Path()
            val base = paint.textSize
            try {
                for (c in clusters) {
                    if (c.rigid || c.blank) continue
                    paint.textSize = base * c.scale
                    piece.rewind()
                    paint.getTextPath(line, c.start, c.end, c.x0, c.dy, piece)
                    path.addPath(piece)
                }
            } finally {
                paint.textSize = base
            }
        } else if (clusters.none { it.rigid }) {
            paint.getTextPath(line, 0, line.length, 0f, 0f, path)
        } else {
            // Consecutive non-rigid clusters of one bidi run are outlined together (shaped as one
            // piece), each piece at its visual left edge.
            val piece = Path()
            var i = 0
            while (i < clusters.size) {
                val c = clusters[i]
                if (c.rigid) { i++; continue }
                var j = i
                while (j + 1 < clusters.size && !clusters[j + 1].rigid && clusters[j + 1].contextStart == c.contextStart) j++
                var s = Int.MAX_VALUE
                var e = Int.MIN_VALUE
                var left = Float.MAX_VALUE
                for (k in i..j) {
                    s = min(s, clusters[k].start); e = max(e, clusters[k].end); left = min(left, clusters[k].x0)
                }
                piece.rewind()
                paint.getTextPath(line, s, e, left, 0f, piece)
                path.addPath(piece)
                i = j + 1
            }
        }
        val contours = TextOnPathEngine.flatten(path, TextOnPathEngine.tolerance(paint.textSize))
        val owners = IntArray(contours.size) { k -> ownerOf(contours[k]) }
        val o = Outline(contours, owners, path.fillType)
        outline = o
        return o
    }

    /** The cluster (index) whose extent holds the middle of [contour], or the nearest one. */
    private fun ownerOf(contour: FloatArray): Int {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until contour.size / 2) {
            lo = min(lo, contour[2 * i]); hi = max(hi, contour[2 * i])
        }
        val mid = (lo + hi) / 2f
        var best = 0
        var bestDist = Float.MAX_VALUE
        for ((i, c) in clusters.withIndex()) {
            if (c.rigid) continue
            val d = if (mid < c.x0) c.x0 - mid else if (mid > c.x1) mid - c.x1 else 0f
            // Inside a cluster: prefer one with ink over a space it overhangs.
            val score = if (d == 0f && c.blank) 1e-3f else d
            if (score < bestDist) { bestDist = score; best = i }
            if (score == 0f) break
        }
        return best
    }

    /** Draws cluster [c] with [paint] so that its baseline center lands on the canvas origin. */
    fun drawCluster(canvas: Canvas, c: PathCluster, paint: Paint) {
        if (!lettersScaled) {
            canvas.drawTextRun(line, c.start, c.end, c.contextStart, c.contextEnd, -c.advance / 2f, 0f, c.rtl, paint)
            return
        }
        // v1.6 scaled letter: at its size, its baseline shifted (the paint is given back as it was).
        val base = paint.textSize
        paint.textSize = base * c.scale
        try {
            canvas.drawTextRun(line, c.start, c.end, c.start, c.end, -c.advance / 2f, c.dy, c.rtl, paint)
        } finally {
            paint.textSize = base
        }
    }
}

/** A cluster drawn rigidly: its baseline center at ([x], [y]), turned by [angleDeg]. */
internal class PathPlacement(val cluster: PathCluster, val x: Float, val y: Float, val angleDeg: Float)

/**
 * Text laid out along one path: the bent outline ([path], BEND mode) and the rigidly placed
 * clusters (every cluster in ROTATE mode, emoji in BEND mode), with their bounds (no stroke, no
 * anti-aliasing margin).
 */
internal class PathTextResult(
    val path: Path?,
    val pathBounds: RectF,
    val placements: List<PathPlacement>,
    val placementBounds: RectF,
)

/** Layout, bending and caching behind [TextOnPath]. Thread-safe; results are immutable. */
internal object TextOnPathEngine {

    /** Anti-aliasing margin added to bounds (px). */
    private const val AA_PAD = 1f

    /** Flattening / bending tolerance for text [size] px tall: finer for small text, at most 0.25 px. */
    fun tolerance(size: Float): Float = (size * 0.002f).coerceIn(0.05f, 0.25f)

    /** Everything of a paint that changes glyph shapes or advances. */
    private data class PaintKey(
        val typeface: Typeface?,
        val size: Float,
        val letterSpacing: Float,
        val scaleX: Float,
        val skewX: Float,
        val flags: Int,
        val features: String?,
        val variations: String?,
        val locales: String,
    ) {
        companion object {
            fun of(p: Paint) = PaintKey(
                p.typeface, p.textSize, p.letterSpacing, p.textScaleX, p.textSkewX, p.flags,
                p.fontFeatureSettings, p.fontVariationSettings, p.textLocales.toLanguageTags(),
            )
        }
    }

    /** v1.6: [letters] = the letter scaling the layout has (null = none). */
    private data class LayoutKey(val text: String, val paint: PaintKey, val letters: LetterScaleSpec?)

    /** [layout] is compared by identity (a new layout is a new text or font). */
    private data class ResultKey(val layout: PathTextLayout, val spec: TextPathSpec)

    private class Lru<K, V>(private val capacity: Int) {
        private val map = LinkedHashMap<K, V>(8, 0.75f, true)
        operator fun get(k: K): V? = map[k]
        operator fun set(k: K, v: V) {
            map[k] = v
            while (map.size > capacity) map.remove(map.keys.first())
        }
    }

    /** A cached layout; null for a text with nothing to draw. */
    private class LayoutEntry(val layout: PathTextLayout?)

    private val layouts = Lru<LayoutKey, LayoutEntry>(2)
    private val results = Lru<ResultKey, PathTextResult>(2)
    private var guideKey: TextPathSpec? = null
    private var guideValue: TextPathGuide? = null

    /** [text] on one line: line breaks and tabs become spaces. */
    fun oneLine(text: String): String {
        if (text.none { isBreak(it) }) return text
        return text.replace("\r\n", " ").map { if (isBreak(it)) ' ' else it }.joinToString("")
    }

    /** Line breaks, paragraph breaks and tabs (U+2028 and U+2029 are the Unicode line / paragraph separators). */
    private fun isBreak(c: Char): Boolean = c == '\n' || c == '\r' || c == '\t' || c.code == 0x2028 || c.code == 0x2029

    /**
     * The layout of [text] with [paint] (cached), null when there is nothing to draw. [letters]
     * (v1.6): the letters are scaled (the caller has checked the script can be; null = plain).
     */
    @Synchronized
    fun layout(text: String, paint: Paint, letters: LetterScaleSpec? = null): PathTextLayout? {
        val scale = letters?.takeIf { it.isOn }
        val key = LayoutKey(text, PaintKey.of(paint), scale)
        layouts[key]?.let { return it.layout }
        val line = oneLine(text)
        val l = if (line.isBlank()) null else buildLayout(line, paint, scale)
        layouts[key] = LayoutEntry(l)
        return l
    }
    /** Layout and placement of [text] along [spec] (cached for the last two inputs); null when nothing is drawn. */
    @Synchronized
    fun result(text: String, paint: Paint, requested: TextPathSpec, letters: LetterScaleSpec? = null): Pair<PathTextLayout, PathTextResult>? {
        if (!requested.isActive) return null
        // Numbers out of any sensible range (a corrupt file, a runaway pinch) are fixed first.
        val spec = TextPathGeometry.sanitized(requested)
        val layout = layout(text, paint, letters) ?: return null
        val key = ResultKey(layout, spec)
        results[key]?.let { return layout to it }
        val guide = guideFor(spec) ?: return null
        val r = place(layout, guide, spec, tolerance(paint.textSize))
        results[key] = r
        return layout to r
    }

    /** The guide of [spec] (the last one is kept: dragging the offset or switching modes reuses it). */
    private fun guideFor(spec: TextPathSpec): TextPathGuide? {
        val key = spec.copy(mode = TextPathMode.BEND, side = TextPathSide.OUTSIDE, align = TextPathAlign.CENTER, offset = 0f, baselineShift = 0f, keepSquare = false)
        if (key == guideKey) return guideValue
        val g = TextPathGeometry.guide(spec)
        guideKey = key
        guideValue = g
        return g
    }

    private fun place(layout: PathTextLayout, guide: TextPathGuide, spec: TextPathSpec, tol: Float): PathTextResult {
        val start = TextPathGeometry.startDistance(spec, guide, layout.width)
        val shift = TextPathGeometry.heightOffset(spec, layout.capHeight)
        val bend = spec.mode == TextPathMode.BEND
        val e = DoubleArray(4)
        val m = Matrix()
        val r = RectF()
        val placements = ArrayList<PathPlacement>()
        val placementBounds = RectF()
        for (c in layout.clusters) {
            if (c.blank || (bend && !c.rigid)) continue
            guide.eval(start + (c.x0 + c.x1) / 2.0, e)
            val x = (e[0] + e[3] * shift).toFloat()
            val y = (e[1] - e[2] * shift).toFloat()
            val angle = Math.toDegrees(atan2(e[3], e[2])).toFloat()
            placements += PathPlacement(c, x, y, angle)
            m.setTranslate(-c.advance / 2f, 0f)
            m.postRotate(angle)
            m.postTranslate(x, y)
            r.set(c.ink)
            m.mapRect(r)
            placementBounds.union(r)
        }
        var path: Path? = null
        val pathBounds = RectF()
        if (bend) {
            val outline = layout.outline()
            if (outline.contours.isNotEmpty()) {
                // Letters where the path turns too tightly for them (a square's sharp corner, a
                // hairpin of a curve) keep their shape and turn with the path; the rest bend.
                val frames = arrayOfNulls<DoubleArray>(layout.clusters.size)
                val checked = BooleanArray(layout.clusters.size)
                val bent = ArrayList<FloatArray>(outline.contours.size)
                val stiff = ArrayList<FloatArray>()
                for ((k, contour) in outline.contours.withIndex()) {
                    val owner = outline.owners[k]
                    if (!checked[owner]) {
                        checked[owner] = true
                        frames[owner] = rigidFrame(layout.clusters[owner], guide, start, shift)
                    }
                    val frame = frames[owner]
                    if (frame == null) bent += contour else stiff += transformRigid(contour, frame)
                }
                val p = Path()
                p.fillType = outline.fillType
                val warped = if (bent.isEmpty()) emptyList() else TextPathWarp.warp(bent, guide, start, shift, tol.toDouble())
                for (pts in warped + stiff) {
                    val n = pts.size / 2
                    if (n < 2) continue
                    p.moveTo(pts[0], pts[1])
                    for (i in 1 until n) p.lineTo(pts[2 * i], pts[2 * i + 1])
                    p.close()
                }
                p.computeBounds(pathBounds, true)
                path = p
            }
        }
        return PathTextResult(path, pathBounds, placements, placementBounds)
    }

    /**
     * For a cluster the path turns too tightly to bend: its rigid frame (baseline center x in the
     * layout, then position and unit tangent on the path); null when it bends.
     */
    private fun rigidFrame(c: PathCluster, guide: TextPathGuide, start: Double, shift: Double): DoubleArray? {
        val low = shift - c.ink.bottom
        val high = shift - c.ink.top
        if (!TextPathGeometry.tooCurvedToBend(guide, start + c.x0, start + c.x1, low, high)) return null
        val mid = (c.x0 + c.x1) / 2.0
        val e = DoubleArray(4)
        guide.eval(start + mid, e)
        return doubleArrayOf(mid, e[0] + e[3] * shift, e[1] - e[2] * shift, e[2], e[3])
    }

    /** [contour] (layout coordinates) turned and moved as one piece by [frame] (see [rigidFrame]). */
    private fun transformRigid(contour: FloatArray, frame: DoubleArray): FloatArray {
        val mid = frame[0]; val ox = frame[1]; val oy = frame[2]; val c = frame[3]; val s = frame[4]
        return FloatArray(contour.size) { i ->
            val lx = contour[i and 1.inv()] - mid
            val ly = contour[i or 1].toDouble()
            (if (i and 1 == 0) ox + lx * c - ly * s else oy + lx * s + ly * c).toFloat()
        }
    }

    // ------------------------------------------------------------------ drawing

    /** Draws [text] along [spec]; returns the drawn bounds (empty when nothing was drawn). */
    fun draw(canvas: Canvas, text: String, fill: Paint, stroke: Paint?, spec: TextPathSpec, letters: LetterScaleSpec? = null): RectF {
        val (layout, res) = result(text, fill, spec, letters) ?: return RectF()
        val fillAlign = fill.textAlign
        val strokeAlign = stroke?.textAlign
        fill.textAlign = Paint.Align.LEFT
        stroke?.textAlign = Paint.Align.LEFT
        try {
            if (spec.mode == TextPathMode.BEND) {
                res.path?.let { p ->
                    if (stroke != null) canvas.drawPath(p, stroke)
                    canvas.drawPath(p, fill)
                }
                for (pl in res.placements) drawPlaced(canvas, layout, pl, fill)
            } else {
                if (stroke != null) {
                    val outline = withTextOf(stroke, fill)
                    for (pl in res.placements) if (!pl.cluster.rigid) drawPlaced(canvas, layout, pl, outline)
                }
                for (pl in res.placements) drawPlaced(canvas, layout, pl, fill)
            }
        } finally {
            fill.textAlign = fillAlign
            if (stroke != null && strokeAlign != null) stroke.textAlign = strokeAlign
        }
        return bounds(res, spec, fill, stroke)
    }

    /**
     * [stroke] as is when it lays text out like [fill] (same font, size, spacing...), else a copy
     * with [fill]'s text settings: the outline of rigid letters is drawn as text and must match.
     */
    private fun withTextOf(stroke: Paint, fill: Paint): Paint {
        if (PaintKey.of(stroke).copy(flags = 0) == PaintKey.of(fill).copy(flags = 0) && stroke.isFakeBoldText == fill.isFakeBoldText) return stroke
        return Paint(stroke).apply {
            typeface = fill.typeface
            textSize = fill.textSize
            letterSpacing = fill.letterSpacing
            textScaleX = fill.textScaleX
            textSkewX = fill.textSkewX
            isFakeBoldText = fill.isFakeBoldText
            fontFeatureSettings = fill.fontFeatureSettings
            setFontVariationSettings(fill.fontVariationSettings)
            textLocales = fill.textLocales
            textAlign = Paint.Align.LEFT
        }
    }

    private fun drawPlaced(canvas: Canvas, layout: PathTextLayout, pl: PathPlacement, paint: Paint) {
        val save = canvas.save()
        canvas.translate(pl.x, pl.y)
        canvas.rotate(pl.angleDeg)
        layout.drawCluster(canvas, pl.cluster, paint)
        canvas.restoreToCount(save)
    }

    /**
     * What [draw] paints, as outlines in document coordinates: the letters (first) and, with a
     * [stroke], the area the outline stroke covers (second; null without one). Color emoji have
     * no outline. Null when nothing is drawn.
     */
    fun outlines(text: String, fill: Paint, stroke: Paint?, spec: TextPathSpec, letters: LetterScaleSpec? = null): Pair<Path, Path?>? {
        val (layout, res) = result(text, fill, spec, letters) ?: return null
        val glyphs = Path()
        val outlined = Path()
        val tmp = Path()
        val m = Matrix()
        val p = Paint(fill).apply { textAlign = Paint.Align.LEFT; style = Paint.Style.FILL }
        val bend = spec.mode == TextPathMode.BEND
        res.path?.let { if (bend) { glyphs.addPath(it); outlined.addPath(it) } }
        for (pl in res.placements) {
            val c = pl.cluster
            tmp.rewind()
            if (layout.lettersScaled) {
                // v1.6: at the cluster's own size and baseline shift, as drawCluster draws it.
                val base = p.textSize
                p.textSize = base * c.scale
                p.getTextPath(layout.line, c.start, c.end, -c.advance / 2f, c.dy, tmp)
                p.textSize = base
            } else {
                p.getTextPath(layout.line, c.start, c.end, -c.advance / 2f, 0f, tmp)
            }
            if (tmp.isEmpty) continue
            m.setRotate(pl.angleDeg)
            m.postTranslate(pl.x, pl.y)
            tmp.transform(m)
            glyphs.addPath(tmp)
            // Bent text strokes only its bent outline; rotated letters are each stroked.
            if (!bend && !c.rigid) outlined.addPath(tmp)
        }
        if (glyphs.isEmpty) return null
        val strokeArea = if (stroke != null && !outlined.isEmpty) {
            Path().also { out -> Paint(stroke).apply { style = Paint.Style.STROKE }.getFillPath(outlined, out) }
        } else null
        return glyphs to strokeArea
    }

    /** Bounds of what [draw] paints. */
    fun bounds(text: String, fill: Paint, stroke: Paint?, spec: TextPathSpec, letters: LetterScaleSpec? = null): RectF {
        val (_, res) = result(text, fill, spec, letters) ?: return RectF()
        return bounds(res, spec, fill, stroke)
    }

    private fun bounds(res: PathTextResult, spec: TextPathSpec, fill: Paint, stroke: Paint?): RectF {
        val out = RectF()
        val fillPad = strokePad(fill)
        val outlinePad = max(fillPad, strokePad(stroke))
        if (res.path != null) {
            val r = RectF(res.pathBounds)
            r.inset(-(outlinePad + AA_PAD), -(outlinePad + AA_PAD))
            out.union(r)
        }
        if (res.placements.isNotEmpty()) {
            // Rigid emoji in bent text get no outline; rotated letters do.
            val pad = AA_PAD + if (spec.mode == TextPathMode.ROTATE) outlinePad else fillPad
            val r = RectF(res.placementBounds)
            r.inset(-pad, -pad)
            out.union(r)
        }
        return out
    }

    /** How far a paint's stroke reaches beyond the outline it strokes. */
    private fun strokePad(p: Paint?): Float {
        if (p == null || p.style == Paint.Style.FILL) return 0f
        val half = max(p.strokeWidth, 1f) / 2f
        return if (p.strokeJoin == Paint.Join.MITER) half * max(1f, p.strokeMiter) else half
    }

    // ------------------------------------------------------------------ layout

    private class Run(val start: Int, val limit: Int, val rtl: Boolean)

    private fun buildLayout(line: String, source: Paint, letters: LetterScaleSpec? = null): PathTextLayout {
        val p = Paint(source).apply {
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            shader = null
            pathEffect = null
            maskFilter = null
            colorFilter = null
            xfermode = null
        }
        val len = line.length
        val rect = Rect()
        p.getTextBounds("H", 0, 1, rect)
        val capHeight = if (rect.height() > 0) -rect.top.toFloat() else 0.7f * p.textSize

        // Grapheme cluster boundaries.
        val cuts = ArrayList<Int>()
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(line)
        var b = bi.first()
        while (b != BreakIterator.DONE) { cuts += b; b = bi.next() }

        // Directional runs in visual order.
        val chars = line.toCharArray()
        val runs = if (!Bidi.requiresBidi(chars, 0, len)) {
            listOf(Run(0, len, false))
        } else {
            val bidi = Bidi(line, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
            val count = bidi.runCount
            val levels = ByteArray(count) { bidi.getRunLevel(it).toByte() }
            val order = Array<Any>(count) { it }
            Bidi.reorderVisually(levels, 0, order, 0, count)
            order.map { o -> val i = o as Int; Run(bidi.getRunStart(i), bidi.getRunLimit(i), bidi.getRunLevel(i) % 2 == 1) }
        }
        // v1.6 scaled letters (left-to-right text only: the caller checked the script).
        if (letters != null && runs.size == 1 && !runs[0].rtl) return buildScaled(line, p, letters, cuts, capHeight)

        val clusters = ArrayList<PathCluster>()
        val tmp = Path()
        var x = 0f
        for (run in runs) {
            val bounds = ArrayList<Int>()
            bounds += run.start
            for (c in cuts) if (c > run.start && c < run.limit) bounds += c
            bounds += run.limit
            val adv = FloatArray(bounds.size) { i -> p.getRunAdvance(line, run.start, run.limit, run.start, run.limit, run.rtl, bounds[i]) }
            val runWidth = adv.last()
            val indices = if (run.rtl) (bounds.size - 2 downTo 0) else (0 until bounds.size - 1)
            for (i in indices) {
                val cs = bounds[i]
                val ce = bounds[i + 1]
                val x0 = if (run.rtl) x + runWidth - adv[i + 1] else x + adv[i]
                val x1 = if (run.rtl) x + runWidth - adv[i] else x + adv[i + 1]
                val blank = (cs until ce).all { Character.isWhitespace(line[it]) || Character.isSpaceChar(line[it]) }
                p.getTextBounds(line, cs, ce, rect)
                val rigid = !blank && (isEmojiCluster(line, cs, ce) || (hasNonLatin(line, cs, ce) && hasNoOutline(p, line, cs, ce, tmp)))
                clusters += PathCluster(cs, ce, run.start, run.limit, run.rtl, x0, x1, blank, rigid, RectF(rect))
            }
            x += runWidth
        }
        return PathTextLayout(line, x, capHeight, clusters, p)
    }

    /**
     * The layout of [line] with scaled letters (v1.6 §3.5, "Text on a path"): cluster k takes its
     * advance times `f(k)` ([LetterRamp]), is drawn at `size · f(k)` and, for Center / Top, moved
     * off the path along its normal (layout y, negative = up: `−capH·(1 − f)/2` / `−capH·(1 − f)`),
     * so the bent outline and the rotated letters both follow it. [capHeight] is at full size.
     */
    private fun buildScaled(line: String, p: Paint, letters: LetterScaleSpec, cuts: List<Int>, capHeight: Float): PathTextLayout {
        val len = line.length
        val ramp = LetterRamp.of(line, letters)
        val bounds = ArrayList<Int>(cuts.size + 2)
        bounds += 0
        for (c in cuts) if (c in 1 until len) bounds += c
        bounds += len
        val adv = FloatArray(bounds.size) { i -> p.getRunAdvance(line, 0, len, 0, len, false, bounds[i]) }
        val base = p.textSize
        val rect = Rect()
        val tmp = Path()
        val clusters = ArrayList<PathCluster>(bounds.size)
        var x = 0f
        try {
            for (i in 0 until bounds.size - 1) {
                val cs = bounds[i]
                val ce = bounds[i + 1]
                val f = ramp.factors[cs]
                val w = (adv[i + 1] - adv[i]) * f
                val dy = when (letters.align) {
                    LetterScaleAlign.CENTER -> -capHeight * (1f - f) / 2f
                    LetterScaleAlign.TOP -> -capHeight * (1f - f)
                    LetterScaleAlign.BASELINE -> 0f
                }
                val blank = (cs until ce).all { Character.isWhitespace(line[it]) || Character.isSpaceChar(line[it]) }
                p.textSize = base * f
                p.getTextBounds(line, cs, ce, rect)
                val rigid = !blank && (isEmojiCluster(line, cs, ce) || (hasNonLatin(line, cs, ce) && hasNoOutline(p, line, cs, ce, tmp)))
                val ink = RectF(rect).apply { offset(0f, dy) }
                clusters += PathCluster(cs, ce, cs, ce, false, x, x + w, blank, rigid, ink, f, dy)
                x += w
            }
        } finally {
            p.textSize = base
        }
        return PathTextLayout(line, x, capHeight, clusters, p, lettersScaled = true)
    }

    private fun hasNonLatin(s: String, start: Int, end: Int): Boolean = (start until end).any { s[it].code >= 0x2000 }

    private fun hasNoOutline(p: Paint, s: String, start: Int, end: Int, tmp: Path): Boolean {
        tmp.rewind()
        p.getTextPath(s, start, end, 0f, 0f, tmp)
        return tmp.isEmpty
    }

    /** Emoji (color glyphs, usually without an outline): pictographs, flags, keycaps, emoji sequences. */
    internal fun isEmojiCluster(s: CharSequence, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val cp = Character.codePointAt(s, i)
            if (cp == 0xFE0F || cp == 0x200D || cp == 0x20E3) return true
            if (cp in 0x1F000..0x1FAFF) return true
            if (Build.VERSION.SDK_INT >= 28 && UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Flattens every contour of [path] into a polygon (x, y pairs) within [tolerance] px. Contours
     * are walked one by one (PathMeasure), so no contour break has to be guessed from the output.
     */
    fun flatten(path: Path, tolerance: Float): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        if (path.isEmpty) return out
        val pm = PathMeasure(path, false)
        val seg = Path()
        do {
            val len = pm.length
            if (len > 0f) {
                seg.rewind()
                if (pm.getSegment(0f, len, seg, true)) {
                    val a = seg.approximate(tolerance)
                    val n = a.size / 3
                    val pts = FloatArray(n * 2)
                    var m = 0
                    for (i in 0 until n) {
                        val x = a[3 * i + 1]
                        val y = a[3 * i + 2]
                        if (m >= 2 && x == pts[m - 2] && y == pts[m - 1]) continue
                        pts[m++] = x
                        pts[m++] = y
                    }
                    if (m >= 6) out += pts.copyOf(m)
                }
            }
        } while (pm.nextContour())
        return out
    }
}
