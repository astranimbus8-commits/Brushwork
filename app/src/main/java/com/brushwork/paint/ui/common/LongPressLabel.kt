package com.brushwork.paint.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.brushwork.paint.ui.theme.IbisColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How long the bubble of [longPressLabel] stays (design §3.9: 1.5 s). */
const val LONG_PRESS_BUBBLE_MS = 1500L

/**
 * The one bubble of [longPressLabel] at a time: its [label] and the element it belongs to
 * ([anchor], in root coordinates). [LongPressLabelHost] provides it and draws the bubble.
 */
@Stable
class LongPressBubbles {
    var label by mutableStateOf<String?>(null)
        private set
    internal var anchor by mutableStateOf(Rect.Zero)
        private set
    internal var serial by mutableIntStateOf(0)
        private set

    fun show(label: String, anchor: Rect) {
        this.anchor = anchor
        this.label = label
        serial++
    }

    fun hide() {
        label = null
    }
}

/** The bubbles of the screen around it; null outside a [LongPressLabelHost] (then a long press shows nothing). */
val LocalLongPressBubbles = staticCompositionLocalOf<LongPressBubbles?> { null }

/**
 * v1.8 (item 9, design §3.9 and §4.6): [content] with the bubbles of its [longPressLabel]
 * elements drawn over it, under the element, for [LONG_PRESS_BUBBLE_MS]. The bubble takes no
 * touches. `EditorScreen` puts one around the whole editor.
 */
@Composable
fun LongPressLabelHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val bubbles = remember { LongPressBubbles() }
    val origin = remember { arrayOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin[0] = it.positionInRoot() }) {
        CompositionLocalProvider(LocalLongPressBubbles provides bubbles) { content() }
        val label = bubbles.label
        if (label != null) {
            val serial = bubbles.serial
            LaunchedEffect(serial) {
                delay(LONG_PRESS_BUBBLE_MS)
                if (bubbles.serial == serial) bubbles.hide()
            }
            val gap = with(LocalDensity.current) { BUBBLE_GAP_DP.dp.roundToPx() }
            val anchor = bubbles.anchor.translate(-origin[0].x, -origin[0].y)
            Layout(
                content = {
                    Box(
                        Modifier
                            .background(IbisColors.Menu, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag(V18Tags.LONG_PRESS_BUBBLE),
                    ) {
                        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                },
                modifier = Modifier.matchParentSize(),
            ) { measurables, constraints ->
                val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val x = (anchor.center.x - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                    val y = (anchor.bottom.roundToInt() + gap).coerceIn(0, (constraints.maxHeight - p.height).coerceAtLeast(0))
                    p.place(x, y)
                }
            }
        }
    }
}

/**
 * v1.8 (item 9, design §3.9 and §4.6; frozen): a long press on the element shows [label] in a
 * bubble under it for [LONG_PRESS_BUBBLE_MS] (inside a [LongPressLabelHost]). It never consumes
 * a tap, so a tap still acts; after a long press the rest of that touch is consumed, so the
 * element's click never fires for it.
 */
fun Modifier.longPressLabel(label: String): Modifier = composed {
    val bubbles = LocalLongPressBubbles.current
    val bounds = remember { arrayOf(Rect.Zero) }
    if (bubbles == null) {
        Modifier
    } else {
        Modifier
            .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
            .pointerInput(label, bubbles) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val slop = viewConfiguration.touchSlop
                    // Watched, never consumed: null = still held when the long-press time ran out.
                    val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var over = false
                        while (!over) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id }
                            // Lifted (a tap), moved (a drag) or joined by another finger: no long press.
                            over = e.changes.size > 1 || c == null || !c.pressed || (c.position - down.position).getDistance() > slop
                        }
                        true
                    }
                    if (ended == null) {
                        bubbles.show(label, bounds[0])
                        // The rest of this touch belongs to the bubble: the element's click never fires.
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            e.changes.forEach { it.consume() }
                        } while (e.changes.any { it.pressed })
                    }
                }
            }
    }
}

/** Between the element's bottom and its bubble (dp). */
private const val BUBBLE_GAP_DP = 4
