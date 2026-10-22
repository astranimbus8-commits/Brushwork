package com.brushwork.paint.engine.budget

import java.io.File

/** v1.10 (F1 stub, design 3.3; body A10): the phone's [StorageProbe]. The F1 body measures nothing. */
object AndroidStorageProbe : StorageProbe {
    override fun freeBytes(dir: File): Long = 0

    override fun writeRead(dir: File, bytes: Long): Pair<Float, Float> = 0f to 0f
}
