package com.brushwork.paint.storage

import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.MaskCodec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.vector.VectorCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * v1.7 invariant I13: content that uses no v1.7 feature is written exactly as v1.6 wrote it. The
 * goldens in `src/test/resources/v16/` were captured from the v1.6 sources (`c72ea66`) before any
 * v1.7 change, by `v16/V16GoldenGenerator.kt.txt`: the two v1.5 QA projects (`qa16/v15`) with
 * every text and shape re-encoded by v1.6, two linked text frames, a letter-scaled text, a
 * Path-tool curve and a shape with its own points, saved by v1.6's repository.
 *
 * - `project.json` decodes and encodes again byte for byte;
 * - every stored text, shape, mask spec and adjustment re-encodes byte for byte, except the codec
 *   `version` number at the start of a text or shape;
 * - every vector file re-encodes to the same JSON;
 * - loading the project and saving it again writes the same `project.json` but for its revision,
 *   in the same format version.
 */
@RunWith(RobolectricTestRunner::class)
class I13GoldensRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private val goldens = mapOf(
        "plain" to listOf("vector_6_r1.vec", "vector_19_r2.vec"),
        "adjust" to listOf("vector_6_r1.vec", "vector_22_r2.vec"),
    )

    private fun resource(path: String): ByteArray {
        val full = "v16/$path"
        val stream = javaClass.classLoader?.getResourceAsStream(full)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(full)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$full"), File("app/src/test/resources/$full")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $full")
        return file.readBytes()
    }

    private fun golden(name: String): String = String(resource("project-$name.json"), Charsets.UTF_8)

    private fun inflate(b: ByteArray): String {
        val inf = Inflater()
        try {
            inf.setInput(b)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw AssertionError("truncated vector file")
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            inf.end()
        }
    }

    /** [data] with the codec version at its start replaced by 0 (the one part a v1.7 codec may change). */
    private fun versionless(data: String): String = data.replaceFirst(Regex("^\\{\"version\":\\d+,"), "{\"version\":0,")

    @Test
    fun projectJsonReencodesByteForByte() {
        for (name in goldens.keys) {
            val json = golden(name)
            val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), json)
            assertEquals("$name: project.json byte for byte", json, ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto))
        }
    }

    @Test
    fun layerDataReencodesByteForByte() {
        var texts = 0
        var shapes = 0
        for (name in goldens.keys) {
            val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), golden(name))
            for (e in dto.layers) {
                e.textData?.let { s ->
                    val item = TextCodec.decode(s)
                    assertNotNull("$name/${e.name}: the text decodes", item)
                    assertEquals("$name/${e.name}: the text byte for byte", versionless(s), versionless(TextCodec.encode(item!!)))
                    texts++
                }
                e.shapeData?.let { s ->
                    val shape = ShapeCodec.decode(s)
                    assertNotNull("$name/${e.name}: the shape decodes", shape)
                    assertEquals("$name/${e.name}: the shape byte for byte", versionless(s), versionless(ShapeCodec.encode(shape!!)))
                    shapes++
                }
                e.maskSpec?.let { s -> assertEquals("$name/${e.name}: the mask spec", s, MaskCodec.encode(MaskCodec.decode(s)!!)) }
                e.adjustment?.let { s -> assertEquals("$name/${e.name}: the adjustment", s, AdjustmentCodec.encode(AdjustmentCodec.decode(s)!!)) }
            }
        }
        assertTrue("texts and shapes were checked ($texts, $shapes)", texts >= 20 && shapes >= 2)
    }

    @Test
    fun vectorFilesReencodeToTheSameJson() {
        for ((name, files) in goldens) {
            for (f in files) {
                val bytes = resource("$name/$f")
                val content = VectorCodec.decode(bytes)
                assertEquals("$name/$f: the same JSON", inflate(bytes), inflate(VectorCodec.encode(content)))
            }
        }
    }

    @Test
    fun loadingAndSavingWritesTheGoldenAgain() {
        for ((name, files) in goldens) {
            val json = golden(name)
            val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), json)
            val id = dto.id
            val dir = File(app.filesDir, "projects/$id").apply { deleteRecursively(); mkdirs() }
            File(dir, ProjectFormat.PROJECT_FILE).writeText(json)
            val blank = ByteArray(dto.width * 4)
            for (e in dto.layers) {
                LayerCodec.writeRepeatedRow(File(dir, e.contentFileName), dto.width, dto.height, blank)
                if (e.hasMask) LayerCodec.writeRepeatedRow(File(dir, e.maskFileName), dto.width, dto.height, blank)
            }
            for (f in files) File(dir, f).writeBytes(resource("$name/$f"))
            val repo = ProjectRepository(app)
            val doc = runBlocking { repo.load(id) }
            assertEquals("$name: loads without warnings", emptyList<String>(), doc.loadWarnings)
            assertEquals("$name: the same format version", dto.formatVersion, ProjectFormat.writtenVersion(doc.layers))
            runBlocking { repo.save(doc, null) }
            val again = File(dir, ProjectFormat.PROJECT_FILE).readText()
            val revision = Regex("\"revision\":\\d+")
            assertEquals("$name: the next revision", "\"revision\":${dto.revision + 1}", revision.find(again)?.value)
            assertEquals("$name: project.json byte for byte but the revision", json.replace(revision, "\"revision\":0"), again.replace(revision, "\"revision\":0"))
            dir.deleteRecursively()
        }
    }
}
