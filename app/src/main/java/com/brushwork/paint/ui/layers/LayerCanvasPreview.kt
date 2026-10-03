package com.brushwork.paint.ui.layers

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.ui.theme.IbisColors
import kotlinx.coroutines.delay

/**
 * The left column's preview (design §3.7.7): the flattened canvas on the transparency display the
 * user picked, fitted to the pane (as wide as it unless the canvas is very tall) and sitting on
 * its bottom edge as in ibisPaint, over [IbisColors.PanelOpaque]. It is rendered after composition (never
 * while the window opens), at most once per [CANVAS_PREVIEW_INTERVAL_MS] while the drawing keeps
 * changing, at no more than [MAX_PREVIEW_PX] on its longest side (a 20 MP canvas costs a scaled
 * composite of about 0.7 MP on the main thread, I3), and without tool previews (I7).
 */
@Composable
internal fun LayerCanvasPreview(controller: EditorController, display: TransparencyDisplay, modifier: Modifier = Modifier) {
    val doc = controller.doc
    // Every layer edit, property change, undo / redo or canvas operation bumps one of these.
    val editCount = controller.editCount
    val layersVersion = controller.layersVersion
    val docVersion = controller.docVersion
    // ibisPaint draws the picture as wide as the pane, flush with its bottom edge (on the buttons).
    BoxWithConstraints(modifier.background(IbisColors.PanelOpaque), contentAlignment = Alignment.BottomCenter) {
        val density = LocalDensity.current
        val px = with(density) { maxOf(maxWidth, maxHeight).roundToPx() }.coerceIn(16, MAX_PREVIEW_PX)
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        val lastRender = remember { longArrayOf(Long.MIN_VALUE / 2) }
        LaunchedEffect(px, editCount, layersVersion, docVersion) {
            // The first picture waits for the window's first frame: opening it never waits for
            // the composite (design §6.3, "Layer window open ≤ 150 ms").
            if (image == null) withFrameNanos { }
            // A newer change cancels this wait; the next one waits only what is left, so a
            // drawing that keeps changing still refreshes every interval.
            val wait = lastRender[0] + CANVAS_PREVIEW_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            lastRender[0] = SystemClock.uptimeMillis()
            val rendered = try {
                controller.compositor.renderThumbnail(px).asImageBitmap()
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: RuntimeException) {
                // A layer recycled under a closing editor: keep the last picture.
                null
            }
            if (rendered != null) image = rendered
        }
        val aspect = doc.width.toFloat() / doc.height.coerceAtLeast(1)
        Box(
            Modifier
                .testTag(LayerWindowTags.PREVIEW_PICTURE)
                .aspectRatio(aspect.coerceIn(0.01f, 100f), matchHeightConstraintsFirst = aspect < maxWidth / maxHeight.coerceAtLeast(1.dp))
                .drawBehind { transparencyBacking(display, 5.dp.toPx()) },
        ) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** How often the preview may refresh while the drawing keeps changing. */
internal const val CANVAS_PREVIEW_INTERVAL_MS = 500L

/** The preview's longest side in pixels at most (the 240 dp pane is 660 px at xxhdpi). */
internal const val MAX_PREVIEW_PX = 480

/** Draws what the canvas shows behind transparent pixels for [display] (the squares' looks). */
internal fun DrawScope.transparencyBacking(display: TransparencyDisplay, cell: Float) {
    when (display) {
        TransparencyDisplay.WHITE -> drawRect(Color.White)
        TransparencyDisplay.LIGHT_CHECKER -> checker(IbisColors.CheckerLight, IbisColors.CheckerLight2, cell)
        TransparencyDisplay.DARK_CHECKER -> checker(IbisColors.CheckerDark, IbisColors.CheckerDark2, cell)
        TransparencyDisplay.NONE -> drawRect(IbisColors.Surround)
    }
}

internal fun DrawScope.checker(a: Color, b: Color, cell: Float) {
    drawRect(a)
    val n = cell.coerceAtLeast(1f)
    var y = 0f
    var r = 0
    while (y < size.height) {
        var x = if (r % 2 == 0) n else 0f
        while (x < size.width) {
            drawRect(b, topLeft = Offset(x, y), size = Size(minOf(n, size.width - x), minOf(n, size.height - y)))
            x += 2 * n
        }
        y += n
        r++
    }
}
