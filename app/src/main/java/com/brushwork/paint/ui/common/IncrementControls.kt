package com.brushwork.paint.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.ui.theme.BrushworkColors

/*
 * The increments UI (v1.6 §3.4; area G): the Step popup, the Increments sheet (More ›
 * Increments…, `EditorPanel.INCREMENTS`) and its Settings section. Foundation stubs with the
 * frozen signatures: the sheet and the section offer only the master switch.
 */

/**
 * Long-press on a number or slider value opens the Step popup for [kind] ("Step for angles:
 * [15] ° · Increments [On]"), or for the custom step of control [key] when [kind] is null.
 * Foundation stub: returns the modifier unchanged.
 */
fun Modifier.stepOnLongPress(kind: IncrementKind?, key: String? = null): Modifier = this

/**
 * More › Increments…: the master switch plus the five kind fields and "Clear custom steps"
 * (area G). Foundation stub: a sheet with the master switch only. [onDismiss] closes it.
 */
@Composable
fun IncrementsSheet(controller: EditorController, onDismiss: () -> Unit) {
    BwSheet(title = "Increments", onDismiss = onDismiss) {
        IncrementsSettingsSection(controller)
    }
}

/**
 * Settings › the Increments section: the same content as [IncrementsSheet] (area G).
 * Foundation stub: the master switch.
 */
@Composable
fun IncrementsSettingsSection(controller: EditorController) {
    val inc = controller.increments
    val s = inc.state
    ToggleRow(
        "Use increments",
        s.enabled,
        { on -> inc.update { it.copy(enabled = on) } },
        description = "Moves, sizes, scales and angles snap to steps",
    )
    Text(
        "Steps: ${fmt(s.lengthPx)} px · ${fmt(s.sizePx)} px size · ${fmt(s.scalePercent)} % · ${fmt(s.angleDeg)}° · ${fmt(s.percent)} %",
        style = MaterialTheme.typography.bodySmall,
        color = BrushworkColors.OnChromeDim,
    )
}

private fun fmt(v: Float): String = com.brushwork.paint.core.Units.formatNumber(v.toDouble(), 2)
