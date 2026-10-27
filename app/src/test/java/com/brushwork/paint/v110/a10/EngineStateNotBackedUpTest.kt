package com.brushwork.paint.v110.a10

import android.content.Context
import com.brushwork.paint.AppSettings
import com.brushwork.paint.R
import com.brushwork.paint.UiPrefs
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckMarker
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.storage.engine.EngineState
import com.brushwork.paint.storage.engine.EngineStateStore
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * v1.10 A10 (design 2.10-2.12, review row 18): the engine's state on this phone (the check's
 * result, its marker and fingerprint, `engine-state.json`) lies under `noBackupFilesDir`, which
 * Auto Backup never includes, and none of it is in `AppSettings` or its preferences (the one file
 * the backup and a phone transfer carry), so a restore never moves one phone's level to another.
 */
@RunWith(RobolectricTestRunner::class)
class EngineStateNotBackedUpTest {
    @get:Rule
    val engine = EngineRule(memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe(), exits = FakeExitInfo())

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun theEngineFilesLieUnderNoBackupFilesDir() {
        val noBackup = app.noBackupFilesDir.canonicalFile
        val files = mapOf(
            "engine-check.json" to CheckStore.file(app),
            "engine-check.running" to CheckMarker.file(app),
            "engine-state.json" to EngineStateStore.file(app),
            AutoCheckRunner.FINGERPRINT_FILE to File(app.noBackupFilesDir, AutoCheckRunner.FINGERPRINT_FILE),
        )
        for ((name, f) in files) {
            assertEquals(name, f.name)
            assertEquals("$name lies directly in noBackupFilesDir", noBackup, f.canonicalFile.parentFile)
        }
        // A run writes there and nowhere else in the app's data, but for the notice's "shown once" mark in
        // UiPrefs (design 2.11: the `ranAtMillis` of the result last announced; on another phone its later
        // check is newer, so a restored mark never hides that phone's notice).
        EngineStateStore.write(app, EngineState(engineCheckPending = true))
        val before = dataFiles()
        assertEquals(AutoCheckRunner.Outcome.DONE, A10Kit.runner(app, A10Kit.Env()).runIfDue { false })
        val written = dataFiles() - before - File(app.dataDir, "shared_prefs/${UiPrefs.FILE}.xml").canonicalFile
        assertTrue("written: $written", written.isNotEmpty())
        for (f in written) assertTrue("$f is under noBackupFilesDir", f.canonicalPath.startsWith(noBackup.path + File.separator))
        assertEquals(EngineLevel.FULL, CheckStore.read(app)!!.level)
        assertFalse("the manual request is cleared", EngineStateStore.read(app).engineCheckPending)
    }

    @Test
    fun noEngineStateIsInAppSettingsOrItsPreferences() {
        val names = AppSettings::class.java.declaredMethods.map { it.name.lowercase() } + AppSettings::class.java.declaredFields.map { it.name.lowercase() }
        for (word in ENGINE_STATE_WORDS) {
            assertTrue("AppSettings has no $word: ${names.filter { word in it }}", names.none { word in it })
        }
        EngineStateStore.write(app, EngineState(engineCheckPending = true, lastExitSeen = 5L, slowSwapReads = true))
        A10Kit.runner(app, A10Kit.Env()).runIfDue { false }
        val keys = app.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).all.keys.map { it.lowercase() }
        for (word in ENGINE_STATE_WORDS) assertTrue("brushwork_settings has no $word: $keys", keys.none { word in it })
    }

    @Test
    fun theBackupRulesIncludeOnlyTheSettingsBrushesAndPalettes() {
        for (rules in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val includes = mutableListOf<String>()
            val parser = app.resources.getXml(rules)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "include") {
                    includes += parser.getAttributeValue(null, "domain") + ":" + parser.getAttributeValue(null, "path")
                }
            }
            assertTrue("$rules includes something", includes.isNotEmpty())
            assertEquals(
                "$rules: $includes",
                setOf("sharedpref:brushwork_settings.xml", "sharedpref:brushwork_brushes.xml", "sharedpref:brushwork_palettes.xml"),
                includes.toSet(),
            )
        }
    }

    /** Every file in the app's data directories, recursively. */
    private fun dataFiles(): Set<File> =
        listOf(app.dataDir, app.filesDir, app.noBackupFilesDir, app.cacheDir).flatMap { d -> d.walkTopDown().filter { it.isFile }.map { it.canonicalFile } }.toSet()

    private companion object {
        /** Words of the engine's per-phone state (lowercase). */
        val ENGINE_STATE_WORDS = listOf("enginecheck", "lastexit", "slowswap", "stepdown", "lmk", "provisional")
    }
}
