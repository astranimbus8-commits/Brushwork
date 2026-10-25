package com.brushwork.paint.engine.check

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.MemSnapshot
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.storage.engine.EngineState
import com.brushwork.paint.storage.engine.EngineStateStore
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** v1.10 (A10, design 2.10 "When it runs"): why a run of the check is due. */
enum class CheckTrigger {
    /** `EngineLabels110.CHECK_AGAIN` in Settings (`engineCheckPending`): clears `stepDown` and `lmkDeathsAt`. */
    MANUAL,

    /** No result yet (or an unreadable or newer one). */
    FIRST,

    /** Another phone model or memory size (`deviceKey`). */
    DEVICE,

    /** The first launch of a new `versionCode`. */
    UPDATE,

    /** A run interrupted at M2 (its marker found at this launch), not recorded as a low-memory kill. */
    INTERRUPTED,

    /** A low-memory closing counted after the last run (section 2.12). */
    STEP_DOWN,

    /** The last result is provisional: at each cold start, up to 3. */
    PROVISIONAL,

    /** `Build.FINGERPRINT` changed (an OTA on the same phone). */
    FINGERPRINT,
}

/**
 * v1.10 (A10): what the check needs from the app: the build's [versionCode], `Build.MODEL` and
 * `Build.FINGERPRINT`, whether an editor is open, the layer storage choice, the wall clock, and
 * a way to run on the main thread.
 */
class CheckEnv(
    val versionCode: Int,
    val model: String,
    val fingerprint: String,
    val editorOpen: () -> Boolean,
    val mode: () -> LayerStorageMode,
    val clock: () -> Long = System::currentTimeMillis,
    val onMain: (Runnable) -> Unit,
)

/**
 * v1.10 (A10, design 2.10): runs the automatic check when it is due ([CheckTrigger]) and stores
 * its result ([CheckStore]).
 *
 * **One run** measures M1 (and stops there for a rule 0 or rule 1 verdict), then M2 (inside the
 * [CheckMarker]), M3, M4 and M5 ([CheckSteps]), on one thread at `THREAD_PRIORITY_DEFAULT`
 * ([launchIfDue]), each step within its cap (about 2.7 s in all). Before every step it stops when
 * asked ([requestStop]: the gallery left, the app stopped) or when an editor is open; the steps it
 * finished are kept in memory and the next run resumes with the rest. The decision is
 * [AutoCheck.decide] against this phone's previous result; a manual check (`EngineLabels110.CHECK_AGAIN`) starts
 * from `stepDown = 0` and no closings (the user asked for a fresh decision), every other run
 * carries them. The result is written with `engineCheckPending` and `slowSwapReads` cleared, its
 * gallery notice is raised or settled ([CheckNotices]), and the gallery's budget follows it
 * (`EngineConfig.applyIdle`, on main, so it never races an opening session).
 *
 * **Cold starts.** One instance per process ([of]): a provisional result or an interrupted run
 * re-runs once per process, at most once. [atLaunch] reads a marker left by an interrupted run:
 * a low-memory kill inside its window, or a second interruption in a row, is recorded as final
 * CLASSIC, `LOW_MEMORY`, without a notice; anything else (a swipe, an ANR) only re-runs the check.
 * Unknown memory (`totalMem == 0`) runs nothing and stores nothing.
 */
class AutoCheckRunner(context: Context, private val env: CheckEnv, private val steps: CheckSteps = AndroidCheckSteps(context)) {
    private val context: Context = context.applicationContext ?: context

    /** How a run ended. */
    enum class Outcome { DONE, NOT_DUE, STOPPED, UNKNOWN_MEMORY, FAILED }

    /** What one run finished so far (kept across stops). */
    private class Progress(var trigger: CheckTrigger, val m1: MemSnapshot) {
        var m2: M2Result? = null
        var m3: M3Result? = null
        var m4: M4Result? = null
        var m5: M5Result? = null
        var nanos = 0L
    }

    /** A launch's stop flag: [requestStop] sets the current one. */
    private class Token {
        @Volatile var stopped = false
    }

    @Volatile private var progress: Progress? = null
    @Volatile private var token: Token? = null
    private var launchHandled = false

    /** The attempts of an interrupted run found at this launch (0: none). */
    private var interruptedAttempts = 0

    /** A run finished (or was recorded) in this process: provisional and interrupted re-runs wait for the next one. */
    private var ranThisProcess = false

    private val executor = ThreadPoolExecutor(0, 1, KEEP_ALIVE_S, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            r.run()
        }, "engine-check").apply { isDaemon = true }
    }

    /** True while the run that [launchIfDue] started has steps left (tests). */
    val inProgress: Boolean get() = progress != null

    /**
     * The launch's look at an interrupted run (once per process; `EngineStartup` calls it on its
     * thread, [runIfDue] before its first run). Returns true when it recorded the run as a
     * low-memory kill.
     */
    @Synchronized
    fun atLaunch(): Boolean {
        if (launchHandled) return false
        launchHandled = true
        val marker = CheckMarker.read(context) ?: return false
        CheckMarker.delete(context)
        val window = marker.startMillis..(marker.startMillis + MARKER_WINDOW_MS)
        val exits = runCatching { EngineConfig.exitInfo.exits(marker.startMillis - 1) }.getOrDefault(emptyList())
        val killed = exits.any { it.atMillis in window && (it.lowMemory || it.signaled9) }
        if (!killed && marker.attempts < MAX_INTERRUPTED) {
            interruptedAttempts = marker.attempts
            return false
        }
        return recordInterrupted()
    }

    /**
     * Runs the check on the calling thread if it is due, resuming a stopped run; [stop] is read
     * before each step (and inside M3 and M5). Blocking, up to about 3 s; never on main.
     */
    @Synchronized
    fun runIfDue(stop: () -> Boolean): Outcome {
        atLaunch()
        val halt = { stop() || env.editorOpen() }
        if (halt()) return Outcome.STOPPED
        val stored = CheckStore.readStored(context)
        val state = EngineStateStore.read(context)
        var p = progress
        if (p == null) {
            val t0 = System.nanoTime()
            val m1 = runCatching { steps.m1() }.getOrNull()
            if (m1 == null || m1.totalMem <= 0L) return Outcome.UNKNOWN_MEMORY
            p = Progress(trigger(m1, stored, state) ?: return Outcome.NOT_DUE, m1)
            p.nanos += System.nanoTime() - t0
            progress = p
        } else {
            p.trigger = trigger(p.m1, stored, state) ?: run {
                progress = null
                return Outcome.NOT_DUE
            }
        }
        val m1 = p.m1
        val stepDown = carried(p, stored)?.stepDown ?: 0
        val early = AutoCheck.earlyVerdict(m1.totalMem, m1.isLowRamDevice, m1.is64Bit, m1.javaMax, stepDown)
        if (early == null) {
            if (p.m2 == null) {
                if (halt()) return Outcome.STOPPED
                p.m2 = timed(p) { m2() }
            }
            if (p.m3 == null) {
                if (halt()) return Outcome.STOPPED
                p.m3 = timed(p) { steps.m3(deadline(M3_CAP_MS), halt) } ?: return Outcome.STOPPED
            }
            if (p.m4 == null) {
                if (halt()) return Outcome.STOPPED
                p.m4 = timed(p) { steps.m4() }
            }
            if (p.m5 == null) {
                if (halt()) return Outcome.STOPPED
                p.m5 = timed(p) { steps.m5(deadline(M5_CAP_MS), halt) } ?: return Outcome.STOPPED
            }
        }
        finish(p, stored)
        return Outcome.DONE
    }

    /** Starts [runIfDue] on the check's thread (after a run still stopping); [requestStop] stops it. */
    fun launchIfDue(): Future<Outcome> {
        val t = Token()
        token = t
        return executor.submit<Outcome> {
            try {
                runIfDue { t.stopped }
            } catch (e: Exception) {
                Log.w(TAG, "engine check failed", e)
                progress = null
                Outcome.FAILED
            }
        }
    }

    /** Stops the running check before its next step and deletes the marker at once (cancel, `ON_STOP`). Any thread. */
    fun requestStop() {
        token?.stopped = true
        CheckMarker.delete(context)
    }

    /** `onTrimMemory`: the phone wants memory back, so the check stops and its marker goes. */
    fun onTrimMemory() = requestStop()

    /** Why a run is due for a phone with [m1]'s memory, or null. */
    private fun trigger(m1: MemSnapshot, stored: EngineCheckResult?, state: EngineState): CheckTrigger? {
        val key = EngineConfig.deviceKey(env.model, m1.totalMem)
        return when {
            state.engineCheckPending -> CheckTrigger.MANUAL
            stored == null -> CheckTrigger.FIRST
            stored.deviceKey != key -> CheckTrigger.DEVICE
            stored.appVersionCode != env.versionCode -> CheckTrigger.UPDATE
            interruptedAttempts > 0 && !ranThisProcess -> CheckTrigger.INTERRUPTED
            (stored.lmkDeathsAt.maxOrNull() ?: 0L) > stored.ranAtMillis -> CheckTrigger.STEP_DOWN
            stored.provisional && !ranThisProcess -> CheckTrigger.PROVISIONAL
            fingerprintChanged() -> CheckTrigger.FINGERPRINT
            else -> null
        }
    }

    /** This phone's previous result, whose step-down and closings the run carries; null for a manual run. */
    private fun carried(p: Progress, stored: EngineCheckResult?): EngineCheckResult? =
        if (p.trigger == CheckTrigger.MANUAL) null else previous(p.m1, stored)

    private fun previous(m1: MemSnapshot, stored: EngineCheckResult?): EngineCheckResult? =
        stored?.takeIf { it.deviceKey == EngineConfig.deviceKey(env.model, m1.totalMem) }

    /** M2 inside the marker (the one step that can get the app killed for memory). */
    private fun m2(): M2Result {
        CheckMarker.write(context, CheckMarker.Marker(env.clock(), interruptedAttempts + 1))
        try {
            return steps.m2()
        } finally {
            CheckMarker.delete(context)
        }
    }

    private inline fun <T> timed(p: Progress, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            p.nanos += System.nanoTime() - t0
        }
    }

    private fun deadline(capMs: Long): Long = System.nanoTime() + capMs * 1_000_000L

    /** Decides, stores and publishes the result of [p]. */
    private fun finish(p: Progress, stored: EngineCheckResult?) {
        val m1 = p.m1
        val previous = previous(m1, stored)
        val carry = carried(p, stored)
        val m2 = p.m2
        val m3 = p.m3
        val m4 = p.m4
        val m5 = p.m5
        val inputs = CheckInputs(
            totalMem = m1.totalMem, availMem = m1.availMem, threshold = m1.threshold, lowRam = m1.isLowRamDevice,
            is64Bit = m1.is64Bit, javaMax = m1.javaMax, stepDown = carry?.stepDown ?: 0,
            nativeProbeBytes = m2?.bytes ?: 0L, m2Outcome = m2?.outcome.orEmpty(),
            deflateMBs = m3?.deflateMBs ?: 0f, inflateMBs = m3?.inflateMBs ?: 0f, ratio = m3?.ratio ?: 0f,
            writeMBs = m4?.writeMBs ?: 0f, readMBs = m4?.readMBs ?: 0f, freeBytes = m4?.freeBytes ?: 0L,
            composite64Ms = m5?.compositeMs ?: 0f, belowCacheMinLayers = m5?.belowCacheMinLayers,
        )
        val d = AutoCheck.decide(inputs, previous)
        val result = resultOf(inputs, d, (p.nanos / 1_000_000L), carry)
        store(result, manual = p.trigger == CheckTrigger.MANUAL, previous = previous, announce = true)
        progress = null
    }

    /** The stored record of [inputs] and [d]; [carry] gives the step-down and closings. */
    private fun resultOf(inputs: CheckInputs, d: Decision, durationMs: Long, carry: EngineCheckResult?) = EngineCheckResult(
        appVersionCode = env.versionCode,
        deviceKey = EngineConfig.deviceKey(env.model, inputs.totalMem),
        ranAtMillis = env.clock(),
        durationMs = durationMs,
        level = d.level,
        reason = d.reason,
        totalMem = inputs.totalMem,
        availMem = inputs.availMem,
        threshold = inputs.threshold,
        lowRam = inputs.lowRam,
        is64Bit = inputs.is64Bit,
        javaMax = inputs.javaMax,
        nativeProbeBytes = inputs.nativeProbeBytes,
        deflateMBs = inputs.deflateMBs,
        inflateMBs = inputs.inflateMBs,
        ratio = inputs.ratio,
        writeMBs = inputs.writeMBs,
        readMBs = inputs.readMBs,
        freeBytes = inputs.freeBytes,
        composite64Ms = inputs.composite64Ms,
        belowCacheMinLayers = inputs.belowCacheMinLayers,
        aboveProjection = d.aboveProjection,
        stepDown = carry?.stepDown ?: 0,
        lmkDeathsAt = carry?.lmkDeathsAt.orEmpty(),
        provisional = d.provisional,
        m2Outcome = inputs.m2Outcome,
        inconclusiveRuns = d.inconclusiveRuns,
    )

    /** An interrupted run recorded as final CLASSIC, `LOW_MEMORY` (no notice); false when memory is unknown. */
    private fun recordInterrupted(): Boolean {
        val m1 = runCatching { steps.m1() }.getOrNull()?.takeIf { it.totalMem > 0L } ?: return false
        val stored = CheckStore.readStored(context)
        val manual = EngineStateStore.read(context).engineCheckPending
        val carry = if (manual) null else previous(m1, stored)
        val inputs = CheckInputs(m1.totalMem, m1.availMem, m1.threshold, m1.isLowRamDevice, m1.is64Bit, m1.javaMax, stepDown = carry?.stepDown ?: 0)
        val d = Decision(EngineLevel.CLASSIC, CheckReasons.LOW_MEMORY, provisional = false, inconclusiveRuns = 0, aboveProjection = false)
        store(resultOf(inputs, d, 0L, carry), manual, previous(m1, stored), announce = false)
        return true
    }

    /**
     * Writes [result], clears the state's flags, notes the fingerprint, raises or settles the
     * gallery's notice against [previous] ([CheckNotices]; [announce] false for an interrupted
     * run) and applies the gallery's budget.
     */
    private fun store(result: EngineCheckResult, manual: Boolean, previous: EngineCheckResult?, announce: Boolean) {
        CheckStore.write(context, result)
        CheckNotices.afterCheck(context, result, previous, env.mode(), announce)
        val now = EngineStateStore.read(context)
        if ((manual && now.engineCheckPending) || now.slowSwapReads) {
            EngineStateStore.write(context, now.copy(engineCheckPending = now.engineCheckPending && !manual, slowSwapReads = false))
        }
        runCatching { fingerprintFile().writeText(env.fingerprint) }
        ranThisProcess = true
        interruptedAttempts = 0
        env.onMain(Runnable { EngineConfig.applyIdle(env.mode(), result, Budgets.compute(EngineConfig.memoryProbe, result.stepDown)) })
    }

    private fun fingerprintFile() = File(context.noBackupFilesDir, FINGERPRINT_FILE)

    /** The fingerprint the last result was made under differs from this build's (none noted yet: no change). */
    private fun fingerprintChanged(): Boolean {
        val f = fingerprintFile()
        if (!f.isFile) return false
        return runCatching { f.readText().trim() }.getOrNull()?.let { it != env.fingerprint.trim() } ?: false
    }

    companion object {
        private const val TAG = "BrushworkEngine"

        /** Per-step caps (design 2.10; M2 and M4 are bounded by their probes). */
        const val M3_CAP_MS = 400L
        const val M5_CAP_MS = 800L

        /** A low-memory kill counts against a marker when it happened this long after M2 started. */
        const val MARKER_WINDOW_MS = 5_000L

        /** Interrupted runs in a row that are recorded as CLASSIC. */
        const val MAX_INTERRUPTED = 2

        /** The fingerprint the last result was made under, in `noBackupFilesDir`. */
        const val FINGERPRINT_FILE = "engine-check.fingerprint"

        /** The check thread lingers this long after a run, then ends. */
        private const val KEEP_ALIVE_S = 5L

        @Volatile private var shared: AutoCheckRunner? = null

        /**
         * Tests: under Robolectric (one JVM, many apps) the gallery starts no check unless a test
         * sets this, so the gallery's UI tests never measure in the background.
         */
        @Volatile internal var runUnderRobolectric = false

        /** The gallery may start the check: always on a phone, under Robolectric only when a test asks. */
        val galleryRuns: Boolean get() = runUnderRobolectric || Build.FINGERPRINT != "robolectric"

        /**
         * `EngineLabels110.CHECK_AGAIN` (design 2.11): sets `engineCheckPending`, so the check runs at
         * the next gallery as a manual one (fresh: no step-down or closings carried). Blocking (one
         * small file), off the main thread. The check never runs while an editor is open, where the
         * settings are, so nothing writes the state meanwhile.
         */
        fun requestCheck(context: Context) {
            val state = EngineStateStore.read(context)
            if (!state.engineCheckPending) EngineStateStore.write(context, state.copy(engineCheckPending = true))
        }

        /**
         * Slow swap reads in use (design 2.10; `SlabSwapStore` calls it once per session): sets
         * `slowSwapReads`, so the next sessions run at TILES ([CheckStore.read]) until the next check
         * clears it. Blocking (one small file), off the main thread.
         */
        fun markSlowSwapReads(context: Context) {
            val state = EngineStateStore.read(context)
            if (!state.slowSwapReads) EngineStateStore.write(context, state.copy(slowSwapReads = true))
            Log.w(TAG, "slow swap reads: the next sessions run at TILES until the next check")
        }

        /** The process's runner for [app] (a new one for a new app, as in each Robolectric test). */
        fun of(app: BrushworkApp): AutoCheckRunner = synchronized(this) {
            shared?.takeIf { it.context === app } ?: AutoCheckRunner(app, envOf(app)).also { shared = it }
        }

        private fun envOf(app: BrushworkApp): CheckEnv {
            val code = runCatching {
                val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
            }.getOrDefault(0)
            val main = Handler(Looper.getMainLooper())
            return CheckEnv(
                versionCode = code,
                model = Build.MODEL.orEmpty(),
                fingerprint = Build.FINGERPRINT.orEmpty(),
                editorOpen = { app.editorSession != null },
                mode = { app.settings.layerStorageMode },
                onMain = { main.post(it) },
            )
        }
    }
}
