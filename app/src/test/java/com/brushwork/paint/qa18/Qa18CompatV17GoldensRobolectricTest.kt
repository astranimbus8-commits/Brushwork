package com.brushwork.paint.qa18

import android.graphics.Matrix
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.qa16.V15Fixtures
import com.brushwork.paint.qa17.Qa17CompatArtwork
import com.brushwork.paint.qa18.Qa18CompatV17Golden.crc
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
 * v1.8 QA (compat), I13/I13b and §8.1 on a project the real v1.7.0 build saved
 * ([Qa18CompatV17Golden]: folders, the four kinds of live array, saved selections, per-point
 * roundness, sharp Path points, kerning, a Rotation × 6 stroke, a turned text and a skewed shape,
 * a painted mask, an adjustment layer with an editable mask): v1.8 opens it without a warning,
 * every layer holds the pixels and mask v1.7 loaded, the picture is v1.7's to the bit, and every
 * text, shape, vector and array layer renders from its data to v1.7's pixels (the text ones with
 * Windows host fonts). The editor (controller) opening it, and stepping onto every layer, changes
 * nothing; a save then rewrites `project.json` byte for byte but its revision, in format 3, with
 * the same files; a duplicate opens to the same layers and picture.
 */
@RunWith(RobolectricTestRunner::class)
class Qa18CompatV17GoldensRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun aV17ProjectRendersAndSavesAsV17Did() {
        val dir = Qa18CompatV17Golden.install(app)
        val id = Qa18CompatV17Golden.ID
        val probe = Qa18CompatV17Golden.probe()
        assertEquals("the probe's layers", 20, probe.rows.size)
        val json = File(dir, ProjectFormat.PROJECT_FILE).readText()
        val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), json)
        assertEquals("v1.7 wrote format 3 (folders)", 3, dto.formatVersion)
        val v17Files = dir.listFiles()!!.associate { it.name to it.readBytes() }
        assertEquals("the files v1.7 saved", probe.files.sorted(), v17Files.keys.sorted())

        val repo = ProjectRepository(app)
        val doc = runBlocking { repo.load(id) }
        assertEquals("no warning", emptyList<String>(), doc.loadWarnings)
        assertEquals("format", 3, ProjectFormat.writtenVersion(doc.layers))
        assertEquals("every layer holds what v1.7 loaded", emptyList<String>(), Qa18CompatV17Golden.storedMismatches(doc, probe))
        assertEquals("the picture (I5)", probe.composite, crc(Compositor(doc) { null }.renderFlattened()))
        assertEquals("fresh renderings (I5, I14)", emptyList<String>(), Qa18CompatV17Golden.freshMismatches(doc, probe))
        assertEquals("fresh renderings checked", if (V15Fixtures.isWindows) 11 else 8, Qa18CompatV17Golden.freshChecked(probe))
        assertEquals("two saved selections", 2, doc.savedSelections.size)
        val data = doc.layers.map { it.id to it.dataSnapshot() }

        // In the editor: nothing is redrawn, re-encoded or recorded, on any layer.
        val c = Smoke.controller(app, doc)
        c.viewTransform.set(Matrix())
        Qa17CompatArtwork.settle(c)
        assertEquals("no edit on opening", 0, c.editCount)
        val active = doc.layers[doc.activeLayerIndex]
        for (l in doc.layers.toList()) {
            c.selectLayer(l)
            Qa17CompatArtwork.settle(c)
        }
        c.selectLayer(active)
        Qa17CompatArtwork.settle(c)
        assertEquals("no edit after visiting every layer", 0, c.editCount)
        assertEquals("the same data in the editor", data, doc.layers.map { it.id to it.dataSnapshot() })
        assertEquals("the same pixels in the editor", emptyList<String>(), Qa18CompatV17Golden.storedMismatches(doc, probe))
        assertEquals("the editor's picture", probe.composite, crc(c.compositor.renderFlattened()))

        // Saved (as on leaving after an edit that changed nothing): v1.7's bytes but the revision.
        runBlocking { repo.save(doc, null) }
        val after = dir.listFiles()!!.associate { it.name to it.readBytes() }.filterKeys { it != ProjectFormat.THUMB_FILE }
        assertEquals("the same files", v17Files.keys.sorted(), after.keys.sorted())
        for ((f, bytes) in v17Files) if (f != ProjectFormat.PROJECT_FILE) assertArrayEquals("$f untouched", bytes, after.getValue(f))
        val revision = Regex("\"revision\":\\d+")
        val again = String(after.getValue(ProjectFormat.PROJECT_FILE), Charsets.UTF_8)
        assertEquals("the next revision", "\"revision\":${dto.revision + 1}", revision.find(again)?.value)
        assertEquals("project.json byte for byte but the revision (I13)", json.replace(revision, "\"revision\":0"), again.replace(revision, "\"revision\":0"))
        c.dispose()

        // Duplicated in the gallery: the copy opens to the same layers and picture.
        val copyId = runBlocking { repo.duplicate(id) }
        val copy = runBlocking { repo.load(copyId) }
        assertEquals("no warning on the copy", emptyList<String>(), copy.loadWarnings)
        assertEquals("the copy's layers", emptyList<String>(), Qa18CompatV17Golden.storedMismatches(copy, probe))
        Qa17CompatArtwork.assertSameLayers("the copy", doc, copy)
        Qa17CompatArtwork.assertSameSelections("the copy", doc, copy)
        assertEquals("the copy's picture", probe.composite, crc(Compositor(copy) { null }.renderFlattened()))
    }
}
