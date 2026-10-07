package com.brushwork.paint.ui.symmetry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * The symmetry rulers in the Ruler panel (v1.7 item 18, §3.18: ibisPaint lists them with the
 * rulers, so the Brush user reaches them without switching tools): the type chips
 * (`SymmetryType.label`), "Divisions" for the kaleidoscope and rotation, "Reset symmetry", and
 * "Symmetry" ([onEditOnCanvas]: the Symmetry tool, to place the ruler on the canvas). Changes are
 * live and not undoable, like the ruler's.
 */
@Composable
fun SymmetryRulerSection(controller: EditorController, onEditOnCanvas: () -> Unit) {
    val s = controller.symmetry.sanitized()
    SectionHeader("Symmetry rulers")
    ChoiceChips(
        options = SymmetryType.entries.map { it.label },
        selected = s.type.ordinal,
        onSelect = { i -> SymmetryUi.update(controller) { it.copy(type = SymmetryType.entries[i]) } },
    )
    Text(
        hint(s.type),
        style = MaterialTheme.typography.bodySmall,
        color = BrushworkColors.OnChromeDim,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
    if (s.type == SymmetryType.KALEIDOSCOPE || s.type == SymmetryType.ROTATION) DivisionsField(controller, s.divisions)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(
            onClick = { SymmetryUi.update(controller) { SymmetryUi.reset(it) } },
            enabled = s != SymmetryUi.reset(s),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(SymmetryLabels.RESET, maxLines = 1)
        }
        Button(
            onClick = onEditOnCanvas,
            modifier = Modifier.semantics { contentDescription = SymmetryLabels.TOOL },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Filled.Flip, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Place on canvas", maxLines = 1)
        }
    }
}

/** What the chosen symmetry ruler does (one line under the chips). */
private fun hint(type: SymmetryType): String = when (type) {
    SymmetryType.OFF -> "Brush strokes are repeated by a symmetry ruler: mirrored, turned or tiled."
    SymmetryType.MIRROR -> "Every brush stroke is mirrored across the axis."
    SymmetryType.KALEIDOSCOPE -> "Every brush stroke is turned around the centre and mirrored, like a kaleidoscope."
    SymmetryType.ROTATION -> "Every brush stroke is turned around the centre."
    SymmetryType.ARRAY -> "Every brush stroke repeats in each cell of the grid."
    SymmetryType.PERSPECTIVE_ARRAY -> "Every brush stroke repeats in each cell of a grid in perspective."
}
