package com.brushwork.paint.fonts

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.tools.text.TextFont
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * The user's imported fonts (downloaded from dafont.com and the like), their favorites and the
 * recently used fonts. Files live in app-private storage as `filesDir/fonts/<id>.<ext>` with a
 * JSON index (`index.json`); see [FontImporter] for what an import accepts.
 *
 * Main-safe: the suspend functions do their file work on [io] and update the Compose state
 * ([fonts], [favorites], [recent]) on the caller's thread, serialized by a mutex. [typeface] may
 * be called from any thread (text layout); it loads a font file once and keeps the [Typeface].
 * Use [get] for the shared instance.
 */
class FontStore internal constructor(
    val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limits: FontImportLimits = FontImportLimits(),
) {
    /** Imported fonts, sorted by name. */
    var fonts: List<ImportedFont> by mutableStateOf(emptyList())
        private set

    /** Favorite font keys ([FontIds.keyOf]), in the order they were starred. */
    var favorites: List<String> by mutableStateOf(emptyList())
        private set

    /** Recently used font keys, most recent first. */
    var recent: List<String> by mutableStateOf(emptyList())
        private set

    /** True once the index has been read. */
    var loaded: Boolean by mutableStateOf(false)
        private set

    /** True while an import runs (a reopened font picker still shows it). */
    var importing: Boolean by mutableStateOf(false)
        private set

    private var index = FontIndexData()
    private val mutex = Mutex()
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    fun isFavorite(key: String): Boolean = key in favorites

    fun font(id: String): ImportedFont? = fonts.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ typefaces

    /** The stored file of font [id], or null when there is none (never outside [dir]). */
    fun fileOf(id: String): File? {
        if (!FontIds.isValid(id)) return null
        for (f in FontFormat.entries) {
            val file = File(dir, "$id.${f.ext}")
            if (file.isFile) return file
        }
        return null
    }

    /**
     * The typeface of imported font [id] (plain style; bold / italic are derived from it), or
     * null when its file is missing or unusable. Any thread; one instance per font (the text
     * layout caches rely on it).
     */
    fun typeface(id: String): Typeface? {
        typefaces[id]?.let { return it }
        val file = fileOf(id) ?: return null
        val tf = loadTypeface(file) ?: return null
        val cached = typefaces.putIfAbsent(id, tf) ?: tf
        // Deleted while it was loading (a picker row loads on a background thread): not cached.
        if (!file.exists()) {
            typefaces.remove(id, cached)
            return null
        }
        return cached
    }

    /** Whether imported font [id] can be drawn on this device. */
    fun isAvailable(id: String): Boolean = typeface(id) != null

    // ------------------------------------------------------------------ loading

    /**
     * Reads the index (once) and repairs it: entries whose file is gone are dropped, font files
     * without an entry (an import interrupted by the app being killed) are added back.
     */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val repaired = withContext(io) { readAndRepair() }
        publish(repaired)
        loaded = true
    }

    private fun readAndRepair(): FontIndexData {
        val file = File(dir, INDEX_FILE)
        val stored = FontIndexData.decode(runCatching { if (file.isFile) file.readText() else null }.getOrNull())
        File(dir, FontImporter.STAGING_DIR).deleteRecursively()
        val onDisk = HashMap<String, File>()
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBeforeLast('.')
            val ext = f.name.substringAfterLast('.', "")
            if (f.isFile && FontIds.isValid(id) && FontFormat.entries.any { it.ext == ext }) onDisk[id] = f
        }
        val kept = stored.fonts.filter { it.id in onDisk }
        val known = kept.mapTo(HashSet()) { it.id }
        val names = kept.mapTo(HashSet()) { it.name.lowercase() }
        val adopted = onDisk.filterKeys { it !in known }.mapNotNull { (id, f) ->
            val info = FontFileParser.parse(f) ?: return@mapNotNull null
            val name = FontImporter.uniqueName(info.displayName ?: "Font ${id.take(6)}", names)
            names += name.lowercase()
            ImportedFont(id, name, info.family.orEmpty(), info.subfamily.orEmpty(), f.name, f.name, f.length(), f.lastModified())
        }
        val repaired = stored.copy(fonts = kept + adopted).sanitized()
        if (repaired != stored) writeIndex(repaired)
        return repaired
    }

    // ------------------------------------------------------------------ changes

    /**
     * Imports fonts and zips of fonts (see [FontImporter]); returns what happened. Once started
     * it always finishes and updates the lists (fonts copied to disk are never left out of the
     * index), even when the caller is cancelled meanwhile (e.g. the font picker was closed).
     */
    suspend fun import(sources: List<FontImporter.Source>): FontImportReport = mutex.withLock {
        ensureLoaded()
        withContext(NonCancellable) {
            importing = true
            try {
                val existing = index.fonts
                val (report, next) = withContext(io) {
                    val importer = FontImporter(dir, limits, validator = ::validate)
                    val r = importer.import(sources, existing)
                    val n = index.copy(fonts = index.fonts + r.added).sanitized()
                    if (r.added.isNotEmpty()) writeIndex(n)
                    r to n
                }
                if (report.added.isNotEmpty()) {
                    publish(next)
                    bumpGeneration()
                }
                report
            } finally {
                importing = false
            }
        }
    }

    /** Imports the documents [uris] (Storage Access Framework / "Open with" intents). */
    suspend fun importUris(context: Context, uris: List<Uri>): FontImportReport {
        val cr = context.applicationContext.contentResolver
        val sources = withContext(io) {
            uris.map { uri ->
                val name = runCatching {
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font"
                FontImporter.Source(name) { cr.openInputStream(uri) ?: throw IOException("Can't open $uri") }
            }
        }
        return import(sources)
    }

    /** Deletes imported font [id] (texts using it then show their fallback font). */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        ensureLoaded()
        if (index.fonts.none { it.id == id }) return@withLock false
        val next = index.without(id)
        // File, index and lists always change together (see import).
        withContext(NonCancellable) {
            withContext(io) {
                fileOf(id)?.delete()
                writeIndex(next)
            }
            typefaces.remove(id)
            publish(next)
            bumpGeneration()
        }
        true
    }

    suspend fun setFavorite(key: String, favorite: Boolean) = change { it.withFavorite(key, favorite) }

    suspend fun toggleFavorite(key: String) = change { it.withFavorite(key, key !in it.favorites) }

    /** Records that the font [key] was picked (the "Recent" list). */
    suspend fun markUsed(key: String) = change { it.withRecent(key) }

    private suspend fun change(transform: (FontIndexData) -> FontIndexData) = mutex.withLock {
        ensureLoaded()
        val next = transform(index).sanitized()
        if (next == index) return@withLock
        // Shown at once, then written (off the main thread), also when the caller goes away
        // meanwhile (a star tapped just before closing the picker is still saved).
        publish(next)
        withContext(NonCancellable + io) { writeIndex(next) }
    }

    private fun publish(data: FontIndexData) {
        index = data
        if (fonts != data.fonts) fonts = data.fonts
        if (favorites != data.favorites) favorites = data.favorites
        if (recent != data.recent) recent = data.recent
    }

    /** Atomic write (temp file + rename), so a crash never leaves half an index. */
    private fun writeIndex(data: FontIndexData) {
        try {
            dir.mkdirs()
            val target = File(dir, INDEX_FILE)
            val tmp = File(dir, "$INDEX_FILE.tmp")
            tmp.writeText(FontIndexData.encode(data))
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // The fonts themselves are on disk; load() adopts them again next time.
        }
    }

    companion object {
        const val DIR_NAME = "fonts"
        const val INDEX_FILE = "index.json"

        /** The store text rendering resolves imported fonts with (the one [get] returned last). */
        @Volatile var current: FontStore? = null
            private set

        /**
         * Changes whenever fonts are imported or deleted (or the store changes), so cached text
         * layouts using imported fonts are rebuilt.
         */
        @Volatile var generation: Int = 0
            private set

        @Volatile private var instance: FontStore? = null

        /**
         * App-wide scope (main thread) for imports that must outlive the screen that started them:
         * "Open with Brushwork", or an import whose font picker is closed before it finishes.
         */
        val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

        private fun bumpGeneration() { generation++ }

        /** The shared store of this app ([Context.getFilesDir]/fonts). */
        fun get(context: Context): FontStore {
            val dir = File(context.applicationContext.filesDir, DIR_NAME)
            instance?.takeIf { it.dir == dir }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.dir == dir } ?: FontStore(dir).also { install(it) }
            }
        }

        /** Makes [store] the shared one (tests use a store on their own folder). */
        internal fun install(store: FontStore) {
            instance = store
            current = store
            bumpGeneration()
        }

        /** Typeface of imported font [id] from the current store, or null (missing / no store). */
        fun typefaceFor(id: String): Typeface? = current?.typeface(id)

        /** Typeface of the built-in family [font] (plain style). */
        fun builtIn(font: TextFont): Typeface = Typeface.create(font.family, Typeface.NORMAL)

        /**
         * Font files up to this size are read into memory instead of being mapped, so the file
         * is never held open (it can be moved or deleted at once on any file system).
         */
        private const val IN_MEMORY_MAX = 12L shl 20

        /**
         * Whether Android can load [file] as a font. Android 10+ reports damaged data; older
         * versions silently return the default font instead, which is caught by identity.
         */
        internal fun validate(file: File): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return try {
                    buildFont(file)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            val tf = try { Typeface.createFromFile(file) } catch (e: RuntimeException) { null }
            return tf != null && tf != Typeface.DEFAULT
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun buildFont(file: File): Font =
            if (file.length() <= IN_MEMORY_MAX) Font.Builder(readDirect(file)).build() else Font.Builder(file).build()

        /** The whole file in a direct buffer (what [Font.Builder] needs). */
        private fun readDirect(file: File): ByteBuffer = FileInputStream(file).channel.use { ch ->
            val buf = ByteBuffer.allocateDirect(ch.size().toInt())
            while (buf.hasRemaining()) if (ch.read(buf) < 0) break
            buf.flip()
            buf
        }

        /**
         * The typeface of a stored font file, with the system fonts as fallback for characters
         * it doesn't have (Japanese in a Latin font from dafont, emoji...).
         */
        private fun loadTypeface(file: File): Typeface? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return try {
                    val family = FontFamily.Builder(buildFont(file)).build()
                    Typeface.CustomFallbackBuilder(family).setSystemFallback("sans-serif").build()
                } catch (e: Exception) {
                    null
                }
            }
            val tf = try { Typeface.createFromFile(file) } catch (e: RuntimeException) { null }
            // Loading failed: Android hands back its default font.
            return tf?.takeUnless { it == Typeface.DEFAULT }
        }
    }
}

/** The shared [FontStore] for the current context. */
@Composable
fun rememberFontStore(): FontStore {
    val context = LocalContext.current
    return remember(context) { FontStore.get(context) }
}
