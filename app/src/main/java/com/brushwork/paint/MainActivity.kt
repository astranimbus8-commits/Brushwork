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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.fonts.FontOpenIntent
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
                is EditorSession.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is EditorSession.State.Failed -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(st.message, color = MaterialTheme.colorScheme.onBackground)
                    Button(onClick = { app.closeEditor { openId = null } }, modifier = Modifier.padding(top = 16.dp)) { Text("Back to gallery") }
                }
                is EditorSession.State.Ready -> EditorScreen(
                    controller = st.controller,
                    onExit = exit,
                    onSaveNow = { session.saveNow() },
                )
            }
        }
    }
}
