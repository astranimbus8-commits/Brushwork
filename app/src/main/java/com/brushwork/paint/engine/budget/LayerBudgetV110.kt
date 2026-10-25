package com.brushwork.paint.engine.budget

import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.model.Document
import com.brushwork.paint.storage.CanvasLimits
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.common.LayersLabels110
import com.brushwork.paint.ui.gallery.CanvasCheck
import com.brushwork.paint.ui.gallery.CanvasPresets
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v1.10 (F1, design 2.4 and 2.13; constructor frozen, body A7): the [LayerBudget] of a session
 * (`EngineConfig.openSession`) and of the gallery (`EngineConfig.applyIdle`), at [level] with J and
 * R from [budget].
 *
 * **Unknown memory** (`budget.budgets.known == false`, J3): every member is [LayerBudgetV19]'s, so
 * Robolectric and a phone the probe cannot read keep v1.9's numbers exactly (I32).
 *
 * **Known memory** (section 2.15; L is one layer's bytes, 184 MB the undo RAM and caches):
 * - CLASSIC holds `max(v19Cap(J), (R − 3.8·L − 184 MB)/L)` layers ([classicMaxLayers]), so never
 *   fewer than v1.9.
 * - TILES, and FULL without a swap store, hold `1 + (R − 6·L − 184 MB)/(0.35·L)` (35 % tile
 *   occupancy), never fewer than CLASSIC ([tilesMaxLayers]). The count is R's, not `tileRam`'s: R
 *   is fixed for the session, so the limit never moves under the user's finger (I31).
 * - FULL with a swap store has no layer limit but the structural guard
 *   ([LayerBudget.MAX_LAYERS_GUARD]); adding stops when neither R nor the store has room for one
 *   more layer ("Not enough free storage for more layers").
 *
 * The counts stay in v1.9's units ([used]: pixel layers, masks, array sources by their bytes);
 * blank layers count like any other, and cost 0 bytes (section 2.13). The editor's members key
 * FULL on the session's swap store (`EngineConfig.swapStore`, read at each call: the store is
 * installed after the session opens); the gallery's ([rawMaxLayers], [canvasCheck], [capacity])
 * on [level] alone, since the gallery never has a store.
 *
 * The canvas ceiling (J12): a canvas fits when `6.0·L + 184 MB ≤ R` at TILES and FULL, or
 * `3.8·L + 184 MB` plus one L per painted layer at CLASSIC, which also keeps every size v1.9
 * accepted. About 6000 × 6000 on the T606.
 */
class LayerBudgetV110(private val budget: MemoryBudget, private val level: EngineLevel) : LayerBudget {
    private val known: Boolean get() = budget.budgets.known

    /** R. */
    private val r: Long get() = budget.nativeBudget

    /** J. */
    private val j: Long get() = budget.javaMax

    /** FULL with the session's swap store: no layer limit but the guard and free storage. */
    private val swapping: Boolean get() = level == EngineLevel.FULL && EngineConfig.swapStore.capacityBytes > 0L

    private fun per(doc: Document): Long = max(1L, doc.layerBytes)

    private fun arrayBytes(doc: Document): Long = doc.layers.sumOf { it.array?.pixels?.bytes ?: 0L }

    /** [used] after adding [layers] layers and [extraBytes] array source bytes. */
    private fun countAfter(doc: Document, layers: Int, extraBytes: Long): Long {
        val bytes = max(0L, arrayBytes(doc) + extraBytes)
        return doc.pixelLayerCount.toLong() + doc.layers.count { it.hasMask } + layers + (bytes + per(doc) - 1) / per(doc)
    }

    /** At FULL with swap: R or the swap store can still take one more layer of [doc]. */
    private fun storageRoom(doc: Document): Boolean =
        budget.tileRam() >= per(doc) || EngineConfig.swapStore.canWrite(per(doc))

    override fun used(doc: Document): Int = LayerBudgetV19.used(doc)

    override fun maxLayers(doc: Document): Int = when {
        !known -> LayerBudgetV19.maxLayers(doc)
        swapping -> LayerBudget.MAX_LAYERS_GUARD
        level == EngineLevel.CLASSIC -> classicMaxLayers(r, j, per(doc))
        else -> tilesMaxLayers(r, j, per(doc))
    }

    override fun canAdd(doc: Document, layers: Int, extraBytes: Long): Boolean {
        if (!known) return LayerBudgetV19.canAdd(doc, layers, extraBytes)
        if (doc.layers.size.toLong() + max(0, layers) > LayerBudget.MAX_LAYERS_GUARD) return false
        if (countAfter(doc, layers, extraBytes) > maxLayers(doc)) return false
        return !swapping || layers <= 0 || storageRoom(doc)
    }

    override fun room(doc: Document): Int {
        if (!known) return LayerBudgetV19.room(doc)
        if (swapping && !storageRoom(doc)) return 0
        val structural = LayerBudget.MAX_LAYERS_GUARD - doc.layers.size
        return min(maxLayers(doc) - used(doc), structural).coerceAtLeast(0)
    }

    override fun canAddAdjustment(doc: Document): Boolean =
        if (!known) LayerBudgetV19.canAddAdjustment(doc) else canAdd(doc, 2)

    /**
     * Section 2.13: the structural guard's text at 10,000 layers; at FULL with swap "Not enough
     * free storage for more layers"; else v1.9's "Layer limit reached (N) for this canvas size".
     * [suffix] follows after a colon in each.
     */
    override fun refusal(doc: Document, suffix: String?): String {
        if (!known) return LayerBudgetV19.refusal(doc, suffix)
        val atGuard = doc.layers.size >= LayerBudget.MAX_LAYERS_GUARD || maxLayers(doc) >= LayerBudget.MAX_LAYERS_GUARD
        val text = when {
            swapping && used(doc) < LayerBudget.MAX_LAYERS_GUARD && doc.layers.size < LayerBudget.MAX_LAYERS_GUARD -> EngineLabels110.STORAGE_FULL
            atGuard -> LayersLabels110.GUARD
            else -> "Layer limit reached (${maxLayers(doc)}) for this canvas size"
        }
        return text + (suffix?.let { ": $it" } ?: "")
    }

    /**
     * The bytes the layer bitmaps may take after a canvas operation (`CanvasOps.validateSize`):
     * CLASSIC R less the 184 MB reserve (never below v1.9's 0.55 J); TILES the tile RAM; FULL with
     * swap the tile RAM and the store's free bytes.
     */
    override fun canvasBudgetBytes(): Long = when {
        !known -> LayerBudgetV19.canvasBudgetBytes()
        level == EngineLevel.CLASSIC -> max((j * V19_SHARE).toLong(), r - RESERVE_BYTES)
        swapping -> budget.tileRam() + freeSwap()
        else -> budget.tileRam()
    }

    /**
     * CLASSIC: v1.9's check of every layer bitmap against [canvasBudgetBytes]. TILES (and FULL
     * without swap): the size rules, then `EngineLabels110.CANVAS_OP_TOO_BIG` when twice the
     * layers' logical bytes (the old tiles and the new) pass the tile RAM (section 2.6). FULL with
     * swap: the size rules only (the operation runs layer by layer, its old tiles swapping first).
     * Reads the stores' sizes, never a bitmap.
     */
    override fun canTransformCanvas(doc: Document, width: Int, height: Int): String? {
        if (!known) return LayerBudgetV19.canTransformCanvas(doc, width, height)
        if (level == EngineLevel.CLASSIC) {
            return CanvasOps.validateSize(width, height, CanvasSnapshot.of(doc).bitmapCount, canvasBudgetBytes())
        }
        CanvasOps.validateSize(width, height, 0, Long.MAX_VALUE)?.let { return it }
        if (swapping) return null
        val logical = doc.layers.sumOf { it.pixels.logicalBytes() + (it.maskPixels?.logicalBytes() ?: 0L) }
        return if (2 * logical > budget.tileRam()) EngineLabels110.CANVAS_OP_TOO_BIG else null
    }

    /** Layers of [width] x [height] that fit at [level] (not clamped below; at most the guard). */
    override fun rawMaxLayers(width: Int, height: Int): Long {
        if (!known) return LayerBudgetV19.rawMaxLayers(width, height)
        val per = max(1L, CanvasLimits.layerBytes(width, height))
        return when {
            level == EngineLevel.FULL && canvasFits(width, height) -> LayerBudget.MAX_LAYERS_GUARD.toLong()
            level == EngineLevel.CLASSIC -> classicRaw(r, j, per)
            else -> max(classicRaw(r, j, per), tilesRaw(r, per))
        }.coerceAtMost(LayerBudget.MAX_LAYERS_GUARD.toLong())
    }

    override fun canvasFits(width: Int, height: Int, paintedLayers: Int): Boolean {
        if (!known) return LayerBudgetV19.canvasFits(width, height, paintedLayers)
        if (width < 1 || height < 1 || width > CanvasLimits.MAX_SIDE || height > CanvasLimits.MAX_SIDE) return false
        val per = CanvasLimits.layerBytes(width, height)
        return when (level) {
            EngineLevel.CLASSIC ->
                (CLASSIC_BUFFERS + paintedLayers) * per + RESERVE_BYTES <= r ||
                    (CanvasPresets.check(width, height, j).ok && CanvasLimits.rawMaxLayers(width, height, j) >= paintedLayers)
            EngineLevel.TILES -> RESIDENT_BUFFERS * per + RESERVE_BYTES <= r && tilesRaw(r, per) >= paintedLayers
            EngineLevel.FULL -> RESIDENT_BUFFERS * per + RESERVE_BYTES <= r && paintedLayers <= LayerBudget.MAX_LAYERS_GUARD
        }
    }

    /**
     * The new-canvas dialog's check: the size rules, then the J12 ceiling for one painted layer,
     * refused with "This canvas is too big for this phone's memory. The largest is about W × H."
     * ([largestCanvas], at the asked aspect ratio).
     */
    override fun canvasCheck(width: Int, height: Int): CanvasCheck {
        if (!known) return LayerBudgetV19.canvasCheck(width, height)
        val sides = CanvasPresets.check(width, height, Long.MAX_VALUE)
        if (!sides.ok || canvasFits(width, height)) return sides
        val (w, h) = largestCanvas(width, height)
        return CanvasCheck(false, LayersLabels110.canvasTooBig(w, h))
    }

    /**
     * The longest side (at most `CanvasLimits.IMPORT_MAX_SIDE`) an imported [width] x [height]
     * picture may keep so that its canvas holds `CanvasLimits.IMPORT_MIN_LAYERS` layers at
     * [level]; at FULL so that the canvas fits.
     */
    override fun importMaxSide(width: Int, height: Int): Int {
        if (!known) return LayerBudgetV19.importMaxSide(width, height)
        if (width <= 0 || height <= 0) return CanvasLimits.IMPORT_MAX_SIDE
        val long = max(width, height)
        val short = min(width, height)
        val cap = min(long, CanvasLimits.IMPORT_MAX_SIDE)
        fun fits(side: Int): Boolean {
            val other = max(1L, Math.round(short.toDouble() * side / long)).toInt()
            return if (level == EngineLevel.FULL) canvasFits(side, other) else rawMaxLayers(side, other) >= CanvasLimits.IMPORT_MIN_LAYERS
        }
        if (fits(cap)) return cap
        var lo = 1
        var hi = cap
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (fits(mid)) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** "Layers on this device": null at FULL (no fixed number), else the level's count for one layer of [width] x [height]. */
    override fun capacity(width: Int, height: Int): Int? {
        if (!known) return LayerBudgetV19.capacity(width, height)
        val per = max(1L, CanvasLimits.layerBytes(width, height))
        return when (level) {
            EngineLevel.FULL -> null
            EngineLevel.CLASSIC -> classicMaxLayers(r, j, per)
            EngineLevel.TILES -> tilesMaxLayers(r, j, per)
        }
    }

    /** The vector render caches and array patches: R/16 (v1.9: J/8). */
    override fun renderCacheCap(): Long = if (!known) LayerBudgetV19.renderCacheCap() else r / RENDER_CACHE_DIVISOR

    /** The swap store's free bytes (0 without one). */
    private fun freeSwap(): Long = EngineConfig.swapStore.let { (it.capacityBytes - it.usedBytes).coerceAtLeast(0L) }

    /**
     * The largest canvas at [width]:[height]'s aspect ratio that [canvasCheck] accepts, each side
     * at most `CanvasLimits.MAX_SIDE` and rounded down (to 100 px from 1000 px, else to 10 px) so
     * it reads as "about W × H". CLASSIC: one painted layer, or v1.9's rule, whichever is larger.
     */
    internal fun largestCanvas(width: Int, height: Int): Pair<Int, Int> {
        val usable = (r - RESERVE_BYTES).coerceAtLeast(0L).toDouble()
        val pixels = if (level == EngineLevel.CLASSIC) {
            max(usable / (4 * (CLASSIC_BUFFERS + 1)), (j * V19_SHARE).toLong().toDouble() / (CanvasLimits.MIN_LAYERS + V19_BUFFERS) / 4)
        } else {
            usable / (4 * RESIDENT_BUFFERS)
        }
        val aspect = width.toDouble() / height
        var w = sqrt(pixels * aspect)
        var h = sqrt(pixels / aspect)
        val over = max(w, h) / CanvasLimits.MAX_SIDE
        if (over > 1.0) {
            w /= over
            h /= over
        }
        return roundedDown(w) to roundedDown(h)
    }

    companion object {
        /** Section 2.15: the undo RAM and caches every level reserves besides the layers (184 MB). */
        internal const val RESERVE_BYTES = 184_000_000L

        /** Section 2.15: Classic's resident layer-sized buffers besides the painted layers. */
        internal const val CLASSIC_BUFFERS = 3.8

        /**
         * Section 2.15: TILES' and FULL's resident minimum in layers (active 1, display 1.33, below
         * 1, above 1, coverage 0.25, selection 0.25, operation scratch 1).
         */
        internal const val RESIDENT_BUFFERS = 6.0

        /** Section 2.15: the share of a painted layer's tiles that are occupied. */
        internal const val TILE_OCCUPANCY = 0.35

        /** v1.9's share of the heap for the layers. */
        private const val V19_SHARE = 0.55

        /** v1.9's layer-sized buffers for undo and compositing. */
        private const val V19_BUFFERS = 3

        /** R's share for the render caches: R/16. */
        private const val RENDER_CACHE_DIVISOR = 16L

        /**
         * v1.9's layer cap for a heap of [javaMax] and layers of [layerBytes]: 55 % of the heap
         * less 3 layer-sized buffers, clamped to 2..100 (`LayerBudgetV19.maxLayers`).
         */
        internal fun v19Cap(javaMax: Long, layerBytes: Long): Int =
            ((javaMax * V19_SHARE).toLong() / max(1L, layerBytes) - V19_BUFFERS).toInt().coerceIn(2, 100)

        /**
         * Section 2.15: Classic's painted layers, `max(v19Cap(J), (R − 3.8·L − 184 MB)/L)`, at most
         * [LayerBudget.MAX_LAYERS_GUARD]. The v1.9 floor keeps a phone whose R is near its floor
         * (0.55 J) at v1.9's count: the R term alone is about 6 phone layers there.
         */
        internal fun classicMaxLayers(nativeBudget: Long, javaMax: Long, layerBytes: Long): Int {
            val per = max(1L, layerBytes)
            val rTerm = floor((nativeBudget - CLASSIC_BUFFERS * per - RESERVE_BYTES) / per).toLong()
            return max(v19Cap(javaMax, per).toLong(), rTerm).coerceAtMost(LayerBudget.MAX_LAYERS_GUARD.toLong()).toInt()
        }

        /**
         * Section 2.15: TILES' painted layers, `1 + (R − 6·L − 184 MB)/(0.35·L)`, never fewer than
         * [classicMaxLayers] and at most [LayerBudget.MAX_LAYERS_GUARD]. 235 phone layers at
         * R = 1100 MB.
         */
        internal fun tilesMaxLayers(nativeBudget: Long, javaMax: Long, layerBytes: Long): Int {
            val per = max(1L, layerBytes)
            return max(classicMaxLayers(nativeBudget, javaMax, per).toLong(), tilesRaw(nativeBudget, per))
                .coerceAtMost(LayerBudget.MAX_LAYERS_GUARD.toLong()).toInt()
        }

        /**
         * Section 2.13: what a refused first write to a blank, parked or evicted layer says: at FULL
         * with the session's swap store "Not enough free storage for more layers", else "Not enough
         * memory to paint on this layer. Merge or delete layers". `EditorController.checkEditable`
         * and `updateLayerData` show it (a lead request; they show the second text until then).
         */
        fun firstWriteRefusal(): String =
            if (EngineConfig.level == EngineLevel.FULL && EngineConfig.swapStore.capacityBytes > 0L) {
                EngineLabels110.STORAGE_FULL
            } else {
                EngineLabels110.PAINT_REFUSED
            }

        /** Classic's unclamped count: the larger of v1.9's raw count over J and the R term. */
        private fun classicRaw(nativeBudget: Long, javaMax: Long, per: Long): Long = max(
            (javaMax * V19_SHARE).toLong() / per - V19_BUFFERS,
            floor((nativeBudget - CLASSIC_BUFFERS * per - RESERVE_BYTES) / per).toLong(),
        )

        /** TILES' unclamped count, `1 + (R − 6·L − 184 MB)/(0.35·L)`. */
        private fun tilesRaw(nativeBudget: Long, per: Long): Long =
            1L + floor((nativeBudget - RESIDENT_BUFFERS * per - RESERVE_BYTES) / (TILE_OCCUPANCY * per)).toLong()

        /** [v] rounded down to 100 from 1000, else to 10 from 100; at least 1. */
        private fun roundedDown(v: Double): Int {
            val n = v.toInt()
            return when {
                n >= 1000 -> n / 100 * 100
                n >= 100 -> n / 10 * 10
                else -> max(1, n)
            }
        }
    }
}
