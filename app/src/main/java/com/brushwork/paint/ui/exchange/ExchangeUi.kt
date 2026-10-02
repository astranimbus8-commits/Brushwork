package com.brushwork.paint.ui.exchange

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.ImportException
import com.brushwork.paint.exchange.ImportFile
import com.brushwork.paint.exchange.ImportKind
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.ImportOutcome
import com.brushwork.paint.exchange.ImportSource
import com.brushwork.paint.exchange.ImportTarget
import com.brushwork.paint.exchange.NewLayer
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.PendingImport
import com.brushwork.paint.exchange.PendingImports
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.VectorImport
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.ExportJob
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.pdf.PageRasterizer
import com.brushwork.paint.exchange.pdf.PageRasterizerFactory
import com.brushwork.paint.exchange.pdf.PdfImport
import com.brushwork.paint.exchange.svg.SvgDocument
import com.brushwork.paint.exchange.svg.SvgFormatException
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** File types the import picker offers (the content decides what it is). */
internal val IMPORT_MIME_TYPES = arrayOf("image/svg+xml", "application/pdf", "text/xml", "application/xml", "application/octet-stream")

/** A Brushwork file whose layer data is damaged (its SVG content or PDF pages come in instead). */
internal const val UNREADABLE_DATA = "The Brushwork data in this file can't be read"

/** A question the import asks before going on. */
sealed class ExchangeDialog {
    /** A Brushwork file: its layers (editable) or a picture of it? */
    class MadeWithBrushwork(internal val file: ImportFile, internal val svg: SvgDocument?) : ExchangeDialog()

    /** An SVG over the limits: import what was read as a picture? */
    class TooComplex(internal val file: ImportFile, internal val svg: SvgDocument, internal val newArtwork: Boolean = false) : ExchangeDialog()

    /** Pick the pages of a PDF. */
    class Pages(internal val file: ImportFile, val rasterizer: PageRasterizer, internal val newArtwork: Boolean) : ExchangeDialog()
}

/**
 * The editor's SVG / PDF import and export (v1.5 §4.10, §4.11; owned by A8): the overflow menu
 * entries call [requestImport] / [requestExport], an imported file handed over by the gallery
 * arrives through [importUri]; [ExchangeHost] shows the pickers, the export sheet and the import
 * questions. Imports run behind the busy overlay and are one undo step each.
 */
class ExchangeUiState(internal val controller: EditorController) {
    /**
     * How PDF pages are rendered (test seam: Robolectric has no PdfRenderer). The gallery's
     * factory by default, so a test replaces both with one assignment.
     */
    internal var rasterizers: PageRasterizerFactory = GalleryImports.rasterizers

    /** The system pickers and the context, provided by [ExchangeHost]. */
    internal var openPicker: (() -> Unit)? = null
    internal var createPicker: ((VectorFormat, String) -> Unit)? = null
    internal var context: Context? = null

    /** The export sheet's format while it is open. */
    var exportSheet by mutableStateOf<VectorFormat?>(null)
        internal set

    /** The options of the export sheet (kept for the session). */
    var exportOptions by mutableStateOf(ExportOptions(VectorFormat.SVG))
        internal set

    /** The import question being asked, if any. */
    var dialog by mutableStateOf<ExchangeDialog?>(null)
        internal set

    /** "Import SVG or PDF…": pick a file and import it into the artwork. */
    fun requestImport() {
        val open = openPicker
        if (open == null) {
            controller.toast("No app available to pick files")
            return
        }
        try {
            open()
        } catch (e: ActivityNotFoundException) {
            controller.toast("No app available to pick files")
        }
    }

    /** "Export SVG…" / "Export PDF…": options sheet, then Save as… or Share. */
    fun requestExport(f: VectorFormat) {
        if (controller.filterSession != null) {
            controller.toast("Apply or cancel the filter first")
            return
        }
        exportOptions = exportOptions.copy(format = f)
        exportSheet = f
    }

    internal fun closeExportSheet() {
        exportSheet = null
    }

    /** Save as… from the sheet: the system's file picker, then the export into that file. */
    internal fun saveAs() {
        val options = exportOptions
        closeExportSheet()
        val create = createPicker ?: run { controller.toast("No app available to save files"); return }
        try {
            create(options.format, ExportJob(controller, options).fileName)
        } catch (e: ActivityNotFoundException) {
            controller.toast("No app available to save files")
        }
    }

    /** The picked destination of Save as…. */
    internal fun exportTo(uri: Uri) {
        val ctx = context ?: controller.appContext
        ExportJob(controller, exportOptions).saveTo(ctx, uri)
    }

    /** Share from the sheet. */
    internal fun share() {
        val ctx = context ?: controller.appContext
        val options = exportOptions
        closeExportSheet()
        ExportJob(controller, options).share(ctx)
    }

    // ------------------------------------------------------------------ import

    /** Imports the SVG / PDF file at [uri] into the open artwork (one undo step). */
    fun importUri(uri: Uri) {
        val ctx = context ?: controller.appContext
        val details = PendingImports.details(controller.doc.id, uri)
        if (!ready()) return
        busy("Importing") {
            val file = withContext(Dispatchers.IO) { ImportSource.open(ctx, uri) }
            var handedOver = false
            try {
                handedOver = handle(file, details)
            } finally {
                if (!handedOver) withContext(Dispatchers.IO) { file.close() }
            }
        }
    }

    /** Imports [file]; true when a dialog now owns it. */
    private suspend fun handle(file: ImportFile, details: PendingImport?): Boolean {
        val newArtwork = details?.newArtwork == true
        when (file.kind) {
            ImportKind.SVG -> {
                val svg = withContext(Dispatchers.Default) { SvgParser.parse(file.bytes!!) }
                when {
                    // Brushwork data that can't be read: the file is still an ordinary SVG.
                    svg.hasPayload && newArtwork -> if (!restoreSvgPayload(svg, newArtwork = true)) return importSvg(svg, true, asPicture = false, file = file)
                    svg.hasPayload -> { dialog = ExchangeDialog.MadeWithBrushwork(file, svg); return true }
                    svg.truncated -> { dialog = ExchangeDialog.TooComplex(file, svg, newArtwork); return true }
                    else -> return importSvg(svg, newArtwork, asPicture = false, file = file)
                }
            }
            ImportKind.PDF -> {
                val pdf = file.file!!
                val hasPayload = withContext(Dispatchers.IO) {
                    try {
                        OwnPdfReader.open(pdf).use { it.hasPayload() }
                    } catch (e: Exception) {
                        false
                    }
                }
                if (hasPayload && newArtwork) {
                    // Brushwork data that can't be read: its pages are still there.
                    if (!restorePdfPayload(file, newArtwork = true)) return pdfPages(file, details)
                } else if (hasPayload) {
                    dialog = ExchangeDialog.MadeWithBrushwork(file, null)
                    return true
                } else {
                    return pdfPages(file, details)
                }
            }
        }
        return false
    }

    /** A foreign (or picture-wanted) PDF: its pages; true when the page picker now owns [file]. */
    private suspend fun pdfPages(file: ImportFile, details: PendingImport?): Boolean {
        val ctx = context ?: controller.appContext
        val r = withContext(Dispatchers.IO) { rasterizers.open(ctx, file.file!!) }
        var owned = false
        try {
            val count = r.pageCount
            if (count <= 0) throw ImportException("This PDF has no pages")
            val newArtwork = details?.newArtwork == true
            val chosen = details?.pages?.filter { it in 0 until count }
            when {
                chosen != null && chosen.isNotEmpty() -> importPages(r, chosen, details.transparentPages, newArtwork)
                count == 1 -> importPages(r, listOf(0), transparent = false, newArtwork = newArtwork)
                else -> {
                    dialog = ExchangeDialog.Pages(file, r, newArtwork)
                    owned = true
                }
            }
        } finally {
            if (!owned) withContext(Dispatchers.IO) { r.close() }
        }
        return owned
    }

    private suspend fun importPages(r: PageRasterizer, pages: List<Int>, transparent: Boolean, newArtwork: Boolean) {
        val target = target()
        val job = coroutineContext[Job]
        if (pages.size == 1 && !newArtwork) {
            // One page: placed with the Transform tool, like an imported picture.
            val page = withContext(Dispatchers.IO) { PdfImport.renderPage(r, pages[0], target, transparent) }
            val before = controller.doc.layers.size
            controller.importImageAsLayer(page, "Page ${pages[0] + 1}")
            if (controller.doc.layers.size == before) page.recycle() else controller.toast("Imported page ${pages[0] + 1}")
            return
        }
        val replace = if (newArtwork) defaultLayers(includeBackground = false) else emptyList()
        val layers = withContext(Dispatchers.IO) {
            PdfImport.prepare(r, pages, target.copy(room = target.room + replace.size), transparent) { job?.isActive == false }
        }
        stopIfCancelled(layers)
        report(PdfImport.apply(controller, layers, pages.size, replace))
    }

    /**
     * Imports [svg] (from [file]); true when the "too complex" question now owns [file] (a file
     * over the point or element limits, found while converting it).
     */
    private suspend fun importSvg(svg: SvgDocument, newArtwork: Boolean, asPicture: Boolean, file: ImportFile? = null): Boolean {
        val replace = if (newArtwork) defaultLayers(includeBackground = false) else emptyList()
        val target = target().let { it.copy(room = it.room + replace.size) }
        val prepared = withContext(Dispatchers.Default) {
            val job = coroutineContext[Job]
            VectorImport.prepare(svg, target, newArtwork, asPicture) { job?.isActive == false }
        }
        if (prepared.tooComplex) {
            dialog = ExchangeDialog.TooComplex(file ?: ImportFile(ImportKind.SVG, "", null, null), svg, newArtwork)
            return true
        }
        stopIfCancelled(prepared.layers)
        report(VectorImport.apply(controller, prepared, replace))
        return false
    }

    /** Restores the Brushwork layers of [svg]; false (with a message) when its data can't be read. */
    private suspend fun restoreSvgPayload(svg: SvgDocument, newArtwork: Boolean): Boolean {
        val payload = withContext(Dispatchers.Default) { readable { svg.payload() } } ?: return unreadableData()
        restore(payload, newArtwork) { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }
        return true
    }

    /** Restores the Brushwork layers of a PDF; false (with a message) when its data can't be read. */
    private suspend fun restorePdfPayload(file: ImportFile, newArtwork: Boolean): Boolean {
        val reader = withContext(Dispatchers.IO) { readable { OwnPdfReader.open(file.file!!) } } ?: return unreadableData()
        reader.use {
            val payload = withContext(Dispatchers.IO) { readable { reader.payload() } } ?: return unreadableData()
            restore(payload, newArtwork) { key -> reader.payloadImage(key) }
        }
        return true
    }

    /** [block]'s result, or null when the file's data is damaged. */
    private inline fun <T> readable(block: () -> T?): T? = try {
        block()
    } catch (e: java.io.IOException) {
        null
    }

    /** Says the file's Brushwork data is damaged (repeated in the summary of what comes in instead). */
    private fun unreadableData(): Boolean {
        controller.toast(UNREADABLE_DATA)
        note = UNREADABLE_DATA
        return false
    }

    /** Said again after the summary of the running import. */
    private var note: String? = null

    private suspend fun restore(payload: BrushworkPayload, newArtwork: Boolean, images: PayloadImport.Images) {
        val replace = if (newArtwork) defaultLayers(includeBackground = true) else emptyList()
        // A new artwork made for the file takes its color mode (it has nothing else yet), in
        // the import's undo step.
        val mode = if (newArtwork && replace.size == controller.doc.layers.size) payload.colorMode else null
        val target = target().let { it.copy(room = it.room + replace.size, colorMode = mode ?: it.colorMode) }
        val prepared = withContext(Dispatchers.IO) { PayloadImport.prepare(payload, images, target) }
        stopIfCancelled(prepared.layers)
        report(PayloadImport.apply(controller, prepared, replace, mode))
    }

    // ------------------------------------------------------------------ dialog answers

    /** "Editable layers" for a Brushwork file (its data unreadable: the SVG's content, the PDF's pages). */
    internal fun answerEditable() {
        val d = dialog as? ExchangeDialog.MadeWithBrushwork ?: return
        dialog = null
        busy("Importing") {
            var handedOver = false
            try {
                if (d.svg != null) {
                    if (!restoreSvgPayload(d.svg, newArtwork = false)) handedOver = importSvg(d.svg, newArtwork = false, asPicture = false, file = d.file)
                } else if (!restorePdfPayload(d.file, newArtwork = false)) {
                    handedOver = pdfPages(d.file, null)
                }
            } finally {
                if (!handedOver) withContext(Dispatchers.IO) { d.file.close() }
            }
        }
    }

    /** "Picture" for a Brushwork file. */
    internal fun answerPicture() {
        val d = dialog as? ExchangeDialog.MadeWithBrushwork ?: return
        dialog = null
        busy("Importing") {
            var handedOver = false
            try {
                if (d.svg != null) {
                    if (!payloadPicture(d.svg)) importSvg(d.svg, newArtwork = false, asPicture = true)
                } else {
                    handedOver = pdfPages(d.file, null)
                }
            } finally {
                if (!handedOver) withContext(Dispatchers.IO) { d.file.close() }
            }
        }
    }

    /**
     * A Brushwork SVG "as a picture": its layers restored and composited exactly as the editor
     * draws them, into one layer placed with Transform (one undo step). The SVG drawing can't show
     * everything (layer masks, blend modes). False when its data can't be read or its layers don't
     * fit in memory: the SVG is drawn instead.
     */
    private suspend fun payloadPicture(svg: SvgDocument): Boolean {
        val payload = withContext(Dispatchers.Default) { readable { svg.payload() } } ?: return false
        val target = target()
        val picture = withContext(Dispatchers.Default) {
            PayloadImport.picture(payload, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, target)
        } ?: return false
        if (!coroutineContext.isActive) {
            picture.recycle()
            coroutineContext.ensureActive()
        }
        val created = ImportLayers.insert(controller, listOf(NewLayer(VectorImport.PICTURE_NAME, picture)), VectorImport.LABEL)
        created.firstOrNull()?.let { layer ->
            controller.selectLayer(layer)
            VectorImport.openTransform(controller)
        }
        report(ImportOutcome(pictures = 1, layers = created.size))
        return true
    }

    /** "Import as a picture" for an SVG over the limits. */
    internal fun answerTooComplex(import: Boolean) {
        val d = dialog as? ExchangeDialog.TooComplex ?: return
        dialog = null
        if (!import) { d.file.close(); return }
        busy("Importing") {
            try {
                importSvg(d.svg, newArtwork = d.newArtwork, asPicture = true)
            } finally {
                withContext(Dispatchers.IO) { d.file.close() }
            }
        }
    }

    /** The page picker's answer (null = cancelled). */
    internal fun answerPages(pages: List<Int>?, transparent: Boolean) {
        val d = dialog as? ExchangeDialog.Pages ?: return
        dialog = null
        if (pages.isNullOrEmpty()) {
            d.rasterizer.close()
            d.file.close()
            return
        }
        busy("Importing") {
            try {
                importPages(d.rasterizer, pages.sorted(), transparent, d.newArtwork)
            } finally {
                withContext(Dispatchers.IO) {
                    d.rasterizer.close()
                    d.file.close()
                }
            }
        }
    }

    /** Closes whatever an open question holds (the editor goes away). */
    internal fun dispose() {
        when (val d = dialog) {
            is ExchangeDialog.MadeWithBrushwork -> d.file.close()
            is ExchangeDialog.TooComplex -> d.file.close()
            is ExchangeDialog.Pages -> { d.rasterizer.close(); d.file.close() }
            null -> {}
        }
        dialog = null
    }

    // ------------------------------------------------------------------ helpers

    /** Stopped meanwhile: the prepared layers are freed and nothing is inserted. */
    private suspend fun stopIfCancelled(layers: List<com.brushwork.paint.exchange.NewLayer>) {
        if (coroutineContext.isActive) return
        layers.forEach { l ->
            l.bitmap.recycle()
            l.mask?.recycle()
        }
        coroutineContext.ensureActive()
    }

    private fun target(): ImportTarget {
        val doc = controller.doc
        return ImportTarget(doc.width, doc.height, doc.dpi, doc.colorMode, ImportLayers.room(controller), controller.maxLayers)
    }

    /**
     * The layers a new artwork was created with, when still untouched: its empty "Layer 1"
     * (and, for a Brushwork restore, its plain Background too).
     */
    private fun defaultLayers(includeBackground: Boolean): List<Layer> {
        val layers = controller.doc.layers
        val out = ArrayList<Layer>()
        for (l in layers) {
            val plain = l.mask == null && l.dataSnapshot().isEmpty && !l.locked
            if (!plain) continue
            if (controller.isEmptyPlainLayer(l)) out += l
            else if (includeBackground && l === layers.first()) out += l
        }
        return out
    }

    private fun report(o: ImportOutcome) {
        controller.toast(ImportSummary.format(o) + (note?.let { " · $it" } ?: ""))
        note = null
    }

    private fun ready(): Boolean {
        if (controller.busyMessage != null) return false
        if (controller.filterSession != null) {
            controller.toast("Apply or cancel the filter first")
            return false
        }
        controller.endCanvasGesture()
        val tool = controller.currentTool
        if (tool.hasPendingWork) {
            tool.commit()
            controller.invalidateOverlay()
        }
        return true
    }

    /** Runs [block] behind the busy overlay with Stop; file problems show their own message. */
    private fun busy(label: String, block: suspend () -> Unit) {
        val holder = arrayOfNulls<Job>(1)
        controller.runBusy(label, onCancel = { holder[0]?.cancel() }) {
            holder[0] = coroutineContext[Job]
            note = null
            try {
                block()
            } catch (e: ImportException) {
                controller.toast(e.message ?: "The file can't be imported")
            } catch (e: SvgFormatException) {
                controller.toast("This SVG can't be read: ${e.message}")
            }
        }
    }
}

@Composable
fun rememberExchangeUi(controller: EditorController): ExchangeUiState = remember(controller) { ExchangeUiState(controller) }

/** Hosts the exchange pickers, sheets and dialogs of [state] (call once in the editor). */
@Composable
fun ExchangeHost(state: ExchangeUiState) {
    val context = LocalContext.current
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) state.importUri(uri) }
    val createSvg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VectorFormat.SVG.mime)) { uri -> if (uri != null) state.exportTo(uri) }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VectorFormat.PDF.mime)) { uri -> if (uri != null) state.exportTo(uri) }
    DisposableEffect(state, context, open, createSvg, createPdf) {
        // The activity's own context: Share starts the chooser from it.
        state.context = context
        state.openPicker = { open.launch(IMPORT_MIME_TYPES) }
        state.createPicker = { f, name -> if (f == VectorFormat.SVG) createSvg.launch(name) else createPdf.launch(name) }
        onDispose {
            state.dispose()
            state.context = null
            state.openPicker = null
            state.createPicker = null
        }
    }
    state.exportSheet?.let { ExportOptionsSheet(state) }
    when (val d = state.dialog) {
        is ExchangeDialog.MadeWithBrushwork -> AlertDialog(
            onDismissRequest = {
                state.dialog = null
                d.file.close()
            },
            title = { Text("Made with Brushwork") },
            text = { Text("This file holds its Brushwork layers. Import them as editable layers, or the artwork as a picture?") },
            confirmButton = { TextButton(onClick = state::answerEditable) { Text("Editable layers") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        state.dialog = null
                        d.file.close()
                    }) { Text("Cancel") }
                    TextButton(onClick = state::answerPicture) { Text("Picture") }
                }
            },
            containerColor = BrushworkColors.ChromeHigh,
        )
        is ExchangeDialog.TooComplex -> AlertDialog(
            onDismissRequest = { state.answerTooComplex(false) },
            title = { Text("Very complex SVG") },
            text = {
                Text(
                    "This SVG has more than ${SvgDocument.MAX_ELEMENTS / 1000}k elements or 2 million points, more than " +
                        "Brushwork keeps editable. Import what fits as a picture?",
                )
            },
            confirmButton = { TextButton(onClick = { state.answerTooComplex(true) }) { Text("As a picture") } },
            dismissButton = { TextButton(onClick = { state.answerTooComplex(false) }) { Text("Cancel") } },
            containerColor = BrushworkColors.ChromeHigh,
        )
        is ExchangeDialog.Pages -> PdfPagePicker(
            rasterizer = d.rasterizer,
            onDismiss = { state.answerPages(null, false) },
            onImport = { pages, transparent -> state.answerPages(pages, transparent) },
        )
        null -> {}
    }
}
