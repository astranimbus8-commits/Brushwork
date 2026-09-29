package com.brushwork.paint.ui.gallery

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.storage.ImageExport

/**
 * Returns a function that runs an export action, first asking for WRITE_EXTERNAL_STORAGE on
 * Android 9 and older, where `ProjectRepository.exportToGallery` needs it (newer versions run
 * the action immediately). [onDenied] runs when the user refuses.
 */
@Composable
fun rememberStoragePermissionGate(onDenied: () -> Unit): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val denied by rememberUpdatedState(onDenied)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else denied()
    }
    return remember(context, launcher) {
        { action ->
            if (ImageExport.needsLegacyPermission(context)) {
                pending = action
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                action()
            }
        }
    }
}
