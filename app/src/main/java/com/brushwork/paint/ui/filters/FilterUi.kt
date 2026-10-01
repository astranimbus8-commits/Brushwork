package com.brushwork.paint.ui.filters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FilterFrames
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterRecents
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.masks.AdjustmentLayerOps
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.delay

/** Icon shown next to filters of [category]. */
fun filterCategoryIcon(category: FilterCategory): ImageVector = when (category) {
    FilterCategory.ADJUST -> Icons.Filled.Tune
    FilterCategory.BLUR -> Icons.Filled.BlurOn
    FilterCategory.STYLE -> Icons.Filled.AutoAwesome
    FilterCategory.DRAW -> Icons.Filled.Draw
    FilterCategory.ART -> Icons.Filled.Palette
    FilterCategory.PIXELATE -> Icons.Filled.GridOn
    FilterCategory.DISTORT -> Icons.Filled.Waves
    FilterCategory.FRAME -> Icons.Filled.FilterFrames
    FilterCategory.AI -> Icons.Filled.AutoFixHigh
}

// ====================================================================== browser

/** Categorized, searchable list of all filters; picking one calls controller.startFilter(). */
@Composable
fun FilterBrowser(controller: EditorController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember { FilterRegistry.all }
    val groups = remember { FilterRegistry.byCategory() }
    val recents = remember { FilterRecents.load(context).mapNotNull { FilterRegistry.byId(it) } }
    val pick: (Filter) -> Unit = { f ->
        controller.startFilter(f)
        onDismiss()
    }

    BwSheet(title = "Filters", onDismiss = onDismiss) {
        LayerKindBanner(controller, onDismiss)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search filters") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
            }) else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            all.isEmpty() -> EmptyNote("No filters are available.")
            query.isNotBlank() -> {
                val results = FilterSearch.search(all, query)
                if (results.isEmpty()) EmptyNote("No filter matches \"${query.trim()}\".")
                else {
                    SectionHeader("${results.size} result${if (results.size == 1) "" else "s"}")
                    results.forEach { f -> FilterListRow(f, onClick = { pick(f) }) }
                }
            }
            else -> {
                if (recents.isNotEmpty()) {
                    SectionHeader("Recently used")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recents.forEach { f -> FilterChipItem(f, Icons.Filled.History) { pick(f) } }
                    }
                }
                groups.forEach { (category, filters) ->
                    SectionHeader(category.title)
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filters.forEach { f -> FilterChipItem(f, filterCategoryIcon(category)) { pick(f) } }
                    }
                }
            }
        }
    }
}

/**
 * v1.5: what applying a filter means for the active layer. An adjustment layer has no pixels
 * (its effect is edited in the Masks tool: [Open]); a vector layer is rasterized by applying.
 */
@Composable
private fun LayerKindBanner(controller: EditorController, onDismiss: () -> Unit) {
    // The layer and what kind it is (a layer can turn into a vector or raster layer).
    val kind by remember(controller) {
        derivedStateOf {
            controller.layersVersion
            val l = controller.activeLayer
            Triple(l, l.isAdjustmentLayer, l.isVectorLayer)
        }
    }
    val (active, adjustment, vector) = kind
    if (!adjustment && !vector) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BrushworkColors.ChromeHigh)
            .padding(start = 12.dp, end = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (adjustment) Icons.Filled.Tune else Icons.Filled.Info, contentDescription = null, tint = BrushworkColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (adjustment) "Adjustment layers have no pixels — edit the effect in Masks" else "Applying rasterizes this vector layer",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (adjustment) {
            TextButton(onClick = { AdjustmentLayerOps.edit(controller, active); onDismiss() }) { Text("Open") }
        }
    }
}

@Composable
private fun FilterChipItem(filter: Filter, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(filter.name, maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = BrushworkColors.ChromeHigh,
            labelColor = BrushworkColors.OnChrome,
            leadingIconContentColor = BrushworkColors.Accent,
        ),
        border = null,
    )
}

@Composable
private fun FilterListRow(filter: Filter, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(filterCategoryIcon(filter.category), contentDescription = null, tint = BrushworkColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(filter.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(filter.category.title, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(vertical = 24.dp))
}

// ====================================================================== session panel

/** Parameter controls + Apply/Cancel for the running filter session (bottom of the editor). */
@Composable
fun FilterSessionPanel(session: FilterSession, modifier: Modifier = Modifier) {
    if (session.isClosed) return
    BackHandler { if (session.isApplying) session.cancelApply() else session.cancel() }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    val busy = session.isApplying
    Surface(
        // See-through like the editor's sheets: the live preview on the canvas shows behind it.
        color = SheetBackground,
        contentColor = BrushworkColors.OnChrome,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        // No elevation shadow: it would show through the translucent panel as a dark smear.
        // Edge-to-edge window: keep text parameters above the keyboard.
        modifier = modifier.fillMaxWidth().imePadding(),
    ) {
        Column(Modifier.heightIn(max = maxHeight).navigationBarsPadding()) {
            PanelHeader(session)
            if (busy) ApplyProgress(session)
            HorizontalDivider(color = BrushworkColors.ChromeBorder)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (session.filter.isAdjustmentCapable) AsAdjustmentLayerRow(session, enabled = !busy)
                if (!session.filter.livePreview) PreviewOnDemand(session)
                val params = session.filter.params
                if (params.isEmpty()) {
                    Text(
                        "This filter has no settings. Tap ✓ to apply it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrushworkColors.OnChromeDim,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                params.forEach { p -> FilterParamControl(session, p, enabled = !busy) }
            }
        }
    }
}

@Composable
private fun PanelHeader(session: FilterSession) {
    val controller = session.controller
    val busy = session.isApplying
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { session.cancel() }) { Icon(Icons.Filled.Close, contentDescription = "Cancel filter") }
        Column(Modifier.weight(1f)) {
            Text(
                session.filter.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val where = buildString {
                append(session.layer.name)
                if (session.target == EditTarget.MASK) append(" · mask")
                if (controller.selection != null) append(" · selection")
                if (session.target == EditTarget.CONTENT && session.layer.isVectorLayer) append(" · applying rasterizes it")
            }
            Text(where, style = MaterialTheme.typography.labelSmall, color = BrushworkColors.OnChromeDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RenderingIndicator(session.isRendering && !busy)
        IconButton(onClick = { session.reset() }, enabled = !busy) {
            Icon(Icons.Filled.RestartAlt, contentDescription = "Reset to defaults")
        }
        CompareButton(session, enabled = !busy)
        FilledIconButton(
            onClick = { session.apply() },
            enabled = !busy,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = BrushworkColors.Accent, contentColor = Color(0xFF002B55)),
        ) { Icon(Icons.Filled.Check, contentDescription = "Apply filter") }
    }
}

/** Small spinner shown only when a render takes long enough to notice (avoids flicker). */
@Composable
private fun RenderingIndicator(rendering: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(rendering) {
        if (rendering) { delay(150); visible = true } else visible = false
    }
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        if (visible) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BrushworkColors.Accent)
    }
}

/** Press and hold to see the original layer. */
@Composable
private fun CompareButton(session: FilterSession, enabled: Boolean) {
    DisposableEffect(session) { onDispose { session.compare(false) } }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (session.isComparing) BrushworkColors.AccentDim else Color.Transparent)
            .semantics {
                contentDescription = "Compare with the original (press and hold)"
                role = Role.Button
            }
            .pointerInput(session, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown().consume()
                    session.compare(true)
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        session.compare(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Compare,
            contentDescription = null,
            tint = if (enabled) BrushworkColors.OnChrome else BrushworkColors.OnChromeDim.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ApplyProgress(session: FilterSession) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Applying at full size…", style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
            val p = session.applyProgress
            if (p >= 0f) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = BrushworkColors.Accent)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = BrushworkColors.Accent)
            }
        }
        TextButton(onClick = { session.cancelApply() }) { Text("Stop") }
    }
}

/**
 * v1.5 "As adjustment layer": the filter with the current values becomes a new adjustment layer
 * above the layer (live and editable in the Masks tool) instead of changing its pixels; an active
 * selection becomes the adjustment's mask.
 */
@Composable
private fun AsAdjustmentLayerRow(session: FilterSession, enabled: Boolean) {
    val controller = session.controller
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClickLabel = "Add as adjustment layer", role = Role.Button) {
                val filter = session.filter
                val values = session.values.copy()
                session.cancel()
                AdjustmentLayerOps.fromFilter(controller, filter, values)
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Layers, contentDescription = null, tint = if (enabled) BrushworkColors.Accent else BrushworkColors.OnChromeDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("As adjustment layer", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (controller.selection != null) "Stays editable · the selection becomes its mask" else "Stays editable · changes every layer below",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PreviewOnDemand(session: FilterSession) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (session.previewStale) "This filter is slow: tap Preview to see your changes." else "Preview is up to date.",
            style = MaterialTheme.typography.bodySmall,
            color = BrushworkColors.OnChromeDim,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { session.renderPreview() },
            enabled = session.previewStale && !session.isRendering && !session.isApplying,
            colors = ButtonDefaults.buttonColors(containerColor = BrushworkColors.AccentDim, contentColor = Color.White),
        ) { Text(if (session.isRendering) "Rendering…" else "Preview") }
    }
}
