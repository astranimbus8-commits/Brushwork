package com.brushwork.paint.ui.exchange

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PdfPage
import com.brushwork.paint.exchange.export.StrokeExport
import com.brushwork.paint.exchange.export.TextExportMode
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.theme.BrushworkColors
import java.util.Locale

/**
 * The options of "Export SVG…" / "Export PDF…" (v1.5 §4.10a): how brush strokes and text are
 * written, hidden layers, background, the Brushwork data, the PDF page; then Save as… (a file the
 * user picks) or Share. Notes say where the file can't look exactly like the canvas.
 */
@Composable
internal fun ExportOptionsSheet(state: ExchangeUiState) {
    val c = state.controller
    val o = state.exportOptions
    val pdf = o.format == VectorFormat.PDF
    // Only what the notes need, re-read when layers change.
    val notes by remember(c, pdf) {
        derivedStateOf {
            c.layersVersion
            val layers = c.doc.layers
            val out = ArrayList<String>()
            if (pdf && layers.any { it.folder?.passThrough != true && c.doc.effectiveVisible(it) && it.blendMode == LayerBlendMode.ADD }) out += "Add (Glow) is exported as Screen in PDF"
            if (layers.any { it.isAdjustmentLayer && c.doc.effectiveVisible(it) }) out += "Layers below an adjustment layer are exported as one picture"
            // v1.7 (item 8): a folder clips (and is clipped) like a layer.
            if (layers.any { it.clipping && !it.isAdjustmentLayer && c.doc.effectiveVisible(it) }) out += "Clipping groups are exported as pictures"
            // A pass-through folder below 100 % (drawn as itself: not in a clip group): exact in
            // PDF, isolated in SVG.
            if (!pdf && layers.indices.any { i ->
                    val l = layers[i]
                    l.folder?.passThrough == true && l.opacity > 0f && l.opacity < 1f && c.doc.effectiveVisible(l) &&
                        !FolderComposite.isClipBase(layers, i) && !FolderComposite.isClipped(layers, i)
                }
            ) out += ExportSceneBuilder.PASS_THROUGH_SVG_NOTE
            out
        }
    }
    BwSheet(
        title = "Export ${o.format.name}",
        onDismiss = state::closeExportSheet,
        footer = {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = state::share, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
                Button(onClick = state::saveAs, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save as…")
                }
            }
        },
    ) {
        SectionHeader("Brush strokes")
        ChoiceChips(
            StrokeExport.entries.map { it.label }, o.strokes.ordinal,
            { state.exportOptions = o.copy(strokes = StrokeExport.entries[it]) },
        )
        SectionHeader("Text")
        if (pdf) {
            Text("Text is exported as outlines in PDF", style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        } else {
            ChoiceChips(
                TextExportMode.entries.map { it.label }, o.text.ordinal,
                { state.exportOptions = o.copy(text = TextExportMode.entries[it]) },
            )
        }
        SectionHeader("Hidden layers")
        ChoiceChips(listOf("Skip", "Include"), if (o.includeHidden) 1 else 0, { state.exportOptions = o.copy(includeHidden = it == 1) })
        SectionHeader("Background")
        ChoiceChips(listOf("Transparent", "White"), if (o.whiteBackground) 1 else 0, { state.exportOptions = o.copy(whiteBackground = it == 1) })
        if (pdf) {
            SectionHeader("Page")
            val doc = c.doc
            // Short chip labels (all three fit a 360 dp phone), the details under them.
            val canvas = String.format(Locale.ROOT, "Canvas %.1f × %.1f cm", doc.width / doc.dpi * 2.54f, doc.height / doc.dpi * 2.54f)
            ChoiceChips(
                PdfPage.entries.map { if (it == PdfPage.CANVAS) canvas else it.label }, o.page.ordinal,
                { state.exportOptions = o.copy(page = PdfPage.entries[it]) },
            )
            Text(
                if (o.page == PdfPage.CANVAS) "The canvas size at ${doc.dpi.toInt()} dpi" else "The artwork fitted and centred on the page",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
            )
        }
        Spacer(Modifier.heightIn(min = 8.dp))
        ToggleRow(
            "Include Brushwork data", o.includePayload,
            { state.exportOptions = o.copy(includePayload = it) },
            description = "Restores every layer, hidden ones too, when the file is opened in Brushwork again",
        )
        for (n in notes) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(n, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
            }
        }
    }
}
