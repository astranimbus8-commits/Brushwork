package com.brushwork.paint.storage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.brushwork.paint.model.Document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class ProjectInfo(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Float,
    val layerCount: Int,
    val modifiedAt: Long,
    /** PNG thumbnail file, or null if none yet. */
    val thumbnail: File?,
)

/** Parameters of a new blank canvas. [background] null = transparent. */
data class NewCanvasSpec(
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Float,
    val background: Int? = 0xFFFFFFFF.toInt(),
)

enum class ExportFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
}

// STUB — replaced by the storage module. Keep the public API.
/**
 * Stores projects in app-private storage (one folder per project) and exports images.
 * All suspend functions are main-safe (they switch to IO internally). [save] must be called
 * from the main thread's coroutine context because it reads layer bitmaps.
 */
class ProjectRepository(private val context: Context) {
    /** Incremented whenever the project list changes (gallery observes it). */
    val changes: StateFlow<Int> = MutableStateFlow(0)

    suspend fun list(): List<ProjectInfo> = emptyList()
    suspend fun create(spec: NewCanvasSpec): String = TODO()
    suspend fun createFromImage(uri: Uri): String = TODO()
    suspend fun load(id: String): Document = TODO()
    suspend fun save(doc: Document, thumbnail: Bitmap?) {}
    suspend fun delete(id: String) {}
    suspend fun duplicate(id: String): String = TODO()
    suspend fun rename(id: String, name: String) {}
    /** Saves [bitmap] to the device gallery (Pictures/Brushwork). Returns the content Uri. */
    suspend fun exportToGallery(bitmap: Bitmap, name: String, format: ExportFormat): Uri? = null
    /** Writes [bitmap] to a shareable cache file and returns a FileProvider Uri. */
    suspend fun exportForShare(bitmap: Bitmap, name: String, format: ExportFormat): Uri? = null
    /** Loads, flattens and exports a project without opening it in the editor. */
    suspend fun exportProject(id: String, format: ExportFormat): Uri? = null
}
