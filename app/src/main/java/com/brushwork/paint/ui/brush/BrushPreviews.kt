package com.brushwork.paint.ui.brush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushPreviewRenderer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * A stroke drawn with [preset] by the real brush engine, rendered off the main thread.
 * [trueScale] draws the brush at its real size (clamped to the box); otherwise the size is
 * normalized so every preset's character shows in a small thumbnail. Re-renders [debounceMs]
 * after the last change (slider drags) and keeps showing the previous image meanwhile.
 */
@Composable
fun BrushStrokePreview(
    preset: BrushPreset,
    toolId: ToolId,
    height: Dp,
    modifier: Modifier = Modifier,
    trueScale: Boolean = false,
    debounceMs: Long = 0L,
) {
    val density = LocalDensity.current
    val eraser = toolId == ToolId.ERASER
    val shape = RoundedCornerShape(8.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .then(if (eraser) Modifier.checkerboard(4.dp) else Modifier.background(BrushworkColors.Chrome)),
    ) {
        val wPx = max(16, constraints.maxWidth.takeIf { it != Int.MAX_VALUE } ?: with(density) { 160.dp.roundToPx() })
        val hPx = max(8, with(density) { height.roundToPx() })
        val image by produceState<ImageBitmap?>(null, preset, toolId, wPx, hPx, trueScale) {
            if (debounceMs > 0) delay(debounceMs)
            value = withContext(Dispatchers.Default) {
                val d = if (trueScale) trueScaleDiameter(preset, hPx) else BrushPreviewRenderer.thumbnailDiameter(preset, hPx)
                BrushPreviewRenderer.render(preset, toolId, wPx, hPx, d).asImageBitmap()
            }
        }
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                filterQuality = if (preset.antiAlias) FilterQuality.Low else FilterQuality.None,
            )
        } ?: Box(Modifier.fillMaxSize())
    }
}

private fun trueScaleDiameter(preset: BrushPreset, heightPx: Int): Float =
    if (preset.antiAlias) preset.size.coerceIn(1f, heightPx * 0.6f)
    else (preset.size * 4f).coerceIn(4f, heightPx * 0.6f)
