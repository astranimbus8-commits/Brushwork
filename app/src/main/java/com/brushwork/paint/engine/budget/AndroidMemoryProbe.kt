package com.brushwork.paint.engine.budget

/**
 * v1.10 (F1 stub, design 3.3; body A7): the phone's [MemoryProbe]. The F1 body knows nothing:
 * `totalMem == 0`, so `Budgets.compute` gives `Budgets.v19` (J3) and the engine resolves to
 * CLASSIC with v1.9's numbers (I32).
 */
object AndroidMemoryProbe : MemoryProbe {
    override fun snapshot(): MemSnapshot = MemSnapshot(0, 0, 0, false, false, false, 0, 0, 0)

    override fun probeNative(stepBytes: Long, stopBelowAvail: Long, capBytes: Long): Long = 0
}
