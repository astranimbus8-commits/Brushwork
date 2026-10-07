package com.brushwork.paint.ui.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import kotlinx.coroutines.delay

/**
 * v1.7 (item 8, design §3.8): the small composite inside each folder row's glyph, [FOLDER_THUMB_PX]
 * on its longest side ([FolderComposite.renderBlockThumbnail]: the folder's layers composited
 * isolated, through a scaled canvas, never a document-sized bitmap).
 *
 * A picture is out of date when anything inside the folder changed ([key]: the block's ids,
 * parents, pixels, masks, adjustments and properties). The window renders the out-of-date ones
 * [FOLDER_THUMB_DEBOUNCE_MS] after the last change, one per frame ([FolderThumbnailEffect]); a row
 * shows its last picture meanwhile, the glyph alone before the first. Rendering reads layer
 * pixels, so it runs on the main thread (I3: the document is never read from another thread; a
 * layer bitmap may be replaced or recycled by any edit), within the §6.3 budget of 30 ms each.
 *
 * [image] is observable: a row recomposes when its picture lands.
 */
@Stable
internal class FolderThumbnails(private val maxSize: Int = FOLDER_THUMB_PX) {
    private val images = mutableStateMapOf<Long, ImageBitmap>()
    private val keys = HashMap<Long, Long>()

    /** The last picture of folder [id], or null before the first. */
    fun image(id: Long): ImageBitmap? = images[id]

    /** The folders of [layers] among [listed] (ids of the listed rows) whose picture is out of date, top first. */
    fun stale(layers: List<Layer>, listed: Set<Long>): List<Layer> {
        val out = ArrayList<Layer>()
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            if (l.isFolder && l.id in listed && keys[l.id] != key(layers, i)) out += l
        }
        return out
    }

    /**
     * Renders [folder]'s picture now (main thread). False when it is no longer a folder of [doc],
     * or without memory (the last picture stays).
     */
    fun render(compositor: Compositor, doc: Document, folder: Layer): Boolean {
        val i = doc.indexOf(folder)
        if (i < 0 || !folder.isFolder) return false
        val key = key(doc.layers, i)
        val bitmap = try {
            FolderComposite.renderBlockThumbnail(compositor, doc, folder, maxSize)
        } catch (e: OutOfMemoryError) {
            return false
        } catch (e: RuntimeException) {
            // A layer recycled under a closing editor: keep the last picture.
            return false
        }
        keys[folder.id] = key
        images[folder.id] = bitmap.asImageBitmap()
        return true
    }

    /** Drops the pictures of folders that no longer exist. */
    fun retain(ids: Set<Long>) {
        keys.keys.retainAll(ids)
        images.keys.retainAll(ids)
    }

    /** The number of folders with a picture. */
    val size: Int get() = images.size

    companion object {
        /**
         * What the picture of the folder at flat index [index] of [layers] depends on, hashed: for
         * every layer of its block (the folder's own row too, for its pass-through flag; its own
         * opacity, blend and eye are not drawn in it but cost nothing to include) the id, parent,
         * pixels (bitmap identity and `contentVersion`), mask, adjustment and properties.
         */
        fun key(layers: List<Layer>, index: Int): Long {
            var h = 1125899906842597L
            fun mix(v: Long) { h = 31 * h + v }
            for (i in LayerTree.block(layers, index)) {
                val l = layers[i]
                mix(l.id)
                mix(l.parentId)
                mix(System.identityHashCode(l.bitmap).toLong())
                mix(l.contentVersion)
                mix(System.identityHashCode(l.mask).toLong())
                mix(if (l.maskEnabled) 1 else 0)
                mix(l.adjustment?.hashCode()?.toLong() ?: 0)
                mix(l.opacity.toRawBits().toLong())
                mix(l.blendMode.ordinal.toLong())
                mix((if (l.visible) 1L else 0L) or (if (l.clipping) 2L else 0L) or (if (l.folder?.passThrough == true) 4L else 0L))
            }
            return h
        }
    }
}

/**
 * Renders the out-of-date pictures of the folders among [rows] (the listed ones)
 * [FOLDER_THUMB_DEBOUNCE_MS] after the last change of the document, one per frame, so a frame
 * pays for one folder at most. Every edit, undo, structure or property change restarts the wait.
 */
@Composable
internal fun FolderThumbnailEffect(controller: EditorController, thumbs: FolderThumbnails, rows: List<LayerRowModel>) {
    val layersVersion = controller.layersVersion
    val editCount = controller.editCount
    val docVersion = controller.docVersion
    LaunchedEffect(thumbs, rows, layersVersion, editCount, docVersion) {
        if (rows.none { it.isFolder }) return@LaunchedEffect
        delay(FOLDER_THUMB_DEBOUNCE_MS)
        val doc = controller.doc
        val listed = rows.mapTo(HashSet()) { it.layer.id }
        for (folder in thumbs.stale(doc.layers, listed)) {
            withFrameNanos { }
            thumbs.render(controller.compositor, doc, folder)
        }
    }
}

/** The folder rows' picture, longest side in pixels (design §3.8). */
internal const val FOLDER_THUMB_PX = 96

/** The pictures wait this long after the last change (design §3.8, §6.3). */
internal const val FOLDER_THUMB_DEBOUNCE_MS = 300L
