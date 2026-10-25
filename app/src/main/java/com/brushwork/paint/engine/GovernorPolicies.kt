package com.brushwork.paint.engine

/**
 * v1.9 (design 2.4.3; owner A7b): the [GovernorPolicy] the app runs with, set by
 * `BrushworkApp.onCreate` as [MemoryGovernor.policy]. The foundation's was v1.8's behaviour
 * ([MemoryGovernor.DefaultPolicy]); since A7b step 1 it was [GovernorPolicyV19]. v1.10 (A7 step 2):
 * [GovernorPolicyV110], v1.9's trims and admissions with the undo trim measured against R.
 */
object GovernorPolicies {
    /** The policy for this run of the app. */
    fun current(): GovernorPolicy = GovernorPolicyV110
}
