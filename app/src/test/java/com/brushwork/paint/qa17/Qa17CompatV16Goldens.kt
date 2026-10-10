package com.brushwork.paint.qa17

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.V15Fixtures
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * v1.7 QA (compat): two projects the REAL v1.6.0 build saved, and what v1.6 drew from them
 * (`src/test/resources/qa17/compat/v16-plain`, `v16-adjust`, made by
 * `qa17/compat/V16GoldenPixelsProbeTest.kt.txt` on the v1.6.0 sources). They are the I13 goldens'
 * content: the v1.5 QA projects of `qa16/v15` (whose r1 files they keep byte for byte, so only the
 * files v1.6 added are stored here) with every text and shape in v1.6's encoding, two linked text
 * frames, a letter-scaled text, a Path-tool curve and a shape with its own points. 600 × 420: fewer
 * pixels than 512 × 512.
 *
 * `probe.tsv`: `composite <crc>`, then `layer <id> <name> <stored crc> <fresh crc|-> <fresh==stored>`
 * (CRC32 of the ARGB ints, big-endian). "Star" was re-encoded with a smooth point but not redrawn
 * by the generator, so its fresh rendering differs from its stored pixels in v1.6 too.
 */
internal object Qa17CompatV16Goldens {
    /** Fixture name to (project id, the files v1.6 added). */
    val fixtures: Map<String, Pair<String, List<String>>> = linkedMapOf(
        "plain" to (V15Fixtures.PLAIN to listOf("layer_16_r2.bin", "layer_17_r2.bin", "layer_18_r2.bin", "layer_19_r2.bin", "vector_19_r2.vec")),
        "adjust" to (V15Fixtures.ADJUST to listOf("layer_19_r2.bin", "layer_20_r2.bin", "layer_21_r2.bin", "layer_22_r2.bin", "vector_22_r2.vec")),
    )

    class Probe(val composite: Long, val stored: Map<Long, Long>, val fresh: Map<Long, Long>, val names: Map<Long, String>)

    fun resource(path: String): ByteArray {
        val full = "qa17/compat/$path"
        val stream = Qa17CompatV16Goldens::class.java.classLoader?.getResourceAsStream(full)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(full)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(File("src/test/resources/$full"), File("app/src/test/resources/$full")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $full")
        return file.readBytes()
    }

    /** Puts fixture [name] where the repository keeps projects, as v1.6 left it; returns its id. */
    fun install(context: Context, name: String): String {
        val (id, added) = fixtures.getValue(name)
        val dir = V15Fixtures.install(context, id)
        File(dir, "project.json").writeBytes(resource("v16-$name/project.json"))
        for (f in added) File(dir, f).writeBytes(resource("v16-$name/$f"))
        return id
    }

    fun probe(name: String): Probe {
        var composite = 0L
        val stored = LinkedHashMap<Long, Long>()
        val fresh = LinkedHashMap<Long, Long>()
        val names = LinkedHashMap<Long, String>()
        for (line in String(resource("v16-$name/probe.tsv"), Charsets.UTF_8).lines()) {
            val p = line.split('\t')
            when (p[0]) {
                "composite" -> composite = p[1].toLong()
                "layer" -> {
                    val id = p[1].toLong()
                    names[id] = p[2]
                    stored[id] = p[3].toLong()
                    if (p[4] != "-") fresh[id] = p[4].toLong()
                }
            }
        }
        return Probe(composite, stored, fresh, names)
    }

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** CRC32 of [p] as big-endian ints (the probe's). */
    fun crc(p: IntArray): Long {
        val buf = ByteBuffer.allocate(p.size * 4)
        buf.asIntBuffer().put(p)
        return CRC32().apply { update(buf.array()) }.value
    }

    fun crc(b: Bitmap): Long = crc(pixels(b))

    /** A fresh rendering of [l]'s data, exactly as the v1.6 probe drew it (null: no data). */
    fun fresh(doc: Document, l: Layer): IntArray? {
        val area = Rect(0, 0, doc.width, doc.height)
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        try {
            when {
                l.textData != null -> {
                    val item = TextCodec.decode(l.textData)!!
                    TextRenderer.drawItem(Canvas(out), item, TextRenderer.prepare(item), null)
                }
                l.shapeData != null -> {
                    val s = ShapeCodec.decode(l.shapeData)!!
                    VectorLayerRenderer.render(Canvas(out), VectorContent(objects = listOf(VShape(0L, shape = s))), area, tips = TipCache(), document = area)
                }
                l.vector != null -> VectorLayerRenderer.render(Canvas(out), l.vector!!, area, tips = TipCache(), document = area)
                else -> return null
            }
            return pixels(out)
        } finally {
            out.recycle()
        }
    }

    /** Layers of [doc] whose stored pixels are not what v1.6 loaded from the same files. */
    fun storedMismatches(doc: Document, probe: Probe): List<String> {
        val bad = ArrayList<String>()
        if (doc.layers.map { it.id } != probe.stored.keys.toList()) bad += "layers ${doc.layers.map { it.id }}, v1.6 ${probe.stored.keys}"
        for (l in doc.layers) {
            val want = probe.stored[l.id] ?: continue
            val got = crc(l.bitmap)
            if (got != want) bad += "${l.id} \"${l.name}\": stored pixels $got, v1.6 $want"
        }
        return bad
    }

    /** Text, shape and vector layers of [doc] that v1.7 draws differently from v1.6. */
    fun freshMismatches(doc: Document, probe: Probe): List<String> {
        val bad = ArrayList<String>()
        for (l in doc.layers) {
            val want = probe.fresh[l.id]
            val got = fresh(doc, l)?.let { crc(it) }
            if (got != want) bad += "${l.id} \"${l.name}\": fresh rendering $got, v1.6 $want"
        }
        return bad
    }
}
