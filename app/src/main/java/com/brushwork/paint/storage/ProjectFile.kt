package com.brushwork.paint.storage

import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.RulerSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Contents of `project.json`: everything about a project except the pixels. */
@Serializable
internal data class ProjectFileDto(
    val formatVersion: Int = ProjectFormat.VERSION,
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Float = 350f,
    val colorMode: ColorMode = ColorMode.RGB,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val activeLayerIndex: Int = 0,
    val grid: GridSettings = GridSettings(),
    val ruler: RulerSettings = RulerSettings(),
    /** Bottom layer first (same order as `Document.layers`). */
    val layers: List<LayerEntryDto> = emptyList(),
    /** Incremented by every save; new pixel files carry it in their names (copy-on-write). */
    val revision: Long = 0L,
)

@Serializable
internal data class LayerEntryDto(
    val id: Long,
    val name: String,
    val props: LayerProps,
    val hasMask: Boolean = false,
    /** Pixel file name inside the project folder (null = `layer_<id>.bin`). */
    val file: String? = null,
    /** Mask file name when [hasMask] (null = `mask_<id>.bin`). */
    val maskFile: String? = null,
    /** Serialized editable text object for text layers (see Layer.textData). */
    val textData: String? = null,
    /** Serialized editable shape object for shape layers (see Layer.shapeData). */
    val shapeData: String? = null,
) {
    val contentFileName: String get() = file ?: "layer_$id.bin"
    val maskFileName: String get() = maskFile ?: "mask_$id.bin"
}

/**
 * File names, JSON configuration and small file helpers of the on-disk project format.
 *
 * Folder layout: `project.json`, `thumb.png` and one pixel file per layer and per mask
 * (`layer_<id>_r<revision>.bin`, `mask_<id>_r<revision>.bin`, see [LayerCodec]). A save writes
 * changed layers to NEW files, then atomically replaces `project.json`, then deletes files that
 * are no longer referenced — so a crash at any point leaves either the old or the new project.
 */
internal object ProjectFormat {
    const val VERSION = 1
    const val PROJECT_FILE = "project.json"
    const val THUMB_FILE = "thumb.png"
    const val TEMP_SUFFIX = ".tmp"
    const val THUMB_SIZE = 512

    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val pixelFilePattern = Regex("(layer|mask)_[A-Za-z0-9_-]+\\.bin")

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        // A NaN that slipped into a setting (grid spacing, ruler angle...) must not make every
        // save fail; load() sanitizes the values it relies on.
        allowSpecialFloatingPointValues = true
    }

    fun layerFile(id: Long, revision: Long) = "layer_${id}_r$revision.bin"
    fun maskFile(id: Long, revision: Long) = "mask_${id}_r$revision.bin"

    /** Project ids become folder names: only accept safe ones. */
    fun isValidId(id: String) = idPattern.matches(id)

    fun isPixelFile(name: String) = pixelFilePattern.matches(name)

    fun defaultProps(name: String) = LayerProps(
        name = name, opacity = 1f, blendMode = LayerBlendMode.NORMAL, visible = true,
        clipping = false, alphaLocked = false, locked = false, maskEnabled = true,
    )

    fun read(dir: File): ProjectFileDto =
        json.decodeFromString(ProjectFileDto.serializer(), File(dir, PROJECT_FILE).readText())

    fun readOrNull(dir: File): ProjectFileDto? = try { read(dir) } catch (e: Exception) { null }

    fun write(dir: File, dto: ProjectFileDto) {
        val bytes = json.encodeToString(ProjectFileDto.serializer(), dto).toByteArray(Charsets.UTF_8)
        writeAtomically(File(dir, PROJECT_FILE)) { it.write(bytes) }
    }

    /** Every file [dto] needs (pixel files of all layers and masks). */
    fun referencedFiles(dto: ProjectFileDto): Set<String> = buildSet {
        for (e in dto.layers) {
            add(e.contentFileName)
            if (e.hasMask) add(e.maskFileName)
        }
    }

    /** Deletes pixel files [dto] doesn't reference plus leftover temp files. */
    fun deleteUnreferenced(dir: File, dto: ProjectFileDto) {
        val keep = referencedFiles(dto)
        dir.listFiles()?.forEach { f ->
            val n = f.name
            if (f.isFile && n !in keep && (isPixelFile(n) || n.endsWith(TEMP_SUFFIX))) f.delete()
        }
    }

    /**
     * Writes [target] through a temp file in the same folder, fsyncs it and atomically replaces
     * the old file, so a crash or power loss never leaves a half-written file behind.
     */
    fun writeAtomically(target: File, block: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { fos ->
                val out = fos.buffered(64 * 1024)
                block(out)
                out.flush()
                fos.fd.sync()
            }
            moveReplacing(tmp, target)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            // Some file systems reject ATOMIC_MOVE with a generic error; retry plainly.
            if (!from.exists()) throw e
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
