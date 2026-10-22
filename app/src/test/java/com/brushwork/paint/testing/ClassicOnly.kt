package com.brushwork.paint.testing

/**
 * v1.10 F1 (design 2.16 and 3.3): JUnit 4 category of the tests that pin a fact true only at the
 * CLASSIC engine level, for example `layer.bitmap === x` after a layer switch (at TILES and FULL
 * the inactive layer parks, so its bitmap is a new one when it is read again). Such a test class
 * or method carries `@Category(ClassicOnly::class)` with a one-line comment saying which Classic
 * fact it pins. `unit` runs it; `unit-full` (`-PunitFull`) excludes it through Gradle's
 * `excludeCategories("com.brushwork.paint.testing.ClassicOnly")`. Never a way to hide an engine
 * bug: a test that fails at FULL for any other reason is fixed, or listed with its owner area in
 * `app/src/test/unit-full-backlog.txt`.
 */
interface ClassicOnly
