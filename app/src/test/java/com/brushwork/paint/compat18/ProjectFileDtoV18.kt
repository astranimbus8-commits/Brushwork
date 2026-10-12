package com.brushwork.paint.compat18

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * v1.9 F0: a FROZEN copy of v1.8's `project.json` (`storage/ProjectFile.kt`'s DTOs and
 * `ProjectFormat`'s JSON settings, with the settings and layer types they hold from `model/` and
 * `core/Units.kt`, at 600582d9), in test sources only: what v1.8 reads from and writes to a v1.9
 * project's `project.json` (unknown keys ignored, so v1.8 drops v1.9's `paper`; an unknown enum
 * value takes the field's default). The loader's checks and the document model are not copied.
 * Never change it: it is what v1.8 is.
 */

/** v1.8's `LengthUnit` (serialized by name). */
enum class LengthUnitV18(val label: String, val short: String) {
    PX("Pixels", "px"),
    IN("Inches", "in"),
    CM("Centimeters", "cm"),
    MM("Millimeters", "mm"),
    PT("Points", "pt"),
}

/** v1.8's `LayerBlendMode` (serialized by name). */
enum class LayerBlendModeV18(val label: String) {
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

/** v1.8's `ColorMode` (serialized by name). */
enum class ColorModeV18(val label: String) {
    RGB("RGB color"),
    GRAYSCALE("Grayscale"),
    MONOCHROME("Monochrome (1-bit)"),
}

/** v1.8's `LayerProps`. */
@Serializable
data class LayerPropsV18(
    val name: String,
    val opacity: Float,
    val blendMode: LayerBlendModeV18,
    val visible: Boolean,
    val clipping: Boolean,
    val alphaLocked: Boolean,
    val locked: Boolean,
    val maskEnabled: Boolean,
)

/** v1.8's `FolderSpec`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpecV18(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val passThrough: Boolean = true,
)

/** v1.8's `GridType`. */
@Serializable
enum class GridTypeV18(val label: String) {
    SQUARE("Square"),
    RULE_OF_THIRDS("Rule of thirds"),
    ISOMETRIC("Isometric"),
    DIAGONAL("Diagonal"),
}

/** v1.8's `GridSettings`. */
@Serializable
data class GridSettingsV18(
    val enabled: Boolean = false,
    val type: GridTypeV18 = GridTypeV18.SQUARE,
    val spacingPx: Float = 100f,
    val majorEvery: Int = 4,
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
    val color: Int = 0xFF2F80ED.toInt(),
    val opacity: Float = 0.45f,
    val unit: LengthUnitV18 = LengthUnitV18.PX,
    val snap: Boolean = false,
)

/** v1.8's `RulerType`. */
@Serializable
enum class RulerTypeV18(val label: String) {
    STRAIGHT("Straight"),
    CIRCLE("Circular"),
    ELLIPSE("Elliptical"),
    RADIAL("Radial"),
}

/** v1.8's `RulerSnap`. */
@Serializable
enum class RulerSnapV18(val label: String) {
    PARALLEL("Parallel / concentric"),
    ON_RULER("On the ruler"),
}

/** v1.8's `RulerSettings`. */
@Serializable
data class RulerSettingsV18(
    val enabled: Boolean = false,
    val type: RulerTypeV18 = RulerTypeV18.STRAIGHT,
    val centerX: Float = -1f,
    val centerY: Float = -1f,
    val angleDeg: Float = 0f,
    val radius: Float = 300f,
    val radiusX: Float = 400f,
    val radiusY: Float = 250f,
    val radialLines: Int = 24,
    val snap: RulerSnapV18 = RulerSnapV18.PARALLEL,
    val unit: LengthUnitV18 = LengthUnitV18.PX,
    val nudgeStep: Float = 1f,
)

/** v1.8's `SymmetryType`. */
@Serializable
enum class SymmetryTypeV18(val label: String) {
    OFF("Off"),
    MIRROR("Mirror ruler"),
    KALEIDOSCOPE("Kaleidoscope ruler"),
    ROTATION("Rotation ruler"),
    ARRAY("Array ruler"),
    PERSPECTIVE_ARRAY("Perspective array ruler"),
}

/** v1.8's `SymmetrySettings` (its serialized fields; v1.8 sanitizes them in the loader, not in the DTO). */
@Serializable
data class SymmetrySettingsV18(
    val type: SymmetryTypeV18 = SymmetryTypeV18.OFF,
    val centerX: Float = -1f,
    val centerY: Float = -1f,
    val angleDeg: Float = 90f,
    val divisions: Int = 6,
    val spacingX: Float = 300f,
    val spacingY: Float = 300f,
    val quad: List<Float> = emptyList(),
)

/** v1.8's `ProjectFileDto` (no `paper`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProjectFileDtoV18(
    val formatVersion: Int = ProjectFormatV18.BASE_VERSION,
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Float = 350f,
    val colorMode: ColorModeV18 = ColorModeV18.RGB,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val activeLayerIndex: Int = 0,
    val grid: GridSettingsV18 = GridSettingsV18(),
    val ruler: RulerSettingsV18 = RulerSettingsV18(),
    val layers: List<LayerEntryDtoV18> = emptyList(),
    val revision: Long = 0L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val selections: List<SelectionEntryDtoV18> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val symmetry: SymmetrySettingsV18 = SymmetrySettingsV18(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nextSelectionId: Long = 1L,
)

/** v1.8's `LayerEntryDto`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LayerEntryDtoV18(
    val id: Long,
    val name: String,
    val props: LayerPropsV18,
    val hasMask: Boolean = false,
    val file: String? = null,
    val maskFile: String? = null,
    val textData: String? = null,
    val shapeData: String? = null,
    val vectorFile: String? = null,
    val maskSpec: String? = null,
    val adjustment: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val parentId: Long = 0L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val folder: FolderSpecV18? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val folderOpen: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val array: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val arrayFile: String? = null,
)

/** v1.8's `SelectionEntryDto`. */
@Serializable
data class SelectionEntryDtoV18(
    val id: Long,
    val name: String,
    val file: String,
    val revision: Long,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** v1.8's `ProjectFormat`: its versions, file name and JSON settings, reading and writing `project.json`. */
object ProjectFormatV18 {
    /** Highest format v1.8 reads (it refuses a higher one). */
    const val VERSION = 3
    const val BASE_VERSION = 1
    const val PROJECT_FILE = "project.json"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** `project.json` text as v1.8 reads it. */
    fun decode(text: String): ProjectFileDtoV18 = json.decodeFromString(ProjectFileDtoV18.serializer(), text)

    /** The `project.json` text v1.8 writes for [dto]. */
    fun encode(dto: ProjectFileDtoV18): String = json.encodeToString(ProjectFileDtoV18.serializer(), dto)

    /** v1.8's `ProjectFormat.read`. */
    fun read(dir: File): ProjectFileDtoV18 = decode(File(dir, PROJECT_FILE).readText())
}
