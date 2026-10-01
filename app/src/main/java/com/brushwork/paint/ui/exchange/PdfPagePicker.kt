package com.brushwork.paint.ui.exchange

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brushwork.paint.exchange.pdf.PageRasterizer
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Choose the pages of a PDF to import (v1.5 §4.11a): thumbnails in 3 columns, tap to select
 * (several at once), Select all / none, "Transparent background", Import. Thumbnails are rendered
 * off the main thread as they scroll into view.
 */
@Composable
internal fun PdfPagePicker(
    rasterizer: PageRasterizer,
    onDismiss: () -> Unit,
    onImport: (pages: List<Int>, transparent: Boolean) -> Unit,
    title: String = "Import PDF pages",
    showTransparent: Boolean = true,
) {
    val count = remember(rasterizer) { rasterizer.pageCount }
    var selected by rememberSaveable { mutableStateOf(listOf(0)) }
    var transparent by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = BrushworkColors.ChromeHigh,
            contentColor = BrushworkColors.OnChrome,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$count pages · ${selected.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                ) {
                    items((0 until count).toList(), key = { it }) { index ->
                        PageCell(rasterizer, index, index in selected) {
                            selected = if (index in selected) selected - index else (selected + index).sorted()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selected = (0 until count).toList() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Select all") }
                    TextButton(onClick = { selected = emptyList() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("None") }
                }
                if (showTransparent) ToggleRow("Transparent background", transparent, { transparent = it }, description = "Otherwise pages are placed on white")
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = { onImport(selected, transparent) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (selected.size <= 1) "Import" else "Import ${selected.size}") }
                }
            }
        }
    }
}

@Composable
private fun PageCell(rasterizer: PageRasterizer, index: Int, isSelected: Boolean, onToggle: () -> Unit) {
    val size by produceState<Pair<Float, Float>?>(null, rasterizer, index) {
        value = withContext(Dispatchers.IO) { runCatching { rasterizer.pageSize(index) }.getOrNull() }
    }
    val thumb by produceState<ImageBitmap?>(null, rasterizer, index) {
        value = withContext(Dispatchers.IO) { runCatching { thumbnail(rasterizer, index) }.getOrNull() }
    }
    val ratio = size?.let { (w, h) -> if (w > 0f && h > 0f) w / h else null } ?: (1f / 1.414f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.3f, 3f))
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(if (isSelected) 3.dp else 1.dp, if (isSelected) BrushworkColors.Accent else BrushworkColors.ChromeBorder, RoundedCornerShape(6.dp))
                .semantics {
                    contentDescription = "Page ${index + 1}"
                    selected = isSelected
                }
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(t, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (isSelected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(BrushworkColors.Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A small picture of page [index] over white (about 240 px on its long side). */
private fun thumbnail(r: PageRasterizer, index: Int): ImageBitmap {
    val (pw, ph) = r.pageSize(index)
    val s = THUMB / max(pw, ph).coerceAtLeast(1f)
    val w = max(1, (pw * s).roundToInt())
    val h = max(1, (ph * s).roundToInt())
    val page = r.render(index, w, h)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.eraseColor(0xFFFFFFFF.toInt())
    Canvas(out).drawBitmap(page, 0f, 0f, null)
    page.recycle()
    return out.asImageBitmap()
}

private const val THUMB = 240f
