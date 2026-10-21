package com.brushwork.paint.engine

import com.brushwork.paint.model.Document
import com.brushwork.paint.storage.CanvasLimits
import com.brushwork.paint.ui.gallery.CanvasCheck
import com.brushwork.paint.ui.gallery.CanvasPresets
import kotlin.math.max

/**
 * v1.10 (F0, design 3.2 C15): v1.9's limits, exactly, as a [LayerBudget]: 55 % of `maxMemory`
 * for the layer bitmaps less 3 layer-sized buffers, clamped to 2..100 layers
 * (`EditorController.maxLayers` of v1.9), a mask counting as a layer and array sources by their
 * bytes (v1.9's `effectiveLayerCount`), and `maxMemory / 8` for the render caches.
 * `LayerBudgetV19ParityTest` checks every member against v1.9's formulas.
 */
object LayerBudgetV19 : LayerBudget {
    private val heap: Long get() = Runtime.getRuntime().maxMemory()

    private fun per(doc: Document): Long = max(1L, doc.layerBytes)

    private fun arrayBytes(doc: Document): Long = doc.layers.sumOf { it.array?.pixels?.bytes ?: 0L }

    override fun used(doc: Document): Int =
        doc.pixelLayerCount + doc.layers.count { it.mask != null } + ((arrayBytes(doc) + per(doc) - 1) / per(doc)).toInt()

    override fun maxLayers(doc: Document): Int = ((heap * 0.55).toLong() / per(doc) - 3).toInt().coerceIn(2, 100)

    override fun canAdd(doc: Document, layers: Int, extraBytes: Long): Boolean {
        val bytes = max(0L, arrayBytes(doc) + extraBytes)
        val count = doc.pixelLayerCount.toLong() + doc.layers.count { it.mask != null } + layers + (bytes + per(doc) - 1) / per(doc)
        return count <= maxLayers(doc)
    }

    override fun room(doc: Document): Int = (maxLayers(doc) - used(doc)).coerceAtLeast(0)

    override fun canAddAdjustment(doc: Document): Boolean = used(doc) + 2 <= maxLayers(doc)

    override fun refusal(doc: Document, suffix: String?): String =
        "Layer limit reached (${maxLayers(doc)}) for this canvas size" + (suffix?.let { ": $it" } ?: "")

    override fun canvasBudgetBytes(): Long = (heap * CanvasOps.MEMORY_FRACTION).toLong()

    override fun canTransformCanvas(doc: Document, width: Int, height: Int): String? =
        CanvasOps.validateSize(width, height, CanvasSnapshot.of(doc).bitmapCount, canvasBudgetBytes())

    override fun rawMaxLayers(width: Int, height: Int): Long = CanvasLimits.rawMaxLayers(width, height, heap)

    override fun canvasFits(width: Int, height: Int, paintedLayers: Int): Boolean =
        canvasCheck(width, height).ok && rawMaxLayers(width, height) >= paintedLayers

    override fun canvasCheck(width: Int, height: Int): CanvasCheck = CanvasPresets.check(width, height, heap)

    override fun importMaxSide(width: Int, height: Int): Int = CanvasLimits.importMaxSide(width, height, heap)

    override fun capacity(width: Int, height: Int): Int? = CanvasPresets.maxLayers(width, height, heap)

    override fun renderCacheCap(): Long = heap / 8
}
