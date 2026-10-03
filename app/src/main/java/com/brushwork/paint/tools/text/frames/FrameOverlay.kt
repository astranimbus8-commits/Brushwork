package com.brushwork.paint.tools.text.frames

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer

/**
 * One frame as the Text frames tool's overlay shows it (v1.6, §3.6a): its outer box (document
 * px, the pending one while it is dragged), whether its story goes on beyond it ([overset], the
 * red "+") or into a next frame ([linked]), and where it is in its story.
 */
internal class OverlayFrame(
    val layer: Layer?,
    val rect: RectF,
    val storyId: Long,
    val index: Int,
    val overset: Boolean,
    val linked: Boolean,
    val locked: Boolean = false,
)

/**
 * Draws the Text frames tool's overlay in screen space (v1.6, §3.6a; area D): every frame's box
 * (accent for the selected one, red when its story is overset), the in-port (top-left) and the
 * out-port (bottom-right: a red "+" when the story continues beyond the frame, an arrow when it
 * continues in the next frame), the thread lines from each out-port to the next frame's in-port
 * (1.5 dp accent), the 8 handles of the selected frame and the rectangle being drawn.
 */
internal class FrameOverlay {
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = HALO }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private var dashDensity = 0f
    private val path = Path()

    /** Screen corners of doc rect [r] (TL, TR, BR, BL), following a rotated or mirrored view. */
    private fun corners(t: ViewTransform, r: RectF): List<Vec2> = listOf(
        t.docToScreen(Vec2(r.left, r.top)), t.docToScreen(Vec2(r.right, r.top)),
        t.docToScreen(Vec2(r.right, r.bottom)), t.docToScreen(Vec2(r.left, r.bottom)),
    )

    private fun quad(c: List<Vec2>): Path {
        path.rewind()
        path.moveTo(c[0].x, c[0].y)
        for (i in 1..3) path.lineTo(c[i].x, c[i].y)
        path.close()
        return path
    }

    /**
     * Draws [frames]; [selected] gets handles, [loaded] (the out-port tapped to link) is
     * highlighted with [pulse] (0..1), [drawing] (doc px) is the rectangle being drawn.
     */
    fun draw(
        canvas: Canvas,
        t: ViewTransform,
        frames: List<OverlayFrame>,
        selected: Layer?,
        loaded: Layer?,
        showThreads: Boolean,
        drawing: RectF?,
        pulse: Float,
    ) {
        val stroke = t.dp(1f)
        halo.strokeWidth = t.dp(3f)
        // Thread lines under the frames' ports.
        if (showThreads) {
            line.strokeWidth = t.dp(THREAD_LINE_DP)
            line.color = ACCENT
            val byStory = frames.groupBy { it.storyId }
            for (chain in byStory.values) {
                val sorted = chain.sortedBy { it.index }
                for (i in 0 until sorted.size - 1) {
                    val a = FramePorts.outPort(t, sorted[i].rect)
                    val b = FramePorts.inPort(t, sorted[i + 1].rect)
                    canvas.drawLine(a.x, a.y, b.x, b.y, halo)
                    canvas.drawLine(a.x, a.y, b.x, b.y, line)
                }
            }
        }
        for (f in frames) {
            val sel = f.layer != null && f.layer === selected
            val c = corners(t, f.rect)
            val q = quad(c)
            line.strokeWidth = if (sel) t.dp(1.5f) else stroke
            line.color = when {
                f.overset -> OVERSET
                sel -> ACCENT
                else -> FRAME
            }
            canvas.drawPath(q, halo)
            canvas.drawPath(q, line)
            drawPort(canvas, t, FramePorts.inPort(t, f.rect), PortKind.IN, false, 0f)
            val kind = when {
                f.overset -> PortKind.OVERSET
                f.linked -> PortKind.LINKED
                else -> PortKind.EMPTY
            }
            drawPort(canvas, t, FramePorts.outPort(t, f.rect), kind, f.layer != null && f.layer === loaded, pulse)
            if (sel) for (h in FrameGeometry.Handle.entries) drawHandle(canvas, t, t.docToScreen(h.at(f.rect)), f.locked)
        }
        if (drawing != null) {
            if (dashDensity != t.density) {
                dashDensity = t.density
                dash.pathEffect = DashPathEffect(floatArrayOf(t.dp(6f), t.dp(4f)), 0f)
            }
            dash.strokeWidth = t.dp(1.5f)
            val q = quad(corners(t, drawing))
            canvas.drawPath(q, halo)
            canvas.drawPath(q, dash)
        }
    }

    private enum class PortKind { IN, EMPTY, LINKED, OVERSET }

    private fun drawPort(canvas: Canvas, t: ViewTransform, at: Vec2, kind: PortKind, loaded: Boolean, pulse: Float) {
        val half = t.dp(PORT_DP) / 2f
        fill.color = HALO
        canvas.drawRect(at.x - half - t.dp(1f), at.y - half - t.dp(1f), at.x + half + t.dp(1f), at.y + half + t.dp(1f), fill)
        fill.color = if (loaded) blend(ACCENT, 0xFFFFFFFF.toInt(), pulse) else 0xFFFFFFFF.toInt()
        canvas.drawRect(at.x - half, at.y - half, at.x + half, at.y + half, fill)
        line.strokeWidth = t.dp(1f)
        line.color = if (kind == PortKind.OVERSET) OVERSET else FRAME_DARK
        canvas.drawRect(at.x - half, at.y - half, at.x + half, at.y + half, line)
        val k = half * 0.6f
        line.strokeWidth = t.dp(2f)
        when (kind) {
            PortKind.OVERSET -> {
                line.color = OVERSET
                canvas.drawLine(at.x - k, at.y, at.x + k, at.y, line)
                canvas.drawLine(at.x, at.y - k, at.x, at.y + k, line)
            }
            PortKind.LINKED -> {
                // A small triangle: the story goes on in the next frame.
                fill.color = ACCENT_DARK
                path.rewind()
                path.moveTo(at.x - k * 0.6f, at.y - k)
                path.lineTo(at.x + k, at.y)
                path.lineTo(at.x - k * 0.6f, at.y + k)
                path.close()
                canvas.drawPath(path, fill)
            }
            PortKind.IN, PortKind.EMPTY -> {}
        }
    }

    private fun drawHandle(canvas: Canvas, t: ViewTransform, at: Vec2, locked: Boolean) {
        val r = t.dp(HANDLE_RADIUS_DP)
        fill.color = HALO
        canvas.drawCircle(at.x, at.y, r + t.dp(1.5f), fill)
        fill.color = if (locked) 0xFF9AA0A6.toInt() else ACCENT
        canvas.drawCircle(at.x, at.y, r, fill)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1f - k) + ((b shr s) and 0xFF) * k).toInt() and 0xFF
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        const val ACCENT = 0xFF4DA3FF.toInt()
        private const val ACCENT_DARK = 0xFF2B5E93.toInt()
        const val OVERSET = 0xFFE53935.toInt()
        private const val FRAME = 0xCCFFFFFF.toInt()
        private const val FRAME_DARK = 0xFF5F6368.toInt()
        private const val HALO = 0x99000000.toInt()

        /** The out-port / in-port square (IbisDims.FramePort). */
        const val PORT_DP = 14f

        /** Thread lines (IbisDims.ThreadLine). */
        const val THREAD_LINE_DP = 1.5f
        const val HANDLE_RADIUS_DP = 7f
    }
}

/**
 * Where a frame's ports are (v1.6, §3.6a): the in-port just outside its top-left corner, the
 * out-port just outside its bottom-right corner (diagonally, so they never cover the text or the
 * corner handles), in screen px.
 */
internal object FramePorts {
    /** Distance of a port's centre from its corner, along each axis (dp). */
    const val OFFSET_DP = 13f

    private fun offsetDoc(t: ViewTransform): Float = t.screenToDocLength(t.dp(OFFSET_DP))

    /** The out-port's centre of a frame whose outer box is [r] (doc px), in screen px. */
    fun outPort(t: ViewTransform, r: RectF): Vec2 {
        val o = offsetDoc(t)
        return t.docToScreen(Vec2(r.right + o, r.bottom + o))
    }

    /** The in-port's centre of a frame whose outer box is [r] (doc px), in screen px. */
    fun inPort(t: ViewTransform, r: RectF): Vec2 {
        val o = offsetDoc(t)
        return t.docToScreen(Vec2(r.left - o, r.top - o))
    }
}
