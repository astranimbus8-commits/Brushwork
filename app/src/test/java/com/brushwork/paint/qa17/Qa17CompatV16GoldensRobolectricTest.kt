package com.brushwork.paint.qa17

import android.graphics.Matrix
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.qa16.V15Fixtures
import com.brushwork.paint.qa17.Qa17CompatV16Goldens.crc
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.7 QA (compat), I5 and I13 on projects the real v1.6.0 build saved ([Qa17CompatV16Goldens]):
 * v1.7 opens them without a warning; every layer holds the pixels v1.6 loaded, the picture is
 * v1.6's to the bit, and every text, shape and vector layer (v1.5 texts of every kind, two linked
 * frames, a letter-scaled text, a Path curve, a shape with its own points, v1.5 curves and brush
 * strokes) renders to v1.6's pixels (Windows host fonts, like the v1.5 goldens). The editor
 * (controller) opening it changes nothing, and a save then rewrites `project.json` byte for byte
 * but its revision, in the same format version, with the same files.
 */
@RunWith(RobolectricTestRunner::class)
class Qa17CompatV16GoldensRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun aV16ProjectRendersAndSavesAsV16Did() = check("plain", formatVersion = 1)

    @Test
    fun aV16ProjectWithAdjustmentLayersRendersAndSavesAsV16Did() = check("adjust", formatVersion = 2)

    private fun check(name: String, formatVersion: Int) {
        val t0 = System.nanoTime()
        val id = Qa17CompatV16Goldens.install(app, name)
        val probe = Qa17CompatV16Goldens.probe(name)
        val dir = File(app.filesDir, "projects/$id")
        val json = File(dir, ProjectFormat.PROJECT_FILE).readText()
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), json)
        assertEquals("$name: v1.6 wrote format $formatVersion", formatVersion, dto.formatVersion)
        val v16Files = dir.listFiles()!!.associate { it.name to it.readBytes() }

        val repo = ProjectRepository(app)
        val doc = runBlocking { repo.load(id) }
        assertEquals("$name: no warning", emptyList<String>(), doc.loadWarnings)
        assertEquals("$name: format", formatVersion, ProjectFormat.writtenVersion(doc.layers))
        assertEquals("$name: every layer holds what v1.6 loaded", emptyList<String>(), Qa17CompatV16Goldens.storedMismatches(doc, probe))
        assertEquals("$name: the picture (I5)", probe.composite, crc(Compositor(doc) { null }.renderFlattened()))
        if (V15Fixtures.isWindows) {
            assertEquals("$name: fresh renderings (I5)", emptyList<String>(), Qa17CompatV16Goldens.freshMismatches(doc, probe))
        }
        val data = doc.layers.map { it.id to it.dataSnapshot() }

        // In the editor: nothing is redrawn, re-encoded or recorded.
        val c = Smoke.controller(app, doc)
        c.viewTransform.set(Matrix())
        Qa17CompatArtwork.settle(c)
        assertEquals("$name: no edit", 0, c.editCount)
        assertEquals("$name: the same data in the editor", data, doc.layers.map { it.id to it.dataSnapshot() })
        assertEquals("$name: the same pixels in the editor", emptyList<String>(), Qa17CompatV16Goldens.storedMismatches(doc, probe))
        assertEquals("$name: the editor's picture", probe.composite, crc(c.compositor.renderFlattened()))

        // Saved (as on leaving after an edit that changed nothing): v1.6's bytes but the revision.
        runBlocking { repo.save(doc, null) }
        val after = dir.listFiles()!!.associate { it.name to it.readBytes() }
        assertEquals("$name: the same files", v16Files.keys.sorted(), after.keys.sorted())
        for ((f, bytes) in v16Files) if (f != ProjectFormat.PROJECT_FILE) assertArrayEquals("$name: $f untouched", bytes, after.getValue(f))
        val revision = Regex("\"revision\":\\d+")
        val again = String(after.getValue(ProjectFormat.PROJECT_FILE), Charsets.UTF_8)
        assertEquals("$name: the next revision", "\"revision\":${dto.revision + 1}", revision.find(again)?.value)
        assertEquals("$name: project.json byte for byte but the revision (I13)", json.replace(revision, "\"revision\":0"), again.replace(revision, "\"revision\":0"))
        c.dispose()
        println("[qa17 compat] v1.6 $name: ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
