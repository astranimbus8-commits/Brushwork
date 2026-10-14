package com.brushwork.paint.engine.live

/**
 * v1.9 A6 (R13): a tool whose live preview work waits for the next frame. Touch events arrive
 * faster than frames (the canvas asks for unbuffered input), so a tool that redrew its preview
 * on every event would redraw it two or three times per frame for nothing; instead it keeps the
 * latest state, asks for a frame, and does the work here, once. `CanvasView` calls
 * [beforeFrame] on the current tool first in every frame, before the display tiles update.
 * Main thread.
 */
fun interface BeforeFrame {
    fun beforeFrame()
}
