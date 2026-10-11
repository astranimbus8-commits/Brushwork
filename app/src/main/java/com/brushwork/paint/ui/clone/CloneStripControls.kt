package com.brushwork.paint.ui.clone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.ui.common.ToolIconButton
import com.brushwork.paint.ui.common.closeOnSecondFinger
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow

/**
 * v1.8 (design §3.9 "Clone stamp"; area D): the clone stamp's own strip items, each at a fixed
 * width. Row 1: "Set source" 92 ("Tap the source" while armed), "Aligned" 40 (an icon toggle),
 * "Sample from" 96 (a dropdown: "This layer" / "All layers"). The More row: the hint "Long-press
 * where to copy from" (while there is no source and Set source is not armed; it arms Set source
 * and closes the More row, so the next canvas tap sets the source) and "Show source" 112. The
 * v1.7 handles are unchanged.
 */
internal object CloneStripItems {
    const val SET_SOURCE = "clone.source"
    const val ALIGNED = "clone.aligned"
    const val SAMPLE = "clone.sample"
    const val HINT = "clone.hint"
    const val SHOW_SOURCE = "clone.show"

    const val SET_SOURCE_DP = 92f
    const val ALIGNED_DP = 40f
    const val SAMPLE_DP = 96f
    const val HINT_DP = 232f
    const val SHOW_SOURCE_DP = 112f

    const val SET_SOURCE_LABEL = "Set source"
    const val ARMED_LABEL = "Tap the source"
    const val ALIGNED_LABEL = "Aligned"
    const val SHOW_SOURCE_LABEL = "Show source"
    const val THIS_LAYER = "This layer"
    const val ALL_LAYERS = "All layers"

    /** The "Sample from" control's handle (its description), as in v1.7. */
    fun sampleLabel(allLayers: Boolean) = "Sample from: " + if (allLayers) ALL_LAYERS else THIS_LAYER

    fun setSource(tool: CloneTool) =
        StripItem(SET_SOURCE, if (tool.armed) ARMED_LABEL else SET_SOURCE_LABEL, SET_SOURCE_DP.dp) { SetSourceButton(tool) }

    fun aligned(tool: CloneTool) = StripItem(ALIGNED, ALIGNED_LABEL, ALIGNED_DP.dp) {
        val on = tool.aligned
        ToolIconButton(
            icon = Icons.Filled.Link,
            contentDescription = ALIGNED_LABEL,
            onClick = { tool.setAligned(!on) },
            selected = on,
            size = ALIGNED_DP.dp,
            modifier = Modifier.semantics { stateDescription = if (on) "On" else "Off" },
        )
    }

    fun sampleFrom(tool: CloneTool) = StripItem(SAMPLE, sampleLabel(tool.sampleAllLayers), SAMPLE_DP.dp) { SampleFromMenu(tool) }

    /** The hint, a More-row control; [closeMore] closes the row once it armed Set source. */
    fun hint(tool: CloneTool, closeMore: () -> Unit) = StripItem(HINT, CloneTool.HINT, HINT_DP.dp, row = StripRow.MORE) {
        HintChip { tool.arm(true); closeMore() }
    }

    fun showSource(tool: CloneTool) = StripItem(SHOW_SOURCE, SHOW_SOURCE_LABEL, SHOW_SOURCE_DP.dp, row = StripRow.MORE) {
        CheckChip(SHOW_SOURCE_LABEL, tool.showSource, SHOW_SOURCE_DP) { tool.setShowSource(it) }
    }
}

private val chipShape = RoundedCornerShape(8.dp)

/** v1.7's Set source chip at 92 dp: the target icon and "Set source" ("Tap the source", on two lines, while armed). */
@Composable
private fun SetSourceButton(tool: CloneTool) {
    val armed = tool.armed
    val hasSource = tool.anchor.source != null
    val content = if (armed) Color.White else BrushworkColors.OnChrome
    Row(
        Modifier
            .width(CloneStripItems.SET_SOURCE_DP.dp)
            .heightIn(min = 40.dp)
            .clip(chipShape)
            .background(if (armed) BrushworkColors.AccentDim else Color.Transparent)
            .border(1.dp, if (armed) BrushworkColors.AccentDim else MaterialTheme.colorScheme.outline, chipShape)
            .clickable { tool.arm(!armed) }
            .semantics { stateDescription = if (armed) "Waiting for a tap on the canvas" else if (hasSource) "Source set" else "No source" }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.MyLocation, contentDescription = null, tint = if (armed) Color.White else BrushworkColors.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (armed) CloneStripItems.ARMED_LABEL else CloneStripItems.SET_SOURCE_LABEL,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            color = content,
            maxLines = 2,
        )
    }
}

/** "Sample from" at 96 dp: "Sample" over the current choice and ▾; a dropdown with This layer / All layers. */
@Composable
private fun SampleFromMenu(tool: CloneTool) {
    var open by remember { mutableStateOf(false) }
    val all = tool.sampleAllLayers
    val current = if (all) CloneStripItems.ALL_LAYERS else CloneStripItems.THIS_LAYER
    Box(Modifier.width(CloneStripItems.SAMPLE_DP.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(chipShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, chipShape)
                .clickable { open = true }
                .semantics { contentDescription = CloneStripItems.sampleLabel(all) }
                .padding(start = 7.dp, end = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sample", fontSize = 10.sp, lineHeight = 11.sp, color = BrushworkColors.OnChromeDim, maxLines = 1)
                Text(current, fontSize = 12.sp, lineHeight = 14.sp, color = BrushworkColors.OnChrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = BrushworkColors.OnChromeDim, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.closeOnSecondFinger { open = false }, containerColor = IbisColors.Menu) {
            for ((label, allLayers) in listOf(CloneStripItems.THIS_LAYER to false, CloneStripItems.ALL_LAYERS to true)) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { tool.setSampleAllLayers(allLayers); open = false },
                    leadingIcon = { if (allLayers == all) Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
        }
    }
}

/** The More row's hint: v1.7's hint chip ("Long-press where to copy from"; a tap arms Set source). */
@Composable
private fun HintChip(onClick: () -> Unit) {
    Row(
        Modifier
            .width(CloneStripItems.HINT_DP.dp)
            .heightIn(min = 40.dp)
            .clip(chipShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, chipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.TouchApp, contentDescription = null, tint = BrushworkColors.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(CloneTool.HINT, fontSize = 12.sp, color = BrushworkColors.OnChrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** An on/off chip [widthDp] wide, a check mark while on (v1.7's CheckChip; state "On" / "Off"). */
@Composable
private fun CheckChip(label: String, checked: Boolean, widthDp: Float, onChange: (Boolean) -> Unit) {
    val content = if (checked) Color.White else BrushworkColors.OnChrome
    Row(
        Modifier
            .width(widthDp.dp)
            .heightIn(min = 40.dp)
            .clip(chipShape)
            .background(if (checked) BrushworkColors.AccentDim else Color.Transparent)
            .border(1.dp, if (checked) BrushworkColors.AccentDim else MaterialTheme.colorScheme.outline, chipShape)
            .clickable(role = Role.Checkbox) { onChange(!checked) }
            .semantics {
                selected = checked
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 13.sp, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
