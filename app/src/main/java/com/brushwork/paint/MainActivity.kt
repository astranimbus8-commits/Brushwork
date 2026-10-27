package com.brushwork.paint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.fonts.FontOpenIntent
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.gallery.GalleryScreen
import com.brushwork.paint.ui.theme.BrushworkTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as BrushworkApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "Open with Brushwork" on a font file (.ttf / .otf / dafont .zip): import it and close.
        if (savedInstanceState == null && FontOpenIntent.handle(this, intent)) return
        enableEdgeToEdge()
        setContent {
            BrushworkTheme {
                AppRoot(app)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Autosave whenever the app leaves the screen (home button, app switch, screen off),
        // with the work still landing in the background (v1.7 QA).
        app.editorSession?.saveOnLeaving()
    }
}

@Composable
private fun AppRoot(app: BrushworkApp) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = openId
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (id == null) {
            GalleryScreen(app.repository, onOpenProject = { openId = it })
        } else {
            val session = remember(id) { app.openEditor(id) }
            val exit = { app.closeEditor { openId = null } }
            BackHandler { exit() }
            when (val st = session.state) {
                is EditorSession.State.Loading -> OpeningScreen(session.opening.collectAsState().value)
                is EditorSession.State.Failed -> OpenFailedScreen(
                    message = st.message,
                    offersFullStorage = session.offersFullStorage,
                    onOpenWithFullStorage = { session.openWithFullStorage() },
                    onBack = { app.closeEditor { openId = null } },
                )
                is EditorSession.State.Ready -> EditorScreen(
                    controller = st.controller,
                    onExit = exit,
                    onSaveNow = { session.saveNow() },
                )
            }
        }
    }
}

/**
 * The open spinner (v1.0) and, v1.10 Gate 2 (lane open, O2; design 4.4 A11 step 7), under it
 * "Opening layer n of m" ([EngineLabels110.OPENING_LAYERS]) while [progress] is not null: an
 * open that has run 1 s (`EditorSession.opening`), updated as the layers decode.
 */
@Composable
internal fun OpeningScreen(progress: EditorSession.OpenProgress?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (progress != null) {
            Text(
                EngineLabels110.OPENING_LAYERS.format(progress.layer, progress.of),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * The artwork could not open: why ([message]) and the button back to the gallery. v1.10 (J16,
 * UQ16, design 2.9): while the session [offersFullStorage] (Classic refused a big artwork for a
 * reason other than memory), the [EngineLabels110.OPEN_WITH_FULL] button above it opens this
 * artwork at Full (`EditorSession.openWithFullStorage`).
 */
@Composable
internal fun OpenFailedScreen(
    message: String,
    offersFullStorage: Boolean,
    onOpenWithFullStorage: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onBackground)
        if (offersFullStorage) {
            Button(onClick = onOpenWithFullStorage, modifier = Modifier.padding(top = 16.dp)) { Text(EngineLabels110.OPEN_WITH_FULL) }
        }
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back to gallery") }
    }
}
