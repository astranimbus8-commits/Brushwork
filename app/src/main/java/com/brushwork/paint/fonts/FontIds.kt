package com.brushwork.paint.fonts

import com.brushwork.paint.tools.text.TextFont

/**
 * Ids, keys and names of fonts (pure Kotlin).
 *
 * An imported font is identified by the SHA-256 of its file ([idOf], lowercase hex, first
 * [ID_LENGTH] characters): the same file imported twice gets the same id, and the id is the
 * file's name in the font folder, so it must never be taken from outside unchecked ([isValid]).
 */
object FontIds {
    const val ID_LENGTH = 32

    private val ID = Regex("^[0-9a-f]{16,64}$")

    /** Whether [id] is a well-formed font id (and therefore safe to use as a file name). */
    fun isValid(id: String?): Boolean = id != null && ID.matches(id)

    /** Font id of a file whose SHA-256 digest is [sha256]. */
    fun idOf(sha256: ByteArray): String {
        val sb = StringBuilder(sha256.size * 2)
        for (b in sha256) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.substring(0, minOf(ID_LENGTH, sb.length))
    }

    private const val HEX = "0123456789abcdef"

    const val MAX_NAME_LENGTH = 80

    /** A display name cleaned up for the UI: control characters removed, spaces collapsed, capped. */
    fun cleanName(name: String): String? {
        val s = buildString(name.length) {
            for (ch in name) append(if (ch.isISOControl() || ch == '�' || ch == '\u0000') ' ' else ch)
        }.trim().replace(Regex("\\s+"), " ")
        return s.take(MAX_NAME_LENGTH).trim().ifEmpty { null }
    }

    /** A readable name from a file name ("Some_Font-Bold.ttf" -> "Some Font Bold"). */
    fun nameFromFile(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return cleanName(base.replace('_', ' ').replace('-', ' ')) ?: "Font"
    }

    // ------------------------------------------------------------------ favorites / recent keys

    private const val BUILT_IN = "builtin:"
    private const val FILE = "file:"

    /** Key of a built-in family in the favorites / recent lists. */
    fun keyOf(font: TextFont): String = BUILT_IN + font.name

    /** Key of an imported font in the favorites / recent lists. */
    fun keyOf(id: String): String = FILE + id

    /** The built-in family of [key], or null. */
    fun builtInOf(key: String): TextFont? =
        if (key.startsWith(BUILT_IN)) TextFont.entries.firstOrNull { it.name == key.removePrefix(BUILT_IN) } else null

    /** The imported font id of [key], or null. */
    fun importedOf(key: String): String? = if (key.startsWith(FILE)) key.removePrefix(FILE).takeIf { isValid(it) } else null
}
