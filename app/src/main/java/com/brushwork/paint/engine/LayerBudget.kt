package com.brushwork.paint.engine

import com.brushwork.paint.model.Document
import com.brushwork.paint.ui.gallery.CanvasCheck

/**
 * v1.10 (F0, design 3.2 C15): every layer and canvas limit the app shows or enforces, behind one
 * seam. The editor's layer cap (`EditorController`'s `layerCap` region), the refusals that name
 * it, the array room check, the canvas operations' memory budget and the render caches' cap all
 * read [current]; the gallery and import checks can ask it for a size before a document exists.
 *
 * [current] is [LayerBudgetV19] (exactly v1.9's numbers) until F1 resolves the engine; a policy
 * that does not know the device's memory gives v1.9's numbers too (J3). `CanvasLimits`,
 * `CanvasPresets.check` / `maxLayers` and `NewArtwork.for*(…, maxHeap)` keep their pure
 * heap-taking forms; this seam wraps them.
 */
interface LayerBudget {
    /** The layers [doc] counts against the cap: v1.9's `effectiveLayerCount` rule (pixel layers, masks, array sources). */
    fun used(doc: Document): Int

    /** The cap for [doc]: [used] plus what still fits (at most [MAX_LAYERS_GUARD]). */
    fun maxLayers(doc: Document): Int

    /** True when [layers] more layers and [extraBytes] more array source bytes (negative: freed) fit under [maxLayers]. */
    fun canAdd(doc: Document, layers: Int = 1, extraBytes: Long = 0L): Boolean

    /** Layers that can still be added (0 when [doc] is at or over the cap). */
    fun room(doc: Document): Int

    /** True when an adjustment layer (and its mask) can be added. */
    fun canAddAdjustment(doc: Document): Boolean

    /** The message shown when a new layer would pass [maxLayers], with [suffix] after a colon when given. */
    fun refusal(doc: Document, suffix: String? = null): String

    /** Bytes all layer bitmaps may take after a canvas operation (v1.9 `CanvasOps.memoryBudget()`). */
    fun canvasBudgetBytes(): Long

    /** Null when [doc] may be resized to [width] x [height], else the reason. */
    fun canTransformCanvas(doc: Document, width: Int, height: Int): String?

    /** Gallery: layers of [width] x [height] that fit (not clamped; can be negative). */
    fun rawMaxLayers(width: Int, height: Int): Long

    /** True when a [width] x [height] canvas can be created and holds [paintedLayers] layers (the J12 ceiling, design 2.4). */
    fun canvasFits(width: Int, height: Int, paintedLayers: Int = 1): Boolean

    /** The new-canvas dialog's check of [width] x [height]. */
    fun canvasCheck(width: Int, height: Int): CanvasCheck

    /** The longest side an imported [width] x [height] picture may keep. */
    fun importMaxSide(width: Int, height: Int): Int

    /** "Layers on this device" for [width] x [height]; null = no fixed number. */
    fun capacity(width: Int, height: Int): Int?

    /** Bytes the vector render caches and the array patches may take. */
    fun renderCacheCap(): Long

    companion object {
        /** No policy reports a cap above this many layers. */
        const val MAX_LAYERS_GUARD = 10_000

        /** The policy in force (main thread writes; read anywhere). */
        @Volatile
        var current: LayerBudget = LayerBudgetV19
    }
}
