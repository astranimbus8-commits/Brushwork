package com.brushwork.paint.v19.a7a

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.diagnostics.ReportSharing
import com.brushwork.paint.ui.diagnostics.isMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.TimeZone

/**
 * v1.9 A7a step 5 (design 3.2 A7a, R9): what the crash card's and the Diagnostics list's buttons do.
 * - Copy puts the report's text on the clipboard and says [StabilityLabels19.REPORT_COPIED].
 * - Share opens the system's share sheet with the report's text (from the app's context, as a new task).
 * - Share all: one text for a few reports; past 100 KB a file in the cache, shared through the
 *   app's FileProvider, readable by the app the user picks.
 * - The buttons' times: no two read alike (the second, then a number, when minutes are shared).
 * - The card names memory for an out-of-memory crash and a low-memory kill.
 * The card's layout (360 dp, one line of 40 dp buttons) is a section of the area's one UI class,
 * [DiagnosticsSectionRobolectricTest] (design 5.5: A7a's cap is one Robolectric UI class).
 */
@RunWith(RobolectricTestRunner::class)
class CrashCardRobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun report(time: Long, kind: CrashReport.Kind = CrashReport.Kind.CRASH, text: String = "Brushwork crash report\nKind: ${kind.name}\n") =
        CrashReport("%013d-%s".format(time, kind.name.lowercase()), time, kind, "Crash: IllegalStateException", text)

    @Before
    fun quiet() {
        ShadowLog.stream = null
    }

    @Test
    fun copyPutsTheReportOnTheClipboardAndSaysSo() {
        val r = report(1_760_364_187_000L, text = "Brushwork crash report\nKind: CRASH\nStack:\n    at a.b.C.d(C.kt:12)\n")
        assertTrue(ReportSharing.copy(app, r))
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(r.text, cm.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(StabilityLabels19.REPORT_COPIED, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun shareOpensTheShareSheetWithTheReportsText() {
        val r = report(1_760_364_187_000L)
        assertTrue(ReportSharing.share(app, r))
        val chooser = shadowOf(app).nextStartedActivity
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue("from the app's context: a new task", chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(r.text, send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(send.getStringExtra(Intent.EXTRA_SUBJECT)!!.startsWith("Brushwork crash report 2025-10-13"))
    }

    @Test
    fun shareAllIsOneTextOrPast100KbAFileThroughTheFileProvider() {
        assertNull("nothing to share", ReportSharing.shareAllIntent(app, emptyList()))

        val few = (0 until 3).map { report(1_760_364_187_000L - it * 60_000L, text = "report $it\n") }
        val small = ReportSharing.shareAllIntent(app, few)!!
        val text = small.getStringExtra(Intent.EXTRA_TEXT)!!
        for (i in 0 until 3) assertTrue(text, text.contains("report $i"))
        assertTrue("newest first", text.indexOf("report 0") < text.indexOf("report 2"))
        assertTrue(ReportSharing.shareAll(app, small))
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(app).nextStartedActivity.action)

        // Twenty reports of 64 KB: past the limit, a file.
        clearFileProviderCache()
        val big = (0 until 20).map { i -> report(1_760_364_187_000L - i * 60_000L, text = "report $i\n" + "x".repeat(64 * 1024 - 20)) }
        val file = ReportSharing.shareAllIntent(app, big)
        val written = File(File(app.cacheDir, "exports"), ReportSharing.ALL_FILE)
        assertTrue(written.isFile)
        assertEquals(ReportSharing.allText(big), written.readText())
        assertTrue(written.length() > ReportSharing.TEXT_LIMIT)
        // FileProvider matches its roots with '/' separators: it maps a path on a Unix-like host
        // only (always on a phone, and on CI); the file is written either way.
        if (File.separatorChar == '/') {
            assertNotNull(file)
            assertNull(file!!.getStringExtra(Intent.EXTRA_TEXT))
            @Suppress("DEPRECATION")
            val uri = file.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals(app.packageName + ".fileprovider", uri.authority)
            assertTrue(file.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
        written.delete()
    }

    /**
     * androidx FileProvider caches its roots per authority in a static map; Robolectric gives each
     * test a new cache dir but keeps statics, so an earlier test's root would reject this one's.
     */
    private fun clearFileProviderCache() {
        for (f in androidx.core.content.FileProvider::class.java.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers) || !Map::class.java.isAssignableFrom(f.type)) continue
            f.isAccessible = true
            (f.get(null) as? MutableMap<*, *>)?.let { synchronized(it) { it.clear() } }
        }
    }

    @Test
    fun theButtonsTimesNeverReadAlike() {
        val utc = TimeZone.getTimeZone("UTC")
        val t = 1_760_364_187_000L // 2025-10-13 14:03:07 UTC
        assertEquals(listOf("13 Oct 14:03"), ReportSharing.rowTimes(listOf(t), utc))
        assertEquals(listOf("13 Oct 14:04", "13 Oct 14:03"), ReportSharing.rowTimes(listOf(t + 60_000L, t), utc))
        // The same minute: the second.
        assertEquals(listOf("13 Oct 14:03:27", "13 Oct 14:03:07", "13 Oct 14:02"), ReportSharing.rowTimes(listOf(t + 20_000L, t, t - 60_000L), utc))
        // The same second: then a number.
        assertEquals(listOf("13 Oct 14:03:07", "13 Oct 14:03:07 (2)", "13 Oct 14:03:07 (3)"), ReportSharing.rowTimes(listOf(t + 500L, t + 1L, t), utc))
        // Twenty reports in one second still read twenty ways.
        val many = ReportSharing.rowTimes((0 until 20).map { t + 999L - it }, utc)
        assertEquals(20, many.toSet().size)
    }

    @Test
    fun theCardNamesMemoryForMemoryReportsOnly() {
        assertTrue(isMemory(CrashReport.Kind.OUT_OF_MEMORY))
        assertTrue(isMemory(CrashReport.Kind.LOW_MEMORY_KILL))
        for (k in listOf(CrashReport.Kind.CRASH, CrashReport.Kind.NATIVE, CrashReport.Kind.ANR, CrashReport.Kind.FREEZE)) assertFalse(k.name, isMemory(k))
    }
}
