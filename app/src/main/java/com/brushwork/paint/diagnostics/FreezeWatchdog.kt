package com.brushwork.paint.diagnostics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock

/**
 * v1.9 (R9, design 3.2 A7a step 3; owner A7a): the freeze watchdog. Every [intervalMs] (1 s) its
 * thread asks the main thread to answer a no-op ([tick]); when the main thread has not answered
 * for [freezeMs] (4 s), it takes the main thread's stack once and reports a
 * [CrashReport.Kind.FREEZE] ([onFreeze]). One report per freeze: it is armed again only once the
 * main thread answers. A gap between two ticks of more than two intervals means the whole process
 * was held (the phone slept, a debugger stopped it): the watchdog starts over and reports nothing.
 *
 * Pure (a clock, a way to post to the main thread, the main thread's stack are given), so
 * `FreezeWatchdogTest` drives it tick by tick. [start] runs it in the app: on its own thread, only
 * while an activity is started and no debugger is attached; a tick posts one runnable (well under
 * 0.1 ms a second). Android's own ANR (5 s of unanswered input) is read at the next start
 * ([ExitReasons]); the watchdog also sees the freezes that end before that, or that have no input.
 */
internal class FreezeWatchdog(
    private val clock: () -> Long,
    private val postToMain: (Runnable) -> Unit,
    private val mainStack: () -> Array<StackTraceElement>,
    private val onFreeze: (blockedMs: Long, stack: Array<StackTraceElement>) -> Unit,
    val intervalMs: Long = INTERVAL_MS,
    val freezeMs: Long = FREEZE_MS,
) {
    @Volatile
    private var answered = true
    private var pingedAt = 0L
    private var lastTick = -1L
    private var reported = false

    /** The answer the main thread runs. */
    private val pong = Runnable { answered = true }

    /** Freezes reported so far (tests). */
    var freezes = 0
        private set

    /** One tick, on the watchdog's thread, every [intervalMs]. */
    fun tick() {
        val now = clock()
        val gap = if (lastTick < 0) 0L else now - lastTick
        lastTick = now
        if (gap > 2 * intervalMs) {
            // The watchdog itself was held: no freeze can be told from that. Start over.
            answered = true
            reported = false
        }
        if (!answered) {
            val blocked = now - pingedAt
            if (blocked >= freezeMs && !reported) {
                reported = true
                freezes++
                onFreeze(blocked, mainStack())
            }
            return
        }
        reported = false
        answered = false
        pingedAt = now
        postToMain(pong)
    }

    /** Forgets a ping not answered (the watchdog stops: no activity is started). */
    fun reset() {
        answered = true
        reported = false
        lastTick = -1L
    }

    companion object {
        /** The ping's interval (ms). */
        const val INTERVAL_MS = 1_000L

        /** The main thread blocked this long (ms) is a freeze. */
        const val FREEZE_MS = 4_000L

        @Volatile
        private var running: Runner? = null

        /** Runs the watchdog for [app] (once; `CrashLog.install`, not under Robolectric). */
        fun start(app: Application) {
            if (running != null) return
            running = Runner(app).also { app.registerActivityLifecycleCallbacks(it) }
        }
    }

    /**
     * The watchdog in the app: its thread ticks while at least one activity is started; a freeze
     * is written by the crash log on its own thread; each tick also hands Android the newest
     * breadcrumbs when they changed ([ExitReasons.publishSummary]).
     */
    private class Runner(private val app: Application) : Application.ActivityLifecycleCallbacks, Runnable {
        private val main = Handler(Looper.getMainLooper())
        private val thread = HandlerThread("brushwork-watchdog", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        private val handler = Handler(thread.looper)
        private var started = 0
        @Volatile
        private var ticking = false
        private var crumbs = -1
        private val dog = FreezeWatchdog(
            clock = { SystemClock.uptimeMillis() },
            postToMain = { main.post(it) },
            mainStack = { Looper.getMainLooper().thread.stackTrace },
            onFreeze = { blocked, stack -> CrashLog.recordFreeze(blocked, stack) },
        )

        override fun run() {
            if (!ticking) return
            try {
                if (!Debug.isDebuggerConnected()) dog.tick() else dog.reset()
                val v = Breadcrumbs.added
                if (v != crumbs) {
                    crumbs = v
                    CrashLog.background { ExitReasons.publishSummary(app) }
                }
            } catch (e: Throwable) {
                // Never stops the app: the next tick tries again.
            }
            handler.postDelayed(this, INTERVAL_MS)
        }

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) {
                ticking = true
                handler.post { dog.reset() }
                handler.post(this)
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (started > 0 && --started == 0) {
                ticking = false
                handler.removeCallbacks(this)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
