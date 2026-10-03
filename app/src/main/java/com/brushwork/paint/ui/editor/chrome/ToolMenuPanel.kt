package com.brushwork.paint.ui.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.BwSheetTitleKey
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.editor.ToolMenuEntry
import com.brushwork.paint.ui.editor.blockCanvasTouches
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims

/** The title the tool menu carries for tests and accessibility (the v1.5 Tools sheet's title). */
internal const val TOOL_MENU_TITLE = "Tools"

/**
 * The ibisPaint tool menu (v1.6 §3.7.6; replaces the v1.5 Tools sheet): a dark translucent panel
 * 150 dp wide of two columns of 75 × 52 dp cells in [ToolMenu] order, scrolling past [maxHeight].
 * The current tool's cell is lighter with an accent glyph; in vector mode the tools that need
 * pixels carry a "px" badge (they stay usable). A tap on a cell acts; the caller closes the menu.
 * The menu is not shown while a filter is previewed, so every cell is always enabled.
 */
@Composable
internal fun ToolMenuPanel(
    controller: EditorController,
    maxHeight: Dp,
    onPickTool: (ToolId) -> Unit,
    onFilters: () -> Unit,
    onCanvas: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = controller.activeToolId
    val vectorMode = controller.isVectorMode
    val shape = RoundedCornerShape(IbisDims.ToolMenuRadius)
    Column(
        modifier
            .width(IbisDims.ToolMenuWidth)
            .heightIn(max = maxHeight)
            .semantics {
                paneTitle = TOOL_MENU_TITLE
                this[BwSheetTitleKey] = TOOL_MENU_TITLE
            }
            .clip(shape)
            .background(IbisColors.Panel, shape)
            .blockCanvasTouches()
            .verticalScroll(rememberScrollState()),
    ) {
        ToolMenu.entries.chunked(ToolMenu.COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.Start) {
                row.forEach { entry ->
                    when (entry) {
                        is ToolMenuEntry.Tool -> ToolCell(
                            icon = EditorIcons.tool(entry.id),
                            label = entry.id.label,
                            selected = entry.id == active,
                            badge = if (vectorMode && entry.id in LayerToolRules.PIXEL_ONLY) "px" else null,
                            onClick = { onPickTool(entry.id) },
                        )
                        ToolMenuEntry.Filters -> ToolCell(EditorIcons.FiltersTile, "Filters", onClick = onFilters)
                        ToolMenuEntry.Canvas -> ToolCell(Icons.Filled.AspectRatio, "Canvas", onClick = onCanvas)
                        ToolMenuEntry.Settings -> ToolCell(Icons.Filled.Settings, "Settings", onClick = onSettings)
                    }
                }
            }
        }
    }
}

/** One 75 × 52 dp cell: a 28 dp white glyph over an 11 sp white label (up to 2 lines). */
@Composable
private fun ToolCell(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(IbisDims.ToolCellWidth, IbisDims.ToolCellHeight)
            .background(if (selected) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) IbisColors.Accent else Color.White, modifier = Modifier.size(IbisDims.ToolCellGlyph))
            Text(
                label,
                fontSize = IbisDims.ToolCellText,
                lineHeight = 12.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Text(
                badge,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = BrushworkColors.OnChrome,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BrushworkColors.ChromeBorder)
                    .padding(horizontal = 3.dp),
            )
        }
    }
}
