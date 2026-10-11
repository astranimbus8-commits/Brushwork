package com.brushwork.paint.ui.tools.strip

import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (F3, design §4.6): every [ToolId] has a strip builder that `ToolStrips.of` reaches, on a
 * raster layer and on a vector layer. Every tool, the two v1.8 ones too, has its area's converted
 * spec with something to show: an EMPTY spec means the dispatcher fell back (the tool's class and
 * its builder's parameter disagree).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.registrysandbox"])
class StripRegistryTest {
    @Test
    fun everyToolIdHasABuilder() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig().show()
        val bad = mutableListOf<String>()
        try {
            for (vector in listOf(false, true)) for (id in ToolId.entries) {
                val where = "$id on a ${if (vector) "vector" else "raster"} layer"
                rig.select(id, vector)
                if (rig.c.activeToolId != id) {
                    bad += "$where: not selected"
                    continue
                }
                val spec = rig.spec
                if (spec === StripSpec.EMPTY) bad += "$where: EMPTY (the dispatcher fell back)"
                if (spec.items.isEmpty() && spec.hint == null) bad += "$where: nothing to show"
            }
        } finally {
            rig.close()
        }
        assertTrue("strip builders:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
