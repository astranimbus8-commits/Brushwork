package com.brushwork.paint.engine.live

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.model.Layer
import kotlin.math.hypot
import kotlin.math.min

/**
 * Live adjustment sessions (v1.6, §3.1 C2; area A): while an adjustment layer's effect, mask or
 * opacity is dragged, the area it covers is drawn from proxy tiles at 1/8..1× screen resolution
 * over a per-frame-keyed below-cache; when the finger stops (150 ms) or lifts, the exact image
 * refines tile by tile, visible tiles first, centre out, at most 8 ms per frame
 * (`DisplayTiles.updateBudgeted`). Proxies are views (I7): they never reach a saved, exported,
 * merged, thumbnailed or sampled bitmap, and once a session ends every display tile equals a
 * no-session render bit for bit (the display tiles are only ever rendered by `DisplayTiles`).
 *
 * Callers: `AdjustmentEdit.preview`, `MaskPreview.update` and the layer window's opacity drag of
 * an adjustment layer call [touch], then [end]; a discrete change (a sheet button, an undo) calls
 * [changed]. `CanvasView.onDraw` calls [drawFrame] first and keeps asking for frames while
 * [wantsFrame]. For a live opacity drag, set `layer.opacity` and call
 * `touch(layer, MaskEdits.effectRegion(c, layer))` per move (NOT `previewLayerProps`, whose
 * `invalidateDoc(null)` re-renders every tile on every move), then record the step with
 * `commitLayerProps` and call [end].
 *
 * How a frame is made while a session runs:
 * - Display tiles touched by the session ("session tiles") that are dirty and on screen are not
 *   rendered while the finger moves: the proxy covers their changed part (the rest of the tile
 *   keeps its exact pixels). Every other dirty visible tile renders as usual, and so does a
 *   session tile that was exact already when a change the session didn't make reached it (it
 *   leaves the session: a stroke right after a slider was let go is never shown from a proxy).
 * - The proxy scale `s` is the largest power of two at most the view zoom, in 1/8..1, halved
 *   (for the rest of the session) after a live frame over 33 ms (below-cache builds and frames
 *   that made proxies don't count), or while the proxies needed would exceed 32 MB. Each proxy
 *   tile holds its frame, a below-cache (the composite of the layers under the adjustment at
 *   `s`), rebuilt when [BelowKey] changes or a change that isn't the session's own was drawn
 *   there, and the adjustment's mask at `s` ([com.brushwork.paint.engine.MaskFactorCache], kept
 *   while the mask doesn't change: a slider drag); a frame restores the below-cache 1:1 and
 *   draws the adjustment (the fused path) and every layer above exactly, only where something
 *   changed.
 * - Refinement (after [end], or 150 ms after the last [touch]): session tiles render within
 *   8 ms per frame nearest the view centre first; a rendered tile covers its proxy. When no
 *   session tile on screen is dirty the session ends (off-screen ones stay dirty and render when
 *   they come into view, the v1.5 rule). Buffers are kept 2 s for the next drag, then freed (or
 *   at once on `onTrimMemory`).
 * - Over 32 MB at 1/8, or [policy] EXACT, or the "Fast adjustment preview" setting off: no
 *   session, [touch] only invalidates (the v1.5 path). An `OutOfMemoryError` ends the session
 *   with one toast.
 *
 * Main thread only (I3), like the layers it reads.
 */
class LiveAdjust(private val c: EditorController) {
    /** EXACT: no session starts, [touch] only invalidates. LIVE: sessions (when the setting is on). */
    enum class Policy { EXACT, LIVE }

    /** I8: EXACT under Robolectric (tests opt in with LIVE and drive frames with [clock] and [refineStep]). */
    var policy: Policy = if ("robolectric" == android.os.Build.FINGERPRINT) Policy.EXACT else Policy.LIVE
        set(v) {
            field = v
            if (v == Policy.EXACT && session != null) abort(null)
        }

    /** Time source in nanoseconds (replaceable in tests; refinement budgets use it too). */
    var clock: () -> Long = System::nanoTime

    /**
     * "Fast adjustment preview" (`AppSettings.fastAdjustPreview`, default on): sessions may start.
     * Cached (frames never read the preferences): loaded with the controller and again whenever a
     * session starts, so a change saved elsewhere counts from the next drag. Setting it saves the
     * setting; turning it off ends a running session (the next frame is exact).
     */
    var fastPreview: Boolean = c.settings.fastAdjustPreview
        set(v) {
            field = v
            if (c.settings.fastAdjustPreview != v) c.settings.fastAdjustPreview = v
            if (!v && session != null) abort(null)
        }

    // ------------------------------------------------------------------ tunables (tests change them)

    /** Most bytes one session's proxy tiles and below-caches may hold. */
    internal var memoryCapBytes: Long = MEMORY_CAP_BYTES

    /** A live frame slower than this halves the proxy scale for the rest of the session. */
    internal var slowFrameNanos: Long = SLOW_FRAME_NANOS

    /** The finger resting this long starts refinement. */
    internal var idleNanos: Long = IDLE_NANOS

    /** Work per refinement frame. */
    internal var refineBudgetNanos: Long = REFINE_BUDGET_NANOS

    // ------------------------------------------------------------------ state

    private class Session(val tiles: DisplayTiles, var layer: Layer) {
        /** Display tiles this session changed (drawn from proxies until they are rendered). */
        val inSession = BooleanArray(tiles.tileCount)
        val isSession: (Int) -> Boolean = { inSession[it] }
        val notSession: (Int) -> Boolean = { !inSession[it] }
        var lastTouch = 0L

        /** [end] was called: refine now. */
        var ended = false
        var refining = false

        /** Largest proxy scale this session may use (halved by slow frames). */
        var scaleCap = 1f

        /** Another frame is needed although nothing changed (the scale was lowered). */
        var needsFrame = false

        /** The document area on screen at the last frame (null before the first). */
        var visible: Rect? = null

        fun mark(region: Rect?) {
            val r = if (region == null) Rect(0, 0, tiles.docWidth, tiles.docHeight) else Rect(region)
            if (!r.intersect(0, 0, tiles.docWidth, tiles.docHeight)) return
            val ts = tiles.tileSize
            for (row in r.top / ts..(r.bottom - 1) / ts) for (col in r.left / ts..(r.right - 1) / ts) inSession[tiles.tileIndexOf(col, row)] = true
        }

        /**
         * A change the session didn't make lands in [r] (document px, within the document; told
         * before the display tiles mark it): session tiles there that are exact already (refined,
         * or left clean by the drag) leave the session, so the change renders exactly like any
         * edit instead of being shown from a proxy until refinement comes back to it (a stroke or
         * a mask dab right after a slider was let go). Tiles still waiting stay: their proxy is
         * redrawn with the change and they refine as planned.
         */
        fun releaseExact(r: Rect) {
            if (r.isEmpty) return
            val ts = tiles.tileSize
            val c1 = ((r.right - 1) / ts).coerceAtMost(tiles.cols - 1)
            val r1 = ((r.bottom - 1) / ts).coerceAtMost(tiles.rows - 1)
            for (row in (r.top / ts).coerceAtLeast(0)..r1) for (col in (r.left / ts).coerceAtLeast(0)..c1) {
                val i = tiles.tileIndexOf(col, row)
                if (inSession[i] && !tiles.isDirty(i)) inSession[i] = false
            }
        }
    }

    private var session: Session? = null

    /** Proxy tiles (kept [FREE_DELAY_MS] after a session for the next drag). */
    private var proxies: ProxyTiles? = null

    /** The display tiles [hook] is installed on. */
    private var hooked: DisplayTiles? = null

    /** True while [touch] invalidates (the session's own change: below-caches stay valid). */
    private var ownInvalidation = false

    /** A layer whose session was refused (over the memory cap): plain invalidation until [end]. */
    private var refused: Layer? = null

    private val hook: (Rect) -> Unit = { r ->
        val foreign = !ownInvalidation
        proxies?.invalidate(r, foreign)
        if (foreign) session?.takeIf { it.tiles === hooked }?.releaseExact(r)
    }

    /** True while a session is running (proxy frames or refinement left). */
    val isActive: Boolean get() = session != null

    /** True while frames are still needed (refinement left): the view keeps invalidating. */
    val wantsFrame: Boolean get() = session?.let { it.refining || it.needsFrame } == true

    /** The proxy scale of the last frame drawn from proxies (tests and the report), or 0. */
    internal val proxyScale: Float get() = proxies?.scale ?: 0f

    /** Bytes the proxy tiles hold now (tests). */
    internal val proxyBytes: Long get() = proxies?.bytes ?: 0L

    /** How many below-caches were drawn so far (tests: the per-frame key keeps them while nothing below changes). */
    internal var belowBuilds = 0
        private set

    // ------------------------------------------------------------------ the callers' API

    /**
     * An adjustment layer's effect / opacity / mask changed live; [region] (document px, null =
     * all) needs redrawing. Starts or continues a session when [policy] is LIVE, the "Fast
     * adjustment preview" setting is on and [layer] is a visible adjustment layer; otherwise (and
     * always under Robolectric's EXACT policy) it only invalidates, as v1.5 did.
     */
    fun touch(layer: Layer, region: Rect?) {
        // A new drag picks up a setting changed elsewhere (Settings); frames use the cached value.
        if (session == null && policy == Policy.LIVE) fastPreview = c.settings.fastAdjustPreview
        if (!canRun(layer) || refused === layer) {
            if (session?.layer === layer) abort(null)
            c.invalidateDoc(region)
            return
        }
        val tiles = c.tiles
        var s = session
        if (s != null && s.tiles !== tiles) {
            abort(null)
            s = null
        }
        if (s == null) {
            refused = null
            s = Session(tiles, layer)
            session = s
            install(tiles)
            handler?.removeCallbacks(freeLater)
        } else if (s.layer !== layer) {
            // Another adjustment layer: the proxies split the stack somewhere else now.
            s.layer = layer
            proxies?.invalidateAll()
        }
        s.lastTouch = clock()
        s.ended = false
        s.refining = false
        s.mark(region)
        ownInvalidation = true
        try {
            c.invalidateDoc(region)
        } finally {
            ownInvalidation = false
        }
        wakeAfterIdle()
    }

    /** The live edit of [layer] ended (finger up, sheet flushed): refine to exact. */
    fun end(layer: Layer) {
        if (refused === layer) refused = null
        val s = session ?: return
        if (s.layer !== layer) return
        s.ended = true
        s.refining = true
        c.invalidateOverlay()
    }

    /**
     * A discrete change of [layer]'s effect, mask or opacity ([region]: where it shows; a sheet
     * button, an undo): [touch] and [end] at once, so a large canvas is drawn from proxies and
     * refines without a hitch.
     */
    fun changed(layer: Layer, region: Rect?) {
        touch(layer, region)
        end(layer)
    }

    /**
     * Draws this frame when a session is active (true); false = the caller updates and draws the
     * display tiles itself. [canvas] is the view's canvas in SCREEN space; [docToScreen] maps
     * document px onto it; [visibleDoc] is the document area on screen; [smooth] = filter tiles.
     */
    fun drawFrame(canvas: Canvas, docToScreen: Matrix, visibleDoc: Rect, smooth: Boolean): Boolean {
        val s = session ?: return false
        if (!valid(s)) {
            abort(null)
            return false
        }
        val tiles = s.tiles
        s.visible = Rect(visibleDoc)
        s.needsFrame = false
        if (!s.refining && (s.ended || clock() - s.lastTouch >= idleNanos)) s.refining = true
        tiles.nanoClock = clock
        // Everything outside the session as the canvas always does it, then refinement.
        tiles.updateBudgeted(c.compositor, visibleDoc, Long.MAX_VALUE, skip = s.isSession)
        if (s.refining) tiles.updateBudgeted(c.compositor, visibleDoc, refineBudgetNanos, skip = s.notSession, center = PointF(visibleDoc.exactCenterX(), visibleDoc.exactCenterY()))
        val pending = pendingTiles(s, visibleDoc)
        if (pending.isEmpty()) {
            if (s.refining) finish()
            drawTiles(canvas, docToScreen, visibleDoc, smooth, tiles, pending, null)
            return true
        }
        val p = try {
            proxiesFor(s, pending, zoomOf(docToScreen))
        } catch (e: OutOfMemoryError) {
            // One toast: the rest of this drag runs the exact path (until [end]).
            refused = s.layer
            abort(OUT_OF_MEMORY)
            return false
        }
        if (p == null) {
            // Over the memory cap even at 1/8: the v1.5 path (the caller renders exactly).
            refused = s.layer
            abort(null)
            return false
        }
        drawTiles(canvas, docToScreen, visibleDoc, smooth, tiles, pending, p)
        return true
    }

    /** Tests: one refinement slice of at most [budgetNanos]; false when done (the session ended). */
    fun refineStep(budgetNanos: Long = Long.MAX_VALUE): Boolean {
        val s = session ?: return false
        if (!valid(s)) {
            abort(null)
            return false
        }
        s.refining = true
        val vis = s.visible
        val tiles = s.tiles
        tiles.nanoClock = clock
        tiles.updateBudgeted(c.compositor, vis, Long.MAX_VALUE, skip = s.isSession)
        tiles.updateBudgeted(c.compositor, vis, budgetNanos, skip = s.notSession, center = vis?.let { PointF(it.exactCenterX(), it.exactCenterY()) })
        if (pendingTiles(s, vis).isEmpty()) {
            finish()
            return false
        }
        return true
    }

    /** Frees every session buffer (editor closing, `onTrimMemory`); a running session ends (exact frames follow). */
    fun release() {
        session = null
        refused = null
        freeBuffers()
        handler?.removeCallbacks(idleWake)
    }

    // ------------------------------------------------------------------ frames

    private fun canRun(layer: Layer): Boolean =
        policy == Policy.LIVE && fastPreview && layer.isAdjustmentLayer && c.doc.effectiveVisible(layer) && c.doc.indexOf(layer) >= 0 &&
            // v1.7: the live proxies split the flat stack; a document with folders adjusts at full
            // resolution until area A makes them tree-aware.
            !c.doc.hasFolders

    private fun valid(s: Session): Boolean =
        policy == Policy.LIVE && fastPreview && s.tiles === c.tiles && s.layer.isAdjustmentLayer && c.doc.indexOf(s.layer) >= 0 && !c.doc.hasFolders &&
            s.tiles.docWidth == c.doc.width && s.tiles.docHeight == c.doc.height

    /** Session tiles that are dirty and intersect [visible] (null: anywhere), in index order. */
    private fun pendingTiles(s: Session, visible: Rect?): IntArray {
        val t = s.tiles
        val out = IntArray(t.tileCount)
        var n = 0
        val tr = Rect()
        for (i in 0 until t.tileCount) {
            if (!s.inSession[i] || !t.isDirty(i)) continue
            if (visible != null) {
                t.tileRect(i % t.cols, i / t.cols, tr)
                if (!Rect.intersects(visible, tr)) continue
            }
            out[n++] = i
        }
        return out.copyOf(n)
    }

    /**
     * The proxies covering [pending] at the scale this frame uses, up to date (below-caches
     * checked, changed parts drawn again); null when they can't fit the memory cap.
     */
    private fun proxiesFor(s: Session, pending: IntArray, zoom: Float): ProxyTiles? {
        val tiles = s.tiles
        var scale = min(proxyScaleFor(zoom), s.scaleCap)
        val split = c.doc.indexOf(s.layer)
        val withBelow = split > 0
        var needed: IntArray
        while (true) {
            val geometry = proxies?.takeIf { it.scale == scale } ?: ProxyTiles(tiles.docWidth, tiles.docHeight, tiles.tileSize, scale)
            needed = pending.map { geometry.indexOfTile(it % tiles.cols, it / tiles.cols) }.distinct().toIntArray()
            val bytes = needed.sumOf { geometry.bytesFor(it, withBelow) }
            if (bytes <= memoryCapBytes) {
                if (proxies !== geometry) {
                    proxies?.release()
                    proxies = geometry
                    registerTrim()
                }
                break
            }
            if (scale <= MIN_SCALE) return null
            scale /= 2f
        }
        val p = proxies!!
        // Room for the new proxies: drop the ones this frame doesn't need first.
        val newBytes = needed.filter { p.get(it) == null }.sumOf { p.bytesFor(it, withBelow) }
        if (p.bytes + newBytes > memoryCapBytes) {
            for (i in p.allocated()) {
                if (i in needed) continue
                p.free(i)
                if (p.bytes + newBytes <= memoryCapBytes) break
            }
        }
        val n = c.doc.layers.size
        val safe = AdjustmentStage.safeCompositing
        val ov = c.renderOverride
        val t0 = clock()
        var belowNanos = 0L
        // A frame that makes proxies draws them whole (once): it says nothing about the pace.
        var fresh = false
        for (i in needed) {
            if (p.get(i) == null) fresh = true
            val proxy = p.obtain(i, withBelow)
            if (withBelow && (proxy.belowStale || !proxy.key.matches(c.doc, split, safe, ov))) {
                val b0 = clock()
                drawBelow(proxy, split, safe)
                proxy.dirty = Rect(proxy.docRect)
                belowNanos += clock() - b0
            }
            val d = proxy.dirty ?: continue
            proxy.dirty = null
            drawProxy(proxy, p, d, split, n, withBelow)
        }
        // Adaptive LOD (§3.1 C2): a slow live frame halves the scale for the rest of the session
        // (not counting the below-cache builds, nor a frame that made proxies).
        val frameNanos = clock() - t0 - belowNanos
        if (!s.refining && !fresh && frameNanos > slowFrameNanos && scale > MIN_SCALE) {
            s.scaleCap = scale / 2f
            s.needsFrame = true
        }
        return p
    }

    /** Draws the composite of layers [0, split) at the proxy's scale into its below-cache. */
    private fun drawBelow(proxy: ProxyTiles.Proxy, split: Int, safe: Boolean) {
        val b = proxy.below ?: Bitmap.createBitmap(proxy.pw, proxy.ph, Bitmap.Config.ARGB_8888).also { proxy.below = it }
        b.eraseColor(0)
        val cv = Canvas(b)
        cv.concat(proxy.matrix)
        val clip = alignedRect(proxy)
        cv.clipRect(clip)
        c.compositor.drawDocument(cv, clip, useOverrides = true, target = CompositeTarget(b, proxy.matrix, display = true), layerRange = 0 until split)
        proxy.key.capture(c.doc, split, safe)
        proxy.belowStale = false
        belowBuilds++
    }

    /** The proxy's pixels in document px (the last column / row may reach past the document). */
    private fun alignedRect(proxy: ProxyTiles.Proxy): Rect = Rect(
        proxy.drawnRect.left.toInt(), proxy.drawnRect.top.toInt(), proxy.drawnRect.right.toInt(), proxy.drawnRect.bottom.toInt(),
    )

    /**
     * Draws document area [dirty] of [proxy] again: the below-cache restored 1:1, then the
     * adjustment layer (the fused path) and every layer above it, exactly, at the proxy scale.
     */
    private fun drawProxy(proxy: ProxyTiles.Proxy, p: ProxyTiles, dirty: Rect, split: Int, n: Int, withBelow: Boolean) {
        val inv = p.inv
        val left = proxy.docRect.left; val top = proxy.docRect.top
        val d = Rect(
            Math.floorDiv(dirty.left - left, inv), Math.floorDiv(dirty.top - top, inv),
            Math.floorDiv(dirty.right - left + inv - 1, inv), Math.floorDiv(dirty.bottom - top + inv - 1, inv),
        )
        if (!d.intersect(0, 0, proxy.pw, proxy.ph)) return
        val docClip = Rect(left + d.left * inv, top + d.top * inv, left + d.right * inv, top + d.bottom * inv)
        val cv = proxy.canvas
        val save = cv.save()
        cv.clipRect(d)
        val below = proxy.below
        if (withBelow && below != null) cv.drawBitmap(below, d, d, ProxyTiles.copyPaint) else cv.drawColor(0, PorterDuff.Mode.CLEAR)
        cv.concat(proxy.matrix)
        c.compositor.drawDocument(cv, docClip, useOverrides = true, target = proxy.target, layerRange = split until n)
        cv.restoreToCount(save)
    }

    /**
     * The frame: display tiles under [docToScreen]; for the [pending] session tiles their clean
     * part from the tile and their changed part from the proxy (filtered).
     */
    private fun drawTiles(canvas: Canvas, docToScreen: Matrix, visible: Rect, smooth: Boolean, tiles: DisplayTiles, pending: IntArray, p: ProxyTiles?) {
        val save = canvas.save()
        canvas.concat(docToScreen)
        if (p == null || pending.isEmpty()) {
            tiles.draw(canvas, visible, smooth)
            canvas.restoreToCount(save)
            return
        }
        val skip = BooleanArray(tiles.tileCount)
        for (i in pending) skip[i] = true
        tiles.draw(canvas, visible, smooth) { skip[it] }
        // Reduced proxies are always filtered; at 1:1 they follow the tiles (zoomed far in the
        // canvas shows crisp pixels, so the drag must not show blurred ones that pop on refinement).
        val proxyPaint = if (smooth || p.scale < 1f) ProxyTiles.drawPaint else ProxyTiles.crispPaint
        val tr = Rect()
        for (i in pending) {
            val col = i % tiles.cols; val row = i / tiles.cols
            tiles.tileRect(col, row, tr)
            val d = tiles.dirtyRect(i) ?: continue
            if (!d.intersect(tr)) continue
            if (d != tr) {
                // The part of the tile nothing changed in keeps its exact pixels.
                val s1 = canvas.save()
                canvas.clipRect(tr)
                canvas.clipOutRect(d)
                tiles.drawTile(canvas, i, smooth)
                canvas.restoreToCount(s1)
            }
            val proxy = p.get(p.indexOfTile(col, row)) ?: continue
            val s2 = canvas.save()
            canvas.clipRect(d)
            canvas.drawBitmap(proxy.bitmap, null, proxy.drawnRect, proxyPaint)
            canvas.restoreToCount(s2)
        }
        canvas.restoreToCount(save)
    }

    // ------------------------------------------------------------------ session end, buffers, timers

    /** No session tile on screen is dirty any more: the session ends (buffers kept a moment). */
    private fun finish() {
        session = null
        handler?.let {
            it.removeCallbacks(freeLater)
            it.postDelayed(freeLater, FREE_DELAY_MS)
        }
    }

    /** Ends the session at once: the next frame renders exactly ([toast]: why, said once). */
    private fun abort(toast: String?) {
        session = null
        freeBuffers()
        if (toast != null) c.toast(toast)
        c.invalidateOverlay()
    }

    private fun install(tiles: DisplayTiles) {
        if (hooked === tiles && tiles.invalidationHook === hook) return
        // Proxies of other display tiles (the document was resized) are of no use.
        if (hooked !== tiles) {
            proxies?.release()
            proxies = null
        }
        hooked?.let { if (it.invalidationHook === hook) it.invalidationHook = null }
        tiles.invalidationHook = hook
        hooked = tiles
    }

    private fun freeBuffers() {
        proxies?.release()
        proxies = null
        hooked?.let { if (it.invalidationHook === hook) it.invalidationHook = null }
        hooked = null
        handler?.removeCallbacks(freeLater)
        unregisterTrim()
    }

    private var handlerOrNull: Handler? = null

    /** The main-thread handler of the timers (made when first needed, never in the constructor). */
    private val handler: Handler?
        get() {
            handlerOrNull?.let { return it }
            val looper = Looper.getMainLooper() ?: return null
            return Handler(looper).also { handlerOrNull = it }
        }

    /** Asks for a frame once the finger rested [idleNanos] (refinement starts there). */
    private val idleWake = Runnable { if (session != null) c.invalidateOverlay() }

    private val freeLater = Runnable { if (session == null) freeBuffers() }

    private fun wakeAfterIdle() {
        val h = handler ?: return
        h.removeCallbacks(idleWake)
        h.postDelayed(idleWake, idleNanos / 1_000_000L + 1L)
    }

    private var trimRegistered = false

    private val trim = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (session == null) freeBuffers()
            else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) abort(null)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    private fun registerTrim() {
        if (trimRegistered) return
        runCatching { c.appContext.registerComponentCallbacks(trim) }.onSuccess { trimRegistered = true }
    }

    private fun unregisterTrim() {
        if (!trimRegistered) return
        trimRegistered = false
        runCatching { c.appContext.unregisterComponentCallbacks(trim) }
    }

    companion object {
        /** §3.1 C2: about 8 B per proxy pixel (frame plus below-cache), at most 32 MB per session. */
        const val MEMORY_CAP_BYTES: Long = 32L shl 20
        const val SLOW_FRAME_NANOS: Long = 33_000_000L
        const val IDLE_NANOS: Long = 150_000_000L
        const val REFINE_BUDGET_NANOS: Long = 8_000_000L
        const val FREE_DELAY_MS: Long = 2_000L

        /** Smallest proxy scale. */
        const val MIN_SCALE = 0.125f

        const val OUT_OF_MEMORY = "Not enough memory for the fast adjustment preview: showing the exact image"

        /** The largest power of two at most [zoom], within 1/8..1 (§3.1 C2). */
        fun proxyScaleFor(zoom: Float): Float {
            var s = 1f
            while (s > MIN_SCALE && s > zoom * (1f + 1e-4f)) s /= 2f
            return s
        }

        /** Screen px per document px of [docToScreen] (rotation and mirroring ignored). */
        internal fun zoomOf(docToScreen: Matrix): Float {
            val v = FloatArray(9)
            docToScreen.getValues(v)
            return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
        }
    }
}
