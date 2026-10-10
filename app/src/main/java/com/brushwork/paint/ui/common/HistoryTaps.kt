package com.brushwork.paint.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize

/*
 * v1.7 (item 10, design §3.10; area I): two- and three-finger taps undo and redo over the UI too.
 * The editor recognizes them (ui/editor/HistoryTapHub) from every pointer of its windows: its root
 * feeds the editor's own window, a modal sheet (a window of its own) feeds its, and the canvas
 * says which pointers are its own. This file is the plumbing those windows share.
 */

/** Where a window's pointers go (the editor's history taps). Main thread only. */
interface HistoryTapSink {
    /**
     * One [pass] of [event] in window [slot] (the Initial and Final passes count). True: consume
     * every change of it (the gesture is a history tap, the controls must not act on it).
     */
    fun onPointerEvent(slot: Int, event: PointerEvent, pass: PointerEventPass): Boolean

    /** The touches of window [slot] were cancelled, or its feed went away. */
    fun onCancel(slot: Int)

    /** Pointer [id] of window [slot] went down on the canvas (Initial pass). */
    fun onCanvasDown(slot: Int, id: Long)
}

/** The editor's history taps (null outside the editor: nothing to feed). */
val LocalHistoryTaps = staticCompositionLocalOf<HistoryTapSink?> { null }

/** Window slots: 0 is the editor's own window; every other window feeding [historyTaps] takes one. */
object HistoryTapSlots {
    const val EDITOR = 0
    private var last = 0

    /** A slot for a new window (1..15, reused round-robin: few windows are open at once). */
    fun next(): Int {
        last = last % 15 + 1
        return last
    }
}

/**
 * Feeds every pointer reaching this node (in window [slot]) to [sink], at the Initial pass, before
 * the controls under it, and the Final pass; consumes what [sink] claims. Put it on the outermost
 * node of a window, so every pointer of the window passes through it. No-op without a [sink].
 */
fun Modifier.historyTaps(sink: HistoryTapSink?, slot: Int): Modifier =
    if (sink == null) this else this then HistoryTapFeedElement(sink, slot)

/** Marks the pointers that go down on this node (the canvas) as the canvas' for [sink]. */
fun Modifier.historyTapCanvas(sink: HistoryTapSink?, slot: Int = HistoryTapSlots.EDITOR): Modifier =
    if (sink == null) this else this then HistoryTapCanvasElement(sink, slot)

private data class HistoryTapFeedElement(val sink: HistoryTapSink, val slot: Int) : ModifierNodeElement<HistoryTapFeedNode>() {
    override fun create() = HistoryTapFeedNode(sink, slot)
    override fun update(node: HistoryTapFeedNode) {
        if (node.sink !== sink || node.slot != slot) node.sink.onCancel(node.slot)
        node.sink = sink
        node.slot = slot
    }
}

/** Synchronous (no coroutine) and allocation-free: one call per pass. */
private class HistoryTapFeedNode(var sink: HistoryTapSink, var slot: Int) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial && pass != PointerEventPass.Final) return
        if (!sink.onPointerEvent(slot, pointerEvent, pass)) return
        val changes = pointerEvent.changes
        for (i in changes.indices) changes[i].consume()
    }

    override fun onCancelPointerInput() = sink.onCancel(slot)

    override fun onDetach() = sink.onCancel(slot)
}

private data class HistoryTapCanvasElement(val sink: HistoryTapSink, val slot: Int) : ModifierNodeElement<HistoryTapCanvasNode>() {
    override fun create() = HistoryTapCanvasNode(sink, slot)
    override fun update(node: HistoryTapCanvasNode) {
        node.sink = sink
        node.slot = slot
    }
}

private class HistoryTapCanvasNode(var sink: HistoryTapSink, var slot: Int) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val changes = pointerEvent.changes
        for (i in changes.indices) {
            val c = changes[i]
            if (c.changedToDownIgnoreConsumed()) sink.onCanvasDown(slot, c.id.value)
        }
    }

    override fun onCancelPointerInput() {}
}

/**
 * v1.7 (design §3.10, QA row 8): an open menu only closes under a two- or three-finger tap. A
 * `DropdownMenu` is a window of its own, where history taps don't run (§7), and each item sees
 * only the fingers on it: without this, the item under the first finger to lift fired. Once a
 * second finger is down on the menu, every pointer is consumed from the Initial pass on (the items
 * cancel instead of firing) and [onDismiss] runs once. Put it on the menu's content
 * (`DropdownMenu(modifier = …)`, which is the column around every item).
 *
 * A second finger BESIDE the menu counts too. The menu's window is touch-modal, so it gets that
 * finger, but outside its content the finger hits no node and never reaches this one. What does
 * reach it is the press itself (`ACTION_POINTER_DOWN`), carried by the fingers already on the menu:
 * a [PointerEventType.Press] in which none of this node's own pointers went down.
 */
fun Modifier.closeOnSecondFinger(onDismiss: () -> Unit): Modifier = this then CloseOnSecondFingerElement(onDismiss)

private data class CloseOnSecondFingerElement(val onDismiss: () -> Unit) : ModifierNodeElement<CloseOnSecondFingerNode>() {
    override fun create() = CloseOnSecondFingerNode(onDismiss)
    override fun update(node: CloseOnSecondFingerNode) {
        node.onDismiss = onDismiss
    }
}

private class CloseOnSecondFingerNode(var onDismiss: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    /** A second finger came down in this gesture: its pointers are the menu's no more. */
    private var closing = false

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val changes = pointerEvent.changes
        var pressed = 0
        var landedHere = false
        for (i in changes.indices) {
            val c = changes[i]
            if (c.pressed) pressed++
            if (c.changedToDownIgnoreConsumed()) landedHere = true
        }
        // A finger landed in this window, but not on the menu (see the KDoc).
        val landedBeside = pointerEvent.type == PointerEventType.Press && !landedHere && pressed > 0
        if (!closing && (pressed > 1 || landedBeside)) {
            closing = true
            onDismiss()
        }
        if (closing) for (i in changes.indices) changes[i].consume()
        if (pressed == 0) closing = false
    }

    override fun onCancelPointerInput() {
        closing = false
    }
}
