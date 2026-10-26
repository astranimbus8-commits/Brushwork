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
 * v1.10 A9 step 7 (design 2.5 "Above projection", X5): the premultiplied composite of the units
 * above the active unit onto transparency (`Compositor.drawSplit(u, n)`, [ProjectionRules.aboveStart]),
 * per display tile at 1:1, for the display tiles a stroke touched. While a finger paints the
 * active layer (`DisplayTiles.gestureDown`) a render of a dirty part is the units up to the
 * active unit's top (from the below projection's cache, X4) plus this cache drawn over them with
 * SRC_OVER. Every unit above then draws onto what is below it as source-over
 * ([ProjectionRules.aboveEligible]), which is associative in exact arithmetic; the 8-bit rounding
 * happens at other places, so a frame is within 2 LSB per channel of the full draw, never exact.
 * The parts it drew are re-rendered exactly by the first update after the gesture
 * (`DisplayTiles.exactAfterGesture`, with the projections off: I33).
 *
 * - **Key:** the layers above the active layer, `[a + 1, n)`, as [BelowKey] keeps the layers below
 *   the split (identity, `pixelGeneration`, `contentVersion`, eye, opacity, blend, clipping, mask
 *   switch, parent, folder state; the colour mode and the safe-compositing switch), plus the
 *   active layer and its index. Any mismatch drops the whole projection; eligibility is decided
 *   again only then. Edits of the active layer (and of the layers clipped to it, which draw every
 *   frame) never change it.
 * - **Lifetime:** persistent across strokes while the active layer and the key stay; a display
 *   tile is built when a stroke frame first renders a part of it, at most one per update
 *   ([beginUpdate]), and only when every layer it reads holds resident tiles there (else the part
 *   renders on the exact paths, which may wait, P4).
 * - **Memory:** as the below projection: at most `min(displayHeld, R / 8)` ([capBytes]), not in
 *   the ledger; registered with [MemoryGovernor] as "above-projection" at the below caches'
 *   priority (20): any pressure above NORMAL frees it.
 * - **When:** TILES and FULL with `EngineConfig.aboveProjection` (the check's M5 <= 30 ms,
 *   [ProjectionRules.aboveOn]), at least [ProjectionRules.MIN_LAYERS] visible units above, and no
 *   render override drawing a layer above (what it draws may change at any time). Never for
 *   exports, sampling, thumbnails or autosave: only the display tiles' renders while a finger is
 *   down ask it.
 *
 * Main thread, like the display tiles. [LiveAdjust] holds one per editor and installs it as the
 * display's `AboveRenderer` when the check allowed it.
 */
internal class AboveProjection(
    private val docOf: () -> Document,
    private val activeOf: () -> Layer?,
    private val overrideOf: () -> LayerRenderOverride?,
) : DisplayTiles.AboveRenderer {

    /** Tests: false keeps every render on the exact paths. */
    internal var enabled: Boolean = true

    /** Tests: a fixed cap instead of `min(displayHeld, R / 8)` (null: the design's cap). */
    internal var capOverride: Long? = null

    /** Display tiles built per update (one: a stroke frame never pays for more than one build). */
    internal var buildsPerUpdate: Int = 1

    private var tiles: DisplayTiles? = null
    private var bitmaps: Array<Bitmap?> = emptyArray()
    private var canvases: Array<Canvas?> = emptyArray()
    private var targets: Array<CompositeTarget?> = emptyArray()
    private var built = BooleanArray(0)
    private val key = BelowKey()
    private var keyedActive: Layer? = null
    private var keyedIndex = -1

    /** The first unit above the active unit for the current key; -1: the stack is not eligible. */
    private var start = -1
    private var buildsLeft = 1
    private var governed: MemoryGovernor.Registration? = null

    /** Bytes the projection's tiles hold now. */
    var bytes: Long = 0L
        private set

    /** Parts drawn with the projection (tests). */
    internal var hits: Long = 0L
        private set

    /** Display tiles composited into the projection (tests, `AboveBuildTimingTest`). */
    internal var builds: Long = 0L
        private set

    /** Times the whole projection was dropped by a key mismatch or a trim (tests). */
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
        t.aboveRenderer = this
    }

    /** Takes this projection off [t] (when installed there); its tiles stay until the key changes. */
    fun uninstall(t: DisplayTiles) {
        if (t.aboveRenderer === this) t.aboveRenderer = null
    }

    override fun beginUpdate() {
        buildsLeft = buildsPerUpdate
    }

    override fun prepare(tiles: DisplayTiles, index: Int, tr: Rect, compositor: Compositor): Int {
        if (!enabled || !ProjectionRules.aboveOn || tiles !== this.tiles || index !in bitmaps.indices) return -1
        val doc = docOf()
        if (compositor.document !== doc || tiles.docWidth != doc.width || tiles.docHeight != doc.height) return -1
        val active = activeOf() ?: return -1
        // (a) is read every time: whether the active layer is clipped depends on the layer below it.
        val u = ProjectionRules.aboveStart(doc, active)
        if (u <= 0) return -1
        val a = doc.indexOf(active)
        val n = doc.layers.size
        val ov = overrideOf()
        if (BelowKey.overrideIn(doc, a + 1, n, ov)) return -1
        val safe = AdjustmentStage.safeCompositing
        if (active !== keyedActive || a != keyedIndex || !key.matches(doc, a + 1, n, safe, ov)) {
            dropAll()
            key.capture(doc, a + 1, n, safe)
            keyedActive = active
            keyedIndex = a
            start = if (ProjectionRules.aboveEligible(doc, active, u) && compositor.isSplitBoundary(u)) u else -1
        }
        if (start != u) return -1
        val cache = tileFor(index, tr) ?: return -1
        if (!built[index]) {
            if (buildsLeft <= 0 || WaitingTiles.needsReads(compositor, tr, from = u)) return -1
            if (!build(index, tr, compositor, u)) return -1
            buildsLeft--
        }
        return if (cache.isRecycled) -1 else u
    }

    override fun drawAbove(tiles: DisplayTiles, index: Int, tr: Rect, canvas: Canvas) {
        val cache = bitmaps.getOrNull(index) ?: return
        canvas.drawBitmap(cache, tr.left.toFloat(), tr.top.toFloat(), null)
        hits++
    }

    /** Composites units `[u, n)` of tile [index] (document rect [tr]) onto transparency, across the whole tile. */
    private fun build(index: Int, tr: Rect, compositor: Compositor, u: Int): Boolean {
        val cv = canvases[index] ?: return false
        val save = cv.save()
        try {
            cv.translate(-tr.left.toFloat(), -tr.top.toFloat())
            cv.clipRect(tr)
            cv.drawColor(0, PorterDuff.Mode.CLEAR)
            compositor.drawSplit(cv, tr, targets[index], u, compositor.document.layers.size)
        } catch (e: IllegalArgumentException) {
            // A cut the compositor refuses (never expected: aboveStart follows its rules).
            start = -1
            return false
        } finally {
            cv.restoreToCount(save)
        }
        built[index] = true
        builds++
        return true
    }

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
    internal fun dropAll() {
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
        start = -1
        keyedActive = null
        keyedIndex = -1
        key.clear()
        governed?.close()
        governed = null
    }

    private fun register() {
        if (governed != null) return
        governed = WeakRegistration.register(this, "above-projection", StrokeBelowCache.BELOW_PRIORITY, { it.bytes }, { p, pressure -> p.trimFor(pressure) })
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
