package com.brushwork.paint.ui.layers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drag reordering for a LazyColumn (rows must be keyed). While dragging, the caller keeps a
 * LOCAL order and applies [onMove] swaps to it; the real move happens once in [onDrop]. Offsets
 * are in pixels relative to the list viewport. Holding the row against the top or bottom edge
 * scrolls the list continuously (see [autoScrollSpeed]). Only items for which [isReorderable] is
 * true can be dragged or swapped with (the layer window's Selection Layer row stays first).
 */
class ReorderState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val canDrag: () -> Boolean,
    private val onStart: (index: Int) -> Unit,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: (index: Int) -> Unit,
    internal val isReorderable: (index: Int) -> Boolean = { true },
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

    /** The dragged row has moved since the drag started (a long press without a move is no drag). */
    var moved = false
        private set

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

    /** The visible item under [y] (viewport pixels), or null. */
    internal fun itemAt(y: Float): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { y.toInt() in it.offset..(it.offset + it.size) }

    /** Starts dragging the row under [offset]; false when there is none, it can't move or a drag runs. */
    internal fun start(offset: Offset): Boolean {
        val item = itemAt(offset.y) ?: return false
        return startItem(item)
    }

    /** Starts dragging the visible item [index] (the ≡ handle); false as [start]. */
    internal fun startAt(index: Int): Boolean {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
        return startItem(item)
    }

    private fun startItem(item: LazyListItemInfo): Boolean {
        if (isDragging || !canDrag() || !isReorderable(item.index)) return false
        draggingIndex = item.index
        initialOffset = item.offset
        draggedDelta = 0f
        moved = false
        onStart(item.index)
        return true
    }

    internal fun drag(dy: Float) {
        if (dy != 0f) moved = true
        draggedDelta += dy
        moveUnderDraggedRow()
    }

    /**
     * Swaps the dragged row into the slot under its middle when that belongs to another row that
     * can be reordered. The row is drawn at [visualTop] (it follows the finger, not its slot).
     */
    internal fun moveUnderDraggedRow() {
        val dragging = draggingItemInfo ?: return
        val middle = visualTop(dragging.size) + dragging.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            middle.toInt() in it.offset..(it.offset + it.size) && it.index != dragging.index
        } ?: return
        if (!isReorderable(target.index)) return
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
    isReorderable: (index: Int) -> Boolean = { true },
): ReorderState {
    val scope = rememberCoroutineScope()
    val canDragState = rememberUpdatedState(canDrag)
    val startState = rememberUpdatedState(onStart)
    val moveState = rememberUpdatedState(onMove)
    val dropState = rememberUpdatedState(onDrop)
    val reorderableState = rememberUpdatedState(isReorderable)
    val state = remember(listState) {
        ReorderState(
            listState, scope,
            canDrag = { canDragState.value() },
            onStart = { startState.value(it) },
            onMove = { a, b -> moveState.value(a, b) },
            onDrop = { dropState.value(it) },
            isReorderable = { reorderableState.value(it) },
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

/**
 * Attach to the LazyColumn. Two ways to drag a row:
 * - from its ≡ handle (the rightmost [handleWidth] of the list): at once, after the touch slop;
 * - from anywhere else: long-press, then move (v1.5).
 *
 * A long press released without moving is [onLongPress] of that item instead (the layer window
 * opens the layer's ⋮ menu); [onLongPressStart] runs when the long press is recognised (haptics).
 * Every move of a drag is consumed from the first one, so the list's own scrolling and the rows'
 * taps give way.
 */
fun Modifier.reorderContainer(
    state: ReorderState,
    handleWidth: Dp = 0.dp,
    onLongPressStart: (index: Int) -> Unit = {},
    onLongPress: (index: Int) -> Unit = {},
): Modifier = pointerInput(state, handleWidth) {
    val handlePx = handleWidth.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val item = state.itemAt(down.position.y)
        if (item != null && handlePx > 0f && down.position.x >= size.width - handlePx && state.isReorderable(item.index)) {
            // The ≡ handle: drag at once.
            var started = false
            var travel = 0f
            var commit = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    commit = started
                    if (started) change.consume()
                    break
                }
                if (!started && change.isConsumed) break // the list took it (a fast fling)
                val dy = change.positionChange().y
                change.consume()
                if (started) {
                    state.drag(dy)
                } else {
                    travel += dy
                    if (abs(travel) > viewConfiguration.touchSlop) {
                        started = state.startAt(item.index)
                        if (!started) break
                        state.drag(travel)
                    }
                }
            }
            if (started) state.end(commit = commit)
            return@awaitEachGesture
        }
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val pressed = state.itemAt(longPress.position.y) ?: return@awaitEachGesture
        onLongPressStart(pressed.index)
        if (state.start(longPress.position)) {
            val completed = drag(longPress.id) { change ->
                state.drag(change.positionChange().y)
                change.consume()
            }
            if (state.moved) {
                state.end(commit = completed)
            } else {
                state.end(commit = false)
                if (completed) onLongPress(pressed.index)
            }
        } else {
            // Not draggable (a single layer, the Selection Layer row): a menu on release.
            var lifted = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == longPress.id } ?: break
                if (change.changedToUpIgnoreConsumed()) { lifted = !change.isConsumed; break }
                if (change.isConsumed) break
            }
            if (lifted) onLongPress(pressed.index)
        }
    }
}
