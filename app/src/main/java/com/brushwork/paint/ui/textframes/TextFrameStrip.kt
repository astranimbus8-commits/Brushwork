package com.brushwork.paint.ui.textframes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.placement.StripMenu
import com.brushwork.paint.ui.placement.StripStatusText
import com.brushwork.paint.ui.placement.StripTextButton
import com.brushwork.paint.ui.placement.TextEditorDialog
import com.brushwork.paint.ui.placement.snapItem
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec

/**
 * The Text frames tool's strip (v1.8, design §3.9 "Text frames"; area G). The More row always
 * holds "Threads" and "Snap to objects"; row 1 depends on what the tool does:
 * - nothing selected: v1.7's hint on row 1 ("Drag on the canvas to draw a text frame, ...");
 * - a frame selected: "Edit story", "Link…", "Unlink here" (AUTO), "Delete frame" and, while its
 *   story doesn't fit its frames, the red "+ N characters" (its width kept, so nothing jumps);
 *   "Wrap around picture" (a dropdown of the pictures) heads the More row, under v1.7's hint;
 * - linking (an out-port loaded): "Cancel link" and the link hint on row 1.
 * The story editor (the text editor dialog on [TextFrameTool.story]) is its overlay.
 */
@Composable
internal fun textFrameStrip(tool: TextFrameTool, c: EditorController): StripSpec {
    val ctl = c
    // Frames live in layer data, which isn't Compose state: follow the counters that change with it.
    val rev = tool.revision
    val selected = remember(rev, ctl.layersVersion, ctl.editCount) { tool.selected }
    val overset = remember(rev, ctl.layersVersion, ctl.editCount, selected) { tool.oversetCount(selected) }
    val hasNext = remember(rev, ctl.layersVersion, ctl.editCount, selected) { tool.hasNext(selected) }
    val linking = tool.linkFrom != null
    val F = TextFrameStripItems
    val threads = StripItem(F.THREADS, F.THREADS_LABEL, F.ICON_DP.dp, row = StripRow.MORE) {
        ToolIconButton(Icons.Filled.Timeline, F.THREADS_LABEL, onClick = { tool.showThreads = !tool.showThreads }, selected = tool.showThreads, size = F.ICON_DP.dp)
    }
    val snap = snapItem(F.SNAP, on = { ctl.snapping.enabled }) { ctl.snapping.enabled = it }
    val overlays: @Composable () -> Unit = { if (tool.story.isOpen && tool.story.item != null) TextEditorDialog(tool.story) }
    return when {
        linking -> StripSpec(
            listOf(
                StripItem(F.CANCEL_LINK, F.CANCEL_LINK_LABEL, F.CANCEL_LINK_DP.dp) {
                    StripTextButton(F.CANCEL_LINK_LABEL, F.CANCEL_LINK_DP.dp, onClick = { tool.cancelLink() }, icon = Icons.Filled.Close)
                },
                StripItem(F.STATUS, LINK_HINT, F.LINK_HINT_DP.dp) {
                    StripStatusText(LINK_HINT, F.LINK_HINT_DP.dp, leading = {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = BrushworkColors.Accent, modifier = Modifier.size(16.dp))
                    })
                },
                threads,
                snap,
            ),
            overlays = overlays,
        )
        selected == null -> StripSpec(
            listOf(
                StripItem(F.STATUS, F.DRAW_HINT, F.HINT_DP.dp) { StripStatusText(F.DRAW_HINT, F.HINT_DP.dp) },
                threads,
                snap,
            ),
            overlays = overlays,
        )
        else -> StripSpec(
            listOf(
                StripItem(F.EDIT, F.EDIT_LABEL, F.EDIT_DP.dp) {
                    StripTextButton(F.EDIT_LABEL, F.EDIT_DP.dp, onClick = { tool.openStoryEditor(selected) }, icon = Icons.Filled.Edit)
                },
                StripItem(F.LINK, F.LINK_LABEL, F.LINK_DP.dp) {
                    StripTextButton(F.LINK_LABEL, F.LINK_DP.dp, onClick = { tool.startLink(selected) }, icon = Icons.Filled.Link)
                },
                StripItem(F.UNLINK, F.UNLINK_LABEL, F.ICON_DP.dp, overflowRank = 1) {
                    ToolIconButton(Icons.Filled.LinkOff, F.UNLINK_LABEL, onClick = { tool.unlinkAfter(selected) }, enabled = hasNext, size = F.ICON_DP.dp)
                },
                StripItem(F.DELETE, F.DELETE_LABEL, F.ICON_DP.dp) {
                    ToolIconButton(Icons.Filled.Delete, F.DELETE_LABEL, onClick = { tool.deleteFrame(selected) }, size = F.ICON_DP.dp)
                },
                StripItem(F.OVERSET, F.oversetDescription(overset), F.OVERSET_DP.dp, shown = overset > 0) { OversetNote(overset) },
                StripItem(F.WRAP, F.wrapLabel(tool.wraps(selected)), F.ICON_DP.dp, row = StripRow.MORE) { FrameWrapButton(tool, selected, rev) },
                threads,
                snap,
            ),
            hint = F.FRAME_HINT,
            overlays = overlays,
        )
    }
}

/**
 * "Wrap around picture" for the selected frame (each frame wraps on its own, §3.6a): an opaque
 * dropdown of the pictures it can wrap around (top layer first) and "No wrap". Highlighted while
 * the frame wraps. One step per choice ("Wrap frame"); the story re-flows. A dropdown: the More
 * row stays open.
 */
@Composable
private fun FrameWrapButton(tool: TextFrameTool, frame: Layer, rev: Int) {
    val c = tool.controller
    var open by remember { mutableStateOf(false) }
    val on = remember(rev, c.layersVersion, c.editCount, frame) { tool.wraps(frame) }
    Box {
        ToolIconButton(
            icon = Icons.AutoMirrored.Filled.WrapText,
            contentDescription = TextFrameStripItems.wrapLabel(on),
            onClick = { open = true },
            selected = on,
            size = TextFrameStripItems.ICON_DP.dp,
        )
        StripMenu(open, onDismiss = { open = false }) {
            val current = tool.wrapSourceOf(frame)
            val sources = tool.wrapSources(frame)
            Text(
                if (sources.isEmpty()) "No picture to wrap around: draw or import one on another layer" else "Wrap this frame's lines around",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            DropdownMenuItem(
                text = { Text("No wrap") },
                onClick = {
                    open = false
                    tool.setFrameWrap(frame, null)
                },
                leadingIcon = { WrapCheck(!on) },
                modifier = Modifier.heightIn(min = 44.dp),
            )
            for (l in sources) {
                DropdownMenuItem(
                    text = { Text(l.name, maxLines = 1) },
                    onClick = {
                        open = false
                        tool.setFrameWrap(frame, l)
                    },
                    leadingIcon = { WrapCheck(on && l === current) },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
        }
    }
}

@Composable
private fun WrapCheck(on: Boolean) {
    if (on) Icon(Icons.Filled.Check, contentDescription = "Chosen", modifier = Modifier.size(18.dp))
    else Spacer(Modifier.size(18.dp))
}

/**
 * "+ 132 characters" on two lines in red: the story goes on beyond its last frame (InDesign's red
 * "+"), announced "+ 132 characters that don't fit: link another frame" as in v1.7.
 */
@Composable
private fun OversetNote(count: Int) {
    val text = TextFrameStripItems.overset(count)
    Box(Modifier.width(TextFrameStripItems.OVERSET_DP.dp).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = BrushworkColors.Danger,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = TextFrameStripItems.oversetDescription(count) },
        )
    }
}

/** The Text frames strip's item keys, widths (measured, rounded up to 4 dp) and texts (v1.7's). */
internal object TextFrameStripItems {
    const val STATUS = "frames.status"
    const val CANCEL_LINK = "frames.cancelLink"
    const val EDIT = "frames.edit"
    const val LINK = "frames.link"
    const val UNLINK = "frames.unlink"
    const val DELETE = "frames.delete"
    const val OVERSET = "frames.overset"
    const val WRAP = "frames.wrap"
    const val THREADS = "frames.threads"
    const val SNAP = "frames.snap"

    const val ICON_DP = 40f

    /** "Edit story" on two lines after its icon, at 11 sp. */
    const val EDIT_DP = 64f

    /** "Link…" after its icon. */
    const val LINK_DP = 60f

    /** "+ 1234 characters" on two lines ("characters" the longer). */
    const val OVERSET_DP = 64f

    /** "Cancel link" after its icon. */
    const val CANCEL_LINK_DP = 92f

    /** The link hint on two lines after its icon, beside "Cancel link" (row 1 with the VECTOR cell and the toggle). */
    const val LINK_HINT_DP = 144f

    /** v1.7's hint alone on row 1, two lines: row 1 with the VECTOR cell and the toggle at 360 dp. */
    const val HINT_DP = 240f

    const val EDIT_LABEL = "Edit story"
    const val LINK_LABEL = "Link…"
    const val UNLINK_LABEL = "Unlink here"
    const val DELETE_LABEL = "Delete frame"
    const val THREADS_LABEL = "Threads"
    const val CANCEL_LINK_LABEL = "Cancel link"
    const val DRAW_HINT = "Drag on the canvas to draw a text frame, tap a frame to select it"
    const val FRAME_HINT = "Drag the frame to move it, its dots to resize it, tap it again to type"

    fun wrapLabel(on: Boolean) = if (on) "Wrap around picture: on" else "Wrap around picture"

    /** "+ 132 characters" ("+ 1 character"). */
    fun overset(count: Int) = "+ $count character" + if (count == 1) "" else "s"

    /** The overset note's handle (v1.7's description): "+ 132 characters that don't fit: link another frame". */
    fun oversetDescription(count: Int) = overset(count) + " that don't fit: link another frame"
}
