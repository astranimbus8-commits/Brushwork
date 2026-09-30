package com.brushwork.paint.fonts

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.MainActivity
import com.brushwork.paint.fonts.TestFonts.write
import com.brushwork.paint.tools.text.TextFont
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration

/**
 * Importing fonts (real fonts from Robolectric's runtime, real Typeface loading): dafont-style
 * zips with folders and junk, damaged and duplicate fonts, limits, persistence of the index,
 * favorites and recent fonts, and "Open with Brushwork".
 */
@RunWith(RobolectricTestRunner::class)
class FontImportRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val arvo = TestFonts.real("Arvo-Regular.ttf")
    private val coming = TestFonts.real("ComingSoon.ttf")
    private val dancing = TestFonts.real("DancingScript-Regular.ttf")

    private fun store(limits: FontImportLimits = FontImportLimits()): FontStore {
        val dir = File(context.filesDir, "fonts-${System.nanoTime()}")
        return FontStore(dir, limits = limits).also { FontStore.install(it) }
    }

    private fun src(name: String, bytes: ByteArray) = FontImporter.Source(name) { ByteArrayInputStream(bytes) }

    /** An AppleDouble ("._name") file as macOS puts into zips: not a font. */
    private val appleDouble = ByteArray(120).also { it[0] = 0; it[1] = 5; it[2] = 0x16; it[3] = 7 }

    @Test
    fun aDafontZipWithFoldersJunkDamagedAndDuplicateFonts() = runBlocking<Unit> {
        val s = store()
        val zip = TestFonts.zip(
            "readme.txt" to "Free for personal use. For commercial use contact the author.".toByteArray(),
            "Arvo/" to ByteArray(0),
            "Arvo/Regular/Arvo-Regular.ttf" to arvo,
            "__MACOSX/Arvo/Regular/._Arvo-Regular.ttf" to appleDouble,
            "Arvo/Regular/._Arvo-Regular.ttf" to appleDouble,
            "ComingSoon.TTF" to coming,
            "copy/ComingSoon.ttf" to coming,
            "broken.ttf" to "this is not a font".toByteArray(),
            "damaged.ttf" to arvo.copyOf(2000),
            "web/Arvo.woff" to "wOFF0000rest of a web font".toByteArray(),
            "preview.png" to byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3),
            // Zip-slip: the entry name is never used as a path.
            "../../evil.ttf" to dancing,
        )
        val r = s.import(listOf(src("arvo.zip", zip)))
        val names = r.added.map { it.name }
        assertEquals("three fonts: $names", 3, r.added.size)
        assertTrue(names.any { it.startsWith("Arvo") } && names.any { it.startsWith("Coming Soon") } && names.any { it.startsWith("Dancing Script") })
        assertEquals("the second copy is a duplicate", 1, r.duplicates.size)
        assertEquals(listOf("broken.ttf", "damaged.ttf"), r.invalid)
        assertEquals(listOf("Arvo.woff"), r.unsupported)
        assertTrue(r.notFonts.containsAll(listOf("readme.txt", "preview.png")))
        assertTrue("macOS metadata is skipped silently", (r.invalid + r.notFonts).none { it.startsWith("._") })
        assertFalse(r.limitReached)
        assertTrue(r.message, r.message.startsWith("Added 3 fonts"))

        // Stored as <hash>.ttf inside the font folder, nothing else (no staging leftovers).
        for (f in r.added) {
            assertEquals("${f.id}.ttf", f.file)
            assertTrue(File(s.dir, f.file).isFile)
            assertTrue(FontIds.isValid(f.id))
        }
        assertEquals((r.added.map { it.file } + FontStore.INDEX_FILE).toSet(), s.dir.list()!!.toSet())
        assertFalse(File(s.dir.parentFile, "evil.ttf").exists())
        assertFalse(File(s.dir.parentFile!!.parentFile, "evil.ttf").exists())
        assertEquals(r.added.sortedBy { it.name.lowercase() }, s.fonts)

        // Every font loads, draws differently from the default font, and is cached once.
        val p = Paint().apply { textSize = 40f }
        val plain = p.measureText("Hello World")
        for (f in r.added) {
            val tf = requireNotNull(s.typeface(f.id))
            assertSame(tf, s.typeface(f.id))
            p.typeface = tf
            assertNotEquals(f.name, plain, p.measureText("Hello World"), 0.5f)
        }

        // The same zip again: nothing new.
        val again = s.import(listOf(src("arvo (1).zip", zip)))
        assertTrue(again.added.isEmpty())
        assertEquals(4, again.duplicates.size)
        assertTrue(again.message, again.message.contains("already imported"))
        assertEquals(3, s.fonts.size)
    }

    @Test
    fun zipsFromOldWindowsToolsWithNonUtf8Names() = runBlocking<Unit> {
        val s = store()
        val zip = TestFonts.zip("Schrift/Bücher Ä.ttf" to coming, "Lies mich ü.txt" to "x".toByteArray(), charset = java.nio.charset.Charset.forName("IBM437"))
        val r = s.import(listOf(src("alt.zip", zip)))
        assertEquals(r.message, 1, r.added.size)
        assertTrue(r.added[0].name.startsWith("Coming Soon"))
    }

    @Test
    fun singleFilesWebFontsAndNonFonts() = runBlocking<Unit> {
        val s = store()
        val r = s.import(listOf(src("Arvo-Regular.ttf", arvo)))
        assertTrue(r.message, r.message.startsWith("Added the font Arvo"))
        val dup = s.import(listOf(src("Arvo copy.ttf", arvo)))
        assertTrue(dup.message, dup.message.contains("is already imported"))
        val none = s.import(listOf(src("notes.txt", "hello".toByteArray())))
        assertTrue(none.message, none.message.startsWith("No fonts found"))
        val web = s.import(listOf(src("font.woff2", "wOF2xxxx".toByteArray())))
        assertEquals(listOf("font.woff2"), web.unsupported)
        val bad = s.import(listOf(src("font.otf", ByteArray(500) { 7 })))
        assertEquals(listOf("font.otf"), bad.invalid)
        val unreadable = s.import(listOf(FontImporter.Source("gone.ttf") { throw java.io.FileNotFoundException("gone") }))
        assertEquals(listOf("gone.ttf"), unreadable.unreadable)
        assertEquals(1, s.fonts.size)
    }

    @Test
    fun hugeFilesAndArchivesAreRefused() = runBlocking<Unit> {
        val small = store(FontImportLimits(maxFontBytes = 10_000))
        val r = small.import(listOf(src("Arvo-Regular.ttf", arvo), src("fonts.zip", TestFonts.zip("a/Arvo.ttf" to arvo))))
        assertEquals(listOf("Arvo-Regular.ttf", "Arvo.ttf"), r.tooBig)
        assertTrue(r.added.isEmpty())
        // A "zip bomb": megabytes of zeros compress to almost nothing.
        val tight = store(FontImportLimits(maxTotalBytes = 1_000_000))
        val bomb = TestFonts.zip("big.bin" to ByteArray(8_000_000), "Arvo.ttf" to arvo)
        assertTrue(bomb.size < 100_000)
        val b = tight.import(listOf(src("bomb.zip", bomb)))
        assertTrue(b.limitReached)
        assertTrue(b.added.isEmpty())
        assertTrue(b.message, b.message.contains("too large"))
        // At most so many fonts per import.
        val few = store(FontImportLimits(maxFonts = 1))
        val f = few.import(listOf(src("two.zip", TestFonts.zip("a.ttf" to arvo, "b.ttf" to coming))))
        assertEquals(1, f.added.size)
        assertTrue(f.limitReached)
    }

    @Test
    fun favoritesRecentFontsAndDeletingArePersisted() = runBlocking<Unit> {
        val s = store()
        val r = s.import(listOf(src("Arvo-Regular.ttf", arvo), src("ComingSoon.ttf", coming)))
        val arvoFont = r.added.first { it.name.startsWith("Arvo") }
        val arvoKey = FontIds.keyOf(arvoFont.id)
        val serifKey = FontIds.keyOf(TextFont.SERIF)
        val casualKey = FontIds.keyOf(TextFont.CASUAL)
        s.toggleFavorite(serifKey)
        s.toggleFavorite(arvoKey)
        s.markUsed(casualKey)
        s.markUsed(arvoKey)
        assertEquals(listOf(serifKey, arvoKey), s.favorites)
        assertEquals(listOf(arvoKey, casualKey), s.recent)
        assertTrue(s.isFavorite(arvoKey))

        // The app restarts: a new store on the same folder reads everything back.
        val s2 = FontStore(s.dir)
        s2.load()
        assertEquals(s.fonts, s2.fonts)
        assertEquals(listOf(serifKey, arvoKey), s2.favorites)
        assertEquals(listOf(arvoKey, casualKey), s2.recent)
        s2.toggleFavorite(serifKey)
        assertEquals(listOf(arvoKey), s2.favorites)

        // Deleting a font removes its file, its star and its recent entry.
        assertNotNull(s2.typeface(arvoFont.id))
        assertTrue(s2.delete(arvoFont.id))
        assertFalse(s2.delete(arvoFont.id))
        assertNull(s2.fileOf(arvoFont.id))
        assertNull(s2.typeface(arvoFont.id))
        assertTrue(arvoKey !in s2.favorites && arvoKey !in s2.recent)
        val s3 = FontStore(s.dir)
        s3.load()
        assertEquals(listOf("Coming Soon"), s3.fonts.map { it.name.take(11) })
        assertTrue(s3.favorites.isEmpty())
        assertEquals(listOf(casualKey), s3.recent)
    }

    @Test
    fun loadingRepairsALostOrStaleIndex() = runBlocking<Unit> {
        val s = store()
        s.import(listOf(src("a.ttf", arvo), src("c.ttf", coming)))
        // Killed between copying the fonts and writing the index: the files are adopted again.
        File(s.dir, FontStore.INDEX_FILE).delete()
        File(s.dir, "notes.txt").writeText("not a font")
        File(s.dir, FontImporter.STAGING_DIR).mkdirs()
        val s2 = FontStore(s.dir)
        s2.load()
        assertEquals(s.fonts.map { it.id }.toSet(), s2.fonts.map { it.id }.toSet())
        assertTrue(s2.fonts.any { it.name.startsWith("Arvo") })
        assertFalse(File(s.dir, FontImporter.STAGING_DIR).exists())
        // A font file deleted behind the app's back disappears from the list.
        File(s.dir, s2.fonts[0].file).delete()
        val s3 = FontStore(s.dir)
        s3.load()
        assertEquals(1, s3.fonts.size)
        // Ids are checked before touching files.
        assertNull(s3.fileOf("../${s3.dir.name}/index"))
        assertNull(s3.typeface("not-a-hash"))
    }

    @Test
    fun documentsAreImportedByUri() = runBlocking<Unit> {
        val s = store()
        val f = File(context.cacheDir, "Download/Arvo-Regular.ttf").write(arvo)
        val z = File(context.cacheDir, "Download/coming_soon.zip").write(TestFonts.zip("ComingSoon/ComingSoon.ttf" to coming))
        val r = s.importUris(context, listOf(Uri.fromFile(f), Uri.fromFile(z), Uri.fromFile(File(context.cacheDir, "missing.ttf"))))
        assertEquals(2, r.added.size)
        assertEquals(1, r.unreadable.size)
    }

    @Test
    fun openWithBrushworkIntents() {
        val a = Uri.parse("content://com.android.providers.downloads.documents/document/12")
        val b = Uri.parse("content://media/external/file/7")
        assertEquals(listOf(a), FontOpenIntent.uris(Intent(Intent.ACTION_VIEW, a)))
        assertEquals(listOf(a), FontOpenIntent.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a)))
        assertEquals(listOf(a, b), FontOpenIntent.uris(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))))
        assertTrue(FontOpenIntent.uris(Intent(Intent.ACTION_MAIN)).isEmpty())
        assertTrue(FontOpenIntent.uris(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dafont.com/x.zip"))).isEmpty())
        assertTrue(FontOpenIntent.uris(null).isEmpty())

        // Opening a font file with the app imports it, says so and closes again.
        File(context.filesDir, FontStore.DIR_NAME).deleteRecursively()
        val f = File(context.cacheDir, "Download/ComingSoon.ttf").write(coming)
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(f)).setClass(context, MainActivity::class.java)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        val end = System.currentTimeMillis() + 20_000
        while (!activity.isFinishing && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertTrue("closes after importing", activity.isFinishing)
        val toast = ShadowToast.getTextOfLatestToast()
        assertTrue(toast, toast.startsWith("Added the font Coming Soon"))
        assertEquals(1, FontStore.get(context).fonts.size)

        // A normal launch is not taken.
        val plain = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertFalse(FontOpenIntent.handle(plain, Intent(Intent.ACTION_MAIN)))
    }
}
