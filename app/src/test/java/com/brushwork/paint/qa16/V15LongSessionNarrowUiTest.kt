package com.brushwork.paint.qa16

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [V15LongSessionUiTest] on a small 360 dp phone (360 x 640 dp), where layout matters: the v1.5
 * project opens, its text and curve reopen, and ✓ / ✕ stay whole and on screen (portrait and
 * landscape) through the whole mixed session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.v15sessionnarrowsandbox"])
class V15LongSessionNarrowUiTest : V15LongSessionUiTest() {
    override val shotName: String = "v15-long-session-360dp.png"
}
