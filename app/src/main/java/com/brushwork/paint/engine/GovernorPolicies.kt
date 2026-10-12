package com.brushwork.paint.engine

/**
 * v1.9 (design 2.4.3; owner A7b): the [GovernorPolicy] the app runs with, set by
 * `BrushworkApp.onCreate` as [MemoryGovernor.policy]. The foundation's is v1.8's behaviour
 * ([MemoryGovernor.DefaultPolicy]); A7b returns its own policy here.
 */
object GovernorPolicies {
    /** The policy for this run of the app. */
    fun current(): GovernorPolicy = MemoryGovernor.DefaultPolicy
}
