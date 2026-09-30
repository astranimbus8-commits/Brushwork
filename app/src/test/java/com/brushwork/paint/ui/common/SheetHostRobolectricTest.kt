package com.brushwork.paint.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.pillTitles
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.smoke.SmokeUi.sheetTitles
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * [SheetHost] on its own: BwSheets register with the host provided by [LocalSheetHost] and are
 * drawn by it in the same window; the newest is shown, the others (and a minimized one) keep
 * their state; the host always uses a sheet's latest arguments; closing, Back ([dismissTop])
 * and the pill; without a host, or from another window, BwSheet is a modal sheet.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.common.sheethostsandbox"])
class SheetHostRobolectricTest {

    @Test
    fun sheetsAreHostedStackedMinimizedAndRestored() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var host: SheetHostState? = null
        var showA by mutableStateOf(false)
        var showB by mutableStateOf(false)
        var nestedInA by mutableStateOf(false)
        var titleA by mutableStateOf("Alpha")
        var fromDialog by mutableStateOf(false)
        var withoutHost by mutableStateOf(false)
        var dismissedA = 0
        var dismissedB = 0
        activity.setContent {
            BrushworkTheme {
                val h = rememberSheetHostState().also { host = it }
                CompositionLocalProvider(LocalSheetHost provides h) {
                    Box(Modifier.fillMaxSize()) {
                        SheetHost(h, bottomInset = 56.dp)
                        SheetPill(h, Modifier.align(Alignment.BottomCenter))
                    }
                    if (showA) {
                        BwSheet(title = titleA, onDismiss = { dismissedA++; showA = false }) {
                            var taps by remember { mutableIntStateOf(0) }
                            Text("Alpha taps $taps")
                            TextButton(onClick = { taps++ }) { Text("Tap alpha") }
                            if (nestedInA) {
                                BwSheet(title = "Nested", onDismiss = { nestedInA = false }) { Text("Nested body") }
                            }
                        }
                    }
                    if (showB) {
                        BwSheet(title = "Beta", onDismiss = { dismissedB++; showB = false }, showClose = false) { Text("Beta body") }
                    }
                    if (fromDialog) {
                        Dialog(onDismissRequest = { fromDialog = false }) {
                            BwSheet(title = "From a dialog", onDismiss = { fromDialog = false }) { Text("Dialog sheet body") }
                        }
                    }
                }
                if (withoutHost) {
                    CompositionLocalProvider(LocalSheetHost provides null) {
                        BwSheet(title = "Modal", onDismiss = { withoutHost = false }) { Text("Modal body") }
                    }
                }
            }
        }
        settle()
        val h = requireNotNull(host)
        assertTrue(h.isEmpty)
        assertFalse(h.hasExpanded)

        // One sheet: drawn by the host in the activity's window.
        showA = true
        settle()
        assertEquals("no extra window", 1, SmokeUi.windows().size)
        assertEquals(listOf("Alpha"), sheetTitles())
        assertTrue(h.hasExpanded)
        assertEquals("Alpha", h.topTitle)
        click("Tap alpha", exact = true)
        click("Tap alpha", exact = true)
        assertTrue(has("Alpha taps 2", exact = true))

        // The host follows the call's latest arguments.
        titleA = "Alpha 2"
        settle()
        assertEquals(listOf("Alpha 2"), sheetTitles())

        // A second sheet goes on top; the first keeps its state underneath.
        showB = true
        settle()
        assertEquals(listOf("Beta"), sheetTitles())
        assertFalse("the lower sheet is hidden", has("Alpha taps 2", exact = true))
        assertEquals(2, h.entries.size)

        // Minimized: a pill with the top one's title, no ✕ for a sheet without one.
        h.minimize()
        settle()
        assertEquals(emptyList<String>(), sheetTitles())
        assertEquals(listOf("Beta"), pillTitles())
        assertFalse(has("Close Beta"))
        assertFalse(h.hasExpanded)
        click("Show Beta")
        assertEquals(listOf("Beta"), sheetTitles())
        assertFalse(h.minimized)

        // Back (dismissTop) asks the top sheet to close; the one below comes back as it was.
        h.dismissTop()
        settle()
        assertEquals(1, dismissedB)
        assertEquals(0, dismissedA)
        assertEquals(listOf("Alpha 2"), sheetTitles())
        assertTrue("state kept under the other sheet", has("Alpha taps 2", exact = true))

        // Minimized and restored: still the same state.
        h.minimize()
        settle()
        assertFalse(has("Alpha taps 2", exact = true))
        assertEquals(listOf("Alpha 2"), pillTitles())
        h.restore()
        settle()
        assertTrue("state kept while minimized", has("Alpha taps 2", exact = true))

        // A sheet opened from inside a sheet's content stacks on top of it.
        nestedInA = true
        settle()
        assertEquals(listOf("Nested"), sheetTitles())
        click("Close", exact = true)
        assertEquals(listOf("Alpha 2"), sheetTitles())

        // A newly opened sheet is shown even when the others were minimized.
        h.minimize()
        settle()
        showB = true
        settle()
        assertEquals(listOf("Beta"), sheetTitles())
        assertFalse(h.minimized)
        showB = false
        settle()

        // The pill's ✕ closes a sheet that has one; with none left, nothing is minimized.
        h.minimize()
        settle()
        click("Close Alpha 2", exact = true)
        assertEquals(1, dismissedA)
        assertTrue(h.isEmpty)
        assertFalse(h.minimized)
        assertEquals(emptyList<String>(), pillTitles())
        assertNull(h.topTitle)

        // From another window (a dialog), a sheet can't be drawn by this window's host: modal.
        fromDialog = true
        settle()
        assertTrue("the dialog and its modal sheet: ${SmokeUi.windows().size} windows", SmokeUi.windows().size >= 3)
        assertTrue(h.isEmpty)
        assertTrue(has("Dialog sheet body", exact = true))
        fromDialog = false
        settle()
        assertEquals(1, SmokeUi.windows().size)

        // Without a host (the gallery): the modal bottom sheet, its own window.
        withoutHost = true
        settle(20, 50)
        assertEquals(2, SmokeUi.windows().size)
        assertTrue(has("Modal body", exact = true))
        assertTrue(h.isEmpty)
        withoutHost = false
        settle(20, 50)
        assertEquals(1, SmokeUi.windows().size)
        assertTrue(Smoke.scopeErrors.isEmpty())
    }
}
