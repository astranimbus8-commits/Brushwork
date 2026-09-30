package com.brushwork.paint.fonts

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.launch

/**
 * "Open with Brushwork" / "Share to Brushwork" for font files: a .ttf / .otf / .ttc or a dafont
 * .zip opened from a file manager or a browser download (see the intent filters in the manifest)
 * is imported into the [FontStore], a toast says what was added, and the activity closes again,
 * back to the app the file came from.
 */
object FontOpenIntent {

    private val ACTIONS = setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)

    /** The documents [intent] asks to open (empty when it isn't an open / share intent). */
    fun uris(intent: Intent?): List<Uri> {
        if (intent == null || intent.action !in ACTIONS) return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            else -> streamListExtra(intent)
        }.filter { it.scheme == "content" || it.scheme == "file" }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty().filterNotNull()

    /**
     * If [intent] opens font files: shows "Importing fonts…", imports them, shows the result in a
     * toast and finishes [activity] afterwards (the read permission of the documents lasts as
     * long as the activity). Returns true when it took the intent (the caller shows nothing else).
     */
    fun handle(activity: Activity, intent: Intent?): Boolean {
        val uris = uris(intent)
        if (uris.isEmpty()) return false
        val app = activity.applicationContext
        val store = FontStore.get(app)
        activity.setContentView(importingView(activity))
        // App-wide scope: the import finishes even while the activity goes away.
        FontStore.appScope.launch {
            val message = try {
                val report = store.importUris(app, uris)
                if (report.added.isNotEmpty()) "${report.message}. Pick it in the Text tool's font list." else report.message
            } catch (e: Exception) {
                "Couldn't import the font: ${e.message ?: e.javaClass.simpleName}"
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            if (!activity.isFinishing) {
                // Opened in a task of its own: remove that (else empty) task from Recents too.
                // Never when it sits on top of the app's own task (the editor lives below).
                if (activity.isTaskRoot) activity.finishAndRemoveTask() else activity.finish()
            }
        }
        return true
    }

    /**
     * What shows while the files are read (usually well under a second): a plain view, as this
     * screen lives only for that moment (no Compose, nothing animating).
     */
    private fun importingView(activity: Activity): View {
        val label = TextView(activity).apply {
            text = "Importing fonts…"
            textSize = 18f
            setTextColor(BrushworkColors.OnChrome.toArgb())
            gravity = Gravity.CENTER
        }
        return FrameLayout(activity).apply {
            setBackgroundColor(BrushworkColors.Chrome.toArgb())
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }
}
