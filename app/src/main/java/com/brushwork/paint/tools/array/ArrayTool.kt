package com.brushwork.paint.tools.array

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Array tool (v1.7 item 3, §3.3; area E): the canvas handles of the active layer's live array
 * (the Line arrow, the Circle centre, the Curve guide's points, the Transform pivot and step
 * arrow) and its options sheet. Also opened by the layer ⋮ "Edit array" and by every way of
 * making an array.
 *
 * - Dragging a handle, or a slider or field of the sheet ([preview] / [commitPreview]),
 *   previews live (I7): the compositor draws the layer as a source proxy (at most
 *   [PROXY_MAX] px) once per copy through `controller.renderOverride`; the release commits ONE
 *   step "Edit array" ([ArrayOps.edit]). A discrete control commits at once ([commit]). A
 *   vector array's re-render may land later: the preview stays up until it does, and
 *   [rendering] shows "Rendering array…" meanwhile.
 * - Curve guides: "Draw guide" ([startGuideInput] DRAW) takes the next finger stroke, fitted by
 *   [GuideEditor]; "Use a path" (PICK) takes the first subpath of the vector path tapped next
 *   on any visible layer, copied.
 * - Copies are not hit-testable: a touch away from the handles does nothing.
 *
 * On a layer without an array it draws nothing and takes no touches. Opening it on a layer whose
 * raster source is being edited runs "Finish source edit" (§3.3 a). Nothing is ever pending
 * ([hasPendingWork] stays false); a two-finger tap rolls a preview back through
 * [historyMark] / [rollbackHistory], then undoes as usual.
 */
class ArrayTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.ARRAY

    /** What the next canvas gesture does for a CURVE guide. */
    enum class GuideInput { NONE, DRAW, PICK }

    /** The text array waiting for the "Apply turns the text into pixels" confirmation (Compose state). */
    var pendingTextApply by mutableStateOf<Layer?>(null)
        private set

    /** The Array sheet is open (Compose state; opened with the tool on an arrayed layer). */
    var sheetOpen by mutableStateOf(false)

    /** What the next canvas gesture does for the guide (Compose state). */
    var guideInput by mutableStateOf(GuideInput.NONE)
        private set

    /** The spec being previewed (a drag, a slider) or committed, null when none (Compose state). */
    var previewSpec by mutableStateOf<ArraySpec?>(null)
        private set

    /** A vector array's re-render is on its way (Compose state: "Rendering array…"). */
    var rendering by mutableStateOf(false)
        private set

    /** The layer the tool last worked on (a change of the active layer re-targets it). */
    private var lastLayer: Layer? = null

    /** True while [ArrayOps] pauses this tool around this tool's own edit. */
    private var selfEdit = false

    /** The live preview, or null. */
    private var preview: Preview? = null

    /** The commit whose [ArrayOps.edit] completion ends the preview (the latest one). */
    private var committing: Any? = null

    /** The spec of that commit. */
    private var inFlight: ArraySpec? = null

    /** The handle drag in progress. */
    private var drag: Drag? = null

    /** The guide stroke being drawn (document px). */
    private val stroke = ArrayList<Vec2>()

    /** Where a PICK tap went down. */
    private var pickDown: Vec2? = null

    /** The source proxy of the last preview, reused while the source is unchanged. */
    private var proxyCache: Proxy? = null

    private class Drag(val layer: Layer, val handle: ArrayHandles.Handle, val base: ArraySpec, val source: RectF, val offset: Vec2)

    /** The active layer when it holds an array (and is not a folder), else null. */
    val target: Layer?
        get() = controller.activeLayer.takeIf { it.array != null && !it.isFolder }

    /** The spec the sheet shows: the one previewed or committing, else the active layer's. */
    fun shownSpec(): ArraySpec? = previewSpec ?: target?.array?.spec

    // ------------------------------------------------------------------ the sheet's actions

    /** Asks "Apply turns the text into pixels" before the text array of [layer] is applied. */
    fun askApplyText(layer: Layer) {
        pendingTextApply = layer
    }

    /** The confirmation's answer: [apply] applies the text array (one step "Apply array"). */
    fun answerTextApply(apply: Boolean) {
        val layer = pendingTextApply ?: return
        pendingTextApply = null
        if (apply) ArrayOps.applyNow(controller, layer)
    }

    /**
     * Previews [spec] on the active layer's array (a slider or field moving): nothing is
     * recorded until [commitPreview].
     */
    fun preview(spec: ArraySpec) {
        val layer = target ?: return
        previewOn(layer, spec)
    }

    /** Commits what [preview] shows as ONE step "Edit array" (a slider released, a field done). */
    fun commitPreview() {
        val p = preview ?: return
        // Already on its way (a vector re-render): the same release twice records nothing more.
        if (committing != null && inFlight == p.spec) return
        commitOn(p.layer, p.spec)
    }

    /** Sets the active layer's array to [spec] as ONE step "Edit array" (a chip, a toggle, a segment). */
    fun commit(spec: ArraySpec) {
        val layer = target ?: return
        commitOn(layer, spec)
    }

    /** The guide input for the next canvas gesture ([GuideInput.NONE] stops waiting). */
    fun startGuideInput(input: GuideInput) {
        stroke.clear()
        pickDown = null
        guideInput = if (target == null) GuideInput.NONE else input
        controller.invalidateOverlay()
    }

    /** "Apply array" on the active layer (a text array asks first). */
    fun applyArray() {
        val layer = target ?: return
        dropPreview()
        ArrayOps.apply(controller, layer)
    }

    /** "Remove array" on the active layer. */
    fun removeArray() {
        val layer = target ?: return
        dropPreview()
        ArrayOps.remove(controller, layer)
    }

    /** "Edit source pixels" on the active layer, then the last painting tool to paint the source with. */
    fun editSource() {
        val layer = target ?: return
        dropPreview()
        if (ArrayOps.editSource(controller, layer)) {
            sheetOpen = false
            controller.selectTool(controller.lastPaintTool)
        }
    }

    /** "Finish source edit" on the active layer. */
    fun finishSource() {
        val layer = target ?: return
        ArrayOps.finishSource(controller, layer)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onActivate() {
        if (selfEdit) return
        val layer = controller.activeLayer
        val prev = lastLayer
        lastLayer = layer
        // Opened on another layer (not a pause around an operation on the same one).
        if (prev !== layer) {
            dropPreview()
            stroke.clear()
            pickDown = null
            guideInput = GuideInput.NONE
            finishSourceOf(layer)
            if (layer.array != null && !layer.isFolder) sheetOpen = true
        }
    }

    override fun onSelected() {
        finishSourceOf(controller.activeLayer)
        sheetOpen = target != null
    }

    override fun onDeactivate() {
        super.onDeactivate()
        if (selfEdit) return
        pendingTextApply = null
        drag = null
        stroke.clear()
        pickDown = null
        guideInput = GuideInput.NONE
        if (committing == null) dropPreview()
    }

    override fun onDispose() {
        dropPreview()
        proxyCache = null
    }

    override fun historyMark(): Any? = preview?.takeIf { committing == null }

    override fun rollbackHistory(mark: Any?) {
        // A preview started after the mark (a finger on a slider when the second one landed).
        if (committing == null && preview != null && preview !== mark) dropPreview()
    }

    // ------------------------------------------------------------------ touches

    override fun onDown(p: ToolPoint) {
        val layer = target ?: return
        val spec = layer.array?.spec ?: return
        if (spec.editingSource) return
        val at = Vec2(p.x, p.y)
        when (guideInput) {
            GuideInput.DRAW -> {
                if (!controller.checkUsable(layer)) return
                stroke.clear()
                stroke += at
                controller.invalidateOverlay()
                return
            }
            GuideInput.PICK -> {
                pickDown = at
                return
            }
            GuideInput.NONE -> Unit
        }
        val source = sourceOf(layer.dataSnapshot()) ?: return
        val base = previewSpec?.takeIf { preview?.layer === layer } ?: spec
        val h = ArrayHandles.hit(ArrayHandles.handles(base, source), at, grabRadius()) ?: return
        if (!controller.checkUsable(layer)) return
        drag = Drag(layer, h, base, source, h.pos - at)
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        val at = Vec2(p.x, p.y)
        if (guideInput == GuideInput.DRAW && stroke.isNotEmpty()) {
            if (stroke.last().distanceTo(at) >= controller.viewTransform.screenToDocLength(controller.viewTransform.dp(2f))) {
                stroke += at
                controller.invalidateOverlay()
            }
            return
        }
        val d = drag ?: return
        previewOn(d.layer, ArrayHandles.dragged(d.base, d.source, d.handle, at + d.offset))
        controller.invalidateOverlay()
    }

    override fun onUp(p: ToolPoint) {
        val at = Vec2(p.x, p.y)
        val layer = target
        if (guideInput == GuideInput.DRAW && stroke.isNotEmpty()) {
            stroke += at
            val guide = GuideEditor.fitStroke(stroke)
            stroke.clear()
            controller.invalidateOverlay()
            val spec = layer?.array?.spec ?: return
            guideInput = GuideInput.NONE
            if (guide != null) commitOn(layer, spec.copy(mode = ArrayMode.CURVE, guide = guide))
            return
        }
        if (guideInput == GuideInput.PICK) {
            val down = pickDown ?: return
            pickDown = null
            val t = controller.viewTransform
            if (down.distanceTo(at) > t.screenToDocLength(t.dp(12f))) return
            val spec = layer?.array?.spec ?: return
            val guide = pickPath(at)
            if (guide == null) {
                controller.toast(NO_PATH)
                return
            }
            guideInput = GuideInput.NONE
            commitOn(layer, spec.copy(mode = ArrayMode.CURVE, guide = guide))
            return
        }
        val d = drag ?: return
        drag = null
        previewOn(d.layer, ArrayHandles.dragged(d.base, d.source, d.handle, at + d.offset))
        commitPreview()
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        drag = null
        stroke.clear()
        pickDown = null
        if (committing == null) dropPreview()
        controller.invalidateOverlay()
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val layer = target ?: return
        val spec = layer.array?.spec ?: return
        if (spec.editingSource) return
        if (stroke.size >= 2) {
            ArrayHandles.drawStroke(canvas, stroke, t)
            return
        }
        val shown = previewSpec?.takeIf { preview?.layer === layer } ?: spec
        val source = sourceOf(layer.dataSnapshot()) ?: return
        ArrayHandles.draw(canvas, shown, source, t, drag?.handle)
    }

    // ------------------------------------------------------------------ internals

    /** "Finish source edit" when [layer]'s raster source is being edited (§3.3 a). */
    private fun finishSourceOf(layer: Layer) {
        if (layer.array?.spec?.editingSource == true) ArrayOps.finishSource(controller, layer)
    }

    private fun grabRadius(): Float {
        val t = controller.viewTransform
        return t.screenToDocLength(t.dp(ArrayHandles.GRAB_DP))
    }

    /** The topmost path under [p] on a visible vector layer, as a guide (copied), or null. */
    private fun pickPath(p: Vec2): com.brushwork.paint.vector.VSubpath? {
        val doc = controller.doc
        val tol = grabRadius() / 2f
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            if (l.vector == null || !doc.effectiveVisible(l)) continue
            val o = controller.vectors.hitTest(l, p, tol) as? VPath ?: continue
            GuideEditor.fromPath(o)?.let { return it }
        }
        return null
    }

    private fun previewOn(layer: Layer, spec: ArraySpec) {
        val a = layer.array ?: return
        if (a.spec.editingSource) return
        val p = preview?.takeIf { it.layer === layer } ?: startPreview(layer) ?: return
        p.update(spec.sanitized())
        previewSpec = p.spec
    }

    private fun commitOn(layer: Layer, spec: ArraySpec) {
        val a = layer.array ?: return
        val s = spec.sanitized()
        val vector = ArraySources.kindOf(layer.dataSnapshot()) == ArraySources.Kind.VECTOR
        // A vector array's cache may land later: the preview shows the new copies meanwhile.
        if (vector && s != a.spec && !a.spec.editingSource) previewOn(layer, s)
        val token = Any()
        committing = token
        inFlight = s
        if (vector) rendering = true
        selfEdit = true
        try {
            ArrayOps.edit(controller, layer, s, ArrayLabels.EDIT) { _ ->
                if (committing === token) {
                    committing = null
                    inFlight = null
                    rendering = false
                    dropPreview()
                }
            }
        } finally {
            selfEdit = false
        }
        controller.invalidateOverlay()
    }

    /** Ends the preview (nothing recorded) and shows the layer's own pixels again. */
    private fun dropPreview() {
        val p = preview ?: run { previewSpec = null; return }
        preview = null
        previewSpec = null
        if (controller.renderOverride === p.override) controller.renderOverride = null
        val r = RectF(p.drawn)
        ArrayDraw.cacheBounds(p.layer.dataSnapshot())?.let { r.union(it) }
        controller.invalidateDoc(outward(r))
        controller.invalidateOverlay()
    }

    private fun startPreview(layer: Layer): Preview? {
        val d = layer.dataSnapshot()
        val a = d.array ?: return null
        val source = sourceOf(d) ?: return null
        val proxy = proxyOf(layer, d) ?: return null
        dropPreview()
        val p = Preview(layer, source, proxy, a.spec)
        preview = p
        controller.renderOverride = p.override
        return p
    }

    /** The source bounds of [d] when usable (finite, not empty). */
    private fun sourceOf(d: LayerData): RectF? {
        val s = ArrayDraw.sourceBounds(d) ?: return null
        if (s.isEmpty || !s.left.isFinite() || !s.top.isFinite() || !s.right.isFinite() || !s.bottom.isFinite()) return null
        return s
    }

    /** The source of [d] alone as a bitmap of at most [PROXY_MAX] px a side (cached while the source is the same). */
    private fun proxyOf(layer: Layer, d: LayerData): Proxy? {
        val key = ProxyKey(layer, d.text, d.shape, d.vector, d.array?.pixels)
        proxyCache?.takeIf { it.key == key }?.let { return it }
        val made = try { makeProxy(d) } catch (e: OutOfMemoryError) { null } ?: return null
        val proxy = Proxy(key, made.first, made.second)
        proxyCache = proxy
        return proxy
    }

    private fun makeProxy(d: LayerData): Pair<Bitmap, RectF>? {
        val kind = ArraySources.kindOf(d) ?: return null
        val source = sourceOf(d) ?: return null
        if (kind == ArraySources.Kind.PIXELS) {
            val px: ArrayPixels = d.array?.pixels ?: return null
            if (px.bitmap.isRecycled) return null
            val rect = RectF(px.left.toFloat(), px.top.toFloat(), (px.left + px.bitmap.width).toFloat(), (px.top + px.bitmap.height).toFloat())
            val side = max(px.bitmap.width, px.bitmap.height)
            if (side <= PROXY_MAX) return px.bitmap to rect
            val f = PROXY_MAX.toFloat() / side
            val scaled = Bitmap.createScaledBitmap(px.bitmap, max(1, (px.bitmap.width * f).roundToInt()), max(1, (px.bitmap.height * f).roundToInt()), true)
            return scaled to rect
        }
        val doc = controller.doc
        // Text glyphs may reach past the measured bounds (italics, accents): a margin.
        val margin = if (kind == ArraySources.Kind.TEXT) max(4f, 0.1f * max(source.width(), source.height())) else 4f
        val rect = RectF(source).apply { inset(-margin, -margin) }
        val region = outward(rect)
        rect.set(region)
        val draw: (Canvas) -> Unit = when (kind) {
            ArraySources.Kind.VECTOR -> {
                val v: VectorContent = d.vector ?: return null
                val document = Rect(0, 0, doc.width, doc.height)
                fun(cv: Canvas) { ArrayOps.renderTiles(cv, v, region, document) }
            }
            else -> ArraySources.sourceDraw(d, doc.colorMode, doc.width, doc.height) ?: return null
        }
        val side = max(region.width(), region.height())
        if (side <= 0) return null
        val f = min(1f, PROXY_MAX.toFloat() / side)
        val bmp = BitmapUtils.createLayerBitmap(max(1, (region.width() * f).roundToInt()), max(1, (region.height() * f).roundToInt()))
        Canvas(bmp).apply {
            scale(bmp.width.toFloat() / region.width(), bmp.height.toFloat() / region.height())
            translate(-region.left.toFloat(), -region.top.toFloat())
            draw(this)
        }
        return bmp to rect
    }

    private data class ProxyKey(val layer: Layer, val text: String?, val shape: String?, val vector: VectorContent?, val pixels: ArrayPixels?) {
        // Identity for the large parts (the content and pixels are immutable once published).
        override fun equals(other: Any?): Boolean = other is ProxyKey && other.layer === layer && other.text == text &&
            other.shape == shape && other.vector === vector && other.pixels === pixels

        override fun hashCode(): Int = System.identityHashCode(layer)
    }

    private class Proxy(val key: ProxyKey, val bitmap: Bitmap, val rect: RectF)

    /** What the override draws: the spec and its matrices, swapped as one (the compositor reads it). */
    private class Frame(val spec: ArraySpec, val matrices: List<FloatArray>)

    /**
     * The live preview of [layer]: the compositor draws [proxy] once per matrix of the previewed
     * spec instead of the layer's bitmap (the copies k = N − 1 down to 0, the source on top, as
     * `ArrayDraw` draws the cache).
     */
    private inner class Preview(val layer: Layer, val source: RectF, val proxy: Proxy, spec: ArraySpec) {
        @Volatile private var frame = Frame(spec, ArrayLayout.matrices(spec, source))

        /** What the preview covers now (document px), from the layer's own cache at first. */
        val drawn = RectF(ArrayDraw.cacheBounds(layer.dataSnapshot()) ?: RectF(source))

        val spec: ArraySpec get() = frame.spec

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val m = Matrix()

        /** False until the first [update] (which also hides the layer's old copies). */
        private var drawnOnce = false

        val override = object : LayerRenderOverride {
            override val layer: Layer get() = this@Preview.layer

            override fun drawContent(canvas: Canvas): Boolean {
                val f = frame
                val bmp = proxy.bitmap
                if (bmp.isRecycled) return false
                for (k in f.matrices.size - 1 downTo 0) {
                    val v = f.matrices[k]
                    if (v.any { !it.isFinite() }) continue
                    m.setValues(v)
                    canvas.save()
                    canvas.concat(m)
                    canvas.drawBitmap(bmp, null, proxy.rect, paint)
                    canvas.restore()
                }
                return true
            }
        }

        fun update(s: ArraySpec) {
            if (s == frame.spec && drawnOnce) return
            drawnOnce = true
            val ms = ArrayLayout.matrices(s, source)
            frame = Frame(s, ms)
            val cover = coverOf(ms, proxy.rect)
            val dirty = RectF(drawn).apply { union(cover) }
            drawn.set(cover)
            // The first frame also hides the layer's old copies.
            controller.invalidateDoc(outward(dirty))
        }
    }

    private companion object {
        /** The largest side of the source proxy a preview draws (§3.3 c). */
        const val PROXY_MAX = 1024

        /** The tap of "Use a path" found no path. */
        const val NO_PATH = "Tap a vector path to use it as the guide"

        /** [r] mapped by each of [ms] (the union of the mapped corners). */
        fun coverOf(ms: List<FloatArray>, r: RectF): RectF {
            val out = RectF(r)
            val xs = floatArrayOf(r.left, r.right, r.right, r.left)
            val ys = floatArrayOf(r.top, r.top, r.bottom, r.bottom)
            for (m in ms) for (i in 0 until 4) {
                val x = m[0] * xs[i] + m[1] * ys[i] + m[2]
                val y = m[3] * xs[i] + m[4] * ys[i] + m[5]
                if (!x.isFinite() || !y.isFinite()) continue
                out.left = min(out.left, x); out.right = max(out.right, x)
                out.top = min(out.top, y); out.bottom = max(out.bottom, y)
            }
            return out
        }

        /** [r] rounded out and grown by 2 px (antialiased edges). */
        fun outward(r: RectF): Rect {
            if (!r.left.isFinite() || !r.top.isFinite() || !r.right.isFinite() || !r.bottom.isFinite()) return Rect()
            return Rect().also { r.roundOut(it); it.inset(-2, -2) }
        }
    }
}
