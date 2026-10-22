package com.brushwork.paint.testing

import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.common.PaintMode

/**
 * v1.10 F1 (design 2.16, R2 and R7; lead, frozen kit): an [ObjectColorEditor] with settable state
 * that records every call, for the pill's COLOR cell and the Object colors sheet tests of A2 and
 * A5 without a tool or an object. [targets] and [mode] (what [paintMode] answers) are settable
 * (Text: TEXT and OUTLINE, mode null); [faces] holds each target's committed face (absent =
 * `None`), [refusals] the reason [unavailable] gives.
 *
 * It follows the sheet contract (R7): [begin] keeps the start faces, [preview] and [setNone]
 * change the face at once (the live preview) and are pending, [commit] keeps them, [cancel] puts
 * the start faces back. [setPaintMode] applies at once. [calls] lists every call as text in order
 * (`"begin"`, `"preview FILL #FF336699"`, `"setNone OUTLINE"`, `"setPaintMode STROKE"`,
 * `"commit"`, `"cancel"`), and [inSession] says whether a [begin] is open. A [preview] or
 * [setNone] outside a session, a second [begin], or an edit of a refused target throws
 * `IllegalStateException`, so a cell that breaks the contract fails its test.
 */
class FakeObjectColorEditor(
    override var targets: List<ObjectColorTarget> = listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE),
    /** The paint mode [paintMode] answers (null: the object has none). */
    var mode: PaintMode? = PaintMode.BOTH,
) : ObjectColorEditor {
    override val paintMode: PaintMode? get() = mode

    /** Each target's face now (absent = `None`). */
    val faces: MutableMap<ObjectColorTarget, ObjectColorFace> = mutableMapOf()

    /** The reason toast of a target that cannot be edited now. */
    val refusals: MutableMap<ObjectColorTarget, String> = mutableMapOf()

    /** Every call, as text, in order. */
    val calls: MutableList<String> = mutableListOf()

    /** A [begin] is open (no [commit] or [cancel] yet). */
    var inSession = false
        private set

    /** How many sessions were committed and cancelled. */
    var commits = 0
        private set
    var cancels = 0
        private set

    private var start: Map<ObjectColorTarget, ObjectColorFace> = emptyMap()
    private var startMode: PaintMode? = null

    override fun face(t: ObjectColorTarget): ObjectColorFace = faces[t] ?: ObjectColorFace.None

    override fun unavailable(t: ObjectColorTarget): String? = refusals[t]

    override fun setPaintMode(m: PaintMode) {
        calls += "setPaintMode $m"
        mode = m
    }

    override fun begin() {
        calls += "begin"
        check(!inSession) { "begin() while a session is open" }
        inSession = true
        start = faces.toMap()
        startMode = mode
    }

    override fun preview(t: ObjectColorTarget, argb: Int) {
        calls += "preview $t ${"#%08X".format(argb)}"
        checkEdit(t)
        faces[t] = ObjectColorFace.Solid(argb)
    }

    override fun setNone(t: ObjectColorTarget) {
        calls += "setNone $t"
        checkEdit(t)
        faces[t] = ObjectColorFace.None
    }

    override fun commit() {
        calls += "commit"
        if (!inSession) return
        inSession = false
        commits++
    }

    override fun cancel() {
        calls += "cancel"
        if (!inSession) return
        inSession = false
        faces.clear()
        faces.putAll(start)
        mode = startMode
        cancels++
    }

    private fun checkEdit(t: ObjectColorTarget) {
        check(inSession) { "$t edited outside begin() / commit()" }
        check(t in targets) { "$t is not a target of this object ($targets)" }
        check(refusals[t] == null) { "$t is refused: ${refusals[t]}" }
    }
}
