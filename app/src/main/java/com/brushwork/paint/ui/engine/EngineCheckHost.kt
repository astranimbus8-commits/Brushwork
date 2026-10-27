package com.brushwork.paint.ui.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.check.AutoCheckRunner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * v1.10 (F1, J32, design 2.10; signature frozen, A10 body): runs the automatic check in the
 * background while the gallery shows (`GalleryScreen`). F1: nothing.
 *
 * A10: 1 s after the gallery's first frame, and while the app is started, it asks the process's
 * [AutoCheckRunner] to run the check if it is due ([AutoCheckRunner.launchIfDue], on the check's
 * own thread). Leaving the gallery (opening an artwork) or `ON_STOP` stops it before its next step
 * ([AutoCheckRunner.requestStop], which also deletes the marker); the finished steps are kept and
 * the next start resumes with the rest. The runner itself never measures while an editor is open.
 */
@Composable
fun EngineCheckHost() {
    val app = LocalContext.current.applicationContext as? BrushworkApp ?: return
    if (!AutoCheckRunner.galleryRuns) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(app, lifecycle) {
        withFrameNanos { }
        delay(FIRST_FRAME_DELAY_MS)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val runner = AutoCheckRunner.of(app)
            try {
                runner.launchIfDue()
                awaitCancellation()
            } finally {
                runner.requestStop()
            }
        }
    }
}

/** The check starts this long after the gallery's first frame. */
internal const val FIRST_FRAME_DELAY_MS = 1_000L
