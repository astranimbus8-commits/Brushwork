package com.brushwork.paint.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brushwork.paint.AppSettings
import com.brushwork.paint.engine.live.StrokePreview
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.9 (R13, design 2.4.14; owner A6): the drawing-preview setting in the Display section of the
 * editor's settings, after [com.brushwork.paint.ui.mask.FastAdjustPreviewToggle].
 *
 * v1.9 A6 (P4): `StabilityLabels19.DRAWING_PREVIEW` with the chips `StabilityLabels19.FASTER` and
 * `StabilityLabels19.EXACT_PIXELS` and the note under them; the choice is saved in
 * `AppSettings.drawPreview` (default faster) and the next stroke uses it ([StrokePreview]).
 * Shown only while [StrokePreview.available]: LA2 cut P4 on its proxy numbers
 * ([StrokePreview.SHIPPED] false), so the Display section is as in v1.8 plus R13.
 */
@Composable
fun DrawingPreviewSetting() {
    if (!StrokePreview.available) return
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    var faster by remember(settings) { mutableStateOf(settings.drawPreview != AppSettings.DRAW_PREVIEW_EXACT) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(StabilityLabels19.DRAWING_PREVIEW, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        ChoiceChips(
            listOf(StabilityLabels19.FASTER, StabilityLabels19.EXACT_PIXELS),
            if (faster) 0 else 1,
            { i ->
                faster = i == 0
                settings.drawPreview = if (i == 0) AppSettings.DRAW_PREVIEW_FASTER else AppSettings.DRAW_PREVIEW_EXACT
            },
        )
        Text(StabilityLabels19.DRAWING_PREVIEW_NOTE, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
    }
}
