package com.brushwork.paint.v19.a7b

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.UndoAction

/**
 * A7b tests: a Java heap of [max] bytes with [used] in use, and [native] bytes of native heap (where
 * bitmap pixels live). What [release] lets go of the Java heap stays counted until [collect] (as
 * garbage does until a collection), so a test sees what the policy reads.
 */
class FakeHeap(private val max: Long, var used: Long, var native: Long = 0L) : GovernorPolicyV19.Heap {
    var garbage = 0L
    var collections = 0

    fun release(bytes: Long) { garbage += bytes }

    override fun used(): Long = used

    override fun max(): Long = max

    override fun nativeUsed(): Long = native

    override fun collect() {
        collections++
        used -= garbage
        garbage = 0L
    }
}

/**
 * An undo step named [label] holding [bytes] of [heap]'s native memory (a pixel step's tiles), its
 * [byteSize]; disposing it lets them go at once, as recycling a bitmap does.
 */
class HeapStep(override val label: String, private val heap: FakeHeap, private val bytes: Long) : UndoAction {
    var disposed = false
    override val byteSize: Long get() = bytes
    override fun undo(c: EditorController) {}
    override fun redo(c: EditorController) {}
    override fun dispose() {
        disposed = true
        heap.native -= bytes
    }
}
