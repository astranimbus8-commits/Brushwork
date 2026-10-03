package com.brushwork.paint.qa16

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PdfPage
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.StrokeExport
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextExportMode
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.storage.ImageExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Projects written by v1.5.0 itself, and what v1.5.0 exported from them (v1.6 final QA, I8).
 *
 * `src/test/resources/qa16/v15/<id>/` holds the project folders exactly as v1.5.0's
 * `ProjectRepository.save` wrote them (minus the gallery thumbnail). They were made with the v1.5
 * tools by `qa16/v15/V15FixtureGenerator.kt.txt`, run on the v1.5.0 tag's sources; that run then
 * loaded each project back with v1.5's `ProjectRepository.load` and wrote [golden]: the CRC32 and
 * length of v1.5's PNG exports (flattened, on white, the screen's display tiles, a 256 px
 * thumbnail) and SVG / PDF exports (PDFs with their creation date and random file id zeroed,
 * [normalized]) of the LOADED project, so v1.6 is compared with v1.5 on the same data (brush seeds
 * and timestamps are stored, not regenerated).
 *
 * - [PLAIN]: no adjustment layer. Raster with a painted mask, a clipping group, a hidden layer, a
 *   vector layer (brush stroke, Curve-tool curve, shape, gradient path, a curve with custom handles
 *   and per-anchor widths, a closed tension curve, a polyline), an editable star shape layer, and
 *   text: horizontal, vertical, on a circle, wrapped around the raster picture, a turned centred
 *   caption box with an outline, vertical mixed (sideways Latin), rotated letters on a curve.
 * - [ADJUST]: the same plus four adjustment layers: Tone (linear mask, from the Masks tool), Hue /
 *   Saturation (radial + subtracted linear mask, 80 %), Brightness & Contrast (inverted linear
 *   mask, density 0.9, MULTIPLY at 60 %), Invert (no mask, 50 %), over a background with a hole.
 *
 * Renders of text depend on the host's fonts (the goldens are Windows ones, like
 * `UnscaledParityGoldenTest`): checks against [golden] run on Windows only.
 */
internal object V15Fixtures {
    const val PLAIN = "v15-plain"
    const val ADJUST = "v15-adjust"

    val isWindows: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")

    val files: Map<String, List<String>> = mapOf(
        PLAIN to listOf(
            "project.json", "vector_6_r1.vec", "mask_2_r1.bin",
            "layer_1_r1.bin", "layer_2_r1.bin", "layer_3_r1.bin", "layer_4_r1.bin", "layer_5_r1.bin", "layer_6_r1.bin", "layer_7_r1.bin",
            "layer_8_r1.bin", "layer_9_r1.bin", "layer_10_r1.bin", "layer_12_r1.bin", "layer_13_r1.bin", "layer_14_r1.bin", "layer_15_r1.bin",
        ),
        ADJUST to listOf(
            "project.json", "vector_6_r1.vec", "mask_2_r1.bin", "mask_11_r1.bin", "mask_16_r1.bin", "mask_17_r1.bin",
            "layer_1_r1.bin", "layer_2_r1.bin", "layer_3_r1.bin", "layer_4_r1.bin", "layer_5_r1.bin", "layer_6_r1.bin", "layer_7_r1.bin",
            "layer_8_r1.bin", "layer_9_r1.bin", "layer_10_r1.bin", "layer_11_r1.bin", "layer_12_r1.bin", "layer_13_r1.bin", "layer_14_r1.bin",
            "layer_15_r1.bin", "layer_16_r1.bin", "layer_17_r1.bin", "layer_18_r1.bin",
        ),
    )

    /** CRC32 and length of v1.5.0's exports of the loaded projects (Windows host). */
    val golden: Map<String, Map<String, Pair<Long, Int>>> = mapOf(
        PLAIN to mapOf(
            "export-a4-outlines.pdf" to (1012355562L to 143835),
            "export-hidden-white.svg" to (3060345065L to 187246),
            "export-outlines.svg" to (298956367L to 254873),
            "export.pdf" to (3854634344L to 143433),
            "export.svg" to (4067966600L to 187012),
            "flat-white.png" to (3318592666L to 66270),
            "flat.png" to (3318592666L to 66270),
            "thumb-256.png" to (613328252L to 27084),
            "tiles.png" to (3318592666L to 66270),
        ),
        ADJUST to mapOf(
            "export-a4-outlines.pdf" to (792907613L to 141819),
            "export-hidden-white.svg" to (1346289853L to 178502),
            "export-outlines.svg" to (1207012122L to 178426),
            "export.pdf" to (2629481298L to 141806),
            "export.svg" to (1207012122L to 178426),
            "flat-white.png" to (2951899822L to 65564),
            "flat.png" to (1965919927L to 65617),
            "thumb-256.png" to (2197294719L to 25853),
            "tiles.png" to (1965919927L to 65617),
        ),
    )

    /** The SVG / PDF exports of the generator, by file name. */
    val exports: Map<String, ExportOptions> = linkedMapOf(
        "export.svg" to ExportOptions(VectorFormat.SVG),
        "export-outlines.svg" to ExportOptions(VectorFormat.SVG, strokes = StrokeExport.PICTURES, text = TextExportMode.OUTLINES),
        "export-hidden-white.svg" to ExportOptions(VectorFormat.SVG, includeHidden = true, whiteBackground = true),
        "export.pdf" to ExportOptions(VectorFormat.PDF),
        "export-a4-outlines.pdf" to ExportOptions(VectorFormat.PDF, page = PdfPage.A4, text = TextExportMode.OUTLINES, includeHidden = true),
    )

    fun resource(path: String): ByteArray {
        val full = "qa16/v15/$path"
        val stream = V15Fixtures::class.java.classLoader?.getResourceAsStream(full)
            ?: ClassLoader.getSystemResourceAsStream(full)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(full)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$full"), File("app/src/test/resources/$full")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $full")
        return file.readBytes()
    }

    /** Puts v1.5 project [id] where the repository keeps projects; returns its folder. */
    fun install(context: Context, id: String): File {
        val dir = File(context.filesDir, "projects/$id")
        dir.deleteRecursively()
        dir.mkdirs()
        for (name in files.getValue(id)) File(dir, name).writeBytes(resource("$id/$name"))
        return dir
    }

    fun crc(bytes: ByteArray): Pair<Long, Int> = CRC32().apply { update(bytes) }.value to bytes.size

    /** [bytes] of export [name] with a PDF's creation date and file id (time and random) zeroed, same length. */
    fun normalized(name: String, bytes: ByteArray): ByteArray {
        if (!name.endsWith(".pdf")) return bytes
        var s = String(bytes, Charsets.ISO_8859_1)
        s = Regex("""/CreationDate \(D:(\d+)""").replace(s) { "/CreationDate (D:" + "0".repeat(it.groupValues[1].length) }
        s = Regex("""/ID \[<([0-9A-Fa-f]+)> <([0-9A-Fa-f]+)>]""").replace(s) { "/ID [<" + "0".repeat(it.groupValues[1].length) + "> <" + "0".repeat(it.groupValues[2].length) + ">]" }
        return s.toByteArray(Charsets.ISO_8859_1)
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { ImageExport.encode(bitmap, ExportFormat.PNG, it) }.toByteArray()

    /** The screen at zoom 1: the canvas's display tiles drawn unsmoothed (as the generator did). */
    fun tiles(c: EditorController): Bitmap {
        val tiles = DisplayTiles(c.doc.width, c.doc.height)
        tiles.update(c.compositor, null)
        val screen = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        tiles.draw(Canvas(screen), null, smooth = false)
        return screen
    }

    /** The flattened image through the v1.5 Skia adjustment path (`directWrite = false`). */
    fun flattenedV15Path(c: EditorController): Bitmap {
        val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        c.compositor.drawDocument(Canvas(out), null, useOverrides = false, target = CompositeTarget(out, Matrix(), directWrite = false))
        return out
    }

    /** What v1.6 writes for the generator's file [name] ([normalized]). */
    fun output(c: EditorController, name: String): ByteArray = when (name) {
        "flat.png" -> png(c.compositor.renderFlattened())
        "flat-white.png" -> png(c.compositor.renderFlattened(0xFFFFFFFF.toInt()))
        "thumb-256.png" -> png(c.compositor.renderThumbnail(256))
        "tiles.png" -> png(tiles(c))
        else -> normalized(name, export(c, exports.getValue(name)))
    }

    fun export(c: EditorController, options: ExportOptions): ByteArray {
        val scene = runBlocking { ExportSceneBuilder(c, options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { if (options.format == VectorFormat.SVG) SvgWriter(scene).write(out) else PdfWriter(scene, options.page).write(out) }
        return out.toByteArray()
    }

    /**
     * Largest per-channel difference of two same-size bitmaps (0..255, unpremultiplied ARGB) beyond
     * the premultiplication allowance of `AdjustmentFusedPathRobolectricTest` (colour channels of a
     * pixel with alpha < 64 are free, others may differ by `3 + 255 / alpha` before counting).
     */
    fun maxDiff(a: Bitmap, b: Bitmap): Int {
        require(a.width == b.width && a.height == b.height)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var m = 0
        for (i in pa.indices) {
            val x = pa[i]; val y = pb[i]
            if (x == y) continue
            val alpha = minOf(x ushr 24, y ushr 24)
            for (s in intArrayOf(0, 8, 16, 24)) {
                val d = kotlin.math.abs(((x ushr s) and 0xFF) - ((y ushr s) and 0xFF))
                val excess = if (s < 24 && alpha < 255) (if (alpha < 64) 0 else maxOf(0, d - (3 + 255 / alpha))) else d
                m = maxOf(m, excess)
            }
        }
        return m
    }
}
