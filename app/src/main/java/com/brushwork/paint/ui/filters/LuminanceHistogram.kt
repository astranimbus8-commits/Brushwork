package com.brushwork.paint.ui.filters

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.filters.adjust.ToneFilter

/** True when [filterId]'s sliders show the luminance histogram above them (Tone, §4.8a). */
internal fun showsHistogram(filterId: String?): Boolean = filterId == ToneFilter.ID

/**
 * The luminance histogram (256 bins) of what a filter or an adjustment works on, square-root
 * scaled so small counts stay visible ([CurveEditing.histogramHeights]); shown above Tone's
 * sliders in the Filters panel and the Masks tool's Adjust sheet (§4.8a). Draws nothing until
 * [histogram] is known (it is measured off the main thread) or when it is empty.
 */
@Composable
internal fun LuminanceHistogram(histogram: IntArray?, modifier: Modifier = Modifier) {
    val heights = remember(histogram) { histogram?.let { CurveEditing.histogramHeights(it) } } ?: return
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF141518))
            .semantics { contentDescription = "Luminance histogram" },
    ) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val w = size.width / heights.size
            val color = Color(0xFF9AA0A6).copy(alpha = 0.8f)
            for (i in heights.indices) {
                val v = heights[i]
                if (v <= 0f) continue
                val x = i * w + w / 2f
                drawLine(color, Offset(x, size.height), Offset(x, size.height * (1f - v)), strokeWidth = w.coerceAtLeast(1f))
            }
        }
    }
}
