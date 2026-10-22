package com.brushwork.paint.v110.lead

import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorSession
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotice
import com.brushwork.paint.engine.EngineStartup
import com.brushwork.paint.engine.EngineStat
import com.brushwork.paint.engine.EngineStats
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.gallery.GalleryNotice
import com.brushwork.paint.ui.gallery.pickNotice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * v1.10 F1 (J32, design 3.3; lead, frozen): the hooks F1 places in lead-owned files change nothing
 * before A10 fills them. The app's start leaves the engine's settings as they were; there is no
 * stored check; the stats keep nothing; an artwork opens at CLASSIC (or the test level) with
 * v1.9's budgets and numbers, and closing it puts the gallery's budget back, unless another artwork
 * opened while it was still saving; the gallery's notices are v1.9's while no engine notice is
 * raised, and one comes after a crash report and before the backup's notices. The layer storage
 * setting reads AUTOMATIC by default.
 */
@RunWith(RobolectricTestRunner::class)
class HooksAreNoOpsTest {
    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp
    private val ids = mutableListOf<String>()
    private lateinit var idle: LayerBudget

    @Before
    fun setUp() {
        F1Pixels.resetEngine()
        idle = LayerBudget.current
    }

    @After
    fun tearDown() {
        runBlocking { ids.forEach { app.repository.delete(it) } }
        F1Pixels.resetEngine()
    }

    private fun pumpUntil(what: String, done: () -> Boolean) {
        var n = 0
        while (!done() && n++ < 1000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        assertTrue("timed out waiting for $what", done())
    }

    private fun artwork(name: String): String = runBlocking { app.repository.create(NewCanvasSpec(name, 64, 48, 72f)) }.also { ids += it }

    private fun open(id: String): EditorSession = EditorSession(app, id).also { s ->
        pumpUntil("the editor to open") { s.state is EditorSession.State.Ready }
    }

    private fun close(s: EditorSession) {
        var closed = false
        s.close { closed = true }
        pumpUntil("the editor to close") { closed }
    }

    @Test
    fun theStartTheCheckAndTheStatsDoNothing() {
        val level = EngineConfig.level
        val budgets = EngineConfig.budget.budgets
        val residency = EngineConfig.residency
        EngineStartup.onAppCreate(app)
        assertEquals("the level", level, EngineConfig.level)
        assertEquals("the budgets", budgets, EngineConfig.budget.budgets)
        assertSame("the gallery's layer budget", idle, LayerBudget.current)
        assertSame("the residency", residency, EngineConfig.residency)
        assertNull("no stored check", CheckStore.read(app))
        for (s in EngineStat.entries) EngineStats.record(s, 12f)
        assertTrue("the stats keep nothing", EngineStats.summary().isEmpty())
        assertEquals("the layer storage setting's default", LayerStorageMode.AUTOMATIC, app.settings.layerStorageMode)
    }

    @Test
    fun anArtworkOpensWithV19sNumbersAndClosingPutsTheGalleryBack() {
        val id = artwork("Hooks")
        val s = open(id)
        val doc = (s.state as EditorSession.State.Ready).controller.doc
        assertEquals("the level", EngineConfig.testMode() ?: EngineLevel.CLASSIC, EngineConfig.level)
        assertEquals("v1.9's budgets", Budgets.v19(Runtime.getRuntime().maxMemory()), EngineConfig.budget.budgets)
        val session = LayerBudget.current
        assertFalse("the session's layer budget", session === idle)
        assertEquals("v1.9's layer cap", LayerBudgetV19.maxLayers(doc), session.maxLayers(doc))
        assertEquals("v1.9's import side", LayerBudgetV19.importMaxSide(4096, 3072), session.importMaxSide(4096, 3072))
        assertEquals("v1.9's canvas check", LayerBudgetV19.canvasCheck(4096, 3072), session.canvasCheck(4096, 3072))
        assertEquals("v1.9's capacity", LayerBudgetV19.capacity(4096, 3072), session.capacity(4096, 3072))
        close(s)
        pumpUntil("the engine session to close") { LayerBudget.current === idle }
    }

    @Test
    fun anArtworkOpenedWhileAnotherSavesKeepsItsSession() {
        val first = open(artwork("First"))
        var closed = false
        first.close { closed = true }
        val second = EditorSession(app, artwork("Second"))
        val opened = LayerBudget.current
        pumpUntil("the first editor to close and the second to open") { closed && second.state is EditorSession.State.Ready }
        assertSame("the second artwork keeps its engine session", opened, LayerBudget.current)
        close(second)
        pumpUntil("the engine session to close") { LayerBudget.current === idle }
    }

    @Test
    fun theGalleryNoticesAreV19sUntilAnEngineNotice() {
        val crash = CrashReport("r1", 1_000L, CrashReport.Kind.CRASH, "IllegalStateException", "text")
        val engine = EngineNotice.CheckChose(EngineLevel.CLASSIC, "low memory")
        val statuses = listOf(BackupStatus.NotSet, BackupStatus.Unavailable, BackupStatus.Running(1, 4), BackupStatus.Ok(5L, "Brushwork", 1L))
        for (b in statuses) {
            assertEquals("no engine notice: v1.9's ($b)", pickNotice(null, b, 0L, 1L), pickNotice(null, b, 0L, 1L, null))
            assertEquals("a crash before the engine ($b)", GalleryNotice.Crash(crash), pickNotice(crash, b, 0L, 1L, engine))
            assertEquals("the engine before the backup ($b)", GalleryNotice.Engine(engine), pickNotice(null, b, 0L, 1L, engine))
        }
    }
}
