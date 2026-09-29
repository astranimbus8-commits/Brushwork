package com.brushwork.paint.model

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.serialization.Serializable

enum class LayerBlendMode(val label: String) {
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

enum class ColorMode(val label: String) {
    RGB("RGB color"),
    GRAYSCALE("Grayscale"),
    MONOCHROME("Monochrome (1-bit)"),
}

/**
 * One raster layer. [bitmap] is a mutable ARGB_8888 (premultiplied) bitmap the size of the
 * document. [mask] (optional) is an ARGB_8888 grayscale bitmap: white = visible, black = hidden.
 *
 * Layers are identified by object identity (and a stable [id] for persistence); undo actions
 * hold Layer references, never indices.
 */
class Layer(
    val id: Long,
    var name: String,
    var bitmap: Bitmap,
) {
    var mask: Bitmap? = null
    var maskEnabled: Boolean = true
    /** When true, painting tools edit the mask instead of the pixels. */
    var editingMask: Boolean = false
    var opacity: Float = 1f
    var blendMode: LayerBlendMode = LayerBlendMode.NORMAL
    var visible: Boolean = true
    /** Clipped to the nearest non-clipping layer below. */
    var clipping: Boolean = false
    /** Painting only affects already-opaque pixels. */
    var alphaLocked: Boolean = false
    /** Prevents all edits. */
    var locked: Boolean = false

    /** Incremented on every pixel change (content or mask). Used for thumbnails and dirty saving. */
    var contentVersion: Long = 0
        private set
    /** contentVersion at the time of the last successful save (managed by the storage layer). */
    var savedVersion: Long = -1

    fun markChanged() { contentVersion++ }

    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    /** Copies the non-pixel properties from [other]. */
    fun copyPropsFrom(other: LayerProps) {
        name = other.name; opacity = other.opacity; blendMode = other.blendMode; visible = other.visible
        clipping = other.clipping; alphaLocked = other.alphaLocked; locked = other.locked; maskEnabled = other.maskEnabled
    }

    fun props(): LayerProps = LayerProps(name, opacity, blendMode, visible, clipping, alphaLocked, locked, maskEnabled)

    /** The bitmap that painting tools should currently modify (mask when editing it). */
    val paintTarget: Bitmap get() = if (editingMask) mask ?: bitmap else bitmap

    override fun toString(): String = "Layer($id '$name')"
}

/** Snapshot of the editable, non-pixel properties of a layer (used by undo + persistence). */
@Serializable
data class LayerProps(
    val name: String,
    val opacity: Float,
    val blendMode: LayerBlendMode,
    val visible: Boolean,
    val clipping: Boolean,
    val alphaLocked: Boolean,
    val locked: Boolean,
    val maskEnabled: Boolean,
)

/**
 * The drawing document. Layer 0 is the BOTTOM layer. All mutation happens on the main thread.
 */
class Document(
    val id: String,
    var name: String,
    width: Int,
    height: Int,
    var dpi: Float = 350f,
) {
    var width: Int = width
        private set
    var height: Int = height
        private set

    var colorMode: ColorMode = ColorMode.RGB
    val layers: MutableList<Layer> = mutableListOf()
    var activeLayerIndex: Int = 0
    var createdAt: Long = System.currentTimeMillis()
    var modifiedAt: Long = System.currentTimeMillis()

    var grid: GridSettings = GridSettings()
    var ruler: RulerSettings = RulerSettings()

    private var nextLayerId: Long = 1

    val bounds: Rect get() = Rect(0, 0, width, height)

    val activeLayer: Layer
        get() = layers[activeLayerIndex.coerceIn(0, layers.lastIndex)]

    fun newLayerId(): Long = nextLayerId++

    /** Used by persistence so ids stay unique after loading. */
    fun ensureNextLayerIdAbove(id: Long) { if (nextLayerId <= id) nextLayerId = id + 1 }

    fun indexOf(layer: Layer): Int = layers.indexOfFirst { it === layer }

    fun layerById(id: Long): Layer? = layers.firstOrNull { it.id == id }

    /** Changes the document size. Callers are responsible for replacing every layer's bitmaps. */
    fun setSize(w: Int, h: Int) { width = w; height = h }

    /** Bytes of one full-size ARGB layer. */
    val layerBytes: Long get() = width.toLong() * height * 4

    fun touch() { modifiedAt = System.currentTimeMillis() }
}
