package com.brushwork.paint.model

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.LayerSlot
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.PixelPin
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
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
 *
 * v1.10 (F0, design 3.2 C16 part 1): replacing [bitmap] or [mask] by another bitmap bumps
 * [pixelGeneration], which staleness keys compare (with [contentVersion]) instead of bitmap
 * identity; [owns] answers whether a bitmap is this layer's own.
 *
 * v1.10 (F1, design 2.2, J5, I28): the content is a [PixelStore] ([pixels], [maskPixels]);
 * [bitmap] and [mask] are the facade over it. In Classic every painted layer is [FlatPixels], so
 * the facade gives v1.9's bitmaps. A BLANK, PARKED or EVICTED store is materialized by the getter
 * (main thread only, counted in [PixelFacade]); presence checks use [hasMask], which never
 * materializes.
 *
 * v1.10 (F1c): the store swaps (the [pixels] and [maskPixels] setters, [rehouse], the getter's
 * materialization) hold this layer's monitor, so a residency that cut or materialized a store can
 * check under it that the layer still holds what it read (`ChaosResidency.park`): the design keeps
 * them on the main thread, but a test scope (`Dispatchers.Unconfined`) resumes a canvas commit on a
 * worker while the main looper parks.
 */
class Layer(
    val id: Long,
    var name: String,
    pixels: PixelStore,
) {
    /** v1.9's constructor: [bitmap] becomes a [FlatPixels] store ([FOLDER_BITMAP] a [FolderPixels] one). */
    constructor(id: Long, name: String, bitmap: Bitmap) : this(id, name, storeOf(bitmap))

    @Volatile private var store: PixelStore = pixels
    @Volatile private var maskStore: PixelStore? = null

    /**
     * v1.10 (F1): where the content is. Replacing it by another store bumps [pixelGeneration];
     * park, unpark, evict and swap keep the store's content and never bump it (J5).
     */
    var pixels: PixelStore
        get() = store
        set(value) {
            synchronized(this) {
                if (value !== store) pixelGeneration++
                store = value
            }
        }

    /** v1.10 (F1): where the mask is; null = no mask. The same rules as [pixels]. */
    var maskPixels: PixelStore?
        get() = maskStore
        set(value) {
            synchronized(this) {
                if (value !== maskStore) pixelGeneration++
                maskStore = value
            }
        }

    /**
     * The content; replacing it by another bitmap bumps [pixelGeneration]. v1.10 (F1): the getter
     * gives the FLAT bitmap, [FOLDER_BITMAP] for a folder, else materializes the store (main
     * only, counted), which makes it FLAT; the setter is `pixels = FlatPixels(value)`.
     */
    var bitmap: Bitmap
        get() = when (val p = store) {
            is FlatPixels -> p.bitmap
            FolderPixels -> FOLDER_BITMAP
            else -> materialize(p, mask = false)
        }
        set(value) {
            val cur = store
            if (cur is FlatPixels && value === cur.bitmap) return
            if (cur === FolderPixels && isFolderBitmap(value)) return
            pixels = storeOf(value)
        }

    /** The layer mask, if any; replacing, adding or removing it bumps [pixelGeneration]. v1.10 (F1): the same facade over [maskPixels]. */
    var mask: Bitmap?
        get() = when (val m = maskStore) {
            null -> null
            is FlatPixels -> m.bitmap
            else -> materialize(m, mask = true)
        }
        set(value) {
            val cur = maskStore
            if (value == null) {
                maskPixels = null
                return
            }
            if (cur is FlatPixels && value === cur.bitmap) return
            maskPixels = FlatPixels(value)
        }

    /** v1.10 (F1): the layer has a mask. Every presence check uses it (it never materializes). */
    val hasMask: Boolean get() = maskStore != null

    /**
     * v1.10 (F0, design 3.2 C16 part 1): increases whenever [bitmap] or [mask] is replaced by
     * another bitmap (assigning the same one changes nothing), so a key holding it and
     * [contentVersion] goes stale wherever v1.9's bitmap identity did. Pixel edits in place keep
     * it ([contentVersion] counts them). Never persisted. v1.10 (F1): any replacement of
     * [pixels] or [maskPixels] by another store; NOT bumped by park, unpark, evict, swap or a
     * materialization (J5).
     */
    var pixelGeneration: Long = 0L
        private set

    /** v1.10 (F0): [b] is this layer's own bitmap or mask (identity; F1 answers it without materializing). */
    fun owns(b: Bitmap?): Boolean = b != null && (holds(store, b) || holds(maskStore, b))

    private fun holds(p: PixelStore?, b: Bitmap): Boolean = when (p) {
        is FlatPixels -> b === p.bitmap
        FolderPixels -> isFolderBitmap(b)
        else -> false
    }

    /** v1.10 (F1, design 2.2): a non-resident pin: nothing parks, swaps out or recycles this layer's pixels until it closes; never swaps in. Main only. */
    fun pin(): PixelPin = PixelPin(this, resident = false)

    /** v1.10 (F1, design 2.2): a pin that also makes every tile resident now. Main only, outside a frame. */
    fun pinResident(): PixelPin = PixelPin(this, resident = true)

    /** May this layer be written now (section 2.13): FLAT true; else admitNative(L, LAYER) for its materialization. Never materializes. */
    fun admitWrite(): Boolean {
        val p = store
        if (p is FlatPixels || p === FolderPixels) return true
        val bytes = p.width.toLong() * p.height * 4
        val budget = EngineConfig.budget
        if (!budget.admitNative(bytes, NativePurpose.LAYER)) return false
        budget.release(bytes, NativePurpose.LAYER)
        return true
    }

    /** v1.10 (F1, design 2.0): the state of the content's store. */
    internal val residency: ResidencyState
        get() = when (val p = store) {
            FolderPixels -> ResidencyState.FOLDER
            is BlankPixels -> ResidencyState.BLANK
            is FlatPixels -> ResidencyState.FLAT
            is TiledPixels -> if (p.evictedTo != null) ResidencyState.EVICTED else ResidencyState.PARKED
        }

    /** v1.10 (F1): open [PixelPin]s on this layer (main only); > 0 forbids park. */
    internal var pins = 0

    /** v1.10 (F1): park, unpark, evict and materialize put the same pixels in another store: no [pixelGeneration] bump (J5). Main only. */
    internal fun rehouse(content: PixelStore, mask: PixelStore?) {
        synchronized(this) {
            store = content
            maskStore = mask
        }
    }

    /** The getter's materialization of [p] (content or mask): a FLAT bitmap with the same pixels, the old store released. Main only. */
    private fun materialize(p: PixelStore, mask: Boolean): Bitmap {
        PixelFacade.check(this)
        val b = when (p) {
            is TiledPixels -> p.materialize()
            else -> {
                val bytes = p.width.toLong() * p.height * 4
                val budget = EngineConfig.budget
                if (!budget.admitNative(bytes, NativePurpose.LAYER)) throw OutOfMemoryError("No room for a ${p.width}x${p.height} layer")
                budget.release(bytes, NativePurpose.LAYER)
                BitmapUtils.createLayerBitmap(p.width, p.height)
            }
        }
        PixelFacade.materializations++
        synchronized(this) { if (mask) maskStore = FlatPixels(b) else store = FlatPixels(b) }
        TileOps.dispose(p)
        return b
    }

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

    /**
     * v1.7: recycles this layer's bitmap and mask, but never [FOLDER_BITMAP]. v1.10 (F1): releases
     * the stores without materializing: a flat bitmap is recycled, a tiled store's tiles get one
     * holder fewer.
     */
    fun recycleBitmaps() { TileOps.dispose(store); TileOps.dispose(maskStore) }

    /** Incremented on every pixel change (content or mask). Used for thumbnails and dirty saving. */
    var contentVersion: Long = 0
        private set
    /** contentVersion at the time of the last successful save (managed by the storage layer). */
    var savedVersion: Long = -1

    fun markChanged() { contentVersion++ }

    val width: Int get() = store.width
    val height: Int get() = store.height

    /** Copies the non-pixel properties from [other]. */
    fun copyPropsFrom(other: LayerProps) {
        name = other.name; opacity = other.opacity; blendMode = other.blendMode; visible = other.visible
        clipping = other.clipping; alphaLocked = other.alphaLocked; locked = other.locked; maskEnabled = other.maskEnabled
    }

    fun props(): LayerProps = LayerProps(name, opacity, blendMode, visible, clipping, alphaLocked, locked, maskEnabled)

    /**
     * The bitmap that painting tools should currently modify (mask when editing it). Never a
     * folder's (v1.7): callers are refused earlier (`checkUsable`), so this is the last line of
     * defence. v1.10 (F1): the first write of a BLANK or PARKED store materializes it here (on
     * [PixelFacade]'s allow-list).
     */
    val paintTarget: Bitmap get() {
        check(!isFolder) { "A folder has no pixels to paint: $this" }
        return PixelFacade.allowing { if (editingMask) mask ?: bitmap else bitmap }
    }

    override fun toString(): String = "Layer($id '$name')"

    companion object {
        /** v1.7: the parent id of a top-level layer. Layer ids start at 1 (`Document.newLayerId`), so 0 is never a layer. */
        const val ROOT_ID = 0L

        /** v1.10 (F1): the lazy behind [FOLDER_BITMAP] (declared first: [FOLDER_BITMAP] delegates to it). */
        private val folderBitmap: Lazy<Bitmap> = lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, false) }

        /**
         * The one immutable 1×1 bitmap all folders share: drawing into it throws (V9). Never
         * recycled: see [recycleBitmaps] and [recycleUnlessShared].
         */
        val FOLDER_BITMAP: Bitmap by folderBitmap

        /** v1.10 (F1): [b] is [FOLDER_BITMAP] (asked without making it). */
        private fun isFolderBitmap(b: Bitmap): Boolean = folderBitmap.isInitialized() && b === FOLDER_BITMAP

        /** The store a v1.9 bitmap goes into: [FolderPixels] for [FOLDER_BITMAP], else [FlatPixels]. */
        private fun storeOf(b: Bitmap): PixelStore = if (isFolderBitmap(b)) FolderPixels else FlatPixels(b)

        /** v1.7: a new (empty, open) folder; it owns no layers until some are put into it. */
        fun newFolder(id: Long, name: String, spec: FolderSpec = FolderSpec()): Layer =
            Layer(id, name, FOLDER_BITMAP).apply { folder = spec }
    }
}

/**
 * v1.10 (F1, design 2.2): the facade's materializations ([Layer.bitmap] or [Layer.mask] of a
 * BLANK, PARKED or EVICTED store). [materializations] counts them (main only); with [strict]
 * (tests) one outside the allow-list ([allowing]: the first write through `Layer.paintTarget`)
 * throws [IllegalStateException].
 */
object PixelFacade {
    @Volatile
    var materializations = 0L

    @Volatile
    var strict = false

    /** How deep the current thread is inside [allowing]. */
    @PublishedApi
    internal val depth: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(1) }

    /** Runs [block] on the allow-list: a materialization inside it never throws under [strict]. */
    internal inline fun <T> allowing(block: () -> T): T {
        val d = depth.get()!!
        d[0]++
        try {
            return block()
        } finally {
            d[0]--
        }
    }

    /** Throws under [strict] outside [allowing]: [layer] is about to be materialized. */
    internal fun check(layer: Layer) {
        if (strict && depth.get()!![0] == 0) throw IllegalStateException("$layer materialized outside the allow-list (PixelFacade.strict)")
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

    private var activeIndex: Int = 0

    /**
     * v1.10 (F1, design 2.7): the setter records the change; at the end of the main-thread task
     * the residency hears `deactivated(old)` and `activate(new)` once when the active layer's id
     * changed (`EngineConfig`; nothing in Classic). The writers keep their source lines.
     */
    var activeLayerIndex: Int
        get() = activeIndex
        set(value) {
            activeIndex = value
            EngineConfig.activeIndexSet(this)
        }
    var createdAt: Long = System.currentTimeMillis()
    var modifiedAt: Long = System.currentTimeMillis()

    var grid: GridSettings = GridSettings()
    var ruler: RulerSettings = RulerSettings()

    /** v1.7 (item 14): saved selections, newest first; immutable entries, replaced as a whole (`SavedSelectionsAction`). */
    var savedSelections: List<SavedSelection> = emptyList()

    /** v1.7 (item 18): drawing aid like [ruler]; persisted, not undoable. */
    var symmetry: SymmetrySettings = SymmetrySettings()

    /**
     * R15: null = no paper (v1.8). Set at creation only; part of no undo step. Persisted
     * (`project.json` `paper`); drawn under the layers by `PaperBackdrop` only, never into a
     * layer (I21).
     */
    var paper: PaperSpec? = null

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
