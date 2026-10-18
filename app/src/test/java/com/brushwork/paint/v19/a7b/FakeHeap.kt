package com.brushwork.paint.v19.a7b

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.UndoAction

/**
 * A7b tests: a Java heap of [max] bytes with [used] in use. What [release] lets go stays counted
 * until [collect] (as garbage does until a collection), so a test sees what the policy reads.
 */
class FakeHeap(private val max: Long, var used: Long) : GovernorPolicyV19.Heap {
    var garbage = 0L
    var collections = 0

    fun release(bytes: Long) { garbage += bytes }

    override fun used(): Long = used

    override fun max(): Long = max

    override fun collect() {
        collections++
        used -= garbage
        garbage = 0L
    }
}

/** An undo step named [label] that lets [bytes] of [heap] go when the history drops it. */
class HeapStep(override val label: String, private val heap: FakeHeap, private val bytes: Long) : UndoAction {
    var disposed = false
    override val byteSize: Long get() = 0L
    override fun undo(c: EditorController) {}
    override fun redo(c: EditorController) {}
    override fun dispose() {
        disposed = true
        heap.release(bytes)
    }
}
