package com.brushwork.paint.ui.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextRotateVertical
import androidx.compose.material.icons.filled.TextRotationNone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.PlaceLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Text tool's strip (v1.8, design §3.9 "Text"; area G): row 1 "Edit text" (dimmed with no
 * text to edit), the horizontal / vertical switch, "Letter scaling" (showing "Letters") and the
 * wrap button (AUTO: the first to go to the More row); the More row "Snap to objects", then
 * "Numbers" while a text is open (no slot kept without one), under v1.7's hint. The editor
 * dialog and the Numbers, Wrap and Letter scaling sheets are its overlays, as v1.7 hosted them.
 * v1.9 (R14) ends the More row with “New layer each time” while no text is open, and fades the
 * wrap button for a text that shares its layer with others.
 */
@Composable
internal fun textSpec(tool: TextTool): StripSpec {
    val item = tool.item
    val c = tool.controller
    // The active layer isn't Compose state: follow the counters that change with it.
    val activeText = remember(c.layersVersion, c.editCount) { c.activeLayer.takeIf { it.isTextLayer } }
    val onPath = item?.path?.isActive == true
    val T = TextStripItems
    val hint = when {
        // Wrap is set but the text can't wrap (vertical, on a path).
        item != null && item.wrap.isOn && !item.canWrap -> TextTool.WRAP_HORIZONTAL_ONLY
        onPath -> "Drag the dots to shape the path, two fingers to scale or turn"
        item != null && item.spec.box.wrapFor(item.spec.vertical) > 0f ->
            if (item.spec.vertical) "Bottom / side handles: box size" else "Side / bottom handles: box size"
        item != null -> if (item.spec.vertical) "Bottom handle: box height" else "Side handle: box width"
        activeText != null -> "or tap a text to edit it, empty canvas to add"
        else -> "Tap the canvas to add text, or a text to edit it"
    }
    val items = listOf(
        StripItem(T.EDIT, T.EDIT_LABEL, T.EDIT_DP.dp) {
            val strip = LocalStripContext.current
            StripTextButton(
                T.EDIT_LABEL,
                T.EDIT_DP.dp,
                onClick = {
                    // D25: the editor is a dialog (an overlay); the More row closes first.
                    strip.closeMore()
                    if (item != null) tool.openEditor() else if (activeText != null) tool.editLayer(activeText, openEditor = true)
                },
                enabled = item != null || activeText != null,
                icon = Icons.Filled.Edit,
            )
        },
        StripItem(T.VERTICAL, T.verticalLabel(tool.isVertical), T.ICON_DP.dp) {
            ToolIconButton(
                icon = if (tool.isVertical) Icons.Filled.TextRotateVertical else Icons.Filled.TextRotationNone,
                contentDescription = T.verticalLabel(tool.isVertical),
                onClick = { tool.toggleVertical() },
                selected = tool.isVertical && !onPath,
                enabled = !onPath,
                size = T.ICON_DP.dp,
            )
        },
        StripItem(T.LETTERS, T.LETTERS_LABEL, T.LETTERS_DP.dp) { LettersButton(tool) },
        StripItem(T.WRAP, T.wrapLabel(tool), T.WRAP_DP.dp, overflowRank = 1) { WrapButton(tool) },
        // Snap first, then the Numbers button only while a text is open: no empty slot without
        // one, and Snap keeps its place whether a text is open or not (v1.8 polish).
        snapItem(T.SNAP, on = { c.snapping.enabled }) { c.snapping.enabled = it },
    ) + listOfNotNull(
        if (item == null) null else StripItem(T.NUMBERS, T.NUMBERS_LABEL, T.NUMBERS_DP.dp, row = StripRow.MORE) {
            val strip = LocalStripContext.current
            StripTextButton(T.NUMBERS_LABEL, T.NUMBERS_DP.dp, icon = Icons.Filled.Tune, onClick = {
                strip.closeMore()
                tool.wrapSheetOpen = false
                tool.lettersSheetOpen = false
                tool.numbersOpen = true
            })
        },
        // v1.9 (R14): “New layer each time” while no text is open (off, a new text joins the
        // active text layer), after v1.8's More items.
        if (item != null) null else StripItem(T.NEW_LAYER.key, PlaceLabels19.NEW_LAYER_EACH_TIME, T.NEW_LAYER.widthDp.dp, row = StripRow.MORE) {
            NewLayerEachTimeButton(tool)
        },
    )
    return StripSpec(items, hint = hint, overlays = { TextOverlays(tool) })
}

/**
 * v1.9 (R14): “New layer each time” ([TextTool.newLayerEachTime]), a text button with a selected
 * state (tag [V19Tags.NEW_LAYER_EACH_TIME]): on, every new text gets a layer of its own as in
 * v1.8; off (the default), a new text joins the active text layer. The tap that places a text
 * reads it, so it applies before any text exists.
 *
 * I27: the label is 11 sp on two lines in a fixed cell; past font scale
 * [TextStripItems.NEW_LAYER_MAX_FONT_SCALE] it would need a third line, so it stops growing
 * there (it shrinks rather than being cut, and stays above 11 sp).
 */
@Composable
private fun NewLayerEachTimeButton(tool: TextTool) {
    val on = tool.newLayerEachTime
    val d = LocalDensity.current
    val capped = Density(d.density, minOf(d.fontScale, TextStripItems.NEW_LAYER_MAX_FONT_SCALE))
    Box(Modifier.testTag(V19Tags.NEW_LAYER_EACH_TIME)) {
        CompositionLocalProvider(LocalDensity provides capped) {
            StripTextButton(
                PlaceLabels19.NEW_LAYER_EACH_TIME,
                TextStripItems.NEW_LAYER.widthDp.dp,
                onClick = { tool.newLayerEachTime = !on },
                checked = on,
            )
        }
    }
}

/** The Text tool's sheets and dialog, as v1.7 hosted them (§3.9 "Overlays"). */
@Composable
private fun TextOverlays(tool: TextTool) {
    val item = tool.item
    if (tool.editorOpen && item != null) TextEditorDialog(tool)
    if (tool.numbersOpen && item != null && !tool.editorOpen) TextNumbersSheet(tool)
    if (tool.wrapSheetOpen && item != null && !tool.editorOpen) TextWrapSheet(tool)
    // Also over the open editor (stacked on it), so the button always shows what it opens.
    if (tool.lettersSheetOpen && item != null) TextLetterScaleSheet(tool)
}

/**
 * "Letter scaling" (v1.6 §3.5a): "Letters" under its icon, 40 dp; opens the small "Letter
 * scaling" sheet for the pending text or the active text layer. Tinted while the text's letters
 * are scaled (its state "On" / "Off").
 */
@Composable
private fun LettersButton(tool: TextTool) {
    val on = tool.item?.spec?.letterScale?.isOn == true
    val sheets = LocalSheetHost.current
    val strip = LocalStripContext.current
    StripTextButton(
        TextStripItems.LETTERS_TEXT,
        TextStripItems.LETTERS_DP.dp,
        onClick = {
            strip.closeMore()
            tool.openLettersSheet()
            // Already open under another sheet (the editor opened over it), or folded into its
            // pill: brought back on top and shown.
            if (tool.lettersSheetOpen) sheets?.bringToFront(LettersSheetGroup)
        },
        icon = Icons.Filled.FormatSize,
        description = TextStripItems.LETTERS_LABEL,
        state = if (on) "On" else "Off",
        stacked = true,
        on = on,
    )
}

/**
 * The wrap button (text flowing around a picture, v1.5 §4.1): "Wrap" after its icon, announced
 * by v1.7's label; opens the wrap sheet. Tinted while the text wraps; faded for vertical text and
 * text on a path, where a tap explains that wrap works with horizontal text.
 */
@Composable
private fun WrapButton(tool: TextTool) {
    val item = tool.item
    val strip = LocalStripContext.current
    StripTextButton(
        TextStripItems.WRAP_TEXT,
        TextStripItems.WRAP_DP.dp,
        onClick = {
            strip.closeMore()
            tool.openWrapSheet()
        },
        icon = Icons.AutoMirrored.Filled.WrapText,
        description = TextStripItems.wrapLabel(tool),
        announceText = false,
        on = item != null && item.wrapActive,
        dimmed = !tool.canWrap,
    )
}

/** The Text strip's item keys, widths (measured, rounded up to 4 dp) and handles (the v1.7 labels). */
internal object TextStripItems {
    const val EDIT = "text.edit"
    const val VERTICAL = "text.vertical"
    const val LETTERS = "text.letters"
    const val WRAP = "text.wrap"
    const val NUMBERS = "text.numbers"
    const val SNAP = "text.snap"

    /** v1.9 (R14): the More-row toggle [NewLayerEachTimeButton], while no text is open. */
    val NEW_LAYER = StripKeysV19.NEW_LAYER_EACH_TIME_TEXT

    /** The largest font scale at which [NEW_LAYER]'s label fits its two lines (I27). */
    const val NEW_LAYER_MAX_FONT_SCALE = 1.3f

    /** "Edit text" after its icon at 11 sp. */
    const val EDIT_DP = 92f
    const val ICON_DP = 40f

    /** "Letters" at 10 sp under its icon. */
    const val LETTERS_DP = 40f

    /** "Wrap" after its icon. */
    const val WRAP_DP = 72f

    /** "Numbers" after its icon, on one line (72 dp broke the word in two; v1.8 polish). */
    const val NUMBERS_DP = 80f

    const val EDIT_LABEL = "Edit text"
    const val LETTERS_LABEL = "Letter scaling"
    const val LETTERS_TEXT = "Letters"
    const val WRAP_TEXT = "Wrap"
    const val NUMBERS_LABEL = "Numbers"

    fun verticalLabel(vertical: Boolean) = if (vertical) "Vertical text (tap for horizontal)" else "Horizontal text (tap for vertical)"

    /** The wrap button's v1.7 label: why it can't wrap, whether it wraps. */
    fun wrapLabel(tool: TextTool): String {
        val item = tool.item
        return when {
            item != null && !item.canWrap -> "Wrap around picture (works with horizontal text)"
            item != null && item.wrapActive -> "Wrap around picture: on"
            else -> "Wrap around picture"
        }
    }
}

/**
 * The Frame divider's strip (v1.8, design §3.9 "Frame divider"; area G), by the frame layer's
 * state:
 * - none yet: "New frame layer" and v1.7's "then drag across a panel to divide it" on row 1;
 * - ready: "Rows × columns", "Remove panels mode" and "Frame settings / new frame layer" on row 1,
 *   "Snap to objects" on the More row under "Drag across panels to divide" ("Tap a panel to
 *   remove it" in remove mode);
 * - edited by hand: "Redraw", "New frame" and "Frame layer was edited" on row 1.
 * The settings sheet and the grid dialog are its overlays.
 */
@Composable
internal fun frameDividerSpec(tool: FrameDividerTool): StripSpec {
    val c = tool.controller
    // The frame status depends on layer contents that aren't Compose state: key it on the
    // counters that change with them (tool edits, any undoable edit / undo, layer changes).
    val status = remember(tool.revision, c.layersVersion, c.editCount) { tool.status() }
    // After an undo/redo or layer change, an "edited" frame may match its model again.
    LaunchedEffect(status, c.layersVersion, c.editCount) {
        if (status == FrameDividerTool.Status.OUT_OF_SYNC) tool.refreshSync()
    }
    val F = FrameStripItems
    val overlays: @Composable () -> Unit = {
        if (tool.settingsOpen) FrameSettingsSheet(tool, status)
        if (tool.gridOpen && status == FrameDividerTool.Status.READY) FrameGridDialog(tool)
    }
    val strip = LocalStripContext.current
    val openSettings = {
        strip.closeMore()
        tool.settingsOpen = true
    }
    return when (status) {
        FrameDividerTool.Status.NONE -> {
            ClosesMoreRow()
            StripSpec(
                listOf(
                    StripItem(F.NEW_LAYER, F.NEW_LAYER_LABEL, F.NEW_LAYER_DP.dp) {
                        StripTextButton(F.NEW_LAYER_LABEL, F.NEW_LAYER_DP.dp, onClick = openSettings, icon = Icons.Filled.Add)
                    },
                    StripItem(F.STATUS, F.THEN_DIVIDE, F.STATUS_DP.dp) { StripStatusText(F.THEN_DIVIDE, F.STATUS_DP.dp, balanced = true) },
                ),
                overlays = overlays,
            )
        }
        FrameDividerTool.Status.READY -> StripSpec(
            listOf(
                StripItem(F.GRID, F.GRID_LABEL, F.ICON_DP.dp) {
                    ToolIconButton(Icons.Filled.GridView, F.GRID_LABEL, onClick = {
                        strip.closeMore()
                        tool.gridOpen = true
                    }, size = F.ICON_DP.dp)
                },
                StripItem(F.REMOVE, F.REMOVE_LABEL, F.ICON_DP.dp) {
                    ToolIconButton(Icons.Filled.Delete, F.REMOVE_LABEL, onClick = { tool.removeMode = !tool.removeMode }, selected = tool.removeMode, size = F.ICON_DP.dp)
                },
                StripItem(F.SETTINGS, F.SETTINGS_LABEL, F.ICON_DP.dp) {
                    ToolIconButton(Icons.Filled.Settings, F.SETTINGS_LABEL, onClick = openSettings, size = F.ICON_DP.dp)
                },
                snapItem(F.SNAP, on = { c.snapping.enabled }) { c.snapping.enabled = it },
            ),
            hint = if (tool.removeMode) F.REMOVE_HINT else F.DIVIDE_HINT,
            overlays = overlays,
        )
        FrameDividerTool.Status.OUT_OF_SYNC -> {
            ClosesMoreRow()
            StripSpec(
                listOf(
                    StripItem(F.REDRAW, F.REDRAW_LABEL, F.ICON_DP.dp) {
                        ToolIconButton(Icons.Filled.Refresh, F.REDRAW_LABEL, onClick = { tool.redraw() }, size = F.ICON_DP.dp)
                    },
                    StripItem(F.NEW_FRAME, F.NEW_FRAME_LABEL, F.ICON_DP.dp) {
                        ToolIconButton(Icons.Filled.Add, F.NEW_FRAME_LABEL, onClick = openSettings, size = F.ICON_DP.dp)
                    },
                    StripItem(F.STATUS, F.EDITED, F.STATUS_DP.dp) {
                        StripStatusText(F.EDITED, F.STATUS_DP.dp, leading = {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = BrushworkColors.Danger, modifier = Modifier.size(20.dp))
                        })
                    },
                ),
                overlays = overlays,
            )
        }
    }
}

/** The Frame divider strip's item keys, widths (measured, rounded up to 4 dp) and texts (v1.7's). */
internal object FrameStripItems {
    const val NEW_LAYER = "frame.newLayer"
    const val STATUS = "frame.status"
    const val GRID = "frame.grid"
    const val REMOVE = "frame.remove"
    const val SETTINGS = "frame.settings"
    const val SNAP = "frame.snap"
    const val REDRAW = "frame.redraw"
    const val NEW_FRAME = "frame.newFrame"

    /** "New frame layer" after its icon at 11 sp, two lines. */
    const val NEW_LAYER_DP = 92f
    const val ICON_DP = 40f

    /** The status text on row 1 at 11 sp, two lines: within row 1 at 360 dp with the VECTOR cell, no toggle. */
    const val STATUS_DP = 184f

    const val NEW_LAYER_LABEL = "New frame layer"
    const val GRID_LABEL = "Rows × columns"
    const val REMOVE_LABEL = "Remove panels mode"
    const val SETTINGS_LABEL = "Frame settings / new frame layer"
    const val REDRAW_LABEL = "Redraw"
    const val NEW_FRAME_LABEL = "New frame"
    const val THEN_DIVIDE = "then drag across a panel to divide it"
    const val DIVIDE_HINT = "Drag across panels to divide"
    const val REMOVE_HINT = "Tap a panel to remove it"
    const val EDITED = "Frame layer was edited"
}
