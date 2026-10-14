package com.brushwork.paint.v19.a7a

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.diagnostics.Breadcrumbs
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.diagnostics.ExitReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowLog

/**
 * v1.9 A7a step 2 (design 3.2 A7a, R9): the exit reasons of the runs before.
 * - The mapping: a low-memory kill, an ANR and a native crash make reports; a Java crash only on
 *   the first run (the handler writes it otherwise); a SIGKILL counts as a low-memory kill only on
 *   a phone that does not report those; the user's and the system's normal stops make none.
 * - Read at start ([ExitReasons.readSince]) through Robolectric's `ActivityManager`: only the exits
 *   after "exitSeenAt", each once, oldest first; a low-memory kill in the background is quiet;
 *   an ANR keeps the main thread of its trace only, a native crash only the names of its
 *   tombstone (no path with a user name, no other text); the last breadcrumbs ride along.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExitInfoReasonTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val am: ActivityManager get() = app.getSystemService(ActivityManager::class.java)

    @Before
    fun clean() {
        ShadowLog.stream = null
        CrashLog.deleteAll(app)
        CrashLog.prefs(app).edit().clear().commit()
    }

    @Test
    fun theReasonsThatMakeReports() {
        assertEquals(CrashReport.Kind.LOW_MEMORY_KILL, ExitReasons.kindOf(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(CrashReport.Kind.ANR, ExitReasons.kindOf(ApplicationExitInfo.REASON_ANR))
        assertEquals(CrashReport.Kind.NATIVE, ExitReasons.kindOf(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertNull("the handler wrote it", ExitReasons.kindOf(ApplicationExitInfo.REASON_CRASH))
        assertEquals("no handler before", CrashReport.Kind.CRASH, ExitReasons.kindOf(ApplicationExitInfo.REASON_CRASH, firstRun = true))
        assertNull(ExitReasons.kindOf(ApplicationExitInfo.REASON_SIGNALED, status = 9, lowMemoryReported = true))
        assertEquals(CrashReport.Kind.LOW_MEMORY_KILL, ExitReasons.kindOf(ApplicationExitInfo.REASON_SIGNALED, status = 9, lowMemoryReported = false))
        assertNull(ExitReasons.kindOf(ApplicationExitInfo.REASON_SIGNALED, status = 15, lowMemoryReported = false))
        for (r in listOf(
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_UNKNOWN,
        )) assertNull(ExitReasons.reasonName(r), ExitReasons.kindOf(r, firstRun = true))

        val fg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val bg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        assertTrue(ExitReasons.showsCard(CrashReport.Kind.LOW_MEMORY_KILL, fg))
        assertTrue(ExitReasons.showsCard(CrashReport.Kind.LOW_MEMORY_KILL, ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        assertFalse(ExitReasons.showsCard(CrashReport.Kind.LOW_MEMORY_KILL, bg))
        assertTrue(ExitReasons.showsCard(CrashReport.Kind.ANR, bg))
        assertTrue(ExitReasons.showsCard(CrashReport.Kind.NATIVE, bg))
    }

    private fun exit(reason: Int, time: Long, importance: Int, description: String? = null, trace: ByteArray? = null, status: Int = 0, summary: ByteArray? = null) {
        val b = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason).setTimestamp(time).setImportance(importance).setStatus(status)
            .setPss(612L * 1024).setRss(700L * 1024).setPid(4242).setProcessName(app.packageName)
        if (description != null) b.setDescription(description)
        if (trace != null) b.setTraceInputStream(trace.inputStream())
        if (summary != null) b.setProcessStateSummary(summary)
        shadowOf(am).addApplicationExitInfo(b.build())
    }

    private val anrTrace = """
        ----- pid 4242 at 2026-10-13 14:03:07 -----
        Cmd line: com.brushwork.paint

        "Signal Catcher" daemon prio=10 tid=4 Runnable
          at some.other.Thread.run(Other.java:1)

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72f0 self=0xb400
          at com.brushwork.paint.tools.select.colorrange.ColorSelectTool.ensureProxy(SourceFile:267)
          - waiting to lock <0x0abc> (a java.lang.Object) held by thread 12
          native: #00 pc 0004e3a8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+24)
          at com.brushwork.paint.engine.Compositor.renderThumbnail(SourceFile:292)

        "RenderThread" daemon prio=7 tid=13 Native
          at secret.Elsewhere.run(Elsewhere.java:9)
    """.trimIndent()

    private fun tombstone(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun field(s: String) { out.write(byteArrayOf(0x0A, s.length.toByte())); out.write(s.toByteArray()) }
        field("SIGSEGV")
        field("SEGV_MAPERR")
        field("/system/lib64/libhwui.so")
        field("android::uirenderer::skiapipeline::SkiaPipeline::renderFrame")
        field("/data/app/~~x9==/com.brushwork.paint-y7==/oat/arm64/base.odex")
        field("com.brushwork.paint.tools.select.magnetic.EdgeMap.build")
        field("/storage/emulated/0/Pictures/Alice Smith/holiday.jpg")
        field("Happy birthday Alice")
        return out.toByteArray()
    }

    @Test
    fun theExitsSinceTheLastRunBecomeReportsOnce() {
        val t0 = 1_760_000_000_000L
        CrashLog.prefs(app).edit().putLong(CrashLog.EXIT_SEEN_AT, t0).commit()
        val fg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val bg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        exit(ApplicationExitInfo.REASON_LOW_MEMORY, t0 - 5_000, fg, "lmk")                      // before: read already
        exit(ApplicationExitInfo.REASON_LOW_MEMORY, t0 + 1_000, fg, "lmk", summary = "tool:MAGNETIC_LASSO > open:2480x3508/5".toByteArray())
        exit(ApplicationExitInfo.REASON_ANR, t0 + 2_000, fg, "Input dispatching timed out (\"Alice's drawing\")", anrTrace.toByteArray())
        exit(ApplicationExitInfo.REASON_CRASH_NATIVE, t0 + 3_000, fg, "crash", tombstone())
        exit(ApplicationExitInfo.REASON_CRASH, t0 + 4_000, fg, "crash")                          // the handler's
        exit(ApplicationExitInfo.REASON_USER_REQUESTED, t0 + 5_000, fg, "remove task")
        exit(ApplicationExitInfo.REASON_LOW_MEMORY, t0 + 6_000, bg, "lmk")                      // background: quiet

        ExitReasons.readSince(app)
        val reports = CrashLog.reports(app).sortedBy { it.timeMillis }
        assertEquals(reports.map { it.id }.toString(), 4, reports.size)
        val (lmk, anr, native, quietLmk) = reports
        assertEquals(CrashLog.idOf(t0 + 1_000, CrashReport.Kind.LOW_MEMORY_KILL, quiet = false), lmk.id)
        assertEquals(CrashLog.idOf(t0 + 2_000, CrashReport.Kind.ANR, quiet = false), anr.id)
        assertEquals(CrashLog.idOf(t0 + 3_000, CrashReport.Kind.NATIVE, quiet = false), native.id)
        assertEquals(CrashLog.idOf(t0 + 6_000, CrashReport.Kind.LOW_MEMORY_KILL, quiet = true), quietLmk.id)
        assertEquals(t0 + 6_000, CrashLog.prefs(app).getLong(CrashLog.EXIT_SEEN_AT, 0))

        // The low-memory kill: its memory at exit, the breadcrumbs it left.
        assertTrue(lmk.text, lmk.text.contains("Memory: at exit: PSS 612 MB, RSS 700 MB"))
        assertTrue(lmk.text, lmk.text.contains("Last breadcrumbs: tool:MAGNETIC_LASSO > open:2480x3508/5"))
        assertEquals("Closed by Android for memory", lmk.summary)
        // The ANR: the main thread only, paths cut to file names, the description scrubbed.
        val main = anr.text.lines()
        assertTrue(anr.text, main.contains("Main thread:"))
        assertTrue(anr.text, main.any { it.contains("ColorSelectTool.ensureProxy(SourceFile:267)") })
        assertTrue(anr.text, main.any { it.contains("libc.so (syscall+24)") && !it.contains("/apex/") })
        assertFalse(anr.text, anr.text.contains("Signal Catcher") || anr.text.contains("secret.Elsewhere") || anr.text.contains("Alice"))
        assertTrue(anr.summary, anr.summary.startsWith("Not responding: Input dispatching timed out"))
        // The native crash: the signal, the library and function names; no path, no other text.
        val nt = native.text
        for (s in listOf("SIGSEGV", "SEGV_MAPERR", "libhwui.so", "android::uirenderer::skiapipeline::SkiaPipeline::renderFrame", "com.brushwork.paint.tools.select.magnetic.EdgeMap.build")) {
            assertTrue("$s in\n$nt", nt.contains(s))
        }
        for (s in listOf("/system/", "Alice", "holiday", "Happy", "/storage/", "~~x9")) assertFalse("$s in\n$nt", nt.contains(s))

        // The card: the newest that shows it (the native crash; the background kill is quiet).
        assertEquals(native.id, CrashLog.unseen(app)?.id)

        // Read again: nothing new.
        ExitReasons.readSince(app)
        assertEquals(4, CrashLog.reports(app).size)
    }

    @Test
    fun theFirstRunTakesWhatAndroidKeptQuietly() {
        val t0 = 1_760_000_000_000L
        val fg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        exit(ApplicationExitInfo.REASON_CRASH, t0, fg, "crash")
        exit(ApplicationExitInfo.REASON_LOW_MEMORY, t0 + 1_000, fg, "lmk")
        ExitReasons.readSince(app)
        val reports = CrashLog.reports(app)
        assertEquals(2, reports.size)
        assertTrue(reports.map { it.id }.toString(), reports.all { it.id.endsWith("-quiet") })
        assertNull("nothing from before the crash log shows the card", CrashLog.unseen(app))
        assertEquals(t0 + 1_000, CrashLog.prefs(app).getLong(CrashLog.EXIT_SEEN_AT, 0))
    }

    @Test
    fun theStateSummaryIsTheNewestBreadcrumbsIn128Bytes() {
        repeat(40) { Breadcrumbs.add("tool:MAGNETIC_LASSO_$it") }
        val bytes = ExitReasons.summaryOf(Breadcrumbs.snapshot())
        assertTrue("${bytes.size}", bytes.size <= ExitReasons.SUMMARY_BYTES)
        val s = String(bytes)
        assertTrue(s, s.endsWith("tool:MAGNETIC_LASSO_39"))
        assertEquals("tool:MAGNETIC_LASSO_39", ExitReasons.stateSummary(ExitReasons.summaryOf(listOf("tool:MAGNETIC_LASSO_39"))))
        // Publishing it never throws, whatever the platform does with it.
        ExitReasons.publishSummary(app)
    }
}
