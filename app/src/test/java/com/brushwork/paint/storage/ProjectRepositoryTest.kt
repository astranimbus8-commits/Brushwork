package com.brushwork.paint.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerSnap
import com.brushwork.paint.model.RulerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** Save/load/list/duplicate/export behavior on real Skia bitmaps (Robolectric NATIVE graphics). */
@RunWith(RobolectricTestRunner::class)
class ProjectRepositoryTest {
    private lateinit var context: Context
    private lateinit var repo: ProjectRepository

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "projects").deleteRecursively()
        repo = ProjectRepository(context)
        clearFileProviderCache()
    }

    /**
     * androidx FileProvider caches its root paths in a static map per authority. Robolectric gives
     * every test a new temporary cacheDir but keeps statics across test classes, so a share done
     * by an earlier test (e.g. the editor smoke test) would leave a stale root behind and make
     * getUriForFile reject the new cache path. On a device the cache dir never changes.
     */
    private fun clearFileProviderCache() {
        for (f in androidx.core.content.FileProvider::class.java.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers) || !Map::class.java.isAssignableFrom(f.type)) continue
            f.isAccessible = true
            (f.get(null) as? MutableMap<*, *>)?.let { synchronized(it) { it.clear() } }
        }
    }

    private fun dirOf(id: String) = File(context.filesDir, "projects/$id")

    private fun raw(b: Bitmap): ByteArray = ByteArray(b.byteCount).also { b.copyPixelsToBuffer(ByteBuffer.wrap(it)) }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertArrayEquals(raw(expected), raw(actual))
    }

    /** Two layers with semi-transparent content (a lossy path would fail), a mask and odd props. */
    private fun sampleDoc(id: String = "proj-1", w: Int = 67, h: Int = 45): Document {
        val doc = Document(id, "Sample", w, h, 300f)
        val ink = Layer(doc.newLayerId(), "Ink", BitmapUtils.createLayerBitmap(w, h))
        ink.bitmap.eraseColor(0x80336699.toInt())
        Canvas(ink.bitmap).drawCircle(20f, 20f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FF2200 })
        ink.copyPropsFrom(LayerProps("Ink", 0.5f, LayerBlendMode.MULTIPLY, visible = false, clipping = false, alphaLocked = true, locked = false, maskEnabled = false))
        val color = Layer(doc.newLayerId(), "Color", BitmapUtils.createLayerBitmap(w, h))
        Canvas(color.bitmap).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0x10FF0000, 0xE00000FF.toInt(), Shader.TileMode.CLAMP)
        })
        color.copyPropsFrom(LayerProps("Color", 0.8f, LayerBlendMode.SCREEN, visible = true, clipping = true, alphaLocked = false, locked = true, maskEnabled = true))
        color.mask = BitmapUtils.createMaskBitmap(w, h).also {
            Canvas(it).drawRect(0f, 0f, 30f, h.toFloat(), Paint().apply { this.color = 0xFF404040.toInt() })
        }
        doc.layers += ink
        doc.layers += color
        doc.activeLayerIndex = 0
        doc.colorMode = ColorMode.GRAYSCALE
        doc.grid = GridSettings(enabled = true, type = GridType.ISOMETRIC, spacingPx = 42.5f, unit = LengthUnit.MM, color = 0xFF112233.toInt(), snap = true)
        doc.ruler = RulerSettings(enabled = true, type = RulerType.ELLIPSE, centerX = 12.25f, centerY = 30f, angleDeg = 33f, radiusX = 20f, radiusY = 10f, snap = RulerSnap.ON_RULER, unit = LengthUnit.CM)
        doc.createdAt = 1_700_000_000_000L
        doc.modifiedAt = 1_700_000_500_000L
        return doc
    }

    @Test
    fun saveLoadRoundTripIsLossless() = runBlocking<Unit> {
        val doc = sampleDoc()
        val thumb = Compositor(doc) { null }.renderThumbnail(512)
        repo.save(doc, thumb)
        assertTrue(File(dirOf(doc.id), "thumb.png").isFile)
        doc.layers.forEach { assertEquals(it.contentVersion, it.savedVersion) }

        val loaded = repo.load(doc.id)
        assertEquals("Sample", loaded.name)
        assertEquals(67, loaded.width)
        assertEquals(45, loaded.height)
        assertEquals(300f, loaded.dpi)
        assertEquals(ColorMode.GRAYSCALE, loaded.colorMode)
        assertEquals(doc.grid, loaded.grid)
        assertEquals(doc.ruler, loaded.ruler)
        assertEquals(doc.createdAt, loaded.createdAt)
        assertEquals(doc.modifiedAt, loaded.modifiedAt)
        assertEquals(0, loaded.activeLayerIndex)
        assertEquals(2, loaded.layers.size)
        for (i in doc.layers.indices) {
            val a = doc.layers[i]
            val b = loaded.layers[i]
            assertEquals(a.id, b.id)
            assertEquals(a.props(), b.props())
            assertSamePixels(a.bitmap, b.bitmap)
            assertEquals(a.mask != null, b.mask != null)
            a.mask?.let { assertSamePixels(it, b.mask!!) }
            assertEquals(b.contentVersion, b.savedVersion)
        }
        assertTrue("new layer ids must not collide", loaded.newLayerId() > 2)

        // Saving the loaded document without changes rewrites no pixel file.
        val before = ProjectFormat.read(dirOf(doc.id))
        repo.save(loaded, null)
        val after = ProjectFormat.read(dirOf(doc.id))
        assertEquals(before.layers.map { it.contentFileName }, after.layers.map { it.contentFileName })
    }

    @Test
    fun editableTextAndShapeDataSurviveSaveAndLoad() = runBlocking<Unit> {
        val doc = sampleDoc("proj-data")
        doc.layers[0].shapeData = "{\"type\":\"STAR\",\"box\":[1,2,3,4]}"
        doc.layers[1].textData = "{\"text\":\"Hi\"}"
        repo.save(doc, null)
        val loaded = repo.load(doc.id)
        assertEquals(doc.layers[0].shapeData, loaded.layers[0].shapeData)
        assertEquals(null, loaded.layers[0].textData)
        assertEquals(doc.layers[1].textData, loaded.layers[1].textData)
        assertEquals(null, loaded.layers[1].shapeData)
        // Unchanged pixels (no rewrite) still keep the data.
        repo.save(loaded, null)
        assertEquals(doc.layers[0].shapeData, repo.load(doc.id).layers[0].shapeData)
    }

    @Test
    fun incrementalSaveOnlyRewritesChangedLayers() = runBlocking<Unit> {
        val doc = sampleDoc()
        val (ink, color) = doc.layers
        repo.save(doc, null)
        val dir = dirOf(doc.id)
        val first = ProjectFormat.read(dir)
        val inkFile = File(dir, first.layers[0].contentFileName)
        val colorFile = File(dir, first.layers[1].contentFileName)
        val colorMask = File(dir, first.layers[1].maskFileName)
        assertTrue(inkFile.isFile && colorFile.isFile && colorMask.isFile)

        // Plant a sentinel in the unchanged layer's file: a rewrite would replace it.
        val sentinel = "sentinel".toByteArray()
        inkFile.writeBytes(sentinel)
        Canvas(color.bitmap).drawColor(0xFF00FF00.toInt())
        color.markChanged()
        ink.name = "Renamed ink" // property-only change: no pixel rewrite needed
        repo.save(doc, null)

        val second = ProjectFormat.read(dir)
        assertEquals(first.layers[0].contentFileName, second.layers[0].contentFileName)
        assertArrayEquals(sentinel, inkFile.readBytes())
        assertEquals("Renamed ink", second.layers[0].props.name)
        assertNotEquals(first.layers[1].contentFileName, second.layers[1].contentFileName)
        assertFalse("old revision is deleted", colorFile.exists())
        assertFalse("old mask revision is deleted", colorMask.exists())
        assertEquals(color.contentVersion, color.savedVersion)

        // A changed layer is rewritten; afterwards everything loads back exactly.
        ink.markChanged()
        repo.save(doc, null)
        var loaded = repo.load(doc.id)
        assertSamePixels(ink.bitmap, loaded.layers[0].bitmap)
        assertSamePixels(color.bitmap, loaded.layers[1].bitmap)

        // Removing the mask and a layer deletes their files.
        color.mask = null
        color.markChanged()
        doc.layers.remove(ink)
        doc.activeLayerIndex = 0
        repo.save(doc, null)
        val third = ProjectFormat.read(dir)
        assertEquals(listOf(color.id), third.layers.map { it.id })
        assertFalse(third.layers[0].hasMask)
        val pixelFiles = dir.list()!!.filter { ProjectFormat.isPixelFile(it) }
        assertEquals(listOf(third.layers[0].contentFileName), pixelFiles)
        loaded = repo.load(doc.id)
        assertEquals(1, loaded.layers.size)
        assertEquals(null, loaded.layers[0].mask)
    }

    @Test
    fun resizedDocumentRewritesEveryLayer() = runBlocking<Unit> {
        val doc = sampleDoc()
        repo.save(doc, null)
        // Replace bitmaps with a new size WITHOUT bumping versions: the size check must catch it.
        doc.setSize(30, 20)
        for (l in doc.layers) {
            l.bitmap = BitmapUtils.createLayerBitmap(30, 20).also { it.eraseColor(0x7F102030) }
            l.mask = l.mask?.let { BitmapUtils.createMaskBitmap(30, 20) }
        }
        repo.save(doc, null)
        val loaded = repo.load(doc.id)
        assertEquals(30, loaded.width)
        assertEquals(20, loaded.height)
        for (i in doc.layers.indices) assertSamePixels(doc.layers[i].bitmap, loaded.layers[i].bitmap)
    }

    @Test
    fun createBuildsBackgroundAndEmptyLayer() = runBlocking<Unit> {
        val id = repo.create(NewCanvasSpec("  My   canvas ", 120, 80, 300f, 0xFFFF0000.toInt()))
        val doc = repo.load(id)
        assertEquals("My canvas", doc.name)
        assertEquals(120, doc.width)
        assertEquals(80, doc.height)
        assertEquals(300f, doc.dpi)
        assertEquals(listOf("Background", "Layer 1"), doc.layers.map { it.name })
        assertEquals(1, doc.activeLayerIndex)
        assertEquals(0xFFFF0000.toInt(), doc.layers[0].bitmap.getPixel(119, 79))
        assertEquals(0, doc.layers[1].bitmap.getPixel(5, 5))
        assertTrue(doc.newLayerId() > 2)
        val info = repo.list().single { it.id == id }
        assertEquals(2, info.layerCount)
        val thumb = BitmapFactory.decodeFile(info.thumbnail!!.path)
        assertEquals(120, thumb.width)
        assertEquals(0xFFFF0000.toInt(), thumb.getPixel(3, 3))

        // Transparent and semi-transparent backgrounds are stored exactly like eraseColor().
        val clear = repo.load(repo.create(NewCanvasSpec("Clear", 16, 9, 72f, null)))
        assertEquals(0, clear.layers[0].bitmap.getPixel(0, 0))
        val tinted = repo.load(repo.create(NewCanvasSpec("Tinted", 16, 9, 72f, 0x80FF8000.toInt())))
        val expected = BitmapUtils.createLayerBitmap(16, 9).also { it.eraseColor(0x80FF8000.toInt()) }
        assertSamePixels(expected, tinted.layers[0].bitmap)
        // Large sizes don't need full-size buffers to create.
        val big = repo.create(NewCanvasSpec("Big", 4000, 5000, 350f))
        assertEquals(4000 to 5000, repo.list().single { it.id == big }.let { it.width to it.height })
        val bigThumb = BitmapFactory.decodeFile(File(dirOf(big), "thumb.png").path)
        assertEquals(410 to 512, bigThumb.width to bigThumb.height)
    }

    @Test
    fun listRenameDuplicateDelete() = runBlocking<Unit> {
        val a = repo.create(NewCanvasSpec("A", 40, 30, 350f))
        val b = repo.create(NewCanvasSpec("B", 20, 50, 350f, null))
        // Deterministic order: A modified later than B.
        repo.load(b).also { it.modifiedAt = 1_000L; repo.save(it, null) }
        repo.load(a).also {
            it.modifiedAt = 2_000L
            Canvas(it.layers[1].bitmap).drawRect(2f, 2f, 9f, 9f, Paint().apply { color = 0x99123456.toInt() })
            it.layers[1].markChanged()
            repo.save(it, null)
        }
        assertEquals(listOf(a, b), repo.list().map { it.id })

        val changes = repo.changes.value
        repo.rename(a, "  Sunset  ")
        assertTrue(repo.changes.value > changes)
        assertEquals("Sunset", repo.list().single { it.id == a }.name)
        repo.rename(a, "   ") // ignored
        assertEquals("Sunset", repo.list().single { it.id == a }.name)

        val dup = repo.duplicate(a)
        assertNotEquals(a, dup)
        val dupInfo = repo.list().single { it.id == dup }
        assertEquals("Sunset copy", dupInfo.name)
        assertEquals(40, dupInfo.width)
        assertEquals(2, dupInfo.layerCount)
        assertNotNull(dupInfo.thumbnail)
        val original = repo.load(a)
        val copy = repo.load(dup)
        assertEquals(dup, copy.id)
        for (i in original.layers.indices) assertSamePixels(original.layers[i].bitmap, copy.layers[i].bitmap)
        assertFalse(root().list()!!.any { it.startsWith(".dup") })

        repo.delete(b)
        assertFalse(dirOf(b).exists())
        assertEquals(setOf(a, dup), repo.list().map { it.id }.toSet())
    }

    private fun root() = File(context.filesDir, "projects")

    @Test
    fun corruptProjectsAreSkippedAndReportedClearly() = runBlocking<Unit> {
        val good = repo.create(NewCanvasSpec("Good", 10, 10, 350f))
        val badJson = repo.create(NewCanvasSpec("Bad json", 10, 10, 350f))
        File(dirOf(badJson), "project.json").writeText("{ not json")
        assertEquals(listOf(good), repo.list().map { it.id })
        expectCorrupt("project file") { repo.load(badJson) }

        val truncated = repo.create(NewCanvasSpec("Truncated", 10, 10, 350f))
        val entry = ProjectFormat.read(dirOf(truncated)).layers[0]
        val f = File(dirOf(truncated), entry.contentFileName)
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 4))
        expectCorrupt("Background") { repo.load(truncated) }

        val missing = repo.create(NewCanvasSpec("Missing", 10, 10, 350f))
        File(dirOf(missing), ProjectFormat.read(dirOf(missing)).layers[1].contentFileName).delete()
        expectCorrupt("Layer 1") { repo.load(missing) }

        try {
            repo.load("no-such-project")
            fail("expected FileNotFoundException")
        } catch (e: FileNotFoundException) { /* expected */ }
        try {
            repo.load("../escape")
            fail("expected FileNotFoundException")
        } catch (e: FileNotFoundException) { /* expected */ }
    }

    private suspend fun expectCorrupt(messagePart: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected CorruptProjectException")
        } catch (e: CorruptProjectException) {
            assertTrue("'${e.message}' should mention '$messagePart'", e.message!!.contains(messagePart))
        }
    }

    @Test
    fun createFromImageBuildsPictureAndEmptyLayer() = runBlocking<Unit> {
        val src = BitmapUtils.createLayerBitmap(30, 20)
        Canvas(src).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 30f, 20f, 0xFF2040C0.toInt(), 0x60FFAA00, Shader.TileMode.CLAMP)
        })
        val png = File(context.cacheDir, "import-test.png")
        png.outputStream().use { assertTrue(src.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val uri = Uri.parse("content://com.brushwork.test/pictures/1")
        // ImageImport opens the picture several times (bounds, pixels, EXIF).
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { FileInputStream(png) }

        val id = repo.createFromImage(uri)
        val doc = repo.load(id)
        assertEquals(30 to 20, doc.width to doc.height)
        assertEquals(350f, doc.dpi)
        assertEquals(listOf("Picture", "Layer 1"), doc.layers.map { it.name })
        assertEquals(1, doc.activeLayerIndex)
        assertSamePixels(BitmapFactory.decodeFile(png.path), doc.layers[0].bitmap)
        assertArrayEquals(ByteArray(30 * 20 * 4), raw(doc.layers[1].bitmap))
        val info = repo.list().single { it.id == id }
        assertEquals("Imported picture", info.name)
        assertEquals(30 to 20, BitmapFactory.decodeFile(info.thumbnail!!.path).let { it.width to it.height })
    }

    @Test
    fun wideGamutAndUnpremultipliedBitmapsAreStoredAsSrgb() {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        // Raw Display P3 bytes (R, G, B, A in memory) of a mid color: sRGB bytes must differ.
        val p3 = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        val p3Bytes = ByteArray(4 * 4 * 4) { i -> byteArrayOf(0x80.toByte(), 0xA0.toByte(), 0x33, 0xFF.toByte())[i % 4] }
        p3.copyPixelsFromBuffer(ByteBuffer.wrap(p3Bytes))
        assertFalse(LayerCodec.isStorable(p3))
        val converted = LayerCodec.toStorable(p3)
        assertEquals(srgb, converted.colorSpace)
        assertTrue(LayerCodec.isStorable(converted))
        val stored = ByteArray(p3Bytes.size).also { LayerCodec.copyPixels(p3, it) }
        assertArrayEquals(raw(converted), stored)
        assertFalse("P3 bytes must be converted, not copied", stored.contentEquals(p3Bytes))

        // Unpremultiplied pixels are stored premultiplied.
        val unpremul = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        unpremul.isPremultiplied = false
        unpremul.eraseColor(0x80FF0000.toInt())
        assertFalse(LayerCodec.isStorable(unpremul))
        val out = ByteArray(2 * 2 * 4).also { LayerCodec.copyPixels(unpremul, it) }
        val premultiplied = BitmapUtils.createLayerBitmap(2, 2).also { it.eraseColor(0x80FF0000.toInt()) }
        assertArrayEquals(raw(premultiplied), out)
        assertFalse(out.contentEquals(raw(unpremul)))

        // Ordinary layer bitmaps are copied byte for byte.
        val plain = BitmapUtils.createLayerBitmap(3, 3).also { it.eraseColor(0x40336699) }
        assertTrue(LayerCodec.isStorable(plain))
        assertArrayEquals(raw(plain), ByteArray(36).also { LayerCodec.copyPixels(plain, it) })
    }

    @Test
    fun nanSettingsDoNotBreakSaving() = runBlocking<Unit> {
        val doc = sampleDoc()
        doc.dpi = Float.NaN
        doc.layers[0].opacity = Float.NaN
        doc.layers[1].opacity = 3f
        doc.grid = doc.grid.copy(spacingPx = Float.NaN)
        repo.save(doc, null)
        val loaded = repo.load(doc.id)
        assertEquals(350f, loaded.dpi)
        assertEquals(1f, loaded.layers[0].opacity)
        assertEquals(1f, loaded.layers[1].opacity)
        assertTrue(loaded.grid.spacingPx.isNaN())
        assertSamePixels(doc.layers[0].bitmap, loaded.layers[0].bitmap)
    }

    @Test
    fun jpegIsFlattenedOntoWhiteAndPngIsLossless() {
        val bmp = BitmapUtils.createLayerBitmap(16, 16)
        Canvas(bmp).drawRect(0f, 0f, 8f, 16f, Paint().apply { color = 0xFF000000.toInt() })
        val jpeg = ByteArrayOutputStream().also { ImageExport.encode(bmp, ExportFormat.JPEG, it) }.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val px = decoded.getPixel(13, 8)
        assertTrue("transparent area must become white, was ${Integer.toHexString(px)}", (px and 0xFF) > 240 && ((px shr 8) and 0xFF) > 240)
        assertTrue((decoded.getPixel(2, 8) and 0xFF) < 16)

        val opaque = BitmapUtils.createLayerBitmap(16, 16).also { b ->
            for (y in 0 until 16) for (x in 0 until 16) b.setPixel(x, y, 0xFF000000.toInt() or (x * 16 shl 16) or (y * 16 shl 8) or 77)
        }
        val png = ByteArrayOutputStream().also { ImageExport.encode(opaque, ExportFormat.PNG, it) }.toByteArray()
        val back = BitmapFactory.decodeByteArray(png, 0, png.size)
        for (y in 0 until 16) for (x in 0 until 16) assertEquals(opaque.getPixel(x, y), back.getPixel(x, y))
        assertEquals("My_ art_work", ImageExport.safeFileName("My: art/work"))
        assertEquals("Brushwork", ImageExport.safeFileName(" ..  "))
    }

    @Test
    fun shareExportWritesFlattenedImage() = runBlocking<Unit> {
        val id = repo.create(NewCanvasSpec("Share me", 24, 12, 350f, 0xFF00AA00.toInt()))
        val uri = repo.shareProject(id, ExportFormat.PNG)
        // FileProvider matches roots with '/' separators, so it can only map paths on a
        // Unix-like host (always true on a device); the file is written either way.
        if (File.separatorChar == '/') assertNotNull(uri)
        val file = File(context.cacheDir, "exports/Share me.png")
        assertTrue(file.isFile)
        val img = BitmapFactory.decodeFile(file.path)
        assertEquals(24, img.width)
        assertEquals(0xFF00AA00.toInt(), img.getPixel(20, 10))
        assertEquals(null, repo.shareProject("missing-project"))
    }
}
