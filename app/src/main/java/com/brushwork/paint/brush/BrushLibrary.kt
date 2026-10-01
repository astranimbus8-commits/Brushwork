package com.brushwork.paint.brush

import com.brushwork.paint.tools.ToolId

/** Built-in brush presets, grouped by painting tool. Sizes are document pixels. */
object BrushLibrary {
    val defaultBrush = BrushPreset(
        "pen", "Pen", BrushTip.ROUND_HARD,
        size = 8f, minSizeRatio = 0.25f, hardness = 0.92f, spacing = 0.06f,
    )
    val defaultEraser = BrushPreset(
        "eraser", "Hard eraser", BrushTip.ROUND_HARD,
        size = 30f, hardness = 0.9f, spacing = 0.06f, pressureSize = false,
    )
    val defaultSmudge = BrushPreset(
        "smudge", "Smudge", BrushTip.SMUDGE,
        size = 40f, hardness = 0.3f, spacing = 0.025f, pressureSize = false, mixing = 0.75f,
    )
    val defaultBlur = BrushPreset(
        "blur", "Blur", BrushTip.BLUR,
        size = 60f, hardness = 0.3f, spacing = 0.12f, pressureSize = false, mixing = 0.6f,
    )

    private val gPen = BrushPreset(
        "gpen", "G-pen", BrushTip.ROUND_HARD,
        size = 10f, minSizeRatio = 0.04f, hardness = 0.97f, spacing = 0.05f, taperStart = 35f, taperEnd = 70f,
    )
    private val dipPen = BrushPreset(
        "dippen", "Dip pen", BrushTip.CALLIGRAPHY,
        size = 7f, minSizeRatio = 0.08f, hardness = 0.95f, spacing = 0.05f, angle = 35f, roundness = 0.7f,
        taperStart = 20f, taperEnd = 45f,
    )
    private val pencil = BrushPreset(
        "pencil", "Pencil", BrushTip.PENCIL,
        size = 6f, minSizeRatio = 0.55f, opacity = 0.95f, flow = 0.42f, hardness = 0.55f, spacing = 0.1f,
        pressureOpacity = true, grain = 0.55f,
    )
    private val mechanicalPencil = BrushPreset(
        "mechpencil", "Mechanical pencil", BrushTip.PENCIL,
        size = 2.5f, minSizeRatio = 0.8f, opacity = 0.9f, flow = 0.55f, hardness = 0.75f, spacing = 0.1f,
        pressureOpacity = true, grain = 0.35f,
    )
    private val airbrush = BrushPreset(
        "airbrush", "Airbrush", BrushTip.AIRBRUSH,
        size = 120f, flow = 0.07f, hardness = 0.2f, spacing = 0.08f, pressureSize = false, pressureOpacity = true,
    )
    private val softRound = BrushPreset(
        "softround", "Soft round", BrushTip.ROUND_SOFT,
        size = 40f, minSizeRatio = 0.35f, flow = 0.3f, hardness = 0.25f, spacing = 0.1f, pressureOpacity = true,
    )
    private val hardRound = BrushPreset(
        "hardround", "Hard round", BrushTip.ROUND_HARD,
        size = 30f, minSizeRatio = 0.5f, hardness = 0.85f, spacing = 0.08f,
    )
    private val marker = BrushPreset(
        "marker", "Marker", BrushTip.MARKER,
        size = 20f, opacity = 0.55f, hardness = 0.85f, spacing = 0.06f, roundness = 0.8f, angle = 30f, pressureSize = false,
    )
    private val feltPen = BrushPreset(
        "feltpen", "Felt pen", BrushTip.ROUND_HARD,
        size = 12f, minSizeRatio = 0.65f, opacity = 0.92f, flow = 0.85f, hardness = 0.7f, spacing = 0.07f,
    )
    private val calligraphy = BrushPreset(
        "calligraphy", "Calligraphy", BrushTip.CALLIGRAPHY,
        size = 24f, minSizeRatio = 0.45f, hardness = 0.95f, spacing = 0.04f, angle = 45f, roundness = 0.18f,
    )
    private val watercolor = BrushPreset(
        "watercolor", "Watercolor", BrushTip.WATERCOLOR,
        size = 48f, minSizeRatio = 0.5f, flow = 0.12f, hardness = 0.25f, spacing = 0.12f, pressureOpacity = true,
        mixing = 0.55f,
    )
    private val chalk = BrushPreset(
        "chalk", "Chalk", BrushTip.CHALK,
        size = 32f, minSizeRatio = 0.6f, flow = 0.85f, hardness = 0.75f, spacing = 0.14f, pressureOpacity = true,
        scatter = 0.04f, grain = 0.75f,
    )
    private val crayon = BrushPreset(
        "crayon", "Crayon", BrushTip.CHALK,
        size = 14f, minSizeRatio = 0.7f, flow = 0.9f, hardness = 0.85f, spacing = 0.1f, grain = 0.5f,
    )
    private val spray = BrushPreset(
        "spray", "Spray", BrushTip.SPRAY,
        size = 90f, flow = 0.7f, hardness = 0.5f, spacing = 0.28f, pressureSize = false, pressureOpacity = true,
        scatter = 0.1f,
    )
    private val pixelPen = BrushPreset(
        "pixelpen", "Pixel pen", BrushTip.SQUARE,
        size = 1f, hardness = 1f, spacing = 0.1f, pressureSize = false, antiAlias = false,
    )

    private val softEraser = BrushPreset(
        "softeraser", "Soft eraser", BrushTip.AIRBRUSH,
        size = 90f, flow = 0.25f, hardness = 0.2f, spacing = 0.08f, pressureSize = false, pressureOpacity = true,
    )
    private val pixelEraser = BrushPreset(
        "pixeleraser", "Pixel eraser", BrushTip.SQUARE,
        size = 3f, hardness = 1f, spacing = 0.1f, pressureSize = false, antiAlias = false,
    )

    private val smudgeStrong = BrushPreset(
        "smudgestrong", "Finger smear", BrushTip.SMUDGE,
        size = 30f, hardness = 0.55f, spacing = 0.025f, pressureSize = false, pressureOpacity = true, mixing = 0.93f,
    )
    private val blender = BrushPreset(
        "blender", "Soft blender", BrushTip.SMUDGE,
        size = 70f, hardness = 0.15f, spacing = 0.02f, pressureSize = false, pressureOpacity = true, mixing = 0.55f,
    )

    private val blurStrong = BrushPreset(
        "blurstrong", "Strong blur", BrushTip.BLUR,
        size = 60f, hardness = 0.5f, spacing = 0.1f, pressureSize = false, mixing = 1f,
    )

    /** All brush presets offered in the brush panel for normal painting. */
    val all: List<BrushPreset> = listOf(
        defaultBrush, gPen, dipPen, pencil, mechanicalPencil, airbrush, softRound, hardRound, marker,
        feltPen, calligraphy, watercolor, chalk, crayon, spray, pixelPen,
    )

    val erasers: List<BrushPreset> = listOf(defaultEraser, softEraser, pixelEraser)
    val smudges: List<BrushPreset> = listOf(defaultSmudge, smudgeStrong, blender)
    val blurs: List<BrushPreset> = listOf(defaultBlur, blurStrong)

    /** Clone stamp default (v1.5): soft round, 80 px, hardness 0.4, full opacity and flow, size follows a stylus. */
    val defaultClone = BrushPreset(
        "clone_soft", "Soft round", BrushTip.ROUND_SOFT,
        size = 80f, minSizeRatio = 0.35f, hardness = 0.4f, spacing = 0.08f,
    )

    /** Mask brush default (v1.5): soft round, 120 px, hardness 0.3. */
    val defaultMaskBrush = BrushPreset(
        "mask_soft", "Soft round", BrushTip.ROUND_SOFT,
        size = 120f, minSizeRatio = 0.35f, hardness = 0.3f, spacing = 0.08f, pressureSize = false,
    )
    private val maskHard = BrushPreset(
        "mask_hard", "Hard round", BrushTip.ROUND_HARD,
        size = 60f, minSizeRatio = 0.5f, hardness = 0.85f, spacing = 0.08f, pressureSize = false,
    )

    /**
     * Clone stamp presets: coverage tips only (pen, soft, airbrush, pencil, chalk, marker), as
     * own copies so their sizes and edits are kept apart from the brush's.
     */
    val clones: List<BrushPreset> = listOf(
        defaultClone,
        defaultBrush.copy(id = "clone_pen"),
        airbrush.copy(id = "clone_airbrush"),
        pencil.copy(id = "clone_pencil"),
        chalk.copy(id = "clone_chalk"),
        marker.copy(id = "clone_marker"),
    )

    /** Mask brush presets: soft and hard round. */
    val maskBrushes: List<BrushPreset> = listOf(defaultMaskBrush, maskHard)

    private val index: Map<String, BrushPreset> = (all + erasers + smudges + blurs + clones + maskBrushes).associateBy { it.id }

    /** Presets offered for a painting tool (empty for other tools). */
    fun presetsFor(toolId: ToolId): List<BrushPreset> = when (toolId) {
        ToolId.BRUSH -> all
        ToolId.ERASER -> erasers
        ToolId.SMUDGE -> smudges
        ToolId.BLUR -> blurs
        ToolId.CLONE -> clones
        ToolId.MASK -> maskBrushes
        else -> emptyList()
    }

    /** Default preset of a painting tool (the brush for anything else). */
    fun defaultFor(toolId: ToolId): BrushPreset = when (toolId) {
        ToolId.ERASER -> defaultEraser
        ToolId.SMUDGE -> defaultSmudge
        ToolId.BLUR -> defaultBlur
        ToolId.CLONE -> defaultClone
        ToolId.MASK -> defaultMaskBrush
        else -> defaultBrush
    }

    /** Built-in preset by id, or null. */
    fun byId(id: String): BrushPreset? = index[id]

    /** The painting tool whose library contains [presetId], or null. */
    fun toolOf(presetId: String): ToolId? = when {
        all.any { it.id == presetId } -> ToolId.BRUSH
        erasers.any { it.id == presetId } -> ToolId.ERASER
        smudges.any { it.id == presetId } -> ToolId.SMUDGE
        blurs.any { it.id == presetId } -> ToolId.BLUR
        clones.any { it.id == presetId } -> ToolId.CLONE
        maskBrushes.any { it.id == presetId } -> ToolId.MASK
        else -> null
    }
}
