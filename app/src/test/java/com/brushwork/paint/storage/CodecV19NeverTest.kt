package com.brushwork.paint.storage

import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextKerningMode
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VectorCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.9 F1a (design 2.3, I13): every field v1.9 adds to a persisted class is absent from the JSON
 * at its default, so content without v1.9 data writes v1.8's bytes, and no codec version moves.
 * Set, each field is written (and only it: the nested list writes only what differs).
 * `CodecV18GoldenTest` and `I13GoldensRobolectricTest` keep the goldens byte-identical; this test
 * names each new key. JVM.
 */
class CodecV19NeverTest {
    /** `AppSettings.json`'s configuration: `encodeDefaults = true` is why the tool-settings fields need NEVER. */
    private val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun resource(path: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        if (stream != null) return stream.use { String(it.readBytes(), Charsets.UTF_8) }
        val file = listOf(File("src/test/resources/$path"), File("app/src/test/resources/$path")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $path")
        return file.readText()
    }

    private fun assertNoKey(what: String, json: String, vararg keys: String) {
        for (k in keys) assertFalse("$what: \"$k\" is not written at its default: $json", json.contains("\"$k\""))
    }

    @Test
    fun noCodecVersionMoves() {
        assertEquals("TextCodec", 5, TextCodec.VERSION)
        assertEquals("ShapeCodec", 2, ShapeCodec.VERSION)
        assertEquals("VectorCodec", 1, VectorCodec.VERSION)
        assertEquals("ProjectFormat (v1.10: format 4, design 2.9)", 4, ProjectFormat.VERSION)
        assertEquals("Payload", 2, Payload.VERSION)
    }

    @Test
    fun aTextAtTheDefaultsWritesNoV19Key() {
        val items = listOf(
            TextItem("Plain"),
            TextItem("Boxed", TextSpec(box = TextBoxSpec(width = 200f), fontKerning = false), 10f, 20f, 30f),
            TextItem("Vertical", TextSpec(vertical = true), 5f, 5f),
            TextItem("On a circle", path = TextPathSpec(type = TextPathType.CIRCLE, cx = 50f, cy = 50f, radius = 40f)),
            TextItem("On a curve", path = TextPathSpec(type = TextPathType.CURVE, x2 = 100f, cx1 = 30f, cy1 = -30f, cx2 = 70f, cy2 = 30f)),
        )
        for (item in items) {
            val data = TextCodec.encode(item)
            assertNoKey(item.text, data, "justify", "kerningMode", "list", "id", "more", "nodes")
            assertTrue("${item.text}: version 5", data.startsWith("{\"version\":5,"))
            assertEquals("${item.text}: encodeAll of one text is encode", data, TextCodec.encodeAll(listOf(item)))
        }
    }

    @Test
    fun eachV19TextFieldIsWrittenWhenSet() {
        fun data(item: TextItem) = TextCodec.encode(item)
        assertTrue(data(TextItem("j", TextSpec(justify = TextJustify.ON))).contains("\"justify\":\"ON\""))
        assertTrue(data(TextItem("k", TextSpec(kerningMode = TextKerningMode.OPTICAL))).contains("\"kerningMode\":\"OPTICAL\""))
        // The list writes only its fields that differ from their defaults.
        val listed = data(TextItem("l", TextSpec(list = TextListSpec(type = TextListType.BULLETS))))
        assertTrue(listed, listed.contains("\"list\":{\"type\":\"BULLETS\"}"))
        val numbered = data(TextItem("n", TextSpec(list = TextListSpec(type = TextListType.NUMBERS, start = 3, gapEm = 1f))))
        assertTrue(numbered, numbered.contains("\"list\":{\"type\":\"NUMBERS\",\"start\":3,\"gapEm\":1.0}"))
        assertTrue(data(TextItem("i", id = 4)).contains("\"id\":4"))
        val nodes = VSpline(listOf(VSplinePoint(0f, 0f), VSplinePoint(50f, 0f), VSplinePoint(100f, 40f)), order = 2)
        val pathed = data(TextItem("p", path = TextPathSpec(type = TextPathType.CURVE, nodes = nodes)))
        assertTrue(pathed, pathed.contains("\"nodes\":{"))
        val several = TextCodec.encodeAll(listOf(TextItem("a"), TextItem("b")))
        assertTrue(several, several.contains("\"more\":[") && several.startsWith("{\"version\":5,"))
    }

    @Test
    fun aProjectWithoutPaperWritesNoPaper() {
        for (path in listOf("v16/project-plain.json", "v16/project-adjust.json", "qa18/compat/v18-written/project.json")) {
            val raw = resource(path)
            val dto = ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), raw)
            assertEquals("$path: no paper read", null, dto.paper)
            val written = ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto)
            assertNoKey(path, written, "paper")
            val papered = ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto.copy(paper = PaperSpec()))
            assertTrue("$path: a paper is written", papered.contains("\"paper\":{\"color\":-1}"))
            assertEquals("$path: the paper does not change the format version", dto.formatVersion, ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), papered).formatVersion)
        }
    }

    @Test
    fun aPayloadWithoutPaperWritesNoPaper() {
        val p = BrushworkPayload(width = 40, height = 30)
        assertNoKey("payload", String(Payload.toJson(p), Charsets.UTF_8), "paper")
        val papered = p.copy(paper = PaperSpec())
        val json = String(Payload.toJson(papered), Charsets.UTF_8)
        assertTrue(json, json.contains("\"paper\":{\"color\":-1}"))
        assertEquals("the paper keeps the payload at version 1", 1, Payload.writtenVersion(papered))
        assertTrue(json, json.startsWith("{\"version\":1,"))
    }

    @Test
    fun theToolSettingsWriteNoV19KeyAtTheirDefaults() {
        val curve = settingsJson.encodeToString(CurveSettings.serializer(), CurveSettings())
        assertNoKey("CurveSettings", curve, "ownLayer")
        assertTrue(settingsJson.encodeToString(CurveSettings.serializer(), CurveSettings(ownLayer = true)).contains("\"ownLayer\":true"))
        val shape = settingsJson.encodeToString(ShapeSettings.serializer(), ShapeSettings())
        assertNoKey("ShapeSettings", shape, "newLayerEachTime")
        assertTrue(settingsJson.encodeToString(ShapeSettings.serializer(), ShapeSettings(newLayerEachTime = true)).contains("\"newLayerEachTime\":true"))
    }
}
