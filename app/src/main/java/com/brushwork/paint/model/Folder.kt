package com.brushwork.paint.model

import android.graphics.Bitmap
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * v1.7 (item 8, I11): what makes a [Layer] a FOLDER (`Layer.folder != null`). A folder owns the
 * block of layers directly below it in the flat `Document.layers` list (`LayerTree`); it has no
 * pixels of its own ([Layer.FOLDER_BITMAP]) and no mask. Rides `LayerData` (the pass-through
 * toggle is a `LayerDataAction`).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FolderSpec(
    /**
     * Children composite straight into what is below (default; a Brushwork decision). Off:
     * isolated, drawn with the folder's blend mode.
     *
     * ALWAYS encoded: FolderSpec exists only in format-3 files, so this costs no compatibility,
     * and a later change of the default can never silently change a saved folder (the I13
     * exception).
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val passThrough: Boolean = true,
)

/**
 * v1.7: every recycle of a layer's, an import's or a canvas result's bitmap goes through this
 * (sweep rule B, §4.4): `ProjectRepository.renderFlattened` (via [Layer.recycleBitmaps]),
 * `PayloadImport`, `PdfImport`, `VectorImport`, `ExchangeUi` (prepared new layers, which carry
 * [Layer.FOLDER_BITMAP] for payload-v2 folders) and `CanvasOps`. The shared folder bitmap is never
 * recycled, and an already recycled bitmap is left alone.
 */
fun Bitmap.recycleUnlessShared() { if (this !== Layer.FOLDER_BITMAP && !isRecycled) recycle() }
