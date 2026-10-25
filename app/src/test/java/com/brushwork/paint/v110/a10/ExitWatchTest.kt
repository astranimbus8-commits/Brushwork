package com.brushwork.paint.v110.a10

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.provider.Settings
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotices
import com.brushwork.paint.engine.ExitWatch
import com.brushwork.paint.engine.ExitWatch.Closing
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.budget.AndroidExitInfo
import com.brushwork.paint.engine.budget.ExitRecord
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.storage.engine.EngineStateStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.File
import java.util.concurrent.Executor

/**
 * v1.10 A10 (design 2.12, step 5): `ExitWatch` finds the closings of the app while an editor was
 * open in the foreground and counts them.
 *
 * - A SIGKILL in the foreground on a phone without low-memory kill reports is a probable closing:
 *   one steps nothing down, a second within 30 days steps down (one older than 30 days is
 *   forgotten). A `REASON_LOW_MEMORY` exit is sure and counts at once.
 * - A marker in the background, an exit before the editor came to the foreground, a background
 *   SIGKILL or one on a phone that reports low-memory kills count nothing; an exit is read once
 *   (`lastExitSeen`).
 * - Below API 30 the marker's boot count decides: the same boot with no crash report since is a
 *   probable closing; another boot count (or none) is ignored; a crash report explains the exit.
 * - A restored phone (no `engine-state.json`, no marker) misreads nothing.
 * - The marker follows the editor (written, its foreground time kept until the state changes,
 *   deleted at close, taken once at start), and `AndroidExitInfo` maps the system's records.
 */
@RunWith(RobolectricTestRunner::class)
class ExitWatchTest {
    private val exits = FakeExitInfo(lmkSupported = false)

    @get:Rule
    val engine = EngineRule(memory = FakeMemoryProbe(MemoryPreset.T606), exits = exits)

    private val app get() = RuntimeEnvironment.getApplication()
    private var mode = LayerStorageMode.AUTOMATIC
    private lateinit var writer: Executor

    private val t0 = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        writer = ExitWatch.writer
        ExitWatch.writer = Executor { it.run() }
        clean()
    }

    @After
    fun tearDown() {
        ExitWatch.writer = writer
        clean()
    }

    private fun clean() {
        EngineNotices.current = null
        CheckStore.file(app).delete()
        EngineStateStore.file(app).delete()
        ExitWatch.markerFile(app).delete()
        File(app.noBackupFilesDir, ExitWatch.CLOSINGS_FILE).delete()
        File(app.filesDir, "diagnostics/reports").deleteRecursively()
    }

    private fun env(api: Int = 34, bootCount: Int = 5) = ExitWatch.Env(
        api = api, bootCount = bootCount, exits = exits, mode = { mode }, setMode = { mode = it }, onMain = { it.run() },
    )

    private fun marker(since: Long, foreground: Boolean = true, bootCount: Int = 5) =
        ExitWatch.Marker(EngineLevel.FULL, 12, 1080, 2400, foreground, bootCount, since, since + 500)

    private fun kill(at: Long, foreground: Boolean = true) = ExitRecord(at, lowMemory = false, signaled9 = true, foreground = foreground).also { exits.records += it }

    private fun lowMemory(at: Long) = ExitRecord(at, lowMemory = true, signaled9 = true, foreground = true).also { exits.records += it }

    private fun stored(): EngineCheckResult = CheckStore.readStored(app)!!

    private fun storeCheck() = CheckStore.write(app, A10Kit.result(EngineLevel.FULL, ranAt = t0))

    @Test
    fun signaled9WithoutLowMemoryReportsCountsFromTheSecond() {
        storeCheck()
        kill(t0 + 5_000)
        assertEquals("one SIGKILL in the foreground: probable", Closing.PROBABLE, ExitWatch.atStart(app, marker(t0 + 1_000), env()))
        assertEquals("one: no step-down", 0, stored().stepDown)
        assertTrue(stored().lmkDeathsAt.isEmpty())
        assertEquals("the exit is seen", t0 + 5_000, EngineStateStore.read(app).lastExitSeen)
        assertEquals("seen once", Closing.NONE, ExitWatch.atStart(app, marker(t0 + 1_000), env()))

        kill(t0 + 20_000)
        assertEquals(Closing.PROBABLE, ExitWatch.atStart(app, marker(t0 + 10_000), env()))
        assertEquals("two: step-down", 1, stored().stepDown)
        assertEquals("the second is the closing counted", listOf(t0 + 20_000), stored().lmkDeathsAt)
        assertEquals("the result's time stays, so the check re-runs at the gallery", t0, stored().ranAtMillis)
        assertEquals("a first step-down keeps the level", EngineLevel.FULL, stored().level)
        assertNull("no notice for a first step-down", EngineNotices.current)
    }

    @Test
    fun aProbableClosingOlderThan30DaysIsForgotten() {
        storeCheck()
        kill(t0 + 5_000)
        ExitWatch.atStart(app, marker(t0 + 1_000), env())
        val later = t0 + 31 * day
        kill(later)
        assertEquals(Closing.PROBABLE, ExitWatch.atStart(app, marker(later - 1_000), env()))
        assertEquals("the first fell out of the window: still free", 0, stored().stepDown)
        kill(later + day)
        ExitWatch.atStart(app, marker(later + day - 1_000), env())
        assertEquals("two within 30 days", 1, stored().stepDown)
    }

    @Test
    fun aLowMemoryExitIsSureAndCountsAtOnce() {
        storeCheck()
        exits.lmkSupported = true
        lowMemory(t0 + 5_000)
        assertEquals(Closing.SURE, ExitWatch.atStart(app, marker(t0 + 1_000), env()))
        assertEquals(1, stored().stepDown)
        assertEquals(listOf(t0 + 5_000), stored().lmkDeathsAt)

        kill(t0 + 9_000)
        assertEquals("a SIGKILL where low-memory kills are reported: nothing", Closing.NONE, ExitWatch.atStart(app, marker(t0 + 8_000), env()))
        assertEquals(1, stored().stepDown)
    }

    @Test
    fun whatCountsNothing() {
        storeCheck()
        lowMemory(t0 + 5_000)
        assertEquals("a marker in the background", Closing.NONE, ExitWatch.atStart(app, marker(t0 + 1_000, foreground = false), env()))
        lowMemory(t0 + 9_000)
        assertEquals("an exit before the editor came to the foreground", Closing.NONE, ExitWatch.atStart(app, marker(t0 + 9_001), env()))
        kill(t0 + 12_000, foreground = false)
        assertEquals("a SIGKILL in the background", Closing.NONE, ExitWatch.atStart(app, marker(t0 + 11_000), env()))
        lowMemory(t0 + 15_000)
        assertEquals("no marker: no editor was open", Closing.NONE, ExitWatch.atStart(app, null, env()))
        assertEquals(0, stored().stepDown)
        assertEquals("every exit seen", t0 + 15_000, EngineStateStore.read(app).lastExitSeen)
        assertEquals("no probable closing kept", false, File(app.noBackupFilesDir, ExitWatch.CLOSINGS_FILE).exists())
    }

    @Test
    fun belowApi30TheBootCountDecides() {
        storeCheck()
        val api29 = env(api = 29, bootCount = 5)
        assertEquals("another boot count: a reboot or a battery death", Closing.NONE, ExitWatch.atStart(app, marker(t0, bootCount = 4), api29))
        assertEquals("no boot count", Closing.NONE, ExitWatch.atStart(app, marker(t0, bootCount = -1), env(api = 29, bootCount = -1)))
        assertEquals("the background", Closing.NONE, ExitWatch.atStart(app, marker(t0, foreground = false), api29))

        CrashLog.write(t0 + 2_000, CrashReport.Kind.CRASH, quiet = false, "a crash")
        assertEquals("a crash since the foreground explains it", Closing.NONE, ExitWatch.atStart(app, marker(t0 + 1_000), api29))
        CrashLog.write(t0 + 4_000, CrashReport.Kind.CRASH, quiet = true, "a handled error")
        assertEquals("the same boot, no crash since: probable", Closing.PROBABLE, ExitWatch.atStart(app, marker(t0 + 3_000), api29))
        assertEquals("one: no step-down", 0, stored().stepDown)
        assertEquals(Closing.PROBABLE, ExitWatch.atStart(app, marker(t0 + 60_000), api29))
        assertEquals("two: step-down", 1, stored().stepDown)
        assertEquals("counted at the marker's last write", listOf(t0 + 60_500), stored().lmkDeathsAt)
        assertFalse("no exit records below API 30", EngineStateStore.file(app).exists())
    }

    @Test
    fun aRestoredPhoneMisreadsNothing() {
        // A restored phone: AppSettings came back, noBackupFilesDir did not (no engine-state.json,
        // no marker, no check result); the system still lists the exits of the restore itself.
        mode = LayerStorageMode.FULL
        lowMemory(t0 + 1_000)
        kill(t0 + 2_000)
        assertNull(ExitWatch.takeMarker(app))
        assertEquals(Closing.NONE, ExitWatch.atStart(app, null, env()))
        assertNull("no check result made up", CheckStore.readStored(app))
        assertEquals("the old exits are seen, never counted later", t0 + 2_000, EngineStateStore.read(app).lastExitSeen)
        assertEquals(LayerStorageMode.FULL, mode)

        // The check ran on the new phone; a marker now appears: the old exits stay seen.
        storeCheck()
        assertEquals(Closing.NONE, ExitWatch.atStart(app, marker(t0), env()))
        assertEquals(0, stored().stepDown)
    }

    @Test
    fun aClosingWithoutACheckResultCountsNothing() {
        lowMemory(t0 + 5_000)
        assertEquals("found", Closing.SURE, ExitWatch.atStart(app, marker(t0 + 1_000), env()))
        assertNull("but nothing to step down", CheckStore.readStored(app))
    }

    @Test
    fun theMarkerFollowsTheEditor() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        ExitWatch.editorClosed(app)
        assertFalse(ExitWatch.markerFile(app).exists())
        ExitWatch.editorShown(app, EngineLevel.FULL, 3, 1080, 2400, foreground = true, nowMillis = 1_000)
        ExitWatch.editorShown(app, EngineLevel.FULL, 4, 1080, 2400, foreground = true, nowMillis = 2_000)
        assertTrue("written", ExitWatch.markerFile(app).isFile)
        assertEquals(
            "the foreground time kept while the state stays",
            ExitWatch.Marker(EngineLevel.FULL, 4, 1080, 2400, foreground = true, bootCount = 7, sinceMillis = 1_000, updatedAt = 2_000),
            ExitWatch.takeMarker(app),
        )
        assertFalse("taken once", ExitWatch.markerFile(app).exists())
        assertNull(ExitWatch.takeMarker(app))

        ExitWatch.editorShown(app, EngineLevel.TILES, 4, 800, 600, foreground = true, nowMillis = 3_000)
        ExitWatch.editorShown(app, EngineLevel.TILES, 4, 800, 600, foreground = false, nowMillis = 4_000)
        val bg = ExitWatch.takeMarker(app)!!
        assertEquals("in the background since the stop", 4_000, bg.sinceMillis)
        assertFalse(bg.foreground)

        ExitWatch.editorShown(app, EngineLevel.TILES, 4, 800, 600, foreground = true, nowMillis = 5_000)
        ExitWatch.editorClosed(app)
        assertFalse("deleted when the editor closes", ExitWatch.markerFile(app).exists())

        ExitWatch.markerFile(app).writeText("{ not a marker")
        assertNull("an unreadable marker counts as none", ExitWatch.takeMarker(app))
        assertFalse(ExitWatch.markerFile(app).exists())
        assertFalse("under Robolectric the editor writes no marker unless a test asks", ExitWatch.markerRuns)
    }

    @Test
    @Config(sdk = [30])
    fun androidExitInfoMapsTheSystemsRecords() {
        val am = app.getSystemService(ActivityManager::class.java)
        fun add(reason: Int, at: Long, status: Int, importance: Int) = shadowOf(am).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(reason).setTimestamp(at).setStatus(status).setImportance(importance).setPid(4242).setProcessName(app.packageName)
                .build(),
        )
        val fg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val bg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        add(ApplicationExitInfo.REASON_LOW_MEMORY, t0 + 1_000, 0, bg)
        add(ApplicationExitInfo.REASON_SIGNALED, t0 + 2_000, 9, fg)
        add(ApplicationExitInfo.REASON_SIGNALED, t0 + 3_000, 15, fg)
        add(ApplicationExitInfo.REASON_CRASH, t0 + 4_000, 0, fg)
        AndroidExitInfo.attach(app)
        val all = AndroidExitInfo.exits(t0)
        assertEquals("newest first", listOf(t0 + 4_000, t0 + 3_000, t0 + 2_000, t0 + 1_000), all.map { it.atMillis })
        assertEquals(ExitRecord(t0 + 1_000, lowMemory = true, signaled9 = false, foreground = false), all[3])
        assertEquals(ExitRecord(t0 + 2_000, lowMemory = false, signaled9 = true, foreground = true), all[2])
        assertEquals("SIGTERM is not SIGKILL", ExitRecord(t0 + 3_000, lowMemory = false, signaled9 = false, foreground = true), all[1])
        assertEquals(ExitRecord(t0 + 4_000, lowMemory = false, signaled9 = false, foreground = true), all[0])
        assertEquals("only the newer ones", 2, AndroidExitInfo.exits(t0 + 2_000).size)
        AndroidExitInfo.lmkReportsSupported() // reads the phone's property without throwing
    }
}
