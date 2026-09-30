package com.brushwork.paint.ui.fonts

import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import java.util.concurrent.atomic.AtomicBoolean

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

/** One item of the picker's list: a section header, an empty-section note or a font row. */
private sealed class PickerEntry(val key: String) {
    class Header(val title: String) : PickerEntry("header:$title")
    class Note(section: String, val text: String) : PickerEntry("empty:$section")
    class Font(section: String, val row: FontRow) : PickerEntry(rowKey(section, row.key))

    companion object {
        fun rowKey(section: String, fontKey: String) = "$section:$fontKey"
    }
}

private const val IMPORTED = "Imported"

/**
 * The text tool's font picker: search, "Import fonts" (fonts or dafont zips through the system
 * file picker), then Favorites, Recent, Built-in and Imported fonts, every row written in its own
 * font with [sample]. Rows have a star (favorite) and, for imported fonts, a delete button.
 * Picking a font applies it at once (the sheet stays open to compare fonts; the Recent section
 * keeps the order it had when the sheet opened, so rows never jump under the finger).
 *
 * An import runs on in the app's scope when the sheet is closed meanwhile (its result is then
 * shown in a toast).
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
    var status by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ImportedFont?>(null) }
    /** Recent fonts as they were when the sheet opened (null until the index is read). */
    var recentShown by remember { mutableStateOf<List<String>?>(null) }
    /** Key of a list item to scroll to (the fonts just imported). */
    var scrollTo by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(store) {
        store.load()
        if (recentShown == null) recentShown = store.recent
    }
    // Whether this sheet is still on screen when an import finishes.
    val showing = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { showing.set(false) } }
    val pickImported by rememberUpdatedState(onPickImported)
    val fontsChanged by rememberUpdatedState(onFontsChanged)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        status = null
        val app = context.applicationContext
        FontStore.appScope.launch {
            val report = try {
                store.importUris(app, uris)
            } catch (e: Exception) {
                null
            }
            val message = report?.message ?: "Couldn't import these files"
            if (report != null && report.added.isNotEmpty()) fontsChanged()
            if (!showing.get()) {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                return@launch
            }
            status = message
            if (report == null || report.added.isEmpty()) return@launch
            query = ""
            // Show the new fonts (the Imported section is below the built-in ones).
            val first = report.added.minWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            scrollTo = PickerEntry.rowKey(IMPORTED, FontIds.keyOf(first.id))
            // A single new font is most likely the one wanted: use it.
            report.added.singleOrNull()?.let { f ->
                pickImported(f)
                store.markUsed(FontIds.keyOf(f.id))
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
    val q = query.trim()
    val entries: List<PickerEntry> = buildList {
        fun section(title: String, rows: List<FontRow>, emptyNote: String? = null) {
            if (rows.isEmpty() && emptyNote == null) return
            add(PickerEntry.Header(title))
            if (rows.isEmpty() && emptyNote != null) add(PickerEntry.Note(title, emptyNote))
            rows.forEach { add(PickerEntry.Font(title, it)) }
        }
        if (q.isNotEmpty()) {
            section("Results", (builtIns + imported).filter { it.name.contains(q, ignoreCase = true) }, "No font matches \"$q\"")
        } else {
            section("Favorites", store.favorites.mapNotNull { all[it] })
            section("Recent", recentShown.orEmpty().filter { it !in store.favorites }.mapNotNull { all[it] })
            section("Built-in", builtIns)
            section(IMPORTED, imported, if (store.loaded) "No imported fonts yet: tap Import fonts to add .ttf, .otf or .zip files." else null)
        }
    }
    LaunchedEffect(scrollTo) {
        val key = scrollTo ?: return@LaunchedEffect
        val i = entries.indexOfFirst { it.key == key }
        if (i >= 0) listState.animateScrollToItem(i)
        scrollTo = null
    }

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
                label = { Text("Search fonts", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isEmpty()) null else ({
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { launcher.launch(FONT_IMPORT_MIME_TYPES) },
                enabled = !store.importing,
                // Narrower than the default padding: the search field keeps room on a 360dp phone.
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (store.importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import fonts", maxLines = 1)
            }
        }
        val statusText = if (store.importing) "Importing fonts…" else status
        statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChrome, modifier = Modifier.padding(top = 4.dp)) }
        Text(
            "Download fonts from dafont.com (the .zip is fine), then import them here. Check each font's license on dafont before commercial use.",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )

        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState) {
            items(entries, key = { it.key }, contentType = { it.javaClass.simpleName }) { e ->
                when (e) {
                    is PickerEntry.Header -> SectionHeader(e.title)
                    is PickerEntry.Note -> Text(
                        e.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = BrushworkColors.OnChromeDim,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    is PickerEntry.Font -> {
                        val row = e.row
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

/** A picker row's typeface: still loading, ready, or missing (file gone or damaged). */
private sealed interface RowFace {
    data object Loading : RowFace
    class Ready(val typeface: Typeface) : RowFace
    data object Missing : RowFace
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
    val face: RowFace = when (row) {
        is FontRow.BuiltIn -> remember(row.font) { RowFace.Ready(FontStore.builtIn(row.font)) }
        is FontRow.Imported -> {
            // Loading a font file takes a moment: off the main thread, once (the store keeps it).
            val loaded by produceState<RowFace>(RowFace.Loading, row.font.id) {
                value = withContext(Dispatchers.IO) { store.typeface(row.font.id)?.let { RowFace.Ready(it) } ?: RowFace.Missing }
            }
            loaded
        }
    }
    val typeface: Typeface? = (face as? RowFace.Ready)?.typeface
    val family = remember(typeface) { typeface?.let { FontFamily(it) } }
    // Its file is gone or can't be loaded any more: shown in the default font, with a warning.
    val unusable = face == RowFace.Missing
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
