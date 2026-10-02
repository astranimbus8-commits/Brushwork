package com.brushwork.paint.ui.textframes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.ui.common.SnapToObjectsChip
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.placement.TextEditorDialog
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * The Text frames tool's options strip (v1.6, §3.6; area D), one Row like every tool's options:
 *
 * - nothing selected: a hint (drag to draw a frame);
 * - a frame selected: "Edit story", "Link…", "Unlink here", "Delete frame" and, while its story
 *   doesn't fit its frames, "+ N characters" in red;
 * - linking (an out-port loaded): the link hint ([LinkModeHint]) with "Cancel link";
 * - always: "Threads" (thread lines on / off) and Snap to objects.
 *
 * It also hosts the story editor (the text editor dialog on the tool's [TextFrameTool.story]).
 * Buttons come first and hints last, so on a narrow phone only a hint is ever cut.
 */
@Composable
fun TextFrameToolOptions(tool: TextFrameTool) {
    val c = tool.controller
    // Frames live in layer data, which isn't Compose state: follow the counters that change with it.
    val rev = tool.revision
    val selected = remember(rev, c.layersVersion, c.editCount) { tool.selected }
    val overset = remember(rev, c.layersVersion, c.editCount, selected) { tool.oversetCount(selected) }
    val hasNext = remember(rev, c.layersVersion, c.editCount, selected) { tool.hasNext(selected) }
    val linking = tool.linkFrom != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            linking -> LinkModeHint(onCancel = { tool.cancelLink() })
            selected == null -> {
                Icon(Icons.AutoMirrored.Filled.ViewQuilt, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            else -> {
                StripButton(Icons.Filled.Edit, "Edit story") { tool.openStoryEditor(selected) }
                StripButton(Icons.Filled.Link, "Link…") { tool.startLink(selected) }
                StripButton(Icons.Filled.LinkOff, "Unlink here", enabled = hasNext) { tool.unlinkAfter(selected) }
                StripButton(Icons.Filled.Delete, "Delete frame") { tool.deleteFrame(selected) }
                if (overset > 0) OversetNote(overset)
            }
        }
        ToolIconButton(
            icon = Icons.Filled.Timeline,
            contentDescription = "Threads",
            onClick = { tool.showThreads = !tool.showThreads },
            selected = tool.showThreads,
        )
        SnapToObjectsChip(c)
        if (!linking && selected == null) Hint("Drag on the canvas to draw a text frame, tap a frame to select it")
        else if (!linking) Hint("Drag the frame to move it, its dots to resize it, tap it again to type")
    }
    if (tool.story.isOpen && tool.story.item != null) TextEditorDialog(tool.story)
}

/** "+ 132 characters": the story goes on beyond its last frame (InDesign's red "+"). */
@Composable
private fun OversetNote(count: Int) {
    val text = "+ $count character" + if (count == 1) "" else "s"
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = BrushworkColors.Danger,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 6.dp).semantics { contentDescription = "$text that don't fit: link another frame" },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
}

/** A labelled strip button (at least 40 dp tall: Material's minimum button height). */
@Composable
internal fun StripButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}
