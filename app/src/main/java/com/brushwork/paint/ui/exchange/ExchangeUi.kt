package com.brushwork.paint.ui.exchange

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.VectorFormat

/**
 * The editor's SVG / PDF import and export (v1.5 §4.10, §4.11; owned by A8): the overflow
 * menu entries call [requestImport] / [requestExport], an imported file handed over by the
 * gallery arrives through [importUri]; [ExchangeHost] shows the pickers, sheets and progress.
 * Foundation stub: the requests only say they are coming.
 */
class ExchangeUiState(private val controller: EditorController) {
    /** "Import SVG or PDF…": pick a file and import it into the artwork. */
    fun requestImport() {
        controller.toast("Importing SVG and PDF is coming soon")
    }

    /** "Export SVG…" / "Export PDF…": options sheet, then Save as… or Share. */
    fun requestExport(f: VectorFormat) {
        controller.toast("Exporting ${f.name} is coming soon")
    }

    /** Imports the SVG / PDF file at [uri] into the open artwork (one undo step). */
    fun importUri(uri: Uri) {
        controller.toast("Importing SVG and PDF is coming soon")
    }
}

@Composable
fun rememberExchangeUi(controller: EditorController): ExchangeUiState = remember(controller) { ExchangeUiState(controller) }

/** Hosts the exchange pickers, sheets and dialogs of [state] (call once in the editor). */
@Composable
fun ExchangeHost(state: ExchangeUiState) {}
