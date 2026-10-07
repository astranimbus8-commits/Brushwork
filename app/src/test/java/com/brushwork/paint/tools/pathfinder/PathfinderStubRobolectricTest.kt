package com.brushwork.paint.tools.pathfinder

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.StubToolFixtures
import com.brushwork.paint.tools.ToolId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 F5 (design §4.6): the Pathfinder stub keeps v1.6 behaviour on `main`: the tool leaves no
 * trace on any layer kind. Area G deletes or rewrites this test when it fills the stub
 * (`StubToolsRobolectricTest` must keep passing with the real tool).
 */
@RunWith(RobolectricTestRunner::class)
class PathfinderStubRobolectricTest {
    @Test
    fun thePathfinderToolLeavesNoTrace() {
        val c = Smoke.controller(RuntimeEnvironment.getApplication())
        StubToolFixtures.assertLeavesNoTrace(c, ToolId.PATHFINDER, StubToolFixtures.everyKind(c))
    }
}
