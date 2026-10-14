package com.brushwork.paint.v19.a7a

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.ErrorReporter
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.common.StabilityLabels19
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * v1.9 A7a step 4 (design 3.2 A7a, R9): what `ErrorReporter.handler` does with an error no job
 * caught.
 * - A release build keeps it as a handled report (quiet: Diagnostics only), cancels the open
 *   tool's touch, shows [StabilityLabels19.ERROR_TOAST] with the tool's name, and the scope goes on.
 * - Outside the editor the toast names the error's class.
 * - Debug builds and tests hand it to the thread's uncaught-exception handler (v1.8's outcome).
 * - The handler itself never throws.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorReporterReleaseTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        ShadowLog.stream = null
        CrashLog.deleteAll(app)
        CrashLog.resetRepeats()
    }

    @After
    fun reset() {
        ErrorReporter.releaseOverride = null
        ErrorReporter.editorOverride = null
        CrashLog.drain()
        CrashLog.deleteAll(app)
    }

    private fun appScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + ErrorReporter.handler)

    @Test
    fun aReleaseBuildKeepsTheErrorResetsTheToolAndGoesOn() {
        val scope = appScope()
        val c = Smoke.controller(app, Smoke.document(400, 300, whiteBottom = true), scope)
        ErrorReporter.releaseOverride = true
        ErrorReporter.editorOverride = { c }
        assertTrue(ErrorReporter.recovers)
        c.selectTool(ToolId.MAGNETIC_LASSO)
        val tool = c.currentTool as MagneticLassoTool
        assertTrue(Smoke.pumpUntil { tool.idle })
        c.pointerDown(ToolPoint(100f, 100f))
        assertNotNull("a finger is down: the loupe shows", tool.loupe)
        c.message = null

        scope.launch(CoroutineName("test job")) { throw IllegalStateException("boom") }
        Smoke.pump(100)

        assertEquals(StabilityLabels19.ERROR_TOAST.format(ToolId.MAGNETIC_LASSO.label), c.message)
        assertNull("the tool's touch is cancelled", tool.loupe)
        CrashLog.drain()
        val r = CrashLog.reports(app).single()
        assertTrue(r.id, r.id.endsWith("-crash-quiet"))
        assertEquals("Handled error: IllegalStateException in coroutine test job", r.summary)
        assertTrue(r.text, r.text.lines().contains("Fatal: no"))
        assertNull("a handled error is never on the gallery's card", CrashLog.unseen(app))

        // The scope goes on: the next job runs.
        var ran = false
        scope.launch { ran = true }
        assertTrue(ran)
        assertTrue(scope.isActive)
        scope.cancel()
    }

    @Test
    fun outsideTheEditorTheToastNamesTheError() {
        ErrorReporter.releaseOverride = true
        ErrorReporter.editorOverride = { null }
        val scope = appScope()
        scope.launch { throw IllegalArgumentException("no editor") }
        Smoke.pump(100)
        assertEquals(StabilityLabels19.ERROR_TOAST.format("IllegalArgumentException"), ShadowToast.getTextOfLatestToast())
        assertTrue(scope.isActive)
        scope.cancel()
    }

    @Test
    fun debugBuildsAndTestsHandTheErrorOn() {
        assertNull(ErrorReporter.releaseOverride)
        assertFalse("tests (and debug builds) never recover", ErrorReporter.recovers)
        val seen = AtomicReference<Throwable?>()
        val boom = IllegalStateException("boom")
        val t = Thread { ErrorReporter.handler.handleException(EmptyCoroutineContext, boom) }
        t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> seen.set(e) }
        t.start()
        t.join()
        assertSame(boom, seen.get())
        CrashLog.drain()
        assertTrue("the handler hands it on; it keeps nothing itself", CrashLog.reports(app).isEmpty())
    }

    @Test
    fun theHandlerNeverThrowsItself() {
        ErrorReporter.releaseOverride = true
        ErrorReporter.editorOverride = { throw IllegalStateException("no editor either") }
        ErrorReporter.handler.handleException(EmptyCoroutineContext, RuntimeException("first"))
        Smoke.pump(50)
        CrashLog.drain()
        assertEquals(1, CrashLog.reports(app).size)
    }
}
