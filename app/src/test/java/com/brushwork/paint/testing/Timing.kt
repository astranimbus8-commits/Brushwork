package com.brushwork.paint.testing

/**
 * JUnit 4 category of the tests that time something (v1.8 F0, design §8.5): every such test class
 * carries `@Category(Timing::class)` (`org.junit.experimental.categories.Category`), and its time
 * assertions go through [PerfBudget]'s retrying guards. CI runs the tagged classes on their own,
 * serially, so no second JVM competes with them for the cores (CI2: Gradle's
 * `includeCategories` / `excludeCategories` with `com.brushwork.paint.testing.Timing`).
 * TimingTaggedTest checks that every test file reading a clock is tagged or on its allowlist.
 */
interface Timing
