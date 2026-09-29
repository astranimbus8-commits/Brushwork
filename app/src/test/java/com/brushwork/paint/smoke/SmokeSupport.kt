package com.brushwork.paint.smoke

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * Shared plumbing of the whole-app smoke tests: a real [EditorController] on a real main-thread
 * scope whose uncaught coroutine exceptions are recorded (so they fail the test instead of only
 * being logged), main-looper pumping, touch events for views, and invariants that must hold
 * between any two user actions.
 */
internal object Smoke {

    @Volatile var step = "start"
    @Volatile private var stepSince = System.currentTimeMillis()

    /** Progress marker: printed, and shown by the [watchdog] when a step hangs. */
    fun step(name: String) {
        val now = System.currentTimeMillis()
        println("[smoke] +${now - stepSince} ms  -> $name")
        step = name
        stepSince = now
    }

    /**
     * Prints the stack of [thread] whenever one [step] takes longer than [limitMs] (an endless
     * frame loop otherwise only shows up as an OutOfMemoryError minutes later).
     */
    fun watchdog(thread: Thread = Thread.currentThread(), limitMs: Long = 20_000): Thread =
        Thread {
            var reported = ""
            try {
                while (true) {
                    Thread.sleep(2_000)
                    val s = step
                    if (System.currentTimeMillis() - stepSince > limitMs && s != reported) {
                        reported = s
                        System.err.println("[smoke] WATCHDOG: step \"$s\" running for ${System.currentTimeMillis() - stepSince} ms; main thread:")
                        thread.stackTrace.take(80).forEach { System.err.println("    at $it") }
                    }
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }

    /** Uncaught exceptions of the scopes made by [scope] (cleared by [newScope]). */
    val scopeErrors: MutableList<Throwable> = java.util.Collections.synchronizedList(mutableListOf())

    /** Main-thread scope like the app's (SupervisorJob + Main.immediate) that records failures. */
    fun newScope(): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, t -> scopeErrors += t }
        return CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
    }

    /** Document of [w] x [h] with [layers] empty layers (a white bottom layer if [whiteBottom]). */
    fun document(w: Int = 400, h: Int = 300, layers: Int = 2, whiteBottom: Boolean = false): Document {
        val doc = Document("smoke", "Smoke", w, h)
        repeat(layers) { i ->
            val bmp = BitmapUtils.createLayerBitmap(w, h)
            if (i == 0 && whiteBottom) bmp.eraseColor(-1)
            doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", bmp)
        }
        doc.activeLayerIndex = doc.layers.lastIndex
        return doc
    }

    fun controller(context: Context, doc: Document = document(), scope: CoroutineScope = newScope()): EditorController {
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        return EditorController(context.applicationContext, doc, scope, settings)
    }

    /** Runs the main looper for [ms] of virtual time in [stepMs] steps. */
    fun pump(ms: Long = 200, stepMs: Long = 20) {
        var left = ms
        while (left > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMs))
            left -= stepMs
        }
    }

    /**
     * Pumps the main looper (and gives background threads real time) until [done] or the
     * real-time [timeoutMs] runs out. Returns whether [done] became true.
     */
    fun pumpUntil(timeoutMs: Long = 20_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    /** Depth-first search of the view tree. */
    fun <T : View> find(root: View, cls: Class<T>): T? {
        if (cls.isInstance(root)) return cls.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), cls)?.let { return it }
        return null
    }

    /** Error-level log lines (Robolectric's ShadowLog) since the last [clearLogs]. */
    fun errorLogs(): List<String> = ShadowLog.getLogs()
        .filter { it.type >= Log.ERROR }
        .map { "${it.tag}: ${it.msg}" + (it.throwable?.let { t -> " / ${t.javaClass.simpleName}: ${t.message}" } ?: "") }

    fun clearLogs() = ShadowLog.clear()

    /**
     * What must hold whenever no gesture or operation is in progress: no busy overlay, no
     * dangling gesture, a valid active layer, no preview without pending work, no escaped
     * coroutine exception.
     */
    fun assertQuiet(c: EditorController, where: String) {
        assertTrue("$where: scope errors ${scopeErrors.map { it.toString() }}", scopeErrors.isEmpty())
        assertNull("$where: still busy", c.busyMessage)
        assertFalse("$where: gesture left open", c.isInteracting)
        assertTrue("$where: active layer ${c.doc.activeLayerIndex} of ${c.doc.layers.size}", c.doc.activeLayerIndex in c.doc.layers.indices)
        if (!c.currentTool.hasPendingWork && c.filterSession == null) {
            assertNull("$where: stale renderOverride with no pending work (${c.activeToolId})", c.renderOverride)
        }
        assertEquals("$where: canUndo out of sync", c.undoManager.canUndo, c.canUndo)
        assertEquals("$where: canRedo out of sync", c.undoManager.canRedo, c.canRedo)
    }

    // ------------------------------------------------------------------ touch

    data class P(val id: Int, val x: Float, val y: Float, val tool: Int = MotionEvent.TOOL_TYPE_FINGER)

    /**
     * Sends MotionEvents to a view (a window root or the canvas itself). Event times are the
     * (Robolectric) uptime clock, which [idle] advances by running the main looper, so the long
     * press and tap timeouts see the same time as the events.
     */
    class Touch(private val view: View) {
        private var down = 0L

        private fun event(action: Int, pts: Array<out P>, index: Int): MotionEvent {
            val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = pts[i].id; toolType = pts[i].tool } }
            val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].x; y = pts[i].y; pressure = 0.6f; size = 0.1f } }
            val masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            return MotionEvent.obtain(down, SystemClock.uptimeMillis(), masked, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }

        fun send(action: Int, vararg pts: P, index: Int = 0) {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val e = event(action, pts, index)
            view.dispatchTouchEvent(e)
            e.recycle()
        }

        /** Lets [ms] of time pass (frames and posted callbacks run). */
        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

        /** One finger through [points] (view pixels), a sample every 16 ms. */
        fun stroke(vararg points: Pair<Float, Float>) {
            send(MotionEvent.ACTION_DOWN, P(0, points[0].first, points[0].second))
            for (i in 1 until points.size) {
                val (ax, ay) = points[i - 1]
                val (bx, by) = points[i]
                for (s in 1..6) {
                    idle(16)
                    send(MotionEvent.ACTION_MOVE, P(0, ax + (bx - ax) * s / 6f, ay + (by - ay) * s / 6f))
                }
            }
            idle(16)
            send(MotionEvent.ACTION_UP, P(0, points.last().first, points.last().second))
            idle(50)
        }

        /** A quick tap with one finger. */
        fun tap(x: Float, y: Float) {
            send(MotionEvent.ACTION_DOWN, P(0, x, y))
            idle(40)
            send(MotionEvent.ACTION_UP, P(0, x, y))
            idle(50)
        }

        /** Two fingers down and up quickly without moving (undo). */
        fun twoFingerTap(a: Pair<Float, Float>, b: Pair<Float, Float>) {
            send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
            idle(30)
            send(MotionEvent.ACTION_POINTER_DOWN, P(0, a.first, a.second), P(1, b.first, b.second), index = 1)
            idle(70)
            send(MotionEvent.ACTION_POINTER_UP, P(0, a.first, a.second), P(1, b.first, b.second), index = 0)
            idle(20)
            send(MotionEvent.ACTION_UP, P(1, b.first, b.second))
            idle(50)
        }

        /** Three fingers down and up quickly without moving (redo). */
        fun threeFingerTap(a: Pair<Float, Float>, b: Pair<Float, Float>, d: Pair<Float, Float>) {
            send(MotionEvent.ACTION_DOWN, P(0, a.first, a.second))
            idle(20)
            send(MotionEvent.ACTION_POINTER_DOWN, P(0, a.first, a.second), P(1, b.first, b.second), index = 1)
            idle(20)
            send(MotionEvent.ACTION_POINTER_DOWN, P(0, a.first, a.second), P(1, b.first, b.second), P(2, d.first, d.second), index = 2)
            idle(80)
            send(MotionEvent.ACTION_POINTER_UP, P(0, a.first, a.second), P(1, b.first, b.second), P(2, d.first, d.second), index = 0)
            idle(10)
            send(MotionEvent.ACTION_POINTER_UP, P(1, b.first, b.second), P(2, d.first, d.second), index = 0)
            idle(10)
            send(MotionEvent.ACTION_UP, P(2, d.first, d.second))
            idle(50)
        }

        /** Two fingers from (a0, b0) to (a1, b1), slowly (never a tap). */
        fun pinch(a0: Pair<Float, Float>, b0: Pair<Float, Float>, a1: Pair<Float, Float>, b1: Pair<Float, Float>) {
            send(MotionEvent.ACTION_DOWN, P(0, a0.first, a0.second))
            idle(10)
            send(MotionEvent.ACTION_POINTER_DOWN, P(0, a0.first, a0.second), P(1, b0.first, b0.second), index = 1)
            for (s in 1..10) {
                idle(40)
                val f = s / 10f
                send(
                    MotionEvent.ACTION_MOVE,
                    P(0, a0.first + (a1.first - a0.first) * f, a0.second + (a1.second - a0.second) * f),
                    P(1, b0.first + (b1.first - b0.first) * f, b0.second + (b1.second - b0.second) * f),
                )
            }
            idle(20)
            send(MotionEvent.ACTION_POINTER_UP, P(0, a1.first, a1.second), P(1, b1.first, b1.second), index = 0)
            idle(20)
            send(MotionEvent.ACTION_UP, P(1, b1.first, b1.second))
            idle(50)
        }
    }
}
