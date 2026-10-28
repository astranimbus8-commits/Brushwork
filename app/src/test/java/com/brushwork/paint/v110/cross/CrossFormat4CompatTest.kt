package com.brushwork.paint.v110.cross

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.compat19.CorruptProjectExceptionV19
import com.brushwork.paint.compat19.LayerCodecV19
import com.brushwork.paint.compat19.ProjectFileDtoV19
import com.brushwork.paint.compat19.ProjectFormatV19
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.a11.A11Kit
import com.brushwork.paint.v110.lead.F1Pixels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * v1.10 G2 (compat, C2; A11 x F1 x the compat19 kit): a format-4 save meets v1.9. On a 512 x 512
 * canvas v1.9's cap is 100 open layers. Over it (101 layers, or 100 and a mask) v1.10 writes
 * format 4 and v1.9's loader ([ProjectFormatV19.open], the head of its `loadLocked`) refuses the
 * artwork with its own "newer version" message, never as a damaged file. At the cap (a folder,
 * a mask and blank layers: format 3; plain layers: format 1) the save is what v1.9 writes, byte
 * for byte (X6): `project.json` is what v1.9 writes for the entries it reads, every pixel file is
 * v1.9's encoding of the same pixels ([LayerCodecV19.encode]), and the kit decodes the pixels
 * exactly and renders them as the artwork drew. Painted layers are bitmaps at CLASSIC (what
 * v1.9 holds) and tiles and blank stores at FULL, so both save paths are checked.
 */
abstract class CrossFormat4CompatBase(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val side = SIDE
    private val bytes = side * side * 4

    /** What a saved layer must hold: its entry's values and its raw pixels (null: transparent). */
    private class Expected(
        val id: Long,
        val name: String,
        val parentId: Long,
        val folder: FolderSpec?,
        val opacity: Float,
        val blend: LayerBlendMode,
        val visible: Boolean,
        val clipping: Boolean,
        val content: ByteArray?,
        val mask: ByteArray?,
    )

    private class Built(val doc: Document, val expected: List<Expected>)

    /** A painted [side]² bitmap: seeded noise over [r]. */
    private fun painted(r: Rect, seed: Long): Bitmap = BitmapUtils.createLayerBitmap(side, side).also { A11Kit.noise(it, r, seed) }

    /** A layer [id] holding [b] as this level holds it (a bitmap at CLASSIC, tiles above); null [b] = transparent. */
    private fun layer(id: Long, name: String, b: Bitmap?): Layer = when {
        level == EngineLevel.CLASSIC -> Layer(id, name, b ?: BitmapUtils.createLayerBitmap(side, side))
        b == null -> Layer(id, name, BlankPixels(side, side))
        else -> Layer(id, name, TiledPixels.fromBitmap(b)).also { b.recycle() }
    }

    /** Gives [l] the mask [m] as this level holds it. */
    private fun mask(l: Layer, m: Bitmap) {
        if (level == EngineLevel.CLASSIC) l.mask = m else l.maskPixels = TiledPixels.fromBitmap(m).also { m.recycle() }
    }

    private fun expect(l: Layer, content: ByteArray?, mask: ByteArray?) = Expected(
        l.id, l.name, l.parentId, l.folder, l.opacity, l.blendMode, l.visible, l.clipping, content, mask,
    )

    /**
     * 100 open layers, v1.9's cap, in format 3: a masked painted layer, a folder (isolated,
     * Screen, 80%) holding a Multiply layer and a layer clipped to it, then 96 transparent layers
     * (one hidden).
     */
    private fun folderAndMask(id: String): Built {
        val doc = Document(id, id, side, side)
        val out = ArrayList<Expected>()
        val p1 = painted(Rect(0, 0, 220, 200), 11)
        val c1 = A11Kit.raw(p1)
        val m = BitmapUtils.createMaskBitmap(side, side).also {
            A11Kit.fill(it, Rect(40, 40, 180, 120), 0xFF404040.toInt())
            A11Kit.fill(it, Rect(60, 130, 90, 200), 0xFF000000.toInt())
        }
        val m1 = A11Kit.raw(m)
        val l1 = layer(1, "Paper", p1).also { mask(it, m) }
        doc.layers += l1
        out += expect(l1, c1, m1)
        val p2 = painted(Rect(100, 90, 330, 300), 22)
        val c2 = A11Kit.raw(p2)
        val l2 = layer(2, "Shade", p2).apply { parentId = 4; blendMode = LayerBlendMode.MULTIPLY; opacity = 0.6f }
        doc.layers += l2
        out += expect(l2, c2, null)
        val p3 = BitmapUtils.createLayerBitmap(side, side).also {
            A11Kit.fill(it, Rect(150, 50, 450, 250), 0xFF2E7DD7.toInt())
            A11Kit.fill(it, Rect(200, 120, 260, 400), 0x80E04020.toInt())
        }
        val c3 = A11Kit.raw(p3)
        val l3 = layer(3, "Clip", p3).apply { parentId = 4; clipping = true }
        doc.layers += l3
        out += expect(l3, c3, null)
        val f = Layer.newFolder(4, "Group", FolderSpec(passThrough = false)).apply { blendMode = LayerBlendMode.SCREEN; opacity = 0.8f }
        doc.layers += f
        out += expect(f, null, null)
        for (n in 5L..100L) {
            val l = layer(n, "Blank $n", null).apply { visible = n != 7L }
            doc.layers += l
            out += expect(l, null, null)
        }
        doc.activeLayerIndex = 1
        doc.createdAt = 1
        doc.modifiedAt = 2
        return Built(doc, out)
    }

    /** [count] plain layers, two painted, the others transparent; the first masked when [masked]. */
    private fun plain(id: String, count: Int, masked: Boolean = false): Built {
        val doc = Document(id, id, side, side)
        val out = ArrayList<Expected>()
        for (n in 1L..count) {
            val b = if (n <= 2) painted(Rect(30 * n.toInt(), 20, 200 + 60 * n.toInt(), 260), n) else null
            val raw = b?.let { A11Kit.raw(it) }
            val l = layer(n, "L$n", b)
            var mraw: ByteArray? = null
            if (masked && n == 1L) {
                val m = BitmapUtils.createMaskBitmap(side, side).also { A11Kit.fill(it, Rect(0, 0, 100, 100), 0xFF808080.toInt()) }
                mraw = A11Kit.raw(m)
                mask(l, m)
            }
            doc.layers += l
            out += expect(l, raw, mraw)
        }
        doc.activeLayerIndex = count - 1
        doc.createdAt = 1
        doc.modifiedAt = 2
        return Built(doc, out)
    }

    /** Saves [doc] as a fresh project and lets go of it; returns the project's folder. */
    private fun save(doc: Document): File {
        val repo = A11Kit.freshRepo()
        try {
            runBlocking { repo.save(doc, null) }
        } finally {
            A11Kit.dispose(doc)
        }
        return A11Kit.dirOf(doc.id)
    }

    @Test
    fun atTheCapAFolderAndAMaskSaveAsV19WritesAndRender() {
        val built = folderAndMask("cap3")
        val drawn = F1Pixels.flatten(built.doc)
        try {
            val dir = save(built.doc)
            val dto = checkAsV19Writes(dir, built.expected, version = 3)
            val v19 = v19View(dir, dto)
            try {
                val again = F1Pixels.flatten(v19)
                try {
                    F1Pixels.assertBitEqual("$level: the kit's pixels render as the artwork drew", drawn, again)
                } finally {
                    again.recycle()
                }
            } finally {
                A11Kit.dispose(v19)
            }
        } finally {
            drawn.recycle()
        }
    }

    @Test
    fun atTheCapPlainLayersSaveAsV19Writes() {
        val built = plain("cap1", 100)
        checkAsV19Writes(save(built.doc), built.expected, version = 1)
    }

    @Test
    fun overTheCapV19RefusesWithItsNewerVersionMessage() {
        for ((what, built) in listOf("101 layers" to plain("over101", 101), "100 layers and a mask" to plain("over100m", 100, masked = true))) {
            val dir = save(built.doc)
            assertEquals("$level, $what: v1.10 writes format 4", 4, ProjectFormat.read(dir).formatVersion)
            // v1.9 can read the entries: what it refuses is the version, and it says so.
            val dto = ProjectFormatV19.decode(File(dir, ProjectFormatV19.PROJECT_FILE).readText())
            assertTrue("$level, $what: over v1.9's cap", ProjectFormatV19.effectiveLayerCount(dto) > ProjectFormatV19.v19Cap(side, side))
            try {
                ProjectFormatV19.open(dir)
                fail("$level, $what: v1.9 opens a format-4 artwork")
            } catch (e: IOException) {
                assertFalse("$level, $what: not reported as damaged: $e", e is CorruptProjectExceptionV19)
                assertEquals("$level, $what: v1.9's refusal", ProjectFormatV19.NEWER_VERSION, e.message)
            }
            // The pixel files stay BWLY version 1: only the project file's version moved.
            for (e in dto.layers) {
                val name = e.contentFileName
                val (w, h, len) = LayerCodecV19.header(File(dir, name).readBytes(), name)
                assertEquals("$level, $what: $name header", Triple(side, side, bytes), Triple(w, h, len))
                if (e.hasMask) LayerCodecV19.header(File(dir, e.maskFileName).readBytes(), e.maskFileName)
            }
        }
    }

    /**
     * Checks that [dir] is what v1.9 writes for [expected] in format [version] and that v1.9 opens
     * it within its cap; returns the project as v1.9 reads it.
     */
    private fun checkAsV19Writes(dir: File, expected: List<Expected>, version: Int): ProjectFileDtoV19 {
        val text = File(dir, ProjectFormatV19.PROJECT_FILE).readText()
        val dto = ProjectFormatV19.open(dir)
        assertEquals("$level: format", version, dto.formatVersion)
        assertEquals("$level: the format v1.9 writes for these entries", ProjectFormatV19.writtenVersion(dto.layers), dto.formatVersion)
        assertEquals("$level: v1.10's format agrees", version, ProjectFormat.read(dir).formatVersion)
        assertEquals("$level: project.json is what v1.9 writes for the entries it reads", ProjectFormatV19.encode(dto), text)
        assertEquals("$level: at v1.9's cap", ProjectFormatV19.v19Cap(side, side), ProjectFormatV19.effectiveLayerCount(dto))
        assertEquals("$level: 100 open layers", 100, ProjectFormatV19.effectiveLayerCount(dto))
        assertEquals("$level: every layer", expected.size, dto.layers.size)

        val blank = LayerCodecV19.encode(side, side, ByteArray(bytes))
        val dst = ByteArray(bytes)
        for ((x, e) in expected.zip(dto.layers)) {
            val what = "$level, layer ${x.id} \"${x.name}\""
            assertEquals("$what: id", x.id, e.id)
            assertEquals("$what: name", x.name, e.name)
            assertEquals("$what: props name", x.name, e.props.name)
            assertEquals("$what: parent", x.parentId, e.parentId)
            assertEquals("$what: opacity", x.opacity, e.props.opacity, 0f)
            assertEquals("$what: blend", x.blend.name, e.props.blendMode.name)
            assertEquals("$what: visible", x.visible, e.props.visible)
            assertEquals("$what: clipping", x.clipping, e.props.clipping)
            val spec = e.folderSpec(dto.formatVersion)
            assertEquals("$what: folder", x.folder?.passThrough, spec?.passThrough)
            assertEquals("$what: mask", x.mask != null, e.hasMask)
            if (spec != null) continue
            val file = File(dir, e.contentFileName).readBytes()
            val want = x.content?.let { LayerCodecV19.encode(side, side, it) } ?: blank
            assertArrayEquals("$what: the pixel file v1.9 writes (X6)", want, file)
            LayerCodecV19.read(File(dir, e.contentFileName), side, side, dst)
            assertArrayEquals("$what: the kit decodes the pixels", x.content ?: ByteArray(bytes), dst)
            if (x.mask != null) {
                val mfile = File(dir, e.maskFileName).readBytes()
                assertArrayEquals("$what: the mask file v1.9 writes (X6)", LayerCodecV19.encode(side, side, x.mask), mfile)
                LayerCodecV19.read(File(dir, e.maskFileName), side, side, dst)
                assertArrayEquals("$what: the kit decodes the mask", x.mask, dst)
            }
        }
        return dto
    }

    /** The artwork of [dir] built from what the kit decodes ([dto], v1.9's entries): transparent layers as blank stores. */
    private fun v19View(dir: File, dto: ProjectFileDtoV19): Document {
        val doc = Document("v19", "v19", dto.width, dto.height)
        val dst = ByteArray(bytes)
        fun bitmapOf(data: ByteArray): Bitmap = BitmapUtils.createLayerBitmap(side, side).also { it.copyPixelsFromBuffer(ByteBuffer.wrap(data)) }
        for (e in dto.layers) {
            val spec = e.folderSpec(dto.formatVersion)
            val l = if (spec != null) {
                Layer.newFolder(e.id, e.name, FolderSpec(passThrough = spec.passThrough))
            } else {
                LayerCodecV19.read(File(dir, e.contentFileName), side, side, dst)
                if (dst.all { it == 0.toByte() }) Layer(e.id, e.name, BlankPixels(side, side)) else Layer(e.id, e.name, bitmapOf(dst))
            }
            l.parentId = e.parentId
            l.opacity = e.props.opacity
            l.blendMode = LayerBlendMode.valueOf(e.props.blendMode.name)
            l.visible = e.props.visible
            l.clipping = e.props.clipping
            l.maskEnabled = e.props.maskEnabled
            if (e.hasMask) {
                LayerCodecV19.read(File(dir, e.maskFileName), side, side, dst)
                l.mask = bitmapOf(dst)
            }
            doc.layers += l
        }
        doc.activeLayerIndex = dto.activeLayerIndex
        return doc
    }

    private companion object {
        const val SIDE = 512
    }
}

@RunWith(RobolectricTestRunner::class)
class CrossFormat4CompatClassicTest : CrossFormat4CompatBase(EngineLevel.CLASSIC)

@RunWith(RobolectricTestRunner::class)
class CrossFormat4CompatFullTest : CrossFormat4CompatBase(EngineLevel.FULL)
