package com.brushwork.paint.tools.text.frames

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.PositionedTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.PlaceholderAmount
import com.brushwork.paint.tools.text.PlaceholderKind
import com.brushwork.paint.tools.text.PlaceholderText
import com.brushwork.paint.tools.text.PreparedText
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextWrapSpec
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.offset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Text frames tool (v1.6, §3.6; area D): linked text boxes, InDesign style.
 *
 * - **Draw** a frame by dragging on empty canvas (a dashed rectangle, at least 32 dp on screen;
 *   snapping and Length increments apply). On release the story editor opens; OK creates the
 *   frame layer with its text as one step ("Add text frame"), Cancel (or OK with no text)
 *   creates nothing.
 * - **Overflow:** text that doesn't fit stops at the frame's bottom; the frame's out-port (just
 *   outside its bottom-right corner) shows a red "+", its box turns red and the options strip
 *   reads "+ 132 characters".
 * - **Link:** tap a frame's out-port (or "Link…"): it is loaded. Then drag a new rectangle
 *   anywhere (a frame created after it, the story flowing on into it) or tap a standalone
 *   horizontal text box or a one-frame story (it joins after the loaded frame, its text appended
 *   to the story after a paragraph break, in the story's look, keeping its box size); one step
 *   "Link frame". Pressing an out-port and dragging does both in one motion. Tapping empty canvas
 *   cancels. Thread lines join each out-port to the next in-port.
 * - **Select** a frame by tapping it (it becomes the active layer): 8 resize handles, drag inside
 *   to move, the X / Y pill shows and moves its centre. On release the chain re-flows and the
 *   change is one step ("Move frame" / "Resize frame"); while dragging, only the affected frames
 *   preview (one multi-layer render override, [ThreadPreview]). Tapping the selected frame again
 *   (or "Edit story") opens the story editor: one step "Edit story".
 * - **Unlink here:** the frames after the selected one become empty standalone frames and their
 *   text comes back as overset of the selected one ("Unlink frame"). **Delete frame** deletes
 *   its layer: the remaining frames re-flow in the same step ([TextThreads]).
 * - **Wrap around picture** (per frame): the selected frame's lines go around a picture's outline
 *   ("Wrap frame"); edits of the picture re-flow the story inside their own step.
 * - **Increments** (Length): a move travels in whole steps from where the finger went down, a
 *   dragged edge and a drawn frame land on whole steps from the fixed edge / start corner; object
 *   snapping (and the grid through it) wins over the step. Typed pill values are exact.
 *
 * Every frame is a document-sized text layer whose `textData` is an ordinary [TextItem] holding
 * its slice of the story plus the story itself (`TextItem.thread`). Frames are horizontal,
 * straight and unrotated. Undo and redo never re-flow (I2).
 */
class TextFrameTool(controller: EditorController) : Tool(controller), PositionedTool {
    override val id = ToolId.TEXT_FRAMES

    private val threads: TextThreads get() = controller.textThreads
    private val doc get() = controller.doc

    /** The story editor (the host of the text editor dialog while [StoryEditorHost.isOpen]). */
    val story = StoryEditorHost(this)

    private var selectedState by mutableStateOf<Layer?>(null)

    /** The selected frame (it is the active layer), or null. */
    val selected: Layer?
        get() = selectedState?.takeIf { doc.indexOf(it) >= 0 && threads.isFrame(it) }

    private var linkFromState by mutableStateOf<Layer?>(null)

    /**
     * The frame whose out-port was tapped (link mode: the next frame or text joins after it), or
     * null. Checked on every read: an undo that takes the loaded frame away ends link mode.
     */
    val linkFrom: Layer?
        get() = linkFromState?.takeIf { doc.indexOf(it) >= 0 && threads.isFrame(it) }

    /** Thread lines are shown (the "Threads" chip). */
    var showThreads by mutableStateOf(true)

    /** Bumped whenever this tool changes frames or its pending state (Compose readers refresh). */
    var revision by mutableIntStateOf(0)
        private set

    private val preview = ThreadPreview(controller, controller.textThreads)
    private val overlay = FrameOverlay()

    /** The look of the next new frame's story (the last story written; its colour is the drawing colour). */
    private var nextSpec: TextSpec? = null

    override val hasPendingWork: Boolean get() = story.isOpen || positionEdit != null

    /**
     * What undo takes back first: a new frame being typed, a story edited in the open editor, a
     * frame moved with the X / Y pill. A story editor opened on a frame and left untouched (like
     * a text layer only tapped in the Text tool) doesn't swallow an undo: it closes and the last
     * step is undone.
     */
    override val hasUserChanges: Boolean
        get() = (story.isOpen && (story.editingNew || story.item != story.opened)) ||
            positionEdit.let { it != null && it.second != it.first }

    // ------------------------------------------------------------------ selection

    /**
     * Selects frame [layer] (switching to this tool is the caller's job) and opens its story
     * editor when [openEditor]; false if [layer] is not a frame.
     */
    fun openFrame(layer: Layer, openEditor: Boolean): Boolean {
        if (doc.indexOf(layer) < 0 || !threads.isFrame(layer)) return false
        if (story.isOpen && storyTarget === layer) return true
        if (hasPendingWork) commit()
        if (doc.indexOf(layer) < 0) return false
        select(layer)
        if (openEditor) openStoryEditor(layer)
        return true
    }

    /** Selects [layer] (a frame; null = none). The selected frame is the active layer. */
    fun select(layer: Layer?) {
        if (layer != null && controller.activeLayer !== layer) controller.selectLayer(layer)
        selectedState = layer?.takeIf { doc.indexOf(it) >= 0 }
        changed()
    }

    private fun changed() {
        revision++
        controller.invalidateOverlay()
    }

    override fun onActivate() {
        val a = controller.activeLayer
        selectedState = if (threads.isFrame(a)) a else null
        // A layer operation paused this tool (onDeactivate stops the pulse): a loaded out-port
        // that is still there keeps pulsing; one that went away ends link mode.
        if (linkFrom != null) startPulse() else linkFromState = null
        changed()
    }

    override fun onSelected() {
        linkFromState = null
        stopPulse()
    }

    override fun onDeactivate() {
        cancelGesture()
        if (hasPendingWork) commit()
        stopPulse()
        // Never leave a preview installed without pending work.
        if (!hasPendingWork) preview.clear()
    }

    override fun onDispose() {
        cancelPreviewTimer()
        stopPulse()
        preview.clear()
        hitTexts.clear()
        // The editor closes: the measured stories and decoded frames go too (several MB at most).
        threads.release()
    }

    // ------------------------------------------------------------------ pending work (✓ / ✕)

    /** ✓: writes the open story edit or pill move. */
    override fun commit() {
        if (story.isOpen) confirmStoryEditor()
        if (positionEdit != null) endPositionEdit()
    }

    /** ✕ (and undo of pending work): drops it. */
    override fun discard() {
        if (story.isOpen) cancelStoryEditor()
        if (positionEdit != null) {
            positionEdit = null
            preview.clear()
            changed()
        }
        linkFromState = null
        stopPulse()
    }

    // ------------------------------------------------------------------ the story editor

    /** The frame the story editor was opened from (null = a new frame). */
    internal var storyTarget: Layer? = null
        private set
    private var storyIdEditing = 0L

    /** The new frame being typed (a frame of a story of its own), as the overlay shows it. */
    private var pendingNew: TextItem? = null
    private var pendingNewPrepared: PreparedText? = null

    /** The pending story's frames as flowed by the last preview (overlay: their red "+"). */
    private var pendingFlow: List<TextItem>? = null

    /**
     * Opens the story editor on [layer]'s story (default: the selected frame). Refused with a
     * message when a frame of the story is locked ("Unlock frame k…") or [layer] can't be edited.
     */
    fun openStoryEditor(layer: Layer? = selected): Boolean {
        val l = layer ?: return false
        val item = threads.frameOf(l) ?: return false
        if (story.isOpen) return storyTarget === l
        if (!controller.checkEditable(l)) return false
        val frames = threads.framesOf(item.thread.storyId)
        val locked = frames.indexOfFirst { it.layer.locked }
        if (locked >= 0) {
            controller.toast(threads.lockedMessage(locked))
            return false
        }
        val s = threads.storyOf(frames) ?: return false
        linkFromState = null
        storyTarget = l
        storyIdEditing = s.id
        // The frame's own place, size and wrap around a picture stay as they are (the flow takes
        // them from this item for the frame it was opened from).
        story.open(TextItem(text = s.text, spec = FrameGeometry.withFrameBox(s.spec, item.spec.box), cx = item.cx, cy = item.cy, wrap = item.wrap), new = false)
        pendingFlow = null
        changed()
        return true
    }

    /** The look a new frame's story starts with: the last story's (sized 5 % of the canvas height at first), in the drawing colour. */
    fun specForNewFrame(): TextSpec {
        val size = (doc.height * 0.05f).coerceIn(TextSpec.MIN_SIZE_PX, 2f * max(doc.width, doc.height))
        return (nextSpec ?: TextSpec(sizePx = size)).copy(color = controller.color, vertical = false)
    }

    /** Opens the story editor for a new frame whose outer box is [r] (document px). */
    private fun startNewFrame(r: RectF) {
        if (!controller.canAddLayer) {
            controller.toast(TextThreads.LAYER_LIMIT.format(controller.maxLayers))
            return
        }
        val template = FrameGeometry.placed(TextItem(spec = specForNewFrame()), r.left, r.top, r.right, r.bottom)
        linkFromState = null
        storyTarget = null
        storyIdEditing = 0L
        story.open(template.copy(text = ""), new = true)
        refreshStoryPreview()
        changed()
    }

    /** The story editor's pending story changed: preview it (re-flowed at most every [storyPreviewMs] ms). */
    internal fun onStoryChanged() {
        requestPreview(storyPreviewMs)
        changed()
    }

    /** OK: writes the story (one step); a new frame with no text is not created. */
    fun confirmStoryEditor() {
        val cur = story.item ?: return
        val opened = story.opened
        val new = story.editingNew
        val target = storyTarget
        val id = storyIdEditing
        if (new && cur.text.isNotBlank() && !controller.canAddLayer) {
            controller.toast(TextThreads.LAYER_LIMIT.format(controller.maxLayers))
            return
        }
        // Nothing pending from here on: writing pauses this tool (withToolPaused), which must
        // then find nothing to commit.
        closeStoryEditor()
        if (new) {
            if (cur.text.isBlank()) return
            val sid = threads.newStoryId()
            val layers = threads.writeStory(ADD_LABEL, sid, cur.text, cur.spec, listOf(FlowFrame(null, cur))) ?: return
            nextSpec = FrameGeometry.storyLook(cur.spec)
            select(layers.first())
            return
        }
        if (cur == opened || target == null || doc.indexOf(target) < 0) return
        val frames = threads.framesOf(id)
        if (frames.none { it.layer === target }) return
        val chain = frames.map { f -> if (f.layer === target) FlowFrame(target, cur) else FlowFrame(f.layer, f.item) }
        threads.writeStory(EDIT_LABEL, id, cur.text, cur.spec, chain)
        nextSpec = FrameGeometry.storyLook(cur.spec)
        changed()
    }

    /** Cancel: drops the pending story (a new frame is not created). */
    fun cancelStoryEditor() {
        closeStoryEditor()
    }

    private fun closeStoryEditor() {
        story.close()
        cancelPreviewTimer()
        preview.clear()
        storyTarget = null
        storyIdEditing = 0L
        pendingNew = null
        pendingNewPrepared = null
        pendingFlow = null
        changed()
    }

    /** The frames of the story being edited, with the pending story flowed into them (null when there is none). */
    private fun pendingStoryFlow(cur: TextItem): Pair<List<TextThreads.Frame>, List<TextItem>>? {
        val frames = threads.framesOf(storyIdEditing)
        val s = threads.storyOf(frames) ?: return null
        val chain = frames.map { f -> if (f.layer === storyTarget) FlowFrame(f.layer, cur) else FlowFrame(f.layer, f.item) }
        return frames to TextThreadFlow.flow(cur.text, cur.spec, chain, s.id, s.rev, threads.measures)
    }

    private fun refreshStoryPreview() {
        val cur = story.item ?: return
        if (story.editingNew) {
            preview.clear()
            val item = TextThreadFlow.flow(cur.text, cur.spec, listOf(FlowFrame(null, cur)), PREVIEW_STORY_ID, 0L, threads.measures).first()
            pendingNew = item
            pendingNewPrepared = threads.prepare(item)
            controller.invalidateOverlay()
            return
        }
        val (frames, items) = pendingStoryFlow(cur) ?: return
        pendingFlow = items
        val changedItems = LinkedHashMap<Layer, TextItem>()
        for (k in frames.indices) if (!StoryWriter.sameRendering(frames[k].item, items[k])) changedItems[frames[k].layer] = items[k]
        preview.show(changedItems)
        controller.invalidateOverlay()
    }

    /**
     * "Fill the box" for a story (§3.6, InDesign's fill of a thread): [cur]'s text (or nothing,
     * [replace]) followed by as much placeholder text of [kind] as ALL the frames of the story
     * hold, laid out by the frames' own layout. Null when not even one more word fits.
     */
    internal fun fillChain(cur: TextItem, kind: PlaceholderKind, replace: Boolean): String? {
        val base = if (replace) "" else cur.text
        val prefix = base + PlaceholderText.separator(base, kind, PlaceholderAmount.FILL)
        val chain: List<FlowFrame> = if (story.editingNew || storyTarget == null) {
            listOf(FlowFrame(null, cur))
        } else {
            threads.framesOf(storyIdEditing).map { f -> if (f.layer === storyTarget) FlowFrame(f.layer, cur) else FlowFrame(f.layer, f.item) }
        }
        if (chain.isEmpty()) return null
        // Room for about this many characters (area / (half an em × a line)), grown until it overflows.
        val area = chain.sumOf { (FrameGeometry.contentWidth(it.template) * FrameGeometry.contentHeight(it.template)).toDouble() }
        val em = max(1f, cur.spec.sizePx).toDouble()
        var want = ((area / (0.45 * em * em * max(0.5f, cur.spec.lineSpacing))) * 1.3 + 64).toInt().coerceIn(64, MAX_FILL_CHARS)
        while (true) {
            val tokens = PlaceholderText.tokens(kind, max(0, want - prefix.length))
            if (tokens.isEmpty()) return null
            val sb = StringBuilder(prefix)
            tokens.forEachIndexed { i, tok -> sb.append(if (i == 0 && (prefix.isEmpty() || prefix.last().isWhitespace())) tok.trimStart() else tok) }
            val full = TextThreadFlow.cap(sb.toString())
            // Not through the measure cache: these trial stories are thrown away.
            val items = TextThreadFlow.flow(full, cur.spec, chain, PREVIEW_STORY_ID, 0L)
            val last = items.last()
            if (last.thread.overset || last.thread.end < full.length) {
                val end = last.thread.end
                if (end <= prefix.length) return null
                return full.substring(0, end).trimEnd()
            }
            if (want >= MAX_FILL_CHARS || full.length < want - 1) return full
            want = min(MAX_FILL_CHARS, want * 2)
        }
    }

    // ------------------------------------------------------------------ preview timing

    /** Least time between two re-flow previews while typing (ms; trailing edge). */
    internal var storyPreviewMs = STORY_PREVIEW_MS

    /** Least time between two re-flow previews while a frame is resized (ms; trailing edge). */
    internal var dragPreviewMs = DRAG_PREVIEW_MS

    private val handler = Handler(Looper.getMainLooper())
    private var lastPreviewAt = Long.MIN_VALUE / 2
    private var previewQueued = false
    private val previewRunnable = Runnable {
        previewQueued = false
        runPreview()
    }

    private fun requestPreview(minIntervalMs: Long) {
        val now = SystemClock.uptimeMillis()
        val wait = lastPreviewAt + minIntervalMs - now
        if (wait <= 0 && !previewQueued) {
            runPreview()
            return
        }
        if (!previewQueued) {
            previewQueued = true
            handler.postDelayed(previewRunnable, max(0L, wait))
        }
    }

    private fun cancelPreviewTimer() {
        if (previewQueued) handler.removeCallbacks(previewRunnable)
        previewQueued = false
    }

    /** Runs a preview that is waiting for its time now (tests). */
    internal fun flushPreview() {
        if (!previewQueued) return
        cancelPreviewTimer()
        runPreview()
    }

    /** Previews re-flowed since this tool was made (tests: the typing throttle). */
    internal var previewRuns = 0
        private set

    private fun runPreview() {
        previewRuns++
        lastPreviewAt = SystemClock.uptimeMillis()
        when {
            story.isOpen -> refreshStoryPreview()
            dragLayer != null || positionEdit != null -> refreshDragPreview()
        }
    }

    // ------------------------------------------------------------------ gestures

    private enum class Mode { NONE, DRAW, MOVE, RESIZE, PORT }

    private var mode = Mode.NONE
    private var downDoc = Vec2.ZERO
    private var moved = false

    /** The frame under the finger when it went down (move / tap), its item then and its box. */
    private var dragLayer: Layer? = null
    private var dragStart: TextItem? = null
    private var dragHandle: FrameGeometry.Handle? = null

    /** The dragged frame as it is now (pending). */
    private var dragItem: TextItem? = null

    /** The out-port touched. */
    private var portLayer: Layer? = null

    /**
     * Another frame's out-port a press on one of the selected frame's handles started ON (within
     * [FramePorts.TAP_DP] of its centre), or null: a frame drawn from a port has its top-left
     * handle exactly there. A tap is then the port's, a drag stays the handle's.
     */
    private var tapPortLayer: Layer? = null

    /** The rectangle being drawn (document px). */
    private var drawRect: RectF? = null

    /** The start corner of a frame being drawn (snapped). */
    private var drawStart = Vec2.ZERO

    /** Shown once per drag on a locked frame. */
    private var lockedToastShown = false

    private val snap = controller.newSnapSession()
    private var startBox: DocBox? = null

    private fun beginSnap() {
        snap.begin(exclude = listOfNotNull(dragLayer), includeSelection = true)
    }

    private fun endSnap() {
        startBox = null
        snap.end()
    }

    /** Smallest frame side (document px): [MIN_FRAME_DP] on screen. */
    private fun minSide(t: ViewTransform): Float = t.screenToDocLength(t.dp(MIN_FRAME_DP))

    override fun onDown(p: ToolPoint) {
        mode = Mode.NONE
        moved = false
        lockedToastShown = false
        tapPortLayer = null
        endSnap()
        // While the story editor is open the canvas shows its preview (drags don't edit frames).
        if (story.isOpen) return
        val t = controller.viewTransform
        downDoc = Vec2(p.x, p.y)
        val s = t.docToScreen(downDoc)
        // Link mode (an out-port is loaded): a drag anywhere draws the next frame and a tap joins
        // the text box under the finger (§3.6a); only the ports stay live (tapping the loaded
        // one again unloads it).
        val linking = linkFrom != null
        // The nearest of the selected frame's handles and the frames' out-ports within reach.
        var best = Float.MAX_VALUE
        val sel = selected?.takeIf { it.visible && !linking }
        val selItem = sel?.let { threads.frameOf(it) }
        if (sel != null && selItem != null) {
            val r = FrameGeometry.outerRect(selItem)
            // Inside a small frame the handles reach in only a quarter of its side, so the middle
            // still moves it (a 32 dp frame would otherwise be all handles); outside, the full reach.
            val inside = r.contains(downDoc.x, downDoc.y)
            val side = min(r.width(), r.height()) * t.zoom
            val reach = if (inside) min(t.dp(HANDLE_HIT_DP), side / 4f) else t.dp(HANDLE_HIT_DP)
            for (h in FrameGeometry.Handle.entries) {
                val d = s.distanceTo(t.docToScreen(h.at(r)))
                if (d <= reach && d < best) {
                    best = d
                    mode = Mode.RESIZE
                    dragHandle = h
                    dragLayer = sel
                    dragStart = selItem
                }
            }
        }
        // Another frame's out-port the finger is on, if any (the nearest; see FramePorts.isOn).
        // The selected frame's own out-port keeps the nearest-wins rule with its handles.
        var onPort: Layer? = null
        var onPortD = Float.MAX_VALUE
        for (f in threads.allFrames()) {
            if (!f.layer.visible) continue
            val at = FramePorts.outPort(t, FrameGeometry.outerRect(f.item))
            val d = s.distanceTo(at)
            if (d <= t.dp(PORT_HIT_DP) && d < best) {
                best = d
                mode = Mode.PORT
                portLayer = f.layer
            }
            if (f.layer !== sel && FramePorts.isOn(t, s, at) && d < onPortD) {
                onPortD = d
                onPort = f.layer
            }
        }
        if (mode != Mode.NONE) {
            if (mode == Mode.RESIZE) {
                // A handle on a port (a frame drawn from that port): a tap is the port's (onUp).
                tapPortLayer = onPort
                beginSnap()
            }
            return
        }
        val hit = if (linking) null else frameAt(downDoc)
        if (hit != null) {
            mode = Mode.MOVE
            dragLayer = hit.layer
            dragStart = hit.item
            startBox = FrameGeometry.outerRect(hit.item).let { DocBox(it.left, it.top, it.right, it.bottom) }
            beginSnap()
            return
        }
        beginDraw()
    }

    /** A drag from [downDoc] draws a frame (the start corner snaps to guides and the grid). */
    private fun beginDraw() {
        mode = Mode.DRAW
        snap.begin(includeSelection = true)
        drawStart = if (controller.snapping.enabled || controller.grid.snap) snap.snapPoint(downDoc) else downDoc
    }

    override fun onMove(p: ToolPoint) {
        if (mode == Mode.NONE) return
        val t = controller.viewTransform
        val q = Vec2(p.x, p.y)
        if (!moved) {
            if (t.docToScreen(q).distanceTo(t.docToScreen(downDoc)) < t.dp(TOUCH_SLOP_DP)) return
            moved = true
            // A finger that presses an out-port (the red "+") and travels draws the NEXT frame of
            // that story: the tap-then-drag of link mode in one motion. The port's frame is loaded
            // here without selecting it (selecting would pause this tool mid-gesture); the link
            // itself selects as usual.
            if (mode == Mode.PORT) {
                val from = portLayer?.takeIf { doc.indexOf(it) >= 0 && threads.isFrame(it) }
                portLayer = null
                if (from != null && linkFromState !== from) {
                    linkFromState = from
                    startPulse()
                    changed()
                }
                beginDraw()
            }
        }
        when (mode) {
            Mode.DRAW -> drawRect = drawnRect(q)
            Mode.MOVE -> {
                val layer = dragLayer ?: return
                val start = dragStart ?: return
                if (!canEditFrame(layer)) return
                dragItem = movedItem(start, q)
                // A frame that wraps around a picture re-breaks its lines (and the chain re-flows)
                // as it moves: throttled like a resize. Otherwise only its place changes.
                requestPreview(if (start.wrapActive) dragPreviewMs else 0L)
            }
            Mode.RESIZE -> {
                val layer = dragLayer ?: return
                val start = dragStart ?: return
                if (!canEditFrame(layer)) return
                dragItem = resizedItem(start, q, t)
                requestPreview(dragPreviewMs)
            }
            else -> {}
        }
        controller.invalidateOverlay()
    }

    /** A frame being dragged can be changed (not locked or hidden; told once per drag). */
    private fun canEditFrame(layer: Layer): Boolean {
        if (!layer.locked && layer.visible) return true
        if (!lockedToastShown) {
            lockedToastShown = true
            controller.checkEditable(layer)
        }
        return false
    }

    /**
     * True when grid snapping applies ([com.brushwork.paint.snap.SnapService.gridPoint]'s rule: a
     * shown square grid with snapping on).
     */
    private val gridSnaps: Boolean
        get() = controller.grid.let { it.enabled && it.snap && it.type == GridType.SQUARE && it.spacingPx > 0f }

    /**
     * [start] moved by the finger's travel to [q], per axis (§3.4 precedence): onto an object's
     * guide, else its top-left corner onto the grid, else in whole Length steps.
     */
    private fun movedItem(start: TextItem, q: Vec2): TextItem {
        val rawDx = q.x - downDoc.x
        val rawDy = q.y - downDoc.y
        var dx = rawDx
        var dy = rawDy
        val inc = controller.increments
        val stepped = inc.lengthDelta(Vec2(dx, dy))
        val grid = startBox?.takeIf { gridSnaps }?.let { b ->
            controller.snapping.gridPoint(Vec2(b.left + rawDx, b.top + rawDy)).let { g -> Vec2(g.x - b.left, g.y - b.top) }
        }
        var snappedX = false
        var snappedY = false
        startBox?.let { b ->
            val r = snap.snapMove(b.offset(dx, dy))
            if (r.snappedX) { dx += r.dx; snappedX = true }
            if (r.snappedY) { dy += r.dy; snappedY = true }
        }
        if (!snappedX) dx = grid?.x ?: stepped.x
        if (!snappedY) dy = grid?.y ?: stepped.y
        inc.readout = if (inc.enabled) "${signed(dx)}, ${signed(dy)} px" else null
        return start.copy(cx = start.cx + dx, cy = start.cy + dy)
    }

    /**
     * [start] with handle [dragHandle] dragged to [q]: the opposite edges stay; each dragged edge
     * goes onto an object's guide, else onto the grid, else whole Length steps from the fixed edge.
     */
    private fun resizedItem(start: TextItem, q: Vec2, t: ViewTransform): TextItem {
        val h = dragHandle ?: return start
        val r0 = FrameGeometry.outerRect(start)
        val min = minSide(t)
        var x = q.x
        var y = q.y
        val inc = controller.increments
        val grid = if (gridSnaps) controller.snapping.gridPoint(q) else null
        if (h.dx != 0) {
            val hit = snap.snapValue(x, SnapAxis.X)
            x = hit?.pos ?: grid?.x ?: steppedFrom(x, if (h.dx > 0) r0.left else r0.right, h.dx)
        }
        if (h.dy != 0) {
            val hit = snap.snapValue(y, SnapAxis.Y)
            y = hit?.pos ?: grid?.y ?: steppedFrom(y, if (h.dy > 0) r0.top else r0.bottom, h.dy)
        }
        // A finger past the opposite edge: [FrameGeometry.resized] holds the frame at its least size.
        val r = FrameGeometry.resized(r0, h, Vec2(x, y), min, min)
        snap.showGuidesFor(DocBox(r.left, r.top, r.right, r.bottom))
        inc.readout = if (inc.enabled) "${Units.formatNumber(r.width().toDouble(), 0)} × ${Units.formatNumber(r.height().toDouble(), 0)} px" else null
        return FrameGeometry.placed(start, r.left, r.top, r.right, r.bottom, keepRight = h.dx < 0, keepBottom = h.dy < 0)
    }

    /**
     * [v] (an edge the finger drags, on the side [dir] of the edge [fixed] that stays): whole
     * Length steps from [fixed] while increments are on. Exactly [v] while they are off (I8), and
     * when the finger is past [fixed] (nothing to step: the frame is at its least size there).
     */
    private fun steppedFrom(v: Float, fixed: Float, dir: Int): Float {
        val inc = controller.increments
        val d = (v - fixed) * dir
        if (!inc.enabled || d <= 0f) return v
        return fixed + dir * inc.lengthAbs(d)
    }

    /** The rectangle from the (snapped) start corner to [q]: the corner snaps, else its size steps. */
    private fun drawnRect(q: Vec2): RectF {
        val inc = controller.increments
        val snapped = if (controller.snapping.enabled || controller.grid.snap) snap.snapPoint(q) else q
        fun axis(v: Float, raw: Float, start: Float): Float =
            if (v != raw || !inc.enabled) v else start + Math.signum(raw - start) * inc.lengthAbs(abs(raw - start))
        val x = axis(snapped.x, q.x, drawStart.x)
        val y = axis(snapped.y, q.y, drawStart.y)
        val r = RectF(min(drawStart.x, x), min(drawStart.y, y), max(drawStart.x, x), max(drawStart.y, y))
        inc.readout = if (inc.enabled) "${Units.formatNumber(r.width().toDouble(), 0)} × ${Units.formatNumber(r.height().toDouble(), 0)} px" else null
        return r
    }

    private fun signed(v: Float): String {
        val n = v.roundToInt()
        return if (n > 0) "+$n" else n.toString()
    }

    override fun onUp(p: ToolPoint) {
        val m = mode
        mode = Mode.NONE
        endSnap()
        controller.increments.readout = null
        if (m == Mode.NONE) return
        if (!moved) {
            // The out-port under the finger (clearDrag forgets it).
            val port = portLayer
            val handlePort = tapPortLayer
            clearDrag()
            // A tap on a handle that lies on an out-port is the port's (the handle keeps drags).
            if (m == Mode.RESIZE && handlePort != null) onTap(Mode.PORT, Vec2(p.x, p.y), handlePort)
            else onTap(m, Vec2(p.x, p.y), port)
            controller.invalidateOverlay()
            return
        }
        when (m) {
            Mode.DRAW -> finishDraw()
            Mode.MOVE -> commitDrag(MOVE_LABEL)
            Mode.RESIZE -> commitDrag(RESIZE_LABEL)
            else -> clearDrag()
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        cancelGesture()
        controller.invalidateOverlay()
    }

    private fun cancelGesture() {
        mode = Mode.NONE
        endSnap()
        controller.increments.readout = null
        if (dragItem != null && positionEdit == null) preview.clear()
        clearDrag()
    }

    private fun clearDrag() {
        dragLayer = null
        dragStart = null
        dragItem = null
        dragHandle = null
        drawRect = null
        portLayer = null
        tapPortLayer = null
        if (!story.isOpen) pendingFlow = null
    }

    /** A tap (the finger didn't travel) that went down in mode [m]; [port] is the out-port it went down on. */
    private fun onTap(m: Mode, at: Vec2, port: Layer?) {
        if (m == Mode.PORT) {
            if (port != null && doc.indexOf(port) >= 0) toggleLink(port)
            return
        }
        val from = linkFrom
        if (from != null) {
            val target = textLayerAt(at)
            if (target == null) {
                cancelLink()
                return
            }
            join(from, target)
            return
        }
        val hit = frameAt(at)
        when {
            hit == null -> select(null)
            hit.layer === selected -> openStoryEditor(hit.layer)
            else -> select(hit.layer)
        }
    }

    private fun finishDraw() {
        val r = drawRect
        drawRect = null
        if (r == null) return
        val t = controller.viewTransform
        val min = minSide(t)
        if (r.width() < min || r.height() < min) {
            controller.toast(TOO_SMALL)
            return
        }
        val from = linkFrom
        if (from != null) linkNewFrame(from, r) else startNewFrame(r)
    }

    /** Writes the dragged frame's new place or size: the chain re-flows, one step [label]. */
    private fun commitDrag(label: String) {
        val layer = dragLayer
        val start = dragStart
        val item = dragItem
        clearDrag()
        preview.clear()
        if (layer == null || start == null || item == null || item == start || doc.indexOf(layer) < 0) return
        writeFrame(layer, start, item, label)
    }

    /** Writes frame [layer] (it held [start]) as [item], its story re-flowed: one step [label]. False when nothing was written. */
    private fun writeFrame(layer: Layer, start: TextItem, item: TextItem, label: String): Boolean {
        val frames = threads.framesOf(start.thread.storyId)
        val s = threads.storyOf(frames) ?: return false
        if (frames.none { it.layer === layer }) return false
        val chain = frames.map { f -> if (f.layer === layer) FlowFrame(layer, item) else FlowFrame(f.layer, f.item) }
        val written = threads.writeStory(label, s.id, s.text, s.spec, chain) != null
        changed()
        return written
    }

    /** The drag preview: the dragged frame, and the frames after it when its slice changes. */
    private fun refreshDragPreview() {
        val layer = dragLayer ?: positionEditLayer ?: return
        val item = dragItem ?: positionEdit?.second ?: return
        val frames = threads.framesOf(item.thread.storyId)
        val s = threads.storyOf(frames) ?: return
        val k = frames.indexOfFirst { it.layer === layer }
        if (k < 0) return
        val start = frames[k].item
        val sameLines = start.spec.box == item.spec.box && !item.wrapActive
        val shown = LinkedHashMap<Layer, TextItem>()
        if (sameLines) {
            // Only moved: the same lines at another place.
            shown[layer] = item
            pendingFlow = null
        } else {
            val chain = frames.map { f -> if (f.layer === layer) FlowFrame(layer, item) else FlowFrame(f.layer, f.item) }
            val items = TextThreadFlow.flow(s.text, s.spec, chain, s.id, s.rev, threads.measures)
            for (i in frames.indices) if (i == k || !StoryWriter.sameRendering(frames[i].item, items[i])) shown[frames[i].layer] = items[i]
            pendingFlow = items
        }
        preview.show(shown)
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ hit tests

    /** The topmost visible frame whose box is at [p] (document px), within a small tolerance. */
    private fun frameAt(p: Vec2): TextThreads.Frame? {
        val t = controller.viewTransform
        val tol = t.screenToDocLength(t.dp(HIT_TOLERANCE_DP))
        val frames = threads.allFrames()
        for (i in frames.indices.reversed()) {
            val f = frames[i]
            if (!f.layer.visible) continue
            val r = FrameGeometry.outerRect(f.item)
            if (p.x >= r.left - tol && p.x <= r.right + tol && p.y >= r.top - tol && p.y <= r.bottom + tol) return f
        }
        return null
    }

    /** Decoded and laid-out text layers for hit tests, by stored data instance. */
    private val hitTexts = HashMap<Long, Triple<String, TextItem, PreparedText>>()

    /** The topmost visible text layer (frame or plain text) whose text is at [p], or null. */
    private fun textLayerAt(p: Vec2): Layer? {
        frameAt(p)?.let { return it.layer }
        val t = controller.viewTransform
        val tol = t.screenToDocLength(t.dp(HIT_TOLERANCE_DP))
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            val data = l.textData ?: continue
            if (!l.visible || threads.isFrame(l)) continue
            val cached = hitTexts[l.id]?.takeIf { it.first === data }
            val (item, prep) = if (cached != null) cached.second to cached.third else {
                val item = TextCodec.decode(data) ?: continue
                val prep = TextRenderer.prepare(item)
                if (hitTexts.size >= HIT_CACHE_SIZE) hitTexts.clear()
                hitTexts[l.id] = Triple(data, item, prep)
                item to prep
            }
            if (item.text.isNotBlank() && prep.contains(item, p, tol)) return l
        }
        return null
    }

    // ------------------------------------------------------------------ linking, unlinking, deleting

    /** Loads [layer]'s out-port (link mode), or unloads it when it is loaded already. */
    fun toggleLink(layer: Layer) {
        if (linkFrom === layer) {
            cancelLink()
            return
        }
        startLink(layer)
    }

    /** Loads [layer]'s out-port: the next frame drawn or text tapped joins the story after it. */
    fun startLink(layer: Layer? = selected) {
        val l = layer ?: return
        if (!threads.isFrame(l)) return
        if (story.isOpen) return
        select(l)
        linkFromState = l
        startPulse()
        changed()
    }

    fun cancelLink() {
        linkFromState = null
        stopPulse()
        changed()
    }

    /** Creates a frame whose outer box is [r] (document px) linked after [from]: the story flows on into it ("Link frame"). */
    fun linkNewFrame(from: Layer, r: RectF): Layer? {
        cancelLink()
        val item = threads.frameOf(from) ?: return null
        if (!controller.canAddLayer) {
            controller.toast(TextThreads.LAYER_LIMIT.format(controller.maxLayers))
            return null
        }
        val frames = threads.framesOf(item.thread.storyId)
        val s = threads.storyOf(frames) ?: return null
        val k = frames.indexOfFirst { it.layer === from }
        if (k < 0) return null
        val template = FrameGeometry.placed(TextItem(spec = FrameGeometry.withFrameBox(s.spec, TextBoxSpec())), r.left, r.top, r.right, r.bottom)
        val chain = ArrayList<FlowFrame>(frames.size + 1)
        frames.forEachIndexed { i, f ->
            chain += FlowFrame(f.layer, f.item)
            if (i == k) chain += FlowFrame(null, template)
        }
        // The new layer goes right above the frame it continues.
        if (controller.activeLayer !== from) controller.selectLayer(from)
        val layers = threads.writeStory(LINK_LABEL, s.id, s.text, s.spec, chain) ?: return null
        val created = layers[k + 1]
        select(created)
        return created
    }

    /**
     * Joins text layer [target] after frame [from] (§3.6a): a standalone horizontal text box, or
     * the single frame of another story. Its text (or that story) is appended to [from]'s story
     * after a paragraph break, and it becomes the next frame, in the story's look, keeping its
     * box size and place ("Link frame"). Refused with a message for vertical, path or rotated
     * text, a frame of a longer story, or one of [from]'s own frames.
     */
    fun join(from: Layer, target: Layer): Boolean {
        cancelLink()
        val fromItem = threads.frameOf(from) ?: return false
        val tItem = TextCodec.decode(target.textData) ?: return false
        val tFrame = threads.frameOf(target)
        if (tFrame != null && tFrame.thread.storyId == fromItem.thread.storyId) {
            controller.toast(SAME_STORY)
            return false
        }
        if (tItem.spec.vertical || tItem.path.isActive || abs(tItem.rotationDeg) > 0.01f) {
            controller.toast(HORIZONTAL_ONLY)
            return false
        }
        if (tFrame != null && threads.framesOf(tFrame.thread.storyId).size > 1) {
            controller.toast(SINGLE_FRAME_ONLY)
            return false
        }
        if (!controller.checkEditable(target)) return false
        val frames = threads.framesOf(fromItem.thread.storyId)
        val s = threads.storyOf(frames) ?: return false
        val k = frames.indexOfFirst { it.layer === from }
        if (k < 0) return false
        val added = tFrame?.thread?.story ?: tItem.text
        val sep = if (s.text.isEmpty() || s.text.endsWith("\n") || added.isEmpty()) "" else "\n"
        val joined = s.text + sep + added
        if (joined.length > com.brushwork.paint.tools.text.TextThreadSpec.MAX_STORY) {
            controller.toast("A linked story holds at most ${com.brushwork.paint.tools.text.TextThreadSpec.MAX_STORY} characters")
            return false
        }
        // Its box: the frame's own, or the text's fixed width / height (else the size it has now).
        val box = if (tFrame != null) tFrame.spec.box else {
            val block = TextRenderer.prepare(tItem).block
            val w = if (tItem.spec.box.width > 0f) tItem.spec.box.width else block?.contentWidth ?: tItem.spec.sizePx
            val h = if (tItem.spec.box.minHeight > 0f) tItem.spec.box.minHeight else block?.contentHeight ?: tItem.spec.sizePx
            TextBoxSpec(width = max(1f, w.roundToInt().toFloat()), minHeight = max(1f, h))
        }
        val template = TextItem(spec = FrameGeometry.withFrameBox(s.spec, box), cx = tItem.cx, cy = tItem.cy, wrap = tItem.wrap)
        val chain = ArrayList<FlowFrame>(frames.size + 1)
        frames.forEachIndexed { i, f ->
            chain += FlowFrame(f.layer, f.item)
            if (i == k) chain += FlowFrame(target, template)
        }
        val layers = threads.writeStory(LINK_LABEL, s.id, joined, s.spec, chain) ?: return false
        select(layers[k + 1])
        return true
    }

    /**
     * Unlink here (§3.6a): the frames after [layer] (default: the selected frame) become empty
     * standalone frames, each a story of its own, and their text comes back as overset of
     * [layer] (one step "Unlink frame"). False (with a message) for the last frame of a story.
     */
    fun unlinkAfter(layer: Layer? = selected): Boolean {
        val l = layer ?: return false
        val item = threads.frameOf(l) ?: return false
        val frames = threads.framesOf(item.thread.storyId)
        val s = threads.storyOf(frames) ?: return false
        val k = frames.indexOfFirst { it.layer === l }
        if (k < 0) return false
        if (k == frames.lastIndex) {
            controller.toast(NOTHING_TO_UNLINK)
            return false
        }
        val look = FrameGeometry.storyLook(s.spec)
        val ids = HashSet<Long>()
        val extra = frames.drop(k + 1).map { f ->
            var nid = threads.newStoryId()
            while (!ids.add(nid)) nid = threads.newStoryId()
            FrameWrite(f.layer, f.item, TextThreadFlow.frameItem("", look, f.item, nid, 0, 0, 0, false, 0L))
        }
        val chain = frames.take(k + 1).map { FlowFrame(it.layer, it.item) }
        threads.writeStory(UNLINK_LABEL, s.id, s.text, s.spec, chain, extra) ?: return false
        changed()
        return true
    }

    /** Deletes [layer]'s frame (default: the selected one): its layer goes, the story re-flows in the same step. */
    fun deleteFrame(layer: Layer? = selected) {
        val l = layer ?: return
        if (!threads.isFrame(l)) return
        controller.deleteLayer(l)
        if (doc.indexOf(l) < 0) select(null)
        changed()
    }

    /** Characters of [layer]'s story (default: the selected frame's) that no frame shows. */
    fun oversetCount(layer: Layer? = selected): Int {
        val l = layer ?: return 0
        val item = threads.frameOf(l) ?: return 0
        val last = threads.framesOf(item.thread.storyId).lastOrNull() ?: return 0
        return TextThreadFlow.oversetCount(last.item)
    }

    /** True when [layer]'s story continues in a frame after it. */
    fun hasNext(layer: Layer? = selected): Boolean {
        val l = layer ?: return false
        val item = threads.frameOf(l) ?: return false
        val frames = threads.framesOf(item.thread.storyId)
        val k = frames.indexOfFirst { it.layer === l }
        return k >= 0 && k < frames.lastIndex
    }

    // ------------------------------------------------------------------ wrap around a picture (per frame)

    /**
     * Layers frame [layer] can wrap around, top first: every layer but text layers (frames
     * included) and adjustment layers. A picture may be above or below the frame.
     */
    fun wrapSources(layer: Layer? = selected): List<Layer> =
        doc.layers.asReversed().filter { it !== layer && !it.isTextLayer && !it.isAdjustmentLayer }

    /** The picture frame [layer] wraps around (null when it doesn't, or that layer was deleted). */
    fun wrapSourceOf(layer: Layer? = selected): Layer? {
        val item = layer?.let { threads.frameOf(it) } ?: return null
        if (!item.wrap.isOn) return null
        return doc.layerById(item.wrap.sourceLayerId)?.takeIf { !it.isTextLayer && !it.isAdjustmentLayer }
    }

    /** True when frame [layer] wraps around a picture (also one that was deleted: it keeps the outline). */
    fun wraps(layer: Layer? = selected): Boolean = layer?.let { threads.frameOf(it) }?.wrapActive == true

    /**
     * Frame [layer]'s lines wrap around [source]'s picture (null = no wrap), each frame on its own
     * (§3.6a "Wrap around a picture works per frame"): the frame's lines break around the
     * picture's outline and the chain re-flows, one step "Wrap frame". The first time, the
     * distance becomes 0.3 em, as for a text. Later edits of the picture re-flow the story
     * (TextWrapReflow → [TextThreads.reflowStory]). False (nothing written) when nothing changes
     * or the frame can't be changed.
     */
    fun setFrameWrap(layer: Layer, source: Layer?): Boolean {
        val item = threads.frameOf(layer) ?: return false
        if (story.isOpen || !controller.checkEditable(layer)) return false
        val next = if (source == null) {
            if (!item.wrap.isOn) return false
            item.copy(wrap = item.wrap.copy(sourceLayerId = 0L, polygons = emptyList()))
        } else {
            if (doc.indexOf(source) < 0 || source === layer || source.isTextLayer || source.isAdjustmentLayer) return false
            val polys = controller.textWrap.contours.polygons(source, item.wrap.contour) ?: run {
                controller.toast("Not enough memory to trace \"${source.name}\"")
                return false
            }
            val gap = if (item.wrap == TextWrapSpec()) (WRAP_GAP_EM * item.spec.sizePx).coerceIn(0f, TextWrapSpec.MAX_GAP_PX) else item.wrap.gapPx
            item.copy(wrap = item.wrap.copy(sourceLayerId = source.id, polygons = polys, gapPx = gap))
        }
        if (next == item) return false
        return writeFrame(layer, item, next, WRAP_LABEL)
    }

    // ------------------------------------------------------------------ the X / Y pill

    /** A pill edit of the selected frame's centre: (its item when the edit began, its pending item). */
    private var positionEdit: Pair<TextItem, TextItem>? = null
    private var positionEditLayer: Layer? = null

    private val framePosition = object : ObjectPosition {
        override val position: Vec2?
            get() {
                // Compose: follow edits, undo / redo and this tool's own changes.
                controller.editCount; controller.layersVersion; revision
                val l = selected ?: return null
                val it = positionEdit?.second?.takeIf { positionEditLayer === l } ?: threads.frameOf(l) ?: return null
                return Vec2(it.cx, it.cy)
            }

        override val label: String get() = "Center"

        override fun setPosition(x: Float?, y: Float?) {
            val open = positionEdit != null
            if (!open) beginPositionEdit()
            val edit = positionEdit ?: return
            val cur = edit.second
            val next = cur.copy(cx = x?.takeIf { it.isFinite() } ?: cur.cx, cy = y?.takeIf { it.isFinite() } ?: cur.cy)
            positionEdit = edit.first to next
            requestPreview(0L)
            changed()
            if (!open) endPositionEdit()
        }

        override fun beginPositionEdit() = this@TextFrameTool.beginPositionEdit()

        override fun endPositionEdit() = this@TextFrameTool.endPositionEdit()
    }

    /** The pill's target: the selected frame's centre (its position is null while nothing is selected). */
    override val objectPosition: ObjectPosition get() = framePosition

    private fun beginPositionEdit() {
        if (positionEdit != null) return
        val l = selected ?: return
        if (!controller.checkEditable(l)) return
        val item = threads.frameOf(l) ?: return
        positionEdit = item to item
        positionEditLayer = l
    }

    private fun endPositionEdit() {
        val edit = positionEdit ?: return
        val l = positionEditLayer
        positionEdit = null
        positionEditLayer = null
        preview.clear()
        if (l != null && doc.indexOf(l) >= 0 && edit.first != edit.second) writeFrame(l, edit.first, edit.second, MOVE_LABEL)
        changed()
    }

    // ------------------------------------------------------------------ overlay

    private var pulseOn = false
    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!pulseOn) return
            // Link mode ended without this tool hearing of it (an undo took the loaded frame away).
            if (linkFrom == null) {
                pulseOn = false
                controller.invalidateOverlay()
                return
            }
            controller.invalidateOverlay()
            handler.postDelayed(this, PULSE_FRAME_MS)
        }
    }

    private fun startPulse() {
        if (pulseOn) return
        pulseOn = true
        handler.postDelayed(pulseRunnable, PULSE_FRAME_MS)
    }

    private fun stopPulse() {
        if (!pulseOn) return
        pulseOn = false
        handler.removeCallbacks(pulseRunnable)
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        // A new frame being typed: drawn here (it has no layer yet), clipped to the canvas.
        val newItem = pendingNew?.takeIf { story.isOpen && story.editingNew }
        val newPrep = pendingNewPrepared
        if (newItem != null && newPrep != null) {
            val save = canvas.save()
            canvas.concat(t.matrix)
            canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
            TextRenderer.drawItem(canvas, newItem, newPrep, null, doc.colorMode)
            canvas.restoreToCount(save)
        }
        val list = ArrayList<OverlayFrame>()
        val pending = pendingFlow?.takeIf { story.isOpen || dragLayer != null || positionEdit != null }
        val byLayer = HashMap<Layer, TextItem>()
        if (pending != null) {
            val frames = threads.framesOf(pending.firstOrNull()?.thread?.storyId ?: 0L)
            if (frames.size == pending.size) for (i in frames.indices) byLayer[frames[i].layer] = pending[i]
        }
        val all = threads.allFrames()
        val lastOf = HashMap<Long, Int>()
        for (f in all) {
            val th = (byLayer[f.layer] ?: f.item).thread
            lastOf[th.storyId] = max(lastOf[th.storyId] ?: -1, th.index)
        }
        for (f in all) {
            if (!f.layer.visible) continue
            var item = byLayer[f.layer] ?: f.item
            if (f.layer === dragLayer) dragItem?.let { item = it }
            if (f.layer === positionEditLayer) positionEdit?.second?.let { item = it }
            if (story.isOpen && f.layer === storyTarget) story.item?.let { cur -> item = item.copy(cx = cur.cx, cy = cur.cy, spec = FrameGeometry.withFrameBox(item.spec, cur.spec.box)) }
            val th = item.thread
            list += OverlayFrame(f.layer, FrameGeometry.outerRect(item), th.storyId, th.index, th.overset, th.index < (lastOf[th.storyId] ?: th.index), f.layer.locked)
        }
        if (newItem != null) list += OverlayFrame(null, FrameGeometry.outerRect(newItem), PREVIEW_STORY_ID, 0, newItem.thread.overset, false)
        val pulse = if (linkFrom != null) ((SystemClock.uptimeMillis() % PULSE_PERIOD_MS).toFloat() / PULSE_PERIOD_MS).let { if (it < 0.5f) it * 2f else 2f - it * 2f } else 0f
        overlay.draw(canvas, t, list, selected, linkFrom, showThreads, drawRect, pulse)
        val moving = (dragItem ?: positionEdit?.second)?.let { FrameGeometry.outerRect(it) }?.let { DocBox(it.left, it.top, it.right, it.bottom) }
        snap.draw(canvas, t, moving)
    }

    companion object {
        /** Step names (§3.6a). */
        const val ADD_LABEL = "Add text frame"
        const val LINK_LABEL = "Link frame"
        const val EDIT_LABEL = "Edit story"
        const val MOVE_LABEL = "Move frame"
        const val RESIZE_LABEL = "Resize frame"
        const val UNLINK_LABEL = "Unlink frame"
        const val WRAP_LABEL = "Wrap frame"

        /** Distance between a frame's text and its picture when wrap is first switched on (em). */
        private const val WRAP_GAP_EM = 0.3f

        /** Messages. */
        const val HORIZONTAL_ONLY = "Only horizontal text boxes can be linked"
        const val SINGLE_FRAME_ONLY = "Only a text box or a story's single frame can be linked: unlink that story first"
        const val SAME_STORY = "That frame is already part of this story"
        const val NOTHING_TO_UNLINK = "This is the last frame of its story: nothing to unlink"
        const val TOO_SMALL = "Drag a bigger rectangle to draw a text frame"

        /** A story id for frames that are only previewed (never stored). */
        internal const val PREVIEW_STORY_ID = 1L

        /** Smallest frame on screen (IbisDims.FrameMinSize). */
        const val MIN_FRAME_DP = 32f

        private const val STORY_PREVIEW_MS = 100L
        private const val DRAG_PREVIEW_MS = 48L
        private const val HANDLE_HIT_DP = 24f
        private const val PORT_HIT_DP = 22f
        private const val TOUCH_SLOP_DP = 8f
        private const val HIT_TOLERANCE_DP = 8f
        private const val HIT_CACHE_SIZE = 32
        private const val PULSE_FRAME_MS = 40L
        private const val PULSE_PERIOD_MS = 1000L

        /** Most characters "Fill the box" puts into a story. */
        private const val MAX_FILL_CHARS = 20_000
    }
}
