package com.brushwork.paint.qa17

import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa17.Qa17FolderRows.BlendForm
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerWindowTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 (item 8, §3.8): rows four folders deep in the layer window on the user's 392 dp phone keep
 * their name and every badge whole. The 48 dp indent (where it stops growing) comes out of the
 * thumbnail first (`LayerWindowMetrics.thumbAt`, down to 32 dp; a folder's to its 40 dp target),
 * so a depth-4 row has the room a top-level row has on a 360 dp phone: the number, "100%" over
 * "Normal", the name and the badges, none cut, none over the eye or the ≡ handle.
 *
 * The tree: [Qa17FolderRows.build]. A folder row's "Pass through" never ellipsizes: where one line
 * would, it shows "Pass" over "through" at 11 sp (lead decision #7), the row keeping its height;
 * Folder 1 (depth 0) and Folder 5 (depth 4) both checked (Qa17FoldersNarrow360UiTest: 360 dp).
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.foldersnarrowsandbox"])
class Qa17FoldersNarrowUiTest {

    @Test
    fun depthFourRowsKeepTheirNameAndEveryBadgeWhole() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("depth-4 rows at 392 dp") {
            lateinit var t: Qa17FolderRows.Tree
            val s = h.editor(Smoke.document(256, 192, layers = 1, whiteBottom = true)) { t = Qa17FolderRows.build(it) }
            val c = s.c
            assertEquals(392f, s.widthDp, 1f)
            click("Open layers (active layer ${c.doc.indexOf(t.a) + 1})", exact = true)
            Smoke.pump(1_200)
            settle()
            val bad = mutableListOf<String>()
            fun n(l: Layer) = c.doc.indexOf(l) + 1
            Qa17FolderRows.checkRow(
                s, t.a, 4, "Normal",
                badges = listOf(LayerWindowTags.BADGE_MASK),
                thumbBadges = listOf(LayerLabels.badge(n(t.a), ArrayLabels.BUTTON)),
                bad = bad,
            )
            Qa17FolderRows.checkRow(
                s, t.b, 4, "Normal",
                badges = listOf(LayerWindowTags.BADGE_ALPHA, LayerWindowTags.BADGE_LOCK),
                thumbBadges = listOf(LayerLabels.badge(n(t.b), LayerLabels.VECTOR_BADGE)),
                bad = bad,
            )
            val deep = Qa17FolderRows.checkRow(s, t.f5, 4, FolderLabels.PASS_THROUGH, listOf(LayerWindowTags.BADGE_LOCK), emptyList(), bad, folder = true)
            Qa17FolderRows.checkRow(s, t.inner, 5, "Normal", listOf(LayerWindowTags.BADGE_FOLDER_LOCK), emptyList(), bad)
            Qa17FolderRows.checkRow(s, t.f4, 3, FolderLabels.PASS_THROUGH, emptyList(), emptyList(), bad, folder = true)
            val top = Qa17FolderRows.checkRow(s, t.f1, 0, FolderLabels.PASS_THROUGH, emptyList(), emptyList(), bad, folder = true)
            // Four folders deep the values are too narrow for "Pass through" on one line at 12 sp.
            if (deep != BlendForm.SPLIT) bad += "Folder 5 (depth 4): \"Pass through\" shown $deep, not split"
            println("Qa17FoldersNarrow: Folder 1 (depth 0) $top, Folder 5 (depth 4) $deep")
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
        }
        dog.interrupt()
        h.finish()
    }
}
