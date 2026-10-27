package com.brushwork.paint.v110.a10

import android.content.Context
import android.os.Looper
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.budget.MemSnapshot
import com.brushwork.paint.engine.budget.MemoryProbe
import com.brushwork.paint.engine.check.AndroidCheckSteps
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckEnv
import com.brushwork.paint.engine.check.CheckMarker
import com.brushwork.paint.engine.check.CheckSteps
import com.brushwork.paint.engine.check.M2Result
import com.brushwork.paint.engine.check.M3Result
import com.brushwork.paint.engine.check.M4Result
import com.brushwork.paint.engine.check.M5Result
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.testing.FakeClock
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.time.Duration

/** The A10 tests' shared pieces (design 2.10-2.12). */
internal object A10Kit {
    const val MODEL = "T606"
    const val VERSION = 110
    const val FINGERPRINT = "zte/t606/1"

    /** M3 and M5 numbers of a fast phone (FULL with the default fakes). */
    val FAST_M3 = M3Result(deflateMBs = 80f, inflateMBs = 250f, ratio = 3f)
    val FAST_M5 = M5Result(compositeMs = 20f, belowCacheMinLayers = 32)

    /** The device key of [totalMem] on [MODEL]. */
    fun key(totalMem: Long): String = EngineConfig.deviceKey(MODEL, totalMem)

    /** An env for [context]: main-thread work runs at once (collected in [mainRuns]). */
    class Env(
        var versionCode: Int = VERSION,
        var fingerprint: String = FINGERPRINT,
        val clock: FakeClock = FakeClock(),
    ) {
        @Volatile var editorOpen = false
        @Volatile var mode = LayerStorageMode.AUTOMATIC
        var mainRuns = 0

        fun env(): CheckEnv = CheckEnv(
            versionCode = versionCode,
            model = MODEL,
            fingerprint = fingerprint,
            editorOpen = { editorOpen },
            mode = { mode },
            clock = clock.millis,
            onMain = {
                mainRuns++
                it.run()
            },
        )
    }

    /**
     * The phone's steps (M1, M2 and M4 through the installed fake probes) with chosen M3 and M5
     * results (null: the real benches), counting each step's runs; [before] runs before each step
     * with its name.
     */
    class Steps(
        context: Context,
        var m3: M3Result? = FAST_M3,
        var m5: M5Result? = FAST_M5,
    ) : CheckSteps {
        private val real = AndroidCheckSteps(context)
        val calls = mutableMapOf<String, Int>()
        var before: (String) -> Unit = {}

        private fun count(step: String) {
            before(step)
            calls[step] = (calls[step] ?: 0) + 1
        }

        fun calls(step: String): Int = calls[step] ?: 0

        override fun m1(): MemSnapshot {
            count("m1")
            return real.m1()
        }

        override fun m2(): M2Result {
            count("m2")
            return real.m2()
        }

        override fun m3(deadlineNanos: Long, stop: () -> Boolean): M3Result? {
            count("m3")
            return m3 ?: real.m3(deadlineNanos, stop)
        }

        override fun m4(): M4Result {
            count("m4")
            return real.m4()
        }

        override fun m5(deadlineNanos: Long, stop: () -> Boolean): M5Result? {
            count("m5")
            return m5 ?: real.m5(deadlineNanos, stop)
        }
    }

    fun runner(context: Context, env: Env, steps: CheckSteps = Steps(context)): AutoCheckRunner = AutoCheckRunner(context, env.env(), steps)

    /**
     * [fake] whose M2 notes the check's marker as it finds it at that moment in [markers] (null:
     * none), so a test sees the marker exactly while M2 allocates.
     */
    class MarkerWatch(val fake: FakeMemoryProbe = FakeMemoryProbe(MemoryPreset.T606)) : MemoryProbe by fake {
        val markers: MutableList<CheckMarker.Marker?> = java.util.Collections.synchronizedList(mutableListOf())

        override fun probeNative(stepBytes: Long, stopBelowAvail: Long, capBytes: Long): Long {
            markers += CheckMarker.read(RuntimeEnvironment.getApplication())
            return fake.probeNative(stepBytes, stopBelowAvail, capBytes)
        }
    }

    /** Pumps the main looper (giving other threads real time) until [done] or [timeoutMs] of real time; whether [done] held. */
    fun pumpUntil(timeoutMs: Long = 20_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    /** A stored result of this phone (T606 memory unless [totalMem] says otherwise). */
    fun result(
        level: EngineLevel,
        reason: String = "",
        totalMem: Long = 3788L shl 20,
        version: Int = VERSION,
        ranAt: Long = 1_700_000_000_000L,
        provisional: Boolean = false,
        inconclusiveRuns: Int = 0,
        stepDown: Int = 0,
        lmkDeathsAt: List<Long> = emptyList(),
        m2Outcome: String = "REACHED",
    ) = EngineCheckResult(
        appVersionCode = version, deviceKey = key(totalMem), ranAtMillis = ranAt, durationMs = 1000L, level = level, reason = reason,
        totalMem = totalMem, availMem = 1843L shl 20, threshold = 216L shl 20, lowRam = false, is64Bit = true, javaMax = 512L shl 20,
        nativeProbeBytes = 256L shl 20, deflateMBs = 80f, inflateMBs = 250f, ratio = 3f, writeMBs = 120f, readMBs = 250f,
        freeBytes = 8L shl 30, composite64Ms = 20f, stepDown = stepDown, lmkDeathsAt = lmkDeathsAt, provisional = provisional,
        m2Outcome = m2Outcome, inconclusiveRuns = inconclusiveRuns,
    )
}
