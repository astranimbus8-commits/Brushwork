package com.brushwork.paint.v110.a2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.common.PaintMode

/**
 * A2 test kit: an [ObjectColorEditor] over a fake open object (a fill, a stroke and a paint mode,
 * Compose state) that records every call, for the Object colors sheet's tests. Its own "session"
 * stands for the tool's: [check] keeps the colors (the ✓ step, counted in [steps]), [cross]
 * restores the colors the session opened with (✕); either ends it ([sessionOpen]).
 */
internal class A2FakeColorEditor(fill: Int, stroke: Int, mode: PaintMode = PaintMode.BOTH) : ObjectColorEditor {
    var fill by mutableIntStateOf(fill)
    var stroke by mutableIntStateOf(stroke)
    var mode by mutableStateOf(mode)
    var sessionOpen by mutableStateOf(true)
    var steps = 0
        private set
    val calls = mutableListOf<String>()

    private val opened = Triple(fill, stroke, mode)
    private var start = opened

    override val targets: List<ObjectColorTarget> get() = listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE)
    override val paintMode: PaintMode get() = mode

    override fun face(t: ObjectColorTarget): ObjectColorFace = when (t) {
        ObjectColorTarget.FILL -> if (mode == PaintMode.STROKE) ObjectColorFace.None else ObjectColorFace.Solid(fill)
        else -> ObjectColorFace.Solid(stroke)
    }

    override fun unavailable(t: ObjectColorTarget): String? = null

    override fun setPaintMode(m: PaintMode) {
        calls += "mode $m"
        mode = m
    }

    override fun begin() {
        calls += "begin"
        start = Triple(fill, stroke, mode)
    }

    override fun preview(t: ObjectColorTarget, argb: Int) {
        if (!sessionOpen) return
        calls += "preview $t ${hex(argb)}"
        if (t == ObjectColorTarget.FILL) {
            fill = argb
            if (mode == PaintMode.STROKE) mode = PaintMode.BOTH
        } else {
            stroke = argb
            if (mode == PaintMode.FILL) mode = PaintMode.BOTH
        }
    }

    override fun setNone(t: ObjectColorTarget) {
        calls += "none $t"
        if (t == ObjectColorTarget.FILL) mode = PaintMode.STROKE
    }

    override fun commit() {
        calls += "commit"
    }

    override fun cancel() {
        calls += "cancel"
        fill = start.first
        stroke = start.second
        mode = start.third
    }

    /** The tool's ✓: the previewed colors stay in the object's one step; the session ends. */
    fun check() {
        steps++
        sessionOpen = false
    }

    /** The tool's ✕: the object goes back to how the session opened it; the session ends. */
    fun cross() {
        fill = opened.first
        stroke = opened.second
        mode = opened.third
        sessionOpen = false
    }

    companion object {
        fun hex(c: Int) = "%08X".format(c)
    }
}
