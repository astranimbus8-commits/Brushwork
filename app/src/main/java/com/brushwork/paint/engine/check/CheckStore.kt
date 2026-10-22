package com.brushwork.paint.engine.check

import android.content.Context
import com.brushwork.paint.storage.engine.EngineCheckResult

/**
 * v1.10 (F1, J32, design 2.10; signature frozen, A10 body): the automatic check's stored result
 * (`noBackupFilesDir/engine-check.json`, A10), read by `EditorSession` before the controller
 * exists. F1: no result, so every session runs at CLASSIC with v1.9's numbers.
 */
object CheckStore {
    @Suppress("UNUSED_PARAMETER")
    fun read(context: Context): EngineCheckResult? = null
}
