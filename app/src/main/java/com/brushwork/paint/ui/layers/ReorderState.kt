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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Long-press-drag reordering for a LazyColumn (rows must be keyed). While dragging, the caller
 * keeps a LOCAL order and applies [onMove] swaps to it; the real move happens once in [onDrop].
 * Offsets are in pixels relative to the list viewport. Holding the row against the top or bottom
 * edge scrolls the list continuously (see [autoScrollSpeed]).
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

    private var draggedDelta by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)

    private val draggingItemInfo: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingIndex }

    /** Vertical translation to apply to the dragged row. */
    val draggingOffset: Float
        get() = draggingItemInfo?.let { visualTop(it.size) - it.offset } ?: 0f

    /**
     * Where the dragged row is drawn: under the finger, but kept inside the viewport so that a
     * finger beyond the list edge pins it there (its slot then follows it while auto-scrolling
     * instead of scrolling out of view).
     */
    private fun visualTop(size: Int): Float {
        val info = listState.layoutInfo
        return LayerListMath.clampRowTop(
            initialOffset + draggedDelta, size.toFloat(),
            info.viewportStartOffset.toFloat(), info.viewportEndOffset.toFloat(),
        )
    }

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
        moveUnderDraggedRow()
    }

    /**
     * Swaps the dragged row into the slot under its middle when that belongs to another row.
     * The row is drawn at [visualTop] (it follows the finger, not its slot).
     */
    internal fun moveUnderDraggedRow() {
        val dragging = draggingItemInfo ?: return
        val middle = visualTop(dragging.size) + dragging.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            middle.toInt() in it.offset..(it.offset + it.size) && it.index != dragging.index
        } ?: return
        // Keep the scroll anchor from following the moved row when the first row is involved.
        if (dragging.index == listState.firstVisibleItemIndex || target.index == listState.firstVisibleItemIndex) {
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
        onMove(dragging.index, target.index)
        draggingIndex = target.index
    }

    /**
     * Auto-scroll velocity in px/s (negative = up) while the dragged row is pushed into the edge
     * zone of the list in the direction it was dragged; 0 otherwise (see
     * [LayerListMath.edgeScrollSpeed]). Measured from the finger, not the clamped row. The speed
     * cap (9 rows/s) keeps each frame far below one row, so the dragged slot stays composed.
     */
    internal fun autoScrollSpeed(): Float {
        if (draggingIndex == null) return 0f
        val size = draggingItemInfo?.size ?: return 0f
        val info = listState.layoutInfo
        return LayerListMath.edgeScrollSpeed(
            top = initialOffset + draggedDelta,
            size = size.toFloat(),
            travel = draggedDelta,
            viewportStart = info.viewportStartOffset.toFloat(),
            viewportEnd = info.viewportEndOffset.toFloat(),
        )
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
        snapshotFlow { state.isDragging }.collectLatest { dragging ->
            if (!dragging) return@collectLatest
            var lastFrame = 0L
            while (true) {
                val now = withFrameNanos { it }
                val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(MAX_FRAME_S)
                lastFrame = now
                val step = state.autoScrollSpeed() * dt
                if (step == 0f) continue
                val consumed = try {
                    listState.scrollBy(step)
                } catch (e: CancellationException) {
                    // A swap's requestScrollToItem cancels a scroll in progress; only stop when
                    // this loop itself was cancelled (drag ended / panel closed).
                    currentCoroutineContext().ensureActive()
                    0f
                }
                if (consumed != 0f) state.moveUnderDraggedRow()
            }
        }
    }
    return state
}

private const val MAX_FRAME_S = 0.05f

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
