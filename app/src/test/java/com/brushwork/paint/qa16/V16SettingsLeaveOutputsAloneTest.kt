package com.brushwork.paint.qa16

import android.content.Context
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.6 final QA: the app-wide v1.6 settings are view and gesture preferences only. With every one
 * of them away from its default (increments on, dark checker, fast adjustment preview off, larger
 * curve handles) a v1.5 project — plain and with adjustment layers — renders, exports (PNG, SVG,
 * PDF) and re-saves byte for byte what it does with the defaults (host independent: both runs
 * on this host).
 */
@RunWith(RobolectricTestRunner::class)
class V16SettingsLeaveOutputsAloneTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val repo by lazy { ProjectRepository(app) }
    private val names = listOf("flat.png", "flat-white.png", "thumb-256.png", "tiles.png") + V15Fixtures.exports.keys

    /** Every output and the re-saved files of v1.5 project [id] under the stored preferences. */
    private fun outputs(id: String): Pair<Map<String, ByteArray>, Map<String, ByteArray>> {
        val dir = V15Fixtures.install(app, id)
        val doc = runBlocking { repo.load(id) }
        val c = EditorController(app, doc, Smoke.newScope(), AppSettings(app))
        assertTrue(Smoke.pumpUntil(30_000) { c.busyMessage == null && !c.vectors.isRendering })
        val out = names.associateWith { V15Fixtures.output(c, it) }
        runBlocking { repo.save(doc, c.compositor.renderThumbnail(512)) }
        val files = dir.listFiles()!!.associate { it.name to it.readBytes() }
        return out to files
    }

    @Test
    fun nonDefaultSettingsChangeNoOutput() {
        for (id in listOf(V15Fixtures.PLAIN, V15Fixtures.ADJUST)) {
            val prefs = app.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            val (defOut, defFiles) = outputs(id)
            AppSettings(app).apply {
                increments = increments.copy(enabled = true, lengthPx = 7f, angleDeg = 30f, scalePercent = 25f, sizePx = 3f, percent = 10f)
                transparencyDisplay = TransparencyDisplay.DARK_CHECKER
                fastAdjustPreview = false
                curveHandleScale = 2f
            }
            val (out, files) = outputs(id)
            for (name in names) assertArrayEquals("$id: $name", defOut.getValue(name), out.getValue(name))
            assertEquals("$id: the same files", defFiles.keys.sorted(), files.keys.sorted())
            for ((name, bytes) in defFiles) assertArrayEquals("$id: re-saved $name", bytes, files.getValue(name))
        }
    }
}
