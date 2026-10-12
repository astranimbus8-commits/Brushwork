package com.brushwork.paint.storage

import android.graphics.Bitmap
import com.brushwork.paint.model.PaperSpec

/**
 * v1.9 (design 2.4.8; owner A8): how a new canvas starts (the New canvas dialog). FILL = one layer
 * filled with [NewCanvasSpec.background] (the dialog's "White" or "Color…").
 * `NewCanvasSpec.start` null = v1.8 ("Background" + "Layer 1").
 */
enum class CanvasStart { FILL, TRANSPARENT, PAPER }

/**
 * v1.9 (design 2.4.8; owner A8): what a new project starts with, extracted from
 * [ProjectRepository.create] and [ProjectRepository.createFromImage], which keep writing the
 * files (`project.json`, the pixel files and the thumbnail) from the [Seed]. F1b: v1.8 for every
 * spec. The seed describes its layers instead of holding full-size layer bitmaps, so a new
 * project is still written without allocating any (v1.8).
 */
internal object CanvasCreate {

    /**
     * One layer of a new project, bottom first: [name], and either [picture]'s pixels (stored as
     * they are; [ProjectRepository.createFromImage] makes them storable first) or every pixel
     * [fill] (0 = transparent).
     */
    class SeedLayer(val name: String, val fill: Int = 0, val picture: Bitmap? = null)

    /** A new project's layers (bottom first), its active layer and its paper (null = none). */
    class Seed(val layers: List<SeedLayer>, val activeLayerIndex: Int, val paper: PaperSpec?)

    /**
     * F1b: v1.8 for every spec: a "Background" layer filled with [NewCanvasSpec.background] (or
     * transparent) and an empty "Layer 1" on top, active. A8: the three choices for a non-null
     * `start`, one "Layer 1" each.
     */
    fun seed(spec: NewCanvasSpec): Seed = Seed(
        layers = listOf(SeedLayer("Background", fill = spec.background ?: 0), SeedLayer("Layer 1")),
        activeLayerIndex = 1,
        paper = null,
    )

    /**
     * F1b: v1.8 "Picture" ([picture]) + an empty "Layer 1", active 1. A8: "Picture" alone, active
     * 0. [name] is the project's name (unused in F1b).
     */
    @Suppress("UNUSED_PARAMETER")
    fun seedFromImage(picture: Bitmap, name: String): Seed = Seed(
        layers = listOf(SeedLayer("Picture", picture = picture), SeedLayer("Layer 1")),
        activeLayerIndex = 1,
        paper = null,
    )
}
