package com.brushwork.paint.v19.a8

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.R
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.storage.backup.BackupSettings
import com.brushwork.paint.storage.backup.BackupStateStore
import com.brushwork.paint.ui.color.PaletteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * v1.9 A8 (R16 (b)): Android's Auto Backup keeps the settings, the brush presets and the palettes
 * (the three preference files the app writes them to) and nothing else: no artworks, fonts,
 * caches or crash reports (`diagnostics/` and the "diagnostics" preferences), on Android 8 to 11
 * (`backup_rules.xml`) and on 12 and newer, in the cloud and to a new phone
 * (`data_extraction_rules.xml`). The manifest names both and sets `hasFragileUserData`, and the
 * backup folder's state lies under `noBackupFilesDir`.
 */
@RunWith(RobolectricTestRunner::class)
class AutoBackupRulesTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    /** The three preference files, as the code that writes them names them. */
    private val kept = setOf(
        "sharedpref:${BackupSettings.SETTINGS_PREFS}.xml",
        "sharedpref:${BrushPresetStore.PREFS_NAME}.xml",
        "sharedpref:${PaletteStore.PREFS_NAME}.xml",
    )

    /** The rules of [xml] as "<section>/<include|exclude> <domain>:<path>". */
    private fun rules(xml: Int): List<String> {
        val parser = app.resources.getXml(xml)
        val out = ArrayList<String>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "include", "exclude" -> {
                    val domain = parser.getAttributeValue(null, "domain") ?: parser.getAttributeValue("http://schemas.android.com/apk/res/android", "domain")
                    val path = parser.getAttributeValue(null, "path")
                    out += "$section/${parser.name} $domain:$path"
                }
            }
        }
        return out
    }

    @Test
    fun androidEightToElevenKeepTheSettingsBrushesAndPalettesOnly() {
        val r = rules(R.xml.backup_rules)
        assertEquals(kept.map { "/include $it" }.toSet(), r.toSet())
        assertEquals("no duplicates", r.size, r.toSet().size)
    }

    @Test
    fun androidTwelveAndNewerKeepTheSameInTheCloudAndToANewPhone() {
        val r = rules(R.xml.data_extraction_rules)
        val expected = listOf("cloud-backup", "device-transfer").flatMap { s -> kept.map { "$s/include $it" } }.toSet()
        assertEquals(expected, r.toSet())
    }

    @Test
    fun nothingElseIsIncluded() {
        for (xml in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            for (rule in rules(xml)) {
                assertTrue("only preference files: $rule", rule.substringAfter(' ').startsWith("sharedpref:"))
                assertTrue("never the crash reports: $rule", !rule.contains("diagnostics"))
            }
        }
    }

    @Test
    fun theManifestNamesTheRulesAndHasFragileUserData() {
        val manifest = manifestFile()
        assertNotNull("app/src/main/AndroidManifest.xml", manifest)
        val text = manifest!!.readText()
        val application = text.substringAfter("<application").substringBefore(">")
        assertTrue(application, application.contains("android:allowBackup=\"true\""))
        assertTrue(application, application.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(application, application.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(application, application.contains("android:hasFragileUserData=\"true\""))
    }

    @Test
    fun theBackupStateLiesUnderNoBackupFilesDir() {
        assertEquals(app.noBackupFilesDir.absoluteFile, BackupStateStore.file(app).parentFile!!.absoluteFile)
    }

    private fun manifestFile(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null).absoluteFile
        while (dir != null) {
            for (c in listOf(File(dir, "src/main/AndroidManifest.xml"), File(dir, "app/src/main/AndroidManifest.xml"))) {
                if (c.isFile) return c
            }
            dir = dir.parentFile
        }
        return null
    }
}
