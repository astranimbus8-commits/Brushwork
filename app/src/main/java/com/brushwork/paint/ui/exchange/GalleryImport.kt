package com.brushwork.paint.ui.exchange

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brushwork.paint.exchange.ImportException
import com.brushwork.paint.exchange.ImportFile
import com.brushwork.paint.exchange.ImportKind
import com.brushwork.paint.exchange.ImportSource
import com.brushwork.paint.exchange.NewArtwork
import com.brushwork.paint.exchange.NewArtworkSpec
import com.brushwork.paint.exchange.PendingImport
import com.brushwork.paint.exchange.PendingImports
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.pdf.PageRasterizer
import com.brushwork.paint.exchange.pdf.PageRasterizerFactory
import com.brushwork.paint.exchange.pdf.PdfRendererRasterizer
import com.brushwork.paint.exchange.svg.SvgFormatException
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A PDF waiting for its pages to be chosen in the gallery. */
private class GalleryPdf(val file: ImportFile, val rasterizer: PageRasterizer, val uri: Uri) {
    fun close() {
        runCatching { rasterizer.close() }
        file.close()
    }
}

/**
 * Creating artworks from SVG / PDF files (v1.5 §4.11a): what size the new artwork gets and the
 * hand-off to the editor, which imports the file (one undo step). Separate from the composable
 * so it can be tested.
 */
internal object GalleryImports {
    /** How PDF pages are rendered (test seam). */
    var rasterizers: PageRasterizerFactory = PdfRendererRasterizer.factory

    /** Creates the artwork [spec] and hands [request] over to it; returns the project id. */
    suspend fun create(repository: ProjectRepository, spec: NewArtworkSpec, request: PendingImport): String {
        val id = repository.create(spec.canvas())
        PendingImports.putRequest(id, request)
        return id
    }

    /**
     * Decides the artwork for an SVG [file] (its Brushwork canvas, else its physical size);
     * throws [ImportException] when the file can't make one.
     */
    suspend fun svgSpec(file: ImportFile): NewArtworkSpec = withContext(Dispatchers.Default) {
        val svg = SvgParser.parse(file.bytes!!)
        val heap = Runtime.getRuntime().maxMemory()
        // Damaged Brushwork data: the file is still an SVG of its own size (the editor says so).
        val p = if (svg.hasPayload) {
            try {
                svg.payload()
            } catch (e: java.io.IOException) {
                null
            }
        } else {
            null
        }
        if (p != null) {
            NewArtwork.forPayload(file.name, p, heap) ?: throw ImportException("This artwork is too large for this device")
        } else {
            NewArtwork.forSvg(file.name, svg, heap)
        }
    }

    /** The Brushwork canvas of a PDF [file], or null for a foreign PDF. */
    suspend fun pdfPayloadSpec(file: ImportFile): NewArtworkSpec? = withContext(Dispatchers.IO) {
        val p = try {
            OwnPdfReader.open(file.file!!).use { r -> if (r.hasPayload()) r.payload() else null }
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        NewArtwork.forPayload(file.name, p, Runtime.getRuntime().maxMemory()) ?: throw ImportException("This artwork is too large for this device")
    }
}

/**
 * The gallery's "New from SVG or PDF" (v1.5 §4.11; owned by A8): returns the action that picks
 * a file, creates a project of the right size (an SVG at its physical size at 350 dpi, a PDF page
 * at 300 dpi after choosing the pages, a Brushwork file exactly), hands the file over through
 * [PendingImports] and opens it with [onOpenProject].
 */
@Composable
fun rememberGalleryImport(repository: ProjectRepository, onOpenProject: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf<GalleryPdf?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    suspend fun open(spec: NewArtworkSpec, request: PendingImport) {
        val id = GalleryImports.create(repository, spec, request)
        onOpenProject(id)
    }

    fun start(uri: Uri) {
        if (busy) return
        busy = true
        scope.launch {
            var keep = false
            var file: ImportFile? = null
            try {
                val f = withContext(Dispatchers.IO) { ImportSource.open(context.applicationContext, uri) }
                file = f
                when (f.kind) {
                    ImportKind.SVG -> open(GalleryImports.svgSpec(f), PendingImport(uri))
                    ImportKind.PDF -> {
                        val payload = GalleryImports.pdfPayloadSpec(f)
                        if (payload != null) {
                            open(payload, PendingImport(uri))
                        } else {
                            val r = withContext(Dispatchers.IO) { GalleryImports.rasterizers.open(context.applicationContext, f.file!!) }
                            val count = r.pageCount
                            if (count <= 0) {
                                r.close()
                                throw ImportException("This PDF has no pages")
                            }
                            if (count == 1) {
                                val size = withContext(Dispatchers.IO) { r.pageSize(0) }
                                r.close()
                                open(NewArtwork.forPdfPage(f.name, size.first, size.second, Runtime.getRuntime().maxMemory()), PendingImport(uri, pages = listOf(0)))
                            } else {
                                pdf = GalleryPdf(f, r, uri)
                                keep = true
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                toast(e.message ?: "The file can't be opened")
            } catch (e: SvgFormatException) {
                toast("This SVG can't be read: ${e.message}")
            } catch (e: OutOfMemoryError) {
                toast("Not enough memory to open this file")
            } catch (e: Exception) {
                toast("The file can't be opened: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (!keep) file?.close()
                busy = false
            }
        }
    }

    fun pagesChosen(p: GalleryPdf, pages: List<Int>, transparent: Boolean) {
        pdf = null
        busy = true
        scope.launch {
            try {
                val first = pages.minOrNull() ?: 0
                val size = withContext(Dispatchers.IO) { p.rasterizer.pageSize(first) }
                open(
                    NewArtwork.forPdfPage(p.file.name, size.first, size.second, Runtime.getRuntime().maxMemory()),
                    PendingImport(p.uri, pages = pages.sorted(), transparentPages = transparent),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("The file can't be opened: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                withContext(Dispatchers.IO) { p.close() }
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) start(uri) }
    DisposableEffect(Unit) {
        onDispose { pdf?.close() }
    }

    if (busy) {
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(color = BrushworkColors.ChromeHigh, contentColor = BrushworkColors.OnChrome, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("Opening the file…")
                }
            }
        }
    }
    pdf?.let { p ->
        PdfPagePicker(
            rasterizer = p.rasterizer,
            onDismiss = {
                pdf = null
                p.close()
            },
            onImport = { pages, transparent -> pagesChosen(p, pages, transparent) },
            title = "New artwork from PDF pages",
        )
    }
    return remember(picker, context) {
        {
            try {
                picker.launch(IMPORT_MIME_TYPES)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "No app available to pick files", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
