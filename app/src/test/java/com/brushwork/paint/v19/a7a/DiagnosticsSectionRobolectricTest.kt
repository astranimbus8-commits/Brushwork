package com.brushwork.paint.v19.a7a

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.diagnostics.CrashCard
import com.brushwork.paint.ui.diagnostics.DiagnosticsSection
import com.brushwork.paint.ui.diagnostics.ReportSharing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.9 A7a step 5 (design 3.2 A7a, R9; I27): the crash card and the Diagnostics list on screen,
 * at 360 dp and 392 dp, at font scale 1 and each of [Qa19Ui.FONT_SCALES]. A7a's one Robolectric
 * UI class (design 5.5), so the card's layout check of `CrashCardRobolectricTest` is a section here.
 * - The card (tag [V19Tags.CRASH_CARD]): its title and note ([StabilityLabels19.CRASH_OOM] for
 *   memory only); Copy, Share and Dismiss each at least 40 dp tall, on one line at font scale 1;
 *   Copy copies, Share opens the share sheet, Dismiss dismisses (each says so to the gallery).
 * - The Diagnostics list with three reports (two of the same minute): the count, each report's
 *   summary; no two buttons on screen read alike ("Copy report <time>", "Share report <time>");
 *   [StabilityLabels19.SHARE_ALL] shares all three; [StabilityLabels19.DELETE_REPORTS] empties it.
 * - No text overflows its box anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.v19.a7a.diagnosticssandbox"])
class DiagnosticsSectionRobolectricTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private val widths = listOf(Qa19Ui.Q360, Qa19Ui.Q392)

    @Test
    fun theCrashCardAndTheDiagnosticsList() = Qa19Ui.uiTest { h ->
        val t0 = 1_760_364_187_000L
        for (q in widths) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            for (kind in listOf(CrashReport.Kind.CRASH, CrashReport.Kind.OUT_OF_MEMORY)) h.section("the crash card ($kind) at $q, font scale $scale") {
                RuntimeEnvironment.setQualifiers(q)
                val report = CrashReport(CrashLogIds.id(t0, kind), t0, kind, "Crash: IllegalStateException", "Brushwork crash report\nKind: ${kind.name}\n")
                var copied = 0
                var shared = 0
                var dismissed = 0
                val activity = h.show(fontScale = scale) {
                    Column(Modifier.fillMaxWidth()) { CrashCard(report, onCopy = { copied++ }, onShare = { shared++ }, onDismiss = { dismissed++ }) }
                }
                val card = Qa19Ui.tagged(V19Tags.CRASH_CARD)
                assertNotNull("the card is tagged ${V19Tags.CRASH_CARD}: ${Qa19Ui.shown()}", card)
                assertTrue(Qa19Ui.has(StabilityLabels19.CRASH_TITLE, exact = true))
                assertTrue(Qa19Ui.has(StabilityLabels19.CRASH_SAVED, exact = true))
                assertEquals("memory is named for memory only", kind == CrashReport.Kind.OUT_OF_MEMORY, Qa19Ui.has(StabilityLabels19.CRASH_OOM, exact = true))
                val density = activity.resources.displayMetrics.density
                val windowWidth = activity.window.decorView.width
                assertTrue("the card within the screen: ${card!!.boundsInWindow} in $windowWidth", card.boundsInWindow.right <= windowWidth + 0.5f)
                val buttons = listOf(StabilityLabels19.COPY, StabilityLabels19.SHARE, StabilityLabels19.DISMISS).map { label ->
                    val e = Qa19Ui.find(label, exact = true) ?: throw AssertionError("no \"$label\": ${Qa19Ui.shown()}")
                    label to clickableOf(e.node)
                }
                for ((label, b) in buttons) {
                    val dp = b.boundsInWindow.height / density
                    assertTrue("\"$label\" is $dp dp tall", dp >= 40f - 0.5f)
                    assertTrue("\"$label\" inside the card", b.boundsInWindow.right <= card.boundsInWindow.right + 0.5f)
                }
                if (scale == 1f) {
                    val tops = buttons.map { it.second.boundsInWindow.top }
                    assertTrue("one line of buttons at font scale 1: $tops", tops.all { kotlin.math.abs(it - tops[0]) < 1f })
                }
                Qa19Ui.assertNoTextOverflow("the crash card at $q, font scale $scale")

                if (scale == 1f && kind == CrashReport.Kind.CRASH) {
                    Qa19Ui.click(StabilityLabels19.COPY, exact = true)
                    assertEquals(1, copied)
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertEquals(report.text, cm.primaryClip!!.getItemAt(0).text.toString())
                    Qa19Ui.click(StabilityLabels19.SHARE, exact = true)
                    assertEquals(1, shared)
                    val chooser = shadowOf(activity).nextStartedActivity
                    assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                    Qa19Ui.click(StabilityLabels19.DISMISS, exact = true)
                    assertEquals(1, dismissed)
                }
            }

            h.section("the Diagnostics list with three reports at $q, font scale $scale") {
                RuntimeEnvironment.setQualifiers(q)
                CrashLog.deleteAll(app)
                // Two of the same minute (their buttons name the second), one a day before.
                CrashLog.write(t0, CrashReport.Kind.CRASH, quiet = false, "Brushwork crash report\nSummary: Crash: IllegalStateException\nfirst\n")
                CrashLog.write(t0 + 20_000L, CrashReport.Kind.OUT_OF_MEMORY, quiet = true, "Brushwork crash report\nSummary: Out of memory: OutOfMemoryError\nsecond\n")
                CrashLog.write(t0 - 86_400_000L, CrashReport.Kind.ANR, quiet = false, "Brushwork crash report\nSummary: Not responding\nthird\n")
                val reports = CrashLog.reports(app)
                assertEquals(3, reports.size)
                val times = ReportSharing.rowTimes(reports.map { it.timeMillis })

                val activity = h.show(fontScale = scale) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { DiagnosticsSection() }
                }
                val count = StabilityLabels19.CRASH_REPORTS.format(3)
                assertTrue("the reports are read: ${Qa19Ui.shown()}", Smoke.pumpUntil(10_000) { Qa19Ui.has(count, exact = true) })
                Qa19Ui.settle()
                for (s in listOf("Crash: IllegalStateException", "Out of memory: OutOfMemoryError", "Not responding")) {
                    assertTrue("the summary \"$s\": ${Qa19Ui.shown()}", Qa19Ui.has(s, exact = true))
                }
                for (time in times) {
                    assertTrue(Qa19Ui.has(StabilityLabels19.COPY_REPORT.format(time), exact = true))
                    assertTrue(Qa19Ui.has(StabilityLabels19.SHARE_REPORT.format(time), exact = true))
                }
                assertTrue(Qa19Ui.has(StabilityLabels19.SHARE_ALL, exact = true))
                assertTrue(Qa19Ui.has(StabilityLabels19.DELETE_REPORTS, exact = true))
                assertTrue("the switch stays first", Qa19Ui.has(StabilityLabels19.SHOW_FRAME_TIMES, exact = true))
                val labels = buttonLabels()
                assertEquals("no two buttons read alike: $labels", labels.size, labels.toSet().size)
                assertEquals("each report's two, Share all, Delete all reports, the switch: $labels", 3 * 2 + 3, labels.size)
                Qa19Ui.assertNoTextOverflow("the Diagnostics list at $q, font scale $scale")

                if (scale == 1f) {
                    // One row's Copy copies that report.
                    Qa19Ui.click(StabilityLabels19.COPY_REPORT.format(times[2]), exact = true)
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertTrue(cm.primaryClip!!.getItemAt(0).text.toString().contains("third"))
                    // Share all: one share sheet with the three reports.
                    Qa19Ui.click(StabilityLabels19.SHARE_ALL, exact = true)
                    val chooser = Smoke.pumpUntil(5_000) { shadowOf(activity).peekNextStartedActivity() != null }.let { shadowOf(activity).nextStartedActivity }
                    assertNotNull("Share all opens the share sheet", chooser)
                    @Suppress("DEPRECATION")
                    val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
                    for (w in listOf("first", "second", "third")) assertTrue(text, text.contains(w))
                    // Delete all reports: the list empties at once.
                    Qa19Ui.click(StabilityLabels19.DELETE_REPORTS, exact = true)
                    assertTrue(Smoke.pumpUntil(5_000) { Qa19Ui.has(StabilityLabels19.CRASH_REPORTS.format(0), exact = true) })
                    assertFalse(Qa19Ui.has(StabilityLabels19.SHARE_ALL, exact = true))
                    assertTrue(CrashLog.reports(app).isEmpty())
                }
                CrashLog.deleteAll(app)
            }
        }
    }

    /** The clickable node [n] belongs to (itself or its nearest clickable ancestor). */
    private fun clickableOf(n: SemanticsNode): SemanticsNode {
        var c: SemanticsNode? = n
        while (c != null && c.config.getOrNull(SemanticsActions.OnClick) == null) c = c.parent
        return c ?: throw AssertionError("not clickable: ${n.config}")
    }

    /** The label of every clickable node placed on the screen shown last (its own texts and its children's). */
    private fun buttonLabels(): List<String> {
        val window = RobolectricUi.windowRoots().last()
        return RobolectricUi.elements()
            .filter { it.window === window && it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsActions.OnClick) != null }
            .map { e -> texts(e.node).joinToString(" ") }
    }

    private fun texts(n: SemanticsNode): List<String> =
        (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) + n.children.flatMap { texts(it) }

    /** Report ids as the crash log names them. */
    private object CrashLogIds {
        fun id(time: Long, kind: CrashReport.Kind) = "%013d-%s".format(time, kind.name.lowercase())
    }
}
