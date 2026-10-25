package com.brushwork.paint.engine.budget

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19
import kotlin.math.floor
import kotlin.math.max

/**
 * v1.10 (F1, design 2.4 and 2.13; constructor frozen, body A7): the [LayerBudget] of a session
 * (`EngineConfig.openSession`) and of the gallery (`EngineConfig.applyIdle`). The F1 body
 * delegates every member to [LayerBudgetV19]; A7 replaces the delegation with sections 2.4 and
 * 2.13 and keeps delegating whenever `budget.budgets.known` is false (I32).
 */
@Suppress("unused")
class LayerBudgetV110(private val budget: MemoryBudget, private val level: EngineLevel) : LayerBudget by LayerBudgetV19 {
    companion object {
        /** Section 2.15: the undo RAM and caches every level reserves besides the layers (184 MB). */
        internal const val RESERVE_BYTES = 184_000_000L

        /** Section 2.15: Classic's resident layer-sized buffers besides the painted layers. */
        internal const val CLASSIC_BUFFERS = 3.8

        /**
         * v1.9's layer cap for a heap of [javaMax] and layers of [layerBytes]: 55 % of the heap
         * less 3 layer-sized buffers, clamped to 2..100 (`LayerBudgetV19.maxLayers`).
         */
        internal fun v19Cap(javaMax: Long, layerBytes: Long): Int =
            ((javaMax * 0.55).toLong() / max(1L, layerBytes) - 3).toInt().coerceIn(2, 100)

        /**
         * Section 2.15: Classic's painted layers, `max(v19Cap(J), (R − 3.8·L − 184 MB)/L)`, at most
         * [LayerBudget.MAX_LAYERS_GUARD]. The v1.9 floor keeps a phone whose R is near its floor
         * (0.55 J) at v1.9's count: the R term alone is about 6 phone layers there.
         */
        internal fun classicMaxLayers(nativeBudget: Long, javaMax: Long, layerBytes: Long): Int {
            val per = max(1L, layerBytes)
            val rTerm = floor((nativeBudget - CLASSIC_BUFFERS * per - RESERVE_BYTES) / per).toLong()
            return max(v19Cap(javaMax, per).toLong(), rTerm).coerceAtMost(LayerBudget.MAX_LAYERS_GUARD.toLong()).toInt()
        }
    }
}
