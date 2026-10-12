package com.brushwork.paint.qa19

import com.brushwork.paint.compat18.PayloadV18
import com.brushwork.paint.compat18.ProjectFileDtoV18
import com.brushwork.paint.compat18.ProjectFormatV18
import com.brushwork.paint.compat18.ShapeCodecV18
import com.brushwork.paint.compat18.TextCodecV18
import com.brushwork.paint.compat18.TextTransformsV18
import com.brushwork.paint.compat18.VectorCodecV18
import com.brushwork.paint.model.Document
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.9 (design 2.2 and 5.2, modelled on `qa18/Qa18CompatBackFromV17RobolectricTest`): what v1.8
 * does with what v1.9 writes, one test per row of the compat table of 5.2, each reading a v1.9
 * fixture of `app/src/test/resources/qa19/compat/<row>/` with the frozen v1.8 copies of
 * `compat18/` ([ProjectFormatV18], [TextCodecV18], [ShapeCodecV18], [VectorCodecV18],
 * [PayloadV18], [TextTransformsV18]), as v1.8 would.
 *
 * At F0 every row is `@Ignore`d with its row and owner: the owning area writes the row's
 * fixture generator ([generators]), runs [generateFixtures] once, un-ignores its row and adds the
 * row's own checks after [v18Reads] (the table's "v1.8 opens" and "v1.8 edits and saves"
 * columns). Rows 10 and 12 have one test per owner; row 8 (the wrap re-flow) cannot occur and
 * has none (I24 is `MultiTextLayerRobolectricTest`'s). Q18 repeats the table with the real v1.8
 * APK.
 *
 * UI harness: the sections only decode and reopen files, no row needs one. Two generators may:
 * row 10's duplicates (the selection bar's Duplicate) and row 12's R17 edits (the Shape strip),
 * where the area's code has no entry point below the UI; they then use [Qa19Harness.editor].
 *
 * The test file is in `$ExtraTestFiles` of A8, A5, A1, A9, A4 and A3 (5.4); rows owned by A7a,
 * A7b or the lead (13, 14, 15a) are un-ignored by the lead.
 */
@RunWith(RobolectricTestRunner::class)
class Qa19CompatBackFromV18RobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        File(app.filesDir, "projects").deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    /** A file of the fixture folder `qa19/compat/<path>`. */
    private fun resource(path: String): ByteArray {
        val full = "qa19/compat/$path"
        val stream = javaClass.classLoader?.getResourceAsStream(full) ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(full)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$full"), File("app/src/test/resources/$full")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $full (run generateFixtures)")
        return file.readBytes()
    }

    private fun text(path: String): String = String(resource(path), Charsets.UTF_8)

    /**
     * What v1.8 reads of the project of row [row]: `project.json` and every layer's text, shape
     * and vector data through the frozen v1.8 readers, none refused. The fixture holds each of
     * [newKeys] (so it exercises the row), and v1.8 writes none of them back.
     */
    private fun v18Reads(row: String, newKeys: List<String> = emptyList()): ProjectFileDtoV18 {
        val raw = text("$row/project.json")
        val dto = ProjectFormatV18.decode(raw)
        var written = ProjectFormatV18.encode(dto)
        val inLayers = StringBuilder(raw)
        for (l in dto.layers) {
            l.textData?.let { d ->
                val item = TextCodecV18.decode(d)
                assertNotNull("$row ${l.name}: v1.8 reads the text", item)
                written += TextCodecV18.encode(item!!)
            }
            l.shapeData?.let { d ->
                val s = ShapeCodecV18.decode(d)
                assertNotNull("$row ${l.name}: v1.8 reads the shape", s)
                written += ShapeCodecV18.encode(s!!)
            }
            l.vectorFile?.let { f ->
                val bytes = resource("$row/$f")
                inLayers.append(String(PayloadV18.inflate(bytes), Charsets.UTF_8))
                written += VectorCodecV18.encodeJson(VectorCodecV18.decode(bytes))
            }
        }
        for (k in newKeys) {
            assertTrue("$row: the fixture writes \"$k\"", inLayers.contains("\"$k\""))
            assertFalse("$row: v1.8 writes no \"$k\" back", written.contains("\"$k\""))
        }
        return dto
    }

    /** The texts of a layer's data: v1.8 reads the first only. */
    private fun firstText(row: String, layer: String): String =
        requireNotNull(v18Reads(row).layers.single { it.name == layer }.textData) { "$row: $layer is a text layer" }

    // ------------------------------------------------------------------ the rows (5.2)

    @Ignore("5.2 row 1, TextSpec.justify (NEVER): owner A3")
    @Test
    fun row01TextJustify() {
        v18Reads("text-justify", listOf("justify"))
        // A3: v1.8 shows the saved pixels; an edit lays out with `align` (ragged) and drops the field.
    }

    @Ignore("5.2 row 2, TextSpec.kerningMode (NEVER; sanitized sets fontKerning = true): owner A3")
    @Test
    fun row02TextKerningMode() {
        v18Reads("text-kerning", listOf("kerningMode"))
        // A3: v1.8 shows the saved pixels; an edit kerns as "Auto" (fontKerning is true in the data).
    }

    @Ignore("5.2 row 3, TextSpec.list (NEVER): owner A3")
    @Test
    fun row03TextList() {
        v18Reads("text-list", listOf("list"))
        // A3: v1.8 shows the saved pixels; an edit drops markers and indent and keeps the text.
    }

    @Ignore("5.2 row 4, TextPathSpec.nodes (NEVER; sanitized writes the CURVE or LINE fallback): owner A3")
    @Test
    fun row04TextPathNodes() {
        v18Reads("text-path-nodes", listOf("nodes"))
        // A3: v1.8 reads the CURVE or LINE fallback (a closed guide becomes an open curve); the nodes are dropped.
    }

    @Ignore("5.2 row 5, TextLayerData.more and TextItem.id (NEVER): owner A3")
    @Test
    fun row05SeveralTextsInALayer() {
        v18Reads("text-several", listOf("more"))
        // A3: v1.8 shows the saved pixels with every text; it edits only the first text; its
        // re-encode drops `more`; an untouched layer's JSON survives a v1.8 save.
    }

    @Ignore("5.2 row 6, v1.8 Transform (move, rotate, scale) of a several-text layer (TextTransforms.mapped): owner A3")
    @Test
    fun row06V18TransformOfASeveralTextLayer() {
        val data = firstText("text-transform", "Three texts")
        val moved = TextTransformsV18.mapped(data, floatArrayOf(0.8f, -0.6f, 40f, 0.6f, 0.8f, -12f, 0f, 0f, 1f))
        assertNotNull("v1.8 transforms the layer's text", moved)
        assertFalse("v1.8 keeps only the first text", moved!!.contains("\"more\""))
        // A3: v1.9 reopens the v1.8-transformed layer with one editable text, the pixels of the
        // others moved with it (I1 for the first text only).
    }

    @Ignore("5.2 row 7, v1.8 array source and data lift of a several-text layer (ArraySources, DataLift): owner A3")
    @Test
    fun row07V18ArraySourceAndLiftOfASeveralTextLayer() {
        v18Reads("text-array-lift", listOf("more"))
        // A3: v1.8's copy or lift carries the first text only; the others are pixels.
    }

    // Row 8, v1.8's wrap re-flow (TextWrapReflow): not reachable, no section (a wrapping or
    // framed text is alone in its layer, I24, checked by MultiTextLayerRobolectricTest).

    @Ignore("5.2 row 9, ProjectFileDto.paper and BrushworkPayload.paper (NEVER): owner A8")
    @Test
    fun row09Paper() {
        val raw = text("paper/project.json")
        assertTrue("the fixture has a paper", raw.contains("\"paper\""))
        assertFalse("v1.8 writes no paper back", ProjectFormatV18.encode(ProjectFormatV18.decode(raw)).contains("\"paper\""))
        val payload = resource("paper/payload.json")
        assertTrue(String(payload, Charsets.UTF_8).contains("\"paper\""))
        assertFalse("v1.8's payload drops the paper", String(PayloadV18.toJson(PayloadV18.fromJson(payload)), Charsets.UTF_8).contains("\"paper\""))
        // A8: v1.8 opens one transparent layer over the checker; its PNG is transparent, its JPEG on white.
    }

    @Ignore("5.2 row 10 (shapes joined into a vector layer, S1), existing kinds only: owner A9")
    @Test
    fun row10ShapesJoinedIntoAVectorLayer() {
        v18Reads("shapes-joined")
        // A9: fully read and fully editable in v1.8.
    }

    @Ignore("5.2 row 10 (mixed curves), existing kinds only: owner A1")
    @Test
    fun row10MixedCurves() {
        v18Reads("curves-mixed")
        // A1: fully read and fully editable in v1.8.
    }

    @Ignore("5.2 row 10 (merged layers), existing kinds only: owner A4")
    @Test
    fun row10MergedLayers() {
        v18Reads("layers-merged")
        // A4: fully read and fully editable in v1.8.
    }

    @Ignore("5.2 row 10 (duplicates), existing kinds only: owner A4; its generator may need Qa19Harness.editor")
    @Test
    fun row10Duplicates() {
        v18Reads("duplicates")
        // A4: fully read and fully editable in v1.8.
    }

    @Ignore("5.2 row 10 (cut pieces), existing kinds only: owner A5")
    @Test
    fun row10CutPieces() {
        v18Reads("cut-pieces")
        // A5: fully read and fully editable in v1.8.
    }

    @Ignore("5.2 row 10 (bevelled points of Curve, Polyline and Path), existing kinds only: owner A1")
    @Test
    fun row10BevelledCurvePoints() {
        v18Reads("bevel-curves")
        // A1: fully read and fully editable in v1.8.
    }

    @Ignore("5.2 row 10 (bevelled points of shapes), existing kinds only: owner A9")
    @Test
    fun row10BevelledShapePoints() {
        v18Reads("bevel-shapes")
        // A9: fully read and fully editable in v1.8.
    }

    @Ignore("5.2 row 11, one-layer new canvases and \"Picture\" alone (existing format): owner A8")
    @Test
    fun row11OneLayerNewCanvases() {
        val dto = v18Reads("new-canvas")
        assertEquals("one layer", 1, dto.layers.size)
        // A8: and the imported picture's project ("Picture" alone): fully read, fully editable.
    }

    @Ignore("5.2 row 12 (text arrange order), existing fields: owner A3")
    @Test
    fun row12TextArrangeOrder() {
        v18Reads("text-arrange")
        // A3: identical in v1.8.
    }

    @Ignore("5.2 row 12 (R17 edits), existing fields: owner A9; its generator may need Qa19Harness.editor")
    @Test
    fun row12R17ShapeEdits() {
        v18Reads("shape-r17")
        // A9: identical in v1.8.
    }

    @Ignore("5.2 row 13, banded save (byte-identical files): owner A7b, un-ignored by the lead")
    @Test
    fun row13BandedSave() {
        v18Reads("banded/whole")
        val whole = text("banded/whole/files.txt").lines().filter { it.isNotBlank() }
        assertEquals("the same files", whole, text("banded/banded/files.txt").lines().filter { it.isNotBlank() })
        for (f in whole) assertArrayEquals("$f: byte for byte", resource("banded/whole/$f"), resource("banded/banded/$f"))
    }

    @Ignore("5.2 row 14, prefs and tool settings (UiPrefs, drawPreview, showFrameTimes, newCanvas.background, text.newLayerEachTime, bevel, CurveSettings.ownLayer, ShapeSettings.newLayerEachTime): owner lead (F1a, AppSettings.kt)")
    @Test
    fun row14Prefs() {
        assertTrue("written outside any project", text("prefs/files.txt").lines().none { it.startsWith("projects/") })
        // Lead: v1.8's AppSettings reads the v1.9 settings JSON (unknown keys ignored) with its
        // own values; needs compat18 copies of CurveSettings and ShapeSettings (F1a).
    }

    @Ignore("5.2 row 15 (crash reports), app files outside the projects: owner A7a, un-ignored by the lead")
    @Test
    fun row15CrashReports() {
        assertTrue("written outside any project", text("crash/files.txt").lines().none { it.startsWith("projects/") })
    }

    @Ignore("5.2 row 15 (backup-state.json), app files outside the projects: owner A8")
    @Test
    fun row15BackupState() {
        assertTrue("written outside any project", text("backup-state/files.txt").lines().none { it.startsWith("projects/") })
    }

    @Ignore("5.2 row 16, the backup folder (outside the app): owner A8")
    @Test
    fun row16BackupFolder() {
        assertTrue("format 1", text("backup-folder/brushwork-backup.json").contains("\"format\":1"))
        // A8: v1.8 cannot restore from it and leaves it untouched (it never opens the folder).
    }

    // ------------------------------------------------------------------ the generator

    /**
     * The fixture writers, by row folder: each gets an empty folder to fill with v1.9 code (a
     * project saved by [saveProject], a payload, a `files.txt` listing what the row writes).
     * Each area adds its rows' writers.
     */
    private val generators: Map<String, (File) -> Unit> = mapOf()

    /** [doc] saved by v1.9's repository and copied, every file, into [dir]. */
    @Suppress("unused")
    private fun saveProject(doc: Document, dir: File) {
        runBlocking { ProjectRepository(app).save(doc, null) }
        val saved = File(app.filesDir, "projects/${doc.id}")
        assertTrue("saved into $saved", saved.isDirectory)
        saved.copyRecursively(dir, overwrite = true)
    }

    /** `app/src/test/resources/qa19/compat`, from the module or the repository root. */
    private fun fixtureRoot(): File {
        val user = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val module = if (File(user, "src/test").isDirectory) user else File(user, "app")
        return File(module, "src/test/resources/qa19/compat")
    }

    @Ignore("Writes app/src/test/resources/qa19/compat/ with v1.9 code: run by the lead (or an area for its rows) to regenerate the fixtures")
    @Test
    fun generateFixtures() {
        val root = fixtureRoot()
        root.mkdirs()
        for ((row, write) in generators) {
            val dir = File(root, row)
            dir.deleteRecursively()
            dir.mkdirs()
            write(dir)
            println("[qa19 compat] wrote $row: ${dir.list()?.sorted()}")
        }
    }
}
