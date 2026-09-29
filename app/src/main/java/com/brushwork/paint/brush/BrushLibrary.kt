package com.brushwork.paint.brush

// STUB — replaced by the brush module. Keep these public names.
object BrushLibrary {
    val defaultBrush = BrushPreset("pen", "Pen", BrushTip.ROUND_HARD, size = 8f)
    val defaultEraser = BrushPreset("eraser", "Eraser", BrushTip.ROUND_HARD, size = 30f, pressureSize = false)
    val defaultSmudge = BrushPreset("smudge", "Smudge", BrushTip.SMUDGE, size = 40f, hardness = 0.3f, pressureSize = false)
    val defaultBlur = BrushPreset("blur", "Blur", BrushTip.BLUR, size = 60f, hardness = 0.3f, pressureSize = false)

    /** All brush presets offered in the brush panel for normal painting. */
    val all: List<BrushPreset> = listOf(defaultBrush)
}
