package com.brushwork.paint.tools.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.StrokeResources
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.ui.editor.CanvasView
import org.robolectric.Shadows.shadowOf
import java.lang.management.ManagementFactory
import java.time.Duration
import kotlin.math.max

/**
 * Frame-by-frame simulation of a finger editing a vector object on the phone, with the costs
 * split the way a real frame spends them: the touch events (the tool's onMove), the main looper
 * (the coalesced live brush replay runs there) and the canvas view's draw (re-rendering dirty
 * display tiles through the compositor, then the overlays). Timings are wall-clock on the test
 * machine (a phone is several times slower); dab counts, recomposited pixels and allocations
 * are machine-independent.
 */
internal class VectorPerfHarness(private val c: EditorController, private val view: CanvasView) {

    private val screen: Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    private val screenCanvas = Canvas(screen)
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private var down = 0L

    /** Totals of one measured drag. */
    class Stats(val name: String) {
        var frames = 0
        var inputNs = 0L
        var looperNs = 0L
        /** Re-rendering the dirty display tiles (the part of the draw that scales with the edit). */
        var drawNs = 0L
        /** The rest of the view's draw (blitting every tile to the screen: GPU work on the phone). */
        var screenNs = 0L
        var maxFrameNs = 0L
        var stamps = 0L
        var dirtyPx = 0L
        /** Display tiles re-rendered (each one is re-uploaded to the GPU on the phone). */
        var tiles = 0L
        var allocBytes = 0L
        /** Allocations of the touch input, the looper (the live replay) and the tile update. */
        var allocInput = 0L
        var allocLooper = 0L
        var allocTiles = 0L
        /** The release (finger up + looper + draw) after the drag: total and its worst frame. */
        var releaseNs = 0L
        var releaseMaxFrameNs = 0L
        var releaseStamps = 0L
        /** Most dabs drawn in one frame during / after the drag (the work of the worst frame). */
        var maxFrameStamps = 0L
        var releaseMaxFrameStamps = 0L
        /** Every frame's time (input + looper + tiles) during the drag, and after it. */
        val frameNs = ArrayList<Long>()
        val releaseFrameNs = ArrayList<Long>()

        private fun ms(ns: Long) = ns / 1e6
        private fun kb(bytes: Long) = bytes / 1024.0 / max(1, frames)
        private fun p90(list: List<Long>): Double = if (list.isEmpty()) 0.0 else ms(list.sorted()[(list.size * 9) / 10 - (if (list.size >= 10) 1 else 0)])
        val avgFrameMs: Double get() = ms(inputNs + looperNs + drawNs) / max(1, frames)
        val maxFrameMs: Double get() = ms(maxFrameNs)
        /** 90th percentile of the frame times (a busy machine's hiccups don't count). */
        val p90FrameMs: Double get() = p90(frameNs)
        val releaseP90FrameMs: Double get() = p90(releaseFrameNs)
        val stampsPerFrame: Double get() = stamps.toDouble() / max(1, frames)
        val dirtyMpxPerFrame: Double get() = dirtyPx / 1e6 / max(1, frames)
        val allocKbPerFrame: Double get() = allocBytes / 1024.0 / max(1, frames)

        val tilesPerFrame: Double get() = tiles.toDouble() / max(1, frames)
        val releaseMaxFrameMs: Double get() = ms(releaseMaxFrameNs)

        override fun toString(): String = String.format(
            java.util.Locale.ROOT,
            "[perf] %-34s frames=%d avg=%.2fms (input %.2f, looper %.2f, tiles %.2f) p90=%.2fms max=%.2fms screen=%.2fms dabs/frame=%.0f (max %d) dirty=%.2fMpx/frame tiles/frame=%.1f alloc=%.0fKB/frame (input %.0f, looper %.0f, tiles %.0f) release=%.2fms (p90 frame %.2fms, worst %.2fms, %d dabs, max %d in a frame)",
            name, frames, avgFrameMs, ms(inputNs) / max(1, frames), ms(looperNs) / max(1, frames), ms(drawNs) / max(1, frames),
            p90FrameMs, maxFrameMs, ms(screenNs) / max(1, frames), stampsPerFrame, maxFrameStamps, dirtyMpxPerFrame, tilesPerFrame, allocKbPerFrame,
            kb(allocInput), kb(allocLooper), kb(allocTiles), ms(releaseNs), releaseP90FrameMs, ms(releaseMaxFrameNs), releaseStamps, releaseMaxFrameStamps,
        )
    }

    private val stamper get() = StrokeResources.of(c).stamper

    /** Screen position of a document point. */
    fun screenOf(p: Vec2): Vec2 = c.viewTransform.docToScreen(p)

    @Suppress("UNCHECKED_CAST")
    private fun dirtyRects(): Array<Rect?> =
        DisplayTiles::class.java.getDeclaredField("dirty").apply { isAccessible = true }.get(c.tiles) as Array<Rect?>

    /** Pixels of the display tiles waiting to be re-rendered. */
    fun dirtyPixels(): Long = dirtyRects().sumOf { r -> r?.let { it.width().toLong() * it.height() } ?: 0L }

    /** Display tiles waiting to be re-rendered. */
    fun dirtyTiles(): Int = dirtyRects().count { it != null }

    fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** What the canvas view does in onDraw (tiles, then overlays), into an offscreen screen. */
    fun draw() = view.draw(screenCanvas)

    /** Lets time pass and draws, so nothing is pending before a measurement. */
    fun settle(ms: Long = 400) {
        var left = ms
        while (left > 0) { idle(16); draw(); left -= 16 }
    }

    private fun event(action: Int, samples: List<Vec2>): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        fun coords(p: Vec2) = arrayOf(MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 0.6f; size = 0.1f })
        val now = SystemClock.uptimeMillis()
        val n = samples.size
        val e = MotionEvent.obtain(down, now - 8L * (n - 1), action, 1, props, coords(samples[0]), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        for (i in 1 until n) e.addBatch(now - 8L * (n - 1 - i), coords(samples[i]), 0)
        return e
    }

    private fun send(action: Int, vararg samples: Vec2) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        val e = event(action, samples.toList())
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Two fingers (pointer ids 0 and 1) at screen points [a] and [b]; [index] for POINTER_DOWN / UP. */
    private fun send2(action: Int, a: Vec2, b: Vec2, index: Int = 0) {
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(a, b).map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 0.6f; size = 0.1f } }.toTypedArray()
        val masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), masked, 2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    /** One frame: the input, the looper for 16 ms, then the draw. */
    @Suppress("DEPRECATION")
    private fun frame(s: Stats?, input: () -> Unit) {
        val tid = Thread.currentThread().id
        val a0 = threads.getThreadAllocatedBytes(tid)
        val st0 = stamper.stampCount
        val t0 = System.nanoTime()
        input()
        val t1 = System.nanoTime()
        val a1 = threads.getThreadAllocatedBytes(tid)
        idle(16)
        val t2 = System.nanoTime()
        val a2 = threads.getThreadAllocatedBytes(tid)
        val dirty = dirtyPixels()
        val dirtyTiles = dirtyTiles()
        // The view's onDraw starts with this; done here to time it on its own.
        c.tiles.update(c.compositor)
        val t3 = System.nanoTime()
        val a3 = threads.getThreadAllocatedBytes(tid)
        draw()
        val t4 = System.nanoTime()
        if (s == null) return
        s.allocInput += a1 - a0
        s.allocLooper += a2 - a1
        s.allocTiles += a3 - a2
        s.frames++
        s.inputNs += t1 - t0
        s.looperNs += t2 - t1
        s.drawNs += t3 - t2
        s.screenNs += t4 - t3
        s.maxFrameNs = max(s.maxFrameNs, t3 - t0)
        s.frameNs += t3 - t0
        s.stamps += stamper.stampCount - st0
        s.maxFrameStamps = max(s.maxFrameStamps, stamper.stampCount - st0)
        s.dirtyPx += dirty
        s.tiles += dirtyTiles
        s.allocBytes += threads.getThreadAllocatedBytes(tid) - a0
    }

    /**
     * A finger lands on document point [from] and moves along [path] (document points, two touch
     * samples per frame: 120 Hz input on a 60 Hz display), then lifts. The first [warmup] frames
     * are not measured. Without [release] the finger lifts and nothing else happens (no frame
     * runs after it): the caller measures what follows.
     */
    fun drag(name: String, from: Vec2, path: (Int) -> Vec2, frames: Int = 60, warmup: Int = 0, release: Boolean = true): Stats {
        val s = Stats(name)
        // Garbage left by earlier tests (documents, tiles) is collected now, not mid-measurement.
        System.gc()
        send(MotionEvent.ACTION_DOWN, screenOf(from))
        idle(16); draw()
        var prev = from
        for (f in 0 until warmup + frames) {
            val next = path(f)
            val mid = prev.lerp(next, 0.5f)
            frame(if (f >= warmup) s else null) { send(MotionEvent.ACTION_MOVE, screenOf(mid), screenOf(next)) }
            prev = next
        }
        if (!release) {
            send(MotionEvent.ACTION_UP, screenOf(prev))
            println(s)
            return s
        }
        val st0 = stamper.stampCount
        // The finger lifts; the exact redraw after the drag (if any) happens within the next
        // frames (screen blits not counted).
        var total = 0L
        var worst = 0L
        for (f in 0 until 60) {
            val sf = stamper.stampCount
            val t0 = System.nanoTime()
            if (f == 0) send(MotionEvent.ACTION_UP, screenOf(prev))
            idle(16)
            c.tiles.update(c.compositor)
            val t1 = System.nanoTime()
            draw()
            total += t1 - t0
            worst = max(worst, t1 - t0)
            s.releaseFrameNs += t1 - t0
            s.releaseMaxFrameStamps = max(s.releaseMaxFrameStamps, stamper.stampCount - sf)
        }
        s.releaseNs = total
        s.releaseMaxFrameNs = worst
        s.releaseStamps = stamper.stampCount - st0
        println(s)
        return s
    }

    /**
     * A finger drags from document point [from] along [path] for [dragFrames] frames (not
     * measured), then a second finger lands next to it (the one-finger edit is cancelled) and the
     * two fingers spread apart, zooming the view, for [frames] measured frames; then both lift.
     */
    fun pinchAfterDrag(name: String, from: Vec2, path: (Int) -> Vec2, dragFrames: Int = 30, frames: Int = 40): Stats {
        val s = Stats(name)
        System.gc()
        send(MotionEvent.ACTION_DOWN, screenOf(from))
        idle(16); draw()
        var prev = from
        for (f in 0 until dragFrames) {
            val next = path(f)
            val mid = prev.lerp(next, 0.5f)
            frame(null) { send(MotionEvent.ACTION_MOVE, screenOf(mid), screenOf(next)) }
            prev = next
        }
        // Screen positions from here on (the view zooms under the fingers).
        val a0 = screenOf(prev)
        val b0 = Vec2(a0.x + 240f, a0.y + 80f)
        send2(MotionEvent.ACTION_POINTER_DOWN, a0, b0, index = 1)
        var a = a0
        var b = b0
        for (f in 0 until frames) {
            val k = (f + 1) * 5f
            a = Vec2(a0.x - k, a0.y - k / 3f)
            b = Vec2(b0.x + k, b0.y + k / 3f)
            frame(s) { send2(MotionEvent.ACTION_MOVE, a, b) }
        }
        send2(MotionEvent.ACTION_POINTER_UP, a, b, index = 1)
        send(MotionEvent.ACTION_UP, a)
        idle(16); draw()
        println(s)
        return s
    }

    /** A quick tap on document point [p]. */
    fun tap(p: Vec2) {
        idle(200)
        send(MotionEvent.ACTION_DOWN, screenOf(p))
        idle(40)
        send(MotionEvent.ACTION_UP, screenOf(p))
        idle(50)
    }
}
