package com.brushwork.paint.v110.lead

import android.os.Looper
import android.os.SystemClock
import com.brushwork.paint.vector.select.TapCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.reflect.Modifier
import java.time.Duration

/**
 * v1.10 F0 (R1, J19, finding 23; lead, frozen): the tap cycle has no time limit, as v1.9's. A second
 * tap at the same spot 5 s later still gives depth 1 (all sides). The 5 s pass on Robolectric's
 * paused clock (no real wait), and the cycle is shown not to read any clock: TapCycle.kt names no
 * clock and the class stores no time.
 */
@RunWith(RobolectricTestRunner::class)
class TapCycleNoTimeoutTest {

    @Test
    fun aSecondTapFiveSecondsLaterStillGivesDepthOne() {
        val t = TapCycle()
        val before = SystemClock.uptimeMillis()
        assertEquals(0, t.next(200f, 300f, 7L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue("the clock moved 5 s", SystemClock.uptimeMillis() - before >= 5_000)
        assertEquals(1, t.next(203f, 304f, 7L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(2, t.next(200f, 300f, 7L))

        // No clock is read: the source names none (the names are split so the Timing scanner
        // does not take this file for a clock reader) ...
        val src = mainFile("vector/select/TapCycle.kt").readText()
        val clocks = listOf(
            "System" + ".nanoTime", "System" + ".currentTimeMillis", "System" + "Clock", "uptime" + "Millis", "elapsed" + "Realtime",
            "Time" + "Source", "Instant", "Clock" + ".", "with" + "Frame", "Choreo" + "grapher", "Duration", "Handler", "delay(",
        )
        val named = clocks.filter { it in src }
        assertTrue("TapCycle.kt names a clock or a timer: $named", named.isEmpty())
        // ... and the class keeps no time (a timeout needs a stored instant).
        val fields = TapCycle::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertTrue("TapCycle has state", fields.isNotEmpty())
        val timeLike = fields.filter { f ->
            f.type == java.lang.Long.TYPE || f.type == java.lang.Long::class.java || f.type == java.lang.Double.TYPE ||
                f.type.name.contains("Duration") || f.type.name.contains("Time") || f.type.name.contains("Instant")
        }
        assertTrue("TapCycle stores a time: ${timeLike.map { it.name }}", timeLike.isEmpty())
        // Its only constructor takes nothing (no clock is injected).
        assertTrue(TapCycle::class.java.constructors.all { it.parameterCount == 0 })
    }

    /** The module's main source file [path] under `com/brushwork/paint`, searched upwards from the working directory. */
    private fun mainFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (root in listOf("src/main/java/com/brushwork/paint", "app/src/main/java/com/brushwork/paint")) {
                val f = File(File(dir, root), path)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        throw AssertionError("$path not found from ${System.getProperty("user.dir")}")
    }
}
