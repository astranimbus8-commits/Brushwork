package com.brushwork.paint.ui.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.Document
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class CanvasTab(val label: String) {
    IMAGE_SIZE("Image size"),
    CANVAS_SIZE("Canvas size"),
    TRIM("Trim & crop"),
    ROTATE("Rotate & flip"),
    RESOLUTION("Resolution"),
    COLOR_MODE("Color mode"),
}

/** Small flattened preview of the drawing: display bitmap + its NON-premultiplied pixels. */
internal class CanvasThumbnail(val image: ImageBitmap, val width: Int, val height: Int, val pixels: IntArray)

/**
 * Canvas adjustments: resize image, canvas size (anchor), trim/crop, rotate & flip the whole
 * canvas, resolution (dpi) and color mode. Operations run in the background under the editor's
 * busy overlay and are undoable as a single step.
 */
@Composable
fun CanvasAdjustDialog(controller: EditorController, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Shared by the size tabs so the unit choice carries over.
    var unit by rememberSaveable { mutableStateOf(LengthUnit.PX) }
    val docVersion = controller.docVersion
    val layersVersion = controller.layersVersion
    val busy = controller.busyMessage != null
    val thumbnail by produceState<CanvasThumbnail?>(null, docVersion, layersVersion) {
        value = withContext(Dispatchers.Default) { renderThumbnail(controller.doc) }
    }

    BwSheet(title = "Canvas", onDismiss = onDismiss, scrollable = false) {
        PrimaryScrollableTabRow(
            selectedTabIndex = tab,
            containerColor = BrushworkColors.Chrome,
            contentColor = BrushworkColors.OnChrome,
            edgePadding = 0.dp,
            divider = { HorizontalDivider(color = BrushworkColors.ChromeBorder) },
        ) {
            CanvasTab.entries.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(t.label, maxLines = 1) },
                    selectedContentColor = BrushworkColors.Accent,
                    unselectedContentColor = BrushworkColors.OnChromeDim,
                )
            }
        }
        if (busy) {
            // The rotate tab stays open while it works; show the editor's busy state here too.
            val progress = controller.busyProgress
            if (progress >= 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = BrushworkColors.Accent)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = BrushworkColors.Accent)
            }
            Text(
                controller.busyMessage ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            // Fields restart from the document's new values after every change.
            key(docVersion, layersVersion) {
                when (CanvasTab.entries[tab]) {
                    CanvasTab.IMAGE_SIZE -> ResizeImageTab(controller, unit, { unit = it }, busy, onApplied = onDismiss)
                    CanvasTab.CANVAS_SIZE -> CanvasSizeTab(controller, unit, { unit = it }, busy, thumbnail, onApplied = onDismiss)
                    CanvasTab.TRIM -> TrimCropTab(controller, unit, { unit = it }, busy, thumbnail, onApplied = onDismiss)
                    CanvasTab.ROTATE -> RotateFlipTab(controller, busy)
                    CanvasTab.RESOLUTION -> ResolutionTab(controller, busy, onApplied = onDismiss)
                    CanvasTab.COLOR_MODE -> ColorModeTab(controller, busy, thumbnail, onApplied = onDismiss)
                }
            }
        }
    }
}

/** Renders a ~360 px flattened preview. Reads layer bitmaps without modifying them. */
private fun renderThumbnail(doc: Document): CanvasThumbnail? {
    if (doc.layers.isEmpty()) return null
    return try {
        val bmp = Compositor(doc) { null }.renderThumbnail(360)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        CanvasThumbnail(bmp.asImageBitmap(), bmp.width, bmp.height, px)
    } catch (e: OutOfMemoryError) {
        null
    }
}
