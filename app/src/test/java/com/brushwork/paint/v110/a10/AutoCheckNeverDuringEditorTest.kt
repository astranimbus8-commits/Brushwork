package com.brushwork.paint.v110.a10

import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckMarker
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

/**
 * v1.10 A10 (design 2.10 "When it runs"): the check never measures while an editor is open, not
 * even M1: it would compete with painting for memory and time. Through a fake editor flag, and
 * through the app's own runner ([AutoCheckRunner.of]) with a real editor session open.
 */
@RunWith(RobolectricTestRunner::class)
class AutoCheckNeverDuringEditorTest {
    private val memory = FakeMemoryProbe(MemoryPreset.T606)
    private val storage = FakeStorageProbe()

    @get:Rule
    val engine = EngineRule(memory = memory, storage = storage, exits = FakeExitInfo())

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun withAnEditorOpenNothingIsMeasured() {
        // The rule opened its session with budgets read from the probe (Budgets.compute, A7): count from here.
        val snapshots = memory.snapshots
        val env = A10Kit.Env()
        env.editorOpen = true
        val steps = A10Kit.Steps(app)
        val runner = A10Kit.runner(app, env, steps)
        assertEquals(AutoCheckRunner.Outcome.STOPPED, runner.runIfDue { false })
        assertEquals(AutoCheckRunner.Outcome.STOPPED, runner.launchIfDue().get(10, TimeUnit.SECONDS))
        assertEquals("not even M1", 0, steps.calls.values.sum())
        assertEquals(snapshots, memory.snapshots)
        assertEquals(0, memory.probeCalls)
        assertTrue(storage.dirs.isEmpty())
        assertNull(CheckStore.read(app))
        assertFalse(CheckMarker.file(app).exists())
        assertFalse(runner.inProgress)
    }

    @Test
    fun theAppsRunnerWaitsForTheRealEditorToClose() {
        val brush = app as BrushworkApp
        val runner = AutoCheckRunner.of(brush)
        assertTrue("one runner per app", runner === AutoCheckRunner.of(brush))
        val session = brush.openEditor("00000000-0000-0000-0000-0000000a1000")
        try {
            assertTrue(A10Kit.pumpUntil { session.state !is com.brushwork.paint.EditorSession.State.Loading })
            val before = memory.snapshots
            assertEquals(AutoCheckRunner.Outcome.STOPPED, runner.runIfDue { false })
            assertEquals("not even M1", before, memory.snapshots)
            assertNull(CheckStore.read(app))
        } finally {
            var closed = false
            brush.closeEditor { closed = true }
            assertTrue(A10Kit.pumpUntil { closed })
        }
        // Closed: the same runner measures (M3 and M5 here are the real benches, so only the outcome is asserted).
        assertEquals(AutoCheckRunner.Outcome.DONE, runner.runIfDue { false })
        assertTrue(memory.probeCalls > 0)
        assertTrue(CheckStore.read(app) != null)
    }
}
