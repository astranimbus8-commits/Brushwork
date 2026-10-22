package com.brushwork.paint.engine.budget

/** v1.10 (F1 stub, design 3.3; body A10): the phone's [ExitInfoSource]. The F1 body reports no exit. */
object AndroidExitInfo : ExitInfoSource {
    override fun exits(sinceMillis: Long): List<ExitRecord> = emptyList()

    override fun lmkReportsSupported(): Boolean = false
}
