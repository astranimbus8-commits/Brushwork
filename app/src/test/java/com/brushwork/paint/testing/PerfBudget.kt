package com.brushwork.paint.testing

/**
 * Time budgets of performance guard tests. The budgets are tuned on a developer desktop; the
 * shared CI runners (GitHub Actions sets CI=true) are several times slower under Robolectric, so
 * there every budget is multiplied by [CI_FACTOR]. The guards still catch order-of-magnitude
 * regressions there.
 */
object PerfBudget {
    const val CI_FACTOR = 4.0

    val factor: Double = if (System.getenv("CI").isNullOrEmpty()) 1.0 else CI_FACTOR

    /** [desktopMs] scaled for the machine the tests run on. */
    fun ms(desktopMs: Double): Double = desktopMs * factor
}
