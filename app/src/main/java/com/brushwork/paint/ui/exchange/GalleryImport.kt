package com.brushwork.paint.ui.exchange

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.storage.ProjectRepository

/**
 * The gallery's "New from SVG or PDF" (v1.5 §4.11; owned by A8): returns the action that picks
 * a file, creates a project of the right size, hands the file over through `PendingImports` and
 * opens it with [onOpenProject]. Foundation stub: the action only says it is coming.
 */
@Composable
fun rememberGalleryImport(repository: ProjectRepository, onOpenProject: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    return remember(repository, context) {
        { Toast.makeText(context, "New from SVG or PDF is coming soon", Toast.LENGTH_SHORT).show() }
    }
}
