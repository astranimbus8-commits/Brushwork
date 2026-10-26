package com.brushwork.paint.engine.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer

/**
 * v1.10 A9 step 3 (design 2.5 "Below projection", X4): the composite of every unit below the
 * active layer's split ([ProjectionRules.splitFor]), per display tile at 1:1, for the display
 * tiles that were rendered. A render of a dirty part is then the projection's pixels copied in
 * (SRC) plus `Compositor.drawSplit` of the units from the split upward: `[0, k)` then `[k, n)` onto
 * one target is the full draw bit for bit (I8), so the projection is exact.
 *
 * - **Key:** [BelowKey] (every layer and mask below: identity, `pixelGeneration`, `contentVersion`,
 *   eye, opacity, blend, clipping, mask switch, parent, folder state; the split, the colour mode
 *   and the safe-compositing switch). Any mismatch drops the whole projection; it is never patched
 *   by dirty rects.
 * - **Lifetime:** persistent across strokes, pans and zooms while the active layer and the key
 *   stay; built lazily, a whole display tile at a time, when a part of that tile is rendered
 *   (inside `DisplayTiles.update` and `updateBudgeted`). P4: when the units below the split hold
 *   a tile that is not resident anywhere in the display tile, the build is the waiting tiles'
 *   (their accumulator at the split is [adopt]ed); and a built tile's renders read only the units
 *   from the split up, so cold tiles below never make a stroke frame wait.
 * - **Memory:** at most `min(displayHeld, R / 8)` ([capBytes]); a tile beyond the cap is not built
 *   and renders on the plain path. Registered with [MemoryGovernor] as "below-projection" at the
 *   below-caches' priority (20): any pressure above NORMAL frees it (P5: projections go first).
 * - **When:** at TILES and FULL ([ProjectionRules.on]) with at least [ProjectionRules.MIN_LAYERS]
 *   visible units below the split; never while a render override draws a layer below (what it
 *   draws may change at any time), and never for the first update after a gesture lifts
 *   (`DisplayTiles.projectionsOff`).
 *
 * Main thread, like the display tiles. [LiveAdjust] holds one per editor and installs it as the
 * display's `BelowRenderer` while no stroke uses Classic's `StrokeBelowCache`.
 */
internal class BelowProjection(
    private val docOf: () -> Document,
    private val activeOf: () -> Layer?,
    private val overrideOf: () -> LayerRenderOverride?,
) : DisplayTiles.BelowRenderer {

    /** Tests: false keeps every render on the plain path. */
    internal var enabled: Boolean = true

    /** Tests: a fixed cap instead of `min(displayHeld, R / 8)` (null: the design's cap). */
    internal var capOverride: Long? = null

    private var tiles: DisplayTiles? = null
    private var bitmaps: Array<Bitmap?> = emptyArray()
    private var canvases: Array<Canvas?> = emptyArray()
    private var targets: Array<CompositeTarget?> = emptyArray()
    private var built = BooleanArray(0)
    private val key = BelowKey()
    private var split = -1
    private var governed: MemoryGovernor.Registration? = null

    /** Bytes the projection's tiles hold now. */
    var bytes: Long = 0L
        private set

    /** Renders drawn from the projection (tests). */
    internal var hits: Long = 0L
        private set

    /** Display tiles composited into the projection (tests, `BelowRebuildTimingTest`). */
    internal var builds: Long = 0L
        private set

    /** Times the whole projection was dropped by a key mismatch, a split change or a trim (tests). */
    internal var drops: Long = 0L
        private set

    /** The cap: `min(displayHeld, R / 8)`. */
    fun capBytes(t: DisplayTiles): Long = capOverride ?: minOf(t.heldBytes, EngineConfig.budget.nativeBudget / 8)

    /** Installs this projection on [t] (replacing its tile arrays when the display changed). */
    fun install(t: DisplayTiles) {
        if (tiles !== t) {
            freeAll()
            val n = t.tileCount
            bitmaps = arrayOfNulls(n)
            canvases = arrayOfNulls(n)
            targets = arrayOfNulls(n)
            built = BooleanArray(n)
            tiles = t
        }
        t.belowRenderer = this
    }

    /** Takes this projection off [t] (when installed there); its tiles stay until the key or the split changes. */
    fun uninstall(t: DisplayTiles) {
        if (t.belowRenderer === this) t.belowRenderer = null
    }

    override fun render(tiles: DisplayTiles, index: Int, tr: Rect, d: Rect, canvas: Canvas, target: CompositeTarget, compositor: Compositor): Boolean {
        val k = prepare(tiles, index, tr, compositor)
        return k > 0 && renderSplit(tiles, index, tr, d, canvas, target, compositor, k, Int.MAX_VALUE)
    }

    /**
     * The split of tile [index] with the key checked (a mismatch drops the whole projection and
     * captures the new key) and its cache tile allocated within the cap; -1: the plain path.
     */
    override fun prepare(tiles: DisplayTiles, index: Int, tr: Rect, compositor: Compositor): Int {
        if (!enabled || !ProjectionRules.on || tiles !== this.tiles || index !in bitmaps.indices) return -1
        val doc = docOf()
        if (compositor.document !== doc || tiles.docWidth != doc.width || tiles.docHeight != doc.height) return -1
        val active = activeOf() ?: return -1
        val k = ProjectionRules.splitFor(doc, active)
        if (!ProjectionRules.belowEligible(doc, k)) return -1
        val ov = overrideOf()
        if (BelowKey.overrideBelow(doc, k, ov)) return -1
        val safe = AdjustmentStage.safeCompositing
        if (k != split || !key.matches(doc, k, safe, ov)) {
            dropAll()
            key.capture(doc, k, safe)
            split = k
        }
        return if (tileFor(index, tr) != null) k else -1
    }

    override fun builtCache(index: Int): Bitmap? = if (index in built.indices && built[index]) bitmaps[index] else null

    /**
     * P4: the waiting tiles composited units `[0, k)` of tile [index] across the whole tile into
     * [acc] (exactly what a build here draws, chunk by chunk: I8); it becomes the tile's cache when
     * the split and the key are still the current ones (else it is not kept, and the tile is built
     * when next rendered).
     */
    override fun adopt(tiles: DisplayTiles, index: Int, tr: Rect, k: Int, acc: Bitmap) {
        if (!enabled || !ProjectionRules.on || tiles !== this.tiles || index !in bitmaps.indices || k != split) return
        val doc = docOf()
        val active = activeOf() ?: return
        if (ProjectionRules.splitFor(doc, active) != k) return
        val ov = overrideOf()
        if (BelowKey.overrideBelow(doc, k, ov) || !key.matches(doc, k, AdjustmentStage.safeCompositing, ov)) return
        val cache = tileFor(index, tr) ?: return
        if (cache.width != acc.width || cache.height != acc.height) return
        canvases[index]!!.drawBitmap(acc, 0f, 0f, ProxyTiles.copyPaint)
        built[index] = true
        builds++
    }

    /**
     * The render after [prepare] returned [k]: the cache tile (built here when it is not yet)
     * copied in, then `drawSplit(k, until)` of [d] (`until`: the layer count, or the first unit
     * the above projection draws, step 7).
     */
    override fun renderSplit(tiles: DisplayTiles, index: Int, tr: Rect, d: Rect, canvas: Canvas, target: CompositeTarget, compositor: Compositor, k: Int, until: Int): Boolean {
        if (tiles !== this.tiles || k != split) return false
        val cache = bitmaps.getOrNull(index) ?: return false
        val doc = compositor.document
        val end = minOf(until, doc.layers.size)
        if (end < k) return false
        try {
            if (!built[index]) {
                val cv = canvases[index]!!
                val save = cv.save()
                try {
                    cv.translate(-tr.left.toFloat(), -tr.top.toFloat())
                    cv.clipRect(tr)
                    cv.drawColor(0, PorterDuff.Mode.CLEAR)
                    compositor.drawSplit(cv, tr, targets[index], 0, k)
                } finally {
                    cv.restoreToCount(save)
                }
                built[index] = true
                builds++
            }
            canvas.drawBitmap(cache, tr.left.toFloat(), tr.top.toFloat(), ProxyTiles.copyPaint)
            compositor.drawSplit(canvas, d, target, k, end)
        } catch (e: IllegalArgumentException) {
            // A cut the compositor refuses (never expected: splitFor follows its rules).
            dropAll()
            return false
        }
        hits++
        return true
    }

    /** Changes are found by the key, never by dirty rects. */
    override fun invalidated(tiles: DisplayTiles, r: Rect) = Unit

    /** Tile [index]'s projection bitmap within the cap; null when there is no room. */
    private fun tileFor(index: Int, tr: Rect): Bitmap? {
        bitmaps[index]?.let { return it }
        val t = tiles ?: return null
        val need = tr.width().toLong() * tr.height() * 4L
        if (bytes + need > capBytes(t)) return null
        val bmp = try {
            Bitmap.createBitmap(tr.width(), tr.height(), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        bitmaps[index] = bmp
        canvases[index] = Canvas(bmp)
        targets[index] = CompositeTarget.displayTile(bmp, tr.left, tr.top)
        built[index] = false
        bytes += bmp.allocationByteCount
        register()
        return bmp
    }

    /** Every tile is built again when next used (the bitmaps are kept for the new key). */
    private fun dropAll() {
        if (built.any { it }) drops++
        built.fill(false)
    }

    /** Frees every tile and forgets the key. */
    fun freeAll() {
        for (i in bitmaps.indices) {
            bitmaps[i]?.recycle()
            bitmaps[i] = null
            canvases[i] = null
            targets[i] = null
        }
        built.fill(false)
        bytes = 0L
        split = -1
        key.clear()
        governed?.close()
        governed = null
    }

    private fun register() {
        if (governed != null) return
        governed = WeakRegistration.register(this, "below-projection", StrokeBelowCache.BELOW_PRIORITY, { it.bytes }, { p, pressure -> p.trimFor(pressure) })
    }

    /** P5: above NORMAL the projection is freed and rebuilt lazily. */
    private fun trimFor(pressure: MemoryPressure): Long {
        if (pressure == MemoryPressure.NORMAL) return 0L
        val held = bytes
        if (held > 0L) drops++
        freeAll()
        return held
    }
}
