package com.brushwork.paint.engine

/**
 * v1.10 (F1, design 2.11): a one-time gallery notice about the layer storage. [CheckChose] names
 * a level below FULL (CLASSIC or TILES only, UQ15) and the check's reason; [LowMemoryClosings]
 * follows the second low-memory closing (section 2.12).
 */
sealed interface EngineNotice {
    data class CheckChose(val level: EngineLevel, val reason: String) : EngineNotice

    data object LowMemoryClosings : EngineNotice
}

/** v1.10 (F1, design 2.11): the notice waiting for the gallery. A10 sets it; the gallery shows it; OK clears it. */
object EngineNotices {
    @Volatile
    var current: EngineNotice? = null
}
