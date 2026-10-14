package com.brushwork.paint.engine.live

import com.brushwork.paint.AppSettings

/**
 * v1.9 A6 (P4, R9 / R20; design LA2): the drawing-preview setting (`StabilityLabels19.DRAWING_PREVIEW`:
 * `StabilityLabels19.FASTER` or `StabilityLabels19.EXACT_PIXELS`, saved in `AppSettings.drawPreview`).
 * With the faster preview a painting stroke is shown from the live proxies while the finger is
 * down ([LiveAdjust.beginStroke]): the stroke's area is composited at the screen proxy scale
 * instead of at document resolution. The dabs are stamped at document resolution either way, so
 * the committed stroke and its undo step are the same in both modes; the canvas refines to exact
 * pixels after the lift.
 *
 * LA2: it ships only when the JVM proxy measures the faster preview at least 20 % cheaper per
 * frame than exact pixels on A4 or PRINT at fit zoom. It did not (`DrawPreviewProxyBench`, JVM ms
 * per frame, faster / exact: A4 at fit 8.50 / 8.40, PRINT at fit 9.09 / 9.19; the drawing frame
 * is the tiles' blit and the canvas chrome, the stroke's own composite is about 2 % of it), so
 * [SHIPPED] is false: the setting is not shown, [faster] is false, and every stroke previews as
 * in v1.8 (exact pixels). The code stays for the lead's decision at the merge; tests turn it on
 * with [available].
 */
object StrokePreview {
    /** LA2's verdict: false = CUT (proxy). */
    const val SHIPPED = false

    /** The setting is shown and honoured ([SHIPPED]; tests turn it on and back off). */
    @Volatile
    internal var available: Boolean = SHIPPED

    /** True when strokes starting now preview faster ([available] and the saved choice). */
    fun faster(settings: AppSettings): Boolean =
        available && settings.drawPreview == AppSettings.DRAW_PREVIEW_FASTER

    /** Zooming faster than this factor per second shows the stroke exactly (P4's guard). */
    const val ZOOM_GUARD_PER_SECOND = 1.2
}
