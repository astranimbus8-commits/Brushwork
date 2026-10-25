package com.brushwork.paint.ui.engine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brushwork.paint.AppSettings
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineStats
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckDetails
import com.brushwork.paint.engine.check.CheckNotices
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.storage.engine.EngineState
import com.brushwork.paint.storage.engine.EngineStateStore
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.10 (F1, J14, J32, design 2.11; signature frozen, A10 body): the layer storage section of
 * `EditorSettingsDialog`, after Autosave and before Backup. F1: nothing, so the dialog is v1.9's.
 *
 * A10: one row, tagged [V110Tags.LAYER_STORAGE]: [EngineLabels110.LAYER_STORAGE], the effective
 * choice under it ([subtitleOf]), and [EngineLabels110.FOR_THIS_PHONE] (the row is the phone's,
 * not the artwork's). A tap opens [LayerStorageSheet]: a radio writes `AppSettings.layerStorageMode`
 * at once (it applies at the next open; the gallery's budget follows through
 * `EngineConfig.applyIdle`), [EngineLabels110.CHECK_AGAIN] sets `engineCheckPending`
 * ([AutoCheckRunner.requestCheck]) and [EngineLabels110.COPY_DETAILS] copies [CheckDetails]' text.
 * The stored check and the engine state are read off the main thread when the section shows and
 * after each change.
 */
@Composable
fun LayerStorageSection() {
    val context = LocalContext.current
    val app = context.applicationContext
    val settings = remember(app) { (app as? BrushworkApp)?.settings ?: AppSettings(app) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(settings.layerStorageMode) }
    var stored by remember { mutableStateOf<Stored?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reads) {
        stored = withContext(Dispatchers.IO) { Stored(CheckStore.read(app), EngineStateStore.read(app)) }
    }
    val subtitle = stored?.let { subtitleOf(mode, it.check, it.state.engineCheckPending) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 10.dp)
            .testTag(V110Tags.LAYER_STORAGE)
            .clickable(role = Role.Button) { open = true }
            .padding(vertical = 4.dp),
    ) {
        Text(EngineLabels110.LAYER_STORAGE, style = MaterialTheme.typography.bodyMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        Text(EngineLabels110.FOR_THIS_PHONE, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
    }
    if (open) {
        LayerStorageSheet(
            mode = mode,
            subtitle = subtitle,
            pending = stored?.state?.engineCheckPending == true,
            onMode = { m ->
                if (m != mode) {
                    settings.layerStorageMode = m
                    mode = m
                    CheckNotices.onModeChosen(app, m)
                    val check = stored?.check
                    EngineConfig.applyIdle(m, check, Budgets.compute(EngineConfig.memoryProbe, check?.stepDown ?: 0))
                }
            },
            onCheckAgain = {
                scope.launch {
                    withContext(Dispatchers.IO) { AutoCheckRunner.requestCheck(app) }
                    reads++
                }
            },
            onCopyDetails = {
                val level = EngineConfig.level
                val budgets = EngineConfig.budget.budgets
                val chosen = mode
                scope.launch {
                    val text = withContext(Dispatchers.IO) {
                        CheckDetails.text(chosen, level, budgets, CheckStore.read(app), EngineStateStore.read(app), EngineStats.summary())
                    }
                    CheckDetails.copy(context, text)
                }
            },
            onDismiss = { open = false },
        )
    }
}

/** The stored check and the engine state, as the section last read them. */
private class Stored(val check: EngineCheckResult?, val state: EngineState)

/**
 * The row's subtitle (design 2.11): [EngineLabels110.CHECK_PENDING] after [EngineLabels110.CHECK_AGAIN] until
 * the check runs; a chosen mode as chosen; Automatic as the stored [check] decided it, with the reason
 * of a Classic result. No result, or a provisional Classic one, reads
 * [EngineLabels110.SUB_NO_CHECK]; a provisional Full or TILES result shows its level, which the
 * sessions already run with (`EngineConfig.resolve`).
 */
internal fun subtitleOf(mode: LayerStorageMode, check: EngineCheckResult?, pending: Boolean): String = when {
    pending -> EngineLabels110.CHECK_PENDING
    mode == LayerStorageMode.FULL -> EngineLabels110.SUB_FULL_CHOSEN
    mode == LayerStorageMode.CLASSIC -> EngineLabels110.SUB_CLASSIC_CHOSEN
    check == null || (check.provisional && check.level == EngineLevel.CLASSIC) -> EngineLabels110.SUB_NO_CHECK
    check.level == EngineLevel.FULL -> EngineLabels110.SUB_FULL
    check.level == EngineLevel.TILES -> EngineLabels110.SUB_TILES
    else -> String.format(EngineLabels110.SUB_CLASSIC, check.reason.ifEmpty { CheckReasons.LOW_MEMORY })
}

