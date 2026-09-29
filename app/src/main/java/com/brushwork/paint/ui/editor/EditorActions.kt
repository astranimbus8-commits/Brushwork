package com.brushwork.paint.ui.editor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.ImageImport
import com.brushwork.paint.storage.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

/** The hosting activity (LocalContext may be a ContextWrapper around it). */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Import / export / share operations of the editor menu (all run with the busy overlay). */
internal class EditorActions(private val controller: EditorController, private val context: Context) {
    private val app: BrushworkApp get() = context.applicationContext as BrushworkApp

    /** Decodes a picked image off the main thread and places it on a new layer. */
    fun importPicture(uri: Uri) {
        if (!readyForDocumentAction()) return
        if (!controller.canAddLayer) {
            controller.toast("Layer limit reached (${controller.maxLayers}) for this canvas size")
            return
        }
        val doc = controller.doc
        val maxDim = min(8192, 2 * max(doc.width, doc.height))
        val appContext = context.applicationContext
        controller.runBusy("Importing picture") {
            val bitmap = withContext(Dispatchers.IO) { ImageImport.decode(appContext, uri, maxDim) }
            val layersBefore = doc.layers.size
            controller.importImageAsLayer(bitmap)
            // No layer was added (limit or out of memory, already reported): the picture was not
            // handed to the transform tool, so free it now instead of waiting for the GC.
            if (doc.layers.size == layersBefore) bitmap.recycle()
        }
    }

    /** Flattens the artwork and saves it to the device gallery. */
    fun exportToGallery(format: ExportFormat) {
        if (!readyForDocumentAction()) return
        commitPendingWork()
        controller.runBusy("Exporting ${format.extension.uppercase()}") {
            letOverlayShow()
            // JPEG has no alpha: flatten over white like other painting apps.
            val flat = controller.compositor.renderFlattened(if (format == ExportFormat.JPEG) 0xFFFFFFFF.toInt() else null)
            val uri = try {
                app.repository.exportToGallery(flat, controller.doc.name, format)
            } finally {
                flat.recycle()
            }
            controller.toast(if (uri != null) "Saved \"${controller.doc.name}.${format.extension}\" to Pictures/Brushwork" else "Could not save the image to the gallery")
        }
    }

    /** Flattens the artwork to a PNG in the share cache and opens the system share sheet. */
    fun share() {
        if (!readyForDocumentAction()) return
        commitPendingWork()
        controller.runBusy("Preparing to share") {
            letOverlayShow()
            val flat = controller.compositor.renderFlattened()
            val uri = try {
                app.repository.exportForShare(flat, controller.doc.name, ExportFormat.PNG)
            } finally {
                flat.recycle()
            }
            if (uri == null) {
                controller.toast("Could not prepare the image for sharing")
                return@runBusy
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = ExportFormat.PNG.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(controller.doc.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share artwork").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val activity = context.findActivity()
            try {
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    activity.startActivity(chooser)
                } else {
                    context.applicationContext.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: ActivityNotFoundException) {
                controller.toast("No app available to share with")
            }
        }
    }

    /**
     * False (with a message) while another long operation runs or a filter is being previewed.
     * Otherwise ends any canvas stroke still in progress so the action sees a settled document.
     */
    private fun readyForDocumentAction(): Boolean {
        if (controller.busyMessage != null) return false
        if (controller.filterSession != null) {
            controller.toast("Apply or cancel the filter first")
            return false
        }
        controller.endCanvasGesture()
        return true
    }

    /** Bakes uncommitted tool work (placed text, transform...) so exports include it. */
    private fun commitPendingWork() {
        val tool = controller.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            controller.invalidateOverlay()
        }
    }

    /**
     * runBusy starts on Main.immediate: wait for frames so the busy scrim is on screen before
     * the (blocking) full-resolution flatten starts. Bounded, because no frames arrive while the
     * app is in the background.
     */
    private suspend fun letOverlayShow() {
        withTimeoutOrNull(OVERLAY_WAIT_MS) {
            awaitFrame()
            awaitFrame()
        }
    }

    private companion object {
        const val OVERLAY_WAIT_MS = 150L
    }
}
