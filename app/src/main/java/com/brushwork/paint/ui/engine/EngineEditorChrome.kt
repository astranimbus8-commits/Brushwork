package com.brushwork.paint.ui.engine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.delay

/**
 * v1.10 (F1, J32, design 2.7 and 2.8; signature frozen, A10 body): the engine's chrome over the
 * editor (`EditorScreen`): the ring while a layer is made ready (`EditorController.activating`),
 * the low-storage message, the frame metrics. F1: nothing.
 *
 * A10: the session's swap store. Once the editor shows a session that runs at FULL, a
 * [SlabSwapStore] becomes `EngineConfig.swapStore` ([SlabSwapStore.installIfFull]; at CLASSIC and
 * TILES, with a store already installed, or under Robolectric nothing happens); the session's end
 * (`EngineConfig.closeSession`) closes it and deletes its directory. Installed here and not in
 * `EditorSession` (region `v110-A10 session`) until A11's merge (design 4.0, `$Sequential`).
 *
 * A10: the activation ring (design 2.7). While a layer is made ready asynchronously, canvas input
 * is held without a toast; when that lasts [RING_DELAY_MS] (250 ms), the pill slot (under the
 * options strip, `ChromeLayout.pillTop`) shows a small ring with [EngineLabels110.PREPARING_LAYER]
 * until the layer is ready. A quicker activation shows nothing.
 */
@Composable
fun EngineEditorChrome(controller: EditorController) {
    val context = LocalContext.current
    LaunchedEffect(controller) { SlabSwapStore.installIfFull(context.applicationContext) }

    val activating = controller.activating
    var ring by remember { mutableStateOf(false) }
    LaunchedEffect(activating) {
        ring = false
        if (activating != null) {
            delay(RING_DELAY_MS)
            ring = true
        }
    }
    if (activating != null && ring) PreparingRing()
}

/** The ring appears after an activation has held the canvas this long. */
internal const val RING_DELAY_MS = 250L

/** The ring and its text, in the pill slot under the options strip. */
@Composable
private fun PreparingRing() {
    val density = LocalDensity.current
    val statusDp = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }.value
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .padding(top = ChromeLayout.pillTop(statusDp).dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 360.dp)
                .testTag(PREPARING_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = BrushworkColors.ChromeHigh,
            contentColor = BrushworkColors.OnChrome,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BrushworkColors.ChromeBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BrushworkColors.Accent, strokeWidth = 2.dp)
                Text(EngineLabels110.PREPARING_LAYER, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The ring's test tag (it is never a label). */
internal const val PREPARING_TAG = "engine.preparing"
