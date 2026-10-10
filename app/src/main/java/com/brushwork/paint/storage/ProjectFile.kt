package com.brushwork.paint.storage

import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.SymmetrySettings
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Contents of `project.json`: everything about a project except the pixels. The v1.7 fields are
 * NEVER-encoded at their defaults, so a project without them writes the v1.6 bytes (I13).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ProjectFileDto(
    /**
     * The format this file needs to be read: 1 (v1.0–v1.4 readable) unless the project has an
     * adjustment layer (2) or a folder (3), see [ProjectFormat.writtenVersion].
     */
    val formatVersion: Int = ProjectFormat.BASE_VERSION,
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
    /** v1.7 (item 14): the saved selections, newest first (`Document.savedSelections`). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val selections: List<SelectionEntryDto> = emptyList(),
    /** v1.7 (item 18): the symmetry ruler (`Document.symmetry`). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val symmetry: SymmetrySettings = SymmetrySettings(),
    /** v1.7 (item 14): the id the next saved selection gets (ids are never reused). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextSelectionId: Long = 1L,
)

@OptIn(ExperimentalSerializationApi::class)
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
    /** Vector file of a vector layer (`vector_<id>_r<rev>.vec`, see VectorCodec); null for other layers. */
    val vectorFile: String? = null,
    /** Editable mask spec (MaskCodec), pre-encoded so a damaged one only affects its layer. */
    val maskSpec: String? = null,
    /** Effect of an adjustment layer (AdjustmentCodec), pre-encoded like [maskSpec]. */
    val adjustment: String? = null,
    /** v1.7 (I11): the id of the folder this layer is in; 0 = top level. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val parentId: Long = 0L,
    /** v1.7: non-null = a folder (its [file] is "", it has no pixel or mask file). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val folder: FolderSpec? = null,
    /** v1.7: a folder's rows are shown in the layer window (view state). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val folderOpen: Boolean = true,
    /** v1.7 (I14): the live array's spec (`ArrayCodec` JSON); the entry is then a plain raster entry (its cache). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val array: String? = null,
    /** v1.7 (I14): the array's source container, `array_<id>_r<rev>.bin`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val arrayFile: String? = null,
) {
    val contentFileName: String get() = file ?: "layer_$id.bin"
    val maskFileName: String get() = maskFile ?: "mask_$id.bin"

    /**
     * v1.7 QA: an entry whose pixel file is "" is a folder whose v1.7 keys an older version
     * dropped: v1.6's gallery "Rename" rewrites `project.json` with only the keys it knows (it
     * keeps `formatVersion` 3, so v1.6 still refuses to open it). Only a v1.7 folder entry has
     * that file name.
     */
    val isStrippedFolder: Boolean get() = folder == null && file == ""

    /** v1.7: the folder this entry is ([folder]; a default one when [isStrippedFolder]), else null. */
    val folderSpec: FolderSpec? get() = folder ?: if (isStrippedFolder) FolderSpec() else null
}

/** v1.7 (item 14): one saved selection; [file] holds `SavedSelection.packed`, the rows inside the bounds. */
@Serializable
internal data class SelectionEntryDto(
    val id: Long,
    val name: String,
    /** `sel_<id>_r<rev>.bin`. */
    val file: String,
    val revision: Long,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * File names, JSON configuration and small file helpers of the on-disk project format.
 *
 * Folder layout: `project.json`, `thumb.png` and one pixel file per layer and per mask
 * (`layer_<id>_r<revision>.bin`, `mask_<id>_r<revision>.bin`, see [LayerCodec]), plus one
 * `vector_<id>_r<revision>.vec` per vector layer (v1.5, see VectorCodec; mask specs and
 * adjustments are small and live inside `project.json`). A save writes changed layers to NEW
 * files, then atomically replaces `project.json`, then deletes files that are no longer
 * referenced — so a crash at any point leaves either the old or the new project.
 *
 * Compatibility (I4): `formatVersion` is 1 unless the project has an adjustment layer (then 2,
 * which v1.4 refuses); v1.4 ignores the vector files and shows the layers' cached pixels.
 *
 * v1.7 (§4.3, I14): a project with a folder is format 3 (v1.6 refuses it); a folder entry has no
 * pixel file (`file` = ""). An arrayed layer is a plain raster entry (its cache) plus its spec and
 * an `array_<id>_r<revision>.bin` source container; saved selections are
 * `sel_<id>_r<revision>.bin` files. Neither changes the format version.
 */
internal object ProjectFormat {
    /** Highest format this version reads. */
    const val VERSION = 3
    /** The format of projects without adjustment layers (v1.0–v1.4 read it; vector data is extra). */
    const val BASE_VERSION = 1
    const val PROJECT_FILE = "project.json"
    const val THUMB_FILE = "thumb.png"
    const val TEMP_SUFFIX = ".tmp"
    const val THUMB_SIZE = 512

    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val pixelFilePattern = Regex("(layer|mask)_[A-Za-z0-9_-]+\\.bin")
    private val vectorFilePattern = Regex("vector_[A-Za-z0-9_-]+\\.vec")
    private val arrayFilePattern = Regex("array_[0-9]+_r[0-9]+\\.bin")
    private val selectionFilePattern = Regex("sel_[0-9]+_r[0-9]+\\.bin")

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
    fun vectorFile(id: Long, revision: Long) = "vector_${id}_r$revision.vec"

    /** v1.7: an arrayed layer's source container (`ArrayCodec`). */
    fun arrayFile(layerId: Long, revision: Long) = "array_${layerId}_r$revision.bin"

    /** v1.7: a saved selection's packed rows. */
    fun selectionFile(id: Long, revision: Long) = "sel_${id}_r$revision.bin"

    /** Project ids become folder names: only accept safe ones. */
    fun isValidId(id: String) = idPattern.matches(id)

    fun isPixelFile(name: String) = pixelFilePattern.matches(name)

    /** A vector layer's data file name (v1.5); separate from [isPixelFile] so pixel names stay validated as before (V8). */
    fun isVectorFile(name: String) = vectorFilePattern.matches(name)

    /** v1.7: an array source container's file name. */
    fun isArrayFile(name: String) = arrayFilePattern.matches(name)

    /** v1.7: a saved selection's file name. */
    fun isSelectionFile(name: String) = selectionFilePattern.matches(name)

    /** True for every file name a project may reference (pixels, vectors, array sources, saved selections). */
    fun isDataFile(name: String) = isPixelFile(name) || isVectorFile(name) || isArrayFile(name) || isSelectionFile(name)

    /**
     * The format version to write for [layers] (I14): 3 when a folder exists, else 2 when an
     * adjustment layer exists (older versions then refuse the project instead of showing it
     * wrong), else 1 (I4).
     */
    fun writtenVersion(layers: List<Layer>): Int = when {
        layers.any { it.isFolder } -> 3
        layers.any { it.isAdjustmentLayer } -> 2
        else -> BASE_VERSION
    }

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

    /**
     * Every file [dto] needs (pixel files of all layers and masks, vector files; v1.7: array
     * containers and saved selections). A folder entry needs none.
     */
    fun referencedFiles(dto: ProjectFileDto): Set<String> = buildSet {
        for (e in dto.layers) {
            if (e.folderSpec != null) continue
            e.contentFileName.takeIf { it.isNotEmpty() }?.let { add(it) }
            if (e.hasMask) add(e.maskFileName)
            e.vectorFile?.let { add(it) }
            e.arrayFile?.let { add(it) }
        }
        for (s in dto.selections) add(s.file)
    }

    /** Deletes data files (pixels, vectors, array containers, saved selections) [dto] doesn't reference plus leftover temp files. */
    fun deleteUnreferenced(dir: File, dto: ProjectFileDto) {
        val keep = referencedFiles(dto)
        dir.listFiles()?.forEach { f ->
            val n = f.name
            if (f.isFile && n !in keep && (isDataFile(n) || n.endsWith(TEMP_SUFFIX))) f.delete()
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
