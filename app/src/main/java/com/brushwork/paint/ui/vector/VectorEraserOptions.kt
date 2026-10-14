package com.brushwork.paint.ui.vector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.JoinInner
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.common.iconLongPressLabel
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes

/**
 * The vector eraser's mode chips (Object / Partial / To intersection; v1.5 §4.9, owned by A3),
 * shown FIRST in the eraser's strip (before its brush options) while the active layer is a
 * vector layer (not while its mask is painted: the eraser then erases mask pixels). The mode is
 * remembered (AppSettings.vectorEraserMode). The chips are compact, labels only on a phone, so
 * all three sit on the strip's first screen after the VECTOR chip from 360 dp up; wider screens
 * add their icons.
 */
@Composable
fun VectorEraserOptions(controller: EditorController) {
    val erasesObjects by remember(controller) {
        derivedStateOf {
            controller.layersVersion
            val layer = controller.activeLayer
            layer.isVectorLayer && controller.editTargetOf(layer) == EditTarget.CONTENT
        }
    }
    if (!erasesObjects) return
    val mode = VectorEraserModes.mode(controller)
    val icons = LocalConfiguration.current.screenWidthDp >= ICONS_FROM_DP
    VectorEraseMode.entries.forEach { m ->
        ModeChip(m.label, m == mode, if (icons) iconOf(m) else null) { VectorEraserModes.setMode(controller, m) }
    }
}

/** Screen width (dp) from which the mode chips also show their icons (tablets, landscape). */
private const val ICONS_FROM_DP = 480

/**
 * A choice chip like the strip's other option chips (outlined; accent when selected) with less
 * padding, and a full-size touch target.
 */
@Composable
private fun ModeChip(label: String, selected: Boolean, icon: ImageVector?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val content = if (selected) Color.White else BrushworkColors.OnChrome
    Row(
        Modifier
            .padding(horizontal = 2.dp)
            .minimumInteractiveComponentSize()
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(if (selected) BrushworkColors.AccentDim else Color.Transparent)
            .border(1.dp, if (selected) BrushworkColors.AccentDim else MaterialTheme.colorScheme.outline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = content)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * v1.8 (design §3.9; area D): the vector eraser's modes on the converted strip, one row-1 item of
 * three 40 dp icon buttons (120 dp) described by the mode labels ("Object", "Partial", "To
 * intersection", unchanged), the chosen one accent and selected; a long press shows the mode's
 * label (v1.8 polish).
 *
 * v1.9 (R11 a; A5): also on a legacy shape layer (the Eraser erases its shape as an object); the
 * item is [VectorEraseChooser.item].
 */
internal object VectorEraserStrip {
    const val KEY = "eraser.modes"
    const val WIDTH_DP = 120f

    /**
     * The eraser removes [controller]'s objects: a vector layer or (v1.9) a legacy shape layer,
     * its content (not its mask).
     */
    fun erasesObjects(controller: EditorController): Boolean {
        controller.layersVersion
        val layer = controller.activeLayer
        return VectorEraseChooser.holdsObjects(layer) && controller.editTargetOf(layer) == EditTarget.CONTENT
    }

    /** The three mode buttons, [mode] selected. */
    @Composable
    fun ModeButtons(mode: VectorEraseMode, modifier: Modifier = Modifier, onMode: (VectorEraseMode) -> Unit) {
        Row(modifier.width(WIDTH_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            VectorEraseMode.entries.forEach { m -> ModeButton(m, m == mode) { onMode(m) } }
        }
    }

    @Composable
    private fun ModeButton(m: VectorEraseMode, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .size(40.dp)
                .clip(shape)
                .background(if (selected) BrushworkColors.AccentDim else Color.Transparent)
                .testTag(V19Tags.ERASE_MODE_PREFIX + m.name)
                .iconLongPressLabel(m.label)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconOf(m), contentDescription = m.label, modifier = Modifier.size(22.dp), tint = if (selected) Color.White else BrushworkColors.OnChrome)
        }
    }
}

/** The mode's icon (v1.8): the buttons, and (v1.9) the face of the four tools' mode cell. */
internal fun iconOf(m: VectorEraseMode): ImageVector = when (m) {
    VectorEraseMode.OBJECT -> Icons.Outlined.DeleteSweep
    VectorEraseMode.PARTIAL -> Icons.Filled.ContentCut
    VectorEraseMode.TO_INTERSECTION -> Icons.Filled.JoinInner
}
