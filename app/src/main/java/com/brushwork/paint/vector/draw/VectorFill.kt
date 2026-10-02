package com.brushwork.paint.vector.draw

import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.FillSettings
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.MarchingSquares
import com.brushwork.paint.tools.select.PixelSnapshot
import com.brushwork.paint.tools.select.Region
import com.brushwork.paint.tools.select.RegionParams
import com.brushwork.paint.tools.select.SelectionJobs
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max

/**
 * The bucket on a vector layer (v1.5 §4.9, owned by A3), called first by FillTool on vector
 * layers:
 * - a tap inside a closed path or shape gives it a fill of the main color (one is added when it
 *   has none); a tap on a stroke, an open path or an outline recolors that line. ONE undo step
 *   "Fill object".
 * - a tap on an empty area fills the area enclosed there (the bucket's own options: tolerance,
 *   this layer / all layers, gap closing, expand), traced into one filled path (even-odd, so its
 *   holes stay open) placed UNDER the line art, at the bottom of the layer. ONE undo step "Fill".
 * Painting the layer's mask is left to the raster bucket.
 */
object VectorFill {
    const val FILL_OBJECT_LABEL = "Fill object"
    const val FILL_AREA_LABEL = "Fill"

    /**
     * How far beside a line's paint (dp on screen) a tap still recolors it. Small: a tap beside
     * line art is most often meant to fill the area the lines enclose (small areas, an eye, the
     * gaps between strands of hair, or a whole drawing zoomed out, are mostly within a few dp of
     * a line), and the raster bucket recolors a line only when the tap is on it too.
     */
    internal const val TAP_TOLERANCE_DP = 4f

    /** Simplification of a traced area's outline (document px). */
    private const val TRACE_EPSILON = 0.75f

    /** Handles a bucket tap at [p] (document px) on the active vector layer; false = not handled. */
    fun tap(c: EditorController, p: Vec2): Boolean {
        val layer = c.activeLayer
        val content = layer.vector ?: return false
        if (c.editTargetOf(layer) != EditTarget.CONTENT) return false
        val state = VectorDrawState.of(c)
        // A traced fill is still being computed: this tap does nothing.
        if (state.filling) return true
        if (!c.checkEditable(layer)) return true
        val t = c.viewTransform
        val tol = t.screenToDocLength(t.dp(TAP_TOLERANCE_DP))
        val hit = FillHits.find(content, p, tol, state.targets)
        if (hit != null) {
            recolor(c, layer, content, p, tol, hit, fillColor(c))
            return true
        }
        fillEnclosed(c, layer, content, p)
        return true
    }

    /**
     * The main color as objects get it: opaque, and as the document shows it (gray or black and
     * white documents), like the brush's strokes.
     */
    private fun fillColor(c: EditorController): Int =
        ColorModeOps.displayColor(c.color or 0xFF000000.toInt(), c.doc.colorMode) or 0xFF000000.toInt()

    /**
     * Recolors the part of the object [hit] found at [p] in [content] (one step "Fill object"),
     * nothing when it already has [color]. Applied after the eraser / bucket updates still on
     * their way: when the layer changed by then, what is under [p] then is recolored (nothing
     * when it was erased). False when nothing changes.
     */
    internal fun recolor(c: EditorController, layer: Layer, content: VectorContent, p: Vec2, tol: Float, hit: FillHit, color: Int): Boolean {
        if (recolored(hit.obj, hit.part, color) == null) return false
        val state = VectorDrawState.of(c)
        state.serial(layer) { finish ->
            val current = layer.vector
            val now = when {
                current == null || c.doc.indexOf(layer) < 0 -> null
                current === content -> hit
                else -> FillHits.find(current, p, tol, state.targets)
            }
            val after = now?.let { recolored(it.obj, it.part, color) }
            if (current == null || now == null || after == null) {
                finish()
            } else {
                state.update(c, layer, current.replaced(mapOf(now.obj.id to listOf(after))), FILL_OBJECT_LABEL) { finish() }
            }
        }
        return true
    }

    /** [o] with its fill ([FillPart.FILL]) or line ([FillPart.LINE]) of [color]; null when unchanged. */
    internal fun recolored(o: VObject, part: FillPart, color: Int): VObject? = when (o) {
        is VStroke -> if (o.color == color) null else o.copy(color = color)
        is VPath -> when (part) {
            FillPart.LINE -> o.stroke?.let { st -> if (st.color == color) null else o.copy(stroke = st.copy(color = color)) }
            FillPart.FILL -> if ((o.fill as? VPaint.Solid)?.color == color) null else o.copy(fill = VPaint.Solid(color))
        }
        is VShape -> {
            val sh = o.shape
            when (part) {
                FillPart.LINE -> if (sh.strokeColor == color) null else {
                    // The fill keeps its color; it only "follows" the line when they are the same.
                    o.copy(shape = sh.copy(strokeColor = color, fillFollowsColor = sh.fillColor == color))
                }
                FillPart.FILL -> {
                    if (sh.style.fill && sh.fillColor == color) null else {
                        val style = if (sh.style.fill) sh.style else ShapeStyle.STROKE_FILL
                        o.copy(shape = sh.copy(style = style, fillColor = color, fillFollowsColor = color == sh.strokeColor))
                    }
                }
            }
        }
    }

    /**
     * Fills the area around [p] enclosed by the layer's (or the canvas') pixels, in the
     * background: traced (marching squares + simplification) into a filled path inserted at the
     * bottom of the layer, applied only if the layer did not change meanwhile.
     */
    private fun fillEnclosed(c: EditorController, layer: Layer, content: VectorContent, p: Vec2) {
        val doc = c.doc
        val ix = floor(p.x).toInt()
        val iy = floor(p.y).toInt()
        if (ix !in 0 until doc.width || iy !in 0 until doc.height) return
        val sel = c.selection
        if (sel != null && sel.alphaAt(ix, iy) == 0) { c.toast("Tap inside the selection to fill it"); return }
        val s = (c.tools[ToolId.FILL] as? FillTool)?.settings ?: FillSettings()
        val snapshot = try {
            PixelSnapshot.take(c, s.source, layer.bitmap)
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory to fill")
            return
        }
        val color = fillColor(c)
        val w = doc.width
        val h = doc.height
        // Grown by at least a pixel: the fill goes under the line art, so it reaches under its
        // soft edge instead of leaving a light seam along it.
        val params = RegionParams(s.tolerance, contiguous = true, gapClose = s.gapClose, expand = max(1, s.expand), antiAlias = s.antiAlias)
        val state = VectorDrawState.of(c)
        state.filling = true
        val job = c.scope.launch {
            val self = coroutineContext[Job]
            try {
                val subpaths = withContext(Dispatchers.Default) {
                    try {
                        val region = FillTool.computeFill(snapshot, ix, iy, params, sel, null) { self?.isActive == false }
                        region?.let { trace(it) { self?.isActive == false } }
                    } finally {
                        snapshot.recycle()
                    }
                }
                if (subpaths.isNullOrEmpty()) {
                    if (self?.isActive != false) c.toast("Nothing to fill here")
                    return@launch
                }
                val path = VPath(0, subpaths = subpaths, polyline = true, fillRule = VFillRule.EVENODD, fill = VPaint.Solid(color))
                // After the eraser / bucket updates still on their way: the area was traced from
                // the layer as it was, so it is placed only if the layer is still that.
                state.serial(layer) { finish ->
                    when {
                        doc.indexOf(layer) < 0 || layer.vector !== content || doc.width != w || doc.height != h -> {
                            c.toast("The layer changed while filling. Tap again.")
                            finish()
                        }
                        !c.checkEditable(layer) -> finish()
                        else -> state.update(c, layer, atBottom(content, path), FILL_AREA_LABEL) { finish() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                c.toast("Not enough memory to fill this area")
            } catch (e: Exception) {
                c.toast("Fill failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                state.filling = false
            }
        }
        SelectionJobs.showBusyIfSlow(c, job, "Filling…")
    }

    /** [content] with [o] inserted at the bottom (under everything), with a new id. */
    internal fun atBottom(content: VectorContent, o: VObject): VectorContent {
        val id = content.nextId
        return content.copy(objects = listOf(o.withId(id)) + content.objects, nextId = id + 1)
    }

    /**
     * The outlines of [region]'s coverage (threshold at half) as closed sub-paths in document px,
     * simplified (RDP [TRACE_EPSILON]); specks below a pixel are dropped. Null when [cancelled].
     */
    internal fun trace(region: Region, cancelled: () -> Boolean = { false }): List<VSubpath>? {
        val polys = MarchingSquares.contours(region.coverage, region.width, region.height, cancelled) ?: return null
        val out = ArrayList<VSubpath>(polys.size)
        // Sample (i, j) of the window is the pixel centered at (x0 + i + 0.5, y0 + j + 0.5).
        val ox = region.x0 + 0.5f
        val oy = region.y0 + 0.5f
        for (poly in polys) {
            if (cancelled()) return null
            val simple = MarchingSquares.simplifyClosed(poly, TRACE_EPSILON)
            if (simple.size < 6 || MarchingSquares.area(simple) < 1f) continue
            val anchors = ArrayList<VAnchor>(simple.size / 2)
            var k = 0
            while (k < simple.size) {
                anchors += VAnchor(simple[k] + ox, simple[k + 1] + oy, sharp = true)
                k += 2
            }
            out += VSubpath(anchors, closed = true)
        }
        return out
    }
}

/** What a bucket tap hits: the fill (inside) or the line of an object. */
internal enum class FillPart { FILL, LINE }

internal class FillHit(val obj: VObject, val part: FillPart)

/** Bucket hit tests on vector objects (topmost first). */
internal object FillHits {
    /**
     * The topmost object at [p] and which part of it: a closed path or box shape [FillPart.FILL]
     * when [p] is inside it (also without a fill), its outline [FillPart.LINE] when [p] is on it
     * (within [tol] outside the object; exactly on the paint inside it), strokes and open lines
     * [FillPart.LINE] within [tol] of their paint.
     */
    fun find(content: VectorContent, p: Vec2, tol: Float, cache: TargetCache): FillHit? {
        val objs = content.objects
        for (i in objs.indices.reversed()) {
            val o = objs[i]
            val b = cache.boundsOf(o)
            if (p.x < b[0] - tol || p.x > b[2] + tol || p.y < b[1] - tol || p.y > b[3] + tol) continue
            val part = partAt(o, cache.of(o), p, tol) ?: continue
            return FillHit(o, part)
        }
        return null
    }

    private fun partAt(o: VObject, tg: EraseTarget, p: Vec2, tol: Float): FillPart? {
        val d = distance(tg.lines, p)
        return when (o) {
            is VStroke -> if (d <= tg.reach + tol) FillPart.LINE else null
            is VPath -> {
                val area = if (tg.fills.isNotEmpty()) tg.fills else tg.lines.filter { it.closed }
                val inside = area.isNotEmpty() && EraseMath.inside(area, p.x, p.y, tg.evenOdd)
                val half = EraseTarget.halfWidth(o)
                when {
                    o.stroke != null && d <= half + (if (inside) 0f else tol) -> FillPart.LINE
                    inside -> FillPart.FILL
                    else -> null
                }
            }
            is VShape -> {
                val sh = o.shape
                if (sh.type.isLineLike) {
                    if (ShapeOutlines.hits(sh, p, tol)) FillPart.LINE else null
                } else {
                    val outline = tg.lines.filter { it.closed }
                    val inside = outline.isNotEmpty() && EraseMath.inside(outline, p.x, p.y, false)
                    when {
                        sh.strokes && d <= ShapeOutlines.reach(sh) + (if (inside) 0f else tol) -> FillPart.LINE
                        inside -> FillPart.FILL
                        else -> null
                    }
                }
            }
        }
    }

    /** Distance from [p] to the nearest centerline. */
    private fun distance(lines: List<FlatLine>, p: Vec2): Float {
        var best = Float.POSITIVE_INFINITY
        for (line in lines) {
            if (line.n == 1) best = minOf(best, kotlin.math.hypot(p.x - line.xs[0], p.y - line.ys[0]))
            for (i in 0 until line.segments) {
                val j = if (i + 1 == line.n) 0 else i + 1
                best = minOf(best, EraseMath.pointSegmentDistance(p.x, p.y, line.xs[i], line.ys[i], line.xs[j], line.ys[j]))
            }
        }
        return best
    }
}
