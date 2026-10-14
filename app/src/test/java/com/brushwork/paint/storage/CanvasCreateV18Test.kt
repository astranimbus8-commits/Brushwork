package com.brushwork.paint.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * v1.9 F1b (design 2.4.8): `ProjectRepository.create` and `createFromImage`, now writing the
 * project from a [CanvasCreate] seed, write exactly v1.8's files: the same `project.json` (no
 * paper key, "Layer 1" active), the same pixel files and the same thumbnail, byte for byte.
 *
 * v1.9 A8 (R15): narrowed to what stays v1.8. A null [NewCanvasSpec.start] (the test fixtures
 * and the SVG/PDF import) is still v1.8 byte for byte; the dialog's starts make one layer
 * (`v19/a8/NewCanvasStartTest`). A picture keeps v1.8's pixel file and thumbnail, now as the
 * project's only layer, "Picture", active.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasCreateV18Test {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "projects").deleteRecursively()
    }

    private fun dirOf(id: String) = File(context.filesDir, "projects/$id")

    // ------------------------------------------------------------------ v1.8, verbatim

    /** v1.8's `uniformRow`: one row of [color] in the bitmap's raw (premultiplied) byte layout. */
    private fun v18Row(width: Int, color: Int): ByteArray {
        val row = ByteArray(width * 4)
        if (color == 0) return row
        val b = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
        try {
            b.eraseColor(color)
            b.copyPixelsToBuffer(ByteBuffer.wrap(row))
        } finally {
            b.recycle()
        }
        return row
    }

    private var scratch = 0

    /** The bytes v1.8 wrote for a layer of one [color]. */
    private fun v18FilledLayer(w: Int, h: Int, color: Int): ByteArray {
        val f = File(context.cacheDir, "v18-layer-${scratch++}.bin")
        LayerCodec.writeRepeatedRow(f, w, h, v18Row(w, color))
        return f.readBytes().also { f.delete() }
    }

    /** The bytes v1.8 wrote for a layer with [picture]'s pixels. */
    private fun v18PictureLayer(picture: Bitmap): ByteArray {
        val f = File(context.cacheDir, "v18-picture-${scratch++}.bin")
        val buffer = ByteArray(LayerCodec.byteLength(picture.width, picture.height))
        LayerCodec.copyPixels(picture, buffer)
        LayerCodec.write(f, picture.width, picture.height, buffer)
        return f.readBytes().also { f.delete() }
    }

    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** v1.8's thumbnail of a new blank canvas: one erase with the background. */
    private fun v18BlankThumb(w: Int, h: Int, background: Int): ByteArray {
        val (tw, th) = ProjectRepository.thumbnailSize(w, h)
        val thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        try {
            thumb.eraseColor(background)
            return png(thumb)
        } finally {
            thumb.recycle()
        }
    }

    /**
     * v1.8's `project.json` of a new project with the layers [names] (bottom first, ids from 1) and
     * [active], with the id, name, size and times it was given.
     */
    private fun jsonOf(actual: ProjectFileDto, dpi: Float, names: List<String>, active: Int): String =
        ProjectFormat.json.encodeToString(
            ProjectFileDto.serializer(),
            ProjectFileDto(
                id = actual.id,
                name = actual.name,
                width = actual.width,
                height = actual.height,
                dpi = dpi,
                createdAt = actual.createdAt,
                modifiedAt = actual.modifiedAt,
                activeLayerIndex = active,
                layers = names.mapIndexed { i, n ->
                    LayerEntryDto(i + 1L, n, ProjectFormat.defaultProps(n), file = ProjectFormat.layerFile(i + 1L, 0))
                },
            ),
        )

    /** v1.8's `project.json` of a new project: [bottom] under "Layer 1", which is active. */
    private fun v18Json(actual: ProjectFileDto, dpi: Float, bottom: String): String = jsonOf(actual, dpi, listOf(bottom, "Layer 1"), 1)

    private val files = setOf(ProjectFormat.PROJECT_FILE, ProjectFormat.THUMB_FILE, ProjectFormat.layerFile(1, 0), ProjectFormat.layerFile(2, 0))
    private val pictureFiles = setOf(ProjectFormat.PROJECT_FILE, ProjectFormat.THUMB_FILE, ProjectFormat.layerFile(1, 0))

    // ------------------------------------------------------------------ create

    @Test
    fun aBlankCanvasIsWrittenAsInV18() = runBlocking {
        val repo = ProjectRepository(context)
        val cases = listOf(
            NewCanvasSpec("White", 64, 48, 300f),
            NewCanvasSpec("  Clear   sky ", 40, 70, 72f, background = null),
            NewCanvasSpec("Tinted", 700, 300, 350f, background = 0xFF3080C0.toInt()),
            NewCanvasSpec("Half", 33, 17, Float.NaN, background = 0x80FF0000.toInt()),
        )
        for (spec in cases) {
            val why = spec.name
            assertNull(why, spec.start)
            val id = repo.create(spec)
            val dir = dirOf(id)
            assertEquals(why, files, dir.list()!!.toSet())
            val json = File(dir, ProjectFormat.PROJECT_FILE).readText()
            val dto = ProjectFormat.read(dir)
            assertEquals(why, spec.width, dto.width)
            assertEquals(why, spec.height, dto.height)
            val dpi = if (spec.dpi.isFinite() && spec.dpi >= 1f) spec.dpi else 350f
            assertEquals(why, v18Json(dto, dpi, "Background"), json)
            assertFalse("$why: no paper key (I13)", json.contains("\"paper\""))
            assertNull(why, dto.paper)
            assertEquals(why, spec.name.trim().replace(Regex("\\s+"), " "), dto.name)
            val background = spec.background ?: 0
            assertArrayEquals(why, v18FilledLayer(spec.width, spec.height, background), File(dir, ProjectFormat.layerFile(1, 0)).readBytes())
            assertArrayEquals(why, v18FilledLayer(spec.width, spec.height, 0), File(dir, ProjectFormat.layerFile(2, 0)).readBytes())
            assertArrayEquals(why, v18BlankThumb(spec.width, spec.height, background), File(dir, ProjectFormat.THUMB_FILE).readBytes())
            // It opens as v1.8's did.
            val doc = repo.load(id)
            assertEquals(why, listOf("Background", "Layer 1"), doc.layers.map { it.name })
            assertEquals(why, 1, doc.activeLayerIndex)
            assertNull(why, doc.paper)
            for (layer in doc.layers) layer.recycleBitmaps()
        }
    }

    // ------------------------------------------------------------------ createFromImage

    /** An opaque picture with a different color in every pixel, as a PNG in the cache. */
    private fun pictureFile(w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, (0xFF shl 24) or ((x * 5 % 256) shl 16) or ((y * 7 % 256) shl 8) or ((x + y) % 256))
        val f = File(context.cacheDir, "picture-${w}x$h.png")
        f.writeBytes(png(b))
        b.recycle()
        return f
    }

    /** v1.9 A8 (R15): the picture layer and the thumbnail are v1.8's; the empty "Layer 1" is gone. */
    @Test
    fun anImportedPictureIsItsOnlyLayerAsInV18() = runBlocking {
        val repo = ProjectRepository(context)
        for ((w, h) in listOf(48 to 32, 640 to 120)) {
            val why = "${w}x$h"
            val file = pictureFile(w, h)
            val picture = BitmapFactory.decodeFile(file.path)
            val id = repo.createFromImage(Uri.fromFile(file))
            val dir = dirOf(id)
            assertEquals(why, pictureFiles, dir.list()!!.toSet())
            val json = File(dir, ProjectFormat.PROJECT_FILE).readText()
            val dto = ProjectFormat.read(dir)
            assertEquals(why, w, dto.width)
            assertEquals(why, h, dto.height)
            assertEquals(why, jsonOf(dto, 350f, listOf("Picture"), 0), json)
            assertFalse("$why: no paper key (I13)", json.contains("\"paper\""))
            assertArrayEquals(why, v18PictureLayer(picture), File(dir, ProjectFormat.layerFile(1, 0)).readBytes())
            // v1.8: the picture is the thumbnail (scaled down when larger than a thumbnail).
            val (tw, th) = ProjectRepository.thumbnailSize(w, h)
            val thumb = if (tw == w && th == h) picture else Bitmap.createScaledBitmap(picture, tw, th, true)
            assertArrayEquals(why, png(thumb), File(dir, ProjectFormat.THUMB_FILE).readBytes())
            val doc = repo.load(id)
            assertEquals(why, listOf("Picture"), doc.layers.map { it.name })
            assertEquals(why, 0, doc.activeLayerIndex)
            assertNull(why, doc.paper)
            val pixels = IntArray(w * h).also { doc.layers[0].bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val expected = IntArray(w * h).also { picture.getPixels(it, 0, w, 0, 0, w, h) }
            assertArrayEquals(why, expected, pixels)
            for (layer in doc.layers) layer.recycleBitmaps()
        }
    }

    // ------------------------------------------------------------------ the seeds

    @Test
    fun theSeedIsV18WithoutAStart() {
        for (background in listOf(0xFFFFFFFF.toInt(), null, 0xFF3080C0.toInt())) {
            val why = "$background"
            val seed = CanvasCreate.seed(NewCanvasSpec("n", 10, 10, 350f, background, start = null))
            assertEquals(why, listOf("Background", "Layer 1"), seed.layers.map { it.name })
            assertEquals(why, listOf(background ?: 0, 0), seed.layers.map { it.fill })
            assertEquals(why, listOf(null, null), seed.layers.map { it.picture })
            assertEquals(why, 1, seed.activeLayerIndex)
            assertNull(why, seed.paper)
        }
        // v1.9 A8 (R15): a picture alone.
        val picture = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val seed = CanvasCreate.seedFromImage(picture, "Holiday")
        assertEquals(listOf("Picture"), seed.layers.map { it.name })
        assertEquals(listOf(picture), seed.layers.map { it.picture })
        assertEquals(listOf(0), seed.layers.map { it.fill })
        assertEquals(0, seed.activeLayerIndex)
        assertNull(seed.paper)
        assertEquals(CanvasStart.entries, listOf(CanvasStart.FILL, CanvasStart.TRANSPARENT, CanvasStart.PAPER))
    }
}
