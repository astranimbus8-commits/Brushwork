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
 * files (`project.json`, the pixel files and the thumbnail) from the [Seed]. The seed describes
 * its layers instead of holding full-size layer bitmaps, so a new project is still written
 * without allocating any (v1.8).
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
     * The layers [spec] starts with. A null [NewCanvasSpec.start] keeps v1.8 (test fixtures and the
     * SVG/PDF import): a "Background" layer filled with [NewCanvasSpec.background] (or
     * transparent) and an empty "Layer 1" on top, active. Every [CanvasStart] makes one
     * "Layer 1", active (R15): [CanvasStart.FILL] filled with [NewCanvasSpec.background] (0 when
     * null), [CanvasStart.TRANSPARENT] empty, [CanvasStart.PAPER] empty over white paper.
     */
    fun seed(spec: NewCanvasSpec): Seed = when (spec.start) {
        null -> Seed(
            layers = listOf(SeedLayer(BACKGROUND, fill = spec.background ?: 0), SeedLayer(LAYER_1)),
            activeLayerIndex = 1,
            paper = null,
        )
        CanvasStart.FILL -> Seed(listOf(SeedLayer(LAYER_1, fill = spec.background ?: 0)), activeLayerIndex = 0, paper = null)
        CanvasStart.TRANSPARENT -> Seed(listOf(SeedLayer(LAYER_1)), activeLayerIndex = 0, paper = null)
        CanvasStart.PAPER -> Seed(listOf(SeedLayer(LAYER_1)), activeLayerIndex = 0, paper = PaperSpec())
    }

    /**
     * A project made from a photo (R15): its "Picture" layer ([picture]) alone, active, with no
     * paper. [name] is the project's name (the seed does not need it).
     */
    @Suppress("UNUSED_PARAMETER")
    fun seedFromImage(picture: Bitmap, name: String): Seed =
        Seed(layers = listOf(SeedLayer(PICTURE, picture = picture)), activeLayerIndex = 0, paper = null)

    private const val BACKGROUND = "Background"
    private const val LAYER_1 = "Layer 1"
    private const val PICTURE = "Picture"
}
