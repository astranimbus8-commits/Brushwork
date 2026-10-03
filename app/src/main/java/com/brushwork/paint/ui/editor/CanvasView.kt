package com.brushwork.paint.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.ui.theme.IbisColors
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/** Zoom/rotation readout while the user pinches (for the on-screen chip). */
data class ViewGestureInfo(val zoom: Float, val rotation: Float)

/** Keeps each controller's [Viewport] so a recreated canvas view shows the same framing. */
internal object ViewportStore {
    private val map = WeakHashMap<EditorController, Viewport>()
    fun of(controller: EditorController): Viewport = synchronized(map) { map.getOrPut(controller) { Viewport() } }
}

/**
 * The drawing surface: renders the document tiles under the view transform plus overlays, and
 * turns touch/stylus input into tool input (document coordinates) or pan/zoom/rotate gestures,
 * two-finger-tap undo, three-finger-tap redo and long press. A two-finger gesture is offered to
 * the current tool first (controller.twoFingerStart: e.g. pinching the picture being placed);
 * only when the tool declines does it move the view.
 */
@SuppressLint("ViewConstructor")
class CanvasView(context: Context, private val controller: EditorController) : View(context) {

    /** Receives "Undo: Brush"-style feedback after a multi-finger tap. */
    var onTapAction: ((String) -> Unit)? = null

    /** Receives zoom/rotation while pinching, then null when the gesture ends. */
    var onViewGesture: ((ViewGestureInfo?) -> Unit)? = null

    /**
     * Called when a touch gesture starts on the canvas (the first finger or pen goes down),
     * before the tool sees it: e.g. the editor minimizes an open menu so the canvas is in view.
     */
    var onTouchDown: (() -> Unit)? = null

    /**
     * While set, the canvas is "outside" a floating window (the layers window): a quick tap with
     * one finger or the pen only calls this (e.g. to close the window) and never reaches the
     * tool, so it paints no dot and starts no text. Everything else still works: a stroke is
     * given to the tool from its first point once it moves, a finger held still picks a color,
     * and two fingers zoom the view or pinch the tool's object.
     */
    var onOutsideTap: (() -> Unit)? = null

    private val viewport = ViewportStore.of(controller)
    // The activity handles density changes itself (manifest configChanges): see onConfigurationChanged.
    private var density = resources.displayMetrics.density
    private var classifier = createClassifier()

    // Screen area not covered by chrome (insets from each edge, px); the fit centers in it.
    private var insetLeft = 0f
    private var insetTop = 0f
    private var insetRight = 0f
    private var insetBottom = 0f

    // ------------------------------------------------------------------ drawing resources

    // v1.6: the ibisPaint surround; the transparency squares of the layer window pick the checker.
    private val backdropColor = IbisColors.Surround.toArgb()

    /**
     * How transparency shows (the layer window's squares, `AppSettings.transparencyDisplay`),
     * cached: read when the view is attached and whenever the setting changes (a preferences
     * listener), never per frame (§3.1 review: a SharedPreferences read on every onDraw).
     */
    private var checkerMode: TransparencyDisplay = controller.settings.transparencyDisplay

    /** Held here: SharedPreferences keeps its listeners only weakly. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A null key: the preferences were cleared (API 30+).
        if (key == null || key == TRANSPARENCY_KEY) refreshCheckerMode()
    }
    private val checkerPaint = Paint().apply { shader = createCheckerShader((CHECKER_CELL_DP * density).toInt().coerceAtLeast(2), checkerMode) }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = 0xFF000000.toInt()
    }
    private val docPath = Path()
    private val pts = FloatArray(8)
    private val matrixValues = FloatArray(9)
    private val tmpMatrix = Matrix()
    private val visibleF = RectF()
    private val visible = Rect()

    private val invalidator: () -> Unit = { postInvalidateOnAnimation() }
    private val antsTick = Runnable { invalidate() }

    init {
        keepScreenOn = true
        isHapticFeedbackEnabled = true
        contentDescription = "Canvas"
    }

    // ------------------------------------------------------------------ public commands

    /** Reports the chrome that overlaps the canvas; the initial fit centers in the free area. */
    fun setFitInsets(left: Float, top: Float, right: Float, bottom: Float) {
        val delta = abs(left - insetLeft) + abs(top - insetTop) + abs(right - insetRight) + abs(bottom - insetBottom)
        if (delta == 0f) return
        insetLeft = left; insetTop = top; insetRight = right; insetBottom = bottom
        // The color-pick preview square must not end up under the top bar or the hotbar.
        (controller.tools[ToolId.EYEDROPPER] as? EyedropperTool)?.setChromeInsets(left, top, right, bottom)
        // Refit only for real layout changes (first measurement, filter panel shown/hidden), never
        // mid-gesture, and only while the user hasn't adjusted the view since the last fit. Small
        // changes (tool option strips of different heights) must not make the canvas jump.
        if (delta >= REFIT_INSET_DELTA_DP * density && mode == Mode.NONE && viewport.hasSize &&
            !viewport.userAdjusted && viewport.fittedDocVersion == controller.docVersion
        ) {
            fitNow()
        }
    }

    /**
     * The part of this view the chrome leaves free (view px, as [setFitInsets] reported): where
     * the fit centres the canvas, and where the Path tool's quick starts go (v1.6 §3.2a).
     */
    fun freeArea(): RectF = RectF(insetLeft, insetTop, width - insetRight, height - insetBottom)

    fun setMirrored(mirrored: Boolean) {
        if (viewport.mirrored == mirrored) return
        interruptStroke()
        viewport.mirrored = mirrored
        if (mode == Mode.TRANSFORM) restartTransform(null, -1)
        applyTransform()
    }

    fun fitToScreen() {
        interruptStroke()
        fitNow()
        reanchorGesture()
    }

    fun actualPixels() {
        if (!viewport.hasSize) return
        interruptStroke()
        viewport.actualPixels()
        viewport.userAdjusted = true
        reanchorGesture()
        applyTransform()
        flashGestureInfo()
    }

    fun resetRotation() {
        if (!viewport.hasSize) return
        interruptStroke()
        viewport.resetRotation()
        viewport.userAdjusted = true
        reanchorGesture()
        applyTransform()
        flashGestureInfo()
    }

    /**
     * The view is about to move under a finger that is still drawing (menu action tapped with
     * another finger): drop the stroke instead of letting it jump across the canvas.
     */
    private fun interruptStroke() {
        if (mode == Mode.PENDING_TAP) {
            // The tool never saw the held touch: just forget it.
            dropPendingTap()
            mode = Mode.IGNORE
            return
        }
        if (mode == Mode.TOOL || mode == Mode.TOOL_REST) {
            // Same for two fingers driving the tool: keep what they did, ignore the rest.
            endToolGesture(cancelled = false)
            mode = Mode.IGNORE
            return
        }
        if (mode != Mode.DRAW) return
        cancelPendingLongPress()
        controller.pointerCancel()
        mode = Mode.IGNORE
    }

    /** A pinch in progress continues from the new framing instead of snapping back to the old one. */
    private fun reanchorGesture() {
        if (mode == Mode.TRANSFORM) restartTransform(null, -1)
    }

    val zoom: Float get() = viewport.scale

    // ------------------------------------------------------------------ transform plumbing

    private fun applyTransform() {
        viewport.matrixValues(matrixValues)
        tmpMatrix.setValues(matrixValues)
        val t = controller.viewTransform
        t.density = density
        t.set(tmpMatrix)
        invalidate()
    }

    private fun fitNow() {
        if (!viewport.hasSize) return
        val doc = controller.doc
        viewport.fit(
            doc.width, doc.height,
            left = insetLeft, top = insetTop,
            right = viewport.viewWidth - insetRight, bottom = viewport.viewHeight - insetBottom,
        )
        viewport.fittedDocVersion = controller.docVersion
        applyTransform()
    }

    /** Fits on first layout and whenever the document geometry changed. */
    private fun ensureFitted() {
        if (viewport.hasSize && viewport.fittedDocVersion != controller.docVersion) fitNow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The document moves under the finger (rotation, split screen): a stroke would jump.
        if (oldw > 0 && oldh > 0) interruptStroke()
        viewport.resize(w, h)
        if (mode == Mode.TRANSFORM) restartTransform(null, -1)
        ensureFitted()
        applyTransform()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val d = resources.displayMetrics.density
        if (d == density) return
        // Display size changed while editing: rebuild everything measured in dp.
        abortGesture()
        mode = Mode.NONE
        ignoredMask = 0L
        density = d
        classifier = createClassifier()
        checkerPaint.shader = createCheckerShader((CHECKER_CELL_DP * density).toInt().coerceAtLeast(2), checkerMode)
        if (viewport.hasSize) applyTransform()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.onInvalidate = invalidator
        controller.settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refreshCheckerMode()
        if (viewport.hasSize) applyTransform()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(antsTick)
        removeCallbacks(longPressRunnable)
        dropPendingTap()
        if (mode == Mode.DRAW) controller.pointerCancel()
        if (mode == Mode.TRANSFORM) endTransform()
        endToolGesture(cancelled = true)
        mode = Mode.NONE
        if (controller.onInvalidate === invalidator) controller.onInvalidate = null
        controller.settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDetachedFromWindow()
    }

    /** Takes over a changed transparency display (shader rebuilt, frame redrawn). */
    private fun refreshCheckerMode() {
        val m = controller.settings.transparencyDisplay
        if (m == checkerMode) return
        checkerMode = m
        checkerPaint.shader = createCheckerShader((CHECKER_CELL_DP * density).toInt().coerceAtLeast(2), m)
        invalidate()
    }

    // ------------------------------------------------------------------ rendering

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backdropColor)
        if (!viewport.hasSize) return
        ensureFitted()
        val doc = controller.doc
        val w = doc.width.toFloat(); val h = doc.height.toFloat()

        // Document outline in screen space (it may be rotated): soft shadow, then checkerboard.
        viewport.docToScreen(0f, 0f, pts)
        val x0 = pts[0]; val y0 = pts[1]
        viewport.docToScreen(w, 0f, pts)
        val x1 = pts[0]; val y1 = pts[1]
        viewport.docToScreen(w, h, pts)
        val x2 = pts[0]; val y2 = pts[1]
        viewport.docToScreen(0f, h, pts)
        docPath.rewind()
        docPath.moveTo(x0, y0); docPath.lineTo(x1, y1); docPath.lineTo(x2, y2); docPath.lineTo(pts[0], pts[1]); docPath.close()
        for (i in SHADOW_WIDTHS_DP.indices) {
            shadowPaint.strokeWidth = SHADOW_WIDTHS_DP[i] * density
            shadowPaint.alpha = SHADOW_ALPHAS[i]
            canvas.drawPath(docPath, shadowPaint)
        }
        // v1.6: transparency as the layer window's squares say (a view preference only, I5).
        if (checkerMode != TransparencyDisplay.NONE) canvas.drawPath(docPath, checkerPaint)

        // Composite tiles under the view matrix (v1.6: a live adjustment session draws the frame
        // itself, from its proxy tiles, while it runs).
        val tiles = controller.tiles
        val t = controller.viewTransform
        visibleF.set(0f, 0f, width.toFloat(), height.toFloat())
        t.inverse.mapRect(visibleF)
        visibleF.roundOut(visible)
        val smooth = viewport.scale < SMOOTH_ZOOM_LIMIT
        if (!controller.liveAdjust.drawFrame(canvas, t.matrix, visible, smooth)) {
            tiles.update(controller.compositor, visible)
            val save = canvas.save()
            canvas.concat(t.matrix)
            tiles.draw(canvas, visible, smooth)
            canvas.restoreToCount(save)
        }
        if (controller.liveAdjust.wantsFrame) postInvalidateOnAnimation()

        val hasSelection = controller.selection != null
        controller.drawOverlays(canvas, if (hasSelection) antsPhase() else 0f)
        // Marching ants: one pending tick at a time (~15 fps), none without a selection.
        removeCallbacks(antsTick)
        if (hasSelection) postDelayed(antsTick, ANTS_FRAME_MS)
    }

    private fun antsPhase(): Float = (SystemClock.uptimeMillis() % 3_600_000L) / ANTS_FRAME_MS.toFloat()

    // ------------------------------------------------------------------ input

    /**
     * DRAW: one pointer feeds the tool. TRANSFORM: fingers pan/zoom/rotate the view. TOOL: two
     * fingers drive the current tool (controller.twoFingerStart accepted them, e.g. pinching the
     * picture being placed). TOOL_REST: one finger of such a gesture lifted and the other is still
     * down; the tool gesture may still be open, waiting to see whether it was a two-finger tap.
     * PENDING_TAP: one pointer is down while [onOutsideTap] is set; it is held back from the tool
     * until it turns out to be more than a tap (see [startPendingTap]).
     * IGNORE: the rest of the gesture does nothing.
     */
    private enum class Mode { NONE, DRAW, TRANSFORM, TOOL, TOOL_REST, PENDING_TAP, IGNORE }

    private var mode = Mode.NONE
    private var drawPointerId = -1
    private var drawIsStylus = false
    private var gestureHadStylus = false
    private var enteredTransform = false
    /** The view gesture has had two or more fingers (it stays a view gesture, see onPointerDown). */
    private var viewPinched = false
    private var startState: Viewport.State? = null
    private var startAdjusted = false
    /** Pointer ids treated as palm / leftover contacts for the rest of the gesture. */
    private var ignoredMask = 0L

    private val transformIds = IntArray(MAX_GESTURE_POINTERS)
    private var transformCount = 0
    private val gx = FloatArray(MAX_GESTURE_POINTERS)
    private val gy = FloatArray(MAX_GESTURE_POINTERS)

    private var lastPoint: ToolPoint? = null

    // The touch held back in Mode.PENDING_TAP: a copy of its down event and of the moves since.
    private var pendingDown: MotionEvent? = null
    private val pendingMoves = ArrayList<MotionEvent>()

    // Two-finger gesture handed to the tool (Mode.TOOL / TOOL_REST).
    private val toolIds = IntArray(2)
    /** Finger positions (DOCUMENT coordinates: ax, ay, bx, by) when the tool gesture started / now. */
    private val toolStart = FloatArray(4)
    private val toolNow = FloatArray(4)
    /** The tool still expects controller.twoFingerEnd() for the current gesture. */
    private var toolGestureOpen = false

    private fun bit(id: Int): Long = if (id in 0..63) 1L shl id else 0L
    private fun isIgnored(id: Int) = ignoredMask and bit(id) != 0L
    private fun ignore(id: Int) { ignoredMask = ignoredMask or bit(id) }

    private fun isStylusType(type: Int) = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!viewport.hasSize) return true
        val action = e.actionMasked
        if (controller.busyMessage != null) {
            // Input is blocked while a long operation runs; drop whatever was in progress.
            abortGesture()
            mode = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) Mode.NONE else Mode.IGNORE
            return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e, e.actionIndex)
            MotionEvent.ACTION_UP -> onLastUp(e)
            MotionEvent.ACTION_CANCEL -> { abortGesture(); mode = Mode.NONE; ignoredMask = 0L }
        }
        return true
    }

    private fun onFirstDown(e: MotionEvent) {
        // A missed UP/CANCEL (should not happen): close the previous gesture cleanly.
        if (mode == Mode.DRAW) controller.pointerCancel()
        if (mode == Mode.TRANSFORM) endTransform()
        dropPendingTap()
        endToolGesture(cancelled = true)
        cancelPendingLongPress()
        ignoredMask = 0L
        enteredTransform = false
        viewPinched = false
        gestureHadStylus = false
        startState = viewport.snapshot()
        startAdjusted = viewport.userAdjusted
        val id = e.getPointerId(0)
        val type = e.getToolType(0)
        // Every gesture starts clean, even if ignored (palm) pointers never reported their UP.
        classifier.cancel()
        classifier.down(id, e.getX(0), e.getY(0), e.eventTime)
        // Pen gestures may long-press but are never multi-finger taps (see onLastUp).
        if (isStylusType(type)) gestureHadStylus = true
        val mousePan = type == MotionEvent.TOOL_TYPE_MOUSE &&
            (e.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY)) != 0
        val fingerPans = type == MotionEvent.TOOL_TYPE_FINGER && controller.settings.stylusOnlyDrawing
        onTouchDown?.invoke()
        when {
            mousePan || fingerPans -> restartTransform(e, -1)
            onOutsideTap != null -> startPendingTap(e)
            else -> startDraw(e, 0)
        }
    }

    private fun onPointerDown(e: MotionEvent, idx: Int) {
        val id = e.getPointerId(idx)
        val stylus = isStylusType(e.getToolType(idx))
        if (mode == Mode.PENDING_TAP) {
            // A held pen keeps the gesture: another contact is a resting palm or a second pen.
            if (drawIsStylus) { ignore(id); return }
            // Not a tap: the held finger never reached the tool, so there is nothing to cancel.
            dropPendingTap()
            mode = Mode.NONE
            if (!stylus) {
                // Two fingers: they drive the tool if it wants them, else the view.
                classifier.down(id, e.getX(idx), e.getY(idx), e.eventTime)
                if (!startToolGesture(e)) restartTransform(e, -1)
                return
            }
            // The pen takes over below, the finger becoming a palm.
        }
        if (!stylus && ((mode == Mode.DRAW && drawIsStylus) || mode == Mode.IGNORE)) {
            // Palm (or a leftover finger) while the pen is in use: never draws, pans or taps.
            ignore(id)
            return
        }
        if (stylus) {
            if (mode == Mode.DRAW && drawIsStylus) { ignore(id); return } // a second pen: keep the first
            gestureHadStylus = true
            when (mode) {
                Mode.DRAW -> { cancelPendingLongPress(); controller.pointerCancel() }
                Mode.TRANSFORM -> endTransform()
                // The fingers were probably a resting hand: take back what they did to the tool.
                Mode.TOOL, Mode.TOOL_REST -> endToolGesture(cancelled = true)
                else -> {}
            }
            // The pen takes over: every other contact is now a resting palm, and recognition
            // (long press) restarts with the pen alone. Taps are excluded by gestureHadStylus.
            for (i in 0 until e.pointerCount) if (i != idx) ignore(e.getPointerId(i))
            classifier.cancel()
            classifier.down(id, e.getX(idx), e.getY(idx), e.eventTime)
            startDraw(e, idx)
            return
        }
        classifier.down(id, e.getX(idx), e.getY(idx), e.eventTime)
        when (mode) {
            Mode.DRAW -> {
                // A second finger drops the stroke; the two fingers then drive the tool if it
                // wants them (pinching the picture being placed), else the view.
                cancelPendingLongPress()
                controller.pointerCancel()
                if (!startToolGesture(e)) restartTransform(e, -1)
            }
            Mode.TRANSFORM -> {
                // A finger panning the view (stylus-only drawing) joined by a second one: the pair
                // is offered to the tool too. A gesture that already zoomed the view with two or
                // more fingers keeps doing that when a finger comes back (re-grip), even onto the
                // picture being placed.
                val offer = transformCount == 1 && !viewPinched && classifier.maxPointers == 2
                if (offer && startToolGesture(e)) endTransform() else restartTransform(e, -1)
            }
            Mode.TOOL -> {
                // A third finger takes the tool gesture back; the fingers now move the view (or
                // tap to redo).
                endToolGesture(cancelled = true)
                restartTransform(e, -1)
            }
            Mode.TOOL_REST -> {
                // A finger back down after one lifted (re-grip): keep the result so far and start
                // over with the new pair. Never a tap any more.
                endToolGesture(cancelled = false)
                classifier.invalidate()
                if (!startToolGesture(e)) restartTransform(e, -1)
            }
            else -> {}
        }
    }

    private fun onMove(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            if (!isIgnored(id)) classifier.move(id, e.getX(i), e.getY(i))
        }
        when (mode) {
            Mode.DRAW -> {
                val idx = e.findPointerIndex(drawPointerId)
                if (idx < 0) return
                for (hIdx in 0 until e.historySize) controller.pointerMove(toolPoint(e, idx, hIdx))
                controller.pointerMove(toolPoint(e, idx, -1))
            }
            Mode.TRANSFORM -> {
                var n = 0
                for (k in 0 until transformCount) {
                    val idx = e.findPointerIndex(transformIds[k])
                    if (idx < 0) { restartTransform(e, -1); return }
                    gx[n] = e.getX(idx); gy[n] = e.getY(idx); n++
                }
                viewport.updateGesture(gx, gy, n)
                applyTransform()
                if (n >= 2) onViewGesture?.invoke(ViewGestureInfo(viewport.scale, viewport.rotation))
            }
            Mode.TOOL -> feedToolGesture(e)
            // The remaining finger moved or waited too long: that was no tap, the pinch stands.
            Mode.TOOL_REST -> if (toolGestureOpen && !classifier.tapStillPossible(e.eventTime)) endToolGesture(cancelled = false)
            Mode.PENDING_TAP -> holdPendingMove(e)
            else -> {}
        }
    }

    private fun onPointerUp(e: MotionEvent, idx: Int) {
        val id = e.getPointerId(idx)
        if (isIgnored(id)) { ignoredMask = ignoredMask and bit(id).inv(); return }
        val tap = classifier.up(id, e.eventTime)
        when (mode) {
            Mode.DRAW -> if (id == drawPointerId) {
                cancelPendingLongPress()
                controller.pointerUp(toolPoint(e, idx, -1))
                mode = Mode.IGNORE
            }
            Mode.TRANSFORM -> restartTransform(e, idx)
            Mode.TOOL -> if (id == toolIds[0] || id == toolIds[1]) {
                feedToolGesture(e) // the lift-off event carries the final positions
                mode = Mode.TOOL_REST
                // Still a possible two-finger tap: decided when the other finger lifts.
                if (!classifier.tapStillPossible(e.eventTime)) endToolGesture(cancelled = false)
            }
            // The last active finger lifted while palm contacts stay down.
            Mode.TOOL_REST -> if (classifier.activePointers == 0) {
                finishToolGesture(tap)
                mode = Mode.IGNORE
            }
            // The held pen lifted quickly while a palm stays down: a tap.
            Mode.PENDING_TAP -> if (id == drawPointerId) {
                finishPendingTap()
                mode = Mode.IGNORE
            }
            else -> {}
        }
    }

    private fun onLastUp(e: MotionEvent) {
        val id = e.getPointerId(0)
        val tap = if (isIgnored(id)) TouchGestureClassifier.Tap.NONE else classifier.up(id, e.eventTime)
        when (mode) {
            Mode.DRAW -> {
                cancelPendingLongPress()
                // The gesture is over either way: never leave the tool mid-stroke.
                if (id == drawPointerId) controller.pointerUp(toolPoint(e, 0, -1)) else controller.pointerCancel()
            }
            Mode.TRANSFORM -> {
                endTransform()
                val outside = onOutsideTap
                if (outside != null && isQuickSingleTap(e)) {
                    // A finger that pans the view (stylus-only drawing) tapped: same as a tap
                    // anywhere else outside the floating window. Take back the few pixels it panned.
                    revertView()
                    outside()
                } else if (enteredTransform && !gestureHadStylus) {
                    handleTap(tap)
                }
            }
            Mode.TOOL, Mode.TOOL_REST -> finishToolGesture(tap)
            Mode.PENDING_TAP -> if (id == drawPointerId) finishPendingTap() else dropPendingTap()
            else -> {}
        }
        mode = Mode.NONE
        ignoredMask = 0L
    }

    /** The gesture that just ended was one pointer, lifted quickly without moving. */
    private fun isQuickSingleTap(e: MotionEvent): Boolean =
        classifier.isSinglePointerStill() && e.eventTime - e.downTime <= TouchGestureClassifier.LONG_PRESS_TIMEOUT_MS

    // ------------------------------------------------------------------ held taps (outside a window)

    /**
     * Holds the first pointer back from the tool while [onOutsideTap] is set: lifted quickly
     * without moving it is a tap ([finishPendingTap]); moving past the tap slop, staying down
     * for the long-press time or a second finger make it an ordinary gesture
     * ([releasePendingTap] replays it to the tool from its first point, or see onPointerDown).
     */
    private fun startPendingTap(e: MotionEvent) {
        mode = Mode.PENDING_TAP
        drawPointerId = e.getPointerId(0)
        drawIsStylus = isStylusType(e.getToolType(0))
        // On the live event: the stream is drawn with, should it become a stroke.
        requestUnbufferedDispatch(e)
        pendingDown = MotionEvent.obtain(e)
        removeCallbacks(pendingTapTimeout)
        postDelayed(pendingTapTimeout, TouchGestureClassifier.LONG_PRESS_TIMEOUT_MS)
    }

    private val pendingTapTimeout = Runnable {
        if (mode == Mode.PENDING_TAP && controller.busyMessage == null) releasePendingTap()
    }

    /** A move of the held pointer: kept for the replay; far enough from the start, it is no tap. */
    private fun holdPendingMove(e: MotionEvent) {
        val down = pendingDown ?: return
        val idx = e.findPointerIndex(drawPointerId)
        if (idx < 0) return
        pendingMoves += MotionEvent.obtain(e)
        val far = hypot(e.getX(idx) - down.getX(0), e.getY(idx) - down.getY(0)) >= TouchGestureClassifier.TAP_SLOP_DP * density
        if (far || pendingMoves.size >= MAX_PENDING_MOVES) releasePendingTap()
    }

    /** The held touch is not a tap: the tool gets it now, from its first point on. */
    private fun releasePendingTap() {
        val down = pendingDown ?: return
        removeCallbacks(pendingTapTimeout)
        pendingDown = null
        mode = Mode.DRAW
        controller.pointerDown(toolPoint(down, 0, -1))
        for (m in pendingMoves) {
            val idx = m.findPointerIndex(drawPointerId)
            if (idx >= 0) {
                for (h in 0 until m.historySize) controller.pointerMove(toolPoint(m, idx, h))
                controller.pointerMove(toolPoint(m, idx, -1))
            }
            m.recycle()
        }
        pendingMoves.clear()
        // The hold-still color pick keeps counting from the real start of the touch.
        val held = SystemClock.uptimeMillis() - down.eventTime
        down.recycle()
        cancelPendingLongPress()
        postDelayed(longPressRunnable, (TouchGestureClassifier.LONG_PRESS_TIMEOUT_MS - held).coerceAtLeast(0L))
    }

    /** The held pointer lifted quickly: a tap outside the window. The tool never sees it. */
    private fun finishPendingTap() {
        dropPendingTap()
        onOutsideTap?.invoke()
    }

    /** Forgets the held touch (the tool never saw it). */
    private fun dropPendingTap() {
        removeCallbacks(pendingTapTimeout)
        pendingDown?.recycle()
        pendingDown = null
        for (m in pendingMoves) m.recycle()
        pendingMoves.clear()
    }

    private fun startDraw(e: MotionEvent, idx: Int) {
        mode = Mode.DRAW
        drawPointerId = e.getPointerId(idx)
        drawIsStylus = isStylusType(e.getToolType(idx))
        requestUnbufferedDispatch(e)
        controller.pointerDown(toolPoint(e, idx, -1))
        cancelPendingLongPress()
        postDelayed(longPressRunnable, TouchGestureClassifier.LONG_PRESS_TIMEOUT_MS)
    }

    /**
     * (Re)starts the view gesture with every finger currently down except [excludeIndex]
     * (a pointer that is going up) and palm contacts. [e] null keeps the current pointer set.
     */
    private fun restartTransform(e: MotionEvent?, excludeIndex: Int) {
        if (e != null) {
            transformCount = 0
            for (i in 0 until e.pointerCount) {
                if (i == excludeIndex || transformCount == MAX_GESTURE_POINTERS) continue
                val id = e.getPointerId(i)
                if (isIgnored(id) || isStylusType(e.getToolType(i))) continue
                transformIds[transformCount] = id
                gx[transformCount] = e.getX(i); gy[transformCount] = e.getY(i)
                transformCount++
            }
            if (transformCount == 0) {
                if (mode == Mode.TRANSFORM) endTransform()
                mode = Mode.IGNORE
                return
            }
        }
        mode = Mode.TRANSFORM
        enteredTransform = true
        if (transformCount >= 2) viewPinched = true
        cancelPendingLongPress()
        viewport.beginGesture(gx, gy, transformCount)
    }

    private fun endTransform() {
        onViewGesture?.invoke(null)
    }

    // ------------------------------------------------------------------ two-finger tool gestures

    /**
     * Offers the gesture to the current tool when exactly two fingers are down (palms and pens
     * don't count): e.g. the transform tool scales/rotates the picture when the fingers are on it.
     * Returns true if the tool took it; the view then stays still (Mode.TOOL).
     */
    private fun startToolGesture(e: MotionEvent): Boolean {
        var n = 0
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            if (isIgnored(id) || isStylusType(e.getToolType(i))) continue
            if (n == 2) return false
            toolIds[n] = id
            toolStart[2 * n] = e.getX(i)
            toolStart[2 * n + 1] = e.getY(i)
            n++
        }
        if (n != 2) return false
        controller.viewTransform.inverse.mapPoints(toolStart)
        val a = Vec2(toolStart[0], toolStart[1])
        val b = Vec2(toolStart[2], toolStart[3])
        if (!controller.twoFingerStart(Vec2((a.x + b.x) / 2f, (a.y + b.y) / 2f), a, b)) return false
        mode = Mode.TOOL
        toolGestureOpen = true
        cancelPendingLongPress()
        return true
    }

    /** Reports the change since the tool gesture started (document units, see [TwoFingerChange]). */
    private fun feedToolGesture(e: MotionEvent) {
        if (!toolGestureOpen) return
        val ia = e.findPointerIndex(toolIds[0])
        val ib = e.findPointerIndex(toolIds[1])
        if (ia < 0 || ib < 0) return
        toolNow[0] = e.getX(ia); toolNow[1] = e.getY(ia)
        toolNow[2] = e.getX(ib); toolNow[3] = e.getY(ib)
        controller.viewTransform.inverse.mapPoints(toolNow)
        // Fingers closer than a dp at the start give no usable spread (and never happen for real).
        val change = TwoFingerChange.between(toolStart, toolNow, minSpread = density / viewport.scale)
        controller.twoFingerGesture(change.translation, change.scale, change.rotationDeg)
    }

    private fun endToolGesture(cancelled: Boolean) {
        if (!toolGestureOpen) return
        toolGestureOpen = false
        controller.twoFingerEnd(cancelled)
    }

    /**
     * The last finger of a tool gesture lifted. A quick two-finger tap takes back the few pixels
     * the tool moved (like the view is restored for a tap) and then undoes; anything else keeps
     * the result.
     */
    private fun finishToolGesture(tap: TouchGestureClassifier.Tap) {
        val isTap = tap != TouchGestureClassifier.Tap.NONE && !gestureHadStylus
        endToolGesture(cancelled = isTap)
        if (isTap) handleTap(tap)
    }

    private fun abortGesture() {
        cancelPendingLongPress()
        when (mode) {
            Mode.DRAW -> controller.pointerCancel()
            Mode.TRANSFORM -> endTransform()
            Mode.TOOL, Mode.TOOL_REST -> endToolGesture(cancelled = true)
            Mode.PENDING_TAP -> dropPendingTap()
            else -> {}
        }
        classifier.cancel()
    }

    private fun handleTap(tap: TouchGestureClassifier.Tap) {
        val settings = controller.settings
        when (tap) {
            TouchGestureClassifier.Tap.TWO_FINGER -> if (settings.twoFingerUndo) {
                revertView()
                // (Undone first: a null listener must not skip the undo.)
                val label = HistoryLabels.performUndo(controller)
                onTapAction?.invoke(label)
            }
            TouchGestureClassifier.Tap.THREE_FINGER -> if (settings.threeFingerRedo) {
                revertView()
                val label = HistoryLabels.performRedo(controller)
                onTapAction?.invoke(label)
            }
            TouchGestureClassifier.Tap.NONE -> {}
        }
    }

    /** Undoes the few pixels of pan/zoom a tap produced before it was recognized. */
    private fun revertView() {
        val s = startState ?: return
        viewport.restore(s)
        viewport.userAdjusted = startAdjusted
        applyTransform()
    }

    // ------------------------------------------------------------------ long press

    private val longPressRunnable = Runnable {
        if (mode != Mode.DRAW || controller.busyMessage != null) return@Runnable
        val now = SystemClock.uptimeMillis()
        if (!classifier.longPressDue(now)) return@Runnable
        classifier.markLongPressFired()
        val p = lastPoint ?: return@Runnable
        if (controller.pointerLongPress(p.copy(time = now))) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun cancelPendingLongPress() = removeCallbacks(longPressRunnable)

    // ------------------------------------------------------------------ mouse wheel

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && viewport.hasSize &&
            controller.busyMessage == null && mode == Mode.NONE
        ) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                viewport.zoomAround(e.x, e.y, viewport.scale * WHEEL_ZOOM_STEP.pow(v))
                viewport.userAdjusted = true
                applyTransform()
                flashGestureInfo()
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    private fun flashGestureInfo() {
        onViewGesture?.invoke(ViewGestureInfo(viewport.scale, viewport.rotation))
        onViewGesture?.invoke(null)
    }

    // ------------------------------------------------------------------ helpers

    /** Builds a tool point in DOCUMENT coordinates for pointer [idx] (history sample [h], -1 = current). */
    private fun toolPoint(e: MotionEvent, idx: Int, h: Int): ToolPoint {
        val cur = h < 0
        pts[0] = if (cur) e.getX(idx) else e.getHistoricalX(idx, h)
        pts[1] = if (cur) e.getY(idx) else e.getHistoricalY(idx, h)
        controller.viewTransform.inverse.mapPoints(pts, 0, pts, 0, 1)
        val stylus = isStylusType(e.getToolType(idx))
        val time = if (cur) e.eventTime else e.getHistoricalEventTime(h)
        var pressure = 1f
        var tilt = 0f
        var orientation = 0f
        if (stylus) {
            pressure = (if (cur) e.getPressure(idx) else e.getHistoricalPressure(idx, h)).coerceIn(0f, 1f)
            // The lift-off sample often reports zero pressure; keep the stroke's last pressure.
            val up = cur && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_POINTER_UP)
            if (up && pressure <= 0f) pressure = lastPoint?.pressure ?: pressure
            tilt = if (cur) e.getAxisValue(MotionEvent.AXIS_TILT, idx) else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h)
            // Like x/y, the pen direction is reported relative to the document (view rotation/mirror removed).
            val screenOrientation = if (cur) e.getAxisValue(MotionEvent.AXIS_ORIENTATION, idx) else e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, idx, h)
            orientation = viewport.screenAngleToDoc(screenOrientation)
        }
        return ToolPoint(pts[0], pts[1], pressure, time, stylus, tilt, orientation).also { lastPoint = it }
    }

    private fun createClassifier() = TouchGestureClassifier(
        tapSlopPx = TouchGestureClassifier.TAP_SLOP_DP * density,
        longPressSlopPx = TouchGestureClassifier.LONG_PRESS_SLOP_DP * density,
    )

    /** The pattern behind transparent pixels for [mode] (NONE is never drawn: the surround shows). */
    private fun createCheckerShader(cell: Int, mode: TransparencyDisplay): Shader {
        val (a, b) = when (mode) {
            TransparencyDisplay.WHITE -> IbisColors.CheckerLight to IbisColors.CheckerLight
            TransparencyDisplay.DARK_CHECKER -> IbisColors.CheckerDark to IbisColors.CheckerDark2
            TransparencyDisplay.LIGHT_CHECKER, TransparencyDisplay.NONE -> IbisColors.CheckerLight to IbisColors.CheckerLight2
        }
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(a.toArgb())
        val p = Paint().apply { color = b.toArgb() }
        c.drawRect(cell.toFloat(), 0f, cell * 2f, cell.toFloat(), p)
        c.drawRect(0f, cell.toFloat(), cell.toFloat(), cell * 2f, p)
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private companion object {
        const val MAX_GESTURE_POINTERS = 10
        /** Moves kept while a touch is held back (PENDING_TAP); more means it is no tap anyway. */
        const val MAX_PENDING_MOVES = 64
        const val ANTS_FRAME_MS = 66L
        /** Above this zoom the tiles are drawn with nearest-neighbor sampling (crisp pixels). */
        const val SMOOTH_ZOOM_LIMIT = 2.5f
        const val WHEEL_ZOOM_STEP = 1.15f
        const val REFIT_INSET_DELTA_DP = 24f
        const val CHECKER_CELL_DP = 8f
        /** The `AppSettings.transparencyDisplay` preferences key. */
        const val TRANSPARENCY_KEY = "transparencyDisplay"
        val SHADOW_WIDTHS_DP = floatArrayOf(22f, 14f, 8f, 3f)
        val SHADOW_ALPHAS = intArrayOf(8, 14, 24, 40)
    }
}
