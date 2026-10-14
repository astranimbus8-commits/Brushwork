package com.brushwork.paint.storage.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v1.9 A8 (R16 (b)): the backup folder's layout. Under the picked folder (or the folder itself,
 * see [BackupStateData.pickedIsRoot]):
 *
 * ```
 * Brushwork backup/
 *   brushwork-backup.json      the index (BackupIndexData, format 1)
 *   settings.json              settings, brushes, palettes, the imported fonts' list (BackupSettings)
 *   fonts/<id>.<ext>           the imported font files
 *   <projectId>/               one artwork: project.json, thumb.png and its immutable data files
 *   deleted/<projectId>/       an artwork deleted in the gallery, kept 30 days
 * ```
 */
internal object BackupLayout {
    const val ROOT_NAME = "Brushwork backup"
    const val INDEX = "brushwork-backup.json"
    const val SETTINGS = "settings.json"
    const val FONTS = "fonts"
    const val DELETED = "deleted"
    /** The replacement `project.json` is written under this name first (R16 (b) write order). */
    const val NEW_SUFFIX = ".new"
    const val INDEX_FORMAT = 1
    const val APP = "Brushwork"

    /** How long a deleted artwork is kept under [DELETED]. */
    const val DELETED_KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000

    /** Below this much free space on the folder's volume, deleted artworks are purged oldest first. */
    const val MIN_FREE_BYTES = 500L shl 20

    /** The buffer of every copy to and from the folder (R16 (c)). */
    const val BUFFER = 256 * 1024

    const val MIME_JSON = "application/json"
    const val MIME_PNG = "image/png"
    const val MIME_DATA = "application/octet-stream"

    /** The folder names under the root that are not artworks. */
    val RESERVED = setOf(DELETED, FONTS)
}

/** One artwork in the index. [size] counts its project file, thumbnail and data files. */
@Serializable
internal data class BackupIndexEntry(
    val id: String,
    val name: String,
    val modifiedAt: Long = 0L,
    val revision: Long = 0L,
    val size: Long = 0L,
)

/** One artwork under `deleted/`, with when it was deleted (the 30-day rule counts from it). */
@Serializable
internal data class BackupDeletedEntry(
    val id: String,
    val name: String,
    val deletedAt: Long = 0L,
)

/** `brushwork-backup.json`: format 1, the app that wrote it, and the artworks it holds. */
@Serializable
internal data class BackupIndexData(
    val format: Int = BackupLayout.INDEX_FORMAT,
    val app: String = BackupLayout.APP,
    val appVersion: String = "",
    val updatedAt: Long = 0L,
    val artworks: List<BackupIndexEntry> = emptyList(),
    val deleted: List<BackupDeletedEntry> = emptyList(),
)

/** v1.9 A8: the index's JSON (compact, so it starts with `{"format":1`). */
internal object BackupIndex {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encode(data: BackupIndexData): ByteArray = json.encodeToString(BackupIndexData.serializer(), data).toByteArray(Charsets.UTF_8)

    /** The index, or null when it is damaged or of a newer format this version cannot read. */
    fun decode(bytes: ByteArray): BackupIndexData? = try {
        json.decodeFromString(BackupIndexData.serializer(), String(bytes, Charsets.UTF_8)).takeIf { it.format <= BackupLayout.INDEX_FORMAT }
    } catch (e: Exception) {
        null
    }
}
