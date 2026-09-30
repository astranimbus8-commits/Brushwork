package com.brushwork.paint.fonts

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Limits that keep a broken or hostile file from filling memory, storage or time: every byte read
 * (inflated zip data included) counts towards [maxTotalBytes].
 */
data class FontImportLimits(
    /** Largest single font file (big CJK fonts are ~20 MB). */
    val maxFontBytes: Long = 32L shl 20,
    /** Everything read in one import, all files together. */
    val maxTotalBytes: Long = 192L shl 20,
    /** Zip entries looked at per archive. */
    val maxEntries: Int = 5000,
    /** Font files accepted per import. */
    val maxFonts: Int = 300,
)

/**
 * Imports font files (pure JVM: java.io + java.util.zip) into [fontDir]:
 * - a font file (.ttf / .otf / .ttc, recognized by its header, whatever its name) is copied;
 * - a zip (how dafont.com serves fonts) is scanned entry by entry, in any sub-folder, and every
 *   font in it is copied; read-me files, pictures, `__MACOSX/` and `._name` (macOS metadata)
 *   entries are skipped. Entry names are NEVER used as paths (no zip-slip): a font is stored as
 *   `<id>.<ext>` where the id is its content hash, which also finds duplicates;
 * - every font is checked by [FontFileParser] (a real font with a character map and glyphs) and
 *   then by [validator] (Android can load it), else it is skipped as damaged.
 * Blocking: call on a background thread.
 */
class FontImporter(
    private val fontDir: File,
    private val limits: FontImportLimits = FontImportLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val validator: (File) -> Boolean,
) {
    /** A file to import: its name (for messages and the fallback font name) and how to read it. */
    class Source(val name: String, val open: () -> InputStream)

    /**
     * Imports [sources]; fonts whose id is in [existing] are reported as duplicates. Returns what
     * was added (already stored in [fontDir]) and what was skipped and why.
     */
    fun import(sources: List<Source>, existing: Collection<ImportedFont>): FontImportReport {
        if (!fontDir.isDirectory && !fontDir.mkdirs()) return FontImportReport(unreadable = sources.map { it.name })
        val staging = File(fontDir, STAGING_DIR)
        staging.deleteRecursively()
        staging.mkdirs()
        val st = State(staging, existing)
        try {
            for (s in sources) {
                if (st.limitReached) break
                try {
                    s.open().use { importStream(s.name, it, st) }
                } catch (e: IOException) {
                    st.unreadable += s.name
                } catch (e: SecurityException) {
                    st.unreadable += s.name
                } catch (e: IllegalArgumentException) {
                    // Malformed zip entry names (bad UTF-8) on some platforms.
                    st.invalid += s.name
                }
            }
        } finally {
            staging.deleteRecursively()
        }
        return st.report()
    }

    private class State(val staging: File, existing: Collection<ImportedFont>) {
        val names = existing.mapTo(HashSet()) { it.name.lowercase() }
        val nameOfId = existing.associateTo(HashMap()) { it.id to it.name }
        val added = ArrayList<ImportedFont>()
        val duplicates = ArrayList<String>()
        val invalid = ArrayList<String>()
        val unsupported = ArrayList<String>()
        val tooBig = ArrayList<String>()
        val notFonts = ArrayList<String>()
        val unreadable = ArrayList<String>()
        var limitReached = false
        var total = 0L
        var fonts = 0
        var counter = 0

        fun report() = FontImportReport(added, duplicates, invalid, unsupported, tooBig, notFonts, unreadable, limitReached)
    }

    private fun importStream(name: String, raw: InputStream, st: State) {
        val input = BufferedInputStream(raw, BUFFER)
        input.mark(8)
        val head = ByteArray(4)
        val n = readUpTo(input, head)
        input.reset()
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && (head[2].toInt() == 3 || head[2].toInt() == 5)) {
            importZip(name, input, st)
        } else {
            importOne(displayName(name), input, st, inZip = false)
        }
    }

    private fun importZip(name: String, input: InputStream, st: State) {
        // Names flagged as UTF-8 are read as UTF-8; others (old Windows zips, code page 437)
        // as Latin-1, which never fails to decode (the names only matter for messages).
        val zin = ZipInputStream(input, Charsets.ISO_8859_1)
        var entries = 0
        var found = false
        while (!st.limitReached) {
            val e = try {
                zin.nextEntry
            } catch (ex: ZipException) {
                // Damaged archive, or an entry name Android refuses ("../"): stop reading it.
                if (!found) st.invalid += name
                return
            } ?: break
            if (++entries > limits.maxEntries) { st.limitReached = true; return }
            if (e.isDirectory) continue
            val path = e.name.replace('\\', '/')
            val base = path.substringAfterLast('/')
            val macMeta = base.startsWith("._") || path.startsWith("__MACOSX/") || path.contains("/__MACOSX/")
            if (base.isEmpty() || macMeta) {
                drain(zin, st)
                continue
            }
            val before = st.added.size + st.duplicates.size
            importOne(displayName(base), zin, st, inZip = true)
            if (st.added.size + st.duplicates.size > before) found = true
        }
    }

    /** Reads one file (a zip entry or a whole source) and keeps it if it is a new, usable font. */
    private fun importOne(name: String, input: InputStream, st: State, inZip: Boolean) {
        val head = ByteArray(4)
        val n = readUpTo(input, head)
        st.total += n
        val format = FontFileParser.sniff(head, n)
        if (format == null) {
            when {
                n >= 4 && isWebFont(head) -> st.unsupported += name
                hasFontExtension(name) -> st.invalid += name
                else -> st.notFonts += name
            }
            if (inZip) drain(input, st)
            return
        }
        if (st.fonts >= limits.maxFonts) { st.limitReached = true; return }
        st.fonts++
        val tmp = File(st.staging, "font${st.counter++}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(head, 0, n)
        var size = n.toLong()
        var tooBig = false
        FileOutputStream(tmp).use { out ->
            out.write(head, 0, n)
            val buf = ByteArray(BUFFER)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                st.total += r
                if (st.total > limits.maxTotalBytes) { st.limitReached = true; break }
                size += r
                if (size > limits.maxFontBytes) { tooBig = true; break }
                digest.update(buf, 0, r)
                out.write(buf, 0, r)
            }
        }
        if (st.limitReached) { tmp.delete(); return }
        if (tooBig) {
            tmp.delete()
            st.tooBig += name
            if (inZip) drain(input, st)
            return
        }
        accept(tmp, digest.digest(), name, size, st)
    }

    private fun accept(tmp: File, sha: ByteArray, name: String, size: Long, st: State) {
        val id = FontIds.idOf(sha)
        val known = st.nameOfId[id]
        if (known != null) {
            tmp.delete()
            st.duplicates += known
            return
        }
        val info = FontFileParser.parse(tmp)
        val ok = info != null && try { validator(tmp) } catch (e: Exception) { false }
        if (info == null || !ok) {
            tmp.delete()
            st.invalid += name
            return
        }
        val target = File(fontDir, "$id.${info.format.ext}")
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            st.unreadable += name
            return
        }
        val display = uniqueName(info.displayName ?: FontIds.nameFromFile(name), st.names)
        st.names += display.lowercase()
        st.nameOfId[id] = display
        st.added += ImportedFont(
            id = id,
            name = display,
            family = info.family?.let { FontIds.cleanName(it) }.orEmpty(),
            style = info.subfamily?.let { FontIds.cleanName(it) }.orEmpty(),
            file = target.name,
            source = name,
            bytes = size,
            addedAt = clock(),
        )
    }

    /** Reads the rest of a zip entry (counted, so a huge entry can't run forever). */
    private fun drain(input: InputStream, st: State) {
        val buf = ByteArray(BUFFER)
        while (!st.limitReached) {
            val r = input.read(buf)
            if (r < 0) return
            st.total += r
            if (st.total > limits.maxTotalBytes) st.limitReached = true
        }
    }

    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var n = 0
        while (n < into.size) {
            val r = input.read(into, n, into.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    companion object {
        /** Temporary folder inside the font folder (same file system, so moving is a rename). */
        const val STAGING_DIR = ".staging"
        private const val BUFFER = 64 * 1024

        private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

        fun hasFontExtension(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS

        private fun isWebFont(h: ByteArray): Boolean =
            h[0] == 'w'.code.toByte() && h[1] == 'O'.code.toByte() && h[2] == 'F'.code.toByte() && (h[3] == 'F'.code.toByte() || h[3] == '2'.code.toByte())

        /** Just the file name part (entry names may contain folders, or anything at all). */
        private fun displayName(name: String): String =
            name.replace('\\', '/').substringAfterLast('/').let { FontIds.cleanName(it) } ?: "font"

        /** [base], or "[base] (2)", "(3)"... when a font with that name exists (case-insensitive). */
        fun uniqueName(base: String, taken: Set<String>): String {
            if (base.lowercase() !in taken) return base
            var n = 2
            while (true) {
                val candidate = "$base ($n)"
                if (candidate.lowercase() !in taken) return candidate
                n++
            }
        }
    }
}
