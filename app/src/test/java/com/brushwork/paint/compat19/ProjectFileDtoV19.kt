package com.brushwork.paint.compat19

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/*
 * v1.10 G2 (compat19 kit): a FROZEN copy of v1.9's `project.json` (`storage/ProjectFile.kt`'s
 * DTOs and `ProjectFormat`'s versions and JSON settings, with the settings and layer types they
 * hold from `model/` and `core/Units.kt`, at tag v1.9.0), in test sources only: what v1.9 reads
 * from and writes to a v1.10 project's `project.json` (unknown keys ignored, so v1.9 drops what
 * v1.10 adds; an unknown enum value takes the field's default). [ProjectFormatV19.open] is the
 * head of v1.9's loader (`ProjectRepository.loadLocked`, the checks before any layer is read,
 * the "newer version" refusal of format 4 among them) and [ProjectFormatV19.v19Cap] is v1.9's
 * layer cap (`EditorController.maxLayers`) for a 512 MiB heap. The document model is not copied.
 * Never change it: it is what v1.9 is.
 *
 * The kit: this file, [LayerCodecV19] (the BWLY v1 pixel files), [VectorCodecV19] with
 * [VectorContentV19] and the classes it holds ([ShapeObjectV19], [BrushPresetV19],
 * [PackedPointsV19], [CopyMapsV19]) and [ArrayCodecV19] with [ArraySpecV19]. The vector model,
 * shape object, brush settings, points and copies did not change from v1.8.0 to v1.9.0
 * (`git diff 600582d9 v1.9.0` shows no change to their files), so those copies are compat18's
 * under the V19 names. `ProjectFile.kt` gained `paper`, and `LayerCodec.kt` the banded write,
 * whose files are byte-identical to the whole-layer write's.
 */

/** v1.9's `LengthUnit` (serialized by name). */
enum class LengthUnitV19(val label: String, val short: String) {
    PX("Pixels", "px"),
    IN("Inches", "in"),
    CM("Centimeters", "cm"),
    MM("Millimeters", "mm"),
    PT("Points", "pt"),
}

/** v1.9's `LayerBlendMode` (serialized by name). */
enum class LayerBlendModeV19(val label: String) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    DARKEN("Darken"),
    LIGHTEN("Lighten"),
    COLOR_DODGE("Color Dodge"),
    COLOR_BURN("Color Burn"),
    HARD_LIGHT("Hard Light"),
    SOFT_LIGHT("Soft Light"),
    DIFFERENCE("Difference"),
    EXCLUSION("Exclusion"),
    HUE("Hue"),
    SATURATION("Saturation"),
    COLOR("Color"),
    LUMINOSITY("Luminosity"),
    ADD("Add (Glow)"),
}

/** v1.9's `ColorMode` (serialized by name). */
enum class ColorModeV19(val label: String) {
    RGB("RGB color"),
    GRAYSCALE("Grayscale"),
    MONOCHROME("Monochrome (1-bit)"),
}

/** v1.9's `LayerProps`. */
@Serializable
data class LayerPropsV19(
    val name: String,
    val opacity: Float,
    val blendMode: LayerBlendModeV19,
    val visible: Boolean,
    val clipping: Boolean,
    val alphaLocked: Boolean,
    val locked: Boolean,
    val maskEnabled: Boolean,
)

/** v1.9's `FolderSpec`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpecV19(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val passThrough: Boolean = true,
)

/** v1.9's `GridType`. */
@Serializable
enum class GridTypeV19(val label: String) {
    SQUARE("Square"),
    RULE_OF_THIRDS("Rule of thirds"),
    ISOMETRIC("Isometric"),
    DIAGONAL("Diagonal"),
}

/** v1.9's `GridSettings`. */
@Serializable
data class GridSettingsV19(
    val enabled: Boolean = false,
    val type: GridTypeV19 = GridTypeV19.SQUARE,
    val spacingPx: Float = 100f,
    val majorEvery: Int = 4,
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
    val color: Int = 0xFF2F80ED.toInt(),
    val opacity: Float = 0.45f,
    val unit: LengthUnitV19 = LengthUnitV19.PX,
    val snap: Boolean = false,
)

/** v1.9's `RulerType`. */
@Serializable
enum class RulerTypeV19(val label: String) {
    STRAIGHT("Straight"),
    CIRCLE("Circular"),
    ELLIPSE("Elliptical"),
    RADIAL("Radial"),
}

/** v1.9's `RulerSnap`. */
@Serializable
enum class RulerSnapV19(val label: String) {
    PARALLEL("Parallel / concentric"),
    ON_RULER("On the ruler"),
}

/** v1.9's `RulerSettings`. */
@Serializable
data class RulerSettingsV19(
    val enabled: Boolean = false,
    val type: RulerTypeV19 = RulerTypeV19.STRAIGHT,
    val centerX: Float = -1f,
    val centerY: Float = -1f,
    val angleDeg: Float = 0f,
    val radius: Float = 300f,
    val radiusX: Float = 400f,
    val radiusY: Float = 250f,
    val radialLines: Int = 24,
    val snap: RulerSnapV19 = RulerSnapV19.PARALLEL,
    val unit: LengthUnitV19 = LengthUnitV19.PX,
    val nudgeStep: Float = 1f,
)

/** v1.9's `SymmetryType`. */
@Serializable
enum class SymmetryTypeV19(val label: String) {
    OFF("Off"),
    MIRROR("Mirror ruler"),
    KALEIDOSCOPE("Kaleidoscope ruler"),
    ROTATION("Rotation ruler"),
    ARRAY("Array ruler"),
    PERSPECTIVE_ARRAY("Perspective array ruler"),
}

/** v1.9's `SymmetrySettings` (its serialized fields; v1.9 sanitizes them in the loader, not in the DTO). */
@Serializable
data class SymmetrySettingsV19(
    val type: SymmetryTypeV19 = SymmetryTypeV19.OFF,
    val centerX: Float = -1f,
    val centerY: Float = -1f,
    val angleDeg: Float = 90f,
    val divisions: Int = 6,
    val spacingX: Float = 300f,
    val spacingY: Float = 300f,
    val quad: List<Float> = emptyList(),
)

/** v1.9's `PaperSpec` (R15): white only; never written when there is no paper (I13). */
@Serializable
data class PaperSpecV19(val color: Int = 0xFFFFFFFF.toInt())

/** v1.9's `ProjectFileDto` (v1.8's plus `paper`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProjectFileDtoV19(
    val formatVersion: Int = ProjectFormatV19.BASE_VERSION,
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Float = 350f,
    val colorMode: ColorModeV19 = ColorModeV19.RGB,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val activeLayerIndex: Int = 0,
    val grid: GridSettingsV19 = GridSettingsV19(),
    val ruler: RulerSettingsV19 = RulerSettingsV19(),
    val layers: List<LayerEntryDtoV19> = emptyList(),
    val revision: Long = 0L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val selections: List<SelectionEntryDtoV19> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val symmetry: SymmetrySettingsV19 = SymmetrySettingsV19(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextSelectionId: Long = 1L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val paper: PaperSpecV19? = null,
)

/** v1.9's `LayerEntryDto` (with its file names and folder helpers). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LayerEntryDtoV19(
    val id: Long,
    val name: String,
    val props: LayerPropsV19,
    val hasMask: Boolean = false,
    val file: String? = null,
    val maskFile: String? = null,
    val textData: String? = null,
    val shapeData: String? = null,
    val vectorFile: String? = null,
    val maskSpec: String? = null,
    val adjustment: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val parentId: Long = 0L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val folder: FolderSpecV19? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val folderOpen: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val array: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val arrayFile: String? = null,
) {
    val contentFileName: String get() = file ?: "layer_$id.bin"
    val maskFileName: String get() = maskFile ?: "mask_$id.bin"

    /** v1.9's `isStrippedFolder`: a format-3 entry whose pixel file is "" is a folder an older version stripped. */
    fun isStrippedFolder(formatVersion: Int): Boolean = folder == null && file == "" && formatVersion >= 3

    /** v1.9's `folderSpec`. */
    fun folderSpec(formatVersion: Int): FolderSpecV19? = folder ?: if (isStrippedFolder(formatVersion)) FolderSpecV19() else null
}

/** v1.9's `SelectionEntryDto`. */
@Serializable
data class SelectionEntryDtoV19(
    val id: Long,
    val name: String,
    val file: String,
    val revision: Long,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** v1.9's `CorruptProjectException` (`storage/LayerCodec.kt`): a project file exists but v1.9 can't decode it. */
class CorruptProjectExceptionV19(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * v1.9's `ProjectFormat`: its versions, file names and JSON settings, reading and writing
 * `project.json`, the version it writes, the head of its loader and its layer cap.
 */
object ProjectFormatV19 {
    /** Highest format v1.9 reads (it refuses a higher one, [open]). */
    const val VERSION = 3
    const val BASE_VERSION = 1
    const val PROJECT_FILE = "project.json"

    /** v1.9's `ProjectRepository.MAX_SIDE` (`CanvasLimits.MAX_SIDE`). */
    const val MAX_SIDE = 10_000

    /** The heap the kit computes v1.9's cap for ([v19Cap]): 512 MiB. */
    const val HEAP = 512L shl 20

    /** v1.9's refusal of a project of a higher format (`ProjectRepository.loadLocked`), word for word. */
    const val NEWER_VERSION = "This artwork was saved by a newer version of Brushwork"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun layerFile(id: Long, revision: Long) = "layer_${id}_r$revision.bin"
    fun maskFile(id: Long, revision: Long) = "mask_${id}_r$revision.bin"
    fun vectorFile(id: Long, revision: Long) = "vector_${id}_r$revision.vec"
    fun arrayFile(layerId: Long, revision: Long) = "array_${layerId}_r$revision.bin"

    /** `project.json` text as v1.9 reads it. */
    fun decode(text: String): ProjectFileDtoV19 = json.decodeFromString(ProjectFileDtoV19.serializer(), text)

    /** The `project.json` text v1.9 writes for [dto] (`ProjectFormat.write` stores it as UTF-8). */
    fun encode(dto: ProjectFileDtoV19): String = json.encodeToString(ProjectFileDtoV19.serializer(), dto)

    /** v1.9's `ProjectFormat.read`. */
    fun read(dir: File): ProjectFileDtoV19 = decode(File(dir, PROJECT_FILE).readText())

    /**
     * v1.9's `ProjectFormat.writtenVersion` (I14) over what a save writes for the layers: 3 when a
     * folder exists, else 2 when an adjustment layer exists, else 1. v1.9 asks its layers
     * (`isFolder`, `isAdjustmentLayer`); a saved folder's entry has [LayerEntryDtoV19.folder] and an
     * adjustment layer's entry its [LayerEntryDtoV19.adjustment].
     */
    fun writtenVersion(layers: List<LayerEntryDtoV19>): Int = when {
        layers.any { it.folder != null } -> 3
        layers.any { it.adjustment != null } -> 2
        else -> BASE_VERSION
    }

    /**
     * The head of v1.9's `ProjectRepository.loadLocked` (tag v1.9.0, lines 251-265): the project
     * file of [dir] as v1.9 reads it, or what v1.9 throws before it reads any layer:
     * [FileNotFoundException] when the folder is gone, [CorruptProjectExceptionV19] when
     * `project.json` is missing or damaged, an [IOException] with [NEWER_VERSION] when its
     * `formatVersion` is above [VERSION] (format 4), [CorruptProjectExceptionV19] for a bad size or
     * no layers.
     */
    fun open(dir: File): ProjectFileDtoV19 {
        if (!dir.isDirectory) throw FileNotFoundException("This artwork no longer exists")
        val dto = try {
            read(dir)
        } catch (e: FileNotFoundException) {
            throw CorruptProjectExceptionV19("The artwork's project file is missing", e)
        } catch (e: Exception) {
            throw CorruptProjectExceptionV19("The artwork's project file is damaged", e)
        }
        if (dto.formatVersion > VERSION) throw IOException(NEWER_VERSION)
        val w = dto.width
        val h = dto.height
        if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) throw CorruptProjectExceptionV19("The artwork has an invalid size (${w}x$h)")
        if (dto.layers.isEmpty()) throw CorruptProjectExceptionV19("The artwork has no layers")
        return dto
    }

    /**
     * v1.9's `EditorController.maxLayers` (region v19-A7b layerCap) for a [w] x [h] canvas on a
     * [heap]-byte `maxMemory`: `heap * 0.55 / layerBytes - 3`, within 2..100.
     */
    fun v19Cap(w: Int, h: Int, heap: Long = HEAP): Int {
        val budget = (heap * 0.55).toLong()
        val per = maxOf(1L, w.toLong() * h * 4)
        return (budget / per - 3).toInt().coerceIn(2, 100)
    }

    /**
     * v1.9's `EditorController.effectiveLayerCount` over a saved project: every pixel layer (a
     * folder counts 0), every mask, and the array sources by their bytes in layer-sized units
     * ([arrayBytes], the sum of `ArrayPixels.bytes`, which the entries do not hold).
     */
    fun effectiveLayerCount(dto: ProjectFileDtoV19, arrayBytes: Long = 0L): Int {
        val per = maxOf(1L, dto.width.toLong() * dto.height * 4)
        val pixelLayers = dto.layers.count { it.folderSpec(dto.formatVersion) == null }
        val masks = dto.layers.count { it.hasMask }
        return pixelLayers + masks + ((arrayBytes + per - 1) / per).toInt()
    }
}
