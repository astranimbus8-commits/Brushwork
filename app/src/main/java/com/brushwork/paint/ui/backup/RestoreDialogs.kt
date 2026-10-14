package com.brushwork.paint.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.RestoreItem
import com.brushwork.paint.ui.common.BackupLabels19
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.9 A8 (R16 (a)): the picked folder already holds a backup. [BackupLabels19.RESTORE_THEM]
 * (primary) opens the restore list; [BackupLabels19.BACK_UP_HERE] makes the folder the backup
 * folder without restoring. Dismissed, nothing changes: no folder is set and nothing is written.
 */
@Composable
internal fun RestoreOfferDialog(offer: BackupFlow.Dialog.Offer) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (BackupFlow.dialog === offer) BackupFlow.dialog = null },
        text = {
            Text(
                String.format(BackupLabels19.FOUND, offer.pick.source.count),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(V19Tags.RESTORE_OFFER),
            )
        },
        confirmButton = {
            Button(
                onClick = { BackupFlow.restoreOffered(offer) },
                colors = ButtonDefaults.buttonColors(containerColor = BrushworkColors.Accent),
            ) { Text(BackupLabels19.RESTORE_THEM) }
        },
        dismissButton = {
            TextButton(onClick = { BackupFlow.backUpHere(context, offer) }) { Text(BackupLabels19.BACK_UP_HERE) }
        },
        containerColor = BrushworkColors.ChromeHigh,
    )
}

/**
 * v1.9 A8 (R16 (a)): [BackupLabels19.RESTORE_TITLE], the artworks the backup holds, 48 dp rows.
 * Works the gallery has at the same revision are marked [BackupLabels19.ALREADY] and unchecked;
 * deleted ones sit in the collapsed [BackupLabels19.DELETED_SECTION], unchecked;
 * [BackupLabels19.ALSO_SETTINGS] is on. Closing it (restoring or not) sets the folder it came with.
 */
@Composable
internal fun RestoreListDialog(list: BackupFlow.Dialog.Restore) {
    val context = LocalContext.current
    var items by remember(list) { mutableStateOf<List<RestoreItem>?>(null) }
    val checked = remember(list) { mutableStateListOf<String>() }
    var alsoSettings by remember(list) { mutableStateOf(true) }
    var deletedOpen by remember(list) { mutableStateOf(false) }
    LaunchedEffect(list) {
        val found = BackupService.listRestore(context, list.source)
        checked.clear()
        checked += found.filter { it.checkedByDefault }.map { it.id }
        items = found
    }
    val chosen = items.orEmpty().filter { it.id in checked }.map { it.id }
    BwDialog(
        title = BackupLabels19.RESTORE_TITLE,
        onDismiss = { BackupFlow.closeRestore(context, list, emptyList(), alsoSettings = false) },
        confirmText = String.format(BackupLabels19.RESTORE_CONFIRM, chosen.size),
        onConfirm = { BackupFlow.closeRestore(context, list, chosen, alsoSettings) },
        confirmEnabled = chosen.isNotEmpty(),
    ) {
        val all = items
        if (all == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@BwDialog
        }
        fun toggle(id: String, on: Boolean) {
            if (on) { if (id !in checked) checked += id } else checked -= id
        }
        for (item in all.filter { !it.deleted }) RestoreRow(item, item.id in checked) { toggle(item.id, it) }
        val deleted = all.filter { it.deleted }
        if (deleted.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { deletedOpen = !deletedOpen },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(BackupLabels19.DELETED_SECTION, style = MaterialTheme.typography.bodyMedium, color = BrushworkColors.OnChromeDim, modifier = Modifier.weight(1f))
                Icon(if (deletedOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            if (deletedOpen) for (item in deleted) RestoreRow(item, item.id in checked) { toggle(item.id, it) }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = alsoSettings, role = Role.Checkbox) { alsoSettings = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = alsoSettings, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(BackupLabels19.ALSO_SETTINGS, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RestoreRow(item: RestoreItem, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
            if (item.already) Text(BackupLabels19.ALREADY, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        }
    }
}
