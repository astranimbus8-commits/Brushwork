package com.brushwork.paint.ui.placement

import androidx.compose.runtime.Composable
import com.brushwork.paint.tools.text.TextPathSpec

// STUB — replaced by the text-path engine. Keep the signature.
/**
 * Settings for text on a path: type (straight / line / circle / square-rectangle / curve), bend or
 * rotate letters, side, direction, alignment, offset, and exact numbers (with sliders) for the
 * path's shape. [dpi] is for unit display; [onChange] gets every edit live.
 */
@Composable
fun TextPathControls(spec: TextPathSpec, dpi: Float, onChange: (TextPathSpec) -> Unit) {}
