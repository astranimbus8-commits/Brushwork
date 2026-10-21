package com.brushwork.paint.tools.select.colorrange

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.RenderScaled
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.ColorSelectSettings
import com.brushwork.paint.tools.select.LassoCommit
import com.brushwork.paint.tools.select.PersistedOption
import com.brushwork.paint.tools.select.PixelSnapshot
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.SelectionJobs
import com.brushwork.paint.tools.select.SelectionMasks
import com.brushwork.paint.tools.select.magnetic.Loupe
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.StabilityLabels19
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

/**
 * Color select (v1.8 item 4, design §3.5): Color Range (tap one or more colors, with a
 * fuzziness) plus hue and tone presets.
 *
 * - A touch shows the loupe while the finger is down; where it lifts, the color under it is
 *   added as a sample (a transparent pixel adds nothing). The brush color and the color picker's
 *   add samples too. Up to [ColorSamples.MAX]; one more replaces the oldest. Each add and
 *   [clearSamples] is one in-tool step (the top row's Undo / Redo); the tool has pending work
 *   while it has steps since the last confirm, so ✓ selects and ✕ takes the steps back.
 * - [settings]: the preset (other than the samples, it ignores but keeps them), the fuzziness,
 *   Refer, anti-alias and invert.
 * - [preview]: what WILL be selected, tinted red at half strength over the canvas, recomputed on
 *   every change from a proxy of the Refer pixels whose Lab is cached while the tool is active
 *   ([ColorMasks]); a change while one is computed is taken up as soon as it ends (latest wins).
 * - [selectColors] (✓, the strip's check) commits ONE selection step "Color select" at full
 *   resolution, combined by [mode]; the samples stay, to refine and select again.
 *
 * Leaving the tool selects nothing: the samples stay for the next visit, their steps end.
 *
 * v1.9 (A7a step 6, R9): the proxy's pixels are rendered off the main thread at the proxy's size
 * ([RenderScaled], on the paper), never in a draw: a draw that finds the pixels changed keeps
 * drawing the last preview and asks for a new proxy, built after it. The proxy, the preview and
 * the commit are guarded: a failure is kept as a report ([CrashLog]) and said
 * ([StabilityLabels19.ERROR_TOAST], out of memory [StabilityLabels19.NO_MEMORY_FOR]); never a crash.
 */
class ColorSelectTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.COLOR_SELECT

    var settings: ColorSelectSettings by PersistedOption(controller.settings, "select.color", ColorSelectSettings.serializer(), ColorSelectSettings())
        private set

    var mode by mutableStateOf(SelectionMode.REPLACE)

    internal val samples = ColorSamples()

    /** The samples' colors, oldest first (Compose state). */
    val sampleColors: List<Int> get() = samples.list.map { it.color }

    /** The latest preview (Compose state); null until the proxy is ready. */
    var preview: ColorPreview? by mutableStateOf(null)
        private set

    /** The loupe while a finger is down, else null (tool state for tests; drawn in [drawOverlay]). */
    var loupe: Loupe? by mutableStateOf(null)
        private set

    /** True while [selectColors] computes the selection. */
    var busy by mutableStateOf(false)
        private set

    /** True once the proxy's Lab cache of the current pixels is built (Compose state). */
    var proxyReady by mutableStateOf(false)
        private set

    internal var proxy: ColorProxy? = null
        private set

    /** True while no proxy build (or one asked for by a draw), preview or commit is running (tests wait for it). */
    internal val idle: Boolean get() = proxyJob?.isActive != true && proxyRequest?.isActive != true && previewJob?.isActive != true && !busy

    /**
     * Renders the Refer pixels at the proxy's size ([ColorMasks.proxySize]), off the main thread
     * (v1.9: [RenderScaled], on the paper as the canvas shows it); a test watches or fails it.
     */
    internal var renderSource: (RenderScaled.Source) -> Bitmap = { s ->
        val (w, h) = ColorMasks.proxySize(s.width, s.height)
        RenderScaled.renderAt(s, w, h, withPaper = true)
    }

    override val hasPendingWork: Boolean get() = samples.undoCount > 0
    override val canUndoStep: Boolean get() = samples.undoCount > 0
    override val canRedoStep: Boolean get() = samples.redoCount > 0

    private var proxyKey: ProxyKey? = null
    private var proxyJob: Job? = null
    /** v1.9 (A7a): a new proxy asked for by a draw ([drawOverlay]), built after it. */
    private var proxyRequest: Job? = null
    private var previewJob: Job? = null
    private var previewWanted = false
    private var previewVersion = 0
    private var touching = false

    private data class ProxyKey(
        val source: SampleSource,
        val layer: Layer?,
        val bitmap: Bitmap?,
        val pixels: Long,
        val layersVersion: Int,
        val width: Int,
        val height: Int,
    )

    /** What counts now: the settings and the samples. */
    internal fun params(): ColorRange.Params {
        val s = settings
        return ColorRange.Params.of(s.preset, s.fuzziness, samples.colors(), s.invert, s.antiAlias)
    }

    // ------------------------------------------------------------------ options and samples

    /** Changes the options: a new Refer builds a new proxy, the rest a new preview. */
    fun updateSettings(s: ColorSelectSettings) {
        val old = settings
        if (s == old) return
        settings = s.copy(fuzziness = s.fuzziness.coerceIn(0, ColorSelectSettings.MAX_FUZZINESS))
        if (s.source != old.source) ensureProxy() else requestPreview()
    }

    /** Adds [color] as a sample (one step), tapped at ([x], [y]) or from elsewhere (NaN). */
    fun addSample(color: Int, x: Float = Float.NaN, y: Float = Float.NaN) {
        samples.add(ColorSamples.Sample(color or OPAQUE, x, y))
        changed()
    }

    /** The brush color as a sample (the More row). */
    fun useCurrentColor() = addSample(controller.color)

    /** Removes every sample, one step (the More row). */
    fun clearSamples() {
        if (samples.clear()) changed()
    }

    private fun changed() {
        requestPreview()
        controller.invalidateOverlay()
    }

    override fun undoStep(): Boolean {
        if (!samples.undo()) return false
        changed()
        return true
    }

    override fun redoStep(): Boolean {
        if (!samples.redo()) return false
        changed()
        return true
    }

    /** ✕: the samples go back to what they were at the last confirm. */
    override fun discard() {
        endTouch()
        samples.revert()
        changed()
    }

    override fun commit() = selectColors()

    /**
     * False while no color counts ([ColorPreset.SAMPLED] with no sample, not inverted): the strip's
     * check (ColorSelectLabels18.SELECT_COLORS) is off then, as there is nothing to select (Compose state).
     */
    val canSelect: Boolean
        get() {
            val s = settings
            return s.preset != ColorPreset.SAMPLED || s.invert || samples.list.isNotEmpty()
        }

    /**
     * The strip's check: ONE selection step "Color select" at full resolution, combined by [mode].
     * The samples stay; their steps end. With nothing to select ([canSelect] false: the ✓ of the
     * pending bar after ColorSelectLabels18.CLEAR_SAMPLES, a layer operation landing the steps) only the steps end:
     * the selection there is kept, never replaced by an empty one. When the samples or the preset
     * match no pixel, [ColorSelectLabels18.NO_MATCH] is shown and the selection stays (no step).
     */
    fun selectColors() {
        if (busy) return
        if (!canSelect) {
            samples.resetHistory()
            return
        }
        val c = controller
        val p = params()
        val snapshot = try {
            PixelSnapshot.take(c, settings.source, c.activeLayer.bitmap)
        } catch (e: OutOfMemoryError) {
            c.toast("Not enough memory for \"${ToolId.COLOR_SELECT.label}\"")
            return
        } catch (e: Throwable) {
            // v1.9 (A7a): a layer that went away meanwhile, or worse: kept and said, never a crash.
            failed(e, "select")
            return
        }
        // Confirmed: the samples' steps end only now (a selection that never starts keeps them).
        samples.resetHistory()
        val w = snapshot.width; val h = snapshot.height
        busy = true
        SelectionJobs.applyAsync(c, ToolId.COLOR_SELECT.label, mode, LassoCommit.BUSY_LABEL, emptyMessage = ColorSelectLabels18.NO_MATCH, onFinished = {
            snapshot.recycle()
            busy = false
            c.invalidateOverlay()
        }) { cancelled ->
            val bytes = ColorMasks.fullMask(snapshot, p, cancelled) ?: return@applyAsync null
            SelectionMasks.fromWindow(bytes, 0, 0, w, h, w, h)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onActivate() {
        ensureProxy()
    }

    override fun onDeactivate() {
        endTouch()
        // Nothing is selected on the way out; the samples stay, their steps end with the tool.
        samples.resetHistory()
        proxyRequest?.cancel()
        proxyJob?.cancel()
        previewJob?.cancel()
        dropProxy()
    }

    override fun onDispose() {
        proxyRequest?.cancel()
        proxyJob?.cancel()
        previewJob?.cancel()
        dropProxy()
        pixel?.recycle()
        pixel = null
    }

    private fun dropProxy() {
        proxy = null
        proxyKey = null
        proxyReady = false
        preview?.bitmap?.recycle()
        preview = null
    }

    private fun currentKey(): ProxyKey {
        val c = controller
        val source = settings.source
        val layer = c.activeLayer
        return if (source == SampleSource.LAYER && !layer.isFolder) {
            ProxyKey(source, layer, layer.bitmap, layer.contentVersion, 0, c.doc.width, c.doc.height)
        } else {
            var sum = 0L
            for (l in c.doc.layers) sum += l.contentVersion
            ProxyKey(SampleSource.CANVAS, null, null, sum, c.layersVersion, c.doc.width, c.doc.height)
        }
    }

    /** Builds the proxy of the pixels Refer names, unless the one built (or building) is of them. */
    private fun ensureProxy() {
        val c = controller
        val key = currentKey()
        if (key == proxyKey && (proxy != null || proxyJob?.isActive == true)) return
        proxyJob?.cancel()
        previewJob?.cancel()
        proxy = null
        proxyReady = false
        proxyKey = key
        // v1.9 (A7a): only the source is taken here (no pixel read); it is rendered off the main thread.
        var source = sourceOf(key)
        proxyJob = c.scope.launch {
            var tries = 0
            while (true) {
                try {
                    val px = withContext(Dispatchers.Default) {
                        val bitmap = renderSource(source)
                        try {
                            ColorMasks.proxy(bitmap) { !isActive }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (px != null && proxyKey == key) {
                        proxy = px
                        proxyReady = true
                        requestPreview()
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    // No preview (as in v1.8: nothing said); the strip's check still selects at full size.
                    if (proxyKey == key) proxyKey = null
                    CrashLog.record(e, "color select: proxy", fatal = false)
                    return@launch
                } catch (e: Throwable) {
                    if (proxyKey != key) return@launch
                    // A layer recycled or replaced while it was read: once more, from the pixels as they are now.
                    if (e is RuntimeException && ++tries == 1 && currentKey() == key) {
                        source = sourceOf(key)
                        continue
                    }
                    proxyKey = null
                    failed(e, "proxy")
                    return@launch
                }
            }
        }
    }

    /** What [key] names, to render off the main thread: the layer's pixels or the canvas. Main thread. */
    private fun sourceOf(key: ProxyKey): RenderScaled.Source {
        val bitmap = key.bitmap
        return if (bitmap != null) RenderScaled.Source.layer(bitmap) else RenderScaled.Source.canvas(controller.doc)
    }

    /**
     * v1.9 (A7a): a draw found the pixels changed under the tool (an undo, a redo): a new proxy,
     * built after the draw (never in it); the last preview is drawn until the new one is ready.
     */
    private fun requestProxy() {
        proxyRequest = controller.scope.launch(Dispatchers.Main) { ensureProxy() }
    }

    /**
     * v1.9 (A7a): a job of the tool failed with [e] at [where]: kept as a report (no personal
     * data: the place and the error's class and stack) and said; never thrown on.
     */
    private fun failed(e: Throwable, where: String) {
        CrashLog.record(e, "color select: $where", fatal = false)
        val label = ToolId.COLOR_SELECT.label
        controller.toast(if (e is OutOfMemoryError) String.format(StabilityLabels19.NO_MEMORY_FOR, label) else StabilityLabels19.ERROR_TOAST.format(label))
    }

    /** Computes the preview of what counts now; a change while one runs is taken up when it ends. */
    private fun requestPreview() {
        previewWanted = true
        if (proxy == null || previewJob?.isActive == true) return
        val c = controller
        previewJob = c.scope.launch {
            try {
                while (previewWanted) {
                    previewWanted = false
                    val px = proxy ?: break
                    val p = params()
                    val made = withContext(Dispatchers.Default) {
                        val mask = ColorMasks.previewMask(px, p) { !isActive } ?: return@withContext null
                        mask to BitmapUtils.bytesToAlpha8(mask, px.width, px.height)
                    } ?: continue
                    if (proxy !== px) { made.second.recycle(); continue }
                    val old = preview
                    preview = ColorPreview(++previewVersion, made.first, px.width, px.height, made.second)
                    old?.bitmap?.recycle()
                    c.invalidateOverlay()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // v1.9 (A7a): the last preview stays; the next change tries again.
                previewWanted = false
                failed(e, "preview")
            }
        }
    }

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        if (busy) return
        touching = true
        loupe = loupeAt(p)
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        if (!touching) return
        loupe = loupeAt(p)
        controller.invalidateOverlay()
    }

    /** The loupe over [p], flipped below the finger near the canvas's top (under the editor's bars). */
    private fun loupeAt(p: ToolPoint) = Loupe.at(Vec2(p.x, p.y), controller.viewTransform, Loupe.chromeTop(controller))

    override fun onUp(p: ToolPoint) {
        if (!touching) return
        endTouch()
        sampleAt(p.x, p.y)?.let { addSample(it, p.x, p.y) }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        endTouch()
        controller.invalidateOverlay()
    }

    /** A finger held still keeps its loupe: nothing else happens. */
    override fun onLongPress(p: ToolPoint): Boolean = touching

    private fun endTouch() {
        touching = false
        loupe = null
    }

    /**
     * The color (opaque) of the Refer pixels at document point ([x], [y]); null outside the
     * canvas and on a transparent pixel.
     */
    fun sampleAt(x: Float, y: Float): Int? {
        val doc = controller.doc
        if (!x.isFinite() || !y.isFinite()) return null
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        if (ix !in 0 until doc.width || iy !in 0 until doc.height) return null
        val layer = controller.activeLayer
        val argb = if (settings.source == SampleSource.LAYER && !layer.isFolder) {
            layer.bitmap.getPixel(ix, iy)
        } else {
            renderPixel(ix, iy)
        }
        if (argb ushr 24 == 0) return null
        return argb or OPAQUE
    }

    private var pixel: Bitmap? = null
    private val pixelRect = Rect()

    /** The flattened canvas's pixel ([ix], [iy]) (no tool previews). */
    private fun renderPixel(ix: Int, iy: Int): Int {
        val bmp = pixel?.takeUnless { it.isRecycled } ?: BitmapUtils.createLayerBitmap(1, 1).also { pixel = it }
        bmp.eraseColor(0)
        pixelRect.set(ix, iy, ix + 1, iy + 1)
        val canvas = Canvas(bmp)
        canvas.clipRect(0, 0, 1, 1)
        canvas.translate(-ix.toFloat(), -iy.toFloat())
        controller.compositor.drawDocument(canvas, pixelRect, useOverrides = false, target = CompositeTarget.translate(bmp, ix, iy))
        return bmp.getPixel(0, 0)
    }

    // ------------------------------------------------------------------ overlay

    private val tint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = PREVIEW_TINT }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val mapped = FloatArray(2)

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        // Pixels changed under the tool (an undo, a redo): a new proxy, asked for here and built
        // after this draw (v1.9, A7a: never in it); the last preview is drawn meanwhile.
        if (!busy && proxyRequest?.isActive != true && proxyKey != null && proxyKey != currentKey()) requestProxy()
        val doc = controller.doc
        preview?.let { pv ->
            if (!pv.bitmap.isRecycled) {
                canvas.save()
                canvas.concat(t.matrix)
                canvas.scale(doc.width / pv.width.toFloat(), doc.height / pv.height.toFloat())
                canvas.drawBitmap(pv.bitmap, 0f, 0f, tint)
                canvas.restore()
            }
        }
        drawDots(canvas, t)
        loupe?.draw(canvas, t, controller.compositor, doc.width, doc.height) {}
    }

    /** Each tapped sample's swatch dot at its tap point. */
    private fun drawDots(canvas: Canvas, t: ViewTransform) {
        val r = t.dp(DOT_DP)
        for (s in samples.list) {
            if (!s.tapped) continue
            mapped[0] = s.x; mapped[1] = s.y
            t.matrix.mapPoints(mapped)
            dotFill.color = s.color
            canvas.drawCircle(mapped[0], mapped[1], r, dotFill)
            dotRing.strokeWidth = t.dp(2.5f); dotRing.color = RING_LIGHT
            canvas.drawCircle(mapped[0], mapped[1], r, dotRing)
            dotRing.strokeWidth = t.dp(1f); dotRing.color = RING_DARK
            canvas.drawCircle(mapped[0], mapped[1], r + t.dp(1.5f), dotRing)
        }
    }

    companion object {
        /** The preview's red at half strength (an ALPHA_8 mask drawn with it). */
        const val PREVIEW_TINT = 0x80FF0000.toInt()

        /** A swatch dot's radius (dp). */
        const val DOT_DP = 6f

        private const val OPAQUE = 0xFF shl 24
        private const val RING_LIGHT = 0xFFFFFFFF.toInt()
        private const val RING_DARK = 0xB3000000.toInt()
    }
}
