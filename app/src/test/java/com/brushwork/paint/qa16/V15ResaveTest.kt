package com.brushwork.paint.qa16

import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.6 final QA, I4 / I8: saving a v1.5 project in v1.6 without touching it keeps its files (the
 * same layer, mask and vector files, byte for byte) and its project.json (only the save revision
 * moves on), so v1.5 opens it again exactly as before; duplicating it ("Duplicate artwork")
 * copies every datum, and the copy renders the same pixels.
 */
@RunWith(RobolectricTestRunner::class)
class V15ResaveTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val repo by lazy { ProjectRepository(app) }

    private fun withoutRevision(json: String): JsonObject {
        val o = Json.parseToJsonElement(json).jsonObject
        return JsonObject(o - "revision")
    }

    private fun resave(id: String) {
        val dir = V15Fixtures.install(app, id)
        val before = dir.listFiles()!!.associate { it.name to it.readBytes() }
        val doc = runBlocking { repo.load(id) }
        val c = Smoke.controller(app, doc)
        assertTrue(Smoke.pumpUntil(30_000) { c.busyMessage == null && !c.vectors.isRendering })
        runBlocking { repo.save(doc, c.compositor.renderThumbnail(512)) }
        val after = dir.listFiles()!!.filter { it.name != "thumb.png" }.associate { it.name to it.readBytes() }
        assertEquals("$id: the same files", before.keys.sorted(), after.keys.sorted())
        for ((name, bytes) in before) {
            if (name == "project.json") continue
            assertArrayEquals("$id: $name is untouched", bytes, after.getValue(name))
        }
        val oldJson = String(before.getValue("project.json"), Charsets.UTF_8)
        val newJson = String(after.getValue("project.json"), Charsets.UTF_8)
        assertEquals("$id: project.json but its revision", withoutRevision(oldJson), withoutRevision(newJson))
        assertEquals(
            "$id: project.json byte for byte but the revision",
            oldJson.replace(Regex("\"revision\":\\d+"), "\"revision\":0"),
            newJson.replace(Regex("\"revision\":\\d+"), "\"revision\":0"),
        )
        assertEquals(JsonPrimitive(2), Json.parseToJsonElement(newJson).jsonObject["revision"])
    }

    @Test
    fun resavingAnUntouchedV15ProjectKeepsItsFiles() {
        resave(V15Fixtures.PLAIN)
        resave(V15Fixtures.ADJUST)
    }

    private fun sameData(a: Document, b: Document) {
        assertEquals(a.layers.size, b.layers.size)
        for ((x, y) in a.layers.zip(b.layers)) {
            assertEquals(x.props(), y.props())
            assertEquals(x.textData, y.textData)
            assertEquals(x.shapeData, y.shapeData)
            assertEquals(x.vector, y.vector)
            assertEquals(x.maskSpec, y.maskSpec)
            assertEquals(x.adjustment, y.adjustment)
            assertTrue("${x.name}: pixels", x.bitmap.sameAs(y.bitmap))
            assertEquals(x.mask == null, y.mask == null)
            if (x.mask != null) assertTrue("${x.name}: mask", x.mask!!.sameAs(y.mask))
        }
    }

    @Test
    fun duplicatingAV15ProjectCopiesEveryDatum() {
        for (id in listOf(V15Fixtures.PLAIN, V15Fixtures.ADJUST)) {
            V15Fixtures.install(app, id)
            val copyId = runBlocking { repo.duplicate(id) }
            val original = runBlocking { repo.load(id) }
            val copy = runBlocking { repo.load(copyId) }
            assertEquals(emptyList<String>(), copy.loadWarnings)
            sameData(original, copy)
            val a = Smoke.controller(app, original).compositor.renderFlattened()
            val b = Smoke.controller(app, copy).compositor.renderFlattened()
            assertTrue("$id: the copy renders the same pixels", a.sameAs(b))
            File(app.filesDir, "projects/$copyId").deleteRecursively()
        }
    }
}
