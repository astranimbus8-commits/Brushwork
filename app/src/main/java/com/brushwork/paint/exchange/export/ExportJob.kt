package com.brushwork.paint.exchange.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.storage.ImageExport
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.editor.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * One SVG / PDF export of the open document (v1.5 §4.10): the scene is built on the main thread
 * ([ExportSceneBuilder]), written on IO ([SvgWriter] / [PdfWriter]) and delivered to a file the
 * user picked (Storage Access Framework: no permission on any Android version) or to the share
 * sheet. Runs behind the busy overlay with Stop; a stopped or failed export leaves no file.
 */
class ExportJob(private val c: EditorController, private val options: ExportOptions) {

    /** The file name for the artwork ("My drawing.svg"). */
    val fileName: String get() = ImageExport.safeFileName(c.doc.name) + "." + options.format.extension

    /** Builds the scene (call on the main thread) and writes it to [out] on IO. */
    suspend fun write(out: OutputStream, progress: (Float) -> Unit = {}): ExportScene {
        val scene = ExportSceneBuilder(c, options).build()
        withContext(Dispatchers.IO) { writeScene(scene, out, progress) }
        return scene
    }

    private suspend fun writeScene(scene: ExportScene, out: OutputStream, progress: (Float) -> Unit) {
        when (options.format) {
            VectorFormat.SVG -> SvgWriter(scene, progress).write(out)
            VectorFormat.PDF -> PdfWriter(scene, options.page, progress).write(out)
        }
        out.flush()
    }

    /** Exports into the document [uri] (from `CreateDocument`); deleted again when stopped or failed. */
    fun saveTo(context: Context, uri: Uri) {
        if (!ready()) return
        val holder = arrayOfNulls<Job>(1)
        c.runBusy("Exporting ${options.format.name}", onCancel = { holder[0]?.cancel() }) {
            holder[0] = coroutineContext[Job]
            letOverlayShow()
            val resolver = context.contentResolver
            var done = false
            try {
                val scene = ExportSceneBuilder(c, options).build()
                withContext(Dispatchers.IO) {
                    val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("The file could not be opened")
                    stream.use { s -> writeScene(scene, BufferedOutputStream(s, BUFFER), ::progress) }
                }
                done = true
                c.toast(withNotes("Saved \"$fileName\"", scene))
            } finally {
                if (!done) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                    }
                }
            }
        }
    }

    /** Exports into the share cache and opens the system share sheet. */
    fun share(context: Context) {
        if (!ready()) return
        val holder = arrayOfNulls<Job>(1)
        c.runBusy("Preparing to share", onCancel = { holder[0]?.cancel() }) {
            holder[0] = coroutineContext[Job]
            letOverlayShow()
            val dir = File(context.cacheDir, SHARE_DIR)
            val file = File(dir, fileName)
            var done = false
            try {
                val scene = ExportSceneBuilder(c, options).build()
                withContext(Dispatchers.IO) {
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the share folder")
                    pruneOldShares(dir)
                    FileOutputStream(file).use { s -> writeScene(scene, BufferedOutputStream(s, BUFFER), ::progress) }
                }
                done = true
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = options.format.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(c.doc.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share ${options.format.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val activity = context.findActivity()
                try {
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed) activity.startActivity(chooser)
                    else context.applicationContext.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    c.toast("No app available to share with")
                }
                if (scene.notes.isNotEmpty()) c.toast(withNotes("Ready to share", scene))
            } finally {
                if (!done) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }
    }

    private fun progress(f: Float) {
        c.scope.launch(Dispatchers.Main) { c.busyProgress = f }
    }

    private fun withNotes(text: String, scene: ExportScene): String =
        if (scene.notes.isEmpty()) text else text + " · " + scene.notes.joinToString(" · ")

    /** False (with a message) while another operation runs or a filter is previewed; commits the tool's pending work. */
    private fun ready(): Boolean {
        if (c.busyMessage != null) return false
        if (c.filterSession != null) {
            c.toast("Apply or cancel the filter first")
            return false
        }
        c.endCanvasGesture()
        val tool = c.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            c.invalidateOverlay()
        }
        return true
    }

    /** Lets the busy scrim reach the screen before the main-thread part starts (bounded: no frames in the background). */
    private suspend fun letOverlayShow() {
        withTimeoutOrNull(150L) {
            awaitFrame()
            awaitFrame()
        }
    }

    private fun pruneOldShares(dir: File) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
    }

    private companion object {
        const val SHARE_DIR = "exports"
        const val BUFFER = 256 * 1024
    }
}
