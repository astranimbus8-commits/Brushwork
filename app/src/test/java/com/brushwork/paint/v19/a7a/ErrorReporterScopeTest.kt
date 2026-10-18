package com.brushwork.paint.v19.a7a

import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.ErrorReporter
import com.brushwork.paint.diagnostics.rememberSupervisedScope
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.StabilityLabels19
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v1.9 A7a review (design 2.4.13 and 3.2 A7a, R9): a release build goes on after an error only
 * where its scope survives it.
 * - A scope that is no supervisor is ended by its job's error before the handler sees it (and so
 *   is the scope above it). A plain `rememberCoroutineScope` is such a scope, a plain child of the
 *   window's Recomposer: going on there would leave a screen that never draws or takes a touch
 *   again, so even a release build hands the error on (the app stops with a report, v1.8's
 *   outcome). Shown with a real composition: its Recomposer shuts down, also when the job throws
 *   as it unwinds after the composable has left.
 * - A supervisor scope (the app, editor and font scopes) stays: its job's error is kept, said and
 *   survived, a nested job's too; and a supervisor scope closed by its owner (the editor closing)
 *   is never ended by a job that throws as it unwinds: that error is kept and said too.
 * - Gate 2: the composables' scope, [rememberSupervisedScope], is such a supervisor: its job's
 *   error is kept and said, its other jobs and the Recomposer go on, and it ends when the
 *   composable leaves.
 *
 * The jobs run on a thread of their own whose uncaught-exception handler stands in for the app's.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorReporterScopeTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private val handedOn = AtomicReference<Throwable?>()
    private val handed = CountDownLatch(1)
    private lateinit var thread: ExecutorCoroutineDispatcher

    @Before
    fun setUp() {
        ShadowLog.stream = null
        CrashLog.deleteAll(app)
        CrashLog.resetRepeats()
        ErrorReporter.releaseOverride = true
        ErrorReporter.editorOverride = { null }
        thread = Executors.newSingleThreadExecutor { r ->
            Thread(r, "a7a-scope-test").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                    handedOn.set(e)
                    handed.countDown()
                }
            }
        }.asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        ErrorReporter.releaseOverride = null
        ErrorReporter.editorOverride = null
        thread.close()
        Smoke.pump(50)
        CrashLog.drain()
        CrashLog.deleteAll(app)
    }

    @Test
    fun anErrorThatEndedItsScopeIsHandedOnEvenInARelease() {
        assertTrue(ErrorReporter.recovers)
        val above = Job() // the window's Recomposer, say
        val scope = CoroutineScope(Job(above) + thread + ErrorReporter.handler)
        val boom = IllegalStateException("a composable's job")
        scope.launch { throw boom }
        assertTrue("handed on", handed.await(5, TimeUnit.SECONDS))
        assertSame(boom, handedOn.get())
        assertFalse("the scope ended", scope.isActive)
        assertTrue("and the one above it", above.isCancelled)
        Smoke.pump(50)
        CrashLog.drain()
        assertTrue("kept by the uncaught handler (fatal), not as a handled error", CrashLog.reports(app).isEmpty())
        assertNull("nothing said: the app stops", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aComposablesScopeIsNoSupervisorItsErrorIsHandedOnAndTheRecomposerIsGone() {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(thread + clock)
        val composition = Composition(UnitApplier, recomposer)
        val scope = AtomicReference<CoroutineScope?>()
        try {
            runBlocking {
                withContext(thread) {
                    composition.setContent {
                        val s = rememberCoroutineScope { ErrorReporter.handler }
                        scope.set(s)
                    }
                }
            }
            assertNotNull("composed", scope.get())
            assertTrue(recomposer.currentState.value > Recomposer.State.ShuttingDown)

            val boom = IllegalStateException("a composable's job")
            scope.get()!!.launch { throw boom }
            assertTrue("handed on", handed.await(5, TimeUnit.SECONDS))
            assertSame(boom, handedOn.get())
            // Why: the error has cancelled the composable's scope and the Recomposer above it.
            val deadline = System.currentTimeMillis() + 5_000
            while (recomposer.currentState.value > Recomposer.State.ShuttingDown && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("the Recomposer shuts down: ${recomposer.currentState.value}", recomposer.currentState.value <= Recomposer.State.ShuttingDown)
            CrashLog.drain()
            assertTrue(CrashLog.reports(app).isEmpty())
        } finally {
            runCatching { runBlocking { withContext(thread) { composition.dispose() } } }
            recomposer.cancel()
        }
    }

    @Test
    fun aComposablesJobThatThrowsAfterTheComposableLeftIsHandedOnItStillEndsTheRecomposer() {
        val recomposer = Recomposer(thread + BroadcastFrameClock())
        val composition = Composition(UnitApplier, recomposer)
        val scope = AtomicReference<CoroutineScope?>()
        val waiting = CountDownLatch(1)
        try {
            runBlocking {
                withContext(thread) {
                    composition.setContent {
                        val s = rememberCoroutineScope { ErrorReporter.handler }
                        scope.set(s)
                    }
                }
            }
            scope.get()!!.launch {
                try {
                    waiting.countDown()
                    awaitCancellation()
                } finally {
                    throw IllegalStateException("as the composable left")
                }
            }
            assertTrue("running", waiting.await(5, TimeUnit.SECONDS))
            // The composable leaves: its scope is cancelled, the job throws as it unwinds.
            runBlocking { withContext(thread) { composition.dispose() } }
            assertTrue("handed on", handed.await(5, TimeUnit.SECONDS))
            assertTrue("${handedOn.get()}", handedOn.get() is IllegalStateException)
            // Why: the error still goes up from the cancelled scope to the Recomposer.
            val deadline = System.currentTimeMillis() + 5_000
            while (recomposer.currentState.value > Recomposer.State.ShuttingDown && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("the Recomposer shuts down: ${recomposer.currentState.value}", recomposer.currentState.value <= Recomposer.State.ShuttingDown)
        } finally {
            recomposer.cancel()
        }
    }

    @Test
    fun aComposablesSupervisedScopeKeepsItsJobsErrorItsOtherJobsAndTheRecomposerGoOn() {
        val recomposer = Recomposer(thread + BroadcastFrameClock())
        val composition = Composition(UnitApplier, recomposer)
        val scope = AtomicReference<CoroutineScope?>()
        try {
            runBlocking {
                withContext(thread) {
                    composition.setContent {
                        val s = rememberSupervisedScope()
                        scope.set(s)
                    }
                }
            }
            val s = requireNotNull(scope.get()) { "composed" }
            val waiting = CountDownLatch(1)
            val sibling = s.launch {
                waiting.countDown()
                awaitCancellation()
            }
            assertTrue("the other job runs", waiting.await(5, TimeUnit.SECONDS))
            val failed = s.launch { throw IllegalStateException("a composable's job") }
            runBlocking { failed.join() }
            assertTrue(failed.isCancelled)
            assertNull("nothing handed on: ${handedOn.get()}", handedOn.get())
            assertTrue("the other job goes on", sibling.isActive)
            assertTrue("the scope stays", s.isActive)
            assertTrue("the Recomposer goes on: ${recomposer.currentState.value}", recomposer.currentState.value > Recomposer.State.ShuttingDown)
            var ran = false
            runBlocking { s.launch { ran = true }.join() }
            assertTrue("and runs the next job", ran)
            Smoke.pump(100)
            assertEquals(StabilityLabels19.ERROR_TOAST.format("IllegalStateException"), ShadowToast.getTextOfLatestToast())
            CrashLog.drain()
            assertEquals(listOf("Handled error: IllegalStateException in coroutine"), CrashLog.reports(app).map { it.summary })
            // The composable leaves: its scope ends, and its jobs with it.
            runBlocking { withContext(thread) { composition.dispose() } }
            runBlocking { sibling.join() }
            assertTrue("the other job is cancelled", sibling.isCancelled)
            assertFalse("the scope ended with the composable", s.isActive)
            assertNull("still nothing handed on: ${handedOn.get()}", handedOn.get())
        } finally {
            runCatching { runBlocking { withContext(thread) { composition.dispose() } } }
            recomposer.cancel()
        }
    }

    @Test
    fun aJobThatThrowsAfterItsSupervisorScopeWasClosedIsKeptSaidAndSurvived() {
        val scope = CoroutineScope(SupervisorJob() + thread + ErrorReporter.handler)
        val waiting = CountDownLatch(1)
        val job = scope.launch {
            try {
                waiting.countDown()
                awaitCancellation()
            } finally {
                throw IllegalStateException("as the editor closed")
            }
        }
        assertTrue("running", waiting.await(5, TimeUnit.SECONDS))
        scope.cancel() // the editor closes (EditorSession), the job throws as it unwinds
        runBlocking { job.join() }
        assertNull("nothing handed on: ${handedOn.get()}", handedOn.get())
        Smoke.pump(100)
        assertEquals(StabilityLabels19.ERROR_TOAST.format("IllegalStateException"), ShadowToast.getTextOfLatestToast())
        CrashLog.drain()
        assertEquals(listOf("Handled error: IllegalStateException in coroutine"), CrashLog.reports(app).map { it.summary })
    }

    @Test
    fun aSupervisorScopeStaysItsJobsErrorIsKeptSaidAndSurvived() {
        val scope = CoroutineScope(SupervisorJob() + thread + ErrorReporter.handler)
        // A job's error, then a nested job's (it ends its own job first, which the scope survives).
        val first = scope.launch { throw IllegalStateException("a job") }
        val second = scope.launch { launch { throw IllegalArgumentException("a nested job") } }
        runBlocking {
            first.join()
            second.join()
        }
        assertTrue("the scope stays", scope.isActive)
        var ran = false
        runBlocking { scope.launch { ran = true }.join() }
        assertTrue("and runs the next job", ran)
        assertNull("nothing handed on", handedOn.get())
        Smoke.pump(100)
        assertEquals(StabilityLabels19.ERROR_TOAST.format("IllegalArgumentException"), ShadowToast.getTextOfLatestToast())
        CrashLog.drain()
        val summaries = CrashLog.reports(app).map { it.summary }.sorted()
        assertEquals(
            listOf("Handled error: IllegalArgumentException in coroutine", "Handled error: IllegalStateException in coroutine"),
            summaries,
        )
        assertTrue(CrashLog.reports(app).all { it.id.endsWith("-crash-quiet") })
        scope.cancel()
    }

    @Test
    fun aRootJobOrNoJobIsRecoveredFrom() {
        assertFalse(ErrorReporter.tookItsScopeDown(kotlin.coroutines.EmptyCoroutineContext))
        assertFalse(ErrorReporter.tookItsScopeDown(Job()))
        val supervisor = SupervisorJob()
        assertFalse("a running supervisor's job", ErrorReporter.tookItsScopeDown(Job(supervisor)))
        supervisor.cancel()
    }

    /** A composition that makes no nodes. */
    private object UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
