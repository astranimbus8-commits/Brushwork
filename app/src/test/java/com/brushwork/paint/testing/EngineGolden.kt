package com.brushwork.paint.testing

/**
 * v1.10 F1 (design 2.16 and 6.5): JUnit 4 category of the engine goldens, the tests that build
 * the Flat, Tiled and Blank stores of one document themselves ([TiledGoldenKit]) and compare what
 * each draws. They choose their stores, so the engine level of the run adds nothing: `unit` runs
 * them and `unit-full` (`-PunitFull`) excludes them through Gradle's
 * `excludeCategories("com.brushwork.paint.testing.EngineGolden")`.
 */
interface EngineGolden
