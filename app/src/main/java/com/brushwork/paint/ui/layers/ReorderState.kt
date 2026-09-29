package com.brushwork.paint.ui.layers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Long-press-drag reordering for a LazyColumn (rows must be keyed). While dragging, the caller
 * keeps a LOCAL order and applies [onMove] swaps to it; the real move happens once in [onDrop].
 * Offsets are in pixels relative to the list viewport.
 */
class ReorderState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val canDrag: () -> Boolean,
    private val onStart: (index: Int) -> Unit,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: (index: Int) -> Unit,
) {
    /** Index of the row being dragged (in the local order), or null. */
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** Row that was just dropped and is animating back into its slot. */
    var settlingIndex by mutableStateOf<Int?>(null)
        private set
    val settleOffset = Animatable(0f)

    internal val scrollChannel = Channel<Float>(Channel.CONFLATED)

    private var draggedDelta by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)

    private val draggingItemInfo: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingIndex }

    /** Vertical translation to apply to the dragged row. */
    val draggingOffset: Float
        get() = draggingItemInfo?.let { initialOffset + draggedDelta - it.offset } ?: 0f

    val isDragging: Boolean get() = draggingIndex != null

    internal fun start(offset: Offset): Boolean {
        if (!canDrag()) return false
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { offset.y.toInt() in it.offset..(it.offset + it.size) }
            ?: return false
        draggingIndex = item.index
        initialOffset = item.offset
        draggedDelta = 0f
        onStart(item.index)
        return true
    }

    internal fun drag(dy: Float) {
        draggedDelta += dy
        val dragging = draggingItemInfo ?: return
        val start = dragging.offset + draggingOffset
        val end = start + dragging.size
        val middle = (start + end) / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            middle.toInt() in it.offset..(it.offset + it.size) && it.index != dragging.index
        }
        if (target != null) {
            // Keep the scroll anchor from following the moved row when the first row is involved.
            if (dragging.index == listState.firstVisibleItemIndex || target.index == listState.firstVisibleItemIndex) {
                listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
            onMove(dragging.index, target.index)
            draggingIndex = target.index
        } else {
            val info = listState.layoutInfo
            val overscroll = when {
                draggedDelta > 0 -> (end - info.viewportEndOffset).coerceAtLeast(0f)
                draggedDelta < 0 -> (start - info.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) scrollChannel.trySend(overscroll)
        }
    }

    internal fun end(commit: Boolean) {
        val index = draggingIndex ?: return
        if (commit) {
            // Let the dropped row glide from the finger into its slot.
            val offset = draggingOffset
            settlingIndex = index
            scope.launch {
                settleOffset.snapTo(offset)
                settleOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f))
                settlingIndex = null
            }
        }
        draggingIndex = null
        draggedDelta = 0f
        initialOffset = 0
        onDrop(if (commit) index else -1)
    }
}

/**
 * Creates a [ReorderState] and runs its edge auto-scroll. [onDrop] receives the final index, or
 * -1 when the gesture was cancelled.
 */
@Composable
fun rememberReorderState(
    listState: LazyListState,
    canDrag: () -> Boolean,
    onStart: (index: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: (index: Int) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val canDragState = rememberUpdatedState(canDrag)
    val startState = rememberUpdatedState(onStart)
    val moveState = rememberUpdatedState(onMove)
    val dropState = rememberUpdatedState(onDrop)
    val state = remember(listState) {
        ReorderState(
            listState, scope,
            canDrag = { canDragState.value() },
            onStart = { startState.value(it) },
            onMove = { a, b -> moveState.value(a, b) },
            onDrop = { dropState.value(it) },
        )
    }
    LaunchedEffect(state) {
        for (diff in state.scrollChannel) listState.scrollBy(diff)
    }
    return state
}

/** Attach to the LazyColumn: long-press a row, then drag it. */
fun Modifier.reorderContainer(state: ReorderState): Modifier =
    pointerInput(state) {
        var active = false
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> active = state.start(offset) },
            onDrag = { change, amount ->
                if (active) {
                    change.consume()
                    state.drag(amount.y)
                }
            },
            onDragEnd = { if (active) state.end(commit = true); active = false },
            onDragCancel = { if (active) state.end(commit = false); active = false },
        )
    }
