package com.brushwork.paint.qa18

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.V15Fixtures
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.vector.render.VectorLayerRenderer
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * v1.8 QA (compat): a project the REAL v1.7.0 build saved, and what v1.7 drew from it
 * (`src/test/resources/qa18/compat/v17-compat`, made by `qa18/compat/V17GoldenProbeTest.kt.txt`
 * on the v1.7.0 sources). It is the v1.7 QA's compat artwork (`qa17/Qa17CompatArtwork`, unchanged
 * since v1.7.0: nested, isolated and clipped folders; the four kinds of live array, the raster one
 * in "Edit source pixels"; two saved selections; per-point roundness; sharp Path points; kerning;
 * a Rotation × 6 vector stroke; a turned text and a skewed shape kept as data; symmetry
 * Rotation × 6) plus a raster layer with a painted mask and an Invert adjustment layer with an
 * editable radial mask. 400 × 300 (fewer pixels than 512 × 512), format 3.
 *
 * `probe.tsv`: `composite <crc>`, then per layer, bottom first, `layer <id> <name> <stored crc>
 * <mask crc|-> <fresh crc|-> <kind> <fonts>` (CRC32 of the ARGB ints, big-endian). "fresh" is
 * [fresh] run by v1.7: every text, shape, vector and array layer drawn again from its data.
 * `fonts` marks drawings that use the host's fonts (Windows fonts, like the v1.5 goldens).
 */
internal object Qa18CompatV17Golden {
    const val ID = "qa18-compat-v17"
    const val NAME = "Compat"

    class Row(val id: Long, val name: String, val stored: Long?, val mask: Long?, val fresh: Long?, val kind: String, val fonts: Boolean)

    class Probe(val composite: Long, val files: List<String>, val rows: List<Row>)

    fun resource(path: String): ByteArray {
        val full = "qa18/compat/$path"
        val stream = Qa18CompatV17Golden::class.java.classLoader?.getResourceAsStream(full)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(full)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$full"), File("app/src/test/resources/$full")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $full")
        return file.readBytes()
    }

    fun probe(): Probe {
        var composite = 0L
        var files = emptyList<String>()
        val rows = ArrayList<Row>()
        for (line in String(resource("v17-compat/probe.tsv"), Charsets.UTF_8).lines()) {
            val p = line.split('\t')
            fun num(s: String) = if (s == "-") null else s.toLong()
            when (p[0]) {
                "composite" -> composite = p[1].toLong()
                "files" -> files = p[1].split(',')
                "layer" -> rows += Row(p[1].toLong(), p[2], num(p[3]), num(p[4]), num(p[5]), p[6], p.getOrNull(7) == "true")
            }
        }
        return Probe(composite, files, rows)
    }

    /** Puts the project where the repository keeps projects, as v1.7 left it; returns its folder. */
    fun install(context: Context): File {
        val dir = File(context.filesDir, "projects/$ID")
        dir.deleteRecursively()
        dir.mkdirs()
        for (f in probe().files) File(dir, f).writeBytes(resource("v17-compat/$f"))
        return dir
    }

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    fun crc(p: IntArray): Long {
        val buf = ByteBuffer.allocate(p.size * 4)
        buf.asIntBuffer().put(p)
        return CRC32().apply { update(buf.array()) }.value
    }

    fun crc(b: Bitmap): Long = crc(pixels(b))

    /** A fresh rendering of [l]'s data, exactly as the v1.7 probe drew it (null: no data to draw from). */
    fun fresh(doc: Document, l: Layer): IntArray? {
        val d = l.dataSnapshot()
        val area = Rect(0, 0, doc.width, doc.height)
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        try {
            val cv = Canvas(out)
            val a = d.array
            when {
                a != null && a.pixels != null -> ArrayDraw.drawPixels(cv, a)
                d.vector != null -> VectorLayerRenderer.render(cv, ArrayDraw.effectiveVector(d)!!, area, tips = TipCache(), document = area)
                d.text != null && a == null -> {
                    val item = TextCodec.decode(d.text)!!
                    TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null)
                }
                d.text != null || d.shape != null -> {
                    val draw = ArraySources.sourceDraw(d, doc.colorMode, doc.width, doc.height)!!
                    ArrayDraw.drawWithArray(cv, a, ArrayDraw.sourceBounds(d) ?: RectF(), draw)
                }
                else -> return null
            }
            return pixels(out)
        } finally {
            out.recycle()
        }
    }

    /** Layers of [doc] whose pixels or mask are not what v1.7 loaded from the same files. */
    fun storedMismatches(doc: Document, probe: Probe): List<String> {
        val bad = ArrayList<String>()
        if (doc.layers.map { it.id } != probe.rows.map { it.id }) bad += "layers ${doc.layers.map { it.id }}, v1.7 ${probe.rows.map { it.id }}"
        for ((l, r) in doc.layers.zip(probe.rows)) {
            if (l.isFolder != (r.kind == "folder")) bad += "${l.id} \"${l.name}\": folder ${l.isFolder}, v1.7 ${r.kind}"
            if (l.isFolder) continue
            if (crc(l.bitmap) != r.stored) bad += "${l.id} \"${l.name}\": stored pixels ${crc(l.bitmap)}, v1.7 ${r.stored}"
            val m = l.mask?.let { crc(it) }
            if (m != r.mask) bad += "${l.id} \"${l.name}\": mask $m, v1.7 ${r.mask}"
        }
        return bad
    }

    /** Data layers of [doc] that v1.8 draws differently from v1.7 (font-based ones only on Windows). */
    fun freshMismatches(doc: Document, probe: Probe): List<String> {
        val bad = ArrayList<String>()
        for ((l, r) in doc.layers.zip(probe.rows)) {
            if (l.isFolder || (r.fonts && !V15Fixtures.isWindows)) continue
            val got = fresh(doc, l)?.let { crc(it) }
            if (got != r.fresh) bad += "${l.id} \"${l.name}\" (${r.kind}): fresh rendering $got, v1.7 ${r.fresh}"
        }
        return bad
    }

    /** How many probed layers [freshMismatches] checks on this host. */
    fun freshChecked(probe: Probe): Int = probe.rows.count { it.fresh != null && (!it.fonts || V15Fixtures.isWindows) }
}
