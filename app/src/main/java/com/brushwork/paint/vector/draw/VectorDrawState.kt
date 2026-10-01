package com.brushwork.paint.vector.draw

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.brushwork.paint.EditorController
import java.util.WeakHashMap

/**
 * What the vector drawing seams (brush capture, eraser, bucket; v1.5 A3) remember per editor:
 * the eraser mode (Compose state, persisted in `AppSettings.vectorEraserMode`), the one-time
 * hints of this editor session and the object geometry cache. Main thread.
 */
internal class VectorDrawState(c: EditorController) {
    /** The vector eraser's mode (Compose state). */
    val eraseMode: MutableState<VectorEraseMode> = mutableStateOf(VectorEraseMode.parse(c.settings.vectorEraserMode))

    /** "Selections don't limit vector strokes" was shown. */
    var selectionHintShown = false

    /** "Closed shapes and fills are erased whole" was shown. */
    var partialWholeHintShown = false

    /** Geometry of the objects the eraser and the bucket test (by identity). */
    val targets = TargetCache()

    /** True while the bucket traces an enclosed area (taps are ignored meanwhile). */
    var filling = false

    companion object {
        private val states = WeakHashMap<EditorController, VectorDrawState>()

        fun of(c: EditorController): VectorDrawState = states.getOrPut(c) { VectorDrawState(c) }
    }
}

/** The vector eraser's mode for editor [c] (Compose state) and how to change it (persisted). */
object VectorEraserModes {
    fun mode(c: EditorController): VectorEraseMode = VectorDrawState.of(c).eraseMode.value

    fun setMode(c: EditorController, m: VectorEraseMode) {
        val s = VectorDrawState.of(c)
        if (s.eraseMode.value == m) return
        s.eraseMode.value = m
        c.settings.vectorEraserMode = m.name
    }
}
