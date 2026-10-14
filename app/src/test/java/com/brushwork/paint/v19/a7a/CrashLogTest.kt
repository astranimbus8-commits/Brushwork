package com.brushwork.paint.v19.a7a

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.diagnostics.Breadcrumbs
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.diagnostics.ReportText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * v1.9 A7a step 1 (design 3.2 A7a, R9): the crash log's files.
 * - Format: a report is `filesDir/diagnostics/reports/<millis>-<kind>[-quiet].txt`; its text
 *   starts with the title and the fields (kind, fatal, time, where, app, device, memory,
 *   summary), then the breadcrumbs, oldest first, then the stack with the frames of the throw.
 * - Ring: the newest [CrashLog.MAX_REPORTS] are kept. Size cap: a report is at most
 *   [CrashLog.MAX_BYTES], its stack cut with "[cut: N more lines]".
 * - Privacy: no path, link, e-mail address, quoted text or artwork name gets into a report.
 * - The gallery's card: the newest crash not dismissed ([CrashLog.unseen], [CrashLog.markSeen]);
 *   handled errors are quiet (Diagnostics only), kept once a minute per place and class.
 * - The uncaught handler writes the report, then hands the crash to the handler before it.
 */
@RunWith(RobolectricTestRunner::class)
class CrashLogTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val dir: File get() = File(app.filesDir, "diagnostics/reports")

    @Before
    fun clean() {
        ShadowLog.stream = null
        CrashLog.deleteAll(app)
        CrashLog.prefs(app).edit().clear().commit()
        CrashLog.resetRepeats()
    }

    @After
    fun drain() = CrashLog.drain()

    private fun files() = (dir.listFiles() ?: emptyArray()).map { it.name }.sorted()

    @Test
    fun aFatalReportHasTheFieldsTheBreadcrumbsAndTheStack() {
        Breadcrumbs.add("tool:MAGNETIC_LASSO")
        Breadcrumbs.add("open:400x300/2")
        val boom = IllegalStateException("the wire broke", IllegalArgumentException("no edge"))
        CrashLog.record(boom, "uncaught on thread main", fatal = true)

        val names = files()
        assertEquals(names.toString(), 1, names.size)
        assertTrue(names[0], Regex("""\d{13}-crash\.txt""").matches(names[0]))
        val text = File(dir, names[0]).readText()
        val lines = text.lines()
        assertEquals(ReportText.TITLE, lines[0])
        val fields = listOf("Kind", "Fatal", "Time", "Where", "App", "Device", "Memory", "Summary")
        val order = fields.map { f -> lines.indexOfFirst { it.startsWith("$f: ") } }
        assertTrue("every field, in order: $order\n$text", order.all { it > 0 } && order == order.sorted())
        assertTrue(text, lines.contains("Kind: CRASH"))
        assertTrue(text, lines.contains("Fatal: yes"))
        assertTrue(text, lines.contains("Where: uncaught on thread main"))
        assertTrue(text, lines.any { Regex("""Time: \d{4}-\d\d-\d\d \d\d:\d\d:\d\d UTC""").matches(it) })
        assertTrue(text, lines.contains("Summary: Crash: IllegalArgumentException"))
        // The breadcrumbs, oldest first, before the stack.
        val crumbs = lines.indexOf("Breadcrumbs, oldest first:")
        val stack = lines.indexOf("Stack:")
        assertTrue(text, crumbs in 1 until stack)
        val tool = lines.indexOf("tool:MAGNETIC_LASSO")
        val open = lines.indexOf("open:400x300/2")
        assertTrue(text, tool in crumbs until open && open < stack)
        // The stack: the class and message, this method's frame (file and line), the cause.
        assertEquals("java.lang.IllegalStateException: the wire broke", lines[stack + 1])
        assertTrue(text, lines.any { it.startsWith("\tat ") && it.contains("aFatalReportHasTheFieldsTheBreadcrumbsAndTheStack(CrashLogTest.kt:") })
        assertTrue(text, lines.contains("Caused by: java.lang.IllegalArgumentException: no edge"))

        // Read back: newest first, the summary from the file, on the gallery's card until dismissed.
        val reports = CrashLog.reports(app)
        assertEquals(1, reports.size)
        val r = reports[0]
        assertEquals(names[0].removeSuffix(".txt"), r.id)
        assertEquals(CrashReport.Kind.CRASH, r.kind)
        assertEquals("Crash: IllegalArgumentException", r.summary)
        assertEquals(text, r.text)
        assertEquals(r, CrashLog.unseen(app))
        CrashLog.markSeen(app, r.id)
        assertNull("dismissed", CrashLog.unseen(app))
        assertEquals("still listed", 1, CrashLog.reports(app).size)
    }

    @Test
    fun outOfMemoryIsItsOwnKind() {
        CrashLog.record(RuntimeException("wrapped", OutOfMemoryError("Failed to allocate a 48000016 byte allocation")), "uncaught on thread main", fatal = true)
        val r = CrashLog.reports(app).single()
        assertEquals(CrashReport.Kind.OUT_OF_MEMORY, r.kind)
        assertTrue(r.id, r.id.endsWith("-out_of_memory"))
        assertEquals("Out of memory: OutOfMemoryError", r.summary)
        assertTrue(r.text, r.text.contains("Memory: Java heap "))
        assertEquals("an out-of-memory crash shows the card", r, CrashLog.unseen(app))
    }

    @Test
    fun handledErrorsAreQuietAndKeptOnceAMinutePerPlace() {
        val e = IllegalStateException("trace")
        CrashLog.record(e, "Magnetic lasso trace", fatal = false)
        CrashLog.record(e, "Magnetic lasso trace", fatal = false)
        CrashLog.record(IllegalArgumentException("x"), "Magnetic lasso trace", fatal = false)
        CrashLog.record(e, "Color select preview", fatal = false)
        CrashLog.drain()
        val reports = CrashLog.reports(app)
        assertEquals(reports.map { it.summary }.toString(), 3, reports.size)
        assertTrue(reports.all { it.id.endsWith("-crash-quiet") && it.text.contains("Fatal: no") })
        assertTrue(reports.map { it.summary }.toString(), reports.any { it.summary == "Handled error: IllegalStateException in Magnetic lasso trace" })
        assertNull("a handled error never shows the card", CrashLog.unseen(app))
    }

    @Test
    fun theRingKeepsTheNewestTwenty() {
        val t0 = 1_760_000_000_000L
        for (i in 0 until CrashLog.MAX_REPORTS + 5) {
            assertNotNull(CrashLog.write(t0 + i * 1000L, CrashReport.Kind.CRASH, quiet = false, "${ReportText.TITLE}\nSummary: n$i\n"))
        }
        val names = files()
        assertEquals(CrashLog.MAX_REPORTS, names.size)
        assertEquals(CrashLog.idOf(t0 + 5 * 1000L, CrashReport.Kind.CRASH, false) + ".txt", names.first())
        val reports = CrashLog.reports(app)
        assertEquals("newest first", "n${CrashLog.MAX_REPORTS + 4}", reports.first().summary)
        assertEquals("n5", reports.last().summary)
        // Two reports of the same millisecond both stay (the second a millisecond later).
        CrashLog.write(t0 + 99_000L, CrashReport.Kind.FREEZE, quiet = false, "a")
        CrashLog.write(t0 + 99_000L, CrashReport.Kind.FREEZE, quiet = false, "b")
        assertTrue(files().contains(CrashLog.idOf(t0 + 99_001L, CrashReport.Kind.FREEZE, false) + ".txt"))
        assertEquals(CrashLog.MAX_REPORTS, files().size)
        // A freeze never shows the card.
        assertEquals(CrashLog.idOf(t0 + (CrashLog.MAX_REPORTS + 4) * 1000L, CrashReport.Kind.CRASH, false), CrashLog.unseen(app)?.id)
        CrashLog.deleteAll(app)
        assertTrue(files().isEmpty())
        assertTrue(CrashLog.reports(app).isEmpty())
    }

    @Test
    fun aReportIsAtMost64KbWithItsStackCut() {
        fun deep(n: Int, tag: Int): Throwable {
            val e = IllegalStateException("deep ${"é".repeat(200)}")
            // Frames of their own per throwable (frames shared with the enclosing trace are printed as "... n more").
            e.stackTrace = Array(n) { i -> StackTraceElement("com.brushwork.paint.tools.select.magnetic.DeepFrame${tag}Number$i", "levelNumber$i", "DeepFrameNumber.kt", i + 1) }
            return e
        }
        val e = deep(4000, 0)
        var c: Throwable = e
        repeat(8) { k -> val next = deep(900, k + 1); c.initCause(next); c = next }
        CrashLog.record(e, "uncaught on thread main", fatal = true)
        val f = dir.listFiles()!!.single()
        assertTrue("${f.length()} bytes", f.length() <= CrashLog.MAX_BYTES)
        assertTrue("near the cap: ${f.length()}", f.length() > CrashLog.MAX_BYTES - 1024)
        val last = f.readText().trimEnd().lines().last()
        assertTrue(last, Regex("""\[cut: \d+ more lines]""").matches(last))
        // The fields and the breadcrumbs are whole.
        assertTrue(f.readText().contains("Summary: Crash: IllegalStateException"))

        // compose: multi-byte text counts in bytes.
        val text = ReportText.compose(listOf("Kind" to "CRASH"), listOf("Stack" to List(2000) { "€€€€€€€€€€ $it" }), 4096)
        assertTrue(text.toByteArray().size <= 4096)
        assertTrue(text.trimEnd().lines().last().startsWith("[cut: "))
    }

    @Test
    fun noPersonalDataGetsIntoAReport() {
        val secrets = listOf(
            "/data/user/0/com.brushwork.paint/files/projects/abc123/layer_1.png",
            "/storage/emulated/0/Pictures/Alice Smith/cat.jpg",
            "content://com.android.providers.media.documents/document/image%3A42",
            "alice.smith@example.com",
            "Happy birthday Alice",
            "C:\\Users\\alice\\art.png",
        )
        val e = IllegalStateException(
            "open failed: ${secrets[0]} (No such file); copy ${secrets[1].replace(" ", "_")} from ${secrets[2]}; " +
                "sent to ${secrets[3]}; text \"${secrets[4]}\"; ${secrets[5]}",
            NumberFormatException("For input string: \"${secrets[4]}\""),
        )
        CrashLog.record(e, "uncaught on thread main", fatal = true)
        val text = CrashLog.reports(app).single().text
        for (s in secrets + listOf("alice", "Alice", "abc123", "projects/", "emulated", "Pictures")) {
            assertFalse("\"$s\" in the report:\n$text", text.contains(s))
        }
        assertTrue(text, text.contains("<path>") && text.contains("<uri>") && text.contains("<email>") && text.contains("<text>"))
        // The class names and the frames stay.
        assertTrue(text.contains("java.lang.IllegalStateException: open failed: <path>"))
        assertTrue(text.contains("Caused by: java.lang.NumberFormatException: For input string: <text>"))
        // The open artwork's name is scrubbed wherever it appears.
        assertEquals("saving <name> failed", ReportText.scrub("saving My Cat failed", listOf("My Cat")))
        // Only report files are written, under filesDir/diagnostics.
        assertTrue(files().all { Regex("""\d{13}-[a-z_]+(-quiet)?\.txt""").matches(it) })
        assertFalse(File(app.filesDir, "projects").exists() && File(app.filesDir, "projects").list()?.isNotEmpty() == true)
    }

    @Test
    fun theHandlerWritesTheReportThenHandsTheCrashOn() {
        var handedOn: Throwable? = null
        var reportsThen = -1
        val handler = CrashLog.Handler { _, e ->
            handedOn = e
            reportsThen = files().size
        }
        val boom = IllegalStateException("boom")
        val t = Thread { throw boom }
        t.uncaughtExceptionHandler = handler
        t.start()
        t.join()
        assertSame(boom, handedOn)
        assertEquals("the report is written before the crash is handed on", 1, reportsThen)
        val r = CrashLog.reports(app).single()
        assertTrue(r.text, r.text.contains("Where: uncaught on thread ${t.name}"))
        assertEquals(CrashReport.Kind.CRASH, r.kind)
    }
}
