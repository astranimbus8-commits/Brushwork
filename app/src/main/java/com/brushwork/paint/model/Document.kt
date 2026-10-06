package com.brushwork.paint.model

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.engine.LayerSlot
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.vector.VectorContent
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

    /**
     * Non-null for an EDITABLE TEXT LAYER: the text tool's serialized text object (content, style,
     * box, path...) that [bitmap] was rendered from. The text tool can load it to edit the text
     * again. Any other pixel edit turns the layer into a normal raster layer (the controller
     * clears this, undoably). Opaque to everything except the text tool and storage.
     */
    var textData: String? = null

    val isTextLayer: Boolean get() = textData != null

    /**
     * Non-null for an EDITABLE SHAPE LAYER: the shape tool's serialized shape object (type, box,
     * vertices, appearance) that [bitmap] was rendered from, so the shape can be edited again.
     * Like [textData], any other pixel edit turns the layer into a normal raster layer (the
     * controller clears this, undoably). Opaque to everything except the shape tool and storage.
     */
    var shapeData: String? = null

    val isShapeLayer: Boolean get() = shapeData != null

    /**
     * Non-null for a VECTOR LAYER (v1.5): its editable objects; [bitmap] is their render cache.
     * Like [textData], a raster pixel edit of the content clears it (undoably), and the cache and
     * the data always change together in one undo step (see `EditorController.updateLayerData`).
     */
    var vector: VectorContent? = null

    /**
     * Non-null when [mask] is an EDITABLE MASK (v1.5): the mask bitmap is rendered from this
     * spec. A pixel edit of the mask clears it (undoably).
     */
    var maskSpec: MaskSpec? = null

    /**
     * Non-null for an ADJUSTMENT LAYER (v1.5): instead of its (empty) pixels, the effect is
     * applied to the composite of the layers below, through the layer's mask and opacity.
     */
    var adjustment: AdjustmentSpec? = null

    val isVectorLayer: Boolean get() = vector != null

    val isAdjustmentLayer: Boolean get() = adjustment != null

    /**
     * v1.7 (I11): the folder this layer is in; [ROOT_ID] = top level. Structural:
     * `LayerTreeAction` captures it, [LayerData] does not.
     */
    var parentId: Long = ROOT_ID

    /**
     * v1.7: non-null for a FOLDER. A folder's [bitmap] is [FOLDER_BITMAP]; it has no mask. Rides
     * [LayerData].
     */
    var folder: FolderSpec? = null

    /** v1.7: the folder's rows are shown in the layer window. View state: persisted, never an undo step. */
    var folderOpen: Boolean = true

    /** v1.7 (I14): the live array; [bitmap] is its cache. Rides [LayerData]. */
    var array: LayerArray? = null

    val isFolder: Boolean get() = folder != null

    /**
     * True for layers that keep an editable object (text, shape or vector content, or since v1.7
     * a live array) besides their pixels.
     */
    val hasEditableData: Boolean get() = textData != null || shapeData != null || vector != null || array != null

    /** Every editable-data field as one immutable snapshot. */
    fun dataSnapshot(): LayerData = LayerData(textData, shapeData, vector, maskSpec, adjustment, folder, array)

    /** Sets every editable-data field from [d] (pixels are not touched). */
    fun restoreData(d: LayerData) {
        textData = d.text
        shapeData = d.shape
        vector = d.vector
        maskSpec = d.maskSpec
        adjustment = d.adjustment
        folder = d.folder
        array = d.array
    }

    /** v1.7: recycles this layer's bitmap and mask, but never [FOLDER_BITMAP]. */
    fun recycleBitmaps() { bitmap.recycleUnlessShared(); mask?.recycle() }

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

    /**
     * The bitmap that painting tools should currently modify (mask when editing it). Never a
     * folder's (v1.7): callers are refused earlier (`checkUsable`), so this is the last line of
     * defence.
     */
    val paintTarget: Bitmap get() {
        check(!isFolder) { "A folder has no pixels to paint: $this" }
        return if (editingMask) mask ?: bitmap else bitmap
    }

    override fun toString(): String = "Layer($id '$name')"

    companion object {
        /** v1.7: the parent id of a top-level layer. Layer ids start at 1 (`Document.newLayerId`), so 0 is never a layer. */
        const val ROOT_ID = 0L

        /**
         * The one immutable 1×1 bitmap all folders share: drawing into it throws (V9). Never
         * recycled: see [recycleBitmaps] and [recycleUnlessShared].
         */
        val FOLDER_BITMAP: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, false) }

        /** v1.7: a new (empty, open) folder; it owns no layers until some are put into it. */
        fun newFolder(id: Long, name: String, spec: FolderSpec = FolderSpec()): Layer =
            Layer(id, name, FOLDER_BITMAP).apply { folder = spec }
    }
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

    /** v1.7 (item 14): saved selections, newest first; immutable entries, replaced as a whole (`SavedSelectionsAction`). */
    var savedSelections: List<SavedSelection> = emptyList()

    /** v1.7 (item 18): drawing aid like [ruler]; persisted, not undoable. */
    var symmetry: SymmetrySettings = SymmetrySettings()

    /**
     * Problems found while loading that did not stop the project from opening (a layer's vector
     * or mask data could not be read, an unknown adjustment effect...). The editor shows them
     * once; main thread only.
     */
    val loadWarnings: MutableList<String> = mutableListOf()

    private var nextLayerId: Long = 1

    val bounds: Rect get() = Rect(0, 0, width, height)

    val activeLayer: Layer
        get() = layers[activeLayerIndex.coerceIn(0, layers.lastIndex)]

    fun newLayerId(): Long = nextLayerId++

    /** Used by persistence so ids stay unique after loading. */
    fun ensureNextLayerIdAbove(id: Long) { if (nextLayerId <= id) nextLayerId = id + 1 }

    /**
     * v1.7: the id the next saved selection gets. Persisted (`project.json` `nextSelectionId`),
     * because saved-selection ids are never reused within a document, even after the newest one
     * is deleted and the project saved.
     */
    var nextSelectionId: Long = 1
        private set

    /** Saved-selection ids are never reused within a document. */
    fun newSelectionId(): Long = nextSelectionId++

    /** Used by persistence: the next saved-selection id is above [id] (and at least 1). */
    fun ensureNextSelectionIdAbove(id: Long) { if (nextSelectionId <= id) nextSelectionId = id + 1 }

    /** v1.7: true when any layer is a folder. O(n), never cached. */
    val hasFolders: Boolean get() = layers.any { it.isFolder }

    /** v1.7: the layers with pixels (folders excluded); what `maxLayers` counts (plus array sources, §4.4). */
    val pixelLayerCount: Int get() = layers.count { !it.isFolder }

    /** v1.7: every layer except folders, bottom first. */
    val pixelLayers: Sequence<Layer> get() = layers.asSequence().filter { !it.isFolder }

    /**
     * v1.7 (I11, sweep rule L): [layer] is shown: its own eye AND every ancestor folder's. Gates
     * (tools, snapping, hit tests, export) read this, never `layer.visible` alone.
     */
    fun effectiveVisible(layer: Layer): Boolean =
        layer.visible && (layer.parentId == Layer.ROOT_ID || LayerTree.shownByAncestors(layers, indexOf(layer)))

    /** v1.7 (I11, sweep rule L): [layer] is locked: its own lock OR any ancestor folder's. */
    fun effectiveLocked(layer: Layer): Boolean =
        layer.locked || (layer.parentId != Layer.ROOT_ID && LayerTree.lockedByAncestor(layers, indexOf(layer)))

    /**
     * v1.7 (I11): inserts [layer] at flat index [at] inside folder [parentId] (sets its
     * `parentId`). The default, the parent of the unit directly below [at], is always valid.
     * Only `LayerStructure` and the undo actions change the structure (sweep rule S).
     */
    fun insertLayer(at: Int, layer: Layer, parentId: Long = parentBelow(at)) {
        layer.parentId = parentId
        layers.add(at.coerceIn(0, layers.size), layer)
    }

    /**
     * v1.7 (I11): the parent a layer inserted at flat index [at] gets by default: that of the row
     * directly below (a folder row closes its block, so its own parent), [Layer.ROOT_ID] at the bottom.
     */
    fun parentBelow(at: Int): Long =
        if (at <= 0 || layers.isEmpty()) Layer.ROOT_ID else layers[(at - 1).coerceAtMost(layers.lastIndex)].parentId

    /** v1.7: the order and every parent, by reference (`LayerTreeAction`'s snapshot). */
    fun slots(): List<LayerSlot> = layers.map { LayerSlot(it, it.parentId) }

    /** v1.7: puts back an order and its parents taken by [slots]. */
    fun restoreSlots(slots: List<LayerSlot>) {
        layers.clear()
        for (s in slots) { s.layer.parentId = s.parentId; layers.add(s.layer) }
    }

    fun indexOf(layer: Layer): Int = layers.indexOfFirst { it === layer }

    fun layerById(id: Long): Layer? = layers.firstOrNull { it.id == id }

    /** Changes the document size. Callers are responsible for replacing every layer's bitmaps. */
    fun setSize(w: Int, h: Int) { width = w; height = h }

    /** Bytes of one full-size ARGB layer. */
    val layerBytes: Long get() = width.toLong() * height * 4

    fun touch() { modifiedAt = System.currentTimeMillis() }
}
