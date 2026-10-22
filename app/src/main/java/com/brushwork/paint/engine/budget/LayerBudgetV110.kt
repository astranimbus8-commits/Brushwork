package com.brushwork.paint.engine.budget

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19

/**
 * v1.10 (F1, design 2.4 and 2.13; constructor frozen, body A7): the [LayerBudget] of a session
 * (`EngineConfig.openSession`) and of the gallery (`EngineConfig.applyIdle`). The F1 body
 * delegates every member to [LayerBudgetV19]; A7 replaces the delegation with sections 2.4 and
 * 2.13 and keeps delegating whenever `budget.budgets.known` is false (I32).
 */
@Suppress("unused")
class LayerBudgetV110(private val budget: MemoryBudget, private val level: EngineLevel) : LayerBudget by LayerBudgetV19
