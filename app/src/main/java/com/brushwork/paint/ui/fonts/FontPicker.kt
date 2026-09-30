package com.brushwork.paint.ui.fonts

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.fonts.FontIds
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.fonts.ImportedFont
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Document types the "Import fonts" picker offers: fonts, and zips (how dafont.com serves them).
 * Many file managers call a .ttf "application/octet-stream", so that is included too.
 */
val FONT_IMPORT_MIME_TYPES = arrayOf(
    "application/zip", "application/x-zip-compressed",
    "font/ttf", "font/otf", "font/sfnt", "font/collection", "font/*",
    "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/font-sfnt",
    "application/octet-stream",
)

/** One row of the picker: a built-in family or an imported font. */
private sealed class FontRow(val key: String, val name: String) {
    class BuiltIn(val font: TextFont) : FontRow(FontIds.keyOf(font), font.label)
    class Imported(val font: ImportedFont) : FontRow(FontIds.keyOf(font.id), font.name)
}

/**
 * The text tool's font picker: search, "Import fonts" (fonts or dafont zips through the system
 * file picker), then Favorites, Recent, Built-in and Imported fonts, every row written in its own
 * font with [sample]. Rows have a star (favorite) and, for imported fonts, a delete button.
 * Picking a font applies it at once (the sheet stays open to compare fonts).
 */
@Composable
fun FontPickerSheet(
    store: FontStore,
    spec: TextSpec,
    sample: String,
    onPickBuiltIn: (TextFont) -> Unit,
    onPickImported: (ImportedFont) -> Unit,
    /** Fonts were imported or deleted (texts using them are laid out again). */
    onFontsChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ImportedFont?>(null) }
    LaunchedEffect(store) { store.load() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        status = null
        scope.launch {
            val report = try {
                store.importUris(context, uris)
            } catch (e: Exception) {
                null
            }
            importing = false
            status = report?.message ?: "Couldn't import these files"
            if (report != null && report.added.isNotEmpty()) {
                onFontsChanged()
                // A single new font is most likely the one wanted: use it.
                report.added.singleOrNull()?.let { f ->
                    onPickImported(f)
                    store.markUsed(FontIds.keyOf(f.id))
                }
            }
        }
    }

    val selectedKey = spec.fontId?.let { FontIds.keyOf(it) } ?: FontIds.keyOf(spec.font)
    fun pick(row: FontRow) {
        when (row) {
            is FontRow.BuiltIn -> onPickBuiltIn(row.font)
            is FontRow.Imported -> onPickImported(row.font)
        }
        scope.launch { store.markUsed(row.key) }
    }

    val builtIns = remember { TextFont.entries.map { FontRow.BuiltIn(it) } }
    val imported = store.fonts.map { FontRow.Imported(it) }
    val all: Map<String, FontRow> = (builtIns + imported).associateBy { it.key }

    BwSheet(
        title = "Fonts",
        onDismiss = onDismiss,
        scrollable = false,
        maxHeightFraction = 0.6f,
        showClose = false,
        actions = { TextButton(onClick = onDismiss) { Text("Done", fontWeight = FontWeight.SemiBold, color = BrushworkColors.Accent) } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search fonts") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isEmpty()) null else ({
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { launcher.launch(FONT_IMPORT_MIME_TYPES) }, enabled = !importing, modifier = Modifier.heightIn(min = 48.dp)) {
                if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import fonts")
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChrome, modifier = Modifier.padding(top = 4.dp)) }
        Text(
            "Download fonts from dafont.com (the .zip is fine), then import them here. Check each font's license on dafont before commercial use.",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )

        val q = query.trim()
        val sections: List<Pair<String, List<FontRow>>> = if (q.isNotEmpty()) {
            val found = (builtIns + imported).filter { it.name.contains(q, ignoreCase = true) }
            listOf("Results" to found)
        } else {
            val favorites = store.favorites.mapNotNull { all[it] }
            val recent = store.recent.filter { it !in store.favorites }.mapNotNull { all[it] }
            listOf("Favorites" to favorites, "Recent" to recent, "Built-in" to builtIns, "Imported" to imported)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            for ((title, rows) in sections) {
                if (rows.isEmpty() && title != "Imported" && title != "Results") continue
                item(key = "header:$title") { SectionHeader(title) }
                if (rows.isEmpty()) {
                    item(key = "empty:$title") {
                        Text(
                            if (title == "Results") "No font matches \"$q\"" else "No imported fonts yet: tap Import fonts to add .ttf, .otf or .zip files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BrushworkColors.OnChromeDim,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(rows, key = { "$title:${it.key}" }) { row ->
                    FontPickerRow(
                        row = row,
                        store = store,
                        sample = sample,
                        selected = row.key == selectedKey,
                        favorite = store.isFavorite(row.key),
                        onPick = { pick(row) },
                        onToggleFavorite = { scope.launch { store.toggleFavorite(row.key) } },
                        onDelete = (row as? FontRow.Imported)?.let { r -> { confirmDelete = r.font } },
                    )
                }
            }
        }
    }

    confirmDelete?.let { font ->
        BwDialog(
            title = "Delete font?",
            onDismiss = { confirmDelete = null },
            confirmText = "Delete font",
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    val usedHere = spec.fontId == font.id
                    if (store.delete(font.id)) {
                        status = "Deleted ${font.name}" + if (usedHere) ": this text now shows in ${spec.font.label}" else ""
                        onFontsChanged()
                    }
                }
            },
            dismissText = "Keep",
        ) {
            Text("\"${font.name}\" is removed from Brushwork. Texts that use it keep their pixels; editing them again shows a replacement font until you import it again.")
        }
    }
}

@Composable
private fun FontPickerRow(
    row: FontRow,
    store: FontStore,
    sample: String,
    selected: Boolean,
    favorite: Boolean,
    onPick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val typeface: Typeface? = when (row) {
        is FontRow.BuiltIn -> remember(row.font) { FontStore.builtIn(row.font) }
        is FontRow.Imported -> {
            // Loading a font file takes a moment: off the main thread, once (the store keeps it).
            val loaded by produceState<Typeface?>(null, row.font.id) { value = withContext(Dispatchers.IO) { store.typeface(row.font.id) } }
            loaded
        }
    }
    val family = remember(typeface) { typeface?.let { FontFamily(it) } }
    val unusable = row is FontRow.Imported && typeface == null && store.loaded && store.fileOf(row.font.id) == null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BrushworkColors.AccentDim.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = "Use font ${row.name}", role = Role.Button, onClick = onPick)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                sample,
                fontFamily = family,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = BrushworkColors.OnChrome,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unusable) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = BrushworkColors.Danger, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = BrushworkColors.Accent, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (favorite) "Remove ${row.name} from favorites" else "Add ${row.name} to favorites",
                tint = if (favorite) BrushworkColors.Accent else BrushworkColors.OnChromeDim,
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete font ${row.name}", tint = BrushworkColors.OnChromeDim) }
        }
    }
}

/**
 * The editor's font field: the current font's name written in that font; tapping it opens the
 * [FontPickerSheet]. [missing]: the text's imported font isn't on this device (warning below).
 */
@Composable
fun FontField(spec: TextSpec, typeface: Typeface, missing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val family = remember(typeface) { FontFamily(typeface) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, BrushworkColors.ChromeBorder, RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Choose font", role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Aa", fontFamily = family, fontSize = 24.sp, color = BrushworkColors.OnChrome)
            Text(
                "Font: ${spec.fontLabel}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = BrushworkColors.OnChromeDim) }
        }
        if (missing) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = BrushworkColors.Danger, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "\"${spec.fontLabel}\" isn't on this device: shown in ${spec.font.label}. Import the font again, or pick another one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.Danger,
                )
            }
        }
    }
}
